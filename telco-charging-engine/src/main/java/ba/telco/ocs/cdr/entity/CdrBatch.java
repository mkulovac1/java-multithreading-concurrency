package ba.telco.ocs.cdr.entity;

import ba.telco.ocs.common.concurrency.SimpleCountDownLatch;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static ba.telco.ocs.tariff.service.RatingEngine.MONEY_SCALE;

public class CdrBatch {

    private static final int MAX_FAILURE_SAMPLES = 20;

    @Getter
    private final String id;
    @Getter
    private final int totalRecords;
    @Getter
    private final Instant createdAt;
    private final Clock clock;
    private final SimpleCountDownLatch pending;

    private final AtomicInteger rejected = new AtomicInteger();
    private final AtomicInteger rated = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicLong revenueMinorUnits = new AtomicLong();
    private final Queue<String> failureSamples = new ConcurrentLinkedQueue<>();
    private final AtomicInteger failureSampleCount = new AtomicInteger();

    @Getter
    private volatile Instant completedAt;

    public CdrBatch(String id, int totalRecords, Clock clock) {
        this.id = id;
        this.totalRecords = totalRecords;
        this.clock = clock;
        this.createdAt = Instant.now(clock);
        this.pending = new SimpleCountDownLatch(totalRecords);
        if (totalRecords == 0) {
            completedAt = createdAt;
        }
    }

    public void markRated(BigDecimal cost) {
        rated.incrementAndGet();
        revenueMinorUnits.addAndGet(cost.setScale(MONEY_SCALE).unscaledValue().longValueExact());
        recordOutcome();
    }

    public void markFailed(String recordId, String reason) {
        failed.incrementAndGet();
        if (failureSampleCount.incrementAndGet() <= MAX_FAILURE_SAMPLES) {
            failureSamples.add(recordId + ": " + reason);
        }
        recordOutcome();
    }

    public void markRejected() {
        rejected.incrementAndGet();
        recordOutcome();
    }

    public boolean awaitCompletion(long timeoutMillis) throws InterruptedException {
        return pending.await(timeoutMillis);
    }

    private void recordOutcome() {
        if (pending.countDown()) {
            completedAt = Instant.now(clock);
        }
    }

    public BatchStatus getStatus() {
        return pending.getCount() == 0 ? BatchStatus.COMPLETED : BatchStatus.IN_PROGRESS;
    }

    public int getAccepted() {
        return totalRecords - rejected.get();
    }

    public int getRejected() {
        return rejected.get();
    }

    public int getRated() {
        return rated.get();
    }

    public int getFailed() {
        return failed.get();
    }

    public BigDecimal getRevenue() {
        return BigDecimal.valueOf(revenueMinorUnits.get(), MONEY_SCALE);
    }

    public List<String> getFailureSamples() {
        return List.copyOf(failureSamples);
    }
}
