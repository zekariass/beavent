package com.beavent.backend.admin.internal;

import static com.beavent.db.iam.Tables.MFA_FACTORS;
import static com.beavent.db.iam.Tables.STAFF_ROLE_PERMISSIONS;
import static com.beavent.db.iam.Tables.USER_STAFF_ROLES;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Every {@code /v1/admin} request: admin-console token, multi-factor sign-in (authenticator app), active staff role.
 * Staff permissions are reloaded on each request, so revoking a role takes effect immediately.
 */
class StaffInterceptor implements HandlerInterceptor {

    private static final String BINDING = StaffInterceptor.class.getName() + ".binding";
    private final DSLContext system;

    StaffInterceptor(@SystemDb DSLContext system) {
        this.system = system;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ApiException(ProblemCode.UNAUTHENTICATED);
        }
        if (!jwt.getAudience().contains("ADMIN_CONSOLE")) {
            throw new ApiException(ProblemCode.FORBIDDEN, "Sign in to the admin console to use this API.");
        }
        UUID userId = UUID.fromString(jwt.getSubject());
        String assurance = jwt.getClaimAsString("aal");
        boolean multiFactor = "MFA".equals(assurance)
                || ("STEP_UP".equals(assurance)
                        && system.fetchExists(
                                MFA_FACTORS, MFA_FACTORS.USER_ID.eq(userId).and(MFA_FACTORS.CONFIRMED_AT.isNotNull())));
        if (!multiFactor) {
            throw new ApiException(
                    ProblemCode.FORBIDDEN,
                    "The admin console needs an authenticator app: set one up under your account, then sign in again.");
        }
        Map<String, Set<UUID>> permissions = new HashMap<>();
        system.select(STAFF_ROLE_PERMISSIONS.PERMISSION_CODE, USER_STAFF_ROLES.MARKET_ID)
                .from(USER_STAFF_ROLES)
                .join(STAFF_ROLE_PERMISSIONS)
                .on(STAFF_ROLE_PERMISSIONS.ROLE_CODE.eq(USER_STAFF_ROLES.ROLE_CODE))
                .where(USER_STAFF_ROLES.USER_ID.eq(userId), USER_STAFF_ROLES.REVOKED_AT.isNull())
                .and(USER_STAFF_ROLES.EXPIRES_AT.isNull().or(USER_STAFF_ROLES.EXPIRES_AT.gt(OffsetDateTime.now())))
                .forEach(row -> permissions
                        .computeIfAbsent(row.value1(), p -> new HashSet<>())
                        .add(row.value2() == null ? StaffAccess.ALL_MARKETS : row.value2()));
        if (permissions.isEmpty()) {
            throw new ApiException(ProblemCode.FORBIDDEN, "This account has no access to the admin console.");
        }
        request.setAttribute(BINDING, StaffAccess.bind(new StaffAccess(userId, permissions)));
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception)
            throws Exception {
        if (request.getAttribute(BINDING) instanceof AutoCloseable binding) {
            binding.close();
        }
    }
}
