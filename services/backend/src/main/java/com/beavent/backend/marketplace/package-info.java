/**
 * Marketplace: what the public sees of a business (plan step 2.2): the venue page for a shared link now, search and
 * listings in Stage 8. A read model over the catalogue, team and tenancy data, run in the business's context
 * (row-level security) from the business found by its public slug.
 *
 * <p>Public API: {@link com.beavent.backend.marketplace.Venues}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Marketplace")
package com.beavent.backend.marketplace;
