/**
 * Admin console backend (plan step 0.10): staff-only API under {@code /v1/admin}. Access needs an admin-console
 * token from a multi-factor sign-in and an active staff role; permissions are scoped to markets. Runs on the system
 * role (cross-organization) and filters by the staff member's markets itself; every change carries a reason and is
 * audited.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Admin console")
package com.beavent.backend.admin;
