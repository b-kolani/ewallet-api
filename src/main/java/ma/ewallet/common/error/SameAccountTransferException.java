package ma.ewallet.common.error;

import org.springframework.http.HttpStatus;

/** 422: a transfer from an account to itself is meaningless. */
public class SameAccountTransferException extends BusinessException {

    public SameAccountTransferException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "SAME_ACCOUNT_TRANSFER", "Source and target accounts must be different");
    }
}
