package ma.ewallet.operation;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ma.ewallet.operation.dto.DepositRequest;
import ma.ewallet.operation.dto.TransactionResponse;
import ma.ewallet.operation.dto.TransferRequest;
import ma.ewallet.operation.dto.WithdrawalRequest;

/**
 * HTTP layer for money movements. A controller should stay thin: read the
 * request, delegate to a service, turn the result into an HTTP response.
 * No business rule lives here.
 *
 * <p>{@code Authentication auth} is injected by Spring Security from the
 * validated JWT; {@code auth.getName()} is the token's subject, i.e. the
 * username. We never trust a username sent in the request body.</p>
 *
 * <p>{@code @Valid} triggers Bean Validation on the DTO ({@code @NotNull},
 * {@code @DecimalMin}...); failures become a 400 before our code even runs.</p>
 *
 * <p>The {@code @Operation}/{@code @ApiResponse} annotations only feed the
 * Swagger documentation.</p>
 */
@RestController
@Tag(name = "Opérations", description = "Dépôts, retraits et transferts (écritures en partie double)")
@SecurityRequirement(name = "bearerAuth")
public class OperationController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final OperationService operations;

    public OperationController(OperationService operations) {
        this.operations = operations;
    }

    @PostMapping("/api/accounts/{accountId}/deposits")
    @Operation(summary = "Déposer des fonds sur un de mes comptes")
    @ApiResponse(responseCode = "201", description = "Dépôt effectué")
    @ApiResponse(responseCode = "200", description = "Rejeu idempotent : résultat original renvoyé")
    public ResponseEntity<TransactionResponse> deposit(
            @PathVariable UUID accountId,
            @Valid @RequestBody DepositRequest request,
            @Parameter(description = "Clé d'idempotence (optionnelle pour un dépôt)")
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            Authentication auth) {
        return respond(operations.deposit(auth.getName(), accountId, request, idempotencyKey));
    }

    @PostMapping("/api/accounts/{accountId}/withdrawals")
    @Operation(summary = "Retirer des fonds d'un de mes comptes")
    @ApiResponse(responseCode = "201", description = "Retrait effectué")
    @ApiResponse(responseCode = "422", description = "Solde insuffisant ou devise incorrecte")
    public ResponseEntity<TransactionResponse> withdraw(
            @PathVariable UUID accountId,
            @Valid @RequestBody WithdrawalRequest request,
            @Parameter(description = "Clé d'idempotence (optionnelle pour un retrait)")
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            Authentication auth) {
        return respond(operations.withdraw(auth.getName(), accountId, request, idempotencyKey));
    }

    @PostMapping("/api/transfers")
    @Operation(summary = "Transférer des fonds vers un autre compte de même devise",
            description = "L'en-tête Idempotency-Key est obligatoire : une nouvelle tentative avec la même clé "
                    + "et le même contenu renvoie le transfert original sans nouveau débit.")
    @ApiResponse(responseCode = "201", description = "Transfert effectué")
    @ApiResponse(responseCode = "200", description = "Rejeu idempotent : résultat original renvoyé")
    @ApiResponse(responseCode = "409", description = "Conflit de concurrence persistant, réessayer avec la même clé")
    @ApiResponse(responseCode = "422", description = "Solde insuffisant, devise incorrecte ou clé réutilisée")
    public ResponseEntity<TransactionResponse> transfer(
            @Valid @RequestBody TransferRequest request,
            @Parameter(description = "Clé d'idempotence unique par transfert (ex. UUID v4)", required = true)
            @RequestHeader(name = IDEMPOTENCY_KEY) String idempotencyKey, // required: missing header → 400
            Authentication auth) {
        return respond(operations.transfer(auth.getName(), request, idempotencyKey));
    }

    /**
     * 201 Created for a new operation; 200 OK + {@code Idempotent-Replayed: true}
     * for a retry, so the client can tell "I just did it" from "it was already done".
     */
    private static ResponseEntity<TransactionResponse> respond(TransactionResponse result) {
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(result);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
}
