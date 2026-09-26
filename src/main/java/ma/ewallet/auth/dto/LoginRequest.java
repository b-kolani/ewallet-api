package ma.ewallet.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Login body. Deliberately no format rules: we just compare with what is stored. */
public record LoginRequest(@NotBlank String username, @NotBlank String password) {
}
