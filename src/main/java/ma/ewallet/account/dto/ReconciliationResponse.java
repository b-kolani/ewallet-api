package ma.ewallet.account.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Compares the cached balance with the balance recomputed from the ledger
 * ("rapprochement"). If {@code consistent} is ever false, the cache and the
 * journal disagree: a bug to investigate. The integration tests check this
 * invariant after every scenario.
 */
public record ReconciliationResponse(UUID accountId, BigDecimal cachedBalance, BigDecimal ledgerBalance,
                                     boolean consistent) {
}
