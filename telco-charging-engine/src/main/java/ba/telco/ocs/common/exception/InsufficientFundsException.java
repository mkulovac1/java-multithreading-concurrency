package ba.telco.ocs.common.exception;

import org.springframework.http.HttpStatus;

public class InsufficientFundsException extends BusinessException {

    public InsufficientFundsException(String message) {
        super(HttpStatus.PAYMENT_REQUIRED, message);
    }
}
