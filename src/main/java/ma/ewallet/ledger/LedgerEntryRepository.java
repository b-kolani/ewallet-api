package ma.ewallet.ledger;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    /**
     * Account statement, newest first, one page at a time.
     *
     * <p>{@code @EntityGraph} loads each entry's transaction in the same SQL
     * query (JOIN). Without it, reading {@code entry.getTransaction().getType()}
     * for 20 entries would fire 20 extra queries: the classic "N+1 problem".</p>
     */
    @EntityGraph(attributePaths = "transaction")
    Page<LedgerEntry> findByAccount_IdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    /**
     * Balance recomputed from the journal — the source of truth.
     * Native SQL: credits count positive, debits negative, and
     * {@code coalesce(..., 0)} turns "no rows" (NULL) into 0.
     */
    @Query(value = """
            select coalesce(sum(case when direction = 'CREDIT' then amount else -amount end), 0)
            from ledger_entries
            where account_id = :accountId
            """, nativeQuery = true)
    BigDecimal ledgerBalance(@Param("accountId") UUID accountId);
}
