package com.lumi.wallet.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the tests move by hand.
 *
 * <p>Expiry is central to this service — reservations time out after 5 minutes, quotes go stale, lots
 * expire after a year — and none of that can be tested by waiting. Moving the clock also makes the
 * tests deterministic: a reservation is either expired or not, rather than depending on how long the
 * suite took to get there.
 */
public class MutableClock extends Clock {

    /** Matches the timestamps used in HELP.md's own examples. */
    public static final Instant DEFAULT_NOW = Instant.parse("2026-08-17T10:00:00Z");

    private Instant instant;

    public MutableClock() {
        this(DEFAULT_NOW);
    }

    public MutableClock(Instant instant) {
        this.instant = instant;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    public void advance(Duration amount) {
        this.instant = this.instant.plus(amount);
    }

    public void advanceMinutes(long minutes) {
        advance(Duration.ofMinutes(minutes));
    }

    public void advanceDays(long days) {
        advance(Duration.ofDays(days));
    }

    public void reset() {
        this.instant = DEFAULT_NOW;
    }
}
