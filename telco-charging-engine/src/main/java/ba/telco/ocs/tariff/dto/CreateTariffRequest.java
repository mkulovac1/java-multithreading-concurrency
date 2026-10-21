package ba.telco.ocs.tariff.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateTariffRequest(
        @NotBlank(message = "Tariff code is required.")
        @Pattern(regexp = "[A-Z0-9_]{2,20}", message = "Tariff code may contain only uppercase letters, digits and _ (2-20 characters).")
        String code,
        @NotBlank(message = "Tariff name is required.")
        @Size(max = 100, message = "Tariff name can have at most 100 characters.")
        String name,
        @NotNull(message = "Price per minute is required.")
        @DecimalMin(value = "0.0", message = "Price per minute cannot be negative.")
        @DecimalMax(value = "1000.0", message = "Price per minute can be at most 1000.")
        BigDecimal voicePricePerMinute,
        @NotNull(message = "SMS price is required.")
        @DecimalMin(value = "0.0", message = "SMS price cannot be negative.")
        @DecimalMax(value = "1000.0", message = "SMS price can be at most 1000.")
        BigDecimal smsPrice,
        @NotNull(message = "Price per MB is required.")
        @DecimalMin(value = "0.0", message = "Price per MB cannot be negative.")
        @DecimalMax(value = "1000.0", message = "Price per MB can be at most 1000.")
        BigDecimal dataPricePerMb,
        @Min(value = 1, message = "Billing increment must be at least 1 second.")
        @Max(value = 60, message = "Billing increment can be at most 60 seconds.")
        int voiceBillingIncrementSeconds) {
}
