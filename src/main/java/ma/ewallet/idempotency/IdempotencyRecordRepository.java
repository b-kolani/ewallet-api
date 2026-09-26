package ma.ewallet.idempotency;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    /** Has this user already used this key? */
    Optional<IdempotencyRecord> findByUsernameAndIdempotencyKey(String username, String idempotencyKey);
}
