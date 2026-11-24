package com.beavent.backend.tenancy;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import java.util.Set;
import java.util.UUID;

/**
 * The caller's verified membership in the organization named by the request path ({@code /v1/orgs/{orgId}/...}).
 * Bound for the request by the tenancy module after it checked the membership; the database context is narrowed to
 * the same organization at the same time, so row-level security and these checks always agree.
 *
 * @param permissions organization permission codes of the member's role (owners hold every permission)
 * @param locationIds the locations the member may work in; ignored when {@code allLocations}
 */
public record OrgAccess(
        UUID orgId,
        UUID userId,
        UUID memberId,
        String roleCode,
        boolean isOwner,
        Set<String> permissions,
        boolean allLocations,
        Set<UUID> locationIds) {

    private static final ThreadLocal<OrgAccess> CURRENT = new ThreadLocal<>();

    public OrgAccess {
        permissions = Set.copyOf(permissions);
        locationIds = Set.copyOf(locationIds);
    }

    /**
     * @throws IllegalStateException outside a {@code /v1/orgs/{orgId}/...} request
     */
    public static OrgAccess current() {
        OrgAccess access = CURRENT.get();
        if (access == null) {
            throw new IllegalStateException("No organization access bound: not a /v1/orgs/{orgId}/... request");
        }
        return access;
    }

    public boolean can(String permission) {
        return isOwner || permissions.contains(permission);
    }

    /** @throws ApiException {@link ProblemCode#FORBIDDEN} unless the member holds {@code permission} */
    public OrgAccess require(String permission) {
        if (!can(permission)) {
            throw new ApiException(ProblemCode.FORBIDDEN, "Your role does not allow this (" + permission + ").");
        }
        return this;
    }

    public boolean canAccessLocation(UUID locationId) {
        return allLocations || isOwner || locationIds.contains(locationId);
    }

    /** True if the member holds every permission in {@code wanted} (used to stop privilege escalation). */
    public boolean holdsAll(Set<String> wanted) {
        return isOwner || permissions.containsAll(wanted);
    }

    /** Binds {@code access} to the current thread until the returned handle is closed. Tenancy module only. */
    public static Binding bind(OrgAccess access) {
        CURRENT.set(access);
        return CURRENT::remove;
    }

    @FunctionalInterface
    public interface Binding extends AutoCloseable {
        @Override
        void close();
    }
}
