package ba.telco.ocs.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI telcoOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Telco Charging Engine API")
                .version("1.0.0")
                .description("""
                        Online Charging System (prepaid credit, unit reservations), CDR mediation \
                        (producer-consumer pipeline), re-rating simulation (parallel processing), \
                        bulk SMS campaigns (virtual threads + SMPP window) and fraud detection (lock-free).""")
                .contact(new Contact().name("Telco Platform Team"))
                .license(new License().name("MIT")));
    }
}
