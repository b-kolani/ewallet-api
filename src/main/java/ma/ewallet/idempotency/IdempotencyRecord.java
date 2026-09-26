package ma.ewallet.idempotency;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * "User U already used key K, for request fingerprint H, and it produced
 * ledger transaction T."
 *
 * <p>The table has a UNIQUE constraint on (username, idempotency_key).
 * That constraint — enforced by PostgreSQL, not by Java — is what makes
 * two <i>simultaneous</i> requests with the same key impossible to both
 * succeed: the second INSERT fails, whatever the timing.</p>
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Keys are scoped per user: Alice and Bob may both use "order-42" independently. */
    @Column(nullable = false, length = 50, updatable = false)
    private String username;

    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    /** SHA-256 of the request payload, to detect a key reused for a different request. */
    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    /** The result to send back on a retry. */
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
        // for JPA
    }

    public IdempotencyRecord(String username, String idempotencyKey, String requestHash, UUID transactionId) {
        this.username = username;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.transactionId = transactionId;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
