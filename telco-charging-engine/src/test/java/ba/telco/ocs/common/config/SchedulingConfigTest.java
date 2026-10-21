package ba.telco.ocs.common.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigTest {

    @Test
    void aBlockedJobDoesNotStopTheOtherJobs() throws Exception {
        ScheduledExecutorService scheduler = new SchedulingConfig().housekeepingScheduler();
        try {
            CountDownLatch blockerStarted = new CountDownLatch(1);
            CountDownLatch releaseBlocker = new CountDownLatch(1);
            AtomicBoolean otherJobRan = new AtomicBoolean();
            CountDownLatch otherJobDone = new CountDownLatch(1);

            scheduler.schedule(() -> {
                blockerStarted.countDown();
                try {
                    releaseBlocker.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, 0, TimeUnit.MILLISECONDS);
            assertThat(blockerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            scheduler.schedule(() -> {
                otherJobRan.set(true);
                otherJobDone.countDown();
            }, 0, TimeUnit.MILLISECONDS);

            assertThat(otherJobDone.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(otherJobRan).isTrue();
            releaseBlocker.countDown();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void poolUsesDaemonThreadsSoItNeverKeepsTheJvmAlive() throws Exception {
        ScheduledExecutorService scheduler = new SchedulingConfig().housekeepingScheduler();
        try {
            AtomicBoolean daemon = new AtomicBoolean();
            CountDownLatch done = new CountDownLatch(1);
            scheduler.execute(() -> {
                daemon.set(Thread.currentThread().isDaemon() && Thread.currentThread().getName().startsWith("housekeeping-"));
                done.countDown();
            });

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(daemon).isTrue();
            assertThat(SchedulingConfig.HOUSEKEEPING_THREADS).isEqualTo(2);
        } finally {
            scheduler.shutdownNow();
        }
    }
}
