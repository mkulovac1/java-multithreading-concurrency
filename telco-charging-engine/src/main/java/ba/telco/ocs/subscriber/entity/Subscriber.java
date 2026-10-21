package ba.telco.ocs.subscriber.entity;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class Subscriber {

    @Getter
    private final String msisdn;
    @Getter
    private final String name;
    @Getter
    private final Instant createdAt;
    private final ReentrantLock lock = new ReentrantLock();

    @Getter
    @Setter
    private volatile String tariffCode;
    @Getter
    private volatile SubscriberStatus status;

    private BigDecimal balance;
    private BigDecimal reserved = BigDecimal.ZERO;

    public Subscriber(String msisdn, String name, String tariffCode, BigDecimal initialBalance, Instant createdAt) {
        this.msisdn = msisdn;
        this.name = name;
        this.tariffCode = tariffCode;
        this.status = SubscriberStatus.ACTIVE;
        this.balance = initialBalance;
        this.createdAt = createdAt;
    }

    public boolean tryLock(Duration timeout) throws InterruptedException {
        return lock.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    public void unlock() {
        lock.unlock();
    }

    public BalanceSnapshot balanceSnapshot() {
        lock.lock();
        try {
            return new BalanceSnapshot(balance, reserved);
        } finally {
            lock.unlock();
        }
    }

    public BigDecimal available() {
        assertLocked();
        return balance.subtract(reserved);
    }

    public void credit(BigDecimal amount) {
        assertLocked();
        balance = balance.add(amount);
    }

    public void debit(BigDecimal amount) {
        assertLocked();
        balance = balance.subtract(amount);
    }

    public void reserve(BigDecimal amount) {
        assertLocked();
        reserved = reserved.add(amount);
    }

    public void releaseReservation(BigDecimal amount) {
        assertLocked();
        reserved = reserved.subtract(amount);
    }

    private void assertLocked() {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Account " + msisdn + " accessed without holding its lock");
        }
    }

    public void setStatus(SubscriberStatus status) {
        assertLocked();
        this.status = status;
    }
}
