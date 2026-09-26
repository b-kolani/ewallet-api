package ma.ewallet.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception into a clean JSON error, in the standard
 * RFC 7807 "Problem Details" format ({@code application/problem+json}):
 * <pre>
 * {
 *   "type": "about:blank", "title": "Unprocessable Entity", "status": 422,
 *   "detail": "Insufficient funds on account 3f2c…",
 *   "code": "INSUFFICIENT_FUNDS"            ← our addition
 * }
 * </pre>
 * Clients should switch on {@code code} (stable), never on {@code detail}
 * (a human message that may change).
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} gives us Spring's own
 * handling of framework errors (malformed JSON, missing header, wrong
 * parameter type...) in the same format, for free.</p>
 *
 * <p>Spring picks the <b>most specific</b> handler for an exception, so the
 * {@code Exception.class} catch-all only receives truly unexpected errors.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusiness(BusinessException ex) {
        return problem(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    /**
     * A database constraint was violated (e.g. two users registering the same
     * name at the same instant). The service-level check missed it, the database
     * caught it: we answer 409 instead of a scary 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "CONFLICT", "The request conflicts with the current state of the resource");
    }

    /**
     * Last resort. The real cause goes to the server logs, while the client gets
     * a generic message: stack traces and SQL errors can leak internal details.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    /**
     * {@code @Valid} failed on a request body. We add an {@code errors} map
     * (field → message) so a front-end can show each error next to its field:
     * {@code "errors": {"amount": "must be greater than or equal to 0.01"}}.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ProblemDetail body = ex.getBody();
        body.setDetail("Request validation failed");
        body.setProperty("code", "VALIDATION_FAILED");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(status.getReasonPhrase());
        body.setProperty("code", code);
        return ResponseEntity.status(status).body(body);
    }
}
