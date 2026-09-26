package ma.ewallet.account;

/**
 * The two kinds of accounts in the ledger.
 *
 * <p>In double-entry bookkeeping money never appears from nowhere: when a
 * customer deposits 100 MAD, <i>something</i> must be debited. That
 * "something" is the SYSTEM settlement account, which represents the real
 * money held by the platform at its bank.</p>
 */
public enum AccountType {
    /** Customer wallet. Can never go below zero. */
    USER,
    /** Settlement counterpart for deposits/withdrawals (one per currency, created by the migration). */
    SYSTEM
}
