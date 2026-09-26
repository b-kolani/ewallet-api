package ma.ewallet.auth;

import java.time.Instant;
import java.util.List;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import ma.ewallet.auth.dto.TokenResponse;
import ma.ewallet.config.JwtProperties;
import ma.ewallet.user.AppUser;

/**
 * Issues JWTs.
 *
 * <p>A JWT is three Base64 parts separated by dots: {@code header.payload.signature}.
 * The payload ("claims") is <b>readable by anyone</b> — it is signed, not
 * encrypted. The signature only proves it was issued by us and not modified.
 * So never put secrets (password, balances...) in a token.</p>
 *
 * <p>Claims used here: {@code iss} (issuer), {@code iat} (issued at),
 * {@code exp} (expiry), {@code sub} (the username) and {@code roles}.</p>
 */
@Service
public class JwtTokenService {

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    public JwtTokenService(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    public TokenResponse issue(AppUser user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl())) // short-lived: a stolen token is useless after 1 h
                .subject(user.getUsername())           // becomes auth.getName() in controllers
                .claim("roles", List.of(user.getRole().name()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", properties.ttl().toSeconds());
    }
}
