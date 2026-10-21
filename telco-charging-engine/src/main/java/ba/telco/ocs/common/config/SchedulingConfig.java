package ba.telco.ocs.common.config;

import ba.telco.ocs.common.concurrency.NamedThreadFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class SchedulingConfig {

    static final int HOUSEKEEPING_THREADS = 2;

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService housekeepingScheduler() {
        return Executors.newScheduledThreadPool(HOUSEKEEPING_THREADS, new NamedThreadFactory("housekeeping", true));
    }
}
