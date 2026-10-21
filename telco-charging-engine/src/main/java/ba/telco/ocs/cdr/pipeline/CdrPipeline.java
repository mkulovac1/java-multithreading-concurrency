package ba.telco.ocs.cdr.pipeline;

import ba.telco.ocs.cdr.entity.Cdr;
import ba.telco.ocs.cdr.entity.CdrBatch;
import ba.telco.ocs.cdr.service.CdrRatingService;
import ba.telco.ocs.common.concurrency.BoundedBlockingBuffer;
import ba.telco.ocs.common.concurrency.NamedThreadFactory;
import ba.telco.ocs.common.config.TelcoProperties;
import ba.telco.ocs.common.exception.BusinessException;
import ba.telco.ocs.common.metrics.LatencyStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

@Component
@Slf4j
public class CdrPipeline implements SmartLifecycle {

    private static final long POLL_MILLIS = 200;
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final CdrRatingService ratingService;
    private final BoundedBlockingBuffer<CdrJob> buffer;
    private final int workerCount;
    private final Duration offerTimeout;
    private final NamedThreadFactory threadFactory = new NamedThreadFactory("cdr-rater", false);
    private final List<Thread> workers = new ArrayList<>();

    private final LongAdder rated = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LatencyStats endToEndLatency = new LatencyStats();

    private volatile boolean accepting;
    private volatile boolean running;

    public CdrPipeline(CdrRatingService ratingService, TelcoProperties properties) {
        this.ratingService = ratingService;
        this.buffer = new BoundedBlockingBuffer<>(properties.pipeline().bufferCapacity());
        this.workerCount = properties.pipeline().workers();
        this.offerTimeout = properties.pipeline().offerTimeout();
    }

    public void submit(CdrBatch batch, List<Cdr> cdrs) {
        boolean saturated = !accepting;
        for (Cdr cdr : cdrs) {
            if (saturated) {
                reject(batch);
                continue;
            }
            try {
                if (!buffer.offer(new CdrJob(cdr, batch, System.nanoTime()), offerTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    saturated = true;
                    reject(batch);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                saturated = true;
                reject(batch);
            }
        }
    }

    private void reject(CdrBatch batch) {
        rejected.increment();
        batch.markRejected();
    }

    private void workerLoop() {
        try {
            while (true) {
                CdrJob job = buffer.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (job == null) {
                    if (!accepting) {
                        return;
                    }
                    continue;
                }
                process(job);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("{} interrupted, {} CDRs left unprocessed in buffer", Thread.currentThread().getName(), buffer.size());
        }
    }

    private void process(CdrJob job) {
        Cdr cdr = job.cdr();
        try {
            job.batch().markRated(ratingService.rate(cdr));
            rated.increment();
        } catch (BusinessException e) {
            job.batch().markFailed(cdr.recordId(), e.getMessage());
            failed.increment();
        } catch (RuntimeException e) {
            log.error("Unexpected error rating CDR {}", cdr.recordId(), e);
            job.batch().markFailed(cdr.recordId(), "Internal rating error.");
            failed.increment();
        } finally {
            endToEndLatency.record(System.nanoTime() - job.enqueuedAtNanos());
        }
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        accepting = true;
        for (int i = 0; i < workerCount; i++) {
            Thread worker = threadFactory.newThread(this::workerLoop);
            workers.add(worker);
            worker.start();
        }
        running = true;
        log.info("CDR pipeline started with {} workers, buffer capacity {}", workerCount, buffer.capacity());
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        accepting = false;
        long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos();
        for (Thread worker : workers) {
            try {
                long remainingMillis = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
                worker.join(remainingMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (Thread worker : workers) {
            if (worker.isAlive()) {
                worker.interrupt();
            }
        }
        workers.clear();
        running = false;
        log.info("CDR pipeline stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 4096;
    }

    public PipelineStats stats() {
        return new PipelineStats(running, workerCount, buffer.size(), buffer.capacity(),
                rated.sum(), failed.sum(), rejected.sum(), endToEndLatency.snapshot());
    }

    public record PipelineStats(boolean running, int workers, int bufferedRecords, int bufferCapacity, long rated,
                                long failed, long rejected, LatencyStats.LatencySnapshot endToEndLatency) {
    }
}
