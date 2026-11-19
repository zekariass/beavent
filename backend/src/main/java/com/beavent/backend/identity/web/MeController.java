package com.beavent.backend.identity.web;

import com.beavent.backend.identity.Assurance;
import com.beavent.backend.identity.internal.Mfa;
import com.beavent.backend.identity.internal.Passwords;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
class MeController {

    /** Setting the first password without the old one requires a sign-in at most this old (seconds). */
    static final long RECENT_SIGN_IN_SECONDS = 600;

    private final Profiles profiles;
    private final Passwords passwords;
    private final Sessions sessions;
    private final Mfa mfa;
    private final Audit audit;
    private final TransactionTemplate transactions;

    MeController(
            Profiles profiles,
            Passwords passwords,
            Sessions sessions,
            Mfa mfa,
            Audit audit,
            @IdentityDb TransactionTemplate transactions) {
        this.profiles = profiles;
        this.passwords = passwords;
        this.sessions = sessions;
        this.mfa = mfa;
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

    @PutMapping("/v1/me/password")
    ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordChangeBody body, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = userId(jwt);
        UUID familyId = UUID.fromString(jwt.getClaimAsString(Assurance.SESSION_CLAIM));
        transactions.executeWithoutResult(s -> {
            if (passwords.hasPassword(userId)) {
                if (!passwords.currentPasswordMatches(userId, body.currentPassword())) {
                    throw new ApiException(ProblemCode.FORBIDDEN, "The current password is incorrect.");
                }
            } else if (Assurance.current().orElse(null) != Assurance.STEP_UP
                    && !Assurance.recentlyAuthenticated(RECENT_SIGN_IN_SECONDS)) {
                throw new ApiException(ProblemCode.STEP_UP_REQUIRED);
            }
            passwords.set(userId, body.newPassword());
            sessions.revokeAll(userId, familyId, "PASSWORD_CHANGED");
            audit.userAction(userId, "user.password_changed", "iam.user", userId.toString());
        });
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/me/mfa/totp")
    ResponseEntity<TotpEnrollmentBody> enrollTotp(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = userId(jwt);
        Mfa.Enrollment enrollment = transactions.execute(s -> {
            var me = profiles.me(userId);
            String label = me.user().getPhoneE164() != null
                    ? me.user().getPhoneE164()
                    : String.valueOf(me.user().getEmail());
            return mfa.enroll(userId, label);
        });
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new TotpEnrollmentBody(enrollment.factorId(), enrollment.otpauthUri(), enrollment.secret()));
    }

    @PostMapping("/v1/me/mfa/totp/confirm")
    ResponseEntity<Void> confirmTotp(@Valid @RequestBody TotpConfirmBody body, @AuthenticationPrincipal Jwt jwt) {
        mfa.confirm(userId(jwt), body.factorId(), body.code());
        return ResponseEntity.noContent().build();
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
                view.mfaEnabled(),
                view.hasPassword(),
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
