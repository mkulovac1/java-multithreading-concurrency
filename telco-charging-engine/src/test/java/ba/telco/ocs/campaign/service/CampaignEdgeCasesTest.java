package ba.telco.ocs.campaign.service;

import ba.telco.ocs.TestFixtures;
import ba.telco.ocs.campaign.dto.CreateCampaignRequest;
import ba.telco.ocs.campaign.dto.NumberRangeRequest;
import ba.telco.ocs.campaign.entity.Campaign;
import ba.telco.ocs.campaign.entity.CampaignStatus;
import ba.telco.ocs.campaign.mapper.CampaignMapper;
import ba.telco.ocs.campaign.repository.CampaignRepository;
import ba.telco.ocs.campaign.smsc.SimulatedSmscGateway;
import ba.telco.ocs.campaign.smsc.SmscGateway;
import ba.telco.ocs.common.config.TelcoProperties;
import ba.telco.ocs.common.exception.BadRequestException;
import ba.telco.ocs.common.exception.InvalidStateException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignEdgeCasesTest {

    private final TestFixtures fixtures = new TestFixtures();

    private static List<String> recipients(int count) {
        return IntStream.range(0, count).mapToObj(i -> String.valueOf(38761000000L + i)).toList();
    }

    private static void awaitFinished(Campaign campaign) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (campaign.getStatus() == CampaignStatus.RUNNING && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    private CampaignService service(SmscGateway gateway) {
        return new CampaignService(new CampaignRepository(), gateway, fixtures.clock, fixtures.properties);
    }

    @Test
    void campaignsAboveTheRecipientLimitAreRejected() {
        CampaignService service = service((msisdn, text) -> new SmscGateway.SubmitResult(true, "id", null));

        assertThatThrownBy(() -> service.start("Big", "Hi", recipients(100_001)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void gatewayErrorsCountAsFailedMessages() throws Exception {
        CampaignService service = service((msisdn, text) -> {
            throw new IllegalStateException("smsc down");
        });

        Campaign campaign = service.start("Err", "Hi", recipients(5));
        awaitFinished(campaign);

        assertThat(campaign.getFailed()).isEqualTo(5);
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.COMPLETED);
        assertThatThrownBy(() -> service.cancel(campaign.getId())).isInstanceOf(InvalidStateException.class);
    }

    @Test
    void interruptedSendIsSkippedNotFailed() throws Exception {
        CampaignService service = service((msisdn, text) -> {
            throw new InterruptedException();
        });

        Campaign campaign = service.start("Int", "Hi", recipients(3));
        awaitFinished(campaign);

        assertThat(campaign.getSkipped()).isEqualTo(3);
    }

    @Test
    void statsAndShutdownReflectRunningCampaigns() throws Exception {
        CampaignService service = service((msisdn, text) -> {
            Thread.sleep(10_000);
            return new SmscGateway.SubmitResult(true, "id", null);
        });
        Campaign campaign = service.start("Slow", "Hi", recipients(3));
        while (service.stats().smscWindowAvailable() == service.stats().smscWindowSize()) {
            Thread.sleep(5);
        }

        assertThat(service.stats().runningCampaigns()).isEqualTo(1);
        assertThat(service.getAll()).containsExactly(campaign);
        service.shutdown();
        awaitFinished(campaign);

        assertThat(campaign.getSkipped()).isEqualTo(3);
    }

    @Test
    void contextIsDashOutsideACampaignAndBoundInside() throws Exception {
        assertThat(CampaignContext.currentCampaignId()).isEqualTo("-");

        String inside = ScopedValue.where(CampaignContext.CAMPAIGN_ID, "c-1").call(CampaignContext::currentCampaignId);

        assertThat(inside).isEqualTo("c-1");
    }

    @Test
    void numberRangesThatChangeLengthAreRejected() {
        CampaignMapper mapper = new CampaignMapper();
        CreateCampaignRequest request = new CreateCampaignRequest("N", "M", null,
                new NumberRangeRequest("99999999999", 5));

        assertThatThrownBy(() -> mapper.toRecipients(request)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void emptyRecipientListFallsBackToTheNumberRange() {
        CampaignMapper mapper = new CampaignMapper();

        assertThat(mapper.toRecipients(new CreateCampaignRequest("N", "M", List.of(),
                new NumberRangeRequest("38761000001", 2)))).containsExactly("38761000001", "38761000002");
        assertThatThrownBy(() -> mapper.toRecipients(new CreateCampaignRequest("N", "M", List.of(), null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void cancelBeforeTheCoordinatorIsRegisteredStillSkipsEveryMessage() throws Exception {
        CampaignService[] holder = new CampaignService[1];
        CampaignRepository cancellingRepository = new CampaignRepository() {
            @Override
            public void save(Campaign campaign) {
                super.save(campaign);
                holder[0].cancel(campaign.getId());
            }
        };
        holder[0] = new CampaignService(cancellingRepository,
                (msisdn, text) -> new SmscGateway.SubmitResult(true, "id", null), fixtures.clock, fixtures.properties);

        Campaign campaign = holder[0].start("Early", "Hi", recipients(4));
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (holder[0].stats().runningCampaigns() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.CANCELLED);
        assertThat(campaign.getSkipped()).isEqualTo(4);
        assertThat(campaign.getSent()).isZero();
    }

    @Test
    void emptyCampaignIsFullyProgressed() {
        Campaign campaign = new Campaign("id", "n", "m", List.of(), java.time.Instant.now());

        assertThat(new CampaignMapper().toResponse(campaign).progressPercent()).isEqualTo(100.0);
    }

    @Test
    void simulatedGatewayReportsThrottlingAndSuccess() throws Exception {
        TelcoProperties base = fixtures.properties;
        SimulatedSmscGateway failing = new SimulatedSmscGateway(new TelcoProperties(base.charging(), base.pipeline(),
                new TelcoProperties.Campaign(10, Duration.ofMillis(1), Duration.ofMillis(2), 1.0, 10),
                base.fraud(), base.seed()));
        SimulatedSmscGateway reliable = new SimulatedSmscGateway(new TelcoProperties(base.charging(), base.pipeline(),
                new TelcoProperties.Campaign(10, Duration.ofMillis(3), Duration.ofMillis(1), 0.0, 10),
                base.fraud(), base.seed()));

        SmscGateway.SubmitResult rejected = failing.submit("38761000001", "Hi");
        SmscGateway.SubmitResult accepted = reliable.submit("38761000001", "Hi");

        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.errorCode()).isEqualTo("ESME_RTHROTTLED");
        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.messageId()).isNotBlank();
        assertThat(reliable.getInFlight()).isZero();
        assertThat(reliable.getMaxInFlight()).isEqualTo(1);
    }
}
