package ma.ewallet.money;

/**
 * Currencies supported by the wallet.
 *
 * <p>Why not {@code java.util.Currency}? Because we want a <b>closed list</b>:
 * the API must reject "USD" at the door (JSON deserialization fails with a
 * 400) rather than accept any ISO code we don't know how to handle.</p>
 *
 * <p>{@code scale} is the number of decimals allowed ("minor units"):
 * 1 MAD = 100 centimes, 1 EUR = 100 cents, hence 2. A currency like the
 * Japanese yen would have scale 0, the Tunisian dinar scale 3.</p>
 */
public enum CurrencyCode {
    MAD(2),
    EUR(2);

    private final int scale;

    CurrencyCode(int scale) {
        this.scale = scale;
    }

    /** Maximum number of decimal places for an amount in this currency. */
    public int scale() {
        return scale;
    }
}
