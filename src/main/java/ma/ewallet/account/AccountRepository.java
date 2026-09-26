package ma.ewallet.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import ma.ewallet.money.CurrencyCode;

/**
 * Spring Data generates the implementation of this interface at startup.
 * The SQL is derived from the method name: {@code findBy} + properties
 * joined by {@code And}. The underscore in {@code Owner_Username} means
 * "navigate the {@code owner} relation, then its {@code username} field"
 * (it produces a JOIN on app_users).
 */
public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Finds an account only if it belongs to this user. Using this everywhere
     * (instead of findById + an "if owner != me" check) makes it impossible
     * to forget the ownership check.
     */
    Optional<Account> findByIdAndOwner_Username(UUID id, String username);

    List<Account> findAllByOwner_UsernameOrderByCreatedAtAsc(String username);

    /** Used to find the settlement account of a currency: (SYSTEM, MAD) or (SYSTEM, EUR). */
    Optional<Account> findByTypeAndCurrency(AccountType type, CurrencyCode currency);
}
