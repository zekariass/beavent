package com.beavent.backend.identity;

import java.util.List;
import java.util.UUID;

/**
 * Organization memberships shown on {@code /v1/me}. Implemented by the tenancy module (which depends on identity,
 * not the other way round); without an implementation the list is empty.
 */
public interface MembershipDirectory {

    record Membership(
            UUID orgId,
            String orgName,
            String orgSlug,
            String roleCode,
            String roleName,
            boolean isOwner,
            List<String> capabilities) {}

    List<Membership> activeMemberships(UUID userId);
}
