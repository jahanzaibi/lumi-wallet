package com.lumi.wallet.support;

import org.hibernate.community.dialect.DerbyDialect;

import jakarta.persistence.Timeout;

/**
 * Derby dialect with the pessimistic-lock clause corrected.
 *
 * <p>{@code hibernate-community-dialects} 7.4.1 emits the Derby result-set option twice, producing
 * SQL that Derby rejects outright:
 *
 * <pre>
 * select ... from wallet_balance where wallet_account_id=? for update with rs with rs
 *                                                                        ^^^^^^^ ^^^^^^^
 * </pre>
 *
 * <p>Two collaborators each believe they own the suffix. {@code DerbyDialect.getForUpdateString()}
 * and {@code getWriteLockString(..)} return {@code " for update with rs"}, while
 * {@code DerbyLockingClauseStrategy.renderResultSetOptions} appends {@code " with rs"} on its own.
 * Overriding the dialect's half to return the bare lock clause leaves the strategy to add the
 * isolation hint once, which is the arrangement the strategy is written for.
 *
 * <p>This matters because the pessimistic row lock is not optional decoration here: it is the
 * mechanism that makes {@code available points >= requested points} atomic (HELP.md sections 41, 42).
 * Without a working {@code FOR UPDATE} the wallet cannot enforce its central invariant on Derby at
 * all.
 *
 * <p>Only Derby is affected. PostgreSQL — the intended production database (HELP.md section 59) —
 * uses its own dialect and needs none of this, so the workaround disappears with the development
 * database rather than becoming permanent.
 */
public class DerbyRowLockingDialect extends DerbyDialect {

    private static final String FOR_UPDATE = " for update";
    private static final String FOR_READ_ONLY = " for read only";

    @Override
    public String getForUpdateString() {
        return FOR_UPDATE;
    }

    @Override
    public String getWriteLockString(Timeout timeout) {
        return FOR_UPDATE;
    }

    @Override
    public String getReadLockString(Timeout timeout) {
        return FOR_READ_ONLY;
    }

    @Override
    @SuppressWarnings("deprecation")
    public String getWriteLockString(int timeout) {
        return FOR_UPDATE;
    }

    @Override
    @SuppressWarnings("deprecation")
    public String getReadLockString(int timeout) {
        return FOR_READ_ONLY;
    }
}
