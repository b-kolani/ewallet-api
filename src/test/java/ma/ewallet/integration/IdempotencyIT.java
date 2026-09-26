package ma.ewallet.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

import ma.ewallet.money.CurrencyCode;

/**
 * Proves the "no double debit" promise of the Idempotency-Key header,
 * including when retries arrive <b>simultaneously</b>.
 */
class IdempotencyIT extends AbstractIntegrationTest {

    @Test
    void retryingATransferReturnsTheOriginalResultWithoutDebitingTwice() {
        String alice = newUser();
        String bob = newUser();
        UUID from = openAccount(alice, CurrencyCode.MAD);
        UUID to = openAccount(bob, CurrencyCode.MAD);
        deposit(alice, from, "1000.00", CurrencyCode.MAD);
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> first = transfer(alice, key, from, to, "250.00", CurrencyCode.MAD);
        ResponseEntity<JsonNode> retry = transfer(alice, key, from, to, "250", CurrencyCode.MAD); // same amount, other scale

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getBody().get("replayed").asBoolean()).isTrue();
        assertThat(retry.getBody().get("id").asText()).isEqualTo(first.getBody().get("id").asText());

        assertThat(balanceOf(alice, from)).isEqualByComparingTo("750.00");
        assertThat(balanceOf(bob, to)).isEqualByComparingTo("250.00");
        assertLedgerConsistent(from);
    }

    @Test
    void reusingAKeyForADifferentPayloadIsRejected() {
        String alice = newUser();
        UUID from = openAccount(alice, CurrencyCode.MAD);
        UUID to = openAccount(newUser(), CurrencyCode.MAD);
        deposit(alice, from, "100.00", CurrencyCode.MAD);
        String key = UUID.randomUUID().toString();

        transfer(alice, key, from, to, "10.00", CurrencyCode.MAD);
        ResponseEntity<JsonNode> reuse = transfer(alice, key, from, to, "20.00", CurrencyCode.MAD);

        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(reuse.getBody().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(balanceOf(alice, from)).isEqualByComparingTo("90.00");
    }

    @Test
    void keysAreScopedPerUser() {
        String alice = newUser();
        String carol = newUser();
        UUID aliceAccount = openAccount(alice, CurrencyCode.EUR);
        UUID carolAccount = openAccount(carol, CurrencyCode.EUR);
        deposit(alice, aliceAccount, "50.00", CurrencyCode.EUR);
        deposit(carol, carolAccount, "50.00", CurrencyCode.EUR);
        String sharedKey = "order-42";

        ResponseEntity<JsonNode> a = transfer(alice, sharedKey, aliceAccount, carolAccount, "5.00", CurrencyCode.EUR);
        ResponseEntity<JsonNode> c = transfer(carol, sharedKey, carolAccount, aliceAccount, "5.00", CurrencyCode.EUR);

        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(c.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(a.getBody().get("id").asText()).isNotEqualTo(c.getBody().get("id").asText());
    }

    @Test
    void concurrentRetriesWithTheSameKeyProduceExactlyOneTransfer() throws Exception {
        String alice = newUser();
        String bob = newUser();
        UUID from = openAccount(alice, CurrencyCode.MAD);
        UUID to = openAccount(bob, CurrencyCode.MAD);
        deposit(alice, from, "1000.00", CurrencyCode.MAD);
        String key = UUID.randomUUID().toString();

        // 10 identical requests at the same instant, e.g. a client whose network
        // timed out and who retried aggressively. Only ONE transfer may happen.
        List<ResponseEntity<JsonNode>> responses = runConcurrently(10,
                i -> transfer(alice, key, from, to, "100.00", CurrencyCode.MAD));

        List<ResponseEntity<JsonNode>> successes = responses.stream()
                .filter(r -> r.getStatusCode().is2xxSuccessful())
                .toList();
        Set<String> transactionIds = successes.stream()
                .map(r -> r.getBody().get("id").asText())
                .collect(Collectors.toSet());

        assertThat(successes).isNotEmpty();
        assertThat(transactionIds).hasSize(1);
        assertThat(successes.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count()).isEqualTo(1);
        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isIn(200, 201, 409));

        assertThat(balanceOf(alice, from)).isEqualByComparingTo("900.00");
        assertThat(balanceOf(bob, to)).isEqualByComparingTo("100.00");
        Integer records = jdbc.queryForObject(
                "select count(*) from idempotency_records where idempotency_key = ?", Integer.class, key);
        assertThat(Objects.requireNonNull(records)).isEqualTo(1);
        assertLedgerConsistent(from);
        assertLedgerConsistent(to);
    }
}
