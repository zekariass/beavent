package com.beavent.backend.payments.internal.provider;

/** Normalised attempt states (docs/04 §4); raw provider statuses are kept beside them for support. */
public enum AttemptStatus {
    CREATED,
    AWAITING_CUSTOMER,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED,
    UNKNOWN;

    /** A final state: no further notification or lookup can change it (late successes are handled separately). */
    public boolean isFinal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == EXPIRED;
    }
}
