package ba.telco.ocs.campaign.mapper;

import ba.telco.ocs.campaign.dto.CampaignResponse;
import ba.telco.ocs.campaign.dto.CreateCampaignRequest;
import ba.telco.ocs.campaign.dto.NumberRangeRequest;
import ba.telco.ocs.campaign.entity.Campaign;
import ba.telco.ocs.common.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.stream.LongStream;

@Component
public class CampaignMapper {

    public List<String> toRecipients(CreateCampaignRequest request) {
        boolean hasList = request.recipients() != null && !request.recipients().isEmpty();
        boolean hasRange = request.numberRange() != null;
        if (hasList == hasRange) {
            throw new BadRequestException("Provide either a recipient list or a number range (exactly one).");
        }
        return hasList ? List.copyOf(request.recipients()) : expand(request.numberRange());
    }

    private List<String> expand(NumberRangeRequest range) {
        long first = Long.parseLong(range.firstMsisdn());
        int digits = range.firstMsisdn().length();
        List<String> numbers = LongStream.range(first, first + range.count()).mapToObj(Long::toString).toList();
        if (numbers.getLast().length() != digits) {
            throw new BadRequestException("Number range exceeds the allowed number of digits.");
        }
        return numbers;
    }

    public CampaignResponse toResponse(Campaign campaign) {
        int total = campaign.getRecipients().size();
        int sent = campaign.getSent();
        int failed = campaign.getFailed();
        int skipped = campaign.getSkipped();
        double progress = total == 0 ? 100.0 : Math.round((sent + failed + skipped) * 10_000.0 / total) / 100.0;
        Long duration = campaign.getFinishedAt() == null ? null
                : Duration.between(campaign.getCreatedAt(), campaign.getFinishedAt()).toMillis();
        return new CampaignResponse(campaign.getId(), campaign.getName(), campaign.getStatus(), total, sent, failed,
                skipped, progress, campaign.getCreatedAt(), campaign.getFinishedAt(), duration);
    }
}
