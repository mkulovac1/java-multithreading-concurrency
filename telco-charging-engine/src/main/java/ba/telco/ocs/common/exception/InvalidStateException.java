package ba.telco.ocs.common.exception;

import org.springframework.http.HttpStatus;

public class InvalidStateException extends BusinessException {

    public InvalidStateException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
