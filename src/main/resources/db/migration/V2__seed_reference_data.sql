-- Reference data: assets, ledger liability accounts, and the default reward program/rules.
--
-- Ids are readable rather than random UUIDs so that seeded rows can be referred to directly from
-- tests and from support queries. Customer-owned rows always use generated UUIDs.

-- Monetary assets (HELP.md section 2). POINT is the reward asset and is deliberately a separate
-- asset so that 100 SAR can never be confused with 100 POINTS.
INSERT INTO asset (id, code, type, decimal_scale, name, active, created_at) VALUES
    ('asset-sar',   'SAR',   'MONETARY', 2, 'Saudi Riyal',   TRUE, CURRENT_TIMESTAMP),
    ('asset-usd',   'USD',   'MONETARY', 2, 'US Dollar',     TRUE, CURRENT_TIMESTAMP),
    ('asset-eur',   'EUR',   'MONETARY', 2, 'Euro',          TRUE, CURRENT_TIMESTAMP),
    ('asset-point', 'POINT', 'REWARD',   0, 'Reward Points', TRUE, CURRENT_TIMESTAMP);

-- Ledger liability accounts, held under the reserved customer id 'SYSTEM'. Every reward that is
-- credited to a customer is debited from the matching liability account, which is what keeps
-- SUM(DEBIT) = SUM(CREDIT) per asset (HELP.md section 38).
--
-- Liability accounts intentionally have no wallet_balance row: their position is the ledger
-- itself, and a liability position is naturally negative, which wallet_balance forbids.
INSERT INTO wallet_account (id, customer_id, asset_id, account_type, status, created_at) VALUES
    ('liability-point', 'SYSTEM', 'asset-point', 'LIABILITY', 'ACTIVE', CURRENT_TIMESTAMP),
    ('liability-sar',   'SYSTEM', 'asset-sar',   'LIABILITY', 'ACTIVE', CURRENT_TIMESTAMP),
    ('liability-usd',   'SYSTEM', 'asset-usd',   'LIABILITY', 'ACTIVE', CURRENT_TIMESTAMP),
    ('liability-eur',   'SYSTEM', 'asset-eur',   'LIABILITY', 'ACTIVE', CURRENT_TIMESTAMP);

-- The default program. points_per_currency_unit = 100 reproduces HELP.md's worked examples, in
-- which 3,000 points settle 30.00 SAR of an order.
INSERT INTO reward_program (
    id, code, name, reward_asset_id, points_per_currency_unit, max_redemption_percent, active,
    created_at)
VALUES (
    'program-default', 'DEFAULT', 'Lumi Rewards', 'asset-point', 100.0000, 100.00, TRUE,
    CURRENT_TIMESTAMP);

-- Rules per order type (HELP.md sections 16, 17). Eligibility is driven by a business event
-- wherever one exists; DEFAULT falls back to a time delay because no such event is available.
INSERT INTO reward_rule (
    id, program_id, order_type, earn_rate, eligibility_type, eligibility_days,
    minimum_order_amount, maximum_points, expiration_days, refund_reversal_mode, active,
    created_at)
VALUES
    ('rule-retail', 'program-default', 'RETAIL', 0.010000, 'ORDER_DELIVERED', NULL,
     0.0000, 100000.0000, 365, 'PROPORTIONAL', TRUE, CURRENT_TIMESTAMP),
    ('rule-hotel', 'program-default', 'HOTEL', 0.010000, 'STAY_COMPLETED', NULL,
     0.0000, 100000.0000, 365, 'PROPORTIONAL', TRUE, CURRENT_TIMESTAMP),
    ('rule-flight', 'program-default', 'FLIGHT', 0.005000, 'TRAVEL_COMPLETED', NULL,
     100.0000, 100000.0000, 365, 'PROPORTIONAL', TRUE, CURRENT_TIMESTAMP),
    ('rule-service', 'program-default', 'SERVICE', 0.020000, 'SERVICE_COMPLETED', NULL,
     0.0000, 50000.0000, 180, 'PROPORTIONAL', TRUE, CURRENT_TIMESTAMP),
    ('rule-default', 'program-default', 'DEFAULT', 0.010000, 'TIME', 7,
     0.0000, 100000.0000, 365, 'PROPORTIONAL', TRUE, CURRENT_TIMESTAMP);
