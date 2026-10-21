package ba.telco.ocs.cdr;

import ba.telco.ocs.TestFixtures;
import ba.telco.ocs.cdr.rating.ReratingSimulationService;
import ba.telco.ocs.common.exception.BadRequestException;
import ba.telco.ocs.common.exception.ResourceBusyException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReratingValidationTest {

    private final TestFixtures fixtures = new TestFixtures();
    private final ReratingSimulationService service = new ReratingSimulationService(fixtures.tariffService,
            fixtures.ratingEngine);

    @Test
    void threadCountMustBeWithinLimits() {
        assertThatThrownBy(() -> service.simulate("BASIC", 100, 0, 1)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.simulate("BASIC", 100, 1_000_000, 1)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void cdrCountMustBeWithinLimits() {
        assertThatThrownBy(() -> service.simulate("BASIC", 1, 2, 1)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.simulate("BASIC", ReratingSimulationService.MAX_CDR_COUNT + 1, 2, 1))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void onlyOneSimulationRunsAtATime() throws Exception {
        Semaphore permit = (Semaphore) ReflectionTestUtils.getField(service, "simulationPermit");
        permit.acquire();

        assertThatThrownBy(() -> service.simulate("BASIC", 100, 1, 1)).isInstanceOf(ResourceBusyException.class);
        permit.release();

        assertThat(service.simulate("BASIC", 100, 1, 1).resultsMatch()).isTrue();
    }

    @Test
    void interruptedCoordinatorPropagatesTheInterruption() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> service.simulate("BASIC", 1_000, 2, 1))
                    .isInstanceOf(InterruptedException.class);
        } finally {
            Thread.interrupted();
        }
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }
}
