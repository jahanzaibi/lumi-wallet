package com.lumi.wallet.api;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.lumi.wallet.common.CorrelationId;
import com.lumi.wallet.common.ErrorCode;
import com.lumi.wallet.common.ErrorResponse;
import com.lumi.wallet.common.WalletException;

/**
 * Turns every failure into the single error envelope from HELP.md section 45.
 *
 * <pre>
 * { "code": "INSUFFICIENT_REWARD_BALANCE", "message": "...", "correlationId": "CORR-123" }
 * </pre>
 *
 * <p>The status comes from the {@link ErrorCode} itself, so a given code always produces the same
 * status no matter which service raised it. Anything unrecognised becomes a 500 with a generic
 * message and a correlation id: the caller gets something to quote in a support ticket, and the
 * details stay in the log rather than being handed to a client.
 */
@RestControllerAdvice
public class WalletExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WalletExceptionHandler.class);

    @ExceptionHandler(WalletException.class)
    public ResponseEntity<ErrorResponse> handleWalletException(WalletException e) {
        ErrorCode code = e.code();
        // Expected business outcomes are not incidents. A 5xx is, so it gets a stack trace.
        if (code.status().is5xxServerError()) {
            log.error("{} while handling request: {}", code, e.getMessage(), e);
        } else {
            log.info("{}: {}", code, e.getMessage());
        }
        return respond(code, e.getMessage(), null);
    }

    // ---------------------------------------------------------------------------------------------
    // Request shape problems. All 400s, all described the same way.
    // ---------------------------------------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(WalletExceptionHandler::describe)
                .toList();
        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed", details);
    }

    private static String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleInvalidParameters(
            HandlerMethodValidationException e) {
        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed", null);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        // Most often a missing Idempotency-Key, which HELP.md section 39 requires on every command.
        return respond(ErrorCode.VALIDATION_FAILED,
                "Required header '" + e.getHeaderName() + "' is missing", null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException e) {
        return respond(ErrorCode.VALIDATION_FAILED,
                "Required parameter '" + e.getParameterName() + "' is missing", null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleUnreadable(Exception e) {
        return respond(ErrorCode.VALIDATION_FAILED, "Request body or parameter could not be read",
                null);
    }

    // ---------------------------------------------------------------------------------------------
    // Concurrency and constraint collisions
    // ---------------------------------------------------------------------------------------------

    /**
     * Two requests raced and this one lost. A conflict rather than an error: the caller may safely
     * retry, and with the same idempotency key the retry will replay the winner's result.
     */
    @ExceptionHandler({OptimisticLockingFailureException.class,
            PessimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> handleLockFailure(Exception e) {
        log.warn("Lock contention while handling request: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONCURRENT_MODIFICATION",
                        "The wallet was modified concurrently; retry the request",
                        CorrelationId.getOrGenerate(), null));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrityViolation(
            DataIntegrityViolationException e) {
        // A uniqueness guard fired: a duplicate redemption, reward or ledger posting was refused by
        // the database. That is the constraint doing its job, so it is reported as a conflict.
        log.warn("Constraint violation while handling request: {}", e.getMostSpecificCause()
                .getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("DUPLICATE_REQUEST",
                        "The request conflicts with an operation that already happened",
                        CorrelationId.getOrGenerate(), null));
    }

    // ---------------------------------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unhandled exception while handling request", e);
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), null);
    }

    private static ResponseEntity<ErrorResponse> respond(ErrorCode code, String message,
            List<String> details) {
        return ResponseEntity.status(code.status())
                .body(new ErrorResponse(code.name(), message, CorrelationId.getOrGenerate(),
                        details));
    }
}
