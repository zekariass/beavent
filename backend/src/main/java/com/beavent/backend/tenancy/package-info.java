/**
 * Tenancy: organizations, capabilities, legal entities, locations, roles, members, invitations and business
 * verification (docs/03 §2.5, docs/05 §3). Uses the {@code beavent_app} role under row-level security; membership
 * checks and cross-organization lookups (invitation tokens, people's names) use the system role.
 *
 * <p>Public API: {@link com.beavent.backend.tenancy.OrgAccess} — the caller's membership in the organization of the
 * current {@code /v1/orgs/{orgId}/...} request, for permission and location checks in every partner module.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Tenancy")
package com.beavent.backend.tenancy;
