package ba.telco.ocs.charging.service;

import ba.telco.ocs.charging.entity.ChargingSession;
import ba.telco.ocs.charging.entity.SessionStatus;
import ba.telco.ocs.charging.repository.ChargingSessionRepository;
import ba.telco.ocs.common.config.TelcoProperties;
import ba.telco.ocs.common.exception.BadRequestException;
import ba.telco.ocs.common.exception.InsufficientFundsException;
import ba.telco.ocs.common.exception.InvalidStateException;
import ba.telco.ocs.common.exception.NotFoundException;
import ba.telco.ocs.common.metrics.LatencyStats;
import ba.telco.ocs.subscriber.entity.Subscriber;
import ba.telco.ocs.subscriber.service.AccountLocks;
import ba.telco.ocs.subscriber.service.SubscriberService;
import ba.telco.ocs.tariff.entity.ServiceType;
import ba.telco.ocs.tariff.entity.TariffPlan;
import ba.telco.ocs.tariff.service.RatingEngine;
import ba.telco.ocs.tariff.service.TariffService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

@Service
@Slf4j
public class ChargingService {

    static final long MAX_SESSION_UNITS = 1_000_000_000;

    private final ChargingSessionRepository sessionRepository;
    private final SubscriberService subscriberService;
    private final TariffService tariffService;
    private final RatingEngine ratingEngine;
    private final AccountLocks accountLocks;
    private final Clock clock;
    private final Duration sessionTtl;

    private final LongAdder reservations = new LongAdder();
    private final LongAdder commits = new LongAdder();
    private final LongAdder releases = new LongAdder();
    private final LongAdder extensions = new LongAdder();
    private final LongAdder expirations = new LongAdder();
    private final LongAdder rejectedInsufficientFunds = new LongAdder();
    private final LatencyStats reserveLatency = new LatencyStats();

    public ChargingService(ChargingSessionRepository sessionRepository, SubscriberService subscriberService,
                           TariffService tariffService, RatingEngine ratingEngine, AccountLocks accountLocks,
                           Clock clock, TelcoProperties properties) {
        this.sessionRepository = sessionRepository;
        this.subscriberService = subscriberService;
        this.tariffService = tariffService;
        this.ratingEngine = ratingEngine;
        this.accountLocks = accountLocks;
        this.clock = clock;
        this.sessionTtl = properties.charging().sessionTtl();
    }

    public ChargingSession reserve(String msisdn, ServiceType serviceType, long requestedUnits) {
        long start = System.nanoTime();
        Subscriber subscriber = subscriberService.getByMsisdn(msisdn);
        TariffPlan tariff = tariffService.getByCode(subscriber.getTariffCode());

        ChargingSession session = accountLocks.withLock(subscriber, () -> {
            subscriberService.requireActive(subscriber);
            BigDecimal available = subscriber.available();
            long granted = ratingEngine.affordableUnits(tariff, serviceType, requestedUnits, available);
            if (granted == 0) {
                rejectedInsufficientFunds.increment();
                throw new InsufficientFundsException("Insufficient credit for service " + serviceType + ".");
            }
            BigDecimal amount = ratingEngine.price(tariff, serviceType, granted);
            Instant now = Instant.now(clock);
            ChargingSession created = new ChargingSession(UUID.randomUUID().toString(), msisdn, serviceType, tariff,
                    requestedUnits, granted, amount, now, now.plus(sessionTtl));
            subscriber.reserve(amount);
            sessionRepository.save(created);
            return created;
        });

        reservations.increment();
        reserveLatency.record(System.nanoTime() - start);
        return session;
    }

