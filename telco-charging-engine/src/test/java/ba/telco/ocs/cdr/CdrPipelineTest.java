package ba.telco.ocs.cdr;

import ba.telco.ocs.TestFixtures;
import ba.telco.ocs.cdr.entity.BatchStatus;
import ba.telco.ocs.cdr.entity.Cdr;
import ba.telco.ocs.cdr.entity.CdrBatch;
import ba.telco.ocs.cdr.pipeline.CdrPipeline;
import ba.telco.ocs.cdr.service.CdrRatingService;
import ba.telco.ocs.cdr.service.UsageAggregator;
import ba.telco.ocs.fraud.service.FraudDetectionService;
import ba.telco.ocs.tariff.entity.ServiceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static ba.telco.ocs.TestFixtures.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

class CdrPipelineTest {

    private final TestFixtures fixtures = new TestFixtures();
    private final UsageAggregator usageAggregator = new UsageAggregator();
    private final FraudDetectionService fraud = new FraudDetectionService(fixtures.properties, fixtures.clock,
            Executors.newSingleThreadScheduledExecutor());
    private final CdrRatingService ratingService = new CdrRatingService(fixtures.subscriberService,
            fixtures.tariffService, fixtures.ratingEngine, usageAggregator, fraud);
    private final CdrPipeline pipeline = new CdrPipeline(ratingService, fixtures.properties);

    @AfterEach
    void stop() {
        pipeline.stop();
    }

    @Test
    void ratesEveryRecordFromConcurrentProducers() throws Exception {
        fixtures.subscriberService.create("38761000001", "A", "SMART", BigDecimal.ZERO);
        pipeline.start();
        int producers = 8;
        int perBatch = 500;
        List<CdrBatch> batches = new ArrayList<>();
        for (int i = 0; i < producers; i++) {
            batches.add(new CdrBatch("b" + i, perBatch, fixtures.clock));
        }

        runConcurrently(producers, index -> pipeline.submit(batches.get(index), sms("38761000001", perBatch, index)));

        for (CdrBatch batch : batches) {
            assertThat(batch.awaitCompletion(5_000)).isTrue();
            assertThat(batch.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(batch.getRated()).isEqualTo(perBatch);
            assertThat(batch.getRevenue()).isEqualByComparingTo("25.00");
        }
        var usage = usageAggregator.find("38761000001").orElseThrow();
        assertThat(usage.getSmsCount()).isEqualTo((long) producers * perBatch);
        assertThat(usage.getRevenue()).isEqualByComparingTo("200.00");
    }

    @Test
    void unknownSubscriberIsReportedAsFailureNotLost() throws Exception {
        pipeline.start();
        CdrBatch batch = new CdrBatch("b", 3, fixtures.clock);

        pipeline.submit(batch, sms("38769999999", 3, 0));

        assertThat(batch.awaitCompletion(5_000)).isTrue();
        assertThat(batch.getFailed()).isEqualTo(3);
        assertThat(batch.getFailureSamples()).hasSize(3).allMatch(s -> s.contains("does not exist"));
    }

    @Test
    void fullBufferRejectsInsteadOfBlockingForever() throws Exception {
        var props = TestFixtures.properties(Duration.ofSeconds(1));
        CdrPipeline stalled = new CdrPipeline(ratingService, props);
        stalled.start();
        stalled.stop();
        CdrBatch batch = new CdrBatch("b", 10, fixtures.clock);

        stalled.submit(batch, sms("38761000001", 10, 0));

        assertThat(batch.getRejected()).isEqualTo(10);
        assertThat(batch.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void stopDrainsBufferedRecordsBeforeWorkersExit() throws Exception {
        fixtures.subscriberService.create("38761000002", "B", "SMART", BigDecimal.ZERO);
        pipeline.start();
        CdrBatch batch = new CdrBatch("b", 900, fixtures.clock);
        pipeline.submit(batch, sms("38761000002", 900, 0));

        pipeline.stop();

        assertThat(batch.getRated()).isEqualTo(900);
        assertThat(pipeline.isRunning()).isFalse();
    }

    private static List<Cdr> sms(String msisdn, int count, int producer) {
        Instant base = Instant.parse("2026-10-21T10:00:00Z");
        List<Cdr> cdrs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            cdrs.add(new Cdr("p" + producer + "-" + i, msisdn, ServiceType.SMS, 1, base.plusSeconds(i * 10L)));
        }
        return cdrs;
    }
}
