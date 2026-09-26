package ma.ewallet.ledger;

/** Business meaning of a journal transaction (the accounting is the same for all: one debit, one credit). */
public enum TransactionType {
    /** Money enters the platform: settlement → wallet. */
    DEPOSIT,
    /** Money leaves the platform: wallet → settlement. */
    WITHDRAWAL,
    /** Money moves between two customers: wallet → wallet. */
    TRANSFER
}
