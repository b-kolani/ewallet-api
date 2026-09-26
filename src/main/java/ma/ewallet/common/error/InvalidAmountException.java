package ma.ewallet.common.error;

import org.springframework.http.HttpStatus;

/** 400: zero, negative, too many decimals or above the maximum. */
public class InvalidAmountException extends BusinessException {

    public InvalidAmountException(String message) {
        super(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", message);
    }
}
