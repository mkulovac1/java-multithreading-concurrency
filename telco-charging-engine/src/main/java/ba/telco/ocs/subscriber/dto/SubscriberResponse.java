package ba.telco.ocs.subscriber.dto;

import ba.telco.ocs.subscriber.entity.SubscriberStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record SubscriberResponse(
        String msisdn,
        String name,
        String tariffCode,
        SubscriberStatus status,
        BigDecimal balance,
        BigDecimal reservedAmount,
        BigDecimal availableBalance,
        Instant createdAt) {
}
