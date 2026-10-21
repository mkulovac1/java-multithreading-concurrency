package ba.telco.ocs.tariff.dto;

import java.math.BigDecimal;

public record TariffResponse(
        String code,
        String name,
        BigDecimal voicePricePerMinute,
        BigDecimal smsPrice,
        BigDecimal dataPricePerMb,
        int voiceBillingIncrementSeconds) {
}
