package com.lumi.wallet.common;

import java.util.List;

/**
 * The error envelope from HELP.md section 45.
 *
 * @param details field level problems, present only for validation failures
 */
public record ErrorResponse(
        String code,
        String message,
        String correlationId,
        List<String> details) {

    public static ErrorResponse of(ErrorCode code, String message, String correlationId) {
        return new ErrorResponse(code.name(), message, correlationId, null);
    }
}
