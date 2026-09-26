package ma.ewallet.common.error;

import org.springframework.http.HttpStatus;

// All "expected" errors extend this class so a single @ExceptionHandler can turn them
// into proper HTTP responses. Unchecked (RuntimeException) so that throwing one also
// makes Spring roll back the current @Transactional method.
/**
 * Base class for expected, domain-level failures. Each one maps to an
 * HTTP status and a stable machine-readable {@code code}.
 */
public abstract class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
