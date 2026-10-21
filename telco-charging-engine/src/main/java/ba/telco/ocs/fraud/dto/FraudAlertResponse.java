package ba.telco.ocs.fraud.dto;

import java.time.Instant;

public record FraudAlertResponse(String alertId, String msisdn, int eventsInWindow, Instant windowStart,
                                 Instant detectedAt, String description) {
}
