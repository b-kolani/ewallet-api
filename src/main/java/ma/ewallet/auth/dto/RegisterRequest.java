package ma.ewallet.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registration body. The username pattern avoids spaces and odd characters;
 * the password max of 72 exists because BCrypt ignores anything beyond 72 bytes.
 */
public record RegisterRequest(
        @NotBlank
        @Pattern(regexp = "^[a-zA-Z0-9._-]{3,50}$",
                message = "must be 3-50 characters: letters, digits, dot, underscore or dash")
        String username,
        @NotBlank @Size(min = 8, max = 72) String password) {
}
