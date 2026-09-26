package ma.ewallet.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ma.ewallet.auth.dto.LoginRequest;
import ma.ewallet.auth.dto.RegisterRequest;
import ma.ewallet.auth.dto.TokenResponse;
import ma.ewallet.auth.dto.UserResponse;

/**
 * Public endpoints (see SecurityConfig.PUBLIC_PATHS): obviously you can't
 * need a token to obtain your first token.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentification", description = "Inscription et obtention d'un jeton JWT")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer un utilisateur")
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    @Operation(summary = "Se connecter et obtenir un jeton JWT (Bearer)")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}
