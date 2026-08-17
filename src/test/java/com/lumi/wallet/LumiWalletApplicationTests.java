package com.lumi.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the whole application against in-memory Derby. This also proves that the Flyway
 * migrations apply and that every JPA mapping matches the migrated schema, because
 * {@code spring.jpa.hibernate.ddl-auto} is {@code validate}.
 */
@SpringBootTest
@ActiveProfiles("test")
class LumiWalletApplicationTests {

    @Test
    void contextLoads() {
    }

}
