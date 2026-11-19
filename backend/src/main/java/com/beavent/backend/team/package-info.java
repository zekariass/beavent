/**
 * Team (plan stage 1.2): team profiles (with or without a login), pricing levels, regular working hours (weekly or
 * multi-week rotations), one-off shifts and time off. Runs on the application role under row-level security.
 *
 * <p>Public API: {@link com.beavent.backend.team.Rota} — concrete working hours per person and day, used by the
 * rota screens and by availability (Stage 2).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Team")
package com.beavent.backend.team;
