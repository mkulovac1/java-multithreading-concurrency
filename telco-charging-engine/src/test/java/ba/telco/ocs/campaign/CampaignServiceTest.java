package ba.telco.ocs.campaign;

import ba.telco.ocs.TestFixtures;
import ba.telco.ocs.campaign.entity.Campaign;
import ba.telco.ocs.campaign.entity.CampaignStatus;
import ba.telco.ocs.campaign.repository.CampaignRepository;
import ba.telco.ocs.campaign.service.CampaignService;
import ba.telco.ocs.campaign.smsc.SmscGateway;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignServiceTest {

    private final TestFixtures fixtures = new TestFixtures();

    @Test
    void sendsEveryMessageWithoutExceedingTheSmscWindow() throws Exception {
        CountingGateway gateway = new CountingGateway(Duration.ofMillis(5), 7);
        CampaignService service = new CampaignService(new CampaignRepository(), gateway, fixtures.clock, fixtures.properties);

        Campaign campaign = service.start("Promo", "Popust 50%!", recipients(2_000));
        awaitFinished(campaign);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.COMPLETED);
        assertThat(campaign.getSent() + campaign.getFailed()).isEqualTo(2_000);
        assertThat(campaign.getFailed()).isEqualTo(2_000 / 7);
        assertThat(gateway.maxInFlight.get()).isLessThanOrEqualTo(10);
        assertThat(gateway.maxInFlight.get()).isEqualTo(10);
    }

    @Test
    void cancelInterruptsInFlightSendsAndSkipsTheRest() throws Exception {
        CountingGateway gateway = new CountingGateway(Duration.ofSeconds(10), 0);
        CampaignService service = new CampaignService(new CampaignRepository(), gateway, fixtures.clock, fixtures.properties);
        Campaign campaign = service.start("Spor", "Test", recipients(500));
        while (gateway.inFlight.get() < 10) {
            Thread.sleep(5);
        }

        service.cancel(campaign.getId());
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (campaign.getSkipped() + campaign.getSent() + campaign.getFailed() < 500 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.CANCELLED);
        assertThat(campaign.getSent()).isZero();
        assertThat(campaign.getSkipped()).isEqualTo(500);
    }

    @Test
    void cancelImmediatelyAfterStartStillAccountsForEveryRecipient() throws Exception {
        CountingGateway gateway = new CountingGateway(Duration.ofMillis(1), 0);
        CampaignService service = new CampaignService(new CampaignRepository(), gateway, fixtures.clock, fixtures.properties);
        List<Campaign> campaigns = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            Campaign campaign = service.start("Instant cancel " + i, "Test", recipients(5));
            service.cancel(campaign.getId());
            campaigns.add(campaign);
        }

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (service.stats().runningCampaigns() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertThat(service.stats().runningCampaigns()).isZero();
        assertThat(campaigns).allSatisfy(campaign ->
                assertThat(campaign.getSent() + campaign.getFailed() + campaign.getSkipped()).isEqualTo(5));
    }

    private static List<String> recipients(int count) {
        return IntStream.range(0, count).mapToObj(i -> String.valueOf(38761000000L + i)).toList();
    }

    private static void awaitFinished(Campaign campaign) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (campaign.getStatus() == CampaignStatus.RUNNING && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    @RequiredArgsConstructor
    static final class CountingGateway implements SmscGateway {

        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        private final Duration latency;
        private final int failEvery;

        @Override
        public SubmitResult submit(String msisdn, String text) throws InterruptedException {
            int current = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(latency);
                boolean fail = failEvery > 0 && calls.incrementAndGet() % failEvery == 0;
                return new SubmitResult(!fail, fail ? null : "id", fail ? "ERR" : null);
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }
}
