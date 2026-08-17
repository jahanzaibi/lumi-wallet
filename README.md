# Multi-Currency Wallet and Rewards Service

## 1. Purpose

The Wallet Service provides:

- Customer wallet accounts.
- Multiple currencies/assets.
- Reward point earning.
- Pending reward points.
- Reward point availability rules.
- Reward point redemption.
- Full wallet/reward payment.
- Partial wallet/reward payment.
- Reward reservation during payment.
- Reward reversal after cancellation/refund.
- Reward expiration.
- Reward debt when already-redeemed rewards are reversed.
- Immutable double-entry ledger.
- RabbitMQ event integration.
- Idempotent APIs and event consumers.

The Wallet Service does **not** own:

- Credit/debit card processing.
- MADA processing.
- Bank payment processing.
- Payment authorization.
- Payment capture.
- Payment refund of external payment methods.
- Merchant payment settlement.

Those responsibilities remain in the Payment Service.

---

# 2. Core design principle

The Wallet Service has two distinct financial concepts:

### Monetary wallet

Examples:

```text
SAR
USD
EUR
```

### Reward asset

Example:

```text
POINT
```

They must never be mixed in the same ledger balance.

For example:

```text
100 SAR != 100 POINTS
```

A customer may have:

```text
SAR wallet       500.00
USD wallet       100.00
Reward points    10,000
```

Each is an independent account/asset.

---

# 3. Service responsibilities

## Wallet Service owns

```text
Customer wallet
Reward points
Reward rules
Reward eligibility
Reward expiration
Reward redemption
Reward reservations
Reward reversals
Reward ledger
Idempotency
RabbitMQ event consumption
RabbitMQ event publishing
```

## Payment Service owns

```text
Card payment
MADA
Apple Pay
Bank payment
Payment authorization
Payment capture
Payment failure
External payment refund
Payment settlement
```

## Order Service owns

```text
Order lifecycle
Order creation
Order confirmation
Order cancellation
Order delivery
Order completion
Order refund eligibility
```

---

# 4. Payment architecture

The Wallet Service should not call the external payment provider.

For a partial payment:

```text
Order = 100 SAR

Wallet reward = 30 SAR
External payment = 70 SAR
```

The flow is:

```text
Client
  |
  | payment request
  v
Order/Checkout Service
  |
  | wallet quote
  v
Wallet Service
  |
  | 30 SAR wallet contribution
  v
Order/Checkout Service
  |
  | 70 SAR
  v
Payment Service
```

The Wallet Service only reserves the reward value.

The Payment Service processes the remaining amount.

---

# 5. Full payment

Example:

```text
Order = 100 SAR
Wallet reward = 100 SAR
External payment = 0 SAR
```

Flow:

```text
Checkout
   |
   v
Wallet quote
   |
   v
100 SAR reward available
   |
   v
Wallet redemption reservation
   |
   v
Order confirmed
   |
   v
Wallet redemption committed
```

No external payment is required.

---

# 6. Partial payment

Example:

```text
Order = 100 SAR
Wallet contribution = 30 SAR
Remaining payment = 70 SAR
```

Flow:

```text
1. Checkout requests wallet quote

2. Wallet validates:
   - customer
   - points
   - reward rules
   - order amount

3. Wallet returns:

   walletContribution = 30 SAR
   remainingAmount    = 70 SAR
   points             = 3,000

4. Checkout creates wallet redemption reservation.

5. Payment Service processes 70 SAR.

6. If payment succeeds:
       Wallet redemption is committed.

7. If payment fails:
       Wallet reservation is released.
```

---

# 7. Wallet quote API

## Endpoint

```http
POST /api/v1/wallet/redemptions/quote
```

## Request

```json
{
  "customerId": "CUS-100",
  "orderId": "ORD-100",
  "currency": "SAR",
  "orderAmount": 100.00,
  "requestedWalletAmount": 30.00
}
```

`requestedWalletAmount` is optional.

If omitted, the wallet returns the maximum redeemable amount.

## Response

