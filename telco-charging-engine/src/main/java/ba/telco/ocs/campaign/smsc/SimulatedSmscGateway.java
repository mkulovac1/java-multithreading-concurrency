package ba.telco.ocs.campaign.smsc;

import ba.telco.ocs.campaign.service.CampaignContext;
import ba.telco.ocs.common.config.TelcoProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class SimulatedSmscGateway implements SmscGateway {

    private final long minLatencyMillis;
    private final long maxLatencyMillis;
    private final double failureRate;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    public SimulatedSmscGateway(TelcoProperties properties) {
        this.minLatencyMillis = properties.campaign().smscMinLatency().toMillis();
        this.maxLatencyMillis = Math.max(minLatencyMillis, properties.campaign().smscMaxLatency().toMillis());
        this.failureRate = properties.campaign().smscFailureRate();
    }

    @Override
    public SubmitResult submit(String msisdn, String text) throws InterruptedException {
        int current = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(current, Math::max);
        try {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Thread.sleep(random.nextLong(minLatencyMillis, maxLatencyMillis + 1));
            if (random.nextDouble() < failureRate) {
                log.debug("[campaign={}] submit_sm to {} failed", CampaignContext.currentCampaignId(), msisdn);
                return new SubmitResult(false, null, "ESME_RTHROTTLED");
            }
            return new SubmitResult(true, UUID.randomUUID().toString(), null);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    public int getInFlight() {
        return inFlight.get();
    }

    public int getMaxInFlight() {
        return maxInFlight.get();
    }
}
