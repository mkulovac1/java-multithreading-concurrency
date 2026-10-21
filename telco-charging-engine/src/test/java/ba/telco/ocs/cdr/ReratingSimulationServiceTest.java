package ba.telco.ocs.cdr;

import ba.telco.ocs.TestFixtures;
import ba.telco.ocs.cdr.rating.ReratingSimulationService;
import ba.telco.ocs.cdr.rating.ReratingSimulationService.ReratingResult;
import ba.telco.ocs.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReratingSimulationServiceTest {

    private final TestFixtures fixtures = new TestFixtures();
    private final ReratingSimulationService service =
            new ReratingSimulationService(fixtures.tariffService, fixtures.ratingEngine);

    @Test
    void parallelResultEqualsSequentialResult() throws InterruptedException {
        ReratingResult result = service.simulate("SMART", 300_001, 4, 42);

        assertThat(result.resultsMatch()).isTrue();
        assertThat(result.totalRevenue()).isPositive();
    }

    @Test
    void sameSeedGivesSameRevenueRegardlessOfThreadCount() throws InterruptedException {
        ReratingResult oneThread = service.simulate("BASIC", 50_000, 1, 7);
        ReratingResult threeThreads = service.simulate("BASIC", 50_000, 3, 7);

        assertThat(threeThreads.totalRevenue()).isEqualByComparingTo(oneThread.totalRevenue());
    }

    @Test
    void validatesThreadCount() {
        assertThatThrownBy(() -> service.simulate("SMART", 1_000, 0, 1)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.simulate("SMART", 1_000, 10_000, 1)).isInstanceOf(BadRequestException.class);
    }
}
