package ba.telco.ocs.monitoring.dto;

import ba.telco.ocs.campaign.service.CampaignService.CampaignStats;
import ba.telco.ocs.cdr.pipeline.CdrPipeline.PipelineStats;
import ba.telco.ocs.charging.service.ChargingService.ChargingStats;
import ba.telco.ocs.fraud.service.FraudDetectionService.FraudStats;

public record SystemMetricsResponse(
        ChargingStats charging,
        PipelineStats cdrPipeline,
        CampaignStats campaigns,
        SmscStats smsc,
        FraudStats fraud,
        JvmThreadStats jvm) {

    public record SmscStats(int inFlight, int maxInFlightObserved) {
    }

    public record JvmThreadStats(int availableProcessors, int liveThreads, int daemonThreads, int peakThreads,
                                 long totalStartedThreads) {
    }
}
