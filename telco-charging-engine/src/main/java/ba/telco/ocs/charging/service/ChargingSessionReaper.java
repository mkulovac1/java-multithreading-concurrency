package ba.telco.ocs.charging.service;

import ba.telco.ocs.common.config.TelcoProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class ChargingSessionReaper {

    private final ChargingService chargingService;
    private final ScheduledExecutorService scheduler;
    private final Duration interval;
    private final Duration retention;

    public ChargingSessionReaper(ChargingService chargingService, ScheduledExecutorService housekeepingScheduler,
                                 TelcoProperties properties) {
        this.chargingService = chargingService;
        this.scheduler = housekeepingScheduler;
        this.interval = properties.charging().reaperInterval();
        this.retention = properties.charging().sessionRetention();
    }

    @PostConstruct
    void start() {
        scheduler.scheduleWithFixedDelay(this::runOnce, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void runOnce() {
        try {
            int expired = chargingService.expireSessions();
            int purged = chargingService.purgeClosedSessions(retention);
            if (expired > 0 || purged > 0) {
                log.info("Reaper: expired {} reservations, purged {} closed sessions", expired, purged);
            }
        } catch (RuntimeException e) {
            log.error("Charging session reaper run failed", e);
        }
    }
}
