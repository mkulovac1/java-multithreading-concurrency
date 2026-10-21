package ba.telco.ocs.tariff.service;

import ba.telco.ocs.tariff.entity.ServiceType;
import ba.telco.ocs.tariff.entity.TariffPlan;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class RatingEngine {

    public static final int MONEY_SCALE = 4;
    private static final BigDecimal SECONDS_PER_MINUTE = BigDecimal.valueOf(60);
    private static final BigDecimal KB_PER_MB = BigDecimal.valueOf(1024);

    public BigDecimal price(TariffPlan tariff, ServiceType serviceType, long units) {
        if (units < 0) {
            throw new IllegalArgumentException("units cannot be negative");
        }
        BigDecimal amount = switch (serviceType) {
            case VOICE -> tariff.voicePricePerMinute()
                    .multiply(BigDecimal.valueOf(billableUnits(tariff, serviceType, units)))
                    .divide(SECONDS_PER_MINUTE, MONEY_SCALE, RoundingMode.HALF_UP);
            case SMS -> tariff.smsPrice().multiply(BigDecimal.valueOf(units));
            case DATA -> tariff.dataPricePerMb()
                    .multiply(BigDecimal.valueOf(units))
                    .divide(KB_PER_MB, MONEY_SCALE, RoundingMode.HALF_UP);
        };
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public long billableUnits(TariffPlan tariff, ServiceType serviceType, long units) {
        if (serviceType != ServiceType.VOICE || units == 0) {
            return units;
        }
        long increment = tariff.voiceBillingIncrementSeconds();
        return Math.multiplyExact(Math.ceilDiv(units, increment), increment);
    }

    public long affordableUnits(TariffPlan tariff, ServiceType serviceType, long requested, BigDecimal available) {
        if (available.signum() <= 0) {
            return price(tariff, serviceType, requested).signum() == 0 ? requested : 0;
        }
        if (price(tariff, serviceType, requested).compareTo(available) <= 0) {
            return requested;
        }
        long low = 0;
        long high = requested;
        while (low < high) {
            long mid = low + (high - low + 1) / 2;
            if (price(tariff, serviceType, mid).compareTo(available) <= 0) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }
}
