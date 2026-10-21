package ba.telco.ocs.campaign.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record NumberRangeRequest(
        @NotBlank(message = "First number of the range is required.")
        @Pattern(regexp = "387\\d{8,9}", message = "First number must be in the format 387XXXXXXXX.")
        String firstMsisdn,
        @Min(value = 1, message = "Range must contain at least one number.")
        @Max(value = 100_000, message = "Range can contain at most 100,000 numbers.")
        int count) {
}
