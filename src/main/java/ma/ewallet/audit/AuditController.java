package ma.ewallet.audit;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import ma.ewallet.common.PageResponse;

/**
 * A user can only read <b>their own</b> audit log: the username comes from the JWT,
 * never from a request parameter. {@code @Min}/{@code @Max} on the parameters are
 * validated by Spring MVC (6.1+) and return 400 if violated; {@code size} is capped
 * at 100 so nobody can ask for a million rows at once.
 */
@RestController
@RequestMapping("/api/audit-logs")
@Tag(name = "Audit", description = "Journal d'audit des opérations de l'utilisateur connecté")
@SecurityRequirement(name = "bearerAuth")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    @Operation(summary = "Liste paginée du journal d'audit (plus récent d'abord)")
    public PageResponse<AuditLogResponse> list(Authentication auth,
                                               @RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return auditService.forUser(auth.getName(), page, size);
    }
}
