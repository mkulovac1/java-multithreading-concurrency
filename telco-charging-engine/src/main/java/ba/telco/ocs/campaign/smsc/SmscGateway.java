package ba.telco.ocs.campaign.smsc;

public interface SmscGateway {

    SubmitResult submit(String msisdn, String text) throws InterruptedException;

    record SubmitResult(boolean accepted, String messageId, String errorCode) {
    }
}
