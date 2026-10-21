package ba.telco.ocs.fraud.service;

import ba.telco.ocs.cdr.entity.Cdr;
import ba.telco.ocs.common.concurrency.LockFreeStack;
import ba.telco.ocs.common.config.TelcoProperties;
import ba.telco.ocs.fraud.entity.FraudAlert;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

@Service
@Slf4j
public class FraudDetectionService {

    private static final long WINDOW_MILLIS = Duration.ofMinutes(1).toMillis();
    private static final Duration WINDOW_RETENTION = Duration.ofMinutes(10);

    private record Window(long startMillis, int count, boolean alerted) {
    }

    private final ConcurrentMap<String, AtomicReference<Window>> windows = new ConcurrentHashMap<>();
    private final LockFreeStack<FraudAlert> alerts = new LockFreeStack<>();
    private final LongAdder alertsRaised = new LongAdder();
    private final int maxEventsPerMinute;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;

    public FraudDetectionService(TelcoProperties properties, Clock clock, ScheduledExecutorService housekeepingScheduler) {
        this.maxEventsPerMinute = properties.fraud().maxEventsPerMinute();
        this.clock = clock;
        this.scheduler = housekeepingScheduler;
    }

    @PostConstruct
    void scheduleCleanup() {
        scheduler.scheduleWithFixedDelay(this::purgeStaleWindows, 1, 1, TimeUnit.MINUTES);
    }

    public void inspect(Cdr cdr) {
        Instant now = Instant.now(clock);
        Instant eventTime = cdr.eventTime().isAfter(now) ? now : cdr.eventTime();
        long eventWindow = Math.floorDiv(eventTime.toEpochMilli(), WINDOW_MILLIS) * WINDOW_MILLIS;
        AtomicReference<Window> reference = windows.computeIfAbsent(cdr.msisdn(),
                key -> new AtomicReference<>(new Window(eventWindow, 0, false)));

        while (true) {
            Window current = reference.get();
            Window next;
            if (eventWindow > current.startMillis()) {
                next = new Window(eventWindow, 1, false);
            } else if (eventWindow == current.startMillis()) {
                next = new Window(eventWindow, current.count() + 1, current.alerted());
            } else {
                return;
            }
            boolean raiseAlert = !next.alerted() && next.count() > maxEventsPerMinute;
            if (raiseAlert) {
                next = new Window(next.startMillis(), next.count(), true);
            }
            if (reference.compareAndSet(current, next)) {
                if (raiseAlert) {
                    raise(cdr.msisdn(), next);
                }
                return;
            }
        }
    }

    private void raise(String msisdn, Window window) {
        alerts.push(new FraudAlert(UUID.randomUUID().toString(), msisdn, window.count(),
                Instant.ofEpochMilli(window.startMillis()), Instant.now(clock)));
        alertsRaised.increment();
        log.warn("Fraud alert: {} exceeded {} events/min", msisdn, maxEventsPerMinute);
    }

    public List<FraudAlert> getAlerts(int limit) {
        return alerts.snapshot(limit);
    }

    public List<FraudAlert> acknowledgeAll() {
        return alerts.drain();
    }

    public FraudAlert acknowledgeLatest() {
        return alerts.pop();
    }

    public FraudStats stats() {
        return new FraudStats(alerts.size(), alertsRaised.sum(), windows.size(), maxEventsPerMinute);
    }

    void purgeStaleWindows() {
        try {
            long threshold = Instant.now(clock).minus(WINDOW_RETENTION).toEpochMilli();
            windows.values().removeIf(reference -> reference.get().startMillis() < threshold);
        } catch (RuntimeException e) {
            log.error("Fraud window cleanup failed", e);
        }
    }

    public record FraudStats(int openAlerts, long alertsRaised, int trackedSubscribers, int maxEventsPerMinute) {
    }
}
