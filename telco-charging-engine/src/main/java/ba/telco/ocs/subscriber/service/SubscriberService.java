package ba.telco.ocs.subscriber.service;

import ba.telco.ocs.common.exception.BadRequestException;
import ba.telco.ocs.common.exception.InsufficientFundsException;
import ba.telco.ocs.common.exception.InvalidStateException;
import ba.telco.ocs.common.exception.NotFoundException;
import ba.telco.ocs.subscriber.entity.BalanceSnapshot;
import ba.telco.ocs.subscriber.entity.Subscriber;
import ba.telco.ocs.subscriber.entity.SubscriberStatus;
import ba.telco.ocs.subscriber.repository.SubscriberRepository;
import ba.telco.ocs.tariff.service.TariffService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static ba.telco.ocs.tariff.service.RatingEngine.MONEY_SCALE;

@Service
@RequiredArgsConstructor
public class SubscriberService {

    private final SubscriberRepository subscriberRepository;
    private final TariffService tariffService;
    private final AccountLocks accountLocks;
    private final Clock clock;

    public Subscriber create(String msisdn, String name, String tariffCode, BigDecimal initialBalance) {
        requireTariff(tariffCode);
        Subscriber subscriber = new Subscriber(msisdn, name, tariffCode, money(initialBalance), Instant.now(clock));
        if (!subscriberRepository.insert(subscriber)) {
            throw new InvalidStateException("Subscriber with number " + msisdn + " already exists.");
        }
        return subscriber;
    }

    public Subscriber getByMsisdn(String msisdn) {
        return subscriberRepository.findByMsisdn(msisdn)
                .orElseThrow(() -> new NotFoundException("Subscriber with number " + msisdn + " does not exist."));
    }

    public List<Subscriber> getPage(int offset, int limit) {
        return subscriberRepository.findPage(offset, limit);
    }

    public BalanceSnapshot topUp(String msisdn, BigDecimal amount) {
        Subscriber subscriber = getByMsisdn(msisdn);
        return accountLocks.withLock(subscriber, () -> {
            subscriber.credit(money(amount));
            return subscriber.balanceSnapshot();
        });
    }

    public TransferResult transfer(String fromMsisdn, String toMsisdn, BigDecimal amount) {
        if (fromMsisdn.equals(toMsisdn)) {
            throw new BadRequestException("Cannot transfer credit to the same number.");
        }
        Subscriber from = getByMsisdn(fromMsisdn);
        Subscriber to = getByMsisdn(toMsisdn);
        BigDecimal transferAmount = money(amount);

        return accountLocks.withLocks(from, to, () -> {
            requireActive(from);
            requireActive(to);
            if (from.available().compareTo(transferAmount) < 0) {
                throw new InsufficientFundsException("Insufficient available credit for the transfer.");
            }
            from.debit(transferAmount);
            to.credit(transferAmount);
            return new TransferResult(from.balanceSnapshot(), to.balanceSnapshot(), transferAmount);
        });
    }

    public Subscriber changeStatus(String msisdn, SubscriberStatus status) {
        Subscriber subscriber = getByMsisdn(msisdn);
        accountLocks.withLock(subscriber, () -> {
            subscriber.setStatus(status);
            return null;
        });
        return subscriber;
    }

    public Subscriber changeTariff(String msisdn, String tariffCode) {
        requireTariff(tariffCode);
        Subscriber subscriber = getByMsisdn(msisdn);
        subscriber.setTariffCode(tariffCode);
        return subscriber;
    }

    public void requireActive(Subscriber subscriber) {
        if (subscriber.getStatus() != SubscriberStatus.ACTIVE) {
            throw new InvalidStateException("Subscriber " + subscriber.getMsisdn() + " is not active.");
        }
    }

    public long count() {
        return subscriberRepository.count();
    }

    private void requireTariff(String tariffCode) {
        if (!tariffService.exists(tariffCode)) {
            throw new BadRequestException("Tariff with code " + tariffCode + " does not exist.");
        }
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
