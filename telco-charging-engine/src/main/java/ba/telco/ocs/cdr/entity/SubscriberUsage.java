package ba.telco.ocs.cdr.entity;

import ba.telco.ocs.tariff.entity.ServiceType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.concurrent.atomic.LongAdder;

import static ba.telco.ocs.tariff.service.RatingEngine.MONEY_SCALE;

@RequiredArgsConstructor
public class SubscriberUsage {

    @Getter
    private final String msisdn;
    private final LongAdder cdrCount = new LongAdder();
    private final LongAdder voiceSeconds = new LongAdder();
    private final LongAdder smsCount = new LongAdder();
    private final LongAdder dataKb = new LongAdder();
    private final LongAdder revenueMinorUnits = new LongAdder();

    public void record(ServiceType serviceType, long units, BigDecimal cost) {
        cdrCount.increment();
        LongAdder counter = switch (serviceType) {
            case VOICE -> voiceSeconds;
            case SMS -> smsCount;
            case DATA -> dataKb;
        };
        counter.add(units);
        revenueMinorUnits.add(cost.setScale(MONEY_SCALE).unscaledValue().longValueExact());
    }

    public long getCdrCount() {
        return cdrCount.sum();
    }

    public long getVoiceSeconds() {
        return voiceSeconds.sum();
    }

    public long getSmsCount() {
        return smsCount.sum();
    }

    public long getDataKb() {
        return dataKb.sum();
    }

    public BigDecimal getRevenue() {
        return BigDecimal.valueOf(revenueMinorUnits.sum(), MONEY_SCALE);
    }
}
