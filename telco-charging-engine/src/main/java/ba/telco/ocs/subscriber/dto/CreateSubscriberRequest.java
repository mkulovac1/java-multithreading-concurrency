package ba.telco.ocs.subscriber.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateSubscriberRequest(
        @NotBlank(message = "Phone number (MSISDN) is required.")
        @Pattern(regexp = "387\\d{8,9}", message = "MSISDN must be in the format 387XXXXXXXX (8-9 digits after 387).")
        String msisdn,
        @NotBlank(message = "Subscriber name is required.")
        @Size(max = 100, message = "Name can have at most 100 characters.")
        String name,
        @NotBlank(message = "Tariff code is required.")
        String tariffCode,
        @NotNull(message = "Initial balance is required.")
        @DecimalMin(value = "0.0", message = "Initial balance cannot be negative.")
        @Digits(integer = 10, fraction = 4, message = "Amount can have at most 4 decimal places.")
        BigDecimal initialBalance) {
}
