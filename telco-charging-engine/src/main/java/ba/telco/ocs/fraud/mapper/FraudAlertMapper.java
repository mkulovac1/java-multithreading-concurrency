package ba.telco.ocs.fraud.mapper;

import ba.telco.ocs.fraud.dto.FraudAlertResponse;
import ba.telco.ocs.fraud.entity.FraudAlert;
import org.springframework.stereotype.Component;

@Component
public class FraudAlertMapper {

    public FraudAlertResponse toResponse(FraudAlert alert) {
        return new FraudAlertResponse(alert.id(), alert.msisdn(), alert.eventsInWindow(), alert.windowStart(),
                alert.detectedAt(), "Suspicious activity: " + alert.eventsInWindow()
                + " events within one minute (possible SIM-box or Wangiri).");
    }
}
