package ba.telco.ocs.campaign.entity;

import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class Campaign {

    @Getter
    private final String id;
    @Getter
    private final String name;
    @Getter
    private final String message;
    @Getter
    private final List<String> recipients;
    @Getter
    private final Instant createdAt;
    private final AtomicReference<CampaignStatus> status = new AtomicReference<>(CampaignStatus.RUNNING);
    private final AtomicInteger sent = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();

    @Getter
    private volatile Instant finishedAt;

    public Campaign(String id, String name, String message, List<String> recipients, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.message = message;
        this.recipients = List.copyOf(recipients);
        this.createdAt = createdAt;
    }

    public boolean complete(Instant now) {
        return finish(CampaignStatus.COMPLETED, now);
    }

    public boolean cancel(Instant now) {
        return finish(CampaignStatus.CANCELLED, now);
    }

    private boolean finish(CampaignStatus target, Instant now) {
        if (status.compareAndSet(CampaignStatus.RUNNING, target)) {
            finishedAt = now;
            return true;
        }
        return false;
    }

    public boolean isCancelled() {
        return status.get() == CampaignStatus.CANCELLED;
    }

    public void recordSent() {
        sent.incrementAndGet();
    }

    public void recordFailed() {
        failed.incrementAndGet();
    }

    public void recordSkipped() {
        skipped.incrementAndGet();
    }

    public CampaignStatus getStatus() {
        return status.get();
    }

    public int getSent() {
        return sent.get();
    }

    public int getFailed() {
        return failed.get();
    }

    public int getSkipped() {
        return skipped.get();
    }
}
