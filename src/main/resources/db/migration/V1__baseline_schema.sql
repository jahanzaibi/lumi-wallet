-- Baseline schema for the Multi-Currency Wallet and Rewards Service (HELP.md section 30).
--
-- Target: embedded Apache Derby. Kept portable to PostgreSQL, which is the intended production
-- database (HELP.md section 59). Derby specifics that shaped this file:
--   * VARCHAR is capped at 32672 chars, so the outbox payload is a CLOB rather than
--     VARCHAR(50000) as sketched in section 57.
--   * A Derby UNIQUE INDEX treats NULLs as equal and therefore permits only one NULL row. Every
--     de-duplication column below is NOT NULL and carries a deterministic natural key, falling
--     back to the row's own id when the row is legitimately repeatable. This is portable and
--     pushes the "a duplicate event cannot create another reward" invariant (section 60.12) into
--     the database instead of relying on application checks alone.
--   * Pessimistic row locking (sections 41/42) is only ever taken on wallet_balance by primary
--     key, because Derby refuses FOR UPDATE on a statement carrying ORDER BY.
--
-- Identifier columns are VARCHAR(64) rather than the VARCHAR(36) sketched in sections 31-37, so
-- that ids can carry a readable type prefix in the style HELP.md uses throughout its own examples
-- ("RED-100", "QUOTE-100", "EVT-123"). A bare UUID already fills 36 characters.

-- ---------------------------------------------------------------------------------------------
-- Assets. A monetary asset (SAR/USD/EUR) and a reward asset (POINT) must never share a balance
-- (HELP.md section 2).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE asset (
    id             VARCHAR(64) NOT NULL,
    code           VARCHAR(20) NOT NULL,
    type           VARCHAR(20) NOT NULL,
    decimal_scale  INTEGER NOT NULL,
    name           VARCHAR(100) NOT NULL,
    active         BOOLEAN NOT NULL,
    created_at     TIMESTAMP NOT NULL,

    CONSTRAINT pk_asset PRIMARY KEY (id),
    CONSTRAINT uk_asset_code UNIQUE (code),
    CONSTRAINT ck_asset_type CHECK (type IN ('MONETARY', 'REWARD'))
);

-- ---------------------------------------------------------------------------------------------
-- Wallet accounts (HELP.md section 31). One account per customer per asset. Ledger liability
-- accounts live here too, under the reserved customer id 'SYSTEM', so that every ledger entry
-- points at a real account row.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE wallet_account (
    id           VARCHAR(64) NOT NULL,
    customer_id  VARCHAR(64) NOT NULL,
    asset_id     VARCHAR(64) NOT NULL,
    account_type VARCHAR(20) NOT NULL,
    status       VARCHAR(20) NOT NULL,
    created_at   TIMESTAMP NOT NULL,

    CONSTRAINT pk_wallet_account PRIMARY KEY (id),
    CONSTRAINT uk_wallet_customer_asset UNIQUE (customer_id, asset_id),
    CONSTRAINT fk_wallet_account_asset FOREIGN KEY (asset_id) REFERENCES asset (id),
    CONSTRAINT ck_wallet_account_type CHECK (account_type IN ('CUSTOMER', 'LIABILITY')),
    CONSTRAINT ck_wallet_account_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED'))
);

-- ---------------------------------------------------------------------------------------------
-- Balances (HELP.md section 32). available / locked / debt, where debt carries already-redeemed
-- points that were later reversed (section 22).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE wallet_balance (
    wallet_account_id VARCHAR(64) NOT NULL,
    available_amount  DECIMAL(19,4) NOT NULL,
    locked_amount     DECIMAL(19,4) NOT NULL,
    debt_amount       DECIMAL(19,4) NOT NULL,
    version           BIGINT NOT NULL,
    updated_at        TIMESTAMP NOT NULL,

    CONSTRAINT pk_wallet_balance PRIMARY KEY (wallet_account_id),
    CONSTRAINT fk_wallet_balance_account FOREIGN KEY (wallet_account_id)
        REFERENCES wallet_account (id),
    -- Points can never be spent twice (section 60.6): the database refuses a negative balance
    -- even if application logic is wrong.
    CONSTRAINT ck_wallet_balance_non_negative CHECK (
        available_amount >= 0 AND locked_amount >= 0 AND debt_amount >= 0)
);

