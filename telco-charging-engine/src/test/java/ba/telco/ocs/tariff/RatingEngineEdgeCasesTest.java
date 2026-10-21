package ba.telco.ocs.tariff;

import ba.telco.ocs.TestFixtures;
import ba.telco.ocs.tariff.entity.ServiceType;
import ba.telco.ocs.tariff.entity.TariffPlan;
import ba.telco.ocs.tariff.repository.TariffRepository;
import ba.telco.ocs.tariff.service.RatingEngine;
import ba.telco.ocs.tariff.service.TariffService;
import ba.telco.ocs.common.exception.InvalidStateException;
import ba.telco.ocs.common.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RatingEngineEdgeCasesTest {

    private final RatingEngine engine = new RatingEngine();
    private final TariffPlan free = new TariffPlan("FREE", "Free", BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, 1);

    @Test
    void negativeUnitsAreRejected() {
        assertThatThrownBy(() -> engine.price(TestFixtures.BASIC, ServiceType.SMS, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void billableUnitsOnlyRoundVoice() {
        assertThat(engine.billableUnits(TestFixtures.BASIC, ServiceType.VOICE, 0)).isZero();
        assertThat(engine.billableUnits(TestFixtures.BASIC, ServiceType.VOICE, 61)).isEqualTo(120);
        assertThat(engine.billableUnits(TestFixtures.BASIC, ServiceType.DATA, 61)).isEqualTo(61);
    }

    @Test
    void hugeVoiceDurationsFailLoudlyInsteadOfProducingNegativeCosts() {
        assertThatThrownBy(() -> engine.price(TestFixtures.BASIC, ServiceType.VOICE, Long.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class);
        assertThat(engine.price(TestFixtures.BASIC, ServiceType.VOICE, 1_000_000_000).signum()).isPositive();
    }

    @Test
    void freeServicesAreGrantedInFullEvenWithoutCredit() {
        assertThat(engine.affordableUnits(free, ServiceType.VOICE, 500, BigDecimal.ZERO)).isEqualTo(500);
        assertThat(engine.affordableUnits(free, ServiceType.SMS, 7, new BigDecimal("-1"))).isEqualTo(7);
    }

    @Test
    void pricedServicesAreDeniedWithoutCredit() {
        assertThat(engine.affordableUnits(TestFixtures.BASIC, ServiceType.SMS, 5, BigDecimal.ZERO)).isZero();
    }

    @Test
    void fullRequestIsGrantedWhenAffordableAndPartiallyOtherwise() {
        assertThat(engine.affordableUnits(TestFixtures.BASIC, ServiceType.SMS, 5, new BigDecimal("10"))).isEqualTo(5);
        assertThat(engine.affordableUnits(TestFixtures.BASIC, ServiceType.SMS, 5, new BigDecimal("0.20"))).isEqualTo(2);
        assertThat(engine.affordableUnits(TestFixtures.BASIC, ServiceType.DATA, 10_240, new BigDecimal("0.05")))
                .isEqualTo(2_562);
    }

    @Test
    void tariffServiceCreatesUpdatesAndListsPlans() {
        TariffService service = new TariffService(new TariffRepository());
        service.create(TestFixtures.BASIC);
        service.create(TestFixtures.SMART);

        assertThatThrownBy(() -> service.create(TestFixtures.BASIC)).isInstanceOf(InvalidStateException.class);
        assertThat(service.exists("BASIC")).isTrue();
        assertThat(service.exists("NOPE")).isFalse();
        assertThat(service.getAll()).extracting(TariffPlan::code).containsExactly("BASIC", "SMART");

        TariffPlan changed = new TariffPlan("BASIC", "Changed", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1);
        assertThat(service.update(changed)).isEqualTo(changed);
        assertThat(service.getByCode("BASIC").name()).isEqualTo("Changed");
        assertThatThrownBy(() -> service.update(free)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getByCode("FREE")).isInstanceOf(NotFoundException.class);
    }
}
