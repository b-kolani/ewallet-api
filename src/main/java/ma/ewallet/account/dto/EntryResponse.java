package ma.ewallet.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import ma.ewallet.ledger.EntryDirection;
import ma.ewallet.ledger.LedgerEntry;
import ma.ewallet.ledger.TransactionType;
import ma.ewallet.money.CurrencyCode;

/**
 * One line of an account statement ("relevé"): direction, amount and the balance
 * right after it, like a bank statement. Built inside a read-only transaction
 * because it reads the entry's lazily-loaded transaction.
 */
public record EntryResponse(UUID id, UUID transactionId, TransactionType type, EntryDirection direction,
                            BigDecimal amount, CurrencyCode currency, BigDecimal balanceAfter,
                            String reference, Instant createdAt) {

    public static EntryResponse from(LedgerEntry entry) {
        return new EntryResponse(entry.getId(), entry.getTransaction().getId(), entry.getTransaction().getType(),
                entry.getDirection(), entry.getAmount(), entry.getCurrency(), entry.getBalanceAfter(),
                entry.getTransaction().getReference(), entry.getCreatedAt());
    }
}
