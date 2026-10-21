package ba.telco.ocs.campaign.service;

import ba.telco.ocs.campaign.entity.Campaign;
import ba.telco.ocs.campaign.repository.CampaignRepository;
import ba.telco.ocs.campaign.smsc.SmscGateway;
import ba.telco.ocs.common.config.TelcoProperties;
import ba.telco.ocs.common.exception.BadRequestException;
import ba.telco.ocs.common.exception.InvalidStateException;
import ba.telco.ocs.common.exception.NotFoundException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;

@Service
@Slf4j
public class CampaignService {

    private final CampaignRepository campaignRepository;
    private final SmscGateway smscGateway;
    private final Clock clock;
    private final int maxRecipients;
    private final int windowSize;
    private final Semaphore smscWindow;
    private final ThreadFactory coordinatorFactory = Thread.ofVirtual().name("campaign-coordinator-", 1).factory();
    private final Map<String, Thread> runningCampaigns = new ConcurrentHashMap<>();

    public CampaignService(CampaignRepository campaignRepository, SmscGateway smscGateway, Clock clock,
                           TelcoProperties properties) {
        this.campaignRepository = campaignRepository;
        this.smscGateway = smscGateway;
        this.clock = clock;
        this.maxRecipients = properties.campaign().maxRecipients();
        this.windowSize = properties.campaign().smscWindowSize();
        this.smscWindow = new Semaphore(windowSize, true);
    }

    public Campaign start(String name, String message, List<String> recipients) {
        if (recipients.size() > maxRecipients) {
            throw new BadRequestException("A campaign can have at most " + maxRecipients + " recipients.");
        }
        Campaign campaign = new Campaign(UUID.randomUUID().toString(), name, message, recipients, Instant.now(clock));
        campaignRepository.save(campaign);
        Thread coordinator = coordinatorFactory.newThread(() -> run(campaign));
        synchronized (runningCampaigns) {
            runningCampaigns.put(campaign.getId(), coordinator);
            coordinator.start();
        }
        return campaign;
    }

    private void run(Campaign campaign) {
        try (ExecutorService perMessage = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String msisdn : campaign.getRecipients()) {
                if (campaign.isCancelled() || Thread.currentThread().isInterrupted()) {
                    campaign.recordSkipped();
                    continue;
                }
                perMessage.submit(() -> ScopedValue.where(CampaignContext.CAMPAIGN_ID, campaign.getId())
                        .run(() -> sendOne(campaign, msisdn)));
            }
        } finally {
            if (campaign.complete(Instant.now(clock))) {
                log.info("Campaign {} completed: sent={}, failed={}, skipped={}", campaign.getId(),
                        campaign.getSent(), campaign.getFailed(), campaign.getSkipped());
            }
            synchronized (runningCampaigns) {
                runningCampaigns.remove(campaign.getId());
            }
        }
    }

    private void sendOne(Campaign campaign, String msisdn) {
        if (campaign.isCancelled()) {
            campaign.recordSkipped();
            return;
        }
        try {
            smscWindow.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            campaign.recordSkipped();
            return;
        }
        try {
            SmscGateway.SubmitResult result = smscGateway.submit(msisdn, campaign.getMessage());
            if (result.accepted()) {
                campaign.recordSent();
            } else {
                campaign.recordFailed();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            campaign.recordSkipped();
        } catch (RuntimeException e) {
            log.warn("[campaign={}] SMSC error for {}: {}", CampaignContext.currentCampaignId(), msisdn, e.getMessage());
            campaign.recordFailed();
        } finally {
            smscWindow.release();
        }
    }

    public Campaign cancel(String campaignId) {
        Campaign campaign = get(campaignId);
        if (!campaign.cancel(Instant.now(clock))) {
            throw new InvalidStateException("Campaign has already finished (status: " + campaign.getStatus() + ").");
        }
        Thread coordinator = runningCampaigns.get(campaignId);
        if (coordinator != null) {
            coordinator.interrupt();
        }
        return campaign;
    }

    public Campaign get(String campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new NotFoundException("Campaign " + campaignId + " does not exist."));
    }

    public List<Campaign> getAll() {
        return campaignRepository.findAll();
    }

    public CampaignStats stats() {
        return new CampaignStats(runningCampaigns.size(), windowSize, smscWindow.availablePermits(),
                smscWindow.getQueueLength());
    }

    @PreDestroy
    void shutdown() {
        runningCampaigns.values().forEach(Thread::interrupt);
    }

    public record CampaignStats(int runningCampaigns, int smscWindowSize, int smscWindowAvailable,
                                int messagesWaitingForWindow) {
    }
}
