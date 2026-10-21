package ba.telco.ocs.charging.mapper;

import ba.telco.ocs.charging.dto.ChargingSessionResponse;
import ba.telco.ocs.charging.entity.ChargingSession;
import org.springframework.stereotype.Component;

@Component
public class ChargingSessionMapper {

    public ChargingSessionResponse toResponse(ChargingSession session) {
        ChargingSession.State state = session.getState();
        return new ChargingSessionResponse(session.getId(), session.getMsisdn(), session.getServiceType(),
                session.getTariff().code(), session.getRequestedUnits(), state.grantedUnits(),
                state.reservedAmount(), state.status(), state.usedUnits(), state.chargedAmount(),
                session.getCreatedAt(), state.expiresAt(), state.closedAt());
    }
}
