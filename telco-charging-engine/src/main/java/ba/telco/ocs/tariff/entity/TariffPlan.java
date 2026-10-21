package ba.telco.ocs.tariff.entity;

import java.math.BigDecimal;

public record TariffPlan(
        String code,
        String name,
        BigDecimal voicePricePerMinute,
        BigDecimal smsPrice,
        BigDecimal dataPricePerMb,
        int voiceBillingIncrementSeconds) {
}
