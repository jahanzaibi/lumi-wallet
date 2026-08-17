package com.lumi.wallet.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lumi.wallet.account.WalletAccount;
import com.lumi.wallet.account.WalletAccountService;
import com.lumi.wallet.account.WalletBalance;
import com.lumi.wallet.common.Amounts;
import com.lumi.wallet.common.ErrorCode;
import com.lumi.wallet.common.WalletException;
import com.lumi.wallet.reward.RewardProgram;
import com.lumi.wallet.reward.RewardProgramRepository;
import com.lumi.wallet.reward.RewardService;
import com.lumi.wallet.reward.RewardTransaction;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * The read side of the wallet API (HELP.md section 46).
 *
 * <p>{@code customerId} arrives as a query parameter because this service has no authentication
 * layer in scope. In a deployed system it would come from the authenticated principal instead —
 * as it stands, any caller can read any customer's balance, which is fine for an internal service
 * behind a gateway and not fine if this were ever exposed directly.
 */
@RestController
@RequestMapping("/api/v1/wallet")
public class WalletController {

    private static final int MAX_PAGE_SIZE = 200;

    private final WalletAccountService accounts;
    private final RewardService rewards;
    private final RewardProgramRepository programs;

    public WalletController(WalletAccountService accounts, RewardService rewards,
            RewardProgramRepository programs) {
        this.accounts = accounts;
        this.rewards = rewards;
        this.programs = programs;
    }

    /** Every asset the customer holds, each with its own position (HELP.md sections 2, 46). */
    @GetMapping("/balance")
    public BalanceResponse balance(@RequestParam @NotBlank String customerId) {
        List<BalanceResponse.AssetBalance> balances = accounts.accountsOf(customerId).stream()
                .map(this::toAssetBalance)
                .toList();
        return new BalanceResponse(customerId, balances);
    }

    private BalanceResponse.AssetBalance toAssetBalance(WalletAccount account) {
        WalletBalance balance = accounts.balanceOf(account);
        boolean reward = account.getAsset().isReward();
        return new BalanceResponse.AssetBalance(
                account.getAsset().getCode(),
                account.getAsset().getType().name(),
                account.getStatus().name(),
                scale(balance.getAvailableAmount(), reward),
                scale(balance.getLockedAmount(), reward),
                scale(balance.getDebtAmount(), reward));
    }

    /** The reward position, including the lots that carry the expiry dates (HELP.md section 18). */
    @GetMapping("/rewards")
    public RewardsResponse rewards(@RequestParam @NotBlank String customerId) {
        RewardProgram program = programs.findActiveProgram()
                .orElseThrow(() -> new WalletException(ErrorCode.INTERNAL_ERROR,
                        "no active reward program is configured"));

        // Read from the lots rather than the balance: they are the record of what is spendable, and
        // agreeing with them is the invariant worth surfacing here.
        BigDecimal available = Amounts.points(rewards.availablePoints(customerId));
        BigDecimal pending = Amounts.points(rewards.pendingPoints(customerId));

        // A customer who has never earned anything has no balance row, and asking for their rewards
        // must not create one.
        Optional<WalletBalance> balance = accounts.findRewardBalance(customerId);
        BigDecimal locked = balance.map(WalletBalance::getLockedAmount)
                .map(Amounts::points)
                .orElse(Amounts.ZERO_POINTS);
        BigDecimal debt = balance.map(WalletBalance::getDebtAmount)
                .map(Amounts::points)
                .orElse(Amounts.ZERO_POINTS);

        return new RewardsResponse(
                customerId,
                program.getRewardAsset().getCode(),
                available,
                locked,
                pending,
                debt,
                program.getPointsPerCurrencyUnit(),
                Amounts.moneyFor(available, program.getPointsPerCurrencyUnit()),
                rewards.lotsOf(customerId).stream()
                        .map(RewardsResponse.RewardLotView::of)
                        .toList());
    }

    @GetMapping("/rewards/history")
    public RewardHistoryResponse history(
            @RequestParam @NotBlank String customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        Page<RewardTransaction> found = rewards.historyOf(customerId, PageRequest.of(page, size));
        return new RewardHistoryResponse(customerId, page, size, found.getTotalElements(),
                found.getContent().stream()
                        .map(RewardHistoryResponse.RewardHistoryEntry::of)
                        .toList());
    }

    /** Points are whole units; money keeps two decimals (HELP.md section 2). */
    private static BigDecimal scale(BigDecimal amount, boolean reward) {
        return reward ? Amounts.points(amount) : Amounts.money(amount);
    }
}
