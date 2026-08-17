package com.lumi.wallet.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.lumi.wallet.common.ErrorCode;
import com.lumi.wallet.common.WalletException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Wraps a state-changing command in {@code Idempotency-Key} semantics (HELP.md sections 39, 40).
 *
 * <pre>
 * same key + same request      -> return the original result
 * same key + different request -> 409 IDEMPOTENCY_CONFLICT
 * </pre>
 *
 * <p>The stored response is replayed rather than the command being re-run, which is what makes a
 * duplicate commit answer COMPLETED without a second ledger posting and a duplicate release answer
 * RELEASED without a second credit (section 40).
 *
 * <p>Note that this is the outer guard, not the only one. The database constraints underneath —
 * {@code uk_ledger_transaction_reference}, {@code uk_reward_transaction_dedupe},
 * {@code uk_processed_event_event_id} — still hold if a caller retries with a <em>different</em>
 * key, which is exactly what a broker redelivery or an upstream retry storm looks like.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs {@code command} at most once for {@code key}.
     *
     * @param key         the caller's {@code Idempotency-Key}
     * @param scope       the operation being performed, folded into the request fingerprint so that
     *                    one key cannot be reused across two different commands
     * @param request     the request body, fingerprinted to detect a conflicting reuse
     * @param responseType the response type, needed to rehydrate a replayed response
     * @param command     the command to run when this call owns the key
     */
    public <T> T execute(String key, String scope, Object request, Class<T> responseType,
            Supplier<T> command) {

        if (key == null || key.isBlank()) {
            // No key supplied: nothing to deduplicate against, so run the command as-is. The
            // controllers require the header, so this is only reachable from internal callers such
            // as the expiry scheduler, whose idempotency comes from the state machine instead.
            return command.get();
        }

        String requestHash = fingerprint(scope, request);
        ClaimOutcome outcome = claim(key, scope, requestHash);
        if (!outcome.owned()) {
            return replay(outcome.claim(), responseType);
        }

        try {
            T response = command.get();
            store.complete(outcome.claim().getId(), 200, serialize(response),
                    resourceIdOf(response));
            return response;
        } catch (RuntimeException e) {
            // Release the key so a transient failure does not lock the caller out permanently.
            store.fail(outcome.claim().getId());
            throw e;
        }
    }

    /**
     * Attempts to take ownership of the key, deciding between "run it" and "replay it".
     */
    private ClaimOutcome claim(String key, String scope, String requestHash) {
        Optional<IdempotencyRecord> fresh;
        try {
            fresh = store.tryClaim(key, scope, requestHash);
        } catch (DataIntegrityViolationException e) {
            // uk_idempotency_key: a concurrent caller claimed it between the check and the insert.
            // Its transaction has rolled back; fall through and read what the winner wrote.
            fresh = Optional.empty();
        }
        if (fresh.isPresent()) {
            return new ClaimOutcome(fresh.get(), true);
        }

        IdempotencyRecord existing = store.find(key)
                .orElseThrow(() -> new WalletException(ErrorCode.INTERNAL_ERROR,
                        "idempotency key " + key + " could neither be claimed nor read"));

        if (!existing.matches(requestHash)) {
            throw WalletException.of(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "idempotency key '%s' was already used with a different request", key);
        }

        return switch (existing.getStatus()) {
            case COMPLETED -> new ClaimOutcome(existing, false);
            case IN_PROGRESS -> throw WalletException.of(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                    "an identical request for key '%s' is still in progress", key);
            // A previous attempt failed. Exactly one caller may re-run it; anyone who loses that
            // race is a concurrent duplicate and is treated as such.
            case FAILED -> store.tryReclaimFailed(existing.getId())
                    ? new ClaimOutcome(existing, true)
                    : throwInProgress(key);
        };
    }

    private static ClaimOutcome throwInProgress(String key) {
        throw WalletException.of(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                "an identical request for key '%s' is still in progress", key);
    }

    /**
     * @param owned true when this caller must run the command, false when the stored response of an
     *              earlier identical request should be replayed
     */
    private record ClaimOutcome(IdempotencyRecord claim, boolean owned) {
    }

    private <T> T replay(IdempotencyRecord existing, Class<T> responseType) {
        log.info("Replaying stored response for idempotency key {} ({})",
                existing.getIdempotencyKey(), existing.getScope());
        if (existing.getResponseBody() == null) {
            throw WalletException.of(ErrorCode.INTERNAL_ERROR,
                    "idempotency key '%s' completed without a stored response",
                    existing.getIdempotencyKey());
        }
        try {
            return objectMapper.readValue(existing.getResponseBody(), responseType);
        } catch (JacksonException e) {
            throw new WalletException(ErrorCode.INTERNAL_ERROR,
                    "stored idempotent response could not be read: " + e.getMessage());
        }
    }

    /**
     * A stable fingerprint of the request, so that reusing a key with a changed body is detected.
     * Records serialise their components in declaration order, which makes this deterministic
     * without needing a canonicalising serialiser.
     */
    private String fingerprint(String scope, Object request) {
        String json = request == null ? "" : serialize(request);
        return sha256(scope + '|' + json);
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new WalletException(ErrorCode.INTERNAL_ERROR,
                    "request could not be serialised for idempotency: " + e.getMessage());
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    private static String resourceIdOf(Object response) {
        return response instanceof HasResourceId identified ? identified.resourceId() : null;
    }

    /**
     * Lets a response expose the id of the thing it created, so an operator can trace an
     * idempotency key back to the redemption it produced.
     */
    public interface HasResourceId {

        String resourceId();
    }
}
