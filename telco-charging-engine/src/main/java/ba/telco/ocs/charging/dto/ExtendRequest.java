package ba.telco.ocs.charging.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record ExtendRequest(
        @Min(value = 1, message = "Additional units must be at least 1.")
        @Max(value = 10_000_000, message = "Additional units can be at most 10,000,000.")
        long additionalUnits) {
}
