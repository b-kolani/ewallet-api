package ma.ewallet.ledger;

/**
 * Side of a journal line.
 *
 * <p>Convention used in this project: every account is "credit-normal",
 * like a bank sees its customers' deposits (a liability). So:</p>
 * <ul>
 *   <li>CREDIT = money arrives on the account → balance goes up</li>
 *   <li>DEBIT  = money leaves the account   → balance goes down</li>
 * </ul>
 * <p>(Careful: this is the <i>bank's</i> point of view. On your bank statement,
 * a "crédit" is money you received, which matches.)</p>
 */
public enum EntryDirection {
    /** Decreases the account balance. */
    DEBIT,
    /** Increases the account balance. */
    CREDIT
}
