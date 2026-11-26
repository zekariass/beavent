package com.beavent.backend.commerce;

import java.time.Instant;
import java.util.UUID;

/** Receipts with gapless numbers per legal entity (plan 4.1; delivery and printing are plan 4.3). */
public interface Receipts {

    record Receipt(UUID id, UUID orderId, String number, Instant issuedAt) {}

    /** The receipt of a paid order: issued now with the legal entity's next number, or the one issued before. */
    Receipt issue(UUID orgId, UUID orderId, Instant now);

    /** The order's receipt; null when none was issued. */
    Receipt find(UUID orgId, UUID orderId);
}