```json
{
  "quoteId": "QUOTE-100",
  "customerId": "CUS-100",
  "orderId": "ORD-100",
  "currency": "SAR",
  "orderAmount": 100.00,
  "walletAmount": 30.00,
  "remainingAmount": 70.00,
  "pointsRequired": 3000,
  "pointsAvailable": 10000,
  "expiresAt": "2026-08-17T12:00:00Z"
}
```

The quote is not a redemption.

It is only a calculation.

---

# 8. Why a quote is useful

Do not immediately deduct points when calculating checkout.

Otherwise:

```text
User opens checkout
        |
        v
Wallet deducts 3000 points
        |
        v
User closes browser
```

The customer would lose points.

Therefore:

```text
QUOTE
```

does not modify the balance.

Only:

```text
REDEMPTION RESERVATION
```

changes the available/locked balance.

---

# 9. Redemption API

## Endpoint

```http
POST /api/v1/wallet/redemptions
```

Header:

```http
Idempotency-Key: RED-ORD-100
```

Request:

```json
{
  "quoteId": "QUOTE-100",
  "customerId": "CUS-100",
  "orderId": "ORD-100",
  "currency": "SAR",
  "walletAmount": 30.00,
  "points": 3000
}
```

Response:

```json
{
  "redemptionId": "RED-100",
  "status": "RESERVED",
  "walletAmount": 30.00,
  "points": 3000,
  "currency": "SAR",
  "expiresAt": "2026-08-17T12:05:00Z"
}
```

The points move:

```text
AVAILABLE
    |
    v
LOCKED
```

They are not yet permanently consumed.

---

# 10. Commit redemption

After the external payment succeeds, Checkout/Order Service calls:

```http
POST /api/v1/wallet/redemptions/{redemptionId}/commit
```

Header:

```http
Idempotency-Key: COMMIT-RED-100
```

Response:

```json
{
  "redemptionId": "RED-100",
  "status": "COMPLETED",
  "points": 3000,
  "walletAmount": 30.00
}
```

The state changes:

```text
LOCKED
   |
   v
REDEEMED
```

---

# 11. Release redemption

If external payment fails:

```http
POST /api/v1/wallet/redemptions/{redemptionId}/release
```

The points move:

```text
LOCKED
   |
   v
AVAILABLE
```

Example response:

```json
{
  "redemptionId": "RED-100",
  "status": "RELEASED",
  "points": 3000
}
```

This API must also be idempotent.

Calling release twice must not release the points twice.

---

# 12. Redemption state machine

```text
                 ┌─────────────┐
                 │   CREATED   │
                 └──────┬──────┘
                        |
                        v
                 ┌─────────────┐
                 │   RESERVED  │
                 └──────┬──────┘
                        |
             ┌──────────┴──────────┐
             |                     |
             v                     v
         COMPLETED              RELEASED
```

Allowed transitions:

```text
CREATED   -> RESERVED
RESERVED  -> COMPLETED
RESERVED  -> RELEASED
```

Invalid:

```text
COMPLETED -> RELEASED
COMPLETED -> COMPLETED
RELEASED  -> COMPLETED
RELEASED  -> RELEASED
```

---

# 13. Redemption expiration

Reservations should have a short TTL.

Recommended initial value:

```text
5 minutes
```

Example:

```text
12:00:00 reservation created
12:05:00 reservation expires
```

If Payment Service never responds, a scheduled process releases the points.

The expiration process must use the same state transition rules.

It must never blindly add points.

---

# 14. Reward earning

Reward earning is independent from redemption.

Example:

```text
Order = 500 SAR
Reward = 500 points
```

Create:

```text
reward_transaction
```

with:

```text
type   = EARN
status = PENDING
points = 500
order  = ORD-100
```

The customer cannot use these points yet.

---

# 15. Reward lifecycle

```text
PAYMENT_SUCCESS
      |
      v
   PENDING
      |
      +--------------------+
      |                    |
      v                    v
ELIGIBILITY_EVENT       CANCELLED
      |                    |
      v                    v
 AVAILABLE               VOIDED
      |
      |
      v
   REDEEMED
```

---

# 16. Reward eligibility

 Business events for  eligibility.

Examples:

```text
Retail:
ORDER_DELIVERED

Hotel:
STAY_COMPLETED

Flight:
TRAVEL_COMPLETED

Service:
SERVICE_COMPLETED
```

