package ma.ewallet.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe binding of the {@code app.jwt.*} block of application.yml.
 * Spring converts "PT1H" (ISO-8601) into a {@link Duration} of one hour.
 * Registered thanks to {@code @ConfigurationPropertiesScan} on the main class.
 *
 * @param secret HMAC key, provided via the JWT_SECRET environment variable in production
 * @param ttl    token lifetime
 * @param issuer value of the "iss" claim, checked when decoding
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, Duration ttl, String issuer) {
}
