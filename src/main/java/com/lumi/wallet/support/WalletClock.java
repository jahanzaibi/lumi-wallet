package com.lumi.wallet.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Component;

/**
 * The single source of "now".
 *
 * <p>Time is injected rather than read from {@code Instant.now()} at call sites so that expiry
 * behaviour (reservation TTL, quote TTL, lot expiry) can be tested by moving the clock instead of
 * sleeping. Values are truncated to milliseconds because Derby's TIMESTAMP does not preserve
 * nanosecond precision, and a value that changes when it round-trips through the database makes
 * equality assertions fail for no good reason.
 */
@Component
public class WalletClock {

    private final Clock clock;

    public WalletClock() {
        this(Clock.systemUTC());
    }

    public WalletClock(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    public Instant nowPlus(Duration duration) {
        return now().plus(duration);
    }

    public Instant nowPlusDays(int days) {
        return now().plus(Duration.ofDays(days));
    }
}
