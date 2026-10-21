package ba.telco.ocs.cdr.service;

import ba.telco.ocs.cdr.entity.SubscriberUsage;
import ba.telco.ocs.tariff.entity.ServiceType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class UsageAggregator {

    private final ConcurrentMap<String, SubscriberUsage> usages = new ConcurrentHashMap<>();

    public void record(String msisdn, ServiceType serviceType, long units, BigDecimal cost) {
        usages.computeIfAbsent(msisdn, SubscriberUsage::new).record(serviceType, units, cost);
    }

    public Optional<SubscriberUsage> find(String msisdn) {
        return Optional.ofNullable(usages.get(msisdn));
    }

    public List<SubscriberUsage> topByRevenue(int limit) {
        return usages.values().stream()
                .sorted(Comparator.comparing(SubscriberUsage::getRevenue).reversed())
                .limit(limit)
                .toList();
    }
}
