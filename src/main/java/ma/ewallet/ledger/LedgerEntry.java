package ma.ewallet.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import ma.ewallet.account.Account;
import ma.ewallet.money.CurrencyCode;

/**
 * One line of the journal ("écriture"): "account X was DEBITED / CREDITED
 * of amount Y as part of transaction Z".
 *
 * <p><b>Immutable by design</b>: every column is {@code updatable = false}
 * and there are no setters. In accounting you never edit or delete a line;
 * to fix a mistake you post a new, reversing transaction. That gives a
 * complete, auditable history.</p>
 *
 * <p>Entries are never saved directly: they are saved automatically with
 * their parent {@link LedgerTransaction} (cascade PERSIST).</p>
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The owning side of the transaction ↔ entries relationship (holds the foreign key column). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private LedgerTransaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6, updatable = false)
    private EntryDirection direction;

    /** Always positive: the direction carries the sign, not the amount. */
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    /**
     * Balance of the account right after this line, like the "solde" column
     * of a bank statement. Null for SYSTEM accounts, which keep no balance.
     */
    @Column(name = "balance_after", precision = 19, scale = 2, updatable = false)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
        // for JPA
    }

    /**
     * Package-private: only {@link LedgerTransaction#addEntry} can create an
     * entry, which guarantees amount and currency are copied from the transaction.
     */
    LedgerEntry(LedgerTransaction transaction, Account account, EntryDirection direction, BigDecimal balanceAfter) {
        this.transaction = transaction;
        this.account = account;
        this.direction = direction;
        this.amount = transaction.getAmount();
        this.currency = transaction.getCurrency();
        this.balanceAfter = balanceAfter;
        this.createdAt = transaction.getCreatedAt();
    }

    public UUID getId() {
        return id;
    }

    public LedgerTransaction getTransaction() {
        return transaction;
    }

    public Account getAccount() {
        return account;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
