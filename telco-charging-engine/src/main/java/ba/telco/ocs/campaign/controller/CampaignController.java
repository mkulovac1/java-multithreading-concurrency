package ba.telco.ocs.campaign.controller;

import ba.telco.ocs.campaign.dto.CampaignResponse;
import ba.telco.ocs.campaign.dto.CreateCampaignRequest;
import ba.telco.ocs.campaign.mapper.CampaignMapper;
import ba.telco.ocs.campaign.service.CampaignService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/campaigns")
@Tag(name = "Bulk SMS Campaigns", description = "Virtual threads (thread-per-message) + Semaphore as the SMPP window towards the SMSC")
@RequiredArgsConstructor
public class CampaignController {

    private final CampaignService campaignService;
    private final CampaignMapper campaignMapper;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Start a campaign (asynchronous)")
    public CampaignResponse start(@Valid @RequestBody CreateCampaignRequest request) {
        return campaignMapper.toResponse(campaignService.start(request.name(), request.message(),
                campaignMapper.toRecipients(request)));
    }

    @GetMapping
    @Operation(summary = "List campaigns (newest first)")
    public List<CampaignResponse> getAll() {
        return campaignService.getAll().stream().map(campaignMapper::toResponse).toList();
    }

    @GetMapping("/{campaignId}")
    @Operation(summary = "Campaign progress")
    public CampaignResponse get(@PathVariable String campaignId) {
        return campaignMapper.toResponse(campaignService.get(campaignId));
    }

    @DeleteMapping("/{campaignId}")
    @Operation(summary = "Cancel a campaign (interrupts in-flight threads)")
    public CampaignResponse cancel(@PathVariable String campaignId) {
        return campaignMapper.toResponse(campaignService.cancel(campaignId));
    }
}
