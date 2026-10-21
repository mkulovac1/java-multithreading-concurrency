package ba.telco.ocs.cdr.dto;

import ba.telco.ocs.tariff.entity.ServiceType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CdrRequest(
        @NotBlank(message = "Record ID is required.")
        @Size(max = 64, message = "Record ID can have at most 64 characters.")
        String recordId,
        @NotBlank(message = "Phone number (MSISDN) is required.")
        String msisdn,
        @NotNull(message = "Service type is required (VOICE, SMS, DATA).")
        ServiceType serviceType,
        @Min(value = 0, message = "Units cannot be negative.")
        @Max(value = 1_000_000_000, message = "Units can be at most 1,000,000,000.")
        long units,
        @NotNull(message = "Event time is required.")
        Instant eventTime) {
}
