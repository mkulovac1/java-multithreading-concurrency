package ba.telco.ocs.common.concurrency;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class BoundedBlockingBuffer<T> {

    private final ArrayDeque<T> items;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    public BoundedBlockingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.items = new ArrayDeque<>(capacity);
    }

    public void put(T item) throws InterruptedException {
        requireNonNull(item);
        lock.lockInterruptibly();
        try {
            while (items.size() == capacity) {
                notFull.await();
            }
            enqueue(item);
        } finally {
            lock.unlock();
        }
    }

    public boolean offer(T item, long timeout, TimeUnit unit) throws InterruptedException {
        requireNonNull(item);
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (items.size() == capacity) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            enqueue(item);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public T take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (items.isEmpty()) {
                notEmpty.await();
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (items.isEmpty()) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return items.size();
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return capacity;
    }

    private void enqueue(T item) {
        items.addLast(item);
        notEmpty.signal();
    }

    private T dequeue() {
        T item = items.removeFirst();
        notFull.signal();
        return item;
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new IllegalArgumentException("item must not be null");
        }
    }
}
