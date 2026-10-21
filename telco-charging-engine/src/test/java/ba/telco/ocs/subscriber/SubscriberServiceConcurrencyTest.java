package ba.telco.ocs.subscriber;

import ba.telco.ocs.TestFixtures;
import ba.telco.ocs.common.exception.BadRequestException;
import ba.telco.ocs.common.exception.InsufficientFundsException;
import ba.telco.ocs.common.exception.InvalidStateException;
import ba.telco.ocs.common.exception.ResourceBusyException;
import ba.telco.ocs.subscriber.entity.Subscriber;
import ba.telco.ocs.subscriber.entity.SubscriberStatus;
import ba.telco.ocs.subscriber.service.SubscriberService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static ba.telco.ocs.TestFixtures.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriberServiceConcurrencyTest {

    private final TestFixtures fixtures = new TestFixtures();
    private final SubscriberService service = fixtures.subscriberService;

    @Test
    void concurrentTopUpsAreNeverLost() throws Exception {
        service.create("38761111111", "A", "SMART", BigDecimal.ZERO);

        runConcurrently(16, index -> {
            for (int i = 0; i < 1_000; i++) {
                service.topUp("38761111111", new BigDecimal("0.01"));
            }
        });

        assertThat(service.getByMsisdn("38761111111").balanceSnapshot().balance()).isEqualByComparingTo("160.00");
    }

    @Test
    void oppositeTransfersDoNotDeadlockAndConserveMoney() throws Exception {
        service.create("38761000001", "A", "SMART", new BigDecimal("2000"));
        service.create("38761000002", "B", "SMART", new BigDecimal("2000"));

        runConcurrently(16, index -> {
            String from = index % 2 == 0 ? "38761000001" : "38761000002";
            String to = index % 2 == 0 ? "38761000002" : "38761000001";
            for (int i = 0; i < 2_000; i++) {
                service.transfer(from, to, new BigDecimal("0.10"));
            }
        });

        BigDecimal a = service.getByMsisdn("38761000001").balanceSnapshot().balance();
        BigDecimal b = service.getByMsisdn("38761000002").balanceSnapshot().balance();
        assertThat(a.add(b)).isEqualByComparingTo("4000");
        assertThat(a).isEqualByComparingTo("2000");
    }

    @Test
    void noTransferCompletesAfterSuspensionReturns() throws Exception {
        service.create("38761000007", "Sender", "SMART", new BigDecimal("1000"));
        service.create("38761000008", "Receiver", "SMART", BigDecimal.ZERO);
        AtomicReference<BigDecimal> balanceAtSuspension = new AtomicReference<>();

        runConcurrently(9, index -> {
            if (index == 0) {
                LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
                service.changeStatus("38761000007", SubscriberStatus.SUSPENDED);
                balanceAtSuspension.set(service.getByMsisdn("38761000007").balanceSnapshot().balance());
                return;
            }
            while (true) {
                try {
                    service.transfer("38761000007", "38761000008", new BigDecimal("0.01"));
                } catch (InvalidStateException suspended) {
                    return;
                }
            }
        });

        assertThat(service.getByMsisdn("38761000007").balanceSnapshot().balance())
                .isEqualByComparingTo(balanceAtSuspension.get());
        assertThat(balanceAtSuspension.get()).isLessThan(new BigDecimal("1000"));
    }

    @Test
    void concurrentTransfersNeverOverdrawTheSender() throws Exception {
        service.create("38761000003", "Sender", "SMART", new BigDecimal("10"));
        service.create("38761000004", "Receiver", "SMART", BigDecimal.ZERO);
        AtomicInteger successful = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        runConcurrently(20, index -> {
            try {
                service.transfer("38761000003", "38761000004", new BigDecimal("1"));
                successful.incrementAndGet();
            } catch (InsufficientFundsException e) {
                rejected.incrementAndGet();
            }
        });

        assertThat(successful.get()).isEqualTo(10);
        assertThat(rejected.get()).isEqualTo(10);
        assertThat(service.getByMsisdn("38761000003").balanceSnapshot().balance()).isEqualByComparingTo("0");
    }

    @Test
    void lockTimeoutTurnsIntoBusyError() throws Exception {
        TestFixtures shortTimeout = new TestFixtures(Clock.systemUTC(), Duration.ofMillis(50));
        Subscriber subscriber = shortTimeout.subscriberService.create("38761000005", "Hot", "SMART", BigDecimal.TEN);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder = Thread.ofPlatform().start(() -> {
            try {
                subscriber.tryLock(Duration.ofSeconds(1));
                locked.countDown();
                release.await();
                subscriber.unlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(locked.await(1, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> shortTimeout.subscriberService.topUp("38761000005", BigDecimal.ONE))
                .isInstanceOf(ResourceBusyException.class);
        assertThat(shortTimeout.accountLocks.getLockTimeouts()).isEqualTo(1);

        release.countDown();
        holder.join();
    }

    @Test
    void mutatingBalanceWithoutLockFailsFast() {
        Subscriber subscriber = service.create("38761000006", "X", "SMART", BigDecimal.TEN);
        assertThatThrownBy(() -> subscriber.credit(BigDecimal.ONE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void transferToSelfIsRejected() {
        service.create("38761000007", "Self", "SMART", BigDecimal.TEN);
        assertThatThrownBy(() -> service.transfer("38761000007", "38761000007", BigDecimal.ONE))
                .isInstanceOf(BadRequestException.class);
    }
}
