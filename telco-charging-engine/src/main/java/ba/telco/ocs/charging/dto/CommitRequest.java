package ba.telco.ocs.charging.dto;

import jakarta.validation.constraints.Min;

public record CommitRequest(@Min(value = 0, message = "Used units cannot be negative.") long usedUnits) {
}
