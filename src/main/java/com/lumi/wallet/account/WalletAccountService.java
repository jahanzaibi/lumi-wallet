package com.lumi.wallet.account;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lumi.wallet.asset.Asset;
import com.lumi.wallet.asset.AssetRepository;
import com.lumi.wallet.common.ErrorCode;
import com.lumi.wallet.common.WalletException;
import com.lumi.wallet.config.WalletProperties;
import com.lumi.wallet.support.WalletClock;

/**
 * Resolves wallet accounts and their balances, creating a customer's account on first use.
 */
@Service
public class WalletAccountService {

    private static final Logger log = LoggerFactory.getLogger(WalletAccountService.class);

    private final WalletAccountRepository accounts;
    private final WalletBalanceRepository balances;
    private final AssetRepository assets;
    private final WalletProperties properties;
    private final WalletClock clock;

    public WalletAccountService(WalletAccountRepository accounts, WalletBalanceRepository balances,
            AssetRepository assets, WalletProperties properties, WalletClock clock) {
        this.accounts = accounts;
        this.balances = balances;
        this.assets = assets;
        this.properties = properties;
        this.clock = clock;
    }

    public Asset requireAsset(String code) {
        return assets.findByCode(code)
                .filter(Asset::isActive)
                .orElseThrow(() -> WalletException.of(ErrorCode.INVALID_CURRENCY,
                        "unknown or inactive asset '%s'", code));
    }

    /** The reward asset, POINT by default. */
    public Asset rewardAsset() {
        return requireAsset(properties.rewardAssetCode());
    }

    /**
     * Validates that a currency quoted by a caller is a monetary asset. Guards against a caller
     * passing "POINT" where an order currency belongs, which would silently equate 100 SAR with
     * 100 points (HELP.md section 2).
     */
    public Asset requireMonetaryAsset(String currency) {
        Asset asset = requireAsset(currency);
        if (!asset.isMonetary()) {
            throw WalletException.of(ErrorCode.INVALID_CURRENCY,
                    "'%s' is not a monetary currency", currency);
        }
        return asset;
    }

    /**
     * The customer's reward account, created on first touch. New customers do not need to be
     * provisioned ahead of their first order.
     */
    @Transactional
    public WalletAccount rewardAccountFor(String customerId) {
        return getOrCreate(customerId, rewardAsset());
    }

    @Transactional
    public WalletAccount getOrCreate(String customerId, Asset asset) {
        return accounts.findByCustomerAndAsset(customerId, asset.getCode())
                .orElseGet(() -> create(customerId, asset));
    }

    /**
     * Creates the account and its balance row.
     *
     * <p>A concurrent caller creating the same account trips {@code uk_wallet_customer_asset}, and
     * that violation is deliberately allowed to propagate rather than being caught and recovered
     * from. Once a flush has failed the surrounding transaction is marked rollback-only, so
     * "catch it and look up the winner's row" cannot work: the lookup succeeds and the commit then
     * fails anyway, with a far more confusing error. Letting it surface rolls the caller's
     * transaction back, and the caller — an idempotent API request or a redelivered event — retries
     * and finds the account waiting. The race is also vanishingly rare, since it needs two first-ever
     * operations for the same customer at the same instant.
     */
    private WalletAccount create(String customerId, Asset asset) {
        Instant now = clock.now();
        WalletAccount account = WalletAccount.forCustomer(customerId, asset, now);
        accounts.saveAndFlush(account);
        balances.saveAndFlush(WalletBalance.emptyFor(account, now));
        log.info("Created {} wallet account {} for customer {}", asset.getCode(), account.getId(),
                customerId);
        return account;
    }

    /**
     * Loads the balance under a pessimistic row lock. Every operation that changes a customer's
     * points takes this lock first, and takes no other, which is what makes
     * "available >= requested" atomic (HELP.md sections 41, 42).
     */
    @Transactional
    public WalletBalance lockBalance(WalletAccount account) {
        return balances.lockForUpdate(account.getId())
                .orElseThrow(() -> WalletException.of(ErrorCode.WALLET_NOT_FOUND,
                        "no balance for account %s", account.getId()));
    }

    /** Convenience for the common case: lock this customer's reward balance. */
    @Transactional
    public WalletBalance lockRewardBalance(String customerId) {
        WalletAccount account = rewardAccountFor(customerId);
        requireActive(account);
        return lockBalance(account);
    }

    public void requireActive(WalletAccount account) {
        if (!account.isActive()) {
            throw WalletException.of(ErrorCode.WALLET_BLOCKED,
                    "wallet account %s is %s", account.getId(), account.getStatus());
        }
    }

    @Transactional(readOnly = true)
    public WalletBalance balanceOf(WalletAccount account) {
        return balances.findById(account.getId())
                .orElseThrow(() -> WalletException.of(ErrorCode.WALLET_NOT_FOUND,
                        "no balance for account %s", account.getId()));
    }

    @Transactional(readOnly = true)
    public List<WalletAccount> accountsOf(String customerId) {
        return accounts.findByCustomer(customerId);
    }

    /**
     * The customer's reward balance if they have one.
     *
     * <p>Read-only lookups must not create an account as a side effect: a balance enquiry for an
     * unknown customer is a legitimate question with the answer "nothing", not a reason to write a
     * row.
     */
    @Transactional(readOnly = true)
    public Optional<WalletBalance> findRewardBalance(String customerId) {
        return accounts.findByCustomerAndAsset(customerId, properties.rewardAssetCode())
                .flatMap(account -> balances.findById(account.getId()));
    }

    /** The counterparty account for postings in the given asset (HELP.md section 38). */
    public WalletAccount liabilityAccount(Asset asset) {
        return accounts.findLiabilityAccount(asset.getCode())
                .orElseThrow(() -> WalletException.of(ErrorCode.INTERNAL_ERROR,
                        "no liability account seeded for asset %s", asset.getCode()));
    }
}
