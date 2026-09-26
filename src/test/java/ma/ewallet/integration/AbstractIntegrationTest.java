package ma.ewallet.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.JsonNode;

import ma.ewallet.auth.dto.LoginRequest;
import ma.ewallet.auth.dto.RegisterRequest;
import ma.ewallet.money.CurrencyCode;
import ma.ewallet.operation.dto.DepositRequest;
import ma.ewallet.operation.dto.TransferRequest;
import ma.ewallet.operation.dto.WithdrawalRequest;

// ---------------------------------------------------------------------------
// Base class of all integration tests (*IT). Unlike unit tests, nothing is
// mocked here: real HTTP calls, real Spring Security, real PostgreSQL.
// Each test creates its own users (random usernames), so tests never share
// data and can run in any order without cleaning the database.
// ---------------------------------------------------------------------------
/**
 * Boots the full application on a random port against a real PostgreSQL
 * started by Testcontainers (requires a running Docker daemon).
 * The container is a JVM-wide singleton shared by all integration tests.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.operations.max-attempts=15", "app.operations.backoff-ms=5"})
public abstract class AbstractIntegrationTest {

    protected static final String PASSWORD = "S3cure-Passw0rd";

    /*
     * One PostgreSQL container for the whole test run ("singleton container"),
     * started in the static block below. @ServiceConnection makes Spring Boot
     * point spring.datasource.* at it automatically (random port, credentials).
     *
     * Why not @Container? With @Container, JUnit stops the container after each
     * test class, but Spring caches the application context between classes —
     * the next class would reuse a context connected to a dead database.
     */
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    /** HTTP client pre-configured with the random port. Unlike RestTemplate, it never throws on 4xx/5xx. */
    @Autowired
    protected TestRestTemplate rest;

    /** Direct SQL access, used to check invariants in the database itself. */

    @Autowired
    protected JdbcTemplate jdbc;

    // ------------------------------------------------------------------ users

    protected static String randomUsername() {
        return "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    protected ResponseEntity<JsonNode> register(String username) {
        return rest.postForEntity("/api/auth/register", new RegisterRequest(username, PASSWORD), JsonNode.class);
    }

    /** Registers a fresh user and returns its bearer token. */
    protected String newUser() {
        String username = randomUsername();
        assertThat(register(username).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> login = rest.postForEntity("/api/auth/login",
                new LoginRequest(username, PASSWORD), JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return login.getBody().get("accessToken").asText();
    }

    // --------------------------------------------------------------- accounts

    protected UUID openAccount(String token, CurrencyCode currency) {
        ResponseEntity<JsonNode> response = call(HttpMethod.POST, "/api/accounts", token, null,
                Map.of("currency", currency.name()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().get("id").asText());
    }

    protected BigDecimal balanceOf(String token, UUID accountId) {
        ResponseEntity<JsonNode> response = call(HttpMethod.GET, "/api/accounts/" + accountId, token, null, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().get("balance").decimalValue();
    }

    // ------------------------------------------------------------- operations

    protected ResponseEntity<JsonNode> deposit(String token, UUID accountId, String amount, CurrencyCode currency) {
        return call(HttpMethod.POST, "/api/accounts/" + accountId + "/deposits", token, null,
                new DepositRequest(new BigDecimal(amount), currency, "test deposit"));
    }

    protected ResponseEntity<JsonNode> withdraw(String token, UUID accountId, String amount, CurrencyCode currency) {
        return call(HttpMethod.POST, "/api/accounts/" + accountId + "/withdrawals", token, null,
                new WithdrawalRequest(new BigDecimal(amount), currency, "test withdrawal"));
    }

    protected ResponseEntity<JsonNode> transfer(String token, String idempotencyKey, UUID from, UUID to,
                                                String amount, CurrencyCode currency) {
        return call(HttpMethod.POST, "/api/transfers", token, idempotencyKey,
                new TransferRequest(from, to, new BigDecimal(amount), currency, "test transfer"));
    }

    protected ResponseEntity<JsonNode> call(HttpMethod method, String path, String token,
                                            String idempotencyKey, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    // ------------------------------------------------------------- invariants

    /** Cached balance must equal the balance recomputed from the journal. */
    protected void assertLedgerConsistent(UUID accountId) {
        BigDecimal cached = jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, accountId);
        BigDecimal derived = jdbc.queryForObject("""
                select coalesce(sum(case when direction = 'CREDIT' then amount else -amount end), 0)
                from ledger_entries where account_id = ?""", BigDecimal.class, accountId);
        assertThat(cached).as("cached vs ledger balance of %s", accountId).isEqualByComparingTo(derived);
    }

    /** Double-entry invariant: every journal transaction has debits == credits. */
    protected void assertEveryTransactionIsBalanced() {
        Integer unbalanced = jdbc.queryForObject("""
                select count(*) from (
                    select transaction_id from ledger_entries
                    group by transaction_id
                    having sum(case when direction = 'DEBIT' then amount else -amount end) <> 0
                        or count(*) <> 2
                ) t""", Integer.class);
        assertThat(unbalanced).isZero();
    }

    // ------------------------------------------------------------ concurrency

    /**
     * Starts {@code n} tasks at the same instant and waits for all results.
     * Every thread blocks on the latch; {@code countDown()} releases them all
     * together, which maximises the chance of real collisions.
     */
    protected static <T> List<T> runConcurrently(int n, IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch startSignal = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int index = i;
                futures.add(pool.submit((Callable<T>) () -> {
                    startSignal.await();
                    return task.apply(index);
                }));
            }
            startSignal.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
