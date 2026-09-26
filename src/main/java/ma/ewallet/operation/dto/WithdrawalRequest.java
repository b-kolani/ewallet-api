package ma.ewallet.operation.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ma.ewallet.money.CurrencyCode;

/**
 * Body of POST /api/accounts/{id}/withdrawals.
 *
 * <p>The annotations are Bean Validation rules, checked by {@code @Valid} in the
 * controller before any business code runs (failure → 400 VALIDATION_FAILED):</p>
 * <ul>
 *   <li>{@code @DecimalMin("0.01")}: strictly positive amount;</li>
 *   <li>{@code @Digits(integer = 10, fraction = 2)}: at most 2 decimals and 10 integer digits;</li>
 *   <li>{@code currency} is an enum: "USD" fails JSON parsing (400).</li>
 * </ul>
 * <p>{@code Money.normalize} re-checks the amount in the service layer:
 * the service must stay safe even if called from somewhere other than this API.</p>
 *
 * <p>The amount is a {@code BigDecimal}: Jackson reads the JSON text
 * ("125.50") directly into it, without going through a lossy {@code double}.</p>
 */
public record WithdrawalRequest(
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotNull CurrencyCode currency,
        @Size(max = 140) String reference) {
}
