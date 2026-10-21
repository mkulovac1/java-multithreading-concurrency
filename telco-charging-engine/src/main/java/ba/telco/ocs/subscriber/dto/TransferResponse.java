package ba.telco.ocs.subscriber.dto;

import java.math.BigDecimal;

public record TransferResponse(BigDecimal amount, BalanceResponse from, BalanceResponse to) {
}
