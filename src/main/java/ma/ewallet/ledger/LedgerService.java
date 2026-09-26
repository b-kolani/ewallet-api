package ma.ewallet.ledger;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ma.ewallet.account.Account;
import ma.ewallet.account.AccountRepository;
import ma.ewallet.account.AccountType;
import ma.ewallet.common.error.AccountNotFoundException;
import ma.ewallet.common.error.CurrencyMismatchException;
import ma.ewallet.common.error.SameAccountTransferException;
import ma.ewallet.money.CurrencyCode;
import ma.ewallet.money.Money;

/**
 * The <b>only</b> component allowed to move money. Every movement becomes
 * one balanced journal transaction (one DEBIT line + one CREDIT line):
 *
 * <pre>
 *  Operation    DEBIT (balance ↓)          CREDIT (balance ↑)
 *  DEPOSIT      settlement(currency)       user wallet
 *  WITHDRAWAL   user wallet                settlement(currency)
 *  TRANSFER     source wallet              target wallet
 * </pre>
 *
 * <h2>What this class does NOT do</h2>
 * It doesn't retry, doesn't handle Idempotency-Key and doesn't audit: that's
 * {@code OperationService}'s job. Keeping pure accounting rules here makes
 * this class easy to unit-test with mocks (see {@code LedgerServiceTest}).
 *
 * <h2>Transactions</h2>
 * {@code @Transactional} (default propagation REQUIRED): when called from
 * {@code IdempotentOperationRunner}, it <i>joins</i> the runner's transaction;
 * called alone, it opens its own. Either way, all reads and writes of one
 * operation commit or roll back together.
 */
@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final LedgerTransactionRepository transactions;

    /** Constructor injection: dependencies are explicit, final, and easy to mock in tests. */
    public LedgerService(AccountRepository accounts, LedgerTransactionRepository transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @Transactional
    public LedgerTransaction deposit(String username, UUID accountId, BigDecimal amount,
                                     CurrencyCode currency, String reference) {
        Account wallet = ownedAccount(username, accountId);
        requireCurrency(wallet, currency);
        BigDecimal value = Money.normalize(amount, currency);
        // Money comes "from outside" → the settlement account is debited.
        return post(TransactionType.DEPOSIT, value, currency, reference, username,
                settlementAccount(currency), wallet);
    }

    @Transactional
    public LedgerTransaction withdraw(String username, UUID accountId, BigDecimal amount,
                                      CurrencyCode currency, String reference) {
        Account wallet = ownedAccount(username, accountId);
        requireCurrency(wallet, currency);
        BigDecimal value = Money.normalize(amount, currency);
        // Money goes "outside" → the settlement account is credited.
        return post(TransactionType.WITHDRAWAL, value, currency, reference, username,
                wallet, settlementAccount(currency));
    }

    @Transactional
    public LedgerTransaction transfer(String username, UUID sourceId, UUID targetId, BigDecimal amount,
                                      CurrencyCode currency, String reference) {
        // Cheapest checks first, before touching the database.
        if (sourceId.equals(targetId)) {
            throw new SameAccountTransferException();
        }
        // The SOURCE must belong to the caller (you can only spend your own money)...
        Account source = ownedAccount(username, sourceId);
        // ...but the TARGET can belong to anyone. Settlement accounts are hidden (404).
        Account target = accounts.findById(targetId)
                .filter(account -> !account.isSystem())
                .orElseThrow(() -> new AccountNotFoundException(targetId));
        // No implicit currency conversion: MAD → EUR would require an exchange rate.
        requireCurrency(source, currency);
        requireCurrency(target, currency);
        BigDecimal value = Money.normalize(amount, currency);
        return post(TransactionType.TRANSFER, value, currency, reference, username, source, target);
    }

    /**
     * Common posting routine for the three operations.
     *
     * <p>Note that we never call {@code accounts.save(...)}: the accounts were
     * loaded in this transaction, so they are <i>managed</i> by Hibernate.
     * At commit, Hibernate detects that {@code balance} changed ("dirty
     * checking") and issues the UPDATE itself — with the {@code version}
     * check that protects us from concurrent writers.</p>
     */
    private LedgerTransaction post(TransactionType type, BigDecimal amount, CurrencyCode currency,
                                   String reference, String username, Account debited, Account credited) {
        // 1. Update the cached balances. debit() throws InsufficientFundsException
        //    for a user wallet; the exception rolls back the whole transaction.
        debited.debit(amount);
        credited.credit(amount);

        // 2. Write the two journal lines (after step 1, so balanceAfter is correct).
        LedgerTransaction tx = new LedgerTransaction(type, amount, currency, reference, username);
        tx.addEntry(debited, EntryDirection.DEBIT);
        tx.addEntry(credited, EntryDirection.CREDIT);

        // 3. Double-entry guard: Σ debits must equal Σ credits.
        tx.verifyBalanced();

        // 4. INSERT the transaction (its entries follow thanks to cascade PERSIST).
        return transactions.save(tx);
    }

    /**
     * Loads an account of the caller. For someone else's account we answer
     * 404 "not found", not 403 "forbidden": a 403 would confirm that the
     * account exists, letting an attacker probe valid account ids.
     */
    private Account ownedAccount(String username, UUID accountId) {
        return accounts.findByIdAndOwner_Username(accountId, username)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    /** Settlement accounts are created by the Flyway migration; missing one is a deployment bug (500). */
    private Account settlementAccount(CurrencyCode currency) {
        return accounts.findByTypeAndCurrency(AccountType.SYSTEM, currency)
                .orElseThrow(() -> new IllegalStateException("Missing settlement account for " + currency));
    }

    /** The client states the currency explicitly, so a mistake is caught instead of silently accepted. */
    private static void requireCurrency(Account account, CurrencyCode currency) {
        if (account.getCurrency() != currency) {
            throw new CurrencyMismatchException("Account %s is denominated in %s, not %s"
                    .formatted(account.getId(), account.getCurrency(), currency));
        }
    }
}
