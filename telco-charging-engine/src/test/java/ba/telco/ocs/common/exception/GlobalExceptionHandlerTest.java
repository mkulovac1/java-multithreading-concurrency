package ba.telco.ocs.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void interruptionBecomes503AndRestoresTheInterruptFlag() {
        ProblemDetail problem = handler.handleInterrupted(new InterruptedException());

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void businessExceptionsCarryTheirStatus() {
        assertThat(new NotFoundException("x").getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handleBusiness(new InsufficientFundsException("x")).getStatus()).isEqualTo(402);
        assertThat(handler.handleBusiness(new ResourceBusyException("x")).getStatus()).isEqualTo(503);
    }
}
