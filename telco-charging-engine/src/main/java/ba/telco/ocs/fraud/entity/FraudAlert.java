package ba.telco.ocs.fraud.entity;

import java.time.Instant;

public record FraudAlert(String id, String msisdn, int eventsInWindow, Instant windowStart, Instant detectedAt) {
}
