package ba.telco.ocs.charging.dto;

import ba.telco.ocs.tariff.entity.ServiceType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ReserveRequest(
        @NotBlank(message = "Phone number (MSISDN) is required.")
        String msisdn,
        @NotNull(message = "Service type is required (VOICE, SMS, DATA).")
        ServiceType serviceType,
        @Min(value = 1, message = "Requested units must be at least 1.")
        @Max(value = 10_000_000, message = "Requested units can be at most 10,000,000.")
        long requestedUnits) {
}
