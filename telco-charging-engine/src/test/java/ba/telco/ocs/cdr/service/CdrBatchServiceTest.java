package ba.telco.ocs.cdr.service;

import ba.telco.ocs.TestClock;
import ba.telco.ocs.TestFixtures;
import ba.telco.ocs.cdr.entity.BatchStatus;
import ba.telco.ocs.cdr.entity.Cdr;
import ba.telco.ocs.cdr.entity.CdrBatch;
import ba.telco.ocs.cdr.pipeline.CdrPipeline;
import ba.telco.ocs.common.exception.NotFoundException;
import ba.telco.ocs.fraud.service.FraudDetectionService;
import ba.telco.ocs.tariff.entity.ServiceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CdrBatchServiceTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-10-21T10:00:00Z"));
    private final TestFixtures fixtures = new TestFixtures(clock, Duration.ofSeconds(1));
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final CdrPipeline pipeline = new CdrPipeline(new CdrRatingService(fixtures.subscriberService,
            fixtures.tariffService, fixtures.ratingEngine, new UsageAggregator(),
            new FraudDetectionService(fixtures.properties, clock, scheduler)), fixtures.properties);
    private final CdrBatchService service = new CdrBatchService(pipeline, clock, scheduler, fixtures.properties);

    @AfterEach
    void stop() {
        pipeline.stop();
    }

    private static List<Cdr> oneSms() {
        return List.of(new Cdr("r1", "38761000001", ServiceType.SMS, 1, Instant.parse("2026-10-21T10:00:00Z")));
    }

    @Test
    void cleanupIsScheduledEveryMinute() {
        service.scheduleCleanup();

        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(1L), eq(1L), eq(TimeUnit.MINUTES));
    }

    @Test
    void completedBatchesAreKeptUntilTheyExpire() throws Exception {
        fixtures.subscriberService.create("38761000001", "A", "SMART", BigDecimal.ZERO);
        pipeline.start();
        CdrBatch batch = service.submit(oneSms(), 5_000);
        assertThat(batch.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        CdrBatch pending = service.submit(List.of(), 0);

        service.purgeCompleted();
        assertThat(service.get(batch.getId(), 10)).isSameAs(batch);

        clock.advance(Duration.ofHours(2));
        service.purgeCompleted();

        assertThatThrownBy(() -> service.get(batch.getId(), 0)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.get(pending.getId(), 0)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void inProgressBatchesSurviveThePurge() throws Exception {
        CdrBatchService idle = new CdrBatchService(mock(CdrPipeline.class), clock, scheduler, fixtures.properties);
        CdrBatch batch = idle.submit(oneSms(), 0);

        clock.advance(Duration.ofHours(2));
        idle.purgeCompleted();

        assertThat(idle.get(batch.getId(), 0)).isSameAs(batch);
        assertThat(batch.getStatus()).isEqualTo(BatchStatus.IN_PROGRESS);
    }

    @Test
    void batchCanBeReadWithoutWaiting() throws Exception {
        CdrBatch batch = service.submit(oneSms(), 0);

        assertThat(service.get(batch.getId(), 0)).isSameAs(batch);
    }

    @Test
    void failingPurgeDoesNotPropagate() {
        Clock broken = mock(Clock.class);
        when(broken.instant()).thenThrow(new IllegalStateException("clock down"));
        CdrBatchService failing = new CdrBatchService(pipeline, broken, scheduler, fixtures.properties);

        failing.purgeCompleted();

        assertThatThrownBy(() -> failing.get("x", 0)).isInstanceOf(NotFoundException.class);
    }
}
