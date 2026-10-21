package ba.telco.ocs.tariff.repository;

import ba.telco.ocs.tariff.entity.TariffPlan;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Repository
public class TariffRepository {

    private final Map<String, TariffPlan> tariffs = new HashMap<>();
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final Lock readLock = readWriteLock.readLock();
    private final Lock writeLock = readWriteLock.writeLock();

    public Optional<TariffPlan> findByCode(String code) {
        readLock.lock();
        try {
            return Optional.ofNullable(tariffs.get(code));
        } finally {
            readLock.unlock();
        }
    }

    public List<TariffPlan> findAll() {
        readLock.lock();
        try {
            List<TariffPlan> result = new ArrayList<>(tariffs.values());
            result.sort(Comparator.comparing(TariffPlan::code));
            return result;
        } finally {
            readLock.unlock();
        }
    }

    public boolean insert(TariffPlan tariff) {
        writeLock.lock();
        try {
            return tariffs.putIfAbsent(tariff.code(), tariff) == null;
        } finally {
            writeLock.unlock();
        }
    }

    public boolean replace(TariffPlan tariff) {
        writeLock.lock();
        try {
            return tariffs.replace(tariff.code(), tariff) != null;
        } finally {
            writeLock.unlock();
        }
    }
}
