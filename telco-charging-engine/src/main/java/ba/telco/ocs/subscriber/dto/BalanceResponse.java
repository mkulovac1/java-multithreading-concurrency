package ba.telco.ocs.subscriber.dto;

import java.math.BigDecimal;

public record BalanceResponse(String msisdn, BigDecimal balance, BigDecimal reservedAmount, BigDecimal availableBalance) {
}