A time delay may be used when an event is unavailable.

Example:

```text
eligibilityType = TIME
eligibilityDays = 7
```

---

# 17. Reward rule

Table:

```text
reward_rule
------------------------------
id
program_id
order_type
earn_rate
eligibility_type
eligibility_days
minimum_order_amount
maximum_points
expiration_days
active
```

Example:

```text
order_type           = HOTEL
earn_rate            = 1%
eligibility_type     = STAY_COMPLETED
expiration_days      = 365
```

---

# 18. Reward lot

Every earning operation creates a reward lot.

```text
reward_lot
------------------------------
id
customer_id
reward_transaction_id
original_points
remaining_points
available_at
expires_at
status
created_at
```

Example:

```text
LOT-1
original = 1000
remaining = 1000
expires = 2027-08-17
```

This allows expiration and FIFO consumption.

---

# 19. Reward consumption

If the customer has:

```text
Lot A = 1000 points, expires January
Lot B = 2000 points, expires March
Lot C = 500 points, expires June
```

and redeems:

```text
1200 points
```

consume:

```text
Lot A = 1000
Lot B = 200
```

Use earliest expiration first.

---

# 20. Reward cancellation

## Before points become available

Example:

```text
500 PENDING
```

Order cancelled:

```text
PENDING
   |
   v
VOIDED
```

No customer balance change is required.

---

# 21. Cancellation after points became available

Example:

```text
500 AVAILABLE
```

Order gets cancelled.

Create a separate:

```text
REWARD_REVERSAL
```

Do not modify the original earning transaction, The ledger remains immutable.

---

# 22. If reversed points were already redeemed

Example:

```text
Earned = 500
Redeemed = 400
Remaining = 100
```

Order is cancelled.

Do not allow the ledger to become inconsistent.

Create:

```text
reward_debt = 400
```

The customer has:

```text
available = 0
rewardDebt = 400
```

Future rewards first pay the debt.

Example:

```text
New reward = 700

700 - 400 debt = 300 available
```

Result:

```text
rewardDebt = 0
available = 300
```


---

# 23. RabbitMQ

RabbitMQ is used for asynchronous business events.

 Exchanges:

```text
wallet.events
order.events
payment.events
```

Prefer topic exchanges.

Example:

```text
order.events
```

Routing keys:

```text
order.created
order.confirmed
order.delivered
order.cancelled
order.completed
order.refunded
```

Payment:

```text
payment.succeeded
payment.failed
payment.refunded
```

Wallet:

```text
wallet.reward.pending
wallet.reward.available
wallet.reward.reversed
wallet.redemption.reserved
wallet.redemption.completed
wallet.redemption.released
```

---

# 24. RabbitMQ queues

Wallet Service could have:

```text
wallet.order-events
wallet.payment-events
wallet.command-events
```

Bindings:

```text
wallet.order-events
    order.confirmed
    order.delivered
    order.cancelled
    order.completed
    order.refunded
```

and:

```text
wallet.payment-events
    payment.succeeded
    payment.failed
    payment.refunded
```

---

# 25. Event envelope

Every RabbitMQ message should use a standard envelope.

```json
{
  "eventId": "EVT-123",
  "eventType": "ORDER_DELIVERED",
  "eventVersion": 1,
  "occurredAt": "2026-08-17T10:00:00Z",
  "source": "order-service",
  "correlationId": "CORR-123",
  "payload": {
    "orderId": "ORD-100",
    "customerId": "CUS-100"
  }
}
```

---

# 26. Event idempotency

RabbitMQ provides delivery guarantees, but your application should assume duplicate delivery.

For example:

```text
EVT-123
EVT-123
```

may arrive twice.

Create:

```text
processed_event
------------------------------
event_id UNIQUE
event_type
consumer
processed_at
status
```

Before processing:

```text
if event already processed:
    ignore
```

The unique constraint is mandatory.

---

# 27. RabbitMQ consumer transaction

The consumer should conceptually do:

```text
BEGIN DB TRANSACTION

insert processed_event

if duplicate:
    return

process reward

update ledger

COMMIT

ACK RabbitMQ message
```

The ACK should happen after the database transaction succeeds.

If processing fails:

```text
ROLLBACK
NACK
```

