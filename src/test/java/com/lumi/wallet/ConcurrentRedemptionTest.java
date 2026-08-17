package com.lumi.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.lumi.wallet.common.ErrorCode;
import com.lumi.wallet.common.WalletException;
import com.lumi.wallet.redemption.Redemption;
import com.lumi.wallet.redemption.RedemptionStatus;

/**
 * The test HELP.md section 62 calls the most important one:
 *
 * <pre>
 * 10,000 points
 * Request A -> redeem 8,000
 * Request B -> redeem 8,000
 *
 * Expected: A = SUCCESS, B = INSUFFICIENT_BALANCE
 * Final:    AVAILABLE = 2,000, LOCKED = 8,000
 * </pre>
 *
 * <p>What is really being tested is that {@code available >= requested} is checked and acted on
 * atomically (sections 41, 42). Without the pessimistic row lock both requests read 10,000, both
 * conclude they can afford 8,000, and the wallet gives away 16,000 points it does not have. The
 * symptom would not be an error — it would be a silently negative position discovered later.
 */
class ConcurrentRedemptionTest extends AbstractWalletTest {

    private static final BigDecimal GRANTED = new BigDecimal("10000");
    private static final BigDecimal REDEEMED = new BigDecimal("8000");

    @Test
    @DisplayName("two simultaneous redemptions of 8,000 from 10,000: exactly one succeeds")
    void concurrentRedemptionsCannotBothSucceed() throws Exception {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);

        BigDecimal walletAmount = moneyForPoints(REDEEMED);
        // Two different orders, so that the only thing capable of rejecting the loser is the balance
        // check itself. Same-order requests would be refused as a duplicate redemption instead, and
        // would prove nothing about locking.
        String orderA = newOrderId();
        String orderB = newOrderId();

        List<Attempt> results = runConcurrently(
                () -> attempt(customerId, orderA, walletAmount),
                () -> attempt(customerId, orderB, walletAmount));

        List<Attempt> succeeded = results.stream().filter(Attempt::succeeded).toList();
        List<Attempt> failed = results.stream().filter(attempt -> !attempt.succeeded()).toList();

        assertThat(succeeded).as("exactly one of the two requests may reserve points").hasSize(1);
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).errorCode())
                .as("the loser is refused for lack of points, not for any other reason")
                .isEqualTo(ErrorCode.INSUFFICIENT_REWARD_BALANCE);

        assertThat(availablePoints(customerId))
                .as("available points after one 8,000 reservation")
                .isEqualByComparingTo(new BigDecimal("2000"));
        assertThat(lockedPoints(customerId))
                .as("locked points after one 8,000 reservation")
                .isEqualByComparingTo(REDEEMED);

        Redemption winner = redemptions.require(succeeded.get(0).redemptionId());
        assertThat(winner.getStatus()).isEqualTo(RedemptionStatus.RESERVED);
        assertThat(winner.getPoints()).isEqualByComparingTo(REDEEMED);

        assertLedgerConsistent(customerId);
        assertLotsMatchBalance(customerId);
    }

    /**
     * A third request for the points that are left must also be refused, since 2,000 remain and
     * 8,000 were asked for. Confirms the locked points really are unavailable to anyone else
     * (HELP.md section 60, rule 7) rather than merely unavailable during the race.
     */
    @Test
    void lockedPointsStayUnavailableAfterTheRace() throws Exception {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), GRANTED);
        BigDecimal walletAmount = moneyForPoints(REDEEMED);

        runConcurrently(
                () -> attempt(customerId, newOrderId(), walletAmount),
                () -> attempt(customerId, newOrderId(), walletAmount));

        Attempt third = attempt(customerId, newOrderId(), walletAmount);
        assertThat(third.succeeded()).isFalse();
        assertThat(third.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_REWARD_BALANCE);
    }

    private List<Attempt> runConcurrently(Callable<Attempt> first, Callable<Attempt> second)
            throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLine = new CountDownLatch(1);
        try {
            List<Future<Attempt>> futures = new ArrayList<>();
            for (Callable<Attempt> task : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    // Both threads wait here so that they contend for the lock, rather than one
                    // simply finishing before the other starts.
                    startLine.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            startLine.countDown();

            List<Attempt> results = new ArrayList<>();
            for (Future<Attempt> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private Attempt attempt(String customerId, String orderId, BigDecimal walletAmount) {
        try {
            Redemption redemption = redemptions.reserve(
                    reserveRequest(null, customerId, orderId, walletAmount));
            return new Attempt(redemption.getId(), null);
        } catch (WalletException e) {
            return new Attempt(null, e.code());
        }
    }

    /** The outcome of one request: either a redemption id or the code it was refused with. */
    private record Attempt(String redemptionId, ErrorCode errorCode) {

        boolean succeeded() {
            return redemptionId != null;
        }
    }
}
