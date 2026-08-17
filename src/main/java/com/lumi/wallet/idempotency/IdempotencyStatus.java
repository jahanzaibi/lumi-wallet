package com.lumi.wallet.idempotency;

public enum IdempotencyStatus {

    /** Claimed, but the command has not finished yet. A concurrent retry is rejected. */
    IN_PROGRESS,

    /** Finished; the stored response is replayed to any retry. */
    COMPLETED,

    /**
     * The command failed. The key is released for a genuine retry, because refusing to ever retry
     * a failed request would leave a customer permanently stuck on a transient error.
     */
    FAILED
}
