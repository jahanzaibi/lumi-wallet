package com.lumi.wallet.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money and points arithmetic.
 *
 * <p>The conversion direction matters and is not symmetric. Converting money to points rounds
 * <em>up</em> and converting points to money rounds <em>down</em>, so that the points taken from a
 * customer always fully cover the money the wallet contributes to an order. Rounding the other way
 * would let a redemption contribute slightly more value than the points paid for, which over many
 * orders is a real loss.
 */
public final class Amounts {

    /** Monetary scale. Matches the DECIMAL(19,4) columns while presenting normal currency scale. */
    public static final int MONEY_SCALE = 2;

    /** Points are whole units. */
    public static final int POINTS_SCALE = 0;

    public static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE);
    public static final BigDecimal ZERO_POINTS = BigDecimal.ZERO.setScale(POINTS_SCALE);

    private Amounts() {
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal points(BigDecimal value) {
        return value.setScale(POINTS_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Points needed to settle {@code money}, rounded up so the points always cover the money.
     */
    public static BigDecimal pointsFor(BigDecimal money, BigDecimal pointsPerCurrencyUnit) {
        return money.multiply(pointsPerCurrencyUnit).setScale(POINTS_SCALE, RoundingMode.CEILING);
    }

    /**
     * Money that {@code points} can settle, rounded down so it never exceeds the points' value.
     */
    public static BigDecimal moneyFor(BigDecimal points, BigDecimal pointsPerCurrencyUnit) {
        return points.divide(pointsPerCurrencyUnit, MONEY_SCALE, RoundingMode.FLOOR);
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    public static boolean isZero(BigDecimal value) {
        return value != null && value.signum() == 0;
    }

    public static boolean isNegative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }

    /** Scale-insensitive comparison; {@code 30.00} and {@code 30} are the same amount. */
    public static boolean eq(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    public static boolean lt(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) < 0;
    }

    public static boolean gt(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) > 0;
    }

    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
