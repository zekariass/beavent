/**
 * Identity: accounts, sign-in (one-time code to a phone or email, Google/Apple), sessions with refresh-token rotation,
 * step-up, devices and legal consents (docs/03 §2.5). Uses the {@code beavent_identity} database role only.
 *
 * <p>Public API: {@link com.beavent.backend.identity.Assurance} (step-up checks for sensitive actions in other
 * modules) and the {@link com.beavent.backend.identity.MembershipDirectory} SPI implemented by the tenancy module.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Identity")
package com.beavent.backend.identity;
