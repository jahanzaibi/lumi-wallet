package com.lumi.wallet.common;

import org.springframework.http.HttpStatus;

/**
 * The API error vocabulary from HELP.md section 45. Each code carries the HTTP status it maps to,
 * so the same code always produces the same status regardless of which service raised it.
 */
public enum ErrorCode {

    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "Wallet not found"),
    WALLET_BLOCKED(HttpStatus.CONFLICT, "Wallet is blocked"),
    INSUFFICIENT_REWARD_BALANCE(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient reward points"),
    REDEMPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Redemption not found"),
    REDEMPTION_EXPIRED(HttpStatus.CONFLICT, "Redemption reservation has expired"),
    INVALID_REDEMPTION_STATE(HttpStatus.CONFLICT, "Redemption is not in a valid state"),
    QUOTE_EXPIRED(HttpStatus.CONFLICT, "Quote has expired"),
    QUOTE_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "Request does not match the quote"),
    QUOTE_NOT_FOUND(HttpStatus.NOT_FOUND, "Quote not found"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Idempotency key was used with a different request"),
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "An identical request is currently in progress"),
    DUPLICATE_ORDER_REDEMPTION(HttpStatus.CONFLICT, "Order already has a live redemption"),
    ORDER_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_ENTITY, "Order is not eligible for rewards"),
    REWARD_NOT_AVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY, "Reward is not available"),
    INVALID_CURRENCY(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown or unsupported currency"),
    INVALID_AMOUNT(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid amount"),
    LEDGER_IMBALANCE(HttpStatus.INTERNAL_SERVER_ERROR, "Ledger transaction is not balanced"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
