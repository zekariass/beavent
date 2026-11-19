package com.beavent.backend.identity.web;

import com.beavent.backend.identity.internal.ClientContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of the identity API (backend/src/main/openapi/identity/schemas.yaml). */
final class AuthDtos {

    private AuthDtos() {}

    static final String PHONE = "^\\+[1-9][0-9]{6,14}$";
    static final String CLIENT_APP = "MOBILE_APP|WEB_MARKETPLACE|PARTNER_PORTAL|ADMIN_CONSOLE";

    record DeviceInfo(
            @NotBlank @Size(max = 200) String installationId,
            @NotNull @Pattern(regexp = "IOS|ANDROID|WEB") String platform,
            @Size(max = 40) String appVersion) {
        ClientContext.Device toDevice() {
            return new ClientContext.Device(installationId, platform, appVersion);
        }
    }

    record OtpRequestBody(
            @Pattern(regexp = PHONE) String phone,
            @Email @Size(max = 200) String email,

            @NotNull @Pattern(regexp = "SIGN_IN|VERIFY_PHONE|STEP_UP|CLAIM_GUEST_ORDERS|CHANGE_PAYOUT_ACCOUNT")
            String purpose,

            @Pattern(regexp = "SMS|TELEGRAM|WHATSAPP|VOICE|EMAIL")
            String channel,

            @NotNull @Pattern(regexp = CLIENT_APP) String clientApp,
            String language) {}

    record OtpChallengeBody(UUID challengeId, Instant expiresAt, Instant resendAfter, String channel, int codeLength) {}

    record OtpVerifyBody(
            @NotNull UUID challengeId,
            @NotNull @Pattern(regexp = "^[0-9]{4,8}$") String code,
            @Valid DeviceInfo device) {}

    record OAuthLoginBody(
            @NotBlank String idToken,
            @NotBlank String nonce,
            @NotNull @Pattern(regexp = CLIENT_APP) String clientApp,
            @Valid DeviceInfo device) {}

    record RefreshBody(@NotBlank String refreshToken) {}

    record LogoutBody(String refreshToken, Boolean allSessions) {}

    record StepUpBody(
            @NotNull UUID challengeId,
            @NotNull @Pattern(regexp = "^[0-9]{4,8}$") String code) {}

    record MembershipBody(
            UUID orgId,
            String orgName,
            String orgSlug,
            String roleCode,
            String roleName,
            boolean isOwner,
            List<String> capabilities) {}

    record MeBody(
            UUID id,
            String phone,
            boolean phoneVerified,
            String email,
            boolean emailVerified,
            String status,
            String firstName,
            String lastName,
            String displayName,
            UUID avatarFileId,
            String countryCode,
            String preferredLanguage,
            String displayCalendar,
            String timezone,
            boolean isPlatformStaff,
            List<MembershipBody> memberships) {}

    record TokensBody(
            String result,
            String accessToken,
            Instant accessTokenExpiresAt,
            String refreshToken,
            Instant refreshTokenExpiresAt,
            boolean isNewUser,
            MeBody user) {}

    record SessionBody(
            UUID id,
            String clientApp,
            String deviceLabel,
            String ipAddress,
            Instant createdAt,
            Instant lastUsedAt,
            boolean current) {}

    record SessionListBody(List<SessionBody> items) {}

    record DeviceRegistrationBody(
            @NotBlank @Size(max = 200) String installationId,
            @NotNull @Pattern(regexp = "IOS|ANDROID|WEB") String platform,
            @Size(max = 4096) String pushToken,
            String appVersion,
            String locale) {}

    record ConsentAcceptanceBody(@NotEmpty List<@NotNull UUID> documentVersionIds) {}
}
