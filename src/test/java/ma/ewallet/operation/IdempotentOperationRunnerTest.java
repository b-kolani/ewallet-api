package ma.ewallet.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import ma.ewallet.account.Account;
import ma.ewallet.common.error.IdempotencyKeyReusedException;
import ma.ewallet.idempotency.IdempotencyContext;
import ma.ewallet.idempotency.IdempotencyRecord;
import ma.ewallet.idempotency.IdempotencyRecordRepository;
import ma.ewallet.ledger.EntryDirection;
import ma.ewallet.ledger.LedgerTransaction;
import ma.ewallet.ledger.LedgerTransactionRepository;
import ma.ewallet.ledger.TransactionType;
import ma.ewallet.money.CurrencyCode;
import ma.ewallet.operation.dto.TransactionResponse;
import ma.ewallet.user.AppUser;
import ma.ewallet.user.Role;

/**
 * Tests the idempotency decision logic in isolation. The "operation" is a
 * lambda that counts how many times it runs: a replay must run it 0 times.
 * The real concurrency behaviour (unique constraint, rollback) needs a real
 * database and is covered by {@code IdempotencyIT}.
 */
@ExtendWith(MockitoExtension.class)
class IdempotentOperationRunnerTest {

    @Mock
    private IdempotencyRecordRepository records;
    @Mock
    private LedgerTransactionRepository transactions;
    @InjectMocks
    private IdempotentOperationRunner runner;

    private LedgerTransaction transfer;
    private final AtomicInteger executions = new AtomicInteger();
    private Supplier<LedgerTransaction> operation;

    @BeforeEach
    void setUp() {
        Account source = Account.openFor(new AppUser("alice", "h", Role.USER), CurrencyCode.MAD);
        Account target = Account.openFor(new AppUser("bob", "h", Role.USER), CurrencyCode.MAD);
        ReflectionTestUtils.setField(source, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(target, "id", UUID.randomUUID());
        transfer = new LedgerTransaction(TransactionType.TRANSFER, new BigDecimal("10.00"), CurrencyCode.MAD, null, "alice");
        transfer.addEntry(source, EntryDirection.DEBIT);
        transfer.addEntry(target, EntryDirection.CREDIT);
        ReflectionTestUtils.setField(transfer, "id", UUID.randomUUID());
        operation = () -> {
            executions.incrementAndGet();
            return transfer;
        };
    }

    @Test
    void firstCallExecutesAndRecordsTheKey() {
        IdempotencyContext ctx = new IdempotencyContext("alice", "key-1", "hash");
        when(records.findByUsernameAndIdempotencyKey("alice", "key-1")).thenReturn(Optional.empty());

        TransactionResponse response = runner.run(ctx, operation);

        assertThat(executions).hasValue(1);
        assertThat(response.replayed()).isFalse();
        ArgumentCaptor<IdempotencyRecord> saved = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(records).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTransactionId()).isEqualTo(transfer.getId());
        assertThat(saved.getValue().getRequestHash()).isEqualTo("hash");
    }

    @Test
    void retryWithSamePayloadReplaysWithoutExecuting() {
        IdempotencyContext ctx = new IdempotencyContext("alice", "key-1", "hash");
        when(records.findByUsernameAndIdempotencyKey("alice", "key-1"))
                .thenReturn(Optional.of(new IdempotencyRecord("alice", "key-1", "hash", transfer.getId())));
        when(transactions.findById(transfer.getId())).thenReturn(Optional.of(transfer));

        TransactionResponse response = runner.run(ctx, operation);

        assertThat(executions).hasValue(0);
        assertThat(response.replayed()).isTrue();
        assertThat(response.id()).isEqualTo(transfer.getId());
        verify(records, never()).saveAndFlush(any());
    }

    @Test
    void reusingKeyWithDifferentPayloadIsRejected() {
        IdempotencyContext ctx = new IdempotencyContext("alice", "key-1", "other-hash");
        when(records.findByUsernameAndIdempotencyKey("alice", "key-1"))
                .thenReturn(Optional.of(new IdempotencyRecord("alice", "key-1", "hash", transfer.getId())));

        assertThatThrownBy(() -> runner.run(ctx, operation)).isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(executions).hasValue(0);
    }

    @Test
    void withoutKeyTheOperationSimplyRuns() {
        TransactionResponse response = runner.run(null, operation);

        assertThat(executions).hasValue(1);
        assertThat(response.sourceAccountId()).isNotNull();
        assertThat(response.targetAccountId()).isNotNull();
        verify(records, never()).saveAndFlush(any());
    }
}
