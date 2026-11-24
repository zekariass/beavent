/**
 * Catalogue (plan stage 1.1): service menu (groups, services, variants with stage templates and resource needs),
 * price overrides with most-specific-wins resolution, add-ons, bundles, resources and galleries. Runs on the
 * application role under row-level security.
 *
 * <p>Public API: {@link com.beavent.backend.catalog.ServicePricing} — the price and duration of a variant for a
 * location and team member, used by booking (Stage 2) and checkout.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Catalogue")
package com.beavent.backend.catalog;