-- ---------------------------------------------------------------------------------------------
-- Reward program. Holds the points/currency conversion rate, which HELP.md implies
-- (3000 points = 30.00 SAR) but never states, so it is configuration rather than a constant.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reward_program (
    id                       VARCHAR(64) NOT NULL,
    code                     VARCHAR(50) NOT NULL,
    name                     VARCHAR(100) NOT NULL,
    reward_asset_id          VARCHAR(64) NOT NULL,
    points_per_currency_unit DECIMAL(19,4) NOT NULL,
    max_redemption_percent   DECIMAL(5,2) NOT NULL,
    active                   BOOLEAN NOT NULL,
    created_at               TIMESTAMP NOT NULL,

    CONSTRAINT pk_reward_program PRIMARY KEY (id),
    CONSTRAINT uk_reward_program_code UNIQUE (code),
    CONSTRAINT fk_reward_program_asset FOREIGN KEY (reward_asset_id) REFERENCES asset (id),
    CONSTRAINT ck_reward_program_rate CHECK (points_per_currency_unit > 0),
    CONSTRAINT ck_reward_program_percent CHECK (
        max_redemption_percent > 0 AND max_redemption_percent <= 100)
);

-- ---------------------------------------------------------------------------------------------
-- Reward rules (HELP.md section 17). refund_reversal_mode implements section 52: a refund does
-- not automatically mean a 100% reward reversal.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reward_rule (
    id                   VARCHAR(64) NOT NULL,
    program_id           VARCHAR(64) NOT NULL,
    order_type           VARCHAR(50) NOT NULL,
    earn_rate            DECIMAL(9,6) NOT NULL,
    eligibility_type     VARCHAR(30) NOT NULL,
    eligibility_days     INTEGER,
    minimum_order_amount DECIMAL(19,4),
    maximum_points       DECIMAL(19,4),
    expiration_days      INTEGER,
    refund_reversal_mode VARCHAR(20) NOT NULL,
    active               BOOLEAN NOT NULL,
    created_at           TIMESTAMP NOT NULL,

    CONSTRAINT pk_reward_rule PRIMARY KEY (id),
    CONSTRAINT uk_reward_rule_program_order_type UNIQUE (program_id, order_type),
    CONSTRAINT fk_reward_rule_program FOREIGN KEY (program_id) REFERENCES reward_program (id),
    CONSTRAINT ck_reward_rule_earn_rate CHECK (earn_rate >= 0),
    CONSTRAINT ck_reward_rule_refund_mode CHECK (
        refund_reversal_mode IN ('PROPORTIONAL', 'FULL', 'NONE')),
    -- A TIME based rule is the fallback when no business event exists (section 16), and it is
    -- the only kind that needs a day count.
    CONSTRAINT ck_reward_rule_eligibility CHECK (
        eligibility_type <> 'TIME' OR eligibility_days IS NOT NULL)
);

