package ma.ewallet.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

import ma.ewallet.common.error.InvalidAmountException;

/**
 * Validation rules for monetary amounts.
 *
 * <h2>Why BigDecimal and never double?</h2>
 * {@code double} is binary floating point: {@code 0.1 + 0.2 == 0.30000000000000004}.
 * On money, those tiny errors accumulate and balances stop adding up.
 * {@link BigDecimal} stores the exact decimal value.
 *
 * <h2>Why reject instead of round?</h2>
 * If a client sends {@code 10.005 MAD}, rounding it to 10.00 or 10.01 would
 * move an amount the client never asked for. We refuse it and let the client
 * decide. That is why we use {@link RoundingMode#UNNECESSARY}: it throws if
 * rounding would actually change the value — a safety net if a check above
 * is ever removed.
 */
public final class Money {

    /** Arbitrary business ceiling: protects against typos (an extra "000") and overflow. */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000.00");

    private Money() {
        // utility class: static methods only, never instantiated
    }

    /**
     * Checks an amount and returns it with exactly the currency's scale
     * ({@code 10} becomes {@code 10.00}), so every stored amount looks the same.
     *
     * @throws InvalidAmountException if null, zero, negative, too precise or too large
     */
    public static BigDecimal normalize(BigDecimal amount, CurrencyCode currency) {
        if (amount == null) {
            throw new InvalidAmountException("Amount is required");
        }
        // signum() returns -1, 0 or 1: a cheap way to test the sign of a BigDecimal
        if (amount.signum() <= 0) {
            throw new InvalidAmountException("Amount must be strictly positive");
        }
        // stripTrailingZeros() so that "10.500" (scale 3) is accepted as 10.50,
        // while "10.501" is rejected: only *significant* decimals count.
        if (amount.stripTrailingZeros().scale() > currency.scale()) {
            throw new InvalidAmountException(
                    "Amount cannot have more than %d decimal places in %s".formatted(currency.scale(), currency));
        }
        // compareTo, not equals: new BigDecimal("1.0").equals(new BigDecimal("1.00")) is FALSE
        // because equals() also compares the scale. Always use compareTo for amounts.
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new InvalidAmountException("Amount exceeds the maximum of " + MAX_AMOUNT.toPlainString());
        }
        return amount.setScale(currency.scale(), RoundingMode.UNNECESSARY);
    }
}
