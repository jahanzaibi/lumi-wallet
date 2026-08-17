package com.lumi.wallet.api;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lumi.wallet.idempotency.IdempotencyService;
import com.lumi.wallet.redemption.RedemptionService;

import jakarta.validation.Valid;

/**
 * The redemption API from HELP.md sections 7, 9, 10, 11 and 46.
 *
 * <p>The controller is deliberately thin: it validates shapes, applies the {@code Idempotency-Key},
 * and delegates. Every rule that matters — revalidation, locking, state transitions — belongs to
 * {@link RedemptionService}, because the API is not the only caller. The expiry scheduler and the
 * RabbitMQ consumers reach the same logic, and a rule enforced in a controller would not apply to
 * them (HELP.md section 13 is explicit that the expiry process must follow the same rules).
 */
@RestController
@RequestMapping("/api/v1/wallet/redemptions")
public class RedemptionController {

    /** Header name from HELP.md section 39. */
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private static final String SCOPE_RESERVE = "redemption.reserve";
    private static final String SCOPE_COMMIT = "redemption.commit";
    private static final String SCOPE_RELEASE = "redemption.release";

    private final RedemptionService redemptions;
    private final IdempotencyService idempotency;

    public RedemptionController(RedemptionService redemptions, IdempotencyService idempotency) {
        this.redemptions = redemptions;
        this.idempotency = idempotency;
    }

    /**
     * A calculation only; it moves no points (HELP.md sections 7, 8). No idempotency key is required
     * because re-quoting changes nothing a caller can observe.
     */
    @PostMapping("/quote")
    public QuoteResponse quote(@Valid @RequestBody QuoteRequest request) {
        return QuoteResponse.of(redemptions.quote(new RedemptionService.QuoteRequest(
                request.customerId(), request.orderId(), request.currency(), request.orderAmount(),
                request.requestedWalletAmount())));
    }

    /** AVAILABLE -> LOCKED (HELP.md section 9). */
    @PostMapping
    public RedemptionResponse reserve(
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @RequestBody RedemptionRequest request) {

        return idempotency.execute(idempotencyKey, SCOPE_RESERVE, request,
                RedemptionResponse.class,
                () -> RedemptionResponse.of(redemptions.reserve(
                        new RedemptionService.ReserveRequest(request.quoteId(),
                                request.customerId(), request.orderId(), request.currency(),
                                request.walletAmount(), request.points()))));
    }

    @GetMapping("/{redemptionId}")
    public RedemptionResponse get(@PathVariable String redemptionId) {
        return RedemptionResponse.of(redemptions.require(redemptionId));
    }

    /**
     * LOCKED -> REDEEMED after the external payment succeeded (HELP.md section 10). A duplicate
     * commit returns COMPLETED and posts nothing further (section 40).
     */
    @PostMapping("/{redemptionId}/commit")
    public RedemptionResponse commit(
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @PathVariable String redemptionId) {

        return idempotency.execute(idempotencyKey, SCOPE_COMMIT, redemptionId,
                RedemptionResponse.class,
                () -> RedemptionResponse.of(redemptions.commit(redemptionId)));
    }

    /**
     * LOCKED -> AVAILABLE after the external payment failed (HELP.md section 11). A duplicate release
     * returns RELEASED and credits nothing further (section 40).
     */
    @PostMapping("/{redemptionId}/release")
    public RedemptionResponse release(
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @PathVariable String redemptionId) {

        return idempotency.execute(idempotencyKey, SCOPE_RELEASE, redemptionId,
                RedemptionResponse.class,
                () -> RedemptionResponse.of(redemptions.release(redemptionId, "CLIENT_REQUESTED")));
    }
}
