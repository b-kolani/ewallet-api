package ma.ewallet.common.error;

import org.springframework.http.HttpStatus;

/**
 * 409 Conflict: the operation kept colliding with concurrent updates and we
 * stopped retrying. Nothing was debited. Retrying with the same
 * Idempotency-Key is safe.
 */
public class ConcurrentUpdateException extends BusinessException {

    public ConcurrentUpdateException(Throwable cause) {
        super(HttpStatus.CONFLICT, "CONCURRENT_UPDATE",
                "The account was modified concurrently, please retry with the same Idempotency-Key");
        initCause(cause);
    }
}
