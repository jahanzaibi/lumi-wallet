package com.lumi.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.lumi.wallet.api.RedemptionRequest;
import com.lumi.wallet.api.RedemptionResponse;
import com.lumi.wallet.common.ErrorCode;
import com.lumi.wallet.common.WalletException;
import com.lumi.wallet.idempotency.IdempotencyService;
import com.lumi.wallet.redemption.RedemptionService;
import com.lumi.wallet.redemption.RedemptionStatus;

/**
 * Idempotency-Key semantics (HELP.md sections 39, 40).
 *
 * <pre>
 * same key + same request      -> return the original result
 * same key + different request -> 409 IDEMPOTENCY_CONFLICT
 * </pre>
 */
class IdempotencyTest extends AbstractWalletTest {

    private static final String SCOPE = "test.command";

    @Autowired
    private IdempotencyService idempotency;

    @Test
    @DisplayName("the same key with the same request runs the command once")
    void sameKeySameRequestRunsOnce() {
        String key = "IDK-" + UUID.randomUUID();
        AtomicInteger invocations = new AtomicInteger();
        Payload request = new Payload("ORD-1", new BigDecimal("30.00"));

        Result first = idempotency.execute(key, SCOPE, request, Result.class,
                () -> new Result("RES-" + invocations.incrementAndGet()));
        Result second = idempotency.execute(key, SCOPE, request, Result.class,
                () -> new Result("RES-" + invocations.incrementAndGet()));

        assertThat(invocations.get()).as("the command runs exactly once").isEqualTo(1);
        assertThat(second.id()).as("the second call replays the first result")
                .isEqualTo(first.id());
    }

    @Test
    @DisplayName("the same key with a different request is a conflict")
    void sameKeyDifferentRequestConflicts() {
        String key = "IDK-" + UUID.randomUUID();
        idempotency.execute(key, SCOPE, new Payload("ORD-1", new BigDecimal("30.00")), Result.class,
                () -> new Result("RES-1"));

        assertThatThrownBy(() -> idempotency.execute(key, SCOPE,
                new Payload("ORD-1", new BigDecimal("50.00")), Result.class,
                () -> new Result("RES-2")))
                .isInstanceOf(WalletException.class)
                .extracting(e -> ((WalletException) e).code())
                .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    /** One key cannot be reused for a different operation, even with an identical body. */
    @Test
    void sameKeyDifferentScopeConflicts() {
        String key = "IDK-" + UUID.randomUUID();
        Payload request = new Payload("ORD-1", new BigDecimal("30.00"));
        idempotency.execute(key, "redemption.commit", request, Result.class,
                () -> new Result("RES-1"));

        assertThatThrownBy(() -> idempotency.execute(key, "redemption.release", request,
                Result.class, () -> new Result("RES-2")))
                .isInstanceOf(WalletException.class)
                .extracting(e -> ((WalletException) e).code())
                .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    /**
     * A failed command releases its key. Refusing to ever retry would leave a customer permanently
     * stuck behind a transient error, which is worse than allowing a genuine second attempt.
     */
    @Test
    void aFailedCommandCanBeRetried() {
        String key = "IDK-" + UUID.randomUUID();
        Payload request = new Payload("ORD-1", new BigDecimal("30.00"));

        assertThatThrownBy(() -> idempotency.execute(key, SCOPE, request, Result.class,
                () -> {
                    throw new IllegalStateException("transient");
                })).isInstanceOf(IllegalStateException.class);

        Result retried = idempotency.execute(key, SCOPE, request, Result.class,
                () -> new Result("RES-retry"));
        assertThat(retried.id()).isEqualTo("RES-retry");
    }

    /** Without a key there is nothing to deduplicate against, so the command simply runs. */
    @Test
    void aMissingKeyRunsTheCommand() {
        AtomicInteger invocations = new AtomicInteger();
        idempotency.execute(null, SCOPE, new Payload("ORD-1", BigDecimal.ONE), Result.class,
                () -> new Result("RES-" + invocations.incrementAndGet()));
        idempotency.execute("  ", SCOPE, new Payload("ORD-1", BigDecimal.ONE), Result.class,
                () -> new Result("RES-" + invocations.incrementAndGet()));

        assertThat(invocations.get()).isEqualTo(2);
    }

    // =============================================================================================
    // The real commands (HELP.md section 40)
    // =============================================================================================

    /**
     * A duplicate reservation request produces one redemption, not two
     * (HELP.md section 40, "duplicate redemption request").
     */
    @Test
    @DisplayName("a duplicate reserve request creates only one redemption")
    void duplicateReserveCreatesOneRedemption() {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, newOrderId(), new BigDecimal("10000"));

        String key = "RED-" + orderId;
        RedemptionRequest request = new RedemptionRequest(null, customerId, orderId, SAR,
                new BigDecimal("30.00"), null);

        RedemptionResponse first = reserveWithKey(key, request);
        RedemptionResponse second = reserveWithKey(key, request);

        assertThat(second.redemptionId()).isEqualTo(first.redemptionId());
        assertThat(redemptionRepository.findLiveForOrder(orderId)).hasSize(1);
        assertThat(lockedPoints(customerId)).as("points are locked once")
                .isEqualByComparingTo(new BigDecimal("3000"));
    }

    /**
     * A duplicate commit replays COMPLETED with no second ledger posting, and a duplicate release
     * replays RELEASED with no double credit (HELP.md section 40).
     */
    @Test
    void duplicateCommitAndReleaseReplayTheirOutcome() {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), new BigDecimal("10000"));

