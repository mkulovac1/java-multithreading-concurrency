package ba.telco.ocs.charging;

import ba.telco.ocs.TestClock;
import ba.telco.ocs.TestFixtures;
import ba.telco.ocs.charging.entity.ChargingSession;
import ba.telco.ocs.charging.entity.SessionStatus;
import ba.telco.ocs.charging.repository.ChargingSessionRepository;
import ba.telco.ocs.charging.service.ChargingService;
import ba.telco.ocs.common.exception.InvalidStateException;
import ba.telco.ocs.common.exception.NotFoundException;
import ba.telco.ocs.subscriber.entity.Subscriber;
import ba.telco.ocs.tariff.entity.ServiceType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

class ChargingEdgeCasesTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-10-21T10:00:00Z"));
    private final TestFixtures fixtures = new TestFixtures(clock, Duration.ofMillis(50));
    private final ChargingSessionRepository repository = spy(new ChargingSessionRepository());
    private final ChargingService service = new ChargingService(repository, fixtures.subscriberService,
            fixtures.tariffService, fixtures.ratingEngine, fixtures.accountLocks, clock, fixtures.properties);

    private ChargingSession reserve(String msisdn) {
        fixtures.subscriberService.create(msisdn, "N", "BASIC", new BigDecimal("5.00"));
        return service.reserve(msisdn, ServiceType.SMS, 2);
    }

    @Test
    void sessionStateAccessorsReflectTheOutcome() {
        ChargingSession session = reserve("38761000001");
        assertThat(session.isExpired(clock.instant())).isFalse();
        assertThat(session.isExpired(clock.instant().plus(Duration.ofMinutes(10)))).isTrue();
        assertThat(session.getClosedAt()).isNull();
        assertThatThrownBy(() -> session.close(SessionStatus.ACTIVE, 0, BigDecimal.ZERO, clock.instant()))
                .isInstanceOf(IllegalArgumentException.class);

        service.commit(session.getId(), 1);

        assertThat(session.getUsedUnits()).isEqualTo(1);
        assertThat(session.getChargedAmount()).isEqualByComparingTo("0.08");
        assertThat(session.getClosedAt()).isEqualTo(clock.instant());
        assertThat(session.isExpired(clock.instant().plus(Duration.ofMinutes(10)))).isFalse();
        assertThat(session.close(SessionStatus.RELEASED, 0, BigDecimal.ZERO, clock.instant())).isFalse();
    }

    @Test
    void commitAfterExpiryIsRejectedAndTheReaperReleasesTheCredit() {
        ChargingSession session = reserve("38761000001");
        clock.advance(Duration.ofMinutes(4));
        service.commit(reserve("38761000002").getId(), 1);
        clock.advance(Duration.ofMinutes(2));

        assertThatThrownBy(() -> service.commit(session.getId(), 1))
                .isInstanceOf(InvalidStateException.class).hasMessageContaining("expired");
        assertThat(session.getStatus()).isEqualTo(SessionStatus.ACTIVE);

        assertThat(service.expireSessions()).isEqualTo(1);
        assertThat(fixtures.subscriberService.getByMsisdn("38761000001").balanceSnapshot())
                .satisfies(snapshot -> assertThat(snapshot.reserved()).isEqualByComparingTo("0"))
                .satisfies(snapshot -> assertThat(snapshot.balance()).isEqualByComparingTo("5.00"));
    }

    @Test
    void releaseAfterExpiryIsStillAllowed() {
        ChargingSession session = reserve("38761000001");
        clock.advance(Duration.ofMinutes(6));

        assertThat(service.release(session.getId()).getStatus()).isEqualTo(SessionStatus.RELEASED);
    }

    @Test
    void unknownSessionIsNotFound() {
        assertThatThrownBy(() -> service.getById("nope")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.release("nope")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void expiredReservationsAreReleasedAndPurgedAfterRetention() {
        ChargingSession session = reserve("38761000001");
        clock.advance(Duration.ofMinutes(6));

        assertThat(service.expireSessions()).isEqualTo(1);
        assertThat(session.getStatus()).isEqualTo(SessionStatus.EXPIRED);
        assertThat(service.stats().expirations()).isEqualTo(1);
        assertThat(fixtures.subscriberService.getByMsisdn("38761000001").balanceSnapshot().reserved())
                .isEqualByComparingTo("0");

        assertThat(service.purgeClosedSessions(Duration.ofHours(1))).isZero();
        clock.advance(Duration.ofHours(2));
        assertThat(service.purgeClosedSessions(Duration.ofHours(1))).isEqualTo(1);
        assertThat(repository.countActive()).isZero();
    }

    @Test
    void sessionSettledByARacingCommitIsSkippedByTheReaper() {
        ChargingSession session = reserve("38761000001");
        service.commit(session.getId(), 2);
        doReturn(List.of(session)).when(repository).findExpired(any());

        assertThat(service.expireSessions()).isZero();
        assertThat(service.stats().expirations()).isZero();
    }

    @Test
    void busyAccountDefersExpiryToTheNextRun() throws Exception {
        ChargingSession session = reserve("38761000001");
        clock.advance(Duration.ofMinutes(6));
        Subscriber subscriber = fixtures.subscriberService.getByMsisdn("38761000001");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> fixtures.accountLocks.withLock(subscriber, () -> {
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        locked.await();

        assertThat(service.expireSessions()).isZero();
        assertThat(session.getStatus()).isEqualTo(SessionStatus.ACTIVE);
        release.countDown();
        holder.join();

        assertThat(service.expireSessions()).isEqualTo(1);
        assertThatThrownBy(() -> service.release(session.getId())).isInstanceOf(InvalidStateException.class);
    }

    @Test
    void repositoryOnlyRemovesClosedSessionsOlderThanTheThreshold() {
        ChargingSession active = reserve("38761000001");
        ChargingSession closed = reserve("38761000002");
        service.release(closed.getId());

        assertThat(repository.removeClosedBefore(clock.instant().minusSeconds(1))).isZero();
        assertThat(repository.removeClosedBefore(clock.instant().plusSeconds(1))).isEqualTo(1);
        assertThat(repository.findById(active.getId())).isPresent();
        assertThat(repository.findById(closed.getId())).isEmpty();
        assertThat(repository.countActive()).isEqualTo(1);
    }
}
