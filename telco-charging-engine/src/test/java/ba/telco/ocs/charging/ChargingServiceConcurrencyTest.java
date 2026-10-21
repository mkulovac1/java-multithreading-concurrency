package ba.telco.ocs.charging;

import ba.telco.ocs.TestFixtures;
import ba.telco.ocs.charging.dto.ChargingSessionResponse;
import ba.telco.ocs.charging.entity.ChargingSession;
import ba.telco.ocs.charging.entity.SessionStatus;
import ba.telco.ocs.charging.mapper.ChargingSessionMapper;
import ba.telco.ocs.charging.repository.ChargingSessionRepository;
import ba.telco.ocs.charging.service.ChargingService;
import ba.telco.ocs.common.exception.BadRequestException;
import ba.telco.ocs.common.exception.InsufficientFundsException;
import ba.telco.ocs.common.exception.InvalidStateException;
import ba.telco.ocs.subscriber.entity.BalanceSnapshot;
import ba.telco.ocs.tariff.entity.ServiceType;
import lombok.AllArgsConstructor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static ba.telco.ocs.TestFixtures.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChargingServiceConcurrencyTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-21T10:00:00Z"));
    private final TestFixtures fixtures = new TestFixtures(clock, Duration.ofSeconds(5));
    private final ChargingService chargingService = new ChargingService(new ChargingSessionRepository(),
            fixtures.subscriberService, fixtures.tariffService, fixtures.ratingEngine, fixtures.accountLocks,
            clock, fixtures.properties);

    @Test
    void reserveCommitChargesActualUsageAndFreesTheRest() {
        fixtures.subscriberService.create("38761000001", "A", "BASIC", new BigDecimal("1.00"));

        ChargingSession session = chargingService.reserve("38761000001", ServiceType.VOICE, 300);
        assertThat(session.getGrantedUnits()).isEqualTo(300);
        assertThat(session.getReservedAmount()).isEqualByComparingTo("0.60");
        assertThat(balance("38761000001").available()).isEqualByComparingTo("0.40");

        chargingService.commit(session.getId(), 61);

        BalanceSnapshot after = balance("38761000001");
        assertThat(after.balance()).isEqualByComparingTo("0.76");
        assertThat(after.reserved()).isEqualByComparingTo("0");
        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMMITTED);
    }

    @Test
    void grantsOnlyWhatTheCreditCovers() {
        fixtures.subscriberService.create("38761000002", "B", "BASIC", new BigDecimal("0.30"));

        ChargingSession session = chargingService.reserve("38761000002", ServiceType.VOICE, 600);

        assertThat(session.getGrantedUnits()).isEqualTo(120);
        assertThatThrownBy(() -> chargingService.reserve("38761000002", ServiceType.SMS, 1))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void concurrentReservationsNeverOverspendTheBalance() throws Exception {
        fixtures.subscriberService.create("38761000003", "C", "SMART", new BigDecimal("5.00"));
        List<ChargingSession> granted = new CopyOnWriteArrayList<>();
        AtomicInteger rejected = new AtomicInteger();

        runConcurrently(32, index -> {
            for (int i = 0; i < 10; i++) {
                try {
                    granted.add(chargingService.reserve("38761000003", ServiceType.SMS, 1));
                } catch (InsufficientFundsException e) {
                    rejected.incrementAndGet();
                }
            }
        });

        assertThat(granted).hasSize(100);
        assertThat(rejected.get()).isEqualTo(220);
        BalanceSnapshot snapshot = balance("38761000003");
        assertThat(snapshot.reserved()).isEqualByComparingTo("5.00");
        assertThat(snapshot.available()).isEqualByComparingTo("0");
    }

    @Test
    void commitReleaseAndExpiryRaceSettlesExactlyOnce() throws Exception {
        fixtures.subscriberService.create("38761000004", "D", "SMART", new BigDecimal("100.00"));
        int sessions = 200;
        List<ChargingSession> created = new CopyOnWriteArrayList<>();
        for (int i = 0; i < sessions; i++) {
            created.add(chargingService.reserve("38761000004", ServiceType.SMS, 1));
        }
        clock.advance(Duration.ofMinutes(10));
        AtomicInteger winners = new AtomicInteger();

        runConcurrently(3, index -> {
            if (index == 2) {
                winners.addAndGet(chargingService.expireSessions());
                return;
            }
            for (ChargingSession session : created) {
                try {
                    if (index == 0) {
                        chargingService.commit(session.getId(), 1);
                    } else {
                        chargingService.release(session.getId());
                    }
                    winners.incrementAndGet();
                } catch (InvalidStateException alreadySettled) {
                }
            }
        });

        assertThat(winners.get()).isEqualTo(sessions);
        long committed = created.stream().filter(s -> s.getStatus() == SessionStatus.COMMITTED).count();
        BalanceSnapshot snapshot = balance("38761000004");
        assertThat(snapshot.reserved()).isEqualByComparingTo("0");
        assertThat(snapshot.balance())
                .isEqualByComparingTo(new BigDecimal("100.00").subtract(new BigDecimal("0.05").multiply(BigDecimal.valueOf(committed))));
    }

    @Test
    void responseNeverMixesStatusAndSettlementOfDifferentStates() throws Exception {
        fixtures.subscriberService.create("38761000007", "G", "SMART", new BigDecimal("100.00"));
        List<ChargingSession> created = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 500; i++) {
            created.add(chargingService.reserve("38761000007", ServiceType.SMS, 1));
        }
        ChargingSessionMapper mapper = new ChargingSessionMapper();
        AtomicBoolean done = new AtomicBoolean();

        runConcurrently(2, index -> {
            if (index == 0) {
                created.forEach(session -> chargingService.commit(session.getId(), 1));
                done.set(true);
                return;
            }
            while (!done.get()) {
                for (ChargingSession session : created) {
                    ChargingSessionResponse response = mapper.toResponse(session);
                    if (response.status() == SessionStatus.ACTIVE) {
                        assertThat(response.usedUnits()).isZero();
                        assertThat(response.closedAt()).isNull();
                    } else {
                        assertThat(response.usedUnits()).isEqualTo(1);
                        assertThat(response.closedAt()).isNotNull();
                    }
                }
            }
        });
    }

    @Test
    void expiredReservationsAreReleased() {
        fixtures.subscriberService.create("38761000005", "E", "SMART", new BigDecimal("1.00"));
        ChargingSession session = chargingService.reserve("38761000005", ServiceType.SMS, 10);

        assertThat(chargingService.expireSessions()).isZero();
        clock.advance(Duration.ofMinutes(6));
        assertThat(chargingService.expireSessions()).isEqualTo(1);

        assertThat(session.getStatus()).isEqualTo(SessionStatus.EXPIRED);
        assertThat(balance("38761000005").available()).isEqualByComparingTo("1.00");
        assertThatThrownBy(() -> chargingService.commit(session.getId(), 1)).isInstanceOf(InvalidStateException.class);
    }

    @Test
    void commitAboveGrantedUnitsIsRejected() {
        fixtures.subscriberService.create("38761000006", "F", "SMART", new BigDecimal("1.00"));
        ChargingSession session = chargingService.reserve("38761000006", ServiceType.SMS, 2);
        assertThatThrownBy(() -> chargingService.commit(session.getId(), 3)).isInstanceOf(BadRequestException.class);
    }

    private BalanceSnapshot balance(String msisdn) {
        return fixtures.subscriberService.getByMsisdn(msisdn).balanceSnapshot();
    }

    @AllArgsConstructor
    static final class MutableClock extends Clock {

        private volatile Instant now;

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
