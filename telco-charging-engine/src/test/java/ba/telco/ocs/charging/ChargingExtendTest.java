package ba.telco.ocs.charging;

import ba.telco.ocs.TestClock;
import ba.telco.ocs.TestFixtures;
import ba.telco.ocs.charging.entity.ChargingSession;
import ba.telco.ocs.charging.entity.SessionStatus;
import ba.telco.ocs.charging.repository.ChargingSessionRepository;
import ba.telco.ocs.charging.service.ChargingService;
import ba.telco.ocs.common.exception.BadRequestException;
import ba.telco.ocs.common.exception.InsufficientFundsException;
import ba.telco.ocs.common.exception.InvalidStateException;
import ba.telco.ocs.common.exception.NotFoundException;
import ba.telco.ocs.subscriber.entity.BalanceSnapshot;
import ba.telco.ocs.subscriber.entity.SubscriberStatus;
import ba.telco.ocs.tariff.entity.ServiceType;
import ba.telco.ocs.tariff.entity.TariffPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static ba.telco.ocs.TestFixtures.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

class ChargingExtendTest {

    private static final String MSISDN = "38761000001";

    private final TestClock clock = new TestClock(Instant.parse("2026-10-21T10:00:00Z"));
    private final TestFixtures fixtures = new TestFixtures(clock, Duration.ofSeconds(5));
    private final ChargingSessionRepository repository = spy(new ChargingSessionRepository());
    private final ChargingService service = new ChargingService(repository, fixtures.subscriberService,
            fixtures.tariffService, fixtures.ratingEngine, fixtures.accountLocks, clock, fixtures.properties);

    private ChargingSession reserveSms(String balance, long units) {
        fixtures.subscriberService.create(MSISDN, "N", "BASIC", new BigDecimal(balance));
        return service.reserve(MSISDN, ServiceType.SMS, units);
    }

    private BalanceSnapshot balance() {
        return fixtures.subscriberService.getByMsisdn(MSISDN).balanceSnapshot();
    }

    @Test
    void extendReservesOnlyTheDifferenceAndRenewsTheDeadline() {
        ChargingSession session = reserveSms("5.00", 2);
        clock.advance(Duration.ofMinutes(4));

        service.extend(session.getId(), 3);

        assertThat(session.getGrantedUnits()).isEqualTo(5);
        assertThat(session.getReservedAmount()).isEqualByComparingTo("0.40");
        assertThat(session.getExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
        assertThat(balance().reserved()).isEqualByComparingTo("0.40");
        assertThat(balance().available()).isEqualByComparingTo("4.60");
        assertThat(service.stats().extensions()).isEqualTo(1);
    }

    @Test
    void extendedSessionCanBeCommittedAfterTheOriginalDeadline() {
        ChargingSession session = reserveSms("5.00", 2);
        clock.advance(Duration.ofMinutes(4));
        service.extend(session.getId(), 3);
        clock.advance(Duration.ofMinutes(3));

        service.commit(session.getId(), 5);

        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMMITTED);
        assertThat(session.getChargedAmount()).isEqualByComparingTo("0.40");
        assertThat(balance().balance()).isEqualByComparingTo("4.60");
        assertThat(balance().reserved()).isEqualByComparingTo("0");
    }

    @Test
    void releasingAnExtendedSessionFreesTheWholeReservation() {
        ChargingSession session = reserveSms("5.00", 2);
        service.extend(session.getId(), 10);

        service.release(session.getId());

        assertThat(balance().reserved()).isEqualByComparingTo("0");
        assertThat(balance().balance()).isEqualByComparingTo("5.00");
    }

    @Test
    void extensionIsGrantedPartiallyWhenCreditRunsOut() {
        ChargingSession session = reserveSms("0.50", 2);

        service.extend(session.getId(), 10);

        assertThat(session.getGrantedUnits()).isEqualTo(6);
        assertThat(session.getReservedAmount()).isEqualByComparingTo("0.48");
        assertThat(balance().available()).isEqualByComparingTo("0.02");
    }

    @Test
    void extensionWithoutAnyCreditLeftIsRejectedAndChangesNothing() {
        ChargingSession session = reserveSms("0.16", 2);
        Instant deadline = session.getExpiresAt();
        clock.advance(Duration.ofMinutes(1));

        assertThatThrownBy(() -> service.extend(session.getId(), 1)).isInstanceOf(InsufficientFundsException.class);

        assertThat(session.getGrantedUnits()).isEqualTo(2);
        assertThat(session.getExpiresAt()).isEqualTo(deadline);
        assertThat(balance().reserved()).isEqualByComparingTo("0.16");
        assertThat(service.stats().extensions()).isZero();
        assertThat(service.stats().rejectedInsufficientFunds()).isEqualTo(1);
    }

