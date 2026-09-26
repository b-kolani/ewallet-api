package ma.ewallet.audit;

/** How it ended. */
public enum AuditOutcome {
    SUCCESS,
    FAILURE,
    /** An idempotent retry that returned the original result. */
    REPLAYED
}
