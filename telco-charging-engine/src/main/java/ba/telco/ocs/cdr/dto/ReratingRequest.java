package ba.telco.ocs.cdr.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ReratingRequest(
        @NotBlank(message = "Tariff code is required.")
        String tariffCode,
        @Min(value = 1, message = "CDR count must be at least 1.")
        @Max(value = 10_000_000, message = "CDR count can be at most 10,000,000.")
        int cdrCount,
        @Min(value = 1, message = "Thread count must be at least 1.")
        int threads,
        long seed) {
}