        String committedId = redemptions.reserve(reserveRequest(null, customerId, newOrderId(),
                new BigDecimal("30.00"))).getId();
        String commitKey = "COMMIT-" + committedId;

        RedemptionResponse committedOnce = idempotency.execute(commitKey, "redemption.commit",
                committedId, RedemptionResponse.class,
                () -> RedemptionResponse.of(redemptions.commit(committedId)));
        RedemptionResponse committedTwice = idempotency.execute(commitKey, "redemption.commit",
                committedId, RedemptionResponse.class,
                () -> RedemptionResponse.of(redemptions.commit(committedId)));

        assertThat(committedOnce.status()).isEqualTo(RedemptionStatus.COMPLETED);
        assertThat(committedTwice.status()).isEqualTo(RedemptionStatus.COMPLETED);
        assertThat(ledgerPostingCount(com.lumi.wallet.ledger.LedgerReferenceType.REDEMPTION,
                committedId)).as("no second ledger transaction").isEqualTo(1);

        String releasedId = redemptions.reserve(reserveRequest(null, customerId, newOrderId(),
                new BigDecimal("30.00"))).getId();
        String releaseKey = "RELEASE-" + releasedId;

        for (int attempt = 0; attempt < 3; attempt++) {
            RedemptionResponse released = idempotency.execute(releaseKey, "redemption.release",
                    releasedId, RedemptionResponse.class,
                    () -> RedemptionResponse.of(redemptions.release(releasedId, "TEST")));
            assertThat(released.status()).isEqualTo(RedemptionStatus.RELEASED);
        }

        assertThat(availablePoints(customerId)).as("released once, credited once")
                .isEqualByComparingTo(new BigDecimal("7000"));
        assertLotsMatchBalance(customerId);
    }

    private RedemptionResponse reserveWithKey(String key, RedemptionRequest request) {
        return idempotency.execute(key, "redemption.reserve", request, RedemptionResponse.class,
                () -> RedemptionResponse.of(redemptions.reserve(
                        new RedemptionService.ReserveRequest(request.quoteId(),
                                request.customerId(), request.orderId(), request.currency(),
                                request.walletAmount(), request.points()))));
    }

    /** A stand-in request body, fingerprinted to detect a conflicting key reuse. */
    private record Payload(String orderId, BigDecimal amount) {
    }

    /** A stand-in response, stored and replayed on a duplicate. */
    private record Result(String id) {
    }
}
