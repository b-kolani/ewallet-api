package ma.ewallet.common.error;

import org.springframework.http.HttpStatus;

/** 409 Conflict. */
public class UsernameAlreadyTakenException extends BusinessException {

    public UsernameAlreadyTakenException(String username) {
        super(HttpStatus.CONFLICT, "USERNAME_TAKEN", "Username " + username + " is already taken");
    }
}
