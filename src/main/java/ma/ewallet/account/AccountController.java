package ma.ewallet.account;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import ma.ewallet.account.dto.AccountResponse;
import ma.ewallet.account.dto.EntryResponse;
import ma.ewallet.account.dto.OpenAccountRequest;
import ma.ewallet.account.dto.ReconciliationResponse;
import ma.ewallet.common.PageResponse;

/**
 * Account endpoints. Every method receives the caller's identity from the JWT
 * ({@code auth.getName()}) and only ever returns the caller's own accounts.
 * {@code open} returns 201 Created with a {@code Location} header pointing to the new
 * resource, as REST conventions recommend.
 */
@RestController
@RequestMapping("/api/accounts")
@Tag(name = "Comptes", description = "Ouverture et consultation des comptes (portefeuilles)")
@SecurityRequirement(name = "bearerAuth")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @Operation(summary = "Ouvrir un compte dans une devise (MAD, EUR)")
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request,
                                                Authentication auth, UriComponentsBuilder uri) {
        AccountResponse account = accountService.open(auth.getName(), request.currency());
        return ResponseEntity.created(uri.path("/api/accounts/{id}").build(account.id())).body(account);
    }

    @GetMapping
    @Operation(summary = "Lister mes comptes")
    public List<AccountResponse> list(Authentication auth) {
        return accountService.list(auth.getName());
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Consulter un compte et son solde")
    public AccountResponse get(@PathVariable UUID accountId, Authentication auth) {
        return accountService.get(auth.getName(), accountId);
    }

    @GetMapping("/{accountId}/entries")
    @Operation(summary = "Relevé : écritures du grand livre du compte (plus récentes d'abord)")
    public PageResponse<EntryResponse> entries(@PathVariable UUID accountId, Authentication auth,
                                               @RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return accountService.statement(auth.getName(), accountId, page, size);
    }

    @GetMapping("/{accountId}/reconciliation")
    @Operation(summary = "Rapprochement : solde en cache vs solde recalculé depuis le grand livre")
    public ReconciliationResponse reconcile(@PathVariable UUID accountId, Authentication auth) {
        return accountService.reconcile(auth.getName(), accountId);
    }
}