and RabbitMQ can redeliver.

---

# 28. Dead-letter queue

Configure DLQs.

For example:

```text
wallet.order-events
        |
        v
wallet.order-events.dlq
```

After a configurable number of retries:

```text
5 attempts
```

move the message to DLQ.

Do not endlessly retry malformed messages.

---

# 29. RabbitMQ retry

Recommended:

```text
wallet.order-events
       |
       v
retry queue 1
   5 seconds
       |
       v
retry queue 2
   30 seconds
       |
       v
retry queue 3
   5 minutes
       |
       v
DLQ
```

Use exponential/backoff behavior.

---

# 30. Database schema

Core tables:

```text
wallet_account
wallet_balance

asset

reward_program
reward_rule
reward_transaction
reward_lot

redemption
redemption_item

ledger_transaction
ledger_entry

idempotency_key
processed_event
```

---

# 31. wallet_account

```sql
CREATE TABLE wallet_account (
    id              VARCHAR(36) NOT NULL,
    customer_id     VARCHAR(36) NOT NULL,
    asset_id        VARCHAR(36) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    created_at      TIMESTAMP NOT NULL,

    CONSTRAINT pk_wallet_account PRIMARY KEY (id),

    CONSTRAINT uk_wallet_customer_asset
        UNIQUE (customer_id, asset_id)
);
```

A customer can therefore have:

```text
CUS-1 + SAR
CUS-1 + USD
CUS-1 + EUR
```

but only one wallet account per asset.

---

# 32. wallet_balance

```sql
CREATE TABLE wallet_balance (
    wallet_account_id   VARCHAR(36) NOT NULL,
    available_amount    DECIMAL(19,4) NOT NULL,
    locked_amount       DECIMAL(19,4) NOT NULL,
    debt_amount         DECIMAL(19,4) NOT NULL,
    version              BIGINT NOT NULL,

    CONSTRAINT pk_wallet_balance
        PRIMARY KEY (wallet_account_id),

    CONSTRAINT fk_wallet_balance_account
        FOREIGN KEY (wallet_account_id)
        REFERENCES wallet_account(id)
);
```

For reward points:

```text
available_amount
locked_amount
debt_amount
```

---

# 33. reward_transaction

```sql
CREATE TABLE reward_transaction (
    id                  VARCHAR(36) NOT NULL,
    customer_id         VARCHAR(36) NOT NULL,
    order_id            VARCHAR(100),
    type                VARCHAR(30) NOT NULL,
    status              VARCHAR(30) NOT NULL,
    points              DECIMAL(19,4) NOT NULL,

    reversal_of         VARCHAR(36),

    available_at        TIMESTAMP,
    expires_at          TIMESTAMP,

    created_at          TIMESTAMP NOT NULL,

    CONSTRAINT pk_reward_transaction
        PRIMARY KEY (id)
);
```

---

# 34. reward_lot

```sql
CREATE TABLE reward_lot (
    id                      VARCHAR(36) NOT NULL,
    customer_id             VARCHAR(36) NOT NULL,
    reward_transaction_id   VARCHAR(36) NOT NULL,

    original_points         DECIMAL(19,4) NOT NULL,
    remaining_points        DECIMAL(19,4) NOT NULL,

    available_at            TIMESTAMP,
    expires_at              TIMESTAMP,

    status                  VARCHAR(20) NOT NULL,

    created_at              TIMESTAMP NOT NULL,

    CONSTRAINT pk_reward_lot
        PRIMARY KEY (id)
);
```

---

# 35. redemption

```sql
CREATE TABLE redemption (
    id                  VARCHAR(36) NOT NULL,
    customer_id        VARCHAR(36) NOT NULL,
    order_id            VARCHAR(100) NOT NULL,

    currency            VARCHAR(20) NOT NULL,
    wallet_amount       DECIMAL(19,4) NOT NULL,
    points              DECIMAL(19,4) NOT NULL,

    status              VARCHAR(30) NOT NULL,

    quote_id            VARCHAR(36),
    expires_at          TIMESTAMP,

    created_at          TIMESTAMP NOT NULL,
    completed_at        TIMESTAMP,

    CONSTRAINT pk_redemption
        PRIMARY KEY (id),

    CONSTRAINT uk_redemption_order
        UNIQUE (order_id)
);
```

