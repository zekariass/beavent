/**
 * Scheduling (plan stage 1.3, then Stage 2): booking policies now; availability and appointments next. Runs on the
 * application role under row-level security.
 *
 * <p>Public API: {@link com.beavent.backend.scheduling.BookingPolicies} — the policy that applies to a service at a
 * location (most specific wins); {@link com.beavent.backend.scheduling.Availability} — which times can be booked and
 * with whom.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Scheduling")
package com.beavent.backend.scheduling;
