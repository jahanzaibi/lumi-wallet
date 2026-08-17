package com.lumi.wallet.account;

import java.math.BigDecimal;
import java.time.Instant;

import com.lumi.wallet.common.Amounts;
import com.lumi.wallet.common.ErrorCode;
import com.lumi.wallet.common.WalletException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * The spendable state of one wallet account (HELP.md section 32).
 *
 * <p>All balance movement lives here as behaviour rather than in the services, so that the rules
 * "you cannot spend what you do not have" and "you cannot release what is not locked" are stated
 * exactly once. Callers must already hold the pessimistic row lock
 * ({@link WalletBalanceRepository#lockForUpdate}) before calling any mutator; the lock is what
 * makes the check-then-act sequences below atomic (HELP.md sections 41, 42).
 */
@Entity
@Table(name = "wallet_balance")
public class WalletBalance {

    @Id
    @Column(name = "wallet_account_id", nullable = false, length = 64)
    private String walletAccountId;

    @Column(name = "available_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal availableAmount;

    @Column(name = "locked_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal lockedAmount;

    /**
     * Already-redeemed rewards that were later reversed and therefore could not be taken back out
     * of the available balance (HELP.md section 22). Future rewards pay this down first.
     */
    @Column(name = "debt_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal debtAmount;

    /**
     * Left null until the row is first persisted. Spring Data decides whether an entity is new from
     * its version when it has one, so pre-setting this to zero would make a brand-new balance look
     * detached: {@code save} would merge instead of insert, and the insert of a row that does not
     * exist yet fails as a stale-object update. Hibernate assigns the initial version itself.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WalletBalance() {
    }

    private WalletBalance(String walletAccountId, Instant now) {
        this.walletAccountId = walletAccountId;
        this.availableAmount = BigDecimal.ZERO;
        this.lockedAmount = BigDecimal.ZERO;
        this.debtAmount = BigDecimal.ZERO;
        this.updatedAt = now;
    }

    public static WalletBalance emptyFor(WalletAccount account, Instant now) {
        return new WalletBalance(account.getId(), now);
    }

    // -----------------------------------------------------------------------------------------
    // Movement
    // -----------------------------------------------------------------------------------------

    /** Rewards becoming available, or a released redemption returning points. */
    public void credit(BigDecimal amount, Instant now) {
        requirePositive(amount);
        this.availableAmount = this.availableAmount.add(amount);
        touch(now);
    }

    /**
     * Removes points from the available balance outright: a reversal or an expiry, where the
     * points leave the wallet without ever being locked.
     */
    public void debit(BigDecimal amount, Instant now) {
        requirePositive(amount);
        if (Amounts.lt(availableAmount, amount)) {
            throw WalletException.of(ErrorCode.INSUFFICIENT_REWARD_BALANCE,
                    "available %s is less than %s", availableAmount, amount);
        }
        this.availableAmount = this.availableAmount.subtract(amount);
        touch(now);
    }

    /**
     * AVAILABLE -> LOCKED (HELP.md section 9). The guard here is the invariant that section 42
     * insists must be atomic: available points >= requested points.
     */
    public void reserve(BigDecimal amount, Instant now) {
        requirePositive(amount);
        if (Amounts.lt(availableAmount, amount)) {
            throw WalletException.of(ErrorCode.INSUFFICIENT_REWARD_BALANCE,
                    "available %s is less than requested %s", availableAmount, amount);
        }
        this.availableAmount = this.availableAmount.subtract(amount);
        this.lockedAmount = this.lockedAmount.add(amount);
        touch(now);
    }

    /** LOCKED -> AVAILABLE (HELP.md section 11). */
    public void releaseLocked(BigDecimal amount, Instant now) {
        requireLocked(amount);
        this.lockedAmount = this.lockedAmount.subtract(amount);
        this.availableAmount = this.availableAmount.add(amount);
        touch(now);
    }

    /** LOCKED -> REDEEMED (HELP.md section 10): the points are now permanently consumed. */
    public void consumeLocked(BigDecimal amount, Instant now) {
        requireLocked(amount);
        this.lockedAmount = this.lockedAmount.subtract(amount);
        touch(now);
    }

    /**
     * Records reward debt, used when a reversal targets points the customer already spent
     * (HELP.md section 22). Preferable to letting the account go silently inconsistent.
     */
    public void addDebt(BigDecimal amount, Instant now) {
        requirePositive(amount);
        this.debtAmount = this.debtAmount.add(amount);
        touch(now);
    }

    /**
     * Pays debt down out of {@code amount} of newly available points.
     *
     * @return how much of {@code amount} went to debt; the caller keeps the remainder as available
     */
    public BigDecimal settleDebtFrom(BigDecimal amount, Instant now) {
        requirePositive(amount);
        BigDecimal applied = Amounts.min(debtAmount, amount);
        if (Amounts.isZero(applied)) {
            return applied;
        }
        this.debtAmount = this.debtAmount.subtract(applied);
        this.availableAmount = this.availableAmount.subtract(applied);
        touch(now);
        return applied;
    }

    // -----------------------------------------------------------------------------------------

    public boolean hasAvailable(BigDecimal amount) {
        return !Amounts.lt(availableAmount, amount);
    }

    public boolean hasDebt() {
        return Amounts.isPositive(debtAmount);
    }

    private void requirePositive(BigDecimal amount) {
        if (!Amounts.isPositive(amount)) {
            throw WalletException.of(ErrorCode.INVALID_AMOUNT, "amount must be positive but was %s",
                    amount);
        }
    }

    private void requireLocked(BigDecimal amount) {
        requirePositive(amount);
        if (Amounts.lt(lockedAmount, amount)) {
            throw WalletException.of(ErrorCode.INVALID_AMOUNT,
                    "locked %s is less than %s", lockedAmount, amount);
        }
    }

    private void touch(Instant now) {
        this.updatedAt = now;
    }

    public String getWalletAccountId() {
        return walletAccountId;
    }

    public BigDecimal getAvailableAmount() {
        return availableAmount;
    }

    public BigDecimal getLockedAmount() {
        return lockedAmount;
    }

    public BigDecimal getDebtAmount() {
        return debtAmount;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
