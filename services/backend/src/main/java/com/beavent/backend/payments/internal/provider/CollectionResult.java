package com.beavent.backend.payments.internal.provider;

import java.net.URI;

/**
 * A collection the provider accepted.
 *
 * @param providerReference the provider's id for it (ArifPay session id, Chapa tx_ref)
 * @param redirectUrl the hosted page to send the payer to (REDIRECT), or null
 */
public record CollectionResult(String providerReference, AttemptStatus status, URI redirectUrl) {}
