package ma.ewallet.common.error;

import org.springframework.http.HttpStatus;

/** 422: the request currency differs from the account's currency (no implicit FX). */
public class CurrencyMismatchException extends BusinessException {

    public CurrencyMismatchException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENCY_MISMATCH", message);
    }
}
