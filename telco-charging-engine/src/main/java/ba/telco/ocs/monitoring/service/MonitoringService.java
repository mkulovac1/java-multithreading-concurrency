package ba.telco.ocs.monitoring.service;

import ba.telco.ocs.campaign.service.CampaignService;
import ba.telco.ocs.campaign.smsc.SimulatedSmscGateway;
import ba.telco.ocs.cdr.pipeline.CdrPipeline;
import ba.telco.ocs.charging.service.ChargingService;
import ba.telco.ocs.fraud.service.FraudDetectionService;
import ba.telco.ocs.monitoring.dto.SystemMetricsResponse;
import ba.telco.ocs.monitoring.dto.SystemMetricsResponse.JvmThreadStats;
import ba.telco.ocs.monitoring.dto.SystemMetricsResponse.SmscStats;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

@Service
@RequiredArgsConstructor
public class MonitoringService {

    private final ChargingService chargingService;
    private final CdrPipeline cdrPipeline;
    private final CampaignService campaignService;
    private final SimulatedSmscGateway smscGateway;
    private final FraudDetectionService fraudDetectionService;
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    public SystemMetricsResponse collect() {
        return new SystemMetricsResponse(
                chargingService.stats(),
                cdrPipeline.stats(),
                campaignService.stats(),
                new SmscStats(smscGateway.getInFlight(), smscGateway.getMaxInFlight()),
                fraudDetectionService.stats(),
                new JvmThreadStats(Runtime.getRuntime().availableProcessors(), threadMXBean.getThreadCount(),
                        threadMXBean.getDaemonThreadCount(), threadMXBean.getPeakThreadCount(),
                        threadMXBean.getTotalStartedThreadCount()));
    }
}
