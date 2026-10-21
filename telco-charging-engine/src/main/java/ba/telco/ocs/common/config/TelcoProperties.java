package ba.telco.ocs.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "telco")
public record TelcoProperties(
        @Valid @NotNull Charging charging,
        @Valid @NotNull Pipeline pipeline,
        @Valid @NotNull Campaign campaign,
        @Valid @NotNull Fraud fraud,
        @Valid @NotNull Seed seed) {

    public record Charging(@NotNull Duration lockTimeout, @NotNull Duration sessionTtl, @NotNull Duration reaperInterval,
                           @NotNull Duration sessionRetention) {
    }

    public record Pipeline(@Min(1) int bufferCapacity, @Min(1) int workers,
                           @NotNull Duration offerTimeout, @NotNull Duration batchRetention) {
    }

    public record Campaign(@Min(1) int smscWindowSize, @NotNull Duration smscMinLatency, @NotNull Duration smscMaxLatency,
                           @DecimalMin("0.0") @DecimalMax("1.0") double smscFailureRate, @Min(1) int maxRecipients) {
    }

    public record Fraud(@Min(1) int maxEventsPerMinute) {
    }

    public record Seed(boolean enabled) {
    }
}
