package com.beavent.backend.payments.internal.provider;

/**
 * Refund {@code amountMinor} of a successful collection through the provider's API.
 *
 * @param collectionReference our reference of the collection (Chapa tx_ref)
 * @param reference our unique refund reference (the refund id)
 */
public record RefundRequest(String collectionReference, String reference, long amountMinor, String reason) {}
