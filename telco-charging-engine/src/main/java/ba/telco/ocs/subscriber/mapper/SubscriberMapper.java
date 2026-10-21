package ba.telco.ocs.subscriber.mapper;

import ba.telco.ocs.subscriber.dto.BalanceResponse;
import ba.telco.ocs.subscriber.dto.SubscriberResponse;
import ba.telco.ocs.subscriber.dto.TransferResponse;
import ba.telco.ocs.subscriber.entity.BalanceSnapshot;
import ba.telco.ocs.subscriber.entity.Subscriber;
import ba.telco.ocs.subscriber.service.TransferResult;
import org.springframework.stereotype.Component;

@Component
public class SubscriberMapper {

    public SubscriberResponse toResponse(Subscriber subscriber) {
        BalanceSnapshot snapshot = subscriber.balanceSnapshot();
        return new SubscriberResponse(subscriber.getMsisdn(), subscriber.getName(), subscriber.getTariffCode(),
                subscriber.getStatus(), snapshot.balance(), snapshot.reserved(), snapshot.available(),
                subscriber.getCreatedAt());
    }

    public BalanceResponse toBalanceResponse(String msisdn, BalanceSnapshot snapshot) {
        return new BalanceResponse(msisdn, snapshot.balance(), snapshot.reserved(), snapshot.available());
    }

    public TransferResponse toTransferResponse(String fromMsisdn, String toMsisdn, TransferResult result) {
        return new TransferResponse(result.amount(),
                toBalanceResponse(fromMsisdn, result.from()),
                toBalanceResponse(toMsisdn, result.to()));
    }
}
