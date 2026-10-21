package ba.telco.ocs.common;

import ba.telco.ocs.common.concurrency.BoundedBlockingBuffer;
import ba.telco.ocs.common.concurrency.LockFreeStack;
import ba.telco.ocs.common.concurrency.NamedThreadFactory;
import ba.telco.ocs.common.concurrency.SimpleCountDownLatch;
import ba.telco.ocs.common.metrics.LatencyStats;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommonConcurrencyEdgeCasesTest {

    @Test
    void stackRejectsNullAndHonoursSnapshotLimit() {
        LockFreeStack<String> stack = new LockFreeStack<>();
        assertThatThrownBy(() -> stack.push(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(stack.isEmpty()).isTrue();
        assertThat(stack.pop()).isNull();

        stack.push("a");
        stack.push("b");
        stack.push("c");

        assertThat(stack.isEmpty()).isFalse();
        assertThat(stack.snapshot(2)).containsExactly("c", "b");
        assertThat(stack.drain()).containsExactly("c", "b", "a");
        assertThat(stack.size()).isZero();
    }

    @Test
    void latchValidatesCountAndIgnoresExtraCountDowns() throws Exception {
        assertThatThrownBy(() -> new SimpleCountDownLatch(-1)).isInstanceOf(IllegalArgumentException.class);
        SimpleCountDownLatch latch = new SimpleCountDownLatch(1);

        assertThat(latch.await(10)).isFalse();
        assertThat(latch.countDown()).isTrue();
        assertThat(latch.countDown()).isFalse();
        latch.await();
        assertThat(latch.await(10)).isTrue();
        assertThat(latch.getCount()).isZero();
    }

    @Test
    void zeroCountLatchIsOpenImmediately() throws Exception {
        SimpleCountDownLatch latch = new SimpleCountDownLatch(0);

        latch.await();

        assertThat(latch.await(1)).isTrue();
    }

    @Test
    void threadFactoryNamesThreadsAndLogsUncaughtExceptions() {
        NamedThreadFactory factory = new NamedThreadFactory("worker", true);
        Thread thread = factory.newThread(() -> {
        });

        assertThat(thread.getName()).isEqualTo("worker-1");
        assertThat(thread.isDaemon()).isTrue();
        thread.getUncaughtExceptionHandler().uncaughtException(thread, new IllegalStateException("boom"));
        assertThat(factory.newThread(() -> {
        }).getName()).isEqualTo("worker-2");
    }

    @Test
    void emptyLatencyStatsReportZeros() {
        LatencyStats.LatencySnapshot snapshot = new LatencyStats().snapshot();

        assertThat(snapshot.samples()).isZero();
        assertThat(snapshot.avgMicros()).isZero();
        assertThat(snapshot.minMicros()).isZero();
        assertThat(snapshot.maxMicros()).isZero();
    }

    @Test
    void bufferValidatesArgumentsAndTimesOut() throws Exception {
        assertThatThrownBy(() -> new BoundedBlockingBuffer<String>(0)).isInstanceOf(IllegalArgumentException.class);
        BoundedBlockingBuffer<String> buffer = new BoundedBlockingBuffer<>(1);
        assertThatThrownBy(() -> buffer.put(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> buffer.offer(null, 1, TimeUnit.MILLISECONDS))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(buffer.poll(5, TimeUnit.MILLISECONDS)).isNull();
        buffer.put("a");
        assertThat(buffer.offer("b", 5, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(buffer.size()).isEqualTo(1);
        assertThat(buffer.take()).isEqualTo("a");
    }

    @Test
    void blockedConsumerIsWokenByProducer() throws Exception {
        BoundedBlockingBuffer<String> buffer = new BoundedBlockingBuffer<>(1);
        AtomicReference<String> received = new AtomicReference<>();
        Thread consumer = Thread.ofPlatform().start(() -> {
            try {
                received.set(buffer.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        while (consumer.getState() != Thread.State.WAITING) {
            Thread.sleep(1);
        }

        buffer.put("x");
        consumer.join(5_000);

        assertThat(received.get()).isEqualTo("x");
    }

    @Test
    void blockedProducerIsWokenByConsumer() throws Exception {
        BoundedBlockingBuffer<String> buffer = new BoundedBlockingBuffer<>(1);
        buffer.put("first");
        Thread producer = Thread.ofPlatform().start(() -> {
            try {
                buffer.put("second");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        while (producer.getState() != Thread.State.WAITING) {
            Thread.sleep(1);
        }

        assertThat(buffer.take()).isEqualTo("first");
        producer.join(5_000);

        assertThat(buffer.take()).isEqualTo("second");
    }
}
