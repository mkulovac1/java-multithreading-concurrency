package ba.telco.ocs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class TelcoChargingEngineApplicationTest {

    @Test
    void mainStartsTheApplication() {
        assertThatCode(() -> TelcoChargingEngineApplication.main(new String[]{
                "--server.port=0", "--telco.seed.enabled=false", "--spring.main.banner-mode=off"}))
                .doesNotThrowAnyException();
    }
}
