package ma.ewallet.account;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ma.ewallet.account.dto.AccountResponse;
import ma.ewallet.account.dto.EntryResponse;
import ma.ewallet.account.dto.ReconciliationResponse;
import ma.ewallet.audit.AuditAction;
import ma.ewallet.audit.AuditOutcome;
import ma.ewallet.audit.AuditService;
import ma.ewallet.common.PageResponse;
import ma.ewallet.common.error.AccountNotFoundException;
import ma.ewallet.common.error.InvalidCredentialsException;
import ma.ewallet.ledger.LedgerEntryRepository;
import ma.ewallet.money.CurrencyCode;
import ma.ewallet.user.AppUser;
import ma.ewallet.user.AppUserRepository;

/**
 * Opening and reading accounts. It never changes a balance: only
 * {@code LedgerService} moves money.
 *
 * <p>Services return DTOs, not entities, and set {@code spring.jpa.open-in-view=false}
 * in application.yml: all database access finishes inside the service's transaction,
 * so there are no surprise lazy-loading queries during JSON serialization.</p>
 */
@Service
public class AccountService {

    private final AccountRepository accounts;
    private final AppUserRepository users;
    private final LedgerEntryRepository entries;
    private final AuditService audit;

    public AccountService(AccountRepository accounts, AppUserRepository users,
                          LedgerEntryRepository entries, AuditService audit) {
        this.accounts = accounts;
        this.users = users;
        this.entries = entries;
        this.audit = audit;
    }

    /** Opens a new wallet with a zero balance in the requested currency. */
    @Transactional
    public AccountResponse open(String username, CurrencyCode currency) {
        AppUser owner = users.findByUsername(username).orElseThrow(InvalidCredentialsException::new);
        Account account = accounts.save(Account.openFor(owner, currency));
        audit.record(username, AuditAction.ACCOUNT_OPENED, AuditOutcome.SUCCESS,
                account.getId().toString(), "Opened " + currency + " account");
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list(String username) {
        return accounts.findAllByOwner_UsernameOrderByCreatedAtAsc(username).stream()
                .map(AccountResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse get(String username, UUID accountId) {
        return AccountResponse.from(owned(username, accountId));
    }

    @Transactional(readOnly = true)
    public PageResponse<EntryResponse> statement(String username, UUID accountId, int page, int size) {
        owned(username, accountId); // throws 404 if the account isn't the caller's
        return PageResponse.from(entries
                .findByAccount_IdOrderByCreatedAtDesc(accountId, PageRequest.of(page, size))
                .map(EntryResponse::from));
    }

    /** Compares the cached balance with the sum of the journal lines (they must always match). */
    @Transactional(readOnly = true)
    public ReconciliationResponse reconcile(String username, UUID accountId) {
        Account account = owned(username, accountId);
        var ledgerBalance = entries.ledgerBalance(accountId);
        return new ReconciliationResponse(accountId, account.getBalance(), ledgerBalance,
                account.getBalance().compareTo(ledgerBalance) == 0);
    }

    /** Same rule as in LedgerService: someone else's account is reported as "not found" (404). */
    private Account owned(String username, UUID accountId) {
        return accounts.findByIdAndOwner_Username(accountId, username)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
