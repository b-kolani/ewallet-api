package ma.ewallet.audit;

/** What was attempted. Stored as text (EnumType.STRING) in audit_logs.action. */
public enum AuditAction {
    USER_REGISTERED,
    LOGIN,
    ACCOUNT_OPENED,
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER
}
