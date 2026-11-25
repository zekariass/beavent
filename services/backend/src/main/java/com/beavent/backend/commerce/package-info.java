/**
 * Commerce (plan Stage 3, step 3.2): one order model for everything sold (docs/04 §2), with the tax snapshot per line
 * (§12), promotions and promo codes, the platform's fees from billing, and gapless order numbers per legal entity.
 * The arithmetic the database enforces on lines and headers is computed here first, in pure engines.
 *
 * <p>Public API: {@link com.beavent.backend.commerce.Orders}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Commerce")
package com.beavent.backend.commerce;