If one order can have multiple independent redemption attempts, use:

```text
order_id + redemption_sequence
```

instead of a simple unique order ID.

---

# 36. redemption_item

This records exactly which reward lots were consumed.

```sql
CREATE TABLE redemption_item (
    id              VARCHAR(36) NOT NULL,
    redemption_id   VARCHAR(36) NOT NULL,
    reward_lot_id   VARCHAR(36) NOT NULL,
    points          DECIMAL(19,4) NOT NULL,

    CONSTRAINT pk_redemption_item
        PRIMARY KEY (id),

    CONSTRAINT fk_redemption_item_redemption
        FOREIGN KEY (redemption_id)
        REFERENCES redemption(id),

    CONSTRAINT fk_redemption_item_lot
        FOREIGN KEY (reward_lot_id)
        REFERENCES reward_lot(id)
);
```

This is important for audit and reversal.

---

# 37. Ledger

```sql
CREATE TABLE ledger_transaction (
    id                  VARCHAR(36) NOT NULL,
    reference_type      VARCHAR(50) NOT NULL,
    reference_id        VARCHAR(100) NOT NULL,
    transaction_type    VARCHAR(50) NOT NULL,
    created_at          TIMESTAMP NOT NULL,

    CONSTRAINT pk_ledger_transaction
        PRIMARY KEY (id)
);
```

```sql
CREATE TABLE ledger_entry (
    id                      VARCHAR(36) NOT NULL,
    ledger_transaction_id   VARCHAR(36) NOT NULL,
    wallet_account_id       VARCHAR(36) NOT NULL,

    direction               VARCHAR(10) NOT NULL,
    amount                  DECIMAL(19,4) NOT NULL,

    created_at              TIMESTAMP NOT NULL,

    CONSTRAINT pk_ledger_entry
        PRIMARY KEY (id)
);
```

For reward points, the wallet account's asset is `POINTS`.

---

# 38. Double-entry rule

Every ledger transaction must satisfy:

```text
SUM(DEBIT) == SUM(CREDIT)
```

for each asset.

For example:

```text
Reward earning

Customer Reward Account
    CREDIT 500 POINTS

Reward Liability Account
    DEBIT 500 POINTS
```

Balanced:

```text
Debit  = 500
Credit = 500
```

For redemption:

```text
Customer Reward Account
    DEBIT 3000 POINTS

Reward Liability Account
    CREDIT 3000 POINTS
```

---

# 39. Idempotency

All wallet commands that can modify state require:

```http
Idempotency-Key
```

Examples:

```text
POST /redemptions
POST /redemptions/{id}/commit
POST /redemptions/{id}/release
```

The database must have:

```sql
UNIQUE(idempotency_key)
```

Same key + same request:

```text
return original result
```

Same key + different request:

```text
409 IDEMPOTENCY_CONFLICT
```

---

# 40. Important duplicate scenarios

## Duplicate redemption request

```text
RED-123
```

received twice.

Result:

```text
Only one redemption.
```

---

## Duplicate commit

```text
COMMIT-123
```

received twice.

Result:

```text
First → COMPLETED
Second → return COMPLETED
```

No second ledger transaction.

---

## Duplicate release

```text
RELEASE-123
```

received twice.

Result:

```text
First → RELEASED
Second → return RELEASED
```

No double credit.

---

## Duplicate RabbitMQ event

```text
EVENT-123
EVENT-123
```

Result:

```text
First → process
Second → ignore
```

---

# 41. Concurrent redemption

Customer has:

```text
10,000 points
```

Two simultaneous requests:

```text
Request A → redeem 8,000
Request B → redeem 8,000
```

Both cannot succeed.

Lock the customer's reward balance:

```sql
SELECT ...
FROM wallet_balance
WHERE wallet_account_id = ?
FOR UPDATE;
```

Then:

```text
Request A:
available = 10,000
lock 8,000
available = 2,000

Request B:
waits

Request A commits

Request B:
available = 2,000
requested = 8,000

REJECT
```

---

# 42. Use pessimistic locking for reward redemption

