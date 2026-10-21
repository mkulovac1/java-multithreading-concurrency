package ba.telco.ocs.common.concurrency;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static ba.telco.ocs.TestFixtures.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

class SimpleCountDownLatchTest {

    @Test
    void awaitReturnsWhenCountReachesZero() throws Exception {
        SimpleCountDownLatch latch = new SimpleCountDownLatch(3);
        Thread waiter = Thread.ofPlatform().start(() -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        BoundedBlockingBufferTest.awaitState(waiter, Thread.State.WAITING);
        latch.countDown();
        latch.countDown();
        assertThat(waiter.isAlive()).isTrue();
        latch.countDown();
        waiter.join(2_000);

        assertThat(waiter.isAlive()).isFalse();
    }

    @Test
    void timedAwaitReturnsFalseOnTimeout() throws InterruptedException {
        SimpleCountDownLatch latch = new SimpleCountDownLatch(1);
        assertThat(latch.await(30)).isFalse();
        latch.countDown();
        assertThat(latch.await(30)).isTrue();
    }

    @Test
    void exactlyOneCallerObservesTheTransitionToZero() throws Exception {
        int threads = 32;
        SimpleCountDownLatch latch = new SimpleCountDownLatch(threads * 100);
        AtomicInteger zeroObservations = new AtomicInteger();

        runConcurrently(threads, index -> {
            for (int i = 0; i < 101; i++) {
                if (latch.countDown()) {
                    zeroObservations.incrementAndGet();
                }
            }
        });

        assertThat(latch.getCount()).isZero();
        assertThat(zeroObservations.get()).isEqualTo(1);
    }
}
