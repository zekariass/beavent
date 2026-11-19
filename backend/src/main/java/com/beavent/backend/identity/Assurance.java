package com.beavent.backend.identity;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * How strongly the current caller proved who they are (access-token claim {@code aal}). Sensitive actions — payout
 * account changes, large refunds, exports, member role changes — call {@link #requireStepUp()}.
 */
public enum Assurance {
    /** Signed in with a one-time code to a phone or email. */
    OTP,
    /** Signed in with Google or Apple. */
    PROVIDER,
    /** A fresh one-time code shortly before a sensitive action (short-lived token). */
    STEP_UP;

    public static final String CLAIM = "aal";
    public static final String SESSION_CLAIM = "sid";

    /** Assurance of the current request's access token, if authenticated. */
    public static Optional<Assurance> current() {
        return currentJwt().map(jwt -> valueOf(jwt.getClaimAsString(CLAIM)));
    }

    /** @throws ApiException {@link ProblemCode#STEP_UP_REQUIRED} unless the caller completed step-up recently */
    public static void requireStepUp() {
        if (current().orElse(null) != STEP_UP) {
            throw new ApiException(ProblemCode.STEP_UP_REQUIRED);
        }
    }

    /** True if the token was issued after an interactive sign-in within {@code maxAgeSeconds}. */
    public static boolean recentlyAuthenticated(long maxAgeSeconds) {
        return currentJwt()
                .map(Jwt::getIssuedAt)
                .map(issued -> issued.isAfter(Instant.now().minusSeconds(maxAgeSeconds)))
                .orElse(false);
    }

    static Optional<Jwt> currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return Optional.of(jwt);
        }
        return Optional.empty();
    }
}
