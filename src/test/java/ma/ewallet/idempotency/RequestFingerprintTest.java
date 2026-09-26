package ma.ewallet.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The fingerprint must identify the *meaning* of a request, not its formatting. */
class RequestFingerprintTest {

    private final UUID account = UUID.randomUUID();

    @Test
    void equivalentAmountsProduceTheSameFingerprint() {
        assertThat(RequestFingerprint.of("TRANSFER", account, new BigDecimal("100")))
                .isEqualTo(RequestFingerprint.of("TRANSFER", account, new BigDecimal("100.00")))
                .hasSize(64);
    }

    @Test
    void differentPayloadsProduceDifferentFingerprints() {
        assertThat(RequestFingerprint.of("TRANSFER", account, new BigDecimal("100")))
                .isNotEqualTo(RequestFingerprint.of("TRANSFER", account, new BigDecimal("100.01")))
                .isNotEqualTo(RequestFingerprint.of("DEPOSIT", account, new BigDecimal("100")));
    }

    @Test
    void partsCannotCollideByConcatenation() {
        assertThat(RequestFingerprint.of("ab", "c")).isNotEqualTo(RequestFingerprint.of("a", "bc"));
        assertThat(RequestFingerprint.of("x", null)).isNotEqualTo(RequestFingerprint.of("x", ""));
    }
}
