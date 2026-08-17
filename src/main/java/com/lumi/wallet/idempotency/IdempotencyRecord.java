package com.lumi.wallet.idempotency;

import java.time.Instant;

import com.lumi.wallet.common.Ids;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * A stored API outcome, keyed by the caller's {@code Idempotency-Key} (HELP.md section 39).
 *
 * <p>The stored response body is what makes a retry return the <em>original</em> result rather than
 * re-running the command: a duplicate commit answers COMPLETED without a second ledger posting
 * (section 40).
 */
@Entity
@Table(name = "idempotency_key")
public class IdempotencyRecord {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    /** Which operation the key was used for, for diagnostics and for clearer conflict messages. */
    @Column(name = "scope", nullable = false, length = 100)
    private String scope;

    /** Fingerprint of the request, so the same key with a different body can be rejected. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Lob
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "resource_id", length = 64)
    private String resourceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected IdempotencyRecord() {
    }

    private IdempotencyRecord(String idempotencyKey, String scope, String requestHash,
            Instant now) {
        this.id = Ids.newId("IDK");
        this.idempotencyKey = idempotencyKey;
        this.scope = scope;
        this.requestHash = requestHash;
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.createdAt = now;
    }

    public static IdempotencyRecord started(String idempotencyKey, String scope,
            String requestHash, Instant now) {
        return new IdempotencyRecord(idempotencyKey, scope, requestHash, now);
    }

    public void complete(int responseStatus, String responseBody, String resourceId, Instant now) {
        this.status = IdempotencyStatus.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.resourceId = resourceId;
        this.completedAt = now;
    }

    public void fail(Instant now) {
        this.status = IdempotencyStatus.FAILED;
        this.completedAt = now;
    }

    /**
     * Puts a previously failed key back in progress so a genuine retry can run. Only a FAILED key
     * may be restarted: a COMPLETED one replays its stored response instead, which is what stops a
     * duplicate commit from posting to the ledger twice (HELP.md section 40).
     */
    public void restart(Instant now) {
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.createdAt = now;
        this.completedAt = null;
        this.responseStatus = null;
        this.responseBody = null;
        this.resourceId = null;
    }

    public boolean isCompleted() {
        return status == IdempotencyStatus.COMPLETED;
    }

    public boolean isInProgress() {
        return status == IdempotencyStatus.IN_PROGRESS;
    }

    public boolean matches(String requestHash) {
        return this.requestHash.equals(requestHash);
    }

    public String getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getScope() {
        return scope;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public String getResourceId() {
        return resourceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
