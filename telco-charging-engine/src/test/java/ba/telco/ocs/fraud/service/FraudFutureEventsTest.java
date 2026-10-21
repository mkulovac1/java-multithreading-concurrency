package ba.telco.ocs.fraud.service;

import ba.telco.ocs.TestClock;
import ba.telco.ocs.TestFixtures;
import ba.telco.ocs.cdr.entity.Cdr;
import ba.telco.ocs.tariff.entity.ServiceType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FraudFutureEventsTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-10-21T10:00:30Z"));
    private final FraudDetectionService service = new FraudDetectionService(
            TestFixtures.properties(Duration.ofSeconds(1)), clock, mock(ScheduledExecutorService.class));

    private Cdr cdr(Instant at) {
        return new Cdr("r", "38761000001", ServiceType.SMS, 1, at);
    }

    @Test
    void oneCdrDatedInTheFarFutureDoesNotBlindDetectionForTheSubscriber() {
        service.inspect(cdr(Instant.parse("2126-01-01T00:00:00Z")));

        for (int i = 0; i < 31; i++) {
            service.inspect(cdr(clock.instant()));
        }

        assertThat(service.getAlerts(10)).hasSize(1);
    }

    @Test
    void futureWindowDoesNotLeakForever() {
        service.inspect(cdr(Instant.parse("2126-01-01T00:00:00Z")));
        clock.advance(Duration.ofMinutes(30));

        service.purgeStaleWindows();

        assertThat(service.stats().trackedSubscribers()).isZero();
    }
}
