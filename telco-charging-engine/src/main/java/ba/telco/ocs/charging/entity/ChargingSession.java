package ba.telco.ocs.charging.entity;

import ba.telco.ocs.tariff.entity.ServiceType;
import ba.telco.ocs.tariff.entity.TariffPlan;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

public class ChargingSession {

    @Getter
    private final String id;
    @Getter
    private final String msisdn;
    @Getter
    private final ServiceType serviceType;
    @Getter
    private final TariffPlan tariff;
    @Getter
    private final long requestedUnits;
    @Getter
    private final Instant createdAt;
    private final AtomicReference<State> state;

    public record State(SessionStatus status, long usedUnits, BigDecimal chargedAmount, Instant closedAt,
                        long grantedUnits, BigDecimal reservedAmount, Instant expiresAt) {
    }

    public ChargingSession(String id, String msisdn, ServiceType serviceType, TariffPlan tariff, long requestedUnits,
                           long grantedUnits, BigDecimal reservedAmount, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.msisdn = msisdn;
        this.serviceType = serviceType;
        this.tariff = tariff;
        this.requestedUnits = requestedUnits;
        this.createdAt = createdAt;
        this.state = new AtomicReference<>(new State(SessionStatus.ACTIVE, 0, BigDecimal.ZERO, null,
                grantedUnits, reservedAmount, expiresAt));
    }

    public boolean close(SessionStatus target, long usedUnits, BigDecimal chargedAmount, Instant closedAt) {
        if (target == SessionStatus.ACTIVE) {
            throw new IllegalArgumentException("Target status must be terminal");
        }
        State current = state.get();
        if (current.status() != SessionStatus.ACTIVE) {
            return false;
        }
        return state.compareAndSet(current, new State(target, usedUnits, chargedAmount, closedAt,
                current.grantedUnits(), current.reservedAmount(), current.expiresAt()));
    }

    public boolean extend(long grantedUnits, BigDecimal reservedAmount, Instant expiresAt) {
        State current = state.get();
        if (current.status() != SessionStatus.ACTIVE) {
            return false;
        }
        return state.compareAndSet(current, new State(SessionStatus.ACTIVE, 0, BigDecimal.ZERO, null,
                grantedUnits, reservedAmount, expiresAt));
    }

    public boolean isExpired(Instant now) {
        State current = state.get();
        return current.status() == SessionStatus.ACTIVE && !current.expiresAt().isAfter(now);
    }

    public State getState() {
        return state.get();
    }

    public SessionStatus getStatus() {
        return state.get().status();
    }

    public long getGrantedUnits() {
        return state.get().grantedUnits();
    }

    public BigDecimal getReservedAmount() {
        return state.get().reservedAmount();
    }

    public Instant getExpiresAt() {
        return state.get().expiresAt();
    }

    public long getUsedUnits() {
        return state.get().usedUnits();
    }

    public BigDecimal getChargedAmount() {
        return state.get().chargedAmount();
    }

    public Instant getClosedAt() {
        return state.get().closedAt();
    }
}
