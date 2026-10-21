package ba.telco.ocs.campaign.dto;

import ba.telco.ocs.campaign.entity.CampaignStatus;

import java.time.Instant;

public record CampaignResponse(
        String campaignId,
        String name,
        CampaignStatus status,
        int totalRecipients,
        int sent,
        int failed,
        int skipped,
        double progressPercent,
        Instant createdAt,
        Instant finishedAt,
        Long durationMillis) {
}
