package ba.telco.ocs.tariff;

import ba.telco.ocs.TestFixtures;
import ba.telco.ocs.tariff.entity.ServiceType;
import ba.telco.ocs.tariff.entity.TariffPlan;
import ba.telco.ocs.tariff.repository.TariffRepository;
import ba.telco.ocs.tariff.service.RatingEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static ba.telco.ocs.TestFixtures.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

class RatingEngineTest {

    private final RatingEngine engine = new RatingEngine();

    @ParameterizedTest(name = "BASIC (60/60) {0}s -> {1}")
    @CsvSource({"0, 0.0000", "1, 0.1200", "60, 0.1200", "61, 0.2400", "125, 0.3600"})
    void basicVoiceIsChargedPerStartedMinute(long seconds, String expected) {
        assertThat(engine.price(TestFixtures.BASIC, ServiceType.VOICE, seconds)).isEqualByComparingTo(expected);
    }

    @ParameterizedTest(name = "SMART (1/1) {0}s -> {1}")
    @CsvSource({"1, 0.0010", "30, 0.0300", "61, 0.0610"})
    void smartVoiceIsChargedPerSecond(long seconds, String expected) {
        assertThat(engine.price(TestFixtures.SMART, ServiceType.VOICE, seconds)).isEqualByComparingTo(expected);
    }

    @Test
    void smsAndDataPricing() {
        assertThat(engine.price(TestFixtures.SMART, ServiceType.SMS, 3)).isEqualByComparingTo("0.15");
        assertThat(engine.price(TestFixtures.SMART, ServiceType.DATA, 2048)).isEqualByComparingTo("0.02");
        assertThat(engine.price(TestFixtures.SMART, ServiceType.DATA, 512)).isEqualByComparingTo("0.005");
    }

    @Test
    void affordableUnitsNeverExceedAvailableCredit() {
        BigDecimal available = new BigDecimal("0.25");
        long units = engine.affordableUnits(TestFixtures.BASIC, ServiceType.VOICE, 600, available);

        assertThat(units).isEqualTo(120);
        assertThat(engine.price(TestFixtures.BASIC, ServiceType.VOICE, units)).isLessThanOrEqualTo(available);
        assertThat(engine.affordableUnits(TestFixtures.BASIC, ServiceType.SMS, 10, new BigDecimal("0.07"))).isZero();
        assertThat(engine.affordableUnits(TestFixtures.SMART, ServiceType.SMS, 10, new BigDecimal("1.00"))).isEqualTo(10);
    }

    @Test
    void readersAlwaysSeeAConsistentTariffWhileItIsReplaced() throws Exception {
        TariffRepository repository = new TariffRepository();
        TariffPlan cheap = new TariffPlan("X", "x", new BigDecimal("0.01"), new BigDecimal("0.01"), new BigDecimal("0.01"), 1);
        TariffPlan expensive = new TariffPlan("X", "x", new BigDecimal("0.99"), new BigDecimal("0.99"), new BigDecimal("0.99"), 1);
        repository.insert(cheap);
        AtomicInteger torn = new AtomicInteger();

        runConcurrently(8, index -> {
            for (int i = 0; i < 20_000; i++) {
                if (index == 0) {
                    repository.replace(i % 2 == 0 ? expensive : cheap);
                } else {
                    TariffPlan plan = repository.findByCode("X").orElseThrow();
                    if (plan.voicePricePerMinute().compareTo(plan.smsPrice()) != 0) {
                        torn.incrementAndGet();
                    }
                }
            }
        });

        assertThat(torn.get()).isZero();
    }
}
