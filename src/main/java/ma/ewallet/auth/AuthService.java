package ma.ewallet.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import ma.ewallet.audit.AuditAction;
import ma.ewallet.audit.AuditOutcome;
import ma.ewallet.audit.AuditService;
import ma.ewallet.auth.dto.LoginRequest;
import ma.ewallet.auth.dto.RegisterRequest;
import ma.ewallet.auth.dto.TokenResponse;
import ma.ewallet.auth.dto.UserResponse;
import ma.ewallet.common.error.InvalidCredentialsException;
import ma.ewallet.common.error.UsernameAlreadyTakenException;
import ma.ewallet.user.AppUser;
import ma.ewallet.user.AppUserRepository;
import ma.ewallet.user.Role;

/** Registration and login. Both successes and failures go to the audit log. */
@Service
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;
    private final AuditService audit;

    public AuthService(AppUserRepository users, PasswordEncoder passwordEncoder,
                       JwtTokenService tokens, AuditService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.audit = audit;
    }

    /**
     * Not {@code @Transactional} on purpose: {@code users.save()} commits on its
     * own, so if two people register the same name at the same instant, the
     * UNIQUE constraint fails right here (→ 409 via the exception handler)
     * and we never write a "success" audit line for a user that doesn't exist.
     */
    public UserResponse register(RegisterRequest request) {
        // Fast path for the common case; the DB constraint covers the race condition.
        if (users.existsByUsername(request.username())) {
            throw new UsernameAlreadyTakenException(request.username());
        }
        AppUser user = users.save(new AppUser(request.username(),
                passwordEncoder.encode(request.password()), Role.USER)); // store the hash, never the password
        audit.record(user.getUsername(), AuditAction.USER_REGISTERED, AuditOutcome.SUCCESS,
                user.getId().toString(), null);
        return new UserResponse(user.getId(), user.getUsername());
    }

    public TokenResponse login(LoginRequest request) {
        // passwordEncoder.matches() hashes the submitted password and compares it to the stored hash.
        AppUser user = users.findByUsername(request.username())
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                .orElse(null);
        if (user == null) {
            // Same error whether the user doesn't exist or the password is wrong,
            // so an attacker can't discover which usernames exist.
            audit.record(request.username(), AuditAction.LOGIN, AuditOutcome.FAILURE, null, "Invalid credentials");
            throw new InvalidCredentialsException();
        }
        audit.record(user.getUsername(), AuditAction.LOGIN, AuditOutcome.SUCCESS, user.getId().toString(), null);
        return tokens.issue(user);
    }
}
