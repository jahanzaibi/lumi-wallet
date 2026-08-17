package com.lumi.wallet.redemption;

import java.util.Map;
import java.util.Set;

import com.lumi.wallet.common.ErrorCode;
import com.lumi.wallet.common.WalletException;

/**
 * The allowed transitions from HELP.md section 12, in one place.
 *
 * <p>Stated as data rather than as scattered {@code if} statements so that the expiry scheduler
 * goes through exactly the same rules as the API does. Section 13 is explicit about this: the
 * expiration process "must use the same state transition rules" and "must never blindly add
 * points".
 */
public final class RedemptionStateMachine {

    private static final Map<RedemptionStatus, Set<RedemptionStatus>> ALLOWED = Map.of(
            RedemptionStatus.CREATED, Set.of(RedemptionStatus.RESERVED),
            RedemptionStatus.RESERVED, Set.of(RedemptionStatus.COMPLETED,
                    RedemptionStatus.RELEASED),
            RedemptionStatus.COMPLETED, Set.of(),
            RedemptionStatus.RELEASED, Set.of());

    private RedemptionStateMachine() {
    }

    public static boolean canTransition(RedemptionStatus from, RedemptionStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void assertCanTransition(String redemptionId, RedemptionStatus from,
            RedemptionStatus to) {
        if (!canTransition(from, to)) {
            throw WalletException.of(ErrorCode.INVALID_REDEMPTION_STATE,
                    "redemption %s cannot move from %s to %s", redemptionId, from, to);
        }
    }
}
