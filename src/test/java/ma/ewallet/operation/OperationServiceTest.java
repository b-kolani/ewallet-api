package ma.ewallet.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import ma.ewallet.account.Account;
import ma.ewallet.audit.AuditAction;
import ma.ewallet.audit.AuditOutcome;
import ma.ewallet.audit.AuditService;
import ma.ewallet.common.error.ConcurrentUpdateException;
import ma.ewallet.common.error.InsufficientFundsException;
import ma.ewallet.common.error.InvalidIdempotencyKeyException;
import ma.ewallet.ledger.LedgerService;
import ma.ewallet.ledger.TransactionType;
import ma.ewallet.money.CurrencyCode;
import ma.ewallet.operation.dto.DepositRequest;
import ma.ewallet.operation.dto.TransactionResponse;
import ma.ewallet.operation.dto.TransferRequest;

/**
 * Tests the retry loop. {@code thenThrow(a).thenThrow(b).thenReturn(c)} scripts
 * consecutive calls: fail, fail, succeed — simulating two concurrent-update
 * conflicts. The service is built by hand with backoff = 0 so the test doesn't sleep.
 */
class OperationServiceTest {

    private IdempotentOperationRunner runner;
    private AuditService audit;
    private OperationService service;

    private final TransferRequest request = new TransferRequest(UUID.randomUUID(), UUID.randomUUID(),
            new BigDecimal("10.00"), CurrencyCode.MAD, "test");

    @BeforeEach
    void setUp() {
        runner = mock(IdempotentOperationRunner.class);
        audit = mock(AuditService.class);
        service = new OperationService(runner, mock(LedgerService.class), audit, 3, 0);
    }

    @Test
    void retriesOnOptimisticLockConflictThenSucceeds() {
        when(runner.run(any(), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Account.class, UUID.randomUUID()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Account.class, UUID.randomUUID()))
                .thenReturn(response(false));

        TransactionResponse result = service.transfer("alice", request, "key-1");

        assertThat(result.replayed()).isFalse();
        verify(runner, times(3)).run(any(), any());
        verify(audit).record(eq("alice"), eq(AuditAction.TRANSFER), eq(AuditOutcome.SUCCESS), anyString(), anyString());
    }

    @Test
    void givesUpAfterMaxAttempts() {
        when(runner.run(any(), any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.transfer("alice", request, "key-1"))
                .isInstanceOf(ConcurrentUpdateException.class);
        verify(runner, times(3)).run(any(), any());
        verify(audit).record(eq("alice"), eq(AuditAction.TRANSFER), eq(AuditOutcome.FAILURE), isNull(), anyString());
    }

    @Test
    void businessErrorsAreAuditedAndNotRetried() {
        when(runner.run(any(), any())).thenThrow(new InsufficientFundsException(request.sourceAccountId()));

        assertThatThrownBy(() -> service.transfer("alice", request, "key-1"))
                .isInstanceOf(InsufficientFundsException.class);
        verify(runner, times(1)).run(any(), any());
        verify(audit).record(eq("alice"), eq(AuditAction.TRANSFER), eq(AuditOutcome.FAILURE), isNull(),
                org.mockito.ArgumentMatchers.startsWith("INSUFFICIENT_FUNDS"));
    }

    @Test
    void replaysAreAuditedAsReplayed() {
        when(runner.run(any(), any())).thenReturn(response(true));

        service.transfer("alice", request, "key-1");

        verify(audit).record(eq("alice"), eq(AuditAction.TRANSFER), eq(AuditOutcome.REPLAYED), anyString(), anyString());
    }

    @Test
    void transferRequiresAValidIdempotencyKey() {
        assertThatThrownBy(() -> service.transfer("alice", request, null))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
        assertThatThrownBy(() -> service.transfer("alice", request, "   "))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
        assertThatThrownBy(() -> service.transfer("alice", request, "k".repeat(129)))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
        verify(runner, never()).run(any(), any());
    }

    @Test
    void depositWithoutKeyRunsWithoutIdempotencyContext() {
        when(runner.run(isNull(), any())).thenReturn(response(false));

        service.deposit("alice", UUID.randomUUID(), new DepositRequest(BigDecimal.TEN, CurrencyCode.MAD, null), null);

        verify(runner).run(isNull(), any());
    }

    private TransactionResponse response(boolean replayed) {
        return new TransactionResponse(UUID.randomUUID(), TransactionType.TRANSFER, new BigDecimal("10.00"),
                CurrencyCode.MAD, request.sourceAccountId(), request.targetAccountId(), "test", Instant.now(), replayed);
    }
}
