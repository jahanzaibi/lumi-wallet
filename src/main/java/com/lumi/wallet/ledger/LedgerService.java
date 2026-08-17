package com.lumi.wallet.ledger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.lumi.wallet.account.WalletAccount;
import com.lumi.wallet.account.WalletAccountService;
import com.lumi.wallet.asset.Asset;
import com.lumi.wallet.common.Amounts;
import com.lumi.wallet.common.ErrorCode;
import com.lumi.wallet.common.WalletException;
import com.lumi.wallet.support.WalletClock;

/**
 * Writes the immutable double-entry ledger (HELP.md sections 37, 38).
 *
 * <p>Every posting must satisfy SUM(DEBIT) == SUM(CREDIT) per asset, and this class is the only
 * place ledger rows are created, so that rule cannot be bypassed. Postings always run inside the
 * caller's transaction ({@link Propagation#MANDATORY}) because a balance change and its ledger
 * entries have to commit together (HELP.md section 60, rule 17).
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerTransactionRepository transactions;
    private final WalletAccountService accountService;
    private final WalletClock clock;

    public LedgerService(LedgerTransactionRepository transactions,
            WalletAccountService accountService, WalletClock clock) {
        this.transactions = transactions;
        this.accountService = accountService;
        this.clock = clock;
    }

    /**
     * Posts the customer/liability pair that almost every reward movement needs.
     *
     * <p>{@code customerDirection} is the direction on the <em>customer's</em> account; the
     * liability account always takes the opposite side, which is what balances the posting.
     * Crediting a customer with 500 points debits 500 points of reward liability
     * (HELP.md section 38).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerTransaction postAgainstLiability(LedgerReferenceType referenceType,
            String referenceId, LedgerTransactionType transactionType, WalletAccount customer,
            Direction customerDirection, BigDecimal amount) {

        Asset asset = customer.getAsset();
        WalletAccount liability = accountService.liabilityAccount(asset);
        Direction liabilityDirection = customerDirection == Direction.DEBIT
                ? Direction.CREDIT
                : Direction.DEBIT;

        return post(referenceType, referenceId, transactionType, List.of(
                new Posting(customer, customerDirection, amount),
                new Posting(liability, liabilityDirection, amount)));
    }

    /**
     * Posts an arbitrary set of entries, rejecting anything unbalanced.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerTransaction post(LedgerReferenceType referenceType, String referenceId,
            LedgerTransactionType transactionType, List<Posting> postings) {

        if (postings.size() < 2) {
            throw WalletException.of(ErrorCode.LEDGER_IMBALANCE,
                    "a posting needs at least two entries, got %d", postings.size());
        }
        assertBalancedPerAsset(postings);

        LedgerTransaction transaction = new LedgerTransaction(referenceType, referenceId,
                transactionType, clock.now());
        for (Posting posting : postings) {
            if (!Amounts.isPositive(posting.amount())) {
                throw WalletException.of(ErrorCode.INVALID_AMOUNT,
                        "ledger entry amount must be positive but was %s", posting.amount());
            }
            transaction.addEntry(new LedgerEntry(posting.account(), posting.direction(),
                    posting.amount(), transaction.getCreatedAt()));
        }

        // The unique constraint on (reference_type, reference_id, transaction_type) is the real
        // guarantee here: even if two threads reach this line, only one posting can exist.
        LedgerTransaction saved = transactions.saveAndFlush(transaction);
        log.debug("Posted ledger transaction {} ({} {} for {})", saved.getId(), transactionType,
                referenceType, referenceId);
        return saved;
    }

    /**
     * HELP.md section 38: debits must equal credits <em>for each asset</em>. Checking the total
     * across assets would let 100 SAR balance 100 POINTS, which is exactly the confusion the
     * two-asset model exists to prevent.
     */
    private void assertBalancedPerAsset(List<Posting> postings) {
        List<String> assetIds = new ArrayList<>();
        for (Posting posting : postings) {
            String assetId = posting.account().getAsset().getId();
            if (!assetIds.contains(assetId)) {
                assetIds.add(assetId);
            }
        }
        for (String assetId : assetIds) {
            BigDecimal debits = BigDecimal.ZERO;
            BigDecimal credits = BigDecimal.ZERO;
            for (Posting posting : postings) {
                if (!posting.account().getAsset().getId().equals(assetId)) {
                    continue;
                }
                if (posting.direction() == Direction.DEBIT) {
                    debits = debits.add(posting.amount());
                } else {
                    credits = credits.add(posting.amount());
                }
            }
            if (!Amounts.eq(debits, credits)) {
                throw WalletException.of(ErrorCode.LEDGER_IMBALANCE,
                        "asset %s is unbalanced: debit %s, credit %s", assetId, debits, credits);
            }
        }
    }

    /** True when this exact posting already exists, so callers can stay idempotent. */
    @Transactional(readOnly = true)
    public boolean alreadyPosted(LedgerReferenceType referenceType, String referenceId,
            LedgerTransactionType transactionType) {
        return transactions.findByReferenceTypeAndReferenceIdAndTransactionType(
                referenceType, referenceId, transactionType).isPresent();
    }

    /** One side of a posting, before it becomes an immutable {@link LedgerEntry}. */
    public record Posting(WalletAccount account, Direction direction, BigDecimal amount) {
    }
}
