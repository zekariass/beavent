package com.beavent.backend.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.beavent.backend.ApiTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Identity API end to end (MockMvc → real PostGIS/Redis), every response validated against the OpenAPI contract. */
class IdentityApiTests extends ApiTestSupport {

    @Autowired
    Crypto crypto;

    // --- tests -----------------------------------------------------------------------------------------------

    @Test
    void phoneFirstSignInCreatesTheAccountOnceAndIssuesWorkingTokens() throws Exception {
        String phone = newPhone();
        Call first = signInWithOtp(phone);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("result").asString()).isEqualTo("TOKENS");
        assertThat(first.body().get("isNewUser").asBoolean()).isTrue();
        assertThat(first.body().get("user").get("countryCode").asString()).isEqualTo("ET");
        String access = first.body().get("accessToken").asString();
        assertThat(claims(json, access).get("aal").asString()).isEqualTo("OTP");
        assertThat(claims(json, access).get("aud").toString()).contains("MOBILE_APP");

        Call me = call(get("/v1/me"), null, access);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().get("phone").asString()).isEqualTo(phone);
        assertThat(me.body().get("phoneVerified").asBoolean()).isTrue();

        Call again = signInWithOtp(phone);
        assertThat(again.body().get("isNewUser").asBoolean()).isFalse();
        assertThat(again.body().get("user").get("id").asString())
                .isEqualTo(me.body().get("id").asString());
    }

    @Test
    void wrongCodesUseUpAttemptsAndTheCodeIsSingleUse() throws Exception {
        String phone = newPhone();
        String challengeId = requestCode(phone, "SIGN_IN", null);
        String code = lastCode(phone);
        for (int i = 0; i < 5; i++) {
            Call wrong = call(
                    post("/v1/auth/otp/verify"),
                    java.util.Map.of("challengeId", challengeId, "code", "000000".equals(code) ? "111111" : "000000"),
                    null);
            assertThat(wrong.status()).isEqualTo(401);
            assertThat(wrong.body().get("code").asString()).isEqualTo("UNAUTHENTICATED");
        }
        // Max attempts reached: even the right code no longer works.
        assertThat(call(post("/v1/auth/otp/verify"), java.util.Map.of("challengeId", challengeId, "code", code), null)
                        .status())
                .isEqualTo(401);
        // Destination is stored only as an HMAC; the code only as a hash.
        var row = system.selectFrom(com.beavent.db.iam.Tables.AUTH_CHALLENGES)
                .where(com.beavent.db.iam.Tables.AUTH_CHALLENGES.ID.eq(UUID.fromString(challengeId)))
                .fetchOne();
        assertThat(row.getDestinationHash())
                .isEqualTo(crypto.destinationHash(phone))
                .doesNotContain(phone.substring(1));
        assertThat(row.getCodeHash()).doesNotContain(code);
    }

    @Test
    void requestingAnotherCodeImmediatelyIsRateLimited() throws Exception {
        String phone = newPhone();
        requestCode(phone, "SIGN_IN", null);
        Call again = call(
                post("/v1/auth/otp/request"),
                java.util.Map.of("phone", phone, "purpose", "SIGN_IN", "clientApp", "WEB_MARKETPLACE"),
                null);
        assertThat(again.status()).isEqualTo(429);
        assertThat(again.body().get("code").asString()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void refreshRotatesAndReusingAnOldRefreshTokenRevokesTheWholeSession() throws Exception {
        Call signIn = signInWithOtp(newPhone());
        String refresh1 = signIn.body().get("refreshToken").asString();

        Call rotated = call(post("/v1/auth/refresh"), java.util.Map.of("refreshToken", refresh1), null);
        assertThat(rotated.status()).isEqualTo(200);
        String refresh2 = rotated.body().get("refreshToken").asString();
        assertThat(refresh2).isNotEqualTo(refresh1);

        // Someone replays the old token: treat as theft, revoke the family.
        assertThat(call(post("/v1/auth/refresh"), java.util.Map.of("refreshToken", refresh1), null)
                        .status())
                .isEqualTo(401);
        assertThat(call(post("/v1/auth/refresh"), java.util.Map.of("refreshToken", refresh2), null)
                        .status())
                .isEqualTo(401);
    }

    @Test
    void logoutEndsTheSessionAndSessionsCanBeListedAndRevoked() throws Exception {
        String phone = newPhone();
        Call phoneA = signInWithOtp(phone);
        Call phoneB = signInWithOtp(phone);
        String accessA = phoneA.body().get("accessToken").asString();

        Call list = call(get("/v1/me/sessions"), null, accessA);
        assertThat(list.body().get("items").size()).isEqualTo(2);
        String sidB = claims(json, phoneB.body().get("accessToken").asString())
                .get("sid")
                .asString();
        assertThat(call(delete("/v1/me/sessions/" + sidB), null, accessA).status())
                .isEqualTo(204);
        assertThat(call(
                                post("/v1/auth/refresh"),
                                java.util.Map.of(
                                        "refreshToken",
                                        phoneB.body().get("refreshToken").asString()),
                                null)
                        .status())
                .isEqualTo(401);

        assertThat(call(post("/v1/auth/logout"), java.util.Map.of(), accessA).status())
                .isEqualTo(204);
        assertThat(call(
                                post("/v1/auth/refresh"),
                                java.util.Map.of(
                                        "refreshToken",
                                        phoneA.body().get("refreshToken").asString()),
                                null)
                        .status())
                .isEqualTo(401);
    }

    @Test
    void passwordCanBeSetAfterARecentSignInAndLocksAfterRepeatedFailures() throws Exception {
        String phone = newPhone();
        String access = signInWithOtp(phone).body().get("accessToken").asString();
        assertThat(call(put("/v1/me/password"), java.util.Map.of("newPassword", "correct horse battery"), access)
                        .status())
                .isEqualTo(204);

        Call ok = call(
                post("/v1/auth/password"),
                java.util.Map.of(
                        "identifier", phone, "password", "correct horse battery", "clientApp", "PARTNER_PORTAL"),
                null);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(claims(json, ok.body().get("accessToken").asString())
                        .get("aal")
                        .asString())
                .isEqualTo("PASSWORD");
        assertThat(ok.body().get("user").get("hasPassword").asBoolean()).isTrue();

        for (int i = 0; i < 10; i++) {
            assertThat(call(
                                    post("/v1/auth/password"),
                                    java.util.Map.of(
                                            "identifier",
                                            phone,
                                            "password",
                                            "wrong-" + i,
                                            "clientApp",
                                            "PARTNER_PORTAL"),
                                    null)
                            .status())
                    .isEqualTo(401);
        }
        // Locked: the right password is refused until the lockout expires.
        assertThat(call(
                                post("/v1/auth/password"),
                                java.util.Map.of(
                                        "identifier",
                                        phone,
                                        "password",
                                        "correct horse battery",
                                        "clientApp",
                                        "PARTNER_PORTAL"),
                                null)
                        .status())
                .isEqualTo(401);
        // Unknown accounts look the same as wrong passwords.
        assertThat(call(
                                post("/v1/auth/password"),
                                java.util.Map.of(
                                        "identifier",
                                        newPhone(),
                                        "password",
                                        "whatever-123",
                                        "clientApp",
                                        "PARTNER_PORTAL"),
                                null)
                        .status())
                .isEqualTo(401);
    }

    @Test
    void authenticatorAppMakesSignInRequireASecondFactorAndCodesCannotBeReplayed() throws Exception {
        String phone = newPhone();
        String access = signInWithOtp(phone).body().get("accessToken").asString();
        Call enrollment = call(post("/v1/me/mfa/totp"), null, access);
        assertThat(enrollment.status()).isEqualTo(201);
        byte[] secret = Totp.fromBase32(enrollment.body().get("secret").asString());
        long step = Totp.step(Instant.now().getEpochSecond());
        assertThat(call(
                                post("/v1/me/mfa/totp/confirm"),
                                java.util.Map.of(
                                        "factorId",
                                        enrollment.body().get("factorId").asString(),
                                        "code",
                                        Totp.code(secret, step)),
                                access)
                        .status())
                .isEqualTo(204);

        Call pending = signInWithOtp(phone);
        assertThat(pending.body().get("result").asString()).isEqualTo("MFA_REQUIRED");
        String mfaChallenge = pending.body().get("mfaChallengeId").asString();
        // The code already used for confirmation is rejected (replay); the next step's code works.
        assertThat(call(
                                post("/v1/auth/mfa/verify"),
                                java.util.Map.of("mfaChallengeId", mfaChallenge, "code", Totp.code(secret, step)),
                                null)
                        .status())
                .isEqualTo(401);
        Call done = call(
                post("/v1/auth/mfa/verify"),
                java.util.Map.of("mfaChallengeId", mfaChallenge, "code", Totp.code(secret, step + 1)),
                null);
        assertThat(done.status()).isEqualTo(200);
        assertThat(claims(json, done.body().get("accessToken").asString())
                        .get("aal")
                        .asString())
                .isEqualTo("MFA");
        assertThat(done.body().get("user").get("mfaEnabled").asBoolean()).isTrue();
    }

    @Test
    void stepUpWithAFreshCodeIssuesAShortLivedStepUpToken() throws Exception {
        String phone = newPhone();
        String access = signInWithOtp(phone).body().get("accessToken").asString();
        // Request with a different number is refused.
        assertThat(call(
                                post("/v1/auth/otp/request"),
                                java.util.Map.of("phone", newPhone(), "purpose", "STEP_UP", "clientApp", "MOBILE_APP"),
                                access)
                        .status())
                .isEqualTo(400);
        String challengeId = requestCode(phone, "STEP_UP", access);
        Call stepUp = call(
                post("/v1/auth/step-up"),
                java.util.Map.of("method", "OTP", "challengeId", challengeId, "code", lastCode(phone)),
                access);
        assertThat(stepUp.status()).isEqualTo(200);
        JsonNode claims = claims(json, stepUp.body().get("accessToken").asString());
        assertThat(claims.get("aal").asString()).isEqualTo("STEP_UP");
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isLessThanOrEqualTo(300);
    }

    @Test
    void profileUpdatesUseMergeSemanticsAndConsentsAreRecorded() throws Exception {
        String access = signInWithOtp(newPhone()).body().get("accessToken").asString();
        Call updated = call(
                patch("/v1/me"),
                java.util.Map.of(
                        "firstName",
                        "Selam",
                        "lastName",
                        "Tesfaye",
                        "preferredLanguage",
                        "am",
                        "displayCalendar",
                        "ETHIOPIAN"),
                access);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().get("firstName").asString()).isEqualTo("Selam");
        assertThat(updated.body().get("displayCalendar").asString()).isEqualTo("ETHIOPIAN");
        Call cleared = call(patch("/v1/me"), "{\"lastName\": null}", access);
        assertThat(cleared.body().has("lastName")).isFalse();
        assertThat(cleared.body().get("firstName").asString()).isEqualTo("Selam");
        assertThat(call(patch("/v1/me"), java.util.Map.of("preferredLanguage", "xx"), access)
                        .status())
                .isEqualTo(422);

        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        system.insertInto(com.beavent.db.ref.Tables.LEGAL_DOCUMENTS)
                .set(com.beavent.db.ref.Tables.LEGAL_DOCUMENTS.ID, documentId)
                .set(com.beavent.db.ref.Tables.LEGAL_DOCUMENTS.CODE, "ACCEPTABLE_USE")
                .set(com.beavent.db.ref.Tables.LEGAL_DOCUMENTS.AUDIENCE, "ALL")
                .onConflictDoNothing()
                .execute();
        UUID existingDocument = system.select(com.beavent.db.ref.Tables.LEGAL_DOCUMENTS.ID)
                .from(com.beavent.db.ref.Tables.LEGAL_DOCUMENTS)
                .where(
                        com.beavent.db.ref.Tables.LEGAL_DOCUMENTS.CODE.eq("ACCEPTABLE_USE"),
                        com.beavent.db.ref.Tables.LEGAL_DOCUMENTS.MARKET_ID.isNull())
                .fetchOne(com.beavent.db.ref.Tables.LEGAL_DOCUMENTS.ID);
        system.insertInto(com.beavent.db.ref.Tables.LEGAL_DOCUMENT_VERSIONS)
                .set(com.beavent.db.ref.Tables.LEGAL_DOCUMENT_VERSIONS.ID, versionId)
                .set(com.beavent.db.ref.Tables.LEGAL_DOCUMENT_VERSIONS.DOCUMENT_ID, existingDocument)
                .set(com.beavent.db.ref.Tables.LEGAL_DOCUMENT_VERSIONS.VERSION, "test-" + versionId)
                .set(com.beavent.db.ref.Tables.LEGAL_DOCUMENT_VERSIONS.LANGUAGE_CODE, "en")
                .set(com.beavent.db.ref.Tables.LEGAL_DOCUMENT_VERSIONS.EFFECTIVE_AT, java.time.OffsetDateTime.now())
                .execute();
        assertThat(call(
                                post("/v1/me/consents"),
                                java.util.Map.of("documentVersionIds", java.util.List.of(versionId.toString())),
                                access)
                        .status())
                .isEqualTo(204);
        assertThat(call(
                                post("/v1/me/consents"),
                                java.util.Map.of(
                                        "documentVersionIds",
                                        java.util.List.of(UUID.randomUUID().toString())),
                                access)
                        .status())
                .isEqualTo(422);
        assertThat(call(
                                post("/v1/me/devices"),
                                java.util.Map.of(
                                        "installationId",
                                        "dev-" + versionId,
                                        "platform",
                                        "IOS",
                                        "pushToken",
                                        "push-" + versionId),
                                access)
                        .status())
                .isEqualTo(204);
    }

    @Test
    void suspendedAccountsCannotSignIn() throws Exception {
        String phone = newPhone();
        signInWithOtp(phone);
        system.update(com.beavent.db.iam.Tables.USERS)
                .set(com.beavent.db.iam.Tables.USERS.STATUS, "SUSPENDED")
                .where(com.beavent.db.iam.Tables.USERS.PHONE_E164.eq(phone))
                .execute();
        Call refused = signInWithOtp(phone);
        assertThat(refused.status()).isEqualTo(403);
    }

    @Test
    void forgedOrMissingTokensAreRejectedAndJwksIsPublic() throws Exception {
        assertThat(call(get("/v1/me"), null, null).status()).isEqualTo(401);
        String access = signInWithOtp(newPhone()).body().get("accessToken").asString();
        String[] parts = access.split("\\.");
        String forgedPayload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("{\"sub\":\"" + UUID.randomUUID()
                                + "\",\"aud\":\"MOBILE_APP\",\"sid\":\"x\",\"aal\":\"STEP_UP\",\"iss\":\"beavent\",\"exp\":9999999999}")
                        .getBytes(StandardCharsets.UTF_8));
        Call forged = call(get("/v1/me"), null, parts[0] + "." + forgedPayload + "." + parts[2]);
        assertThat(forged.status()).isEqualTo(401);
        assertThat(forged.body().get("code").asString()).isEqualTo("UNAUTHENTICATED");

        Call jwks = call(get("/.well-known/jwks.json"), null, null);
        assertThat(jwks.status()).isEqualTo(200);
        assertThat(jwks.body().get("keys").get(0).get("kty").asString()).isEqualTo("EC");
        assertThat(jwks.body().get("keys").get(0).has("d"))
                .as("no private key material")
                .isFalse();
    }

    @Test
    void providerSignInIsUnavailableUntilConfigured() throws Exception {
        Call result = call(
                post("/v1/auth/oauth/google"),
                java.util.Map.of("idToken", "x", "nonce", "n", "clientApp", "WEB_MARKETPLACE"),
                null);
        assertThat(result.status()).isEqualTo(404);
    }
}
