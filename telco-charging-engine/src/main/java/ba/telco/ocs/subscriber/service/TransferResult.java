package ba.telco.ocs.subscriber.service;

import ba.telco.ocs.subscriber.entity.BalanceSnapshot;

import java.math.BigDecimal;

public record TransferResult(BalanceSnapshot from, BalanceSnapshot to, BigDecimal amount) {
}
