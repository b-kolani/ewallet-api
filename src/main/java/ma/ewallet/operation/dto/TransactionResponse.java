package ma.ewallet.operation.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import ma.ewallet.ledger.EntryDirection;
import ma.ewallet.ledger.LedgerTransaction;
import ma.ewallet.ledger.TransactionType;
import ma.ewallet.money.CurrencyCode;

/**
 * JSON returned after a money movement.
 *
 * <p>Why a DTO instead of returning the entity? The entity has lazy
 * relations, internal fields (other users' balances in {@code balanceAfter},
 * settlement accounts...) and would couple the API to the database schema.
 * The DTO exposes exactly what the client needs.</p>
 *
 * <p>Settlement (SYSTEM) accounts are never exposed: a deposit has no
 * source, a withdrawal has no target (both are {@code null} in JSON).</p>
 *
 * @param replayed true if this is the stored result of an earlier request with the same Idempotency-Key
 */
public record TransactionResponse(UUID id, TransactionType type, BigDecimal amount, CurrencyCode currency,
                                  UUID sourceAccountId, UUID targetAccountId, String reference,
                                  Instant createdAt, boolean replayed) {

    /** Must be called inside a transaction (it reads the lazy entry → account relations). */
    public static TransactionResponse from(LedgerTransaction tx, boolean replayed) {
        // getId() on a lazy proxy doesn't hit the database: Hibernate already knows the id.
        UUID debited = tx.entry(EntryDirection.DEBIT).getAccount().getId();
        UUID credited = tx.entry(EntryDirection.CREDIT).getAccount().getId();
        UUID source = tx.getType() == TransactionType.DEPOSIT ? null : debited;
        UUID target = tx.getType() == TransactionType.WITHDRAWAL ? null : credited;
        return new TransactionResponse(tx.getId(), tx.getType(), tx.getAmount(), tx.getCurrency(),
                source, target, tx.getReference(), tx.getCreatedAt(), replayed);
    }
}
