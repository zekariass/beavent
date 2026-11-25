package com.beavent.backend.payments.internal.provider;

/** A refund the provider accepted; it completes later (notification or {@code queryRefund}). */
public record RefundResult(String providerReference, AttemptStatus status) {}
