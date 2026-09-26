package ma.ewallet.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

import ma.ewallet.auth.dto.LoginRequest;
import ma.ewallet.money.CurrencyCode;

/**
 * End-to-end scenarios through the real HTTP API: authentication, deposits,
 * withdrawals, transfers and every expected error code.
 */
class WalletFlowIT extends AbstractIntegrationTest {

    @Test
    void rejectsUnauthenticatedRequests() {
        assertThat(call(HttpMethod.GET, "/api/accounts", null, null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.GET, "/api/accounts", "not-a-jwt", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void registrationAndLoginErrors() {
        String username = randomUsername();
        assertThat(register(username).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> duplicate = register(username);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody().get("code").asText()).isEqualTo("USERNAME_TAKEN");

        ResponseEntity<JsonNode> badLogin = rest.postForEntity("/api/auth/login",
                new LoginRequest(username, "wrong-password"), JsonNode.class);
        assertThat(badLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void depositThenWithdrawUpdatesBalanceLedgerAndAudit() {
        String token = newUser();
        UUID account = openAccount(token, CurrencyCode.MAD);

        ResponseEntity<JsonNode> deposit = deposit(token, account, "500.00", CurrencyCode.MAD);
        assertThat(deposit.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(deposit.getBody().get("targetAccountId").asText()).isEqualTo(account.toString());
        assertThat(deposit.getBody().get("sourceAccountId").isNull()).isTrue(); // settlement never exposed

        assertThat(withdraw(token, account, "120.50", CurrencyCode.MAD).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(balanceOf(token, account)).isEqualByComparingTo("379.50");
        assertLedgerConsistent(account);
        assertEveryTransactionIsBalanced();

        JsonNode statement = call(HttpMethod.GET, "/api/accounts/" + account + "/entries", token, null, null).getBody();
        assertThat(statement.get("totalElements").asLong()).isEqualTo(2);

        JsonNode reconciliation = call(HttpMethod.GET, "/api/accounts/" + account + "/reconciliation",
                token, null, null).getBody();
        assertThat(reconciliation.get("consistent").asBoolean()).isTrue();

        List<String> actions = new ArrayList<>();
        call(HttpMethod.GET, "/api/audit-logs", token, null, null).getBody().get("content")
                .forEach(log -> actions.add(log.get("action").asText()));
        assertThat(actions).contains("ACCOUNT_OPENED", "DEPOSIT", "WITHDRAWAL", "LOGIN");
    }

    @Test
    void transferMovesMoneyBetweenUsers() {
        String alice = newUser();
        String bob = newUser();
        UUID aliceAccount = openAccount(alice, CurrencyCode.EUR);
        UUID bobAccount = openAccount(bob, CurrencyCode.EUR);
        deposit(alice, aliceAccount, "200.00", CurrencyCode.EUR);

        ResponseEntity<JsonNode> transfer = transfer(alice, UUID.randomUUID().toString(),
                aliceAccount, bobAccount, "75.25", CurrencyCode.EUR);

        assertThat(transfer.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(transfer.getBody().get("type").asText()).isEqualTo("TRANSFER");
        assertThat(balanceOf(alice, aliceAccount)).isEqualByComparingTo("124.75");
        assertThat(balanceOf(bob, bobAccount)).isEqualByComparingTo("75.25");
        assertLedgerConsistent(aliceAccount);
        assertLedgerConsistent(bobAccount);
    }

    @Test
    void overdraftIsRejectedAndNothingIsPosted() {
        String token = newUser();
        UUID account = openAccount(token, CurrencyCode.MAD);
        deposit(token, account, "50.00", CurrencyCode.MAD);

        ResponseEntity<JsonNode> response = withdraw(token, account, "50.01", CurrencyCode.MAD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(balanceOf(token, account)).isEqualByComparingTo("50.00");
        assertLedgerConsistent(account);
    }

    @Test
    void currencyMismatchIsRejected() {
        String alice = newUser();
        UUID mad = openAccount(alice, CurrencyCode.MAD);
        UUID eur = openAccount(alice, CurrencyCode.EUR);
        deposit(alice, mad, "100.00", CurrencyCode.MAD);

        ResponseEntity<JsonNode> wrongDeposit = deposit(alice, eur, "10.00", CurrencyCode.MAD);
        ResponseEntity<JsonNode> crossCurrency = transfer(alice, UUID.randomUUID().toString(),
                mad, eur, "10.00", CurrencyCode.MAD);

        assertThat(wrongDeposit.getBody().get("code").asText()).isEqualTo("CURRENCY_MISMATCH");
        assertThat(crossCurrency.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(crossCurrency.getBody().get("code").asText()).isEqualTo("CURRENCY_MISMATCH");
        assertThat(balanceOf(alice, mad)).isEqualByComparingTo("100.00");
    }

    @Test
    void otherUsersAccountsAreInvisible() {
        String alice = newUser();
        String mallory = newUser();
        UUID aliceAccount = openAccount(alice, CurrencyCode.MAD);
        UUID malloryAccount = openAccount(mallory, CurrencyCode.MAD);
        deposit(alice, aliceAccount, "100.00", CurrencyCode.MAD);

        assertThat(call(HttpMethod.GET, "/api/accounts/" + aliceAccount, mallory, null, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(withdraw(mallory, aliceAccount, "1.00", CurrencyCode.MAD).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(transfer(mallory, UUID.randomUUID().toString(), aliceAccount, malloryAccount,
                "1.00", CurrencyCode.MAD).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(balanceOf(alice, aliceAccount)).isEqualByComparingTo("100.00");
    }

    @Test
    void invalidRequestsAreRejected() {
        String token = newUser();
        UUID account = openAccount(token, CurrencyCode.MAD);

        ResponseEntity<JsonNode> tooPrecise = deposit(token, account, "10.001", CurrencyCode.MAD);
        assertThat(tooPrecise.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooPrecise.getBody().get("errors").has("amount")).isTrue();

        assertThat(deposit(token, account, "-5", CurrencyCode.MAD).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<JsonNode> missingKey = transfer(token, null, account, UUID.randomUUID(), "1.00", CurrencyCode.MAD);
        assertThat(missingKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<JsonNode> sameAccount = transfer(token, "k-" + UUID.randomUUID(), account, account,
                "1.00", CurrencyCode.MAD);
        assertThat(sameAccount.getBody().get("code").asText()).isEqualTo("SAME_ACCOUNT_TRANSFER");
    }
}
