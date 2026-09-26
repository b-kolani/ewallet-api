package ma.ewallet.common.error;

import org.springframework.http.HttpStatus;

/** 422: the key was already used for a <i>different</i> request payload. */
public class IdempotencyKeyReusedException extends BusinessException {

    public IdempotencyKeyReusedException(String key) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key " + key + " was already used with a different request payload");
    }
}
