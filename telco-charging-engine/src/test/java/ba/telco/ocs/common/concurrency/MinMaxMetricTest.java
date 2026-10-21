package ba.telco.ocs.common.concurrency;

import ba.telco.ocs.common.metrics.LatencyStats;
import ba.telco.ocs.common.metrics.MinMaxMetric;
import org.junit.jupiter.api.Test;

import static ba.telco.ocs.TestFixtures.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

class MinMaxMetricTest {

    @Test
    void reportsZeroWhenEmpty() {
        MinMaxMetric metric = new MinMaxMetric();
        assertThat(metric.min()).isZero();
        assertThat(metric.max()).isZero();
    }

    @Test
    void concurrentRecordsKeepTrueMinimumAndMaximum() throws Exception {
        MinMaxMetric metric = new MinMaxMetric();
        LatencyStats stats = new LatencyStats();
        int threads = 16;
        int perThread = 50_000;

        runConcurrently(threads, index -> {
            for (int i = 1; i <= perThread; i++) {
                long value = (long) index * perThread + i;
                metric.record(value);
                stats.record(value);
            }
        });

        assertThat(metric.min()).isEqualTo(1);
        assertThat(metric.max()).isEqualTo((long) threads * perThread);
        assertThat(stats.snapshot().samples()).isEqualTo((long) threads * perThread);
    }
}
