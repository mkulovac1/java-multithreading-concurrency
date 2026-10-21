package ba.telco.ocs.fraud.service;

import ba.telco.ocs.TestClock;
import ba.telco.ocs.TestFixtures;
import ba.telco.ocs.cdr.entity.Cdr;
import ba.telco.ocs.tariff.entity.ServiceType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FraudHousekeepingTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-10-21T10:00:00Z"));
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final FraudDetectionService service = new FraudDetectionService(
            TestFixtures.properties(Duration.ofSeconds(1)), clock, scheduler);

    private Cdr cdr(String msisdn, Instant at) {
        return new Cdr("r", msisdn, ServiceType.SMS, 1, at);
    }

    @Test
    void cleanupIsScheduledEveryMinute() {
        service.scheduleCleanup();

        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(1L), eq(1L), eq(TimeUnit.MINUTES));
    }

    @Test
    void staleWindowsArePurgedAndFreshOnesKept() {
        service.inspect(cdr("38761000001", clock.instant().minus(Duration.ofMinutes(30))));
        service.inspect(cdr("38761000002", clock.instant()));
        assertThat(service.stats().trackedSubscribers()).isEqualTo(2);

        service.purgeStaleWindows();

        assertThat(service.stats().trackedSubscribers()).isEqualTo(1);
    }

    @Test
    void failingCleanupDoesNotPropagate() {
        Clock broken = mock(Clock.class);
        when(broken.instant()).thenThrow(new IllegalStateException("clock down"));
        FraudDetectionService failing = new FraudDetectionService(
                TestFixtures.properties(Duration.ofSeconds(1)), broken, scheduler);

        failing.purgeStaleWindows();

        assertThat(failing.stats().trackedSubscribers()).isZero();
    }
}
