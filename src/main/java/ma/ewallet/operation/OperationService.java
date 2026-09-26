package ma.ewallet.operation;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import ma.ewallet.audit.AuditAction;
import ma.ewallet.audit.AuditOutcome;
import ma.ewallet.audit.AuditService;
import ma.ewallet.common.error.BusinessException;
import ma.ewallet.common.error.ConcurrentUpdateException;
import ma.ewallet.common.error.InvalidIdempotencyKeyException;
import ma.ewallet.idempotency.IdempotencyContext;
import ma.ewallet.idempotency.RequestFingerprint;
import ma.ewallet.ledger.LedgerService;
import ma.ewallet.ledger.LedgerTransaction;
import ma.ewallet.operation.dto.DepositRequest;
import ma.ewallet.operation.dto.TransactionResponse;
import ma.ewallet.operation.dto.TransferRequest;
import ma.ewallet.operation.dto.WithdrawalRequest;

/**
 * Orchestrates a money movement: idempotency, retries and audit.
 *
 * <pre>
 *  Controller → OperationService.execute()            (no transaction)
 *                 └─ loop: runner.run()               (NEW transaction per attempt)
 *                        ├─ idempotency lookup
 *                        ├─ LedgerService.transfer()  (joins the transaction)
 *                        └─ save idempotency record
 *                 └─ audit.record()                   (its own transaction)
 * </pre>
 *
 * <h2>Why is this class deliberately NOT @Transactional?</h2>
 * After a failed commit, a transaction is dead: it can't be retried.
 * To retry, we need a brand-new transaction that re-reads the accounts with
 * their latest balance and version. So the retry loop must sit
 * <i>outside</i> the transaction, and each attempt opens its own.
 *
 * <h2>Which failures are retried?</h2>
 * <ul>
 *   <li>{@link OptimisticLockingFailureException}: another request updated one of
 *       our accounts in between ({@code @Version} mismatch). Retrying with fresh
 *       data is safe because our failed attempt was fully rolled back.</li>
 *   <li>{@link DataIntegrityViolationException}: a concurrent request with the
 *       same Idempotency-Key committed first. The retry finds its record and
 *       returns its result (a "replay").</li>
 * </ul>
 * Business errors (insufficient funds, wrong currency…) are NOT retried:
 * retrying would give the same answer.
 */
@Service
public class OperationService {

    private static final Logger log = LoggerFactory.getLogger(OperationService.class);
    static final int MAX_KEY_LENGTH = 128;

    private final IdempotentOperationRunner runner;
    private final LedgerService ledger;
    private final AuditService audit;
    private final int maxAttempts;
    private final long backoffMs;

    /**
     * {@code @Value} reads {@code app.operations.*} from application.yml
     * (the value after ':' is the default). Passing them through the
     * constructor lets unit tests use e.g. {@code backoffMs = 0} for speed.
     */
    public OperationService(IdempotentOperationRunner runner, LedgerService ledger, AuditService audit,
                            @Value("${app.operations.max-attempts:5}") int maxAttempts,
                            @Value("${app.operations.backoff-ms:25}") long backoffMs) {
        this.runner = runner;
        this.ledger = ledger;
        this.audit = audit;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMs = Math.max(0, backoffMs);
    }

    public TransactionResponse deposit(String username, UUID accountId, DepositRequest request, String idempotencyKey) {
        // The operation name is part of the fingerprint: the same key can't be
        // reused for a deposit and then for a withdrawal.
        IdempotencyContext context = context(username, idempotencyKey,
                "DEPOSIT", accountId, request.amount(), request.currency(), request.reference());
        // The lambda is not executed here: it is handed to the runner, which calls it
        // inside its transaction (and again on each retry).
        return execute(AuditAction.DEPOSIT, username, context, () -> ledger.deposit(
                username, accountId, request.amount(), request.currency(), request.reference()));
    }

    public TransactionResponse withdraw(String username, UUID accountId, WithdrawalRequest request,
                                        String idempotencyKey) {
        IdempotencyContext context = context(username, idempotencyKey,
                "WITHDRAWAL", accountId, request.amount(), request.currency(), request.reference());
        return execute(AuditAction.WITHDRAWAL, username, context, () -> ledger.withdraw(
                username, accountId, request.amount(), request.currency(), request.reference()));
    }

    public TransactionResponse transfer(String username, TransferRequest request, String idempotencyKey) {
        // Mandatory for transfers (the controller also declares the header as required).
        if (idempotencyKey == null) {
            throw new InvalidIdempotencyKeyException();
        }
        IdempotencyContext context = context(username, idempotencyKey, "TRANSFER", request.sourceAccountId(),
                request.targetAccountId(), request.amount(), request.currency(), request.reference());
        return execute(AuditAction.TRANSFER, username, context, () -> ledger.transfer(
                username, request.sourceAccountId(), request.targetAccountId(),
                request.amount(), request.currency(), request.reference()));
    }

    /** The retry loop. Returns on success, throws on business error or after too many conflicts. */
    private TransactionResponse execute(AuditAction action, String username, IdempotencyContext context,
                                        Supplier<LedgerTransaction> operation) {
        for (int attempt = 1; ; attempt++) {
            try {
                TransactionResponse result = runner.run(context, operation); // one attempt = one transaction
                audit.record(username, action, result.replayed() ? AuditOutcome.REPLAYED : AuditOutcome.SUCCESS,
                        result.id().toString(), describe(result));
                return result;
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException conflict) {
                // Technical conflict: the attempt was rolled back, nothing happened. Try again?
                if (attempt >= maxAttempts) {
                    audit.record(username, action, AuditOutcome.FAILURE, null,
                            "Concurrent update, gave up after " + attempt + " attempts");
                    // 409: the client may safely retry later WITH THE SAME KEY.
                    throw new ConcurrentUpdateException(conflict);
                }
                log.debug("{} attempt {} for {} hit a concurrent update, retrying", action, attempt, username);
                backoff(attempt, conflict);
            } catch (BusinessException rejected) {
                // Business refusal (422, 404…): record it and let the exception handler answer.
                audit.record(username, action, AuditOutcome.FAILURE, null, rejected.getCode() + ": " + rejected.getMessage());
                throw rejected;
            }
        }
    }

    /**
     * Waits a little before retrying. Linear (longer at each attempt) plus
     * random "jitter": if 10 requests collide and all retry after exactly
     * 25 ms, they collide again; random delays spread them out.
     */
    private void backoff(int attempt, RuntimeException cause) {
        if (backoffMs == 0) {
            return;
        }
        try {
            Thread.sleep(backoffMs * attempt + ThreadLocalRandom.current().nextLong(backoffMs + 1));
        } catch (InterruptedException e) {
            // Good practice: restore the interrupt flag so callers know the thread was interrupted.
            Thread.currentThread().interrupt();
            throw new ConcurrentUpdateException(cause);
        }
    }

    /** Validates the key and builds the idempotency context (null = no key = no idempotency). */
    private static IdempotencyContext context(String username, String key, Object... payload) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException();
        }
        return new IdempotencyContext(username, trimmed, RequestFingerprint.of(payload));
    }

    /** Short human-readable summary stored in the audit log. */
    private static String describe(TransactionResponse tx) {
        return "%s %s %s from=%s to=%s".formatted(tx.type(), tx.amount().toPlainString(), tx.currency(),
                tx.sourceAccountId(), tx.targetAccountId());
    }
}
