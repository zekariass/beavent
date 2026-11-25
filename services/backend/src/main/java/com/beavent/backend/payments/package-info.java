/**
 * Payments (plan Stage 3): online collection through the active provider, ArifPay by default or Chapa (docs/07 §1,
 * L1–L2), behind one adapter contract (docs/04 §4). The provider is chosen per deployment
 * ({@code beavent.payments.provider}); the collection mode follows it (ArifPay: Beavent collects and pays sellers out;
 * Chapa: split at source). Provider notifications are stored in {@code payments.webhook_events} before anything else.
 *
 * <p>No public API yet: deposits at booking (slice 3.3) add one.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Payments")
package com.beavent.backend.payments;
