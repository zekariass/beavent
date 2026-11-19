package com.beavent.backend.tenancy.web;

import com.beavent.backend.platform.CursorPage;
import com.beavent.backend.tenancy.OrgAccess;
import com.beavent.backend.tenancy.internal.Bodies;
import com.beavent.backend.tenancy.internal.Invitations;
import com.beavent.backend.tenancy.internal.Members;
import com.beavent.backend.tenancy.internal.Roles;
import com.beavent.backend.tenancy.internal.Verification;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Team access: roles, members, invitations, and verification cases (backend/src/main/openapi/tenancy). */
@RestController
class AccessController {

    static final String MEMBERS = "org.members.manage";

    private final Roles roles;
    private final Members members;
    private final Invitations invitations;
    private final Verification verification;

    AccessController(Roles roles, Members members, Invitations invitations, Verification verification) {
        this.roles = roles;
        this.members = members;
        this.invitations = invitations;
        this.verification = verification;
    }

    @GetMapping("/v1/orgs/{orgId}/roles")
    Bodies.Items<Bodies.Role> listRoles(@PathVariable UUID orgId) {
        OrgAccess.current().require(MEMBERS);
        return new Bodies.Items<>(roles.list());
    }

    @PostMapping("/v1/orgs/{orgId}/roles")
    ResponseEntity<Bodies.Role> createRole(@PathVariable UUID orgId, @Valid @RequestBody Bodies.RoleCreate body) {
        OrgAccess access = OrgAccess.current().require(MEMBERS);
        return ResponseEntity.status(HttpStatus.CREATED).body(roles.create(access, body));
    }

    @PatchMapping("/v1/orgs/{orgId}/roles/{roleId}")
    Bodies.Role updateRole(
            @PathVariable UUID orgId, @PathVariable UUID roleId, @Valid @RequestBody Bodies.RoleUpdate body) {
        return roles.update(OrgAccess.current().require(MEMBERS), roleId, body);
    }

    @DeleteMapping("/v1/orgs/{orgId}/roles/{roleId}")
    ResponseEntity<Void> deleteRole(@PathVariable UUID orgId, @PathVariable UUID roleId) {
        roles.delete(OrgAccess.current().require(MEMBERS), roleId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/v1/orgs/{orgId}/members")
    Bodies.Page<Bodies.Member> listMembers(
            @PathVariable UUID orgId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        OrgAccess access = OrgAccess.current().require(MEMBERS);
        CursorPage<Bodies.Member> page = members.list(access.orgId(), cursor, OrganizationsController.limit(limit));
        return new Bodies.Page<>(page.items(), new Bodies.PageInfo(page.nextCursor()));
    }

    @PatchMapping("/v1/orgs/{orgId}/members/{memberId}")
    Bodies.Member updateMember(
            @PathVariable UUID orgId, @PathVariable UUID memberId, @Valid @RequestBody Bodies.MemberUpdate body) {
        return members.update(OrgAccess.current().require(MEMBERS), memberId, body);
    }

    @DeleteMapping("/v1/orgs/{orgId}/members/{memberId}")
    ResponseEntity<Void> removeMember(@PathVariable UUID orgId, @PathVariable UUID memberId) {
        members.remove(OrgAccess.current().require(MEMBERS), memberId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/v1/orgs/{orgId}/invitations")
    Bodies.Items<Bodies.Invitation> listInvitations(@PathVariable UUID orgId) {
        OrgAccess.current().require(MEMBERS);
        return new Bodies.Items<>(invitations.list());
    }

    @PostMapping("/v1/orgs/{orgId}/invitations")
    ResponseEntity<Bodies.Invitation> invite(
            @PathVariable UUID orgId, @Valid @RequestBody Bodies.InvitationCreate body) {
        OrgAccess access = OrgAccess.current().require(MEMBERS);
        return ResponseEntity.status(HttpStatus.CREATED).body(invitations.create(access, body));
    }

    @DeleteMapping("/v1/orgs/{orgId}/invitations/{invitationId}")
    ResponseEntity<Void> revokeInvitation(@PathVariable UUID orgId, @PathVariable UUID invitationId) {
        invitations.revoke(OrgAccess.current().require(MEMBERS), invitationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/invitations/accept")
    Bodies.Member acceptInvitation(@Valid @RequestBody Bodies.InvitationAccept body, @AuthenticationPrincipal Jwt jwt) {
        return invitations.accept(UUID.fromString(jwt.getSubject()), body.token());
    }

    @GetMapping("/v1/orgs/{orgId}/verification-cases")
    Bodies.Items<Bodies.VerificationCase> listVerificationCases(@PathVariable UUID orgId) {
        OrgAccess.current().require(OrganizationsController.SETTINGS);
        return new Bodies.Items<>(verification.list());
    }

    @PostMapping("/v1/orgs/{orgId}/verification-cases")
    ResponseEntity<Bodies.VerificationCase> submitVerification(
            @PathVariable UUID orgId, @Valid @RequestBody Bodies.VerificationCaseCreate body) {
        OrgAccess access = OrgAccess.current().require(OrganizationsController.SETTINGS);
        return ResponseEntity.status(HttpStatus.CREATED).body(verification.submit(access, body));
    }

    @GetMapping("/v1/orgs/{orgId}/verification-cases/{caseId}")
    Bodies.VerificationCase getVerificationCase(@PathVariable UUID orgId, @PathVariable UUID caseId) {
        OrgAccess.current().require(OrganizationsController.SETTINGS);
        return verification.get(caseId);
    }
}
