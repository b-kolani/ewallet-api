# E-Wallet API 

API REST de portefeuille électronique : **comptes, dépôts, retraits et transferts**, adossée à un grand livre comptable en **partie double**.

**Stack :** Java 21 · Spring Boot 3.3 · PostgreSQL 16 · JPA/Hibernate · Flyway · Spring Security (JWT) · JUnit 5 · Mockito · Testcontainers · JaCoCo · springdoc-openapi · Docker Compose

> 📘 **Nouveau sur le projet ?** Lisez [`docs/GUIDE.md`](docs/GUIDE.md) : il explique pas à pas les concepts (partie double, transactions, verrouillage optimiste, idempotence, JWT) et le parcours complet d'un transfert. Le code source est lui-même commenté classe par classe.

---

## Démarrage rapide

```bash
docker compose up --build
```

- Swagger UI : http://localhost:8080/swagger-ui.html
- OpenAPI JSON : http://localhost:8080/v3/api-docs
- Santé : http://localhost:8080/actuator/health

Sans Docker pour l'application (PostgreSQL seul dans Docker) :

```bash
docker compose up -d postgres
mvn spring-boot:run
```

Prérequis : JDK 21, Maven 3.9+, Docker.

## Scénario complet avec curl

```bash
# 1. Inscription + connexion
curl -s -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"S3cure-Passw0rd"}'
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"S3cure-Passw0rd"}' | jq -r .accessToken)

# 2. Ouverture d'un compte en MAD
ACC=$(curl -s -X POST localhost:8080/api/accounts -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"currency":"MAD"}' | jq -r .id)

# 3. Dépôt
curl -s -X POST localhost:8080/api/accounts/$ACC/deposits -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"amount":"1000.00","currency":"MAD","reference":"Salaire"}'

# 4. Transfert idempotent (rejouer la même commande => 200 + Idempotent-Replayed: true, aucun double débit)
curl -i -X POST localhost:8080/api/transfers -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: 7d0b9a52-2f1c-4c55-9c1e-2b1a3f0e8d11' \
  -d "{\"sourceAccountId\":\"$ACC\",\"targetAccountId\":\"<ID_COMPTE_DESTINATAIRE>\",\"amount\":\"250.00\",\"currency\":\"MAD\"}"

# 5. Relevé, rapprochement et audit
curl -s localhost:8080/api/accounts/$ACC/entries -H "Authorization: Bearer $TOKEN"
curl -s localhost:8080/api/accounts/$ACC/reconciliation -H "Authorization: Bearer $TOKEN"
curl -s localhost:8080/api/audit-logs -H "Authorization: Bearer $TOKEN"
```

## Endpoints

| Méthode | Chemin | Description |
|---|---|---|
| POST | `/api/auth/register` | Créer un utilisateur |
| POST | `/api/auth/login` | Obtenir un jeton JWT (1 h) |
| POST | `/api/accounts` | Ouvrir un compte (`MAD` ou `EUR`) |
| GET | `/api/accounts` | Lister mes comptes |
| GET | `/api/accounts/{id}` | Consulter un compte |
| GET | `/api/accounts/{id}/entries` | Relevé (écritures du grand livre, paginé) |
| GET | `/api/accounts/{id}/reconciliation` | Solde en cache vs solde recalculé depuis le ledger |
| POST | `/api/accounts/{id}/deposits` | Dépôt (`Idempotency-Key` optionnel) |
| POST | `/api/accounts/{id}/withdrawals` | Retrait (`Idempotency-Key` optionnel) |
| POST | `/api/transfers` | Transfert (`Idempotency-Key` **obligatoire**) |
| GET | `/api/audit-logs` | Mon journal d'audit (paginé) |

Les erreurs suivent la RFC 7807 (`application/problem+json`) avec un champ `code` stable :
`INSUFFICIENT_FUNDS`, `CURRENCY_MISMATCH`, `ACCOUNT_NOT_FOUND`, `IDEMPOTENCY_KEY_REUSED`, `CONCURRENT_UPDATE`, `VALIDATION_FAILED`…

---

## Choix de conception

### 1. Grand livre en partie double
Chaque mouvement est une `LedgerTransaction` composée d'exactement **une ligne DEBIT et une ligne CREDIT du même montant** (`ledger_entries`, immuables) :

| Opération | Débit | Crédit |
|---|---|---|
| Dépôt | compte de règlement SYSTEM (devise) | portefeuille utilisateur |
| Retrait | portefeuille utilisateur | compte de règlement SYSTEM |
| Transfert | portefeuille source | portefeuille cible |

Tous les comptes sont « à solde créditeur » : un crédit augmente le solde, un débit le diminue. Invariants vérifiés par les tests :
- pour chaque transaction, Σ débits = Σ crédits (`verifyBalanced()` + requête SQL de contrôle) ;
- pour chaque portefeuille, `accounts.balance` (cache) = solde recalculé depuis `ledger_entries` (endpoint `/reconciliation`).

