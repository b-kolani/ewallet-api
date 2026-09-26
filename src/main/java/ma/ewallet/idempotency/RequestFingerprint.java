package ma.ewallet.idempotency;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes a SHA-256 fingerprint of a request, to answer: "is this retry
 * really the <i>same</i> request as the first one?"
 *
 * <p>Why it matters: a buggy client could reuse key "abc" for a 10 MAD
 * transfer and later for a 5 000 MAD transfer. Without the fingerprint we
 * would silently return the 10 MAD result and the client would believe
 * 5 000 MAD was sent. With it, we answer 422 IDEMPOTENCY_KEY_REUSED.</p>
 *
 * <p>Two details:</p>
 * <ul>
 *   <li><b>Canonical amounts</b>: {@code 100}, {@code 100.0} and {@code 100.00}
 *       mean the same thing, so they must give the same fingerprint.</li>
 *   <li><b>Length prefix</b>: parts are written as {@code length:value;}. With a
 *       naive join, ("ab","c") and ("a","bc") would both become "abc" and collide.</li>
 * </ul>
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(Object... parts) {
        StringBuilder canonical = new StringBuilder();
        for (Object part : parts) {
            String value = canonicalise(part);
            canonical.append(value.length()).append(':').append(value).append(';');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 32 bytes → 64 hexadecimal characters (fits the VARCHAR(64) column)
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required to ship SHA-256, so this cannot happen in practice.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String canonicalise(Object part) {
        if (part == null) {
            return "<null>"; // distinct from the empty string ""
        }
        if (part instanceof BigDecimal amount) { // Java 16+ pattern matching for instanceof
            return amount.stripTrailingZeros().toPlainString();
        }
        return part.toString();
    }
}
