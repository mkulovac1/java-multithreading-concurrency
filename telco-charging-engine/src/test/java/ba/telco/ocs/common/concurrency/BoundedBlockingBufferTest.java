package ba.telco.ocs.common.concurrency;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static ba.telco.ocs.TestFixtures.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedBlockingBufferTest {

    @Test
    void preservesFifoOrder() throws InterruptedException {
        BoundedBlockingBuffer<String> buffer = new BoundedBlockingBuffer<>(3);
        buffer.put("a");
        buffer.put("b");
        buffer.put("c");

        assertThat(buffer.take()).isEqualTo("a");
        assertThat(buffer.take()).isEqualTo("b");
        assertThat(buffer.take()).isEqualTo("c");
    }

    @Test
    void offerTimesOutWhenFullAndPollTimesOutWhenEmpty() throws InterruptedException {
        BoundedBlockingBuffer<Integer> buffer = new BoundedBlockingBuffer<>(1);
        assertThat(buffer.offer(1, 10, TimeUnit.MILLISECONDS)).isTrue();

        long start = System.nanoTime();
        assertThat(buffer.offer(2, 50, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(45));

        assertThat(buffer.poll(10, TimeUnit.MILLISECONDS)).isEqualTo(1);
        assertThat(buffer.poll(20, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void blockedProducerIsReleasedWhenConsumerTakes() throws Exception {
        BoundedBlockingBuffer<Integer> buffer = new BoundedBlockingBuffer<>(1);
        buffer.put(1);
        Thread producer = Thread.ofPlatform().start(() -> {
            try {
                buffer.put(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        awaitState(producer, Thread.State.WAITING);
        assertThat(buffer.take()).isEqualTo(1);
        producer.join(2_000);

        assertThat(producer.isAlive()).isFalse();
        assertThat(buffer.take()).isEqualTo(2);
    }

    @Test
    void blockedConsumerRespondsToInterrupt() throws Exception {
        BoundedBlockingBuffer<Integer> buffer = new BoundedBlockingBuffer<>(1);
        AtomicLong interrupted = new AtomicLong();
        Thread consumer = Thread.ofPlatform().start(() -> {
            try {
                buffer.take();
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
            }
        });

        awaitState(consumer, Thread.State.WAITING);
        consumer.interrupt();
        consumer.join(2_000);

        assertThat(interrupted.get()).isEqualTo(1);
    }

    @Test
    void manyProducersAndConsumersTransferEveryItemExactlyOnce() throws Exception {
        BoundedBlockingBuffer<Long> buffer = new BoundedBlockingBuffer<>(16);
        int producers = 4;
        int consumers = 4;
        int perProducer = 25_000;
        LongAdder consumedSum = new LongAdder();
        LongAdder consumedCount = new LongAdder();

        runConcurrently(producers + consumers, index -> {
            try {
                if (index < producers) {
                    for (int i = 1; i <= perProducer; i++) {
                        buffer.put((long) i);
                    }
                } else {
                    for (int i = 0; i < perProducer; i++) {
                        long value = buffer.take();
                        consumedSum.add(value);
                        consumedCount.increment();
                    }
                }
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });

        long expectedSum = (long) producers * perProducer * (perProducer + 1) / 2;
        assertThat(consumedCount.sum()).isEqualTo((long) producers * perProducer);
        assertThat(consumedSum.sum()).isEqualTo(expectedSum);
        assertThat(buffer.size()).isZero();
    }

    @Test
    void rejectsNullsAndNonPositiveCapacity() {
        assertThatThrownBy(() -> new BoundedBlockingBuffer<>(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedBlockingBuffer<String>(1).put(null)).isInstanceOf(IllegalArgumentException.class);
    }

    static void awaitState(Thread thread, Thread.State state) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != state) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Thread never reached state " + state + ", is " + thread.getState());
            }
            Thread.sleep(1);
        }
    }
}
