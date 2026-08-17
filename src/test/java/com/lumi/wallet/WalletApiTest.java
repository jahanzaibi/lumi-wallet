package com.lumi.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.lumi.wallet.api.RedemptionController;
import com.lumi.wallet.common.CorrelationId;

import tools.jackson.databind.ObjectMapper;

/**
 * The public API from HELP.md section 46, and the error envelope from section 45.
 */
@AutoConfigureMockMvc
class WalletApiTest extends AbstractWalletTest {

    private static final String QUOTE_URL = "/api/v1/wallet/redemptions/quote";
    private static final String REDEMPTIONS_URL = "/api/v1/wallet/redemptions";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // =============================================================================================
    // Quote (HELP.md sections 7, 47)
    // =============================================================================================

    @Test
    @DisplayName("the quote response carries every field HELP.md section 7 specifies")
    void quoteReturnsTheSpecifiedShape() throws Exception {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), new BigDecimal("10000"));

        mvc.perform(post(QUOTE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "customerId": "%s",
                          "orderId": "ORD-100",
                          "currency": "SAR",
                          "orderAmount": 100.00,
                          "requestedWalletAmount": 30.00
                        }
                        """.formatted(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteId").exists())
                .andExpect(jsonPath("$.customerId").value(customerId))
                .andExpect(jsonPath("$.orderId").value("ORD-100"))
                .andExpect(jsonPath("$.currency").value("SAR"))
                .andExpect(jsonPath("$.orderAmount").value(100.00))
                .andExpect(jsonPath("$.walletAmount").value(30.00))
                .andExpect(jsonPath("$.remainingAmount").value(70.00))
                .andExpect(jsonPath("$.pointsRequired").value(3000))
                .andExpect(jsonPath("$.pointsAvailable").value(10000))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void invalidQuoteRequestIsRejectedWithFieldDetails() throws Exception {
        mvc.perform(post(QUOTE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerId": "", "orderId": "ORD-1", "currency": "SAR",
                         "orderAmount": -5}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(jsonPath("$.details").isArray());
    }

    // =============================================================================================
    // Reserve, commit, release (HELP.md sections 9, 10, 11, 39)
    // =============================================================================================

    @Test
    @DisplayName("reserve, get, commit: the response shapes from HELP.md sections 9 and 10")
    void reserveThenCommit() throws Exception {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, newOrderId(), new BigDecimal("10000"));

        MvcResult reserved = mvc.perform(post(REDEMPTIONS_URL)
                .header(RedemptionController.IDEMPOTENCY_HEADER, "RED-" + orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reserveBody(customerId, orderId, "30.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.walletAmount").value(30.00))
                .andExpect(jsonPath("$.points").value(3000))
                .andExpect(jsonPath("$.currency").value("SAR"))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn();

        String redemptionId = readField(reserved, "redemptionId");

        mvc.perform(get(REDEMPTIONS_URL + "/" + redemptionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redemptionId").value(redemptionId))
                .andExpect(jsonPath("$.status").value("RESERVED"));

        mvc.perform(post(REDEMPTIONS_URL + "/" + redemptionId + "/commit")
                .header(RedemptionController.IDEMPOTENCY_HEADER, "COMMIT-" + redemptionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redemptionId").value(redemptionId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.points").value(3000))
                .andExpect(jsonPath("$.walletAmount").value(30.00));

        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("7000"));
    }

    @Test
    void releaseReturnsThePoints() throws Exception {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, newOrderId(), new BigDecimal("10000"));

        MvcResult reserved = mvc.perform(post(REDEMPTIONS_URL)
                .header(RedemptionController.IDEMPOTENCY_HEADER, "RED-" + orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reserveBody(customerId, orderId, "30.00")))
                .andExpect(status().isOk())
                .andReturn();
        String redemptionId = readField(reserved, "redemptionId");

        mvc.perform(post(REDEMPTIONS_URL + "/" + redemptionId + "/release")
                .header(RedemptionController.IDEMPOTENCY_HEADER, "RELEASE-" + redemptionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.points").value(3000));

        assertThat(availablePoints(customerId)).isEqualByComparingTo(new BigDecimal("10000"));
    }

    /** Every state-changing command requires the header (HELP.md section 39). */
    @Test
    void reserveWithoutAnIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post(REDEMPTIONS_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reserveBody(newCustomerId(), newOrderId(), "30.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Idempotency-Key")));
    }

    /** Same key, same request: the original result comes back (HELP.md sections 39, 40). */
    @Test
    void duplicateReserveReturnsTheOriginalRedemption() throws Exception {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, newOrderId(), new BigDecimal("10000"));
        String key = "RED-" + orderId;
        String body = reserveBody(customerId, orderId, "30.00");

        MvcResult first = mvc.perform(post(REDEMPTIONS_URL)
                .header(RedemptionController.IDEMPOTENCY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();

        mvc.perform(post(REDEMPTIONS_URL)
                .header(RedemptionController.IDEMPOTENCY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redemptionId").value(readField(first, "redemptionId")));

        assertThat(redemptionRepository.findLiveForOrder(orderId)).hasSize(1);
        assertThat(lockedPoints(customerId)).isEqualByComparingTo(new BigDecimal("3000"));
    }

    /** Same key, different request: 409 IDEMPOTENCY_CONFLICT (HELP.md section 39). */
    @Test
    void sameKeyWithADifferentRequestIsAConflict() throws Exception {
        String customerId = newCustomerId();
        String orderId = newOrderId();
        grantAvailablePoints(customerId, newOrderId(), new BigDecimal("10000"));
        String key = "RED-" + orderId;

        mvc.perform(post(REDEMPTIONS_URL)
                .header(RedemptionController.IDEMPOTENCY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reserveBody(customerId, orderId, "30.00")))
                .andExpect(status().isOk());

        mvc.perform(post(REDEMPTIONS_URL)
                .header(RedemptionController.IDEMPOTENCY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reserveBody(customerId, orderId, "50.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    // =============================================================================================
    // The error model (HELP.md section 45)
    // =============================================================================================

    @Test
    @DisplayName("insufficient points produce the error envelope from HELP.md section 45")
    void insufficientBalanceIsReportedWithTheErrorEnvelope() throws Exception {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), new BigDecimal("100"));

        mvc.perform(post(REDEMPTIONS_URL)
                .header(RedemptionController.IDEMPOTENCY_HEADER, "RED-" + newOrderId())
                .header(CorrelationId.HEADER, "CORR-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(reserveBody(customerId, newOrderId(), "30.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_REWARD_BALANCE"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.correlationId").value("CORR-123"))
                .andExpect(header().string(CorrelationId.HEADER, "CORR-123"));
    }

    @Test
    void anUnknownRedemptionIsNotFound() throws Exception {
        mvc.perform(get(REDEMPTIONS_URL + "/RED-nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REDEMPTION_NOT_FOUND"));
    }

    @Test
    void committingAReleasedRedemptionIsAConflict() throws Exception {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), new BigDecimal("10000"));
        String redemptionId = redemptions.reserve(
                reserveRequest(null, customerId, newOrderId(), new BigDecimal("30.00"))).getId();
        redemptions.release(redemptionId, "TEST");

        mvc.perform(post(REDEMPTIONS_URL + "/" + redemptionId + "/commit")
                .header(RedemptionController.IDEMPOTENCY_HEADER, "COMMIT-" + redemptionId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_REDEMPTION_STATE"));
    }

    // =============================================================================================
    // The read side (HELP.md section 46)
    // =============================================================================================

    @Test
    void balanceListsEachAssetSeparately() throws Exception {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), new BigDecimal("2500"));

        mvc.perform(get("/api/v1/wallet/balance").param("customerId", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(customerId))
                .andExpect(jsonPath("$.balances[0].asset").value("POINT"))
                .andExpect(jsonPath("$.balances[0].assetType").value("REWARD"))
                .andExpect(jsonPath("$.balances[0].available").value(2500))
                .andExpect(jsonPath("$.balances[0].locked").value(0))
                .andExpect(jsonPath("$.balances[0].debt").value(0));
    }

    @Test
    void rewardsShowPendingAvailableAndTheLots() throws Exception {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), new BigDecimal("2500"));
        earnPending(customerId, newOrderId(), new BigDecimal("400"));

        mvc.perform(get("/api/v1/wallet/rewards").param("customerId", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asset").value("POINT"))
                .andExpect(jsonPath("$.availablePoints").value(2500))
                .andExpect(jsonPath("$.pendingPoints").value(400))
                .andExpect(jsonPath("$.rewardDebt").value(0))
                .andExpect(jsonPath("$.availableValue").value(25.00))
                .andExpect(jsonPath("$.pointsPerCurrencyUnit").value(100.0000))
                .andExpect(jsonPath("$.lots.length()").value(2));
    }

    @Test
    void rewardHistoryIsPaged() throws Exception {
        String customerId = newCustomerId();
        grantAvailablePoints(customerId, newOrderId(), new BigDecimal("500"));

        mvc.perform(get("/api/v1/wallet/rewards/history")
                .param("customerId", customerId)
                .param("page", "0")
                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(customerId))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.entries[0].type").value("EARN"))
                .andExpect(jsonPath("$.entries[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.entries[0].points").value(500));
    }

    @Test
    void balanceRequiresACustomerId() throws Exception {
        mvc.perform(get("/api/v1/wallet/balance"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // =============================================================================================

    private static String reserveBody(String customerId, String orderId, String walletAmount) {
        return """
                {
                  "customerId": "%s",
                  "orderId": "%s",
                  "currency": "SAR",
                  "walletAmount": %s
                }
                """.formatted(customerId, orderId, walletAmount);
    }

    private String readField(MvcResult result, String field) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get(field)
                .asString();
    }
}