    @Test
    void voiceExtensionReservesThePriceOfTheTotalNotTheSumOfParts() {
        fixtures.subscriberService.create(MSISDN, "N", "BASIC", new BigDecimal("5.00"));
        ChargingSession session = service.reserve(MSISDN, ServiceType.VOICE, 30);
        assertThat(session.getReservedAmount()).isEqualByComparingTo("0.12");

        service.extend(session.getId(), 10);

        assertThat(session.getGrantedUnits()).isEqualTo(40);
        assertThat(session.getReservedAmount()).isEqualByComparingTo("0.12");
        assertThat(balance().reserved()).isEqualByComparingTo("0.12");

        service.extend(session.getId(), 30);

        assertThat(session.getReservedAmount()).isEqualByComparingTo("0.24");
        assertThat(balance().reserved()).isEqualByComparingTo("0.24");
    }

    @Test
    void expiredClosedUnknownAndSuspendedSessionsCannotBeExtended() {
        ChargingSession session = reserveSms("5.00", 2);
        clock.advance(Duration.ofMinutes(6));
        assertThatThrownBy(() -> service.extend(session.getId(), 1))
                .isInstanceOf(InvalidStateException.class).hasMessageContaining("expired");
        assertThat(session.getGrantedUnits()).isEqualTo(2);

        service.release(session.getId());
        assertThatThrownBy(() -> service.extend(session.getId(), 1))
                .isInstanceOf(InvalidStateException.class).hasMessageContaining("not active");
        assertThatThrownBy(() -> service.extend("nope", 1)).isInstanceOf(NotFoundException.class);

        ChargingSession fresh = service.reserve(MSISDN, ServiceType.SMS, 1);
        fixtures.subscriberService.changeStatus(MSISDN, SubscriberStatus.SUSPENDED);
        assertThatThrownBy(() -> service.extend(fresh.getId(), 1)).isInstanceOf(InvalidStateException.class);
    }

    @Test
    void sessionSizeIsCapped() {
        fixtures.tariffService.create(new TariffPlan("FREE", "Free", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 1));
        fixtures.subscriberService.create(MSISDN, "N", "FREE", BigDecimal.ZERO);
        ChargingSession session = service.reserve(MSISDN, ServiceType.SMS, 999_999_999);

        assertThatThrownBy(() -> service.extend(session.getId(), 2)).isInstanceOf(BadRequestException.class);

        assertThat(service.extend(session.getId(), 1).getGrantedUnits()).isEqualTo(1_000_000_000);
    }

    @Test
    void concurrentExtensionsNeverReserveMoreThanTheBalance() throws Exception {
        ChargingSession session = reserveSms("5.00", 2);
        AtomicInteger granted = new AtomicInteger();

        runConcurrently(16, index -> {
            for (int i = 0; i < 20; i++) {
                try {
                    service.extend(session.getId(), 1);
                    granted.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    return;
                }
            }
        });

        BalanceSnapshot snapshot = balance();
        assertThat(session.getGrantedUnits()).isEqualTo(2 + granted.get()).isEqualTo(62);
        assertThat(snapshot.reserved()).isEqualByComparingTo(session.getReservedAmount());
        assertThat(snapshot.available().signum()).isGreaterThanOrEqualTo(0);
        assertThat(service.stats().extensions()).isEqualTo(granted.get());
    }

    @Test
    void racingExtendAndCommitNeverLeakReservedCredit() throws Exception {
        for (int round = 0; round < 100; round++) {
            String msisdn = String.format("3876100%04d", round);
            fixtures.subscriberService.create(msisdn, "N", "BASIC", new BigDecimal("5.00"));
            ChargingSession session = service.reserve(msisdn, ServiceType.SMS, 2);

            runConcurrently(2, index -> {
                try {
                    if (index == 0) {
                        service.extend(session.getId(), 3);
                    } else {
                        service.commit(session.getId(), 2);
                    }
                } catch (InvalidStateException e) {
                    assertThat(index).isZero();
                }
            });

            BalanceSnapshot snapshot = fixtures.subscriberService.getByMsisdn(msisdn).balanceSnapshot();
            assertThat(session.getStatus()).isEqualTo(SessionStatus.COMMITTED);
            assertThat(snapshot.reserved()).isEqualByComparingTo("0");
            assertThat(snapshot.balance()).isEqualByComparingTo("4.84");
        }
    }

    @Test
    void reaperDoesNotExpireASessionThatWasExtendedAfterTheScan() {
        ChargingSession session = reserveSms("5.00", 2);
        clock.advance(Duration.ofMinutes(4));
        service.extend(session.getId(), 1);
        clock.advance(Duration.ofMinutes(2));
        doReturn(List.of(session)).when(repository).findExpired(any());

        assertThat(service.expireSessions()).isZero();

        assertThat(session.getStatus()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(balance().reserved()).isEqualByComparingTo("0.24");
    }

    @Test
    void sessionCannotBeExtendedOnceClosed() {
        ChargingSession session = reserveSms("5.00", 2);
        service.commit(session.getId(), 1);

        assertThat(session.extend(10, BigDecimal.TEN, clock.instant())).isFalse();
        assertThat(session.getGrantedUnits()).isEqualTo(2);
    }
}
