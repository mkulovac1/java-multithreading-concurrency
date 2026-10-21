package ba.telco.ocs.common.concurrency;

public final class SimpleCountDownLatch {

    private int count;

    public SimpleCountDownLatch(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count cannot be negative");
        }
        this.count = count;
    }

    public synchronized boolean countDown() {
        if (count == 0) {
            return false;
        }
        count--;
        if (count == 0) {
            notifyAll();
            return true;
        }
        return false;
    }

    public synchronized void await() throws InterruptedException {
        while (count > 0) {
            wait();
        }
    }

    public synchronized boolean await(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (count > 0) {
            long remainingMillis = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMillis <= 0) {
                return false;
            }
            wait(remainingMillis);
        }
        return true;
    }

    public synchronized int getCount() {
        return count;
    }
}
