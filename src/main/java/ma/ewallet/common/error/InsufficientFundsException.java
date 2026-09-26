package ma.ewallet.common.error;

import java.util.UUID;

import org.springframework.http.HttpStatus;

/** 422 Unprocessable Entity: the request is well-formed but the balance is too low. */
public class InsufficientFundsException extends BusinessException {

    public InsufficientFundsException(UUID accountId) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "Insufficient funds on account " + accountId);
    }
}
