package ma.ewallet.common.error;

import org.springframework.http.HttpStatus;

/** 401: same message for unknown user and wrong password, on purpose. */
public class InvalidCredentialsException extends BusinessException {

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password");
    }
}
