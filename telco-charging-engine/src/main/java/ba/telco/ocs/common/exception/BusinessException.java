package ba.telco.ocs.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

public abstract class BusinessException extends RuntimeException {

    @Getter
    private final HttpStatus status;

    protected BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
