package ba.telco.ocs.common.metrics;

import java.util.concurrent.atomic.LongAdder;

public final class LatencyStats {

    private final LongAdder count = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private final MinMaxMetric minMax = new MinMaxMetric();

    public void record(long nanos) {
        count.increment();
        totalNanos.add(nanos);
        minMax.record(nanos);
    }

    public LatencySnapshot snapshot() {
        long samples = count.sum();
        long total = totalNanos.sum();
        double avgMicros = samples == 0 ? 0 : (total / (double) samples) / 1_000.0;
        return new LatencySnapshot(samples, minMax.min() / 1_000.0, minMax.max() / 1_000.0, avgMicros);
    }

    public record LatencySnapshot(long samples, double minMicros, double maxMicros, double avgMicros) {
    }
}
