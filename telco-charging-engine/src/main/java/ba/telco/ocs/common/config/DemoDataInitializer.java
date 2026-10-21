package ba.telco.ocs.common.config;

import ba.telco.ocs.subscriber.service.SubscriberService;
import ba.telco.ocs.tariff.entity.TariffPlan;
import ba.telco.ocs.tariff.service.TariffService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@ConditionalOnProperty(prefix = "telco.seed", name = "enabled", havingValue = "true")
@Slf4j
@RequiredArgsConstructor
public class DemoDataInitializer implements ApplicationRunner {

    private final TariffService tariffService;
    private final SubscriberService subscriberService;

    @Override
    public void run(ApplicationArguments args) {
        tariffService.create(new TariffPlan("BASIC", "Basic prepaid",
                new BigDecimal("0.12"), new BigDecimal("0.08"), new BigDecimal("0.02"), 60));
        tariffService.create(new TariffPlan("SMART", "Smart (per-second billing)",
                new BigDecimal("0.08"), new BigDecimal("0.05"), new BigDecimal("0.01"), 1));
        tariffService.create(new TariffPlan("BUSINESS", "Business",
                new BigDecimal("0.05"), new BigDecimal("0.03"), new BigDecimal("0.005"), 1));

        subscriberService.create("38761000001", "Amra Hodžić", "BASIC", new BigDecimal("10.00"));
        subscriberService.create("38761000002", "Haris Begić", "SMART", new BigDecimal("25.00"));
        subscriberService.create("38762000003", "Lejla Kovačević", "BUSINESS", new BigDecimal("100.00"));
        subscriberService.create("38765000004", "Emir Mujić", "SMART", new BigDecimal("0.50"));
        log.info("Demo data loaded: 3 tariffs, 4 subscribers");
    }
}
