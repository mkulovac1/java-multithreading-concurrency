package ba.telco.ocs.campaign.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Recipients are given either as a list (recipients) or as a number range (numberRange) - exactly one of the two.")
public record CreateCampaignRequest(
        @NotBlank(message = "Campaign name is required.")
        @Size(max = 100, message = "Campaign name can have at most 100 characters.")
        String name,
        @NotBlank(message = "Message text is required.")
        @Size(max = 918, message = "Message can have at most 918 characters (6 SMS segments).")
        String message,
        List<@Pattern(regexp = "387\\d{8,9}", message = "Every recipient must be in the format 387XXXXXXXX.") String> recipients,
        @Valid
        NumberRangeRequest numberRange) {
}
