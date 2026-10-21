package ba.telco.ocs.charging.controller;

import ba.telco.ocs.charging.dto.ChargingSessionResponse;
import ba.telco.ocs.charging.dto.CommitRequest;
import ba.telco.ocs.charging.dto.ExtendRequest;
import ba.telco.ocs.charging.dto.ReserveRequest;
import ba.telco.ocs.charging.mapper.ChargingSessionMapper;
import ba.telco.ocs.charging.service.ChargingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/charging/sessions")
@Tag(name = "Online Charging", description = "Credit reservation / commit / release (Diameter Gy analogue, CAS on session status)")
@RequiredArgsConstructor
public class ChargingController {

    private final ChargingService chargingService;
    private final ChargingSessionMapper sessionMapper;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Reserve units (CCR-Initial) - partial grant when credit is insufficient")
    public ChargingSessionResponse reserve(@Valid @RequestBody ReserveRequest request) {
        return sessionMapper.toResponse(
                chargingService.reserve(request.msisdn(), request.serviceType(), request.requestedUnits()));
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "Session status")
    public ChargingSessionResponse get(@PathVariable String sessionId) {
        return sessionMapper.toResponse(chargingService.getById(sessionId));
    }

    @PostMapping("/{sessionId}/extend")
    @Operation(summary = "Reserve more units and renew the validity of an active session (CCR-Update)")
    public ChargingSessionResponse extend(@PathVariable String sessionId, @Valid @RequestBody ExtendRequest request) {
        return sessionMapper.toResponse(chargingService.extend(sessionId, request.additionalUnits()));
    }

    @PostMapping("/{sessionId}/commit")
    @Operation(summary = "Charge actual usage (CCR-Terminate)")
    public ChargingSessionResponse commit(@PathVariable String sessionId, @Valid @RequestBody CommitRequest request) {
        return sessionMapper.toResponse(chargingService.commit(sessionId, request.usedUnits()));
    }

    @PostMapping("/{sessionId}/release")
    @Operation(summary = "Release the reservation without charging (call not established)")
    public ChargingSessionResponse release(@PathVariable String sessionId) {
        return sessionMapper.toResponse(chargingService.release(sessionId));
    }
}
