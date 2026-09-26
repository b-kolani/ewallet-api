-- =====================================================================
--  E-Wallet schema — double-entry ledger
--
--  Flyway runs this file once at startup (tracked in flyway_schema_history).
--  Never edit it after it has run somewhere: add V2__xxx.sql instead.
--  Hibernate does NOT create tables (ddl-auto: none): the schema is
--  versioned SQL, reviewed like code.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Users. Passwords are stored as BCrypt hashes (60 chars), never in clear.
-- ---------------------------------------------------------------------
create table app_users (
    id            uuid         primary key,
    username      varchar(50)  not null unique,
    password_hash varchar(100) not null,
    role          varchar(20)  not null,
    created_at    timestamptz  not null          -- timestamptz = instant in UTC, no timezone ambiguity
);

-- ---------------------------------------------------------------------
-- Accounts. Every account is "credit-normal": a CREDIT increases its
-- balance, a DEBIT decreases it.
--   USER   = customer wallet (has an owner, can't go negative)
--   SYSTEM = settlement account, one per currency, the counterpart of
--            deposits and withdrawals (no owner)
-- balance is a CACHE of the ledger; version is the optimistic lock.
-- numeric(19,2) is exact decimal arithmetic (never use float for money).
-- ---------------------------------------------------------------------
create table accounts (
    id          uuid          primary key,
    owner_id    uuid          references app_users (id),
    type        varchar(10)   not null,
    currency    varchar(3)    not null,
    balance     numeric(19,2) not null,
    version     bigint        not null,
    created_at  timestamptz   not null,
    constraint chk_accounts_type      check (type in ('USER', 'SYSTEM')),
    constraint chk_accounts_currency  check (currency in ('MAD', 'EUR')),
    -- a SYSTEM account has no owner, a USER account must have one
    constraint chk_accounts_owner     check ((type = 'SYSTEM' and owner_id is null)
                                          or (type = 'USER'   and owner_id is not null)),
    -- Last line of defence: even if a Java bug skipped the check in
    -- Account.debit(), PostgreSQL would refuse a negative wallet balance.
    constraint chk_accounts_no_overdraft check (type = 'SYSTEM' or balance >= 0)
);
-- "partial" unique index: at most ONE settlement account per currency
create unique index ux_accounts_system_currency on accounts (currency) where type = 'SYSTEM';
-- speeds up "list my accounts"
create index ix_accounts_owner on accounts (owner_id);

-- ---------------------------------------------------------------------
-- Journal transactions ("pièces comptables"): one per deposit,
-- withdrawal or transfer.
-- ---------------------------------------------------------------------
create table ledger_transactions (
    id           uuid          primary key,
    type         varchar(20)   not null,
    amount       numeric(19,2) not null check (amount > 0),
    currency     varchar(3)    not null,
    reference    varchar(140),
    initiated_by varchar(50)   not null,
    created_at   timestamptz   not null
);

-- ---------------------------------------------------------------------
-- Journal lines ("écritures"). Immutable: never updated, never deleted.
-- Each transaction has exactly one DEBIT and one CREDIT line of the same
-- amount, so for every transaction: sum(debits) = sum(credits).
-- An account's true balance = sum(CREDIT) - sum(DEBIT) of its lines.
-- ---------------------------------------------------------------------
create table ledger_entries (
    id             uuid          primary key,
    transaction_id uuid          not null references ledger_transactions (id),
    account_id     uuid          not null references accounts (id),
    direction      varchar(6)    not null check (direction in ('DEBIT', 'CREDIT')),
    amount         numeric(19,2) not null check (amount > 0),   -- the sign is carried by direction
    currency       varchar(3)    not null,
    balance_after  numeric(19,2),            -- "solde" column of a statement; null for SYSTEM accounts
    created_at     timestamptz   not null
);
-- account statement: "lines of account X, newest first"
create index ix_ledger_entries_account on ledger_entries (account_id, created_at desc);
create index ix_ledger_entries_tx      on ledger_entries (transaction_id);

-- ---------------------------------------------------------------------
-- Idempotency keys, scoped per user. The UNIQUE constraint is what makes
-- two concurrent retries with the same key collapse into one operation:
-- the second INSERT fails, whatever the timing between the two requests.
-- ---------------------------------------------------------------------
create table idempotency_records (
    id              uuid         primary key,
    username        varchar(50)  not null,
    idempotency_key varchar(128) not null,
    request_hash    varchar(64)  not null,   -- SHA-256 hex of the request payload
    transaction_id  uuid         not null references ledger_transactions (id),
    created_at      timestamptz  not null,
    constraint ux_idempotency_user_key unique (username, idempotency_key)
);

-- ---------------------------------------------------------------------
-- Audit trail. username is plain text (no foreign key) so that failed
-- logins with unknown usernames can be recorded too.
-- ---------------------------------------------------------------------
create table audit_logs (
    id          uuid         primary key,
    username    varchar(50),
    action      varchar(40)  not null,
    outcome     varchar(10)  not null,
    resource_id varchar(64),
    details     varchar(500),
    created_at  timestamptz  not null
);
create index ix_audit_logs_user on audit_logs (username, created_at desc);

-- ---------------------------------------------------------------------
-- Settlement accounts (one per supported currency). Fixed UUIDs make
-- them easy to recognise when inspecting the database.
-- ---------------------------------------------------------------------
insert into accounts (id, owner_id, type, currency, balance, version, created_at) values
    ('00000000-0000-0000-0000-000000000001', null, 'SYSTEM', 'MAD', 0, 0, now()),
    ('00000000-0000-0000-0000-000000000002', null, 'SYSTEM', 'EUR', 0, 0, now());
