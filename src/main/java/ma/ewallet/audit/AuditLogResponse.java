package ma.ewallet.audit;

import java.time.Instant;

/** JSON view of an audit line (the internal id and username are not needed by the client). */
public record AuditLogResponse(AuditAction action, AuditOutcome outcome, String resourceId,
                               String details, Instant createdAt) {

    static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(log.getAction(), log.getOutcome(), log.getResourceId(),
                log.getDetails(), log.getCreatedAt());
    }
}
