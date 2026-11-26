package com.beavent.backend.identity.web;

import com.beavent.backend.identity.Assurance;
import com.beavent.backend.identity.internal.Profiles;
import com.beavent.backend.identity.internal.Sessions;
import com.beavent.backend.identity.web.AuthDtos.*;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.IdentityDb;
import com.beavent.backend.platform.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
class MeController {

    private final Profiles profiles;
    private final Sessions sessions;
    private final Audit audit;
    private final TransactionTemplate transactions;

    MeController(Profiles profiles, Sessions sessions, Audit audit, @IdentityDb TransactionTemplate transactions) {
        this.profiles = profiles;
        this.sessions = sessions;
        this.audit = audit;
        this.transactions = transactions;
    }

    @GetMapping("/v1/me")
    MeBody getMe(@AuthenticationPrincipal Jwt jwt) {
        return transactions.execute(s -> toBody(profiles.me(userId(jwt))));
    }

    @PatchMapping("/v1/me")
    MeBody updateMe(@RequestBody JsonNode patch, @AuthenticationPrincipal Jwt jwt) {
        if (patch == null || !patch.isObject() || patch.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Send at least one field to change.");
        }
        UUID userId = userId(jwt);
        return transactions.execute(s -> {
            profiles.update(userId, patch);
            return toBody(profiles.me(userId));
        });
    }

    @GetMapping("/v1/me/sessions")
    SessionListBody listSessions(@AuthenticationPrincipal Jwt jwt) {
        UUID current = UUID.fromString(jwt.getClaimAsString(Assurance.SESSION_CLAIM));
        return transactions.execute(s -> new SessionListBody(sessions.active(userId(jwt)).stream()
                .map(v -> new SessionBody(
                        v.familyId(),
                        v.clientApp(),
                        v.deviceLabel(),
                        v.ipAddress(),
                        v.createdAt().toInstant(),
                        v.lastUsedAt() == null ? null : v.lastUsedAt().toInstant(),
                        v.familyId().equals(current)))
                .toList()));
    }

    @DeleteMapping("/v1/me/sessions/{sessionId}")
    ResponseEntity<Void> revokeSession(@PathVariable UUID sessionId, @AuthenticationPrincipal Jwt jwt) {
        Boolean revoked = transactions.execute(s -> sessions.revokeOwnFamily(userId(jwt), sessionId));
        if (!Boolean.TRUE.equals(revoked)) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/me/devices")
    ResponseEntity<Void> registerDevice(
            @Valid @RequestBody DeviceRegistrationBody body, @AuthenticationPrincipal Jwt jwt) {
        transactions.executeWithoutResult(s -> sessions.registerDevice(
                userId(jwt),
                body.installationId(),
                body.platform(),
                body.pushToken(),
                body.appVersion(),
                body.locale()));
        return ResponseEntity.noContent().build();
    }

    /** Signing out of a phone (or turning its notifications off): it no longer gets pushes for this person. */
    @org.springframework.web.bind.annotation.DeleteMapping("/v1/me/devices/{installationId}")
    ResponseEntity<Void> unregisterDevice(@PathVariable String installationId, @AuthenticationPrincipal Jwt jwt) {
        transactions.executeWithoutResult(s -> sessions.unregisterDevice(userId(jwt), installationId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/me/consents")
    ResponseEntity<Void> acceptLegalDocuments(
            @Valid @RequestBody ConsentAcceptanceBody body,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        String clientApp =
                jwt.getAudience().isEmpty() ? null : jwt.getAudience().getFirst();
        transactions.executeWithoutResult(s ->
                profiles.acceptDocuments(userId(jwt), body.documentVersionIds(), clientApp, request.getRemoteAddr()));
        return ResponseEntity.noContent().build();
    }

    static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    static MeBody toBody(Profiles.MeView view) {
        var u = view.user();
        var p = view.profile();
        return new MeBody(
                u.getId(),
                u.getPhoneE164(),
                u.getPhoneVerifiedAt() != null,
                u.getEmail(),
                u.getEmailVerifiedAt() != null,
                u.getStatus(),
                p.getFirstName(),
                p.getLastName(),
                p.getDisplayName(),
                p.getAvatarFileId(),
                u.getCountryCode(),
                u.getPreferredLanguage(),
                u.getDisplayCalendar(),
                u.getTimezone(),
                view.platformStaff(),
                view.memberships().stream()
                        .map(m -> new MembershipBody(
                                m.orgId(),
                                m.orgName(),
                                m.orgSlug(),
                                m.roleCode(),
                                m.roleName(),
                                m.isOwner(),
                                m.capabilities()))
                        .toList());
    }
}
