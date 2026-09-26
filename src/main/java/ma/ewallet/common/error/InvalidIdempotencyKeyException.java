package ma.ewallet.common.error;

import org.springframework.http.HttpStatus;

/** 400: missing (on transfers), blank or longer than 128 characters. */
public class InvalidIdempotencyKeyException extends BusinessException {

    public InvalidIdempotencyKeyException() {
        super(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be between 1 and 128 characters");
    }
}
