package ba.telco.ocs.fraud;

import ba.telco.ocs.TestClock;
import ba.telco.ocs.TestFixtures;
import ba.telco.ocs.cdr.entity.Cdr;
import ba.telco.ocs.fraud.entity.FraudAlert;
import ba.telco.ocs.fraud.service.FraudDetectionService;
import ba.telco.ocs.tariff.entity.ServiceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;

import static ba.telco.ocs.TestFixtures.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

class FraudDetectionServiceTest {

    private final TestFixtures fixtures = new TestFixtures();
    private final FraudDetectionService service = new FraudDetectionService(fixtures.properties,
            new TestClock(Instant.parse("2026-10-22T00:00:00Z")), Executors.newSingleThreadScheduledExecutor());
    private final Instant minute = Instant.parse("2026-10-21T10:00:00Z");

    @Test
    void noAlertAtThreshold() {
        for (int i = 0; i < 30; i++) {
            service.inspect(cdr("38761000001", minute.plusSeconds(i)));
        }
        assertThat(service.getAlerts(10)).isEmpty();
    }

    @Test
    void manyConcurrentEventsRaiseExactlyOneAlertPerWindow() throws Exception {
        runConcurrently(16, index -> {
            for (int i = 0; i < 100; i++) {
                service.inspect(cdr("38761000002", minute.plusMillis(i)));
            }
        });

        List<FraudAlert> alerts = service.getAlerts(10);
        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().eventsInWindow()).isEqualTo(31);
        assertThat(service.stats().alertsRaised()).isEqualTo(1);
    }

    @Test
    void newWindowCanRaiseNewAlertAndAlertsAreNewestFirst() {
        for (int i = 0; i < 31; i++) {
            service.inspect(cdr("38761000003", minute.plusSeconds(1)));
        }
        for (int i = 0; i < 31; i++) {
            service.inspect(cdr("38761000003", minute.plusSeconds(61)));
        }

        List<FraudAlert> alerts = service.getAlerts(10);
        assertThat(alerts).hasSize(2);
        assertThat(alerts.get(0).windowStart()).isAfter(alerts.get(1).windowStart());
        assertThat(service.acknowledgeAll()).hasSize(2);
        assertThat(service.getAlerts(10)).isEmpty();
    }

    @Test
    void lateEventsForClosedWindowAreIgnored() {
        for (int i = 0; i < 31; i++) {
            service.inspect(cdr("38761000004", minute.plusSeconds(120)));
        }
        service.acknowledgeAll();
        for (int i = 0; i < 100; i++) {
            service.inspect(cdr("38761000004", minute));
        }
        assertThat(service.getAlerts(10)).isEmpty();
    }

    private static Cdr cdr(String msisdn, Instant time) {
        return new Cdr("r", msisdn, ServiceType.VOICE, 10, time);
    }
}
