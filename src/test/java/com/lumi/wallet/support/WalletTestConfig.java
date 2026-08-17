package com.lumi.wallet.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the wall clock with one the tests control.
 *
 * <p>The bean is named differently from the component-scanned {@code walletClock} and marked
 * {@link Primary}, rather than overriding it: bean definition overriding is disabled by default in
 * Spring Boot, and a test that silently depends on it having been enabled is a trap for whoever
 * changes that setting later.
 */
@TestConfiguration
public class WalletTestConfig {

    @Bean
    public MutableClock mutableClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    public WalletClock testWalletClock(MutableClock mutableClock) {
        return new WalletClock(mutableClock);
    }
}
