package ba.telco.ocs.subscriber.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record TransferRequest(
        @NotBlank(message = "Sender number is required.")
        String fromMsisdn,
        @NotBlank(message = "Recipient number is required.")
        String toMsisdn,
        @NotNull(message = "Amount is required.")
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01.")
        @Digits(integer = 10, fraction = 4, message = "Amount can have at most 4 decimal places.")
        BigDecimal amount) {
}
