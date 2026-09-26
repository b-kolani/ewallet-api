package ma.ewallet.account.dto;

import jakarta.validation.constraints.NotNull;
import ma.ewallet.money.CurrencyCode;

/** Body of POST /api/accounts. The balance always starts at 0: money only arrives through a deposit. */
public record OpenAccountRequest(@NotNull CurrencyCode currency) {
}