-- ---------------------------------------------------------------------------------------------
-- Reward transactions (HELP.md section 33). Append-only: a reversal creates a new row and never
-- edits the original earning (sections 21, 60.5).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reward_transaction (
    id           VARCHAR(64) NOT NULL,
    customer_id  VARCHAR(64) NOT NULL,
    order_id     VARCHAR(100),
    program_id   VARCHAR(64),
    rule_id      VARCHAR(64),
    type         VARCHAR(30) NOT NULL,
    status       VARCHAR(30) NOT NULL,
    points       DECIMAL(19,4) NOT NULL,
    order_amount DECIMAL(19,4),
    currency     VARCHAR(20),
    reversal_of  VARCHAR(64),
    -- Deterministic natural key. NOT NULL by necessity on Derby (see header note); repeatable
    -- transaction kinds simply use their own id.
    dedupe_key   VARCHAR(200) NOT NULL,
    available_at TIMESTAMP,
    expires_at   TIMESTAMP,
    created_at   TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP NOT NULL,

    CONSTRAINT pk_reward_transaction PRIMARY KEY (id),
    CONSTRAINT uk_reward_transaction_dedupe UNIQUE (dedupe_key),
    CONSTRAINT fk_reward_transaction_reversal FOREIGN KEY (reversal_of)
        REFERENCES reward_transaction (id),
    CONSTRAINT fk_reward_transaction_program FOREIGN KEY (program_id)
        REFERENCES reward_program (id),
    CONSTRAINT fk_reward_transaction_rule FOREIGN KEY (rule_id) REFERENCES reward_rule (id),
    CONSTRAINT ck_reward_transaction_points CHECK (points >= 0),
    CONSTRAINT ck_reward_transaction_type CHECK (
        type IN ('EARN', 'REVERSE', 'REDEEM', 'EXPIRE', 'DEBT_SETTLEMENT')),
    CONSTRAINT ck_reward_transaction_status CHECK (
        status IN ('PENDING', 'AVAILABLE', 'VOIDED', 'REVERSED', 'REDEEMED', 'EXPIRED',
                   'COMPLETED'))
);

-- Item level reward attribution, so that a partial refund of one order line reverses only that
-- line's reward (HELP.md section 53).
CREATE TABLE reward_transaction_item (
    id                    VARCHAR(64) NOT NULL,
    reward_transaction_id VARCHAR(64) NOT NULL,
    order_item_id         VARCHAR(100) NOT NULL,
    item_amount           DECIMAL(19,4) NOT NULL,
    points                DECIMAL(19,4) NOT NULL,

    CONSTRAINT pk_reward_transaction_item PRIMARY KEY (id),
    CONSTRAINT uk_reward_transaction_item UNIQUE (reward_transaction_id, order_item_id),
    CONSTRAINT fk_reward_transaction_item_tx FOREIGN KEY (reward_transaction_id)
        REFERENCES reward_transaction (id)
);

-- ---------------------------------------------------------------------------------------------
-- Reward lots (HELP.md section 34). The unit of expiration and of FEFO consumption (section 19).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reward_lot (
    id                    VARCHAR(64) NOT NULL,
    customer_id           VARCHAR(64) NOT NULL,
    reward_transaction_id VARCHAR(64) NOT NULL,
    original_points       DECIMAL(19,4) NOT NULL,
    remaining_points      DECIMAL(19,4) NOT NULL,
    available_at          TIMESTAMP,
    expires_at            TIMESTAMP,
    status                VARCHAR(20) NOT NULL,
    version               BIGINT NOT NULL,
    created_at            TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP NOT NULL,

    CONSTRAINT pk_reward_lot PRIMARY KEY (id),
    CONSTRAINT fk_reward_lot_transaction FOREIGN KEY (reward_transaction_id)
        REFERENCES reward_transaction (id),
    CONSTRAINT ck_reward_lot_remaining CHECK (
        remaining_points >= 0 AND remaining_points <= original_points),
    CONSTRAINT ck_reward_lot_status CHECK (
        status IN ('PENDING', 'AVAILABLE', 'CONSUMED', 'EXPIRED', 'VOIDED'))
);

-- ---------------------------------------------------------------------------------------------
-- Quotes. Not listed in section 30, but section 43 requires validating quote expiration and
-- section 9 submits a quoteId, so a quote has to be persisted. A quote never moves a balance
-- (section 8).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE redemption_quote (
    id               VARCHAR(64) NOT NULL,
    customer_id      VARCHAR(64) NOT NULL,
    order_id         VARCHAR(100) NOT NULL,
    program_id       VARCHAR(64) NOT NULL,
    currency         VARCHAR(20) NOT NULL,
    order_amount     DECIMAL(19,4) NOT NULL,
    wallet_amount    DECIMAL(19,4) NOT NULL,
    remaining_amount DECIMAL(19,4) NOT NULL,
    points_required  DECIMAL(19,4) NOT NULL,
    points_available DECIMAL(19,4) NOT NULL,
    expires_at       TIMESTAMP NOT NULL,
    created_at       TIMESTAMP NOT NULL,

    CONSTRAINT pk_redemption_quote PRIMARY KEY (id),
    CONSTRAINT fk_redemption_quote_program FOREIGN KEY (program_id)
        REFERENCES reward_program (id),
    CONSTRAINT ck_redemption_quote_amounts CHECK (
        order_amount >= 0 AND wallet_amount >= 0 AND remaining_amount >= 0
        AND points_required >= 0)
);

