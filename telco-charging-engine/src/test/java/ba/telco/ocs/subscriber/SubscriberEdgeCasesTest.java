package ba.telco.ocs.subscriber;

import ba.telco.ocs.TestFixtures;
import ba.telco.ocs.common.exception.BadRequestException;
import ba.telco.ocs.common.exception.InvalidStateException;
import ba.telco.ocs.common.exception.NotFoundException;
import ba.telco.ocs.common.exception.ResourceBusyException;
import ba.telco.ocs.subscriber.entity.Subscriber;
import ba.telco.ocs.subscriber.entity.SubscriberStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriberEdgeCasesTest {

    private final TestFixtures fixtures = new TestFixtures();

    @Test
    void duplicateAndInvalidActivationsAreRejected() {
        fixtures.subscriberService.create("38761000001", "A", "BASIC", BigDecimal.ONE);

        assertThatThrownBy(() -> fixtures.subscriberService.create("38761000001", "A", "BASIC", BigDecimal.ONE))
                .isInstanceOf(InvalidStateException.class);
        assertThatThrownBy(() -> fixtures.subscriberService.create("38761000002", "B", "NOPE", BigDecimal.ONE))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> fixtures.subscriberService.getByMsisdn("38760000000"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void pagingIsSortedByMsisdn() {
        for (String msisdn : List.of("38761000003", "38761000001", "38761000002")) {
            fixtures.subscriberService.create(msisdn, "N", "BASIC", BigDecimal.ONE);
        }

        assertThat(fixtures.subscriberService.count()).isEqualTo(3);
        assertThat(fixtures.subscriberService.getPage(1, 5)).extracting(Subscriber::getMsisdn)
                .containsExactly("38761000002", "38761000003");
    }

    @Test
    void tariffCanBeChangedOnlyToAnExistingPlan() {
        fixtures.subscriberService.create("38761000001", "A", "BASIC", BigDecimal.ONE);

        assertThat(fixtures.subscriberService.changeTariff("38761000001", "SMART").getTariffCode()).isEqualTo("SMART");
        assertThatThrownBy(() -> fixtures.subscriberService.changeTariff("38761000001", "NOPE"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> fixtures.subscriberService.changeTariff("38760000000", "SMART"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void statusChangeAndSelfTransferAreValidated() {
        fixtures.subscriberService.create("38761000001", "A", "BASIC", BigDecimal.TEN);

        Subscriber suspended = fixtures.subscriberService.changeStatus("38761000001", SubscriberStatus.SUSPENDED);

        assertThat(suspended.getStatus()).isEqualTo(SubscriberStatus.SUSPENDED);
        assertThatThrownBy(() -> fixtures.subscriberService.requireActive(suspended))
                .isInstanceOf(InvalidStateException.class);
        assertThatThrownBy(() -> fixtures.subscriberService.transfer("38761000001", "38761000001", BigDecimal.ONE))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void lockingTheSameAccountTwiceIsRejected() {
        fixtures.subscriberService.create("38761000001", "A", "BASIC", BigDecimal.TEN);
        Subscriber subscriber = fixtures.subscriberService.getByMsisdn("38761000001");

        assertThatThrownBy(() -> fixtures.accountLocks.withLocks(subscriber, subscriber, () -> null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void interruptedThreadGets503AndKeepsItsInterruptFlag() {
        fixtures.subscriberService.create("38761000001", "A", "BASIC", BigDecimal.TEN);
        Subscriber subscriber = fixtures.subscriberService.getByMsisdn("38761000001");

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> fixtures.accountLocks.withLock(subscriber, () -> null))
                    .isInstanceOf(ResourceBusyException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void busyAccountTimesOutAndIsCounted() throws Exception {
        TestFixtures quick = new TestFixtures(java.time.Clock.systemUTC(), Duration.ofMillis(20));
        quick.subscriberService.create("38761000001", "A", "BASIC", BigDecimal.TEN);
        Subscriber subscriber = quick.subscriberService.getByMsisdn("38761000001");
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> quick.accountLocks.withLock(subscriber, () -> {
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        locked.await();

        assertThatThrownBy(() -> quick.subscriberService.topUp("38761000001", BigDecimal.ONE))
                .isInstanceOf(ResourceBusyException.class);
        release.countDown();
        holder.join();

        assertThat(quick.accountLocks.getLockTimeouts()).isEqualTo(1);
    }
}
