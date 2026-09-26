package ma.ewallet.auth.dto;

/**
 * Returned by /api/auth/login. {@code expiresIn} is in seconds, so the client
 * knows when to log in again.
 */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
}
