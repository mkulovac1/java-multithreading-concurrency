package ba.telco.ocs.common.metrics;

import java.util.concurrent.atomic.AtomicLong;

public final class MinMaxMetric {

    private final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong max = new AtomicLong(Long.MIN_VALUE);

    public void record(long value) {
        long currentMin;
        do {
            currentMin = min.get();
            if (value >= currentMin) {
                break;
            }
        } while (!min.compareAndSet(currentMin, value));

        long currentMax;
        do {
            currentMax = max.get();
            if (value <= currentMax) {
                break;
            }
        } while (!max.compareAndSet(currentMax, value));
    }

    public long min() {
        long value = min.get();
        return value == Long.MAX_VALUE ? 0 : value;
    }

    public long max() {
        long value = max.get();
        return value == Long.MIN_VALUE ? 0 : value;
    }
}