-- ---------------------------------------------------------------------------------------------
-- Redemptions (HELP.md section 35). Uses the order_id + redemption_sequence key that section 35
-- itself offers as the alternative, because a RELEASED reservation must leave the order free to
-- try again. "At most one live redemption per order" is enforced in the service under the
-- balance row lock.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE redemption (
    id                  VARCHAR(64) NOT NULL,
    customer_id         VARCHAR(64) NOT NULL,
    order_id            VARCHAR(100) NOT NULL,
    redemption_sequence INTEGER NOT NULL,
    currency            VARCHAR(20) NOT NULL,
    wallet_amount       DECIMAL(19,4) NOT NULL,
    points              DECIMAL(19,4) NOT NULL,
    status              VARCHAR(30) NOT NULL,
    quote_id            VARCHAR(64),
    expires_at          TIMESTAMP,
    version             BIGINT NOT NULL,
    created_at          TIMESTAMP NOT NULL,
    completed_at        TIMESTAMP,
    released_at         TIMESTAMP,

    CONSTRAINT pk_redemption PRIMARY KEY (id),
    CONSTRAINT uk_redemption_order_sequence UNIQUE (order_id, redemption_sequence),
    CONSTRAINT fk_redemption_quote FOREIGN KEY (quote_id) REFERENCES redemption_quote (id),
    CONSTRAINT ck_redemption_status CHECK (
        status IN ('CREATED', 'RESERVED', 'COMPLETED', 'RELEASED')),
    CONSTRAINT ck_redemption_amounts CHECK (wallet_amount > 0 AND points > 0)
);

-- Exactly which lots a redemption consumed, for audit and reversal (HELP.md section 36).
CREATE TABLE redemption_item (
    id            VARCHAR(64) NOT NULL,
    redemption_id VARCHAR(64) NOT NULL,
    reward_lot_id VARCHAR(64) NOT NULL,
    points        DECIMAL(19,4) NOT NULL,

    CONSTRAINT pk_redemption_item PRIMARY KEY (id),
    CONSTRAINT uk_redemption_item UNIQUE (redemption_id, reward_lot_id),
    CONSTRAINT fk_redemption_item_redemption FOREIGN KEY (redemption_id)
        REFERENCES redemption (id),
    CONSTRAINT fk_redemption_item_lot FOREIGN KEY (reward_lot_id) REFERENCES reward_lot (id),
    CONSTRAINT ck_redemption_item_points CHECK (points > 0)
);

-- ---------------------------------------------------------------------------------------------
-- Immutable double-entry ledger (HELP.md sections 37, 38, 60).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE ledger_transaction (
    id               VARCHAR(64) NOT NULL,
    reference_type   VARCHAR(50) NOT NULL,
    reference_id     VARCHAR(100) NOT NULL,
    transaction_type VARCHAR(50) NOT NULL,
    created_at       TIMESTAMP NOT NULL,

    CONSTRAINT pk_ledger_transaction PRIMARY KEY (id),
    -- A duplicate commit cannot produce a second ledger posting (sections 40, 60.8).
    CONSTRAINT uk_ledger_transaction_reference UNIQUE (
        reference_type, reference_id, transaction_type)
);