Défense en profondeur : contrainte SQL `CHECK (type = 'SYSTEM' OR balance >= 0)` — un portefeuille ne peut jamais être négatif, même en cas de bug applicatif.

**Compte chaud :** les comptes SYSTEM participent à *tous* les dépôts/retraits. Ils ne maintiennent donc **pas** de solde en cache ni de version, sinon ils deviendraient un goulot d'étranglement de verrouillage ; leur position se déduit du ledger.

### 2. Montants et devises
`BigDecimal` exclusivement, colonnes `NUMERIC(19,2)`, sérialisation JSON sans notation scientifique. Un montant n'est **jamais arrondi silencieusement** : `10.001 MAD` est rejeté (`Money.normalize`, `RoundingMode.UNNECESSARY`). La devise de la requête doit correspondre à celle des comptes (pas de conversion implicite MAD ↔ EUR).

### 3. Idempotence (`Idempotency-Key`)
- Clé portée par utilisateur : contrainte unique `(username, idempotency_key)`.
- L'écriture comptable, la mise à jour des soldes et l'enregistrement de la clé sont **dans la même transaction SQL** : soit tout est validé, soit rien.
- Nouvelle tentative, même clé, même contenu → **200** + en-tête `Idempotent-Replayed: true`, résultat original, **aucun nouveau débit**.
- Même clé, contenu différent (empreinte SHA-256 canonique de la requête) → **422** `IDEMPOTENCY_KEY_REUSED`.
- Deux requêtes simultanées avec la même clé : la perdante reçoit une violation de contrainte unique, sa transaction est annulée, puis elle est rejouée et renvoie le résultat de la gagnante.

### 4. Concurrence
- `@Version` sur `Account` (verrouillage optimiste) : deux transferts simultanés depuis le même compte ne peuvent pas écraser le solde l'un de l'autre.
- `OperationService` (non transactionnel) relance chaque tentative dans une **nouvelle** transaction (`IdempotentOperationRunner`, `@Transactional`) avec un backoff linéaire + jitter ; au-delà de `app.operations.max-attempts` → **409** `CONCURRENT_UPDATE` (le client peut réessayer sans risque avec la même clé).

### 5. Sécurité et audit
- Spring Security **OAuth2 Resource Server** avec JWT HS256 (`NimbusJwtEncoder/Decoder`), sessions stateless, mots de passe BCrypt, validation de l'émetteur.
- Un utilisateur ne voit que ses comptes : l'accès au compte d'autrui renvoie **404** (et non 403) pour ne pas révéler son existence.
- `audit_logs` : inscription, connexion (succès/échec), ouverture de compte, dépôts, retraits, transferts avec issue `SUCCESS` / `FAILURE` / `REPLAYED`. Écrit en `REQUIRES_NEW` pour que les échecs (transaction métier annulée) soient tout de même tracés.

---

## Tests et couverture

```bash
mvn verify                              # unitaires + intégration + rapport JaCoCo (Docker requis)
mvn test -DexcludedGroups=integration   # unitaires uniquement, sans Docker
```

Rapport de couverture : `target/site/jacoco/index.html` (seuil minimal imposé : 75 % des lignes, configurable via `jacoco.minimum.line.coverage`).

| Type | Classes | Ce qui est vérifié |
|---|---|---|
| Unitaires (JUnit 5, Mockito) | `MoneyTest`, `RequestFingerprintTest`, `LedgerServiceTest`, `IdempotentOperationRunnerTest`, `OperationServiceTest` | règles de montant, écritures équilibrées, découvert, devises, rejeu/conflit de clé, relances sur conflit optimiste |
| Intégration (Testcontainers PostgreSQL) | `WalletFlowIT` | parcours complet HTTP + JWT, erreurs 400/401/404/409/422, relevé, audit |
| | `IdempotencyIT` | rejeu sans double débit, clé réutilisée, portée par utilisateur, **10 requêtes simultanées même clé → 1 seul transfert** |
| | `ConcurrencyIT` | **20 transferts simultanés** sur un solde insuffisant → jamais de découvert, total conservé, ledger cohérent |

## Structure

```
src/main/java/ma/ewallet
├── account/       Account (@Version), service, contrôleur, DTO
├── ledger/        LedgerTransaction, LedgerEntry, LedgerService (seul point de mouvement d'argent)
├── operation/     OperationService (relances), IdempotentOperationRunner (@Transactional), contrôleur
├── idempotency/   IdempotencyRecord, empreinte SHA-256 des requêtes
├── audit/         Journal d'audit
├── auth/, user/   Inscription, connexion, émission JWT
├── money/         CurrencyCode (MAD, EUR), règles de montant
├── config/        Sécurité, OpenAPI
└── common/        Erreurs RFC 7807, pagination
src/main/resources/db/migration   Schéma Flyway (+ comptes de règlement)
```

## Pistes d'évolution
Conversion de devises avec taux horodatés · expiration des clés d'idempotence (TTL) · rôle ADMIN et consultation globale de l'audit · pattern outbox pour publier les événements de transaction · jetons de rafraîchissement.
