package ba.telco.ocs.common.exception;

import org.springframework.http.HttpStatus;

public class ResourceBusyException extends BusinessException {

    public ResourceBusyException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
