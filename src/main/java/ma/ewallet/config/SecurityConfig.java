package ma.ewallet.config;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Security configuration: stateless authentication with JWT.
 *
 * <h2>How a request is authenticated</h2>
 * <ol>
 *   <li>The client logs in once ({@code POST /api/auth/login}) and receives a
 *       signed token (JWT).</li>
 *   <li>It sends it on every request: {@code Authorization: Bearer <token>}.</li>
 *   <li>Spring Security's <i>resource server</i> filter checks the signature,
 *       the expiry and the issuer, then builds an {@code Authentication}
 *       whose name is the token's subject (the username).</li>
 *   <li>Invalid or missing token → 401, before any controller runs.</li>
 * </ol>
 *
 * <h2>Why use the resource server instead of a hand-written JWT filter?</h2>
 * It is maintained and security-reviewed by the Spring team (signature,
 * expiry, clock skew, error responses). Hand-written filters are a common
 * source of vulnerabilities.
 *
 * <h2>HS256</h2>
 * The token is signed with a shared secret (HMAC-SHA256): the same key signs
 * and verifies. Fine for a single service. With several services, you
 * would switch to RS256 (private key signs, public key verifies).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Endpoints reachable without a token. */
    private static final String[] PUBLIC_PATHS = {
            "/api/auth/**",          // register / login (you can't have a token yet)
            "/v3/api-docs/**",       // OpenAPI description
            "/swagger-ui/**",        // Swagger UI assets
            "/swagger-ui.html",
            "/actuator/health",      // used by Docker/Kubernetes health checks
            "/actuator/health/**",
            "/error"                 // Spring's error page, so error responses aren't turned into 401
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        return http
                // CSRF attacks abuse cookies the browser sends automatically. We use no
                // cookies (the token is sent explicitly in a header), so CSRF protection is unnecessary.
                .csrf(AbstractHttpConfigurer::disable)
                // No HTTP session: each request carries its own proof of identity (the JWT).
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Rules are evaluated top to bottom; the first match wins.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers("/api/**").hasRole("USER")
                        .anyRequest().authenticated())
                // Enables the "Bearer token" filter, using the JwtDecoder bean below.
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }

    /**
     * The HMAC secret. HS256 requires at least 256 bits (32 bytes): a shorter
     * secret could be brute-forced, so we refuse to start rather than run insecurely.
     */
    @Bean
    SecretKey jwtSigningKey(JwtProperties properties) {
        byte[] bytes = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes for HS256");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    /** Signs tokens at login (used by {@code JwtTokenService}). */
    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    /** Verifies tokens on each request: signature, expiry ("exp") and issuer ("iss"). */
    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    /**
     * Turns the token's {@code "roles": ["USER"]} claim into the Spring authority
     * {@code ROLE_USER}, which is what {@code hasRole("USER")} checks.
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * BCrypt: slow on purpose and salted (each hash includes random data), so
     * a stolen database can't be reversed with precomputed tables. Passwords
     * are never stored in clear text.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
