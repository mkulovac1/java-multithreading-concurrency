package ba.telco.ocs.cdr;

import ba.telco.ocs.TestFixtures;
import ba.telco.ocs.cdr.entity.BatchStatus;
import ba.telco.ocs.cdr.entity.Cdr;
import ba.telco.ocs.cdr.entity.CdrBatch;
import ba.telco.ocs.cdr.pipeline.CdrPipeline;
import ba.telco.ocs.cdr.service.CdrRatingService;
import ba.telco.ocs.common.config.TelcoProperties;
import ba.telco.ocs.tariff.entity.ServiceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CdrPipelineEdgeCasesTest {

    private final TestFixtures fixtures = new TestFixtures();
    private final CdrRatingService ratingService = mock(CdrRatingService.class);
    private CdrPipeline pipeline;

    @AfterEach
    void stop() {
        if (pipeline != null) {
            pipeline.stop();
        }
    }

    private CdrPipeline pipeline(int capacity, int workers, Duration offerTimeout) {
        TelcoProperties base = fixtures.properties;
        TelcoProperties properties = new TelcoProperties(base.charging(),
                new TelcoProperties.Pipeline(capacity, workers, offerTimeout, Duration.ofHours(1)),
                base.campaign(), base.fraud(), base.seed());
        pipeline = new CdrPipeline(ratingService, properties);
        return pipeline;
    }

    private static List<Cdr> cdrs(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new Cdr("r" + i, "38761000001", ServiceType.SMS, 1, Instant.now())).toList();
    }

    @Test
    void notStartedPipelineRejectsEverything() {
        CdrPipeline idle = pipeline(10, 1, Duration.ofMillis(10));
        CdrBatch batch = new CdrBatch("b", 3, fixtures.clock);

        idle.submit(batch, cdrs(3));

        assertThat(batch.getRejected()).isEqualTo(3);
        assertThat(batch.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(idle.stats().rejected()).isEqualTo(3);
        assertThat(idle.isRunning()).isFalse();
        idle.stop();
    }

    @Test
    void lifecycleCallsAreIdempotentAndOrdered() {
        CdrPipeline p = pipeline(10, 1, Duration.ofMillis(10));

        p.start();
        p.start();
        assertThat(p.isRunning()).isTrue();
        assertThat(p.getPhase()).isLessThan(org.springframework.context.SmartLifecycle.DEFAULT_PHASE);
        assertThat(p.stats().workers()).isEqualTo(1);

        p.stop();
        p.stop();
        assertThat(p.isRunning()).isFalse();
    }

    @Test
    void interruptedShutdownStillInterruptsTheWorkers() {
        CdrPipeline p = pipeline(10, 2, Duration.ofMillis(100));
        p.start();

        Thread.currentThread().interrupt();
        try {
            p.stop();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        assertThat(p.isRunning()).isFalse();
    }

    @Test
    void saturatedBufferRejectsTheRestOfTheBatchQuickly() throws Exception {
        CountDownLatch blocker = new CountDownLatch(1);
        when(ratingService.rate(any())).thenAnswer(invocation -> {
            blocker.await();
            return BigDecimal.ZERO;
        });
        CdrPipeline p = pipeline(1, 1, Duration.ofMillis(20));
        p.start();
        CdrBatch batch = new CdrBatch("b", 6, fixtures.clock);

        p.submit(batch, cdrs(6));
        blocker.countDown();

        assertThat(batch.awaitCompletion(5_000)).isTrue();
        assertThat(batch.getRejected()).isGreaterThanOrEqualTo(3);
        assertThat(batch.getRated() + batch.getRejected()).isEqualTo(6);
    }

    @Test
    void interruptedProducerRejectsInsteadOfBlocking() {
        CdrPipeline p = pipeline(10, 1, Duration.ofSeconds(5));
        p.start();
        CdrBatch batch = new CdrBatch("b", 2, fixtures.clock);

        Thread.currentThread().interrupt();
        try {
            p.submit(batch, cdrs(2));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        assertThat(batch.getRejected()).isEqualTo(2);
    }

    @Test
    void unexpectedRatingErrorsAreRecordedAsInternalFailures() throws Exception {
        when(ratingService.rate(any())).thenThrow(new IllegalStateException("bug"));
        CdrPipeline p = pipeline(10, 1, Duration.ofMillis(100));
        p.start();
        CdrBatch batch = new CdrBatch("b", 2, fixtures.clock);

        p.submit(batch, cdrs(2));

        assertThat(batch.awaitCompletion(5_000)).isTrue();
        assertThat(batch.getFailed()).isEqualTo(2);
        assertThat(batch.getFailureSamples()).allMatch(sample -> sample.endsWith("Internal rating error."));
        assertThat(p.stats().failed()).isEqualTo(2);
    }

    @Test
    void interruptedWorkerStopsWithoutKillingThePipeline() throws Exception {
        CdrPipeline p = pipeline(10, 1, Duration.ofMillis(100));
        p.start();
        Thread worker = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("cdr-rater-")).filter(Thread::isAlive).findFirst().orElseThrow();

        worker.interrupt();
        worker.join(5_000);

        assertThat(worker.isAlive()).isFalse();
        p.stop();
    }

    @Test
    void batchWithManyFailuresKeepsOnlyTwentySamples() {
        CdrBatch batch = new CdrBatch("b", 30, fixtures.clock);

        for (int i = 0; i < 30; i++) {
            batch.markFailed("r" + i, "bad");
        }

        assertThat(batch.getFailed()).isEqualTo(30);
        assertThat(batch.getFailureSamples()).hasSize(20);
        assertThat(batch.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void emptyBatchIsCompletedImmediately() {
        CdrBatch batch = new CdrBatch("b", 0, fixtures.clock);

        assertThat(batch.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(batch.getCompletedAt()).isEqualTo(batch.getCreatedAt());
        assertThat(batch.getAccepted()).isZero();
    }
}
