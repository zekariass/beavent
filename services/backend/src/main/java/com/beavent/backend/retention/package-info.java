/**
 * Retention (plan Stage 3, step 3.8): stored value a customer keeps with one business: gift cards and the customer's
 * balance (docs/04 §8). Value is scoped to one organization (no platform-wide wallet). A card is bought online, paid
 * through the provider like any order, issued with a code when it is delivered (at once or on a chosen date) and spent
 * as a payment method on a booking; a refund can be kept as credit instead of going back to the payer.
 *
 * <p>Public API: {@link com.beavent.backend.retention.GiftCards}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Retention")
package com.beavent.backend.retention;
