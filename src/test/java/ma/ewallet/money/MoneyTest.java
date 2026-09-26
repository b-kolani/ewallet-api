package ma.ewallet.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ma.ewallet.common.error.InvalidAmountException;

/**
 * Pure unit test: no Spring, no database, runs in milliseconds.
 * Note {@code isEqualTo} on BigDecimal also compares the scale:
 * 10.00 is NOT equal to 10.0 here — exactly what we want to check.
 */
class MoneyTest {

    @Test
    void normalizesToTheCurrencyScale() {
        assertThat(Money.normalize(new BigDecimal("10"), CurrencyCode.MAD)).isEqualTo(new BigDecimal("10.00"));
        assertThat(Money.normalize(new BigDecimal("10.5"), CurrencyCode.EUR)).isEqualTo(new BigDecimal("10.50"));
    }

    @Test
    void acceptsTrailingZerosBeyondTheScale() {
        assertThat(Money.normalize(new BigDecimal("10.500"), CurrencyCode.MAD)).isEqualTo(new BigDecimal("10.50"));
    }

    // @ParameterizedTest runs the same test once per value in @ValueSource
    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "-0.01"})
    void rejectsNonPositiveAmounts(String amount) {
        assertThatThrownBy(() -> Money.normalize(new BigDecimal(amount), CurrencyCode.MAD))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessageContaining("strictly positive");
    }

    @Test
    void neverRoundsSilently() {
        assertThatThrownBy(() -> Money.normalize(new BigDecimal("10.001"), CurrencyCode.MAD))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessageContaining("decimal places");
    }

    @Test
    void rejectsMissingAndOversizedAmounts() {
        assertThatThrownBy(() -> Money.normalize(null, CurrencyCode.EUR)).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> Money.normalize(new BigDecimal("1000000000.01"), CurrencyCode.EUR))
                .isInstanceOf(InvalidAmountException.class);
    }
}
