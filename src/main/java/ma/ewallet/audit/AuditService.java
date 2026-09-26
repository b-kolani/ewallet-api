package ma.ewallet.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ma.ewallet.common.PageResponse;

/**
 * Audit trail: who did what, when, and whether it worked.
 *
 * <h2>Why REQUIRES_NEW?</h2>
 * A failed transfer rolls back its transaction. If the audit line were
 * written in that same transaction, it would be rolled back too — and failed
 * attempts (often the most interesting ones for security) would leave no
 * trace. {@code REQUIRES_NEW} suspends the caller's transaction, if any, and
 * commits the audit line in a separate one.
 *
 * <h2>Why swallow exceptions?</h2>
 * The ledger is the legal record of money movements; the audit log is an
 * operational trail. If the audit write fails after money has moved, answering
 * "500 error" would make the client believe the transfer failed. So we log the
 * problem loudly and carry on.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String username, AuditAction action, AuditOutcome outcome, String resourceId, String details) {
        try {
            // Truncate to the column sizes: a failed login may carry an arbitrarily long username.
            repository.save(new AuditLog(truncate(username, 50), action, outcome,
                    truncate(resourceId, 64), truncate(details, 500)));
        } catch (RuntimeException ex) {
            log.error("Failed to write audit log {} {} for {}", action, outcome, username, ex);
        }
    }

    /** readOnly = true: a hint to Hibernate/PostgreSQL that nothing will be written (skips dirty checking). */
    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> forUser(String username, int page, int size) {
        return PageResponse.from(repository
                .findByUsernameOrderByCreatedAtDesc(username, PageRequest.of(page, size))
                .map(AuditLogResponse::from));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
