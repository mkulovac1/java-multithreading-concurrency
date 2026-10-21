package ba.telco.ocs.charging.dto;

import ba.telco.ocs.charging.entity.SessionStatus;
import ba.telco.ocs.tariff.entity.ServiceType;

import java.math.BigDecimal;
import java.time.Instant;

public record ChargingSessionResponse(
        String sessionId,
        String msisdn,
        ServiceType serviceType,
        String tariffCode,
        long requestedUnits,
        long grantedUnits,
        BigDecimal reservedAmount,
        SessionStatus status,
        long usedUnits,
        BigDecimal chargedAmount,
        Instant createdAt,
        Instant expiresAt,
        Instant closedAt) {
}
