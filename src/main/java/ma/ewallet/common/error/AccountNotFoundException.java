package ma.ewallet.common.error;

import java.util.UUID;

import org.springframework.http.HttpStatus;

/** 404. Also used for other users' accounts, so their existence is never revealed. */
public class AccountNotFoundException extends BusinessException {

    public AccountNotFoundException(UUID accountId) {
        super(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account " + accountId + " not found");
    }
}
