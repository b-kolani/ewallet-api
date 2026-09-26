package ma.ewallet.config;

import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

/**
 * Swagger / OpenAPI metadata. springdoc scans the controllers and generates
 * /v3/api-docs automatically; this class only adds the title and declares
 * the "bearerAuth" scheme, which adds the "Authorize" button to Swagger UI.
 * Controllers reference it with {@code @SecurityRequirement(name = "bearerAuth")}.
 */
@Configuration
@OpenAPIDefinition(info = @Info(
        title = "E-Wallet API",
        version = "1.0.0",
        description = """
                API de portefeuille électronique : comptes, dépôts, retraits et transferts.
                Grand livre en partie double, montants BigDecimal (MAD, EUR), idempotence via \
                l'en-tête Idempotency-Key, verrouillage optimiste, authentification JWT.
                1) POST /api/auth/register  2) POST /api/auth/login  3) bouton "Authorize" avec le jeton."""))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {
}
