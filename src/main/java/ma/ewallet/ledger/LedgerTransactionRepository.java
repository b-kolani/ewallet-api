package ma.ewallet.ledger;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** CRUD for journal transactions. Saving a transaction also saves its entries (cascade). */
public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {
}
