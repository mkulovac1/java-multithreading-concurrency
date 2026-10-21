package ba.telco.ocs.cdr.service;

import ba.telco.ocs.cdr.entity.Cdr;
import ba.telco.ocs.fraud.service.FraudDetectionService;
import ba.telco.ocs.subscriber.entity.Subscriber;
import ba.telco.ocs.subscriber.service.SubscriberService;
import ba.telco.ocs.tariff.entity.TariffPlan;
import ba.telco.ocs.tariff.service.RatingEngine;
import ba.telco.ocs.tariff.service.TariffService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class CdrRatingService {

    private final SubscriberService subscriberService;
    private final TariffService tariffService;
    private final RatingEngine ratingEngine;
    private final UsageAggregator usageAggregator;
    private final FraudDetectionService fraudDetectionService;

    public BigDecimal rate(Cdr cdr) {
        Subscriber subscriber = subscriberService.getByMsisdn(cdr.msisdn());
        TariffPlan tariff = tariffService.getByCode(subscriber.getTariffCode());
        BigDecimal cost = ratingEngine.price(tariff, cdr.serviceType(), cdr.units());
        usageAggregator.record(cdr.msisdn(), cdr.serviceType(), cdr.units(), cost);
        fraudDetectionService.inspect(cdr);
        return cost;
    }
}
