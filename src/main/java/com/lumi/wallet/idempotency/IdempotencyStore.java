package com.lumi.wallet.idempotency;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.lumi.wallet.support.WalletClock;

/**
 * Transactional custody of {@link IdempotencyRecord} rows.
 *
 * <p>Separate from {@link IdempotencyService} because every method here must run in its
 * <em>own</em> transaction, and a self-invocation inside one bean would bypass the proxy and
 * silently join the caller's transaction instead. That distinction is the whole point: if the claim
 * shared the command's transaction, a failed command would roll the claim away too, and the
 * duplicate request that arrives a moment later would look like a first attempt.
 */
@Component
public class IdempotencyStore {

    private final IdempotencyRepository records;
    private final WalletClock clock;

    public IdempotencyStore(IdempotencyRepository records, WalletClock clock) {
        this.records = records;
        this.clock = clock;
    }

    /**
     * Inserts a fresh claim, committing immediately.
     *
     * <p>The existence check comes first, and the insert is <em>not</em> wrapped in a catch. A failed
     * flush marks the transaction rollback-only, so catching the constraint violation here and
     * returning normally would fail at commit instead — the recovery has to happen in a transaction
     * that is still usable, which means outside this method. On a genuine race the violation
     * propagates, this transaction rolls back cleanly, and the caller re-reads.
     *
     * @return the claim, or empty when the key is already taken
     * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent caller claimed
     *                                                                the key first
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<IdempotencyRecord> tryClaim(String key, String scope, String requestHash) {
        if (records.findByIdempotencyKey(key).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(records.saveAndFlush(
                IdempotencyRecord.started(key, scope, requestHash, clock.now())));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> find(String key) {
        return records.findByIdempotencyKey(key);
    }

    /**
     * Re-claims a previously failed key so a genuine retry can proceed.
     *
     * @return true when this caller won the re-claim
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryReclaimFailed(String id) {
        Optional<IdempotencyRecord> found = records.findById(id);
        if (found.isEmpty() || found.get().getStatus() != IdempotencyStatus.FAILED) {
            return false;
        }
        found.get().restart(clock.now());
        records.saveAndFlush(found.get());
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String id, int responseStatus, String responseBody, String resourceId) {
        Instant now = clock.now();
        records.findById(id).ifPresent(claim -> {
            claim.complete(responseStatus, responseBody, resourceId, now);
            records.saveAndFlush(claim);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String id) {
        Instant now = clock.now();
        records.findById(id).ifPresent(claim -> {
            claim.fail(now);
            records.saveAndFlush(claim);
        });
    }
}
