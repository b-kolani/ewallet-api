package ma.ewallet.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import ma.ewallet.account.Account;
import ma.ewallet.money.CurrencyCode;

/** JSON view of an account. The version and owner stay internal. */
public record AccountResponse(UUID id, CurrencyCode currency, BigDecimal balance, Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getCurrency(), account.getBalance(),
                account.getCreatedAt());
    }
}
