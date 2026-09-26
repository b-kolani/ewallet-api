package ma.ewallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import ma.ewallet.account.Account;
import ma.ewallet.account.AccountRepository;
import ma.ewallet.account.AccountType;
import ma.ewallet.common.error.AccountNotFoundException;
import ma.ewallet.common.error.CurrencyMismatchException;
import ma.ewallet.common.error.InsufficientFundsException;
import ma.ewallet.common.error.InvalidAmountException;
import ma.ewallet.common.error.SameAccountTransferException;
import ma.ewallet.money.CurrencyCode;
import ma.ewallet.user.AppUser;
import ma.ewallet.user.Role;

/**
 * Unit test of the accounting rules with Mockito.
 *
 * <p>The repositories are <b>mocks</b>: fake objects whose answers we script
 * with {@code when(...).thenReturn(...)}. That lets us test LedgerService alone,
 * without a database, and check precisely what it did.</p>
 *
 * <ul>
 *   <li>{@code @Mock} creates a mock; {@code @InjectMocks} builds the real
 *       LedgerService and passes the mocks to its constructor.</li>
 *   <li>{@code lenient()}: Mockito normally fails a test that sets up a stub it
 *       never uses (to catch useless setup). The shared stubs of setUp() are not
 *       used by every test, hence lenient.</li>
 *   <li>{@code ReflectionTestUtils.setField(..., "id", ...)}: in production
 *       Hibernate generates the id on save; here there is no Hibernate, so we
 *       set it ourselves.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    private static final String ALICE = "alice";

    @Mock
    private AccountRepository accounts;
    @Mock
    private LedgerTransactionRepository transactions;
    @InjectMocks
    private LedgerService ledger;

    private Account aliceMad;
    private Account bobMad;
    private Account bobEur;
    private Account settlementMad;

    @BeforeEach
    void setUp() {
        aliceMad = account(new AppUser(ALICE, "hash", Role.USER), CurrencyCode.MAD);
        bobMad = account(new AppUser("bob", "hash", Role.USER), CurrencyCode.MAD);
        bobEur = account(new AppUser("bob", "hash", Role.USER), CurrencyCode.EUR);
        settlementMad = Account.settlement(CurrencyCode.MAD);
        ReflectionTestUtils.setField(settlementMad, "id", UUID.randomUUID());

        lenient().when(accounts.findByIdAndOwner_Username(aliceMad.getId(), ALICE)).thenReturn(Optional.of(aliceMad));
        lenient().when(accounts.findByTypeAndCurrency(AccountType.SYSTEM, CurrencyCode.MAD))
                .thenReturn(Optional.of(settlementMad));
        // save() returns the object it received, like the real repository does
        lenient().when(transactions.save(any(LedgerTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void depositCreditsTheWalletAndDebitsSettlement() {
        // when
        LedgerTransaction tx = ledger.deposit(ALICE, aliceMad.getId(), new BigDecimal("150"), CurrencyCode.MAD, "salary");

        // then: the wallet is credited, the settlement account is debited, 2 balanced lines

        assertThat(aliceMad.getBalance()).isEqualTo(new BigDecimal("150.00"));
        assertThat(tx.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(tx.getEntries()).hasSize(2);
        assertThat(tx.entry(EntryDirection.DEBIT).getAccount()).isSameAs(settlementMad);
        assertThat(tx.entry(EntryDirection.DEBIT).getBalanceAfter()).isNull();
        assertThat(tx.entry(EntryDirection.CREDIT).getAccount()).isSameAs(aliceMad);
        assertThat(tx.entry(EntryDirection.CREDIT).getBalanceAfter()).isEqualTo(new BigDecimal("150.00"));
        // settlement account keeps no cached balance (hot-spot avoidance)
        assertThat(settlementMad.getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void withdrawalDebitsTheWallet() {
        aliceMad.credit(new BigDecimal("100.00"));

        LedgerTransaction tx = ledger.withdraw(ALICE, aliceMad.getId(), new BigDecimal("40.25"), CurrencyCode.MAD, null);

        assertThat(aliceMad.getBalance()).isEqualTo(new BigDecimal("59.75"));
        assertThat(tx.entry(EntryDirection.DEBIT).getAccount()).isSameAs(aliceMad);
        assertThat(tx.entry(EntryDirection.CREDIT).getAccount()).isSameAs(settlementMad);
    }

    @Test
    void withdrawalRefusesOverdraft() {
        aliceMad.credit(new BigDecimal("10.00"));

        assertThatThrownBy(() -> ledger.withdraw(ALICE, aliceMad.getId(), new BigDecimal("10.01"), CurrencyCode.MAD, null))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(aliceMad.getBalance()).isEqualTo(new BigDecimal("10.00"));
        // nothing may be written when the operation is refused
        verify(transactions, never()).save(any());
    }

    @Test
    void transferMovesFundsBetweenWallets() {
        aliceMad.credit(new BigDecimal("500.00"));
        when(accounts.findById(bobMad.getId())).thenReturn(Optional.of(bobMad));

        LedgerTransaction tx = ledger.transfer(ALICE, aliceMad.getId(), bobMad.getId(),
                new BigDecimal("125.50"), CurrencyCode.MAD, "rent");

        assertThat(aliceMad.getBalance()).isEqualTo(new BigDecimal("374.50"));
        assertThat(bobMad.getBalance()).isEqualTo(new BigDecimal("125.50"));
        assertThat(tx.entry(EntryDirection.DEBIT).getAmount())
                .isEqualTo(tx.entry(EntryDirection.CREDIT).getAmount());
    }

    @Test
    void transferRejectsCurrencyMismatch() {
        aliceMad.credit(new BigDecimal("500.00"));
        when(accounts.findById(bobEur.getId())).thenReturn(Optional.of(bobEur));

        assertThatThrownBy(() -> ledger.transfer(ALICE, aliceMad.getId(), bobEur.getId(),
                new BigDecimal("10"), CurrencyCode.MAD, null))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThat(aliceMad.getBalance()).isEqualTo(new BigDecimal("500.00"));
    }

    @Test
    void transferRejectsSameAccount() {
        assertThatThrownBy(() -> ledger.transfer(ALICE, aliceMad.getId(), aliceMad.getId(),
                BigDecimal.TEN, CurrencyCode.MAD, null))
                .isInstanceOf(SameAccountTransferException.class);
    }

    @Test
    void transferToSettlementAccountIsNotAllowed() {
        when(accounts.findById(settlementMad.getId())).thenReturn(Optional.of(settlementMad));

        assertThatThrownBy(() -> ledger.transfer(ALICE, aliceMad.getId(), settlementMad.getId(),
                BigDecimal.TEN, CurrencyCode.MAD, null))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void cannotUseAnotherUsersAccount() {
        when(accounts.findByIdAndOwner_Username(bobMad.getId(), ALICE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ledger.deposit(ALICE, bobMad.getId(), BigDecimal.TEN, CurrencyCode.MAD, null))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void rejectsAmountsWithTooManyDecimals() {
        assertThatThrownBy(() -> ledger.deposit(ALICE, aliceMad.getId(), new BigDecimal("1.001"), CurrencyCode.MAD, null))
                .isInstanceOf(InvalidAmountException.class);
        verify(transactions, never()).save(any());
    }

    private static Account account(AppUser owner, CurrencyCode currency) {
        Account account = Account.openFor(owner, currency);
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        return account;
    }
}
