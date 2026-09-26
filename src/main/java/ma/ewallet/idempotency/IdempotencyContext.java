package ma.ewallet.idempotency;

/**
 * Everything the runner needs to make one request idempotent:
 * who is calling, the key they chose, and the fingerprint of what they asked.
 * A {@code record} is an immutable data class: Java generates the
 * constructor, accessors, equals/hashCode and toString.
 */
public record IdempotencyContext(String username, String key, String requestHash) {
}
