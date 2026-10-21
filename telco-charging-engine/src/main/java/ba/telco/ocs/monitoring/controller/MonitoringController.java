package ba.telco.ocs.monitoring.controller;

import ba.telco.ocs.monitoring.dto.SystemMetricsResponse;
import ba.telco.ocs.monitoring.service.MonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/monitoring")
@Tag(name = "Monitoring", description = "Operational metrics (LongAdder, CAS min/max, buffer state, threads)")
@RequiredArgsConstructor
public class MonitoringController {

    private final MonitoringService monitoringService;

    @GetMapping("/metrics")
    @Operation(summary = "Snapshot of all system metrics")
    public SystemMetricsResponse metrics() {
        return monitoringService.collect();
    }
}
