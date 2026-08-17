package com.lumi.wallet.asset;

/**
 * HELP.md section 2: a monetary wallet and a reward asset are different financial concepts and
 * must never be mixed in the same ledger balance. 100 SAR is not 100 POINTS.
 */
public enum AssetType {

    MONETARY,
    REWARD
}