For high-value financial/reward operations:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
```


---

# 43. Redemption quote must be validated

A quote is informational and can become stale.

Example:

```text
Quote says:
10,000 points available
```

Then another device redeems:

```text
8,000 points
```

The original quote still says:

```text
10,000
```

When the actual redemption is submitted, Wallet must **revalidate everything**.

Never trust the quote.

Validate:

```text
customer
order
currency
points
wallet amount
current available balance
reward rules
quote expiration
```

---

# 44. Validate FE points supplied by the client

The client may send:

```json
{
  "points": 3000
}
```

but Wallet must calculate/validate the actual amount.

The authoritative data is:

```text
Wallet database
Reward rules
Quote
```

not:

```text
Frontend request
```

---

# 45. API error model

Use a consistent response:

```json
{
  "code": "INSUFFICIENT_REWARD_BALANCE",
  "message": "Insufficient reward points",
  "correlationId": "CORR-123"
}
```

Recommended error codes:

```text
WALLET_NOT_FOUND
WALLET_BLOCKED
INSUFFICIENT_REWARD_BALANCE
REDEMPTION_NOT_FOUND
REDEMPTION_EXPIRED
INVALID_REDEMPTION_STATE
QUOTE_EXPIRED
QUOTE_MISMATCH
IDEMPOTENCY_CONFLICT
DUPLICATE_ORDER_REDEMPTION
ORDER_NOT_ELIGIBLE
REWARD_NOT_AVAILABLE
INVALID_CURRENCY
INVALID_AMOUNT
```

---

# 46. APIs

The initial public API should be:

```text
POST /api/v1/wallet/redemptions/quote

POST /api/v1/wallet/redemptions

GET  /api/v1/wallet/redemptions/{redemptionId}

POST /api/v1/wallet/redemptions/{redemptionId}/commit

POST /api/v1/wallet/redemptions/{redemptionId}/release

GET  /api/v1/wallet/balance

GET  /api/v1/wallet/rewards

GET  /api/v1/wallet/rewards/history
```

Internal/admin APIs can be added separately.

---

# 47. Quote API example

Request:

```json
{
  "customerId": "CUS-100",
  "orderId": "ORD-100",
  "currency": "SAR",
  "orderAmount": 100.00
}
```

Response:

```json
{
  "quoteId": "Q-100",
  "walletAmount": 30.00,
  "points": 3000,
  "remainingAmount": 70.00,
  "expiresAt": "2026-08-17T12:00:00Z"
}
```

---

# 48. Recommended checkout sequence

```text
Customer
   |
   | Buy 100 SAR
   v
Checkout
   |
   | wallet quote
   v
Wallet
   |
   | 30 SAR / 3000 points
   v
Checkout
   |
   | reserve redemption
   v
Wallet
   |
   | RESERVED
   v
Checkout
   |
   | pay 70 SAR
   v
Payment Service
   |
   | SUCCESS
   v
Checkout
   |
   | commit redemption
   v
Wallet
   |
   | COMPLETED
   v
Order confirmed
```

---

# 49. If Payment Service fails

```text
Wallet
   |
   | RESERVED 3000
   |
Payment Service
   |
   | FAILED
   |
   v
Checkout
   |
   | release
   v
Wallet
   |
   | AVAILABLE 3000
```

---

# 50. If Checkout crashes

This is why the redemption has an expiration.

Example:

```text
12:00 reservation
12:05 expiration
```

If no commit/release arrives:

```text
Scheduler
    |
    v
find RESERVED where expires_at < now
    |
    v
RELEASE
```

The release must be idempotent.

---

# 51. Order cancellation

Order Service publishes:

```json
{
  "eventId": "EVT-500",
  "eventType": "ORDER_CANCELLED",
  "eventVersion": 1,
  "source": "order-service",
  "payload": {
    "orderId": "ORD-100",
    "customerId": "CUS-100",
    "reason": "CUSTOMER_CANCELLED"
  }
}
```

Wallet looks up:

```text
ORD-100
```

Then:

### Pending reward

```text
PENDING → VOIDED
```

### Available reward

```text
AVAILABLE → REVERSED
```

### Reserved redemption

Depending on the order state:

```text
RESERVED → RELEASED
```

### Completed redemption

Do not simply delete it.

Create:

```text
REWARD_REVERSE
```

---

# 52. Order refund

A refund should be treated separately from cancellation.

For example:

```text
Order = 1000 SAR
Reward earned = 100 points
Refund = 400 SAR
```

The reward reversal should be calculated according to the reward rule.

It may be:

```text
100 points × 40%
= 40 points reversal
```

Do not assume every refund means 100% reward reversal.

The exact rule should belong to `reward_rule`.

---

# 53. Partial refund

This needs to be explicitly supported.

Example:

```text
Order = 1000 SAR
Reward = 100 points

