package ma.ewallet.audit;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Paginated history of one user, newest first (backed by index ix_audit_logs_user). */
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findByUsernameOrderByCreatedAtDesc(String username, Pageable pageable);
}
