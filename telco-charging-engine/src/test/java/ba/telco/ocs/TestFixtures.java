package ba.telco.ocs;

import ba.telco.ocs.common.config.TelcoProperties;
import ba.telco.ocs.subscriber.repository.SubscriberRepository;
import ba.telco.ocs.subscriber.service.AccountLocks;
import ba.telco.ocs.subscriber.service.SubscriberService;
import ba.telco.ocs.tariff.entity.TariffPlan;
import ba.telco.ocs.tariff.repository.TariffRepository;
import ba.telco.ocs.tariff.service.RatingEngine;
import ba.telco.ocs.tariff.service.TariffService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

public final class TestFixtures {

    public static final TariffPlan SMART = new TariffPlan("SMART", "Smart",
            new BigDecimal("0.06"), new BigDecimal("0.05"), new BigDecimal("0.01"), 1);
    public static final TariffPlan BASIC = new TariffPlan("BASIC", "Basic",
            new BigDecimal("0.12"), new BigDecimal("0.08"), new BigDecimal("0.02"), 60);

    public final TelcoProperties properties;
    public final Clock clock;
    public final TariffService tariffService;
    public final RatingEngine ratingEngine = new RatingEngine();
    public final AccountLocks accountLocks;
    public final SubscriberService subscriberService;

    public TestFixtures() {
        this(Clock.systemUTC(), Duration.ofSeconds(5));
    }

    public TestFixtures(Clock clock, Duration lockTimeout) {
        this.clock = clock;
        this.properties = properties(lockTimeout);
        this.tariffService = new TariffService(new TariffRepository());
        tariffService.create(SMART);
        tariffService.create(BASIC);
        this.accountLocks = new AccountLocks(properties);
        this.subscriberService = new SubscriberService(new SubscriberRepository(), tariffService, accountLocks, clock);
    }

    public static TelcoProperties properties(Duration lockTimeout) {
        return new TelcoProperties(
                new TelcoProperties.Charging(lockTimeout, Duration.ofMinutes(5), Duration.ofSeconds(5), Duration.ofHours(1)),
                new TelcoProperties.Pipeline(1_000, 4, Duration.ofMillis(500), Duration.ofHours(1)),
                new TelcoProperties.Campaign(10, Duration.ofMillis(1), Duration.ofMillis(5), 0.0, 100_000),
                new TelcoProperties.Fraud(30),
                new TelcoProperties.Seed(false));
    }

    public static void runConcurrently(int threads, IntConsumer task) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            Future<?>[] futures = new Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures[i] = executor.submit(() -> {
                    startGate.await();
                    task.accept(index);
                    return null;
                });
            }
            startGate.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
    }
}
