package ba.telco.ocs.common.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

public final class LockFreeStack<T> {

    private record Node<T>(T value, Node<T> next) {
    }

    private final AtomicReference<Node<T>> head = new AtomicReference<>();
    private final AtomicInteger size = new AtomicInteger();

    public void push(T value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        while (true) {
            Node<T> currentHead = head.get();
            Node<T> newHead = new Node<>(value, currentHead);
            if (head.compareAndSet(currentHead, newHead)) {
                size.incrementAndGet();
                return;
            }
            LockSupport.parkNanos(1);
        }
    }

    public T pop() {
        while (true) {
            Node<T> currentHead = head.get();
            if (currentHead == null) {
                return null;
            }
            if (head.compareAndSet(currentHead, currentHead.next())) {
                size.decrementAndGet();
                return currentHead.value();
            }
            LockSupport.parkNanos(1);
        }
    }

    public List<T> drain() {
        Node<T> detached = head.getAndSet(null);
        List<T> result = new ArrayList<>();
        for (Node<T> node = detached; node != null; node = node.next()) {
            result.add(node.value());
        }
        size.addAndGet(-result.size());
        return result;
    }

    public List<T> snapshot(int limit) {
        List<T> result = new ArrayList<>();
        for (Node<T> node = head.get(); node != null && result.size() < limit; node = node.next()) {
            result.add(node.value());
        }
        return result;
    }

    public int size() {
        return Math.max(0, size.get());
    }

    public boolean isEmpty() {
        return head.get() == null;
    }
}
