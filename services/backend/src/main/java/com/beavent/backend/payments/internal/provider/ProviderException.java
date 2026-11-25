package com.beavent.backend.payments.internal.provider;

/**
 * A provider call failed. {@code retryable} is true only when nothing can have been created on the provider's side
 * (connection refused, 5xx before acceptance); otherwise the caller looks the collection up by our reference first.
 */
public class ProviderException extends RuntimeException {

    private final FailureCode code;
    private final boolean retryable;

    public ProviderException(FailureCode code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public FailureCode code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
