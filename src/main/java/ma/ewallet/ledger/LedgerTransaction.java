package ma.ewallet.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import ma.ewallet.account.Account;
import ma.ewallet.money.CurrencyCode;

/**
 * A journal transaction ("pièce comptable"): a group of entries posted
 * together, whose debits and credits always add up to the same amount.
 *
 * <h2>Double-entry in one example</h2>
 * Alice sends 250 MAD to Bob:
 * <pre>
 *   Transaction T42  TRANSFER  250.00 MAD
 *     ├─ DEBIT   Alice's wallet   250.00   (her balance goes down)
 *     └─ CREDIT  Bob's wallet     250.00   (his balance goes up)
 *   Σ debits (250) = Σ credits (250)  ✔
 * </pre>
 * Because every transaction is balanced, the sum of all balances in the
 * system never changes: money can be moved, but never created or destroyed
 * by a bug. That is the whole point of the model.
 */
@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private TransactionType type;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    /** Free label from the client ("Loyer mars"), shown on statements. */
    @Column(length = 140, updatable = false)
    private String reference;

    /** Username of whoever triggered the operation (useful for audit/investigations). */
    @Column(name = "initiated_by", nullable = false, length = 50, updatable = false)
    private String initiatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * {@code mappedBy = "transaction"}: the foreign key lives in LedgerEntry.
     * {@code CascadeType.PERSIST}: saving the transaction also INSERTs its entries,
     * so a transaction and its lines are always written together.
     */
    @OneToMany(mappedBy = "transaction", cascade = CascadeType.PERSIST)
    private List<LedgerEntry> entries = new ArrayList<>();

    protected LedgerTransaction() {
        // for JPA
    }

    public LedgerTransaction(TransactionType type, BigDecimal amount, CurrencyCode currency,
                             String reference, String initiatedBy) {
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.reference = reference;
        this.initiatedBy = initiatedBy;
        this.createdAt = Instant.now();
    }

    /**
     * Records a journal line for an account.
     * <b>Call it after</b> {@code account.debit()/credit()}, so that
     * {@code balanceAfter} captures the new balance.
     */
    public void addEntry(Account account, EntryDirection direction) {
        // Defensive check: a MAD line inside an EUR transaction would silently corrupt the books.
        if (account.getCurrency() != currency) {
            throw new IllegalStateException("Entry currency differs from transaction currency");
        }
        BigDecimal balanceAfter = account.isSystem() ? null : account.getBalance();
        entries.add(new LedgerEntry(this, account, direction, balanceAfter));
    }

    /**
     * Enforces the golden rule of double-entry bookkeeping: total debits == total credits.
     * With exactly one debit and one credit of the same amount this can't fail today —
     * it is a guard for the day someone adds fees or multi-leg transactions.
     */
    public void verifyBalanced() {
        BigDecimal debits = total(EntryDirection.DEBIT);
        BigDecimal credits = total(EntryDirection.CREDIT);
        if (entries.size() < 2 || debits.compareTo(credits) != 0) {
            throw new IllegalStateException("Unbalanced ledger transaction: debits=%s credits=%s"
                    .formatted(debits, credits));
        }
    }

    /** Returns the (single) line of the given direction. */
    public LedgerEntry entry(EntryDirection direction) {
        return entries.stream()
                .filter(e -> e.getDirection() == direction)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No " + direction + " entry"));
    }

    private BigDecimal total(EntryDirection direction) {
        return entries.stream()
                .filter(e -> e.getDirection() == direction)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add); // sum of a stream of BigDecimal
    }

    public UUID getId() {
        return id;
    }

    public TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public String getReference() {
        return reference;
    }

    public String getInitiatedBy() {
        return initiatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Read-only view: entries may only be added through {@link #addEntry}. */
    public List<LedgerEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }
}
