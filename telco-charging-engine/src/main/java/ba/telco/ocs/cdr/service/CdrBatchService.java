package ba.telco.ocs.cdr.service;

import ba.telco.ocs.cdr.entity.Cdr;
import ba.telco.ocs.cdr.entity.CdrBatch;
import ba.telco.ocs.cdr.pipeline.CdrPipeline;
import ba.telco.ocs.common.config.TelcoProperties;
import ba.telco.ocs.common.exception.NotFoundException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class CdrBatchService {

    private final CdrPipeline pipeline;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final Duration retention;
    private final ConcurrentMap<String, CdrBatch> batches = new ConcurrentHashMap<>();

    public CdrBatchService(CdrPipeline pipeline, Clock clock, ScheduledExecutorService housekeepingScheduler,
                           TelcoProperties properties) {
        this.pipeline = pipeline;
        this.clock = clock;
        this.scheduler = housekeepingScheduler;
        this.retention = properties.pipeline().batchRetention();
    }

    @PostConstruct
    void scheduleCleanup() {
        scheduler.scheduleWithFixedDelay(this::purgeCompleted, 1, 1, TimeUnit.MINUTES);
    }

    public CdrBatch submit(List<Cdr> cdrs, long waitMillis) throws InterruptedException {
        CdrBatch batch = new CdrBatch(UUID.randomUUID().toString(), cdrs.size(), clock);
        batches.put(batch.getId(), batch);
        pipeline.submit(batch, cdrs);
        if (waitMillis > 0) {
            batch.awaitCompletion(waitMillis);
        }
        return batch;
    }

    public CdrBatch get(String batchId, long waitMillis) throws InterruptedException {
        CdrBatch batch = batches.get(batchId);
        if (batch == null) {
            throw new NotFoundException("Batch " + batchId + " does not exist or has expired.");
        }
        if (waitMillis > 0) {
            batch.awaitCompletion(waitMillis);
        }
        return batch;
    }

    void purgeCompleted() {
        try {
            Instant threshold = Instant.now(clock).minus(retention);
            batches.values().removeIf(batch -> {
                Instant completedAt = batch.getCompletedAt();
                return completedAt != null && completedAt.isBefore(threshold);
            });
        } catch (RuntimeException e) {
            log.error("Batch cleanup failed", e);
        }
    }
}
