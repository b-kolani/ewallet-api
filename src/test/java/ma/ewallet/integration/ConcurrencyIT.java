package ma.ewallet.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import ma.ewallet.money.CurrencyCode;

/**
 * Proves that optimistic locking prevents lost updates.
 * Without {@code @Version}, 20 concurrent transfers of 10.00 from a 100.00
 * balance could all read "100.00" and all succeed, sending 200.00 out of a
 * 100.00 account. With it, at most 10 succeed and the rest get 422/409.
 */
class ConcurrencyIT extends AbstractIntegrationTest {

    @Test
    void concurrentTransfersFromOneAccountNeverOverdraw() throws Exception {
        String alice = newUser();
        String bob = newUser();
        UUID from = openAccount(alice, CurrencyCode.MAD);
        UUID to = openAccount(bob, CurrencyCode.MAD);
        deposit(alice, from, "100.00", CurrencyCode.MAD);

        // 20 x 10.00 requested against a 100.00 balance
        List<HttpStatus> statuses = runConcurrently(20, i -> HttpStatus.valueOf(
                transfer(alice, UUID.randomUUID().toString(), from, to, "10.00", CurrencyCode.MAD)
                        .getStatusCode().value()));

        long succeeded = statuses.stream().filter(s -> s == HttpStatus.CREATED).count();
        assertThat(statuses).allSatisfy(s -> assertThat(s).isIn(
                HttpStatus.CREATED, HttpStatus.UNPROCESSABLE_ENTITY, HttpStatus.CONFLICT));
        assertThat(succeeded).isBetween(1L, 10L);

        BigDecimal moved = new BigDecimal("10.00").multiply(BigDecimal.valueOf(succeeded));
        assertThat(balanceOf(alice, from)).isEqualByComparingTo(new BigDecimal("100.00").subtract(moved));
        assertThat(balanceOf(bob, to)).isEqualByComparingTo(moved);
        assertThat(balanceOf(alice, from)).isNotNegative();
        assertLedgerConsistent(from);
        assertLedgerConsistent(to);
        assertEveryTransactionIsBalanced();
    }

    @Test
    void crossTransfersBetweenTwoAccountsPreserveTheTotal() throws Exception {
        String alice = newUser();
        String bob = newUser();
        UUID a = openAccount(alice, CurrencyCode.EUR);
        UUID b = openAccount(bob, CurrencyCode.EUR);
        deposit(alice, a, "500.00", CurrencyCode.EUR);
        deposit(bob, b, "500.00", CurrencyCode.EUR);

        // Alice → Bob and Bob → Alice at the same time: both accounts are updated
        // by both directions. Whatever the interleaving, no money is created or lost.
        runConcurrently(20, i -> i % 2 == 0
                ? transfer(alice, UUID.randomUUID().toString(), a, b, "7.00", CurrencyCode.EUR)
                : transfer(bob, UUID.randomUUID().toString(), b, a, "3.00", CurrencyCode.EUR));

        BigDecimal total = balanceOf(alice, a).add(balanceOf(bob, b));
        assertThat(total).isEqualByComparingTo("1000.00");
        assertLedgerConsistent(a);
        assertLedgerConsistent(b);
    }

    @Test
    void concurrentDepositsOnDifferentWalletsDoNotContendOnSettlement() throws Exception {
        List<String> tokens = new ArrayList<>();
        List<UUID> accounts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String token = newUser();
            tokens.add(token);
            accounts.add(openAccount(token, CurrencyCode.MAD));
        }

        // Every deposit also touches the MAD settlement account. Because it is not
        // versioned, these deposits don't conflict: all must succeed at the first try.
        List<HttpStatus> statuses = runConcurrently(10, i -> HttpStatus.valueOf(
                deposit(tokens.get(i), accounts.get(i), "42.00", CurrencyCode.MAD).getStatusCode().value()));

        assertThat(statuses).containsOnly(HttpStatus.CREATED);
        for (int i = 0; i < 10; i++) {
            assertThat(balanceOf(tokens.get(i), accounts.get(i))).isEqualByComparingTo("42.00");
            assertLedgerConsistent(accounts.get(i));
        }
    }
}