    public ChargingSession extend(String sessionId, long additionalUnits) {
        ChargingSession session = getById(sessionId);
        Subscriber subscriber = subscriberService.getByMsisdn(session.getMsisdn());
        TariffPlan tariff = session.getTariff();
        ChargingSession extended = accountLocks.withLock(subscriber, () -> {
            subscriberService.requireActive(subscriber);
            Instant now = Instant.now(clock);
            ChargingSession.State state = session.getState();
            if (state.status() != SessionStatus.ACTIVE) {
                throw new InvalidStateException("Session " + sessionId + " is not active (status: "
                        + state.status() + ").");
            }
            if (session.isExpired(now)) {
                throw new InvalidStateException("Session " + sessionId + " has expired.");
            }
            long requestedTotal = state.grantedUnits() + additionalUnits;
            if (requestedTotal > MAX_SESSION_UNITS) {
                throw new BadRequestException("A session can be granted at most " + MAX_SESSION_UNITS + " units.");
            }
            BigDecimal budget = state.reservedAmount().add(subscriber.available());
            long grantedTotal = ratingEngine.affordableUnits(tariff, session.getServiceType(), requestedTotal, budget);
            if (grantedTotal == state.grantedUnits()) {
                rejectedInsufficientFunds.increment();
                throw new InsufficientFundsException("Insufficient credit to extend the session.");
            }
            BigDecimal reservedTotal = ratingEngine.price(tariff, session.getServiceType(), grantedTotal);
            if (!session.extend(grantedTotal, reservedTotal, now.plus(sessionTtl))) {
                throw new InvalidStateException("Session " + sessionId + " is no longer active.");
            }
            subscriber.reserve(reservedTotal.subtract(state.reservedAmount()));
            return session;
        });
        extensions.increment();
        return extended;
    }

    public ChargingSession commit(String sessionId, long usedUnits) {
        ChargingSession session = getById(sessionId);
        if (session.isExpired(Instant.now(clock))) {
            throw new InvalidStateException("Session " + sessionId + " has expired.");
        }
        if (usedUnits > session.getGrantedUnits()) {
            throw new BadRequestException("Used units (" + usedUnits
                    + ") exceed granted units (" + session.getGrantedUnits() + ").");
        }
        BigDecimal cost = ratingEngine.price(session.getTariff(), session.getServiceType(), usedUnits);
        settle(session, SessionStatus.COMMITTED, usedUnits, cost, false);
        commits.increment();
        return session;
    }

    public ChargingSession release(String sessionId) {
        ChargingSession session = getById(sessionId);
        settle(session, SessionStatus.RELEASED, 0, BigDecimal.ZERO.setScale(RatingEngine.MONEY_SCALE), false);
        releases.increment();
        return session;
    }

    public ChargingSession getById(String sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("Session " + sessionId + " does not exist."));
    }

    public int expireSessions() {
        int expired = 0;
        for (ChargingSession session : sessionRepository.findExpired(Instant.now(clock))) {
            try {
                settle(session, SessionStatus.EXPIRED, 0, BigDecimal.ZERO.setScale(RatingEngine.MONEY_SCALE), true);
                expirations.increment();
                expired++;
            } catch (InvalidStateException alreadySettled) {
            } catch (RuntimeException e) {
                log.warn("Could not expire session {} yet: {}", session.getId(), e.getMessage());
            }
        }
        return expired;
    }

    public int purgeClosedSessions(Duration retention) {
        return sessionRepository.removeClosedBefore(Instant.now(clock).minus(retention));
    }

    private void settle(ChargingSession session, SessionStatus target, long usedUnits, BigDecimal cost,
                        boolean onlyIfExpired) {
        Subscriber subscriber = subscriberService.getByMsisdn(session.getMsisdn());
        accountLocks.withLock(subscriber, () -> {
            if (onlyIfExpired && !session.isExpired(Instant.now(clock))) {
                throw new InvalidStateException("Session " + session.getId() + " is not expired.");
            }
            if (!session.close(target, usedUnits, cost, Instant.now(clock))) {
                throw new InvalidStateException("Session " + session.getId() + " is not active (status: "
                        + session.getStatus() + ").");
            }
            subscriber.releaseReservation(session.getReservedAmount());
            subscriber.debit(cost);
            return null;
        });
    }

    public ChargingStats stats() {
        return new ChargingStats(sessionRepository.countActive(), reservations.sum(), extensions.sum(), commits.sum(),
                releases.sum(), expirations.sum(), rejectedInsufficientFunds.sum(), accountLocks.getLockTimeouts(),
                reserveLatency.snapshot());
    }

    public record ChargingStats(long activeSessions, long reservations, long extensions, long commits, long releases, long expirations,
                                long rejectedInsufficientFunds, long lockTimeouts,
                                LatencyStats.LatencySnapshot reserveLatency) {
    }
}
