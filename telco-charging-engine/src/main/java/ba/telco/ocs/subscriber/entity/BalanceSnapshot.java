package ba.telco.ocs.subscriber.entity;

import java.math.BigDecimal;

public record BalanceSnapshot(BigDecimal balance, BigDecimal reserved) {

    public BigDecimal available() {
        return balance.subtract(reserved);
    }
}