Refund = 300 SAR
```

Potential reversal:

```text
30 points
```

if the reward rate is proportional.

However, if the original order contained:

```text
Item A = 300
Item B = 700
```

and only Item A is refunded, item-level reward attribution is better.

Therefore, if your business requires accurate partial refunds, store:

```text
reward_transaction_item
```

linked to:

```text
orderItemId
```

rather than only `orderId`.

---

# 54. Reward event processing

RabbitMQ consumer:

```java
@RabbitListener(
    queues = "wallet.order-events"
)
public void handleOrderEvent(
        WalletEvent event
) {

    rewardEventService.process(event);
}
```

Service:

```text
BEGIN

check processed_event

if already processed:
    ACK

insert processed_event

process event

create/update reward transaction

create ledger

COMMIT

ACK
```

---

# 55. RabbitMQ event publishing

When Wallet creates a reward:

```text
wallet.reward.pending
```

When it becomes available:

```text
wallet.reward.available
```

When redeemed:

```text
wallet.redemption.completed
```

When released:

```text
wallet.redemption.released
```

When reversed:

```text
wallet.reward.reversed
```

These events allow other services to display status without calling Wallet repeatedly.

---

# 56. Outbox pattern

For production, I strongly recommend an `outbox_event` table.

Do not do:

```text
DB COMMIT
    |
    v
rabbitTemplate.convertAndSend()
```

because the DB can commit and RabbitMQ publishing can fail.

Instead:

```text
BEGIN
    update wallet
    create ledger
    insert outbox event
COMMIT
```

Then an asynchronous publisher reads:

```text
outbox_event
```

and publishes to RabbitMQ.

This guarantees that your database state and outgoing event are coordinated.

---

# 57. Outbox table

```sql
CREATE TABLE outbox_event (
    id              VARCHAR(36) NOT NULL,
    event_id        VARCHAR(36) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    routing_key     VARCHAR(200) NOT NULL,
    payload         VARCHAR(50000) NOT NULL,

    status          VARCHAR(20) NOT NULL,
    retry_count     INTEGER NOT NULL,

    created_at      TIMESTAMP NOT NULL,
    published_at    TIMESTAMP,

    CONSTRAINT pk_outbox_event PRIMARY KEY (id),

    CONSTRAINT uk_outbox_event_id
        UNIQUE (event_id)
);
```

---

# 58. Outbox flow

```text
Wallet Transaction
       |
       +---- Ledger
       |
       +---- Reward
       |
       +---- Outbox Event
                  |
                  v
             DB COMMIT
                  |
                  v
          Outbox Publisher
                  |
                  v
              RabbitMQ
```

This is much safer than publishing directly from the service transaction.

---



# 60. Ledger invariants

These must never be violated:

```text
1. Every completed reward transaction has ledger entries.

2. Total debit == total credit.

3. A ledger entry is immutable.

4. A completed transaction cannot be deleted.

5. Reversal creates a new transaction.

6. Available points can never be spent twice.

7. Locked points cannot be used by another redemption.

8. A redemption can only be committed once.

9. A redemption can only be released once.

10. A completed redemption cannot be released.

11. A cancelled pending reward cannot become available.

12. A duplicate event cannot create another reward.

13. A duplicate API request cannot create another redemption.

14. Same idempotency key with different request is rejected.

15. A quote cannot be trusted after expiration.

16. Actual redemption revalidates the current balance.

17. Reward balance updates and ledger entries occur in one DB transaction.

18. Outgoing RabbitMQ events are produced through the outbox.

19. Incoming RabbitMQ events are idempotent.

20. External payment status is owned by Payment Service.
```

