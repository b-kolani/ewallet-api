package ma.ewallet.operation;

import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import ma.ewallet.common.error.IdempotencyKeyReusedException;
import ma.ewallet.idempotency.IdempotencyContext;
import ma.ewallet.idempotency.IdempotencyRecord;
import ma.ewallet.idempotency.IdempotencyRecordRepository;
import ma.ewallet.ledger.LedgerTransaction;
import ma.ewallet.ledger.LedgerTransactionRepository;
import ma.ewallet.operation.dto.TransactionResponse;

/**
 * Executes <b>one attempt</b> of a money movement inside <b>one</b> database
 * transaction.
 *
 * <h2>Why this is safe against double debits</h2>
 * The ledger posting, the balance updates and the idempotency record are
 * committed together or not at all. There is no moment where the money has
 * moved but the key is not yet recorded (or the opposite). So a retry can
 * only see one of two states:
 * <ol>
 *   <li>no record → nothing happened → execute;</li>
 *   <li>a record → it happened → return the stored result.</li>
 * </ol>
 *
 * <h2>Why a separate class from OperationService?</h2>
 * Spring implements {@code @Transactional} with a <i>proxy</i> wrapped around
 * the bean. A method calling another method of the <b>same</b> object
 * ({@code this.run()}) bypasses the proxy, so no transaction would start.
 * Putting the transactional method in its own bean guarantees that every
 * call from {@code OperationService} goes through the proxy and gets a
 * fresh transaction — essential for retries.
 */
@Component
public class IdempotentOperationRunner {

    private final IdempotencyRecordRepository records;
    private final LedgerTransactionRepository transactions;

    public IdempotentOperationRunner(IdempotencyRecordRepository records, LedgerTransactionRepository transactions) {
        this.records = records;
        this.transactions = transactions;
    }

    /**
     * @param context   null when the client sent no Idempotency-Key (allowed for deposits/withdrawals)
     * @param operation the ledger call to perform, passed as a lambda so it runs <i>inside</i> this transaction
     */
    @Transactional
    public TransactionResponse run(IdempotencyContext context, Supplier<LedgerTransaction> operation) {
        // Step 1 — Have we seen this key before?
        if (context != null) {
            Optional<IdempotencyRecord> existing =
                    records.findByUsernameAndIdempotencyKey(context.username(), context.key());
            if (existing.isPresent()) {
                return replay(context, existing.get());
            }
        }

        // Step 2 — First time: move the money (joins this transaction).
        LedgerTransaction tx = operation.get();

        // Step 3 — Remember the key, pointing to the transaction just created.
        if (context != null) {
            // saveAndFlush (not save) forces the INSERT to run NOW, inside this method.
            // If a concurrent request with the same key committed first, PostgreSQL
            // rejects our INSERT (unique constraint) → DataIntegrityViolationException
            // → the whole transaction, including our postings from step 2, rolls back.
            // OperationService then retries, and step 1 finds the winner's record.
            records.saveAndFlush(new IdempotencyRecord(context.username(), context.key(),
                    context.requestHash(), tx.getId()));
        }
        return TransactionResponse.from(tx, false);
    }

    /** Returns the original result of an already-processed request, without moving any money. */
    private TransactionResponse replay(IdempotencyContext context, IdempotencyRecord record) {
        // Same key but a different request? That's a client bug: refuse loudly.
        if (!record.getRequestHash().equals(context.requestHash())) {
            throw new IdempotencyKeyReusedException(context.key());
        }
        LedgerTransaction original = transactions.findById(record.getTransactionId())
                .orElseThrow(() -> new IllegalStateException("Idempotency record points to a missing transaction"));
        return TransactionResponse.from(original, true);
    }
}
