package ba.telco.ocs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TelcoChargingEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(TelcoChargingEngineApplication.class, args);
    }
}
