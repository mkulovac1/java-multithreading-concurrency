package ba.telco.ocs.common.concurrency;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static ba.telco.ocs.TestFixtures.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

class LockFreeStackTest {

    @Test
    void popsInLifoOrder() {
        LockFreeStack<Integer> stack = new LockFreeStack<>();
        stack.push(1);
        stack.push(2);
        stack.push(3);

        assertThat(stack.snapshot(10)).containsExactly(3, 2, 1);
        assertThat(stack.pop()).isEqualTo(3);
        assertThat(stack.pop()).isEqualTo(2);
        assertThat(stack.pop()).isEqualTo(1);
        assertThat(stack.pop()).isNull();
        assertThat(stack.isEmpty()).isTrue();
    }

    @Test
    void concurrentPushesAndPopsNeverLoseOrDuplicateElements() throws Exception {
        LockFreeStack<Integer> stack = new LockFreeStack<>();
        int threads = 16;
        int perThread = 20_000;
        Set<Integer> popped = ConcurrentHashMap.newKeySet();

        runConcurrently(threads, index -> {
            for (int i = 0; i < perThread; i++) {
                stack.push(index * perThread + i);
                if (i % 2 == 0) {
                    Integer value = stack.pop();
                    if (value != null) {
                        assertThat(popped.add(value)).as("element popped twice: %s", value).isTrue();
                    }
                }
            }
        });

        List<Integer> remaining = stack.drain();
        Set<Integer> all = new HashSet<>(popped);
        for (Integer value : remaining) {
            assertThat(all.add(value)).as("element present twice: %s", value).isTrue();
        }
        assertThat(all).hasSize(threads * perThread);
        assertThat(stack.size()).isZero();
    }

    @Test
    void drainIsAtomicAgainstConcurrentPushers() throws Exception {
        LockFreeStack<Integer> stack = new LockFreeStack<>();
        List<Integer> drained = new ArrayList<>();
        int pushers = 8;
        int perThread = 10_000;

        runConcurrently(pushers + 1, index -> {
            if (index == pushers) {
                for (int i = 0; i < 50; i++) {
                    List<Integer> batch = stack.drain();
                    synchronized (drained) {
                        drained.addAll(batch);
                    }
                }
            } else {
                for (int i = 0; i < perThread; i++) {
                    stack.push(index * perThread + i);
                }
            }
        });
        drained.addAll(stack.drain());

        assertThat(new HashSet<>(drained)).hasSize(pushers * perThread);
        assertThat(drained).hasSize(pushers * perThread);
    }
}
