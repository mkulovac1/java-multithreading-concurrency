package ba.telco.ocs.subscriber.repository;

import ba.telco.ocs.subscriber.entity.Subscriber;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class SubscriberRepository {

    private final ConcurrentMap<String, Subscriber> subscribers = new ConcurrentHashMap<>();

    public boolean insert(Subscriber subscriber) {
        return subscribers.putIfAbsent(subscriber.getMsisdn(), subscriber) == null;
    }

    public Optional<Subscriber> findByMsisdn(String msisdn) {
        return Optional.ofNullable(subscribers.get(msisdn));
    }

    public List<Subscriber> findPage(int offset, int limit) {
        return subscribers.values().stream()
                .sorted(Comparator.comparing(Subscriber::getMsisdn))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    public long count() {
        return subscribers.size();
    }
}
