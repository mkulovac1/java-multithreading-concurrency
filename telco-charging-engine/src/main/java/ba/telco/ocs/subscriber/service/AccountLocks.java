package ba.telco.ocs.subscriber.service;

import ba.telco.ocs.common.config.TelcoProperties;
import ba.telco.ocs.common.exception.ResourceBusyException;
import ba.telco.ocs.subscriber.entity.Subscriber;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

@Component
public class AccountLocks {

    private final Duration lockTimeout;
    private final LongAdder lockTimeouts = new LongAdder();

    public AccountLocks(TelcoProperties properties) {
        this.lockTimeout = properties.charging().lockTimeout();
    }

    public <T> T withLock(Subscriber subscriber, Supplier<T> action) {
        acquire(subscriber);
        try {
            return action.get();
        } finally {
            subscriber.unlock();
        }
    }

    public <T> T withLocks(Subscriber first, Subscriber second, Supplier<T> action) {
        if (first == second) {
            throw new IllegalArgumentException("Cannot lock the same account twice");
        }
        boolean ordered = first.getMsisdn().compareTo(second.getMsisdn()) < 0;
        Subscriber lower = ordered ? first : second;
        Subscriber higher = ordered ? second : first;

        acquire(lower);
        try {
            acquire(higher);
            try {
                return action.get();
            } finally {
                higher.unlock();
            }
        } finally {
            lower.unlock();
        }
    }

    public long getLockTimeouts() {
        return lockTimeouts.sum();
    }

    private void acquire(Subscriber subscriber) {
        try {
            if (!subscriber.tryLock(lockTimeout)) {
                lockTimeouts.increment();
                throw new ResourceBusyException(
                        "Account " + subscriber.getMsisdn() + " is currently busy. Please try again.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResourceBusyException("Request processing was interrupted.");
        }
    }
}
