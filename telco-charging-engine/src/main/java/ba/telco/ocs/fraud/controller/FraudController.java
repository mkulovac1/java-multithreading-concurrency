package ba.telco.ocs.fraud.controller;

import ba.telco.ocs.common.exception.NotFoundException;
import ba.telco.ocs.fraud.dto.FraudAlertResponse;
import ba.telco.ocs.fraud.entity.FraudAlert;
import ba.telco.ocs.fraud.mapper.FraudAlertMapper;
import ba.telco.ocs.fraud.service.FraudDetectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/fraud/alerts")
@Tag(name = "Fraud Detection", description = "Lock-free per-subscriber counters (CAS) and a lock-free alert stack")
@RequiredArgsConstructor
public class FraudController {

    private final FraudDetectionService fraudDetectionService;
    private final FraudAlertMapper alertMapper;

    @GetMapping
    @Operation(summary = "Open alerts, newest first")
    public List<FraudAlertResponse> getAlerts(@RequestParam(defaultValue = "50") @Min(1) @Max(1000) int limit) {
        return fraudDetectionService.getAlerts(limit).stream().map(alertMapper::toResponse).toList();
    }

    @PostMapping("/acknowledge-latest")
    @Operation(summary = "Acknowledge (remove) the newest alert")
    public FraudAlertResponse acknowledgeLatest() {
        FraudAlert alert = fraudDetectionService.acknowledgeLatest();
        if (alert == null) {
            throw new NotFoundException("There are no open alerts.");
        }
        return alertMapper.toResponse(alert);
    }

    @PostMapping("/acknowledge-all")
    @Operation(summary = "Atomically acknowledge all open alerts")
    public List<FraudAlertResponse> acknowledgeAll() {
        return fraudDetectionService.acknowledgeAll().stream().map(alertMapper::toResponse).toList();
    }
}