CREATE TABLE ledger_entry (
    id                    VARCHAR(64) NOT NULL,
    ledger_transaction_id VARCHAR(64) NOT NULL,
    wallet_account_id     VARCHAR(64) NOT NULL,
    asset_id              VARCHAR(64) NOT NULL,
    direction             VARCHAR(10) NOT NULL,
    amount                DECIMAL(19,4) NOT NULL,
    created_at            TIMESTAMP NOT NULL,

    CONSTRAINT pk_ledger_entry PRIMARY KEY (id),
    CONSTRAINT fk_ledger_entry_transaction FOREIGN KEY (ledger_transaction_id)
        REFERENCES ledger_transaction (id),
    CONSTRAINT fk_ledger_entry_account FOREIGN KEY (wallet_account_id)
        REFERENCES wallet_account (id),
    CONSTRAINT fk_ledger_entry_asset FOREIGN KEY (asset_id) REFERENCES asset (id),
    CONSTRAINT ck_ledger_entry_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entry_amount CHECK (amount > 0)
);

-- ---------------------------------------------------------------------------------------------
-- Idempotency (HELP.md section 39). UNIQUE(idempotency_key) is mandatory.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE idempotency_key (
    id              VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    scope           VARCHAR(100) NOT NULL,
    request_hash    VARCHAR(64) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    response_status INTEGER,
    response_body   CLOB,
    resource_id     VARCHAR(64),
    created_at      TIMESTAMP NOT NULL,
    completed_at    TIMESTAMP,

    CONSTRAINT pk_idempotency_key PRIMARY KEY (id),
    CONSTRAINT uk_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
);

-- ---------------------------------------------------------------------------------------------
-- Inbound event de-duplication (HELP.md section 26). The unique constraint is mandatory.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE processed_event (
    id            VARCHAR(64) NOT NULL,
    event_id      VARCHAR(64) NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    consumer      VARCHAR(100) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    error_message VARCHAR(2000),
    processed_at  TIMESTAMP NOT NULL,

    CONSTRAINT pk_processed_event PRIMARY KEY (id),
    CONSTRAINT uk_processed_event_event_id UNIQUE (event_id),
    CONSTRAINT ck_processed_event_status CHECK (status IN ('PROCESSED', 'SKIPPED', 'FAILED'))
);

-- ---------------------------------------------------------------------------------------------
-- Outbox (HELP.md sections 56-58). payload is a CLOB because Derby caps VARCHAR at 32672.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE outbox_event (
    id           VARCHAR(64) NOT NULL,
    event_id     VARCHAR(64) NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    exchange     VARCHAR(100) NOT NULL,
    routing_key  VARCHAR(200) NOT NULL,
    payload      CLOB NOT NULL,
    status       VARCHAR(20) NOT NULL,
    retry_count  INTEGER NOT NULL,
    last_error   VARCHAR(2000),
    created_at   TIMESTAMP NOT NULL,
    published_at TIMESTAMP,

    CONSTRAINT pk_outbox_event PRIMARY KEY (id),
    CONSTRAINT uk_outbox_event_id UNIQUE (event_id),
    CONSTRAINT ck_outbox_event_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_event_retry CHECK (retry_count >= 0)
);

-- ---------------------------------------------------------------------------------------------
-- Indexes for the hot paths.
--
-- Only indexes that Derby does not already provide. Derby backs every foreign key and unique
-- constraint with an index of its own, so covering reward_lot(reward_transaction_id),
-- ledger_entry(wallet_account_id), ledger_entry(ledger_transaction_id),
-- wallet_account(customer_id) or redemption(order_id) again would just be a duplicate.
-- ---------------------------------------------------------------------------------------------

-- FEFO lot selection: earliest expiring first for one customer (HELP.md section 19).
CREATE INDEX ix_reward_lot_fefo ON reward_lot (customer_id, status, expires_at);

-- The PENDING -> AVAILABLE sweep for time-based eligibility (HELP.md section 16).
CREATE INDEX ix_reward_lot_pending ON reward_lot (status, available_at);

CREATE INDEX ix_reward_transaction_customer ON reward_transaction (customer_id, created_at);

-- The reservation expiry sweep (HELP.md sections 13, 50).
CREATE INDEX ix_redemption_expiry ON redemption (status, expires_at);
CREATE INDEX ix_redemption_customer ON redemption (customer_id, created_at);

-- The outbox drain (HELP.md section 58).
CREATE INDEX ix_outbox_event_unpublished ON outbox_event (status, created_at);
