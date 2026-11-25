package com.beavent.backend.payments.internal.provider;

/** Normalised failure codes (docs/04 §4). */
public enum FailureCode {
    INSUFFICIENT_FUNDS,
    CUSTOMER_DECLINED,
    CUSTOMER_TIMEOUT,
    LIMIT_EXCEEDED,
    INVALID_ACCOUNT,
    INVALID_REQUEST,
    DUPLICATE_REFERENCE,
    PROVIDER_UNAVAILABLE,
    RISK_DECLINED,
    UNKNOWN_ERROR
}
