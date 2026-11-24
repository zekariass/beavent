package com.beavent.backend.identity.internal;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code beavent.auth.*} — secrets come from the secret manager outside local development. */
@ConfigurationProperties("beavent.auth")
public record IdentityProperties(
        @DefaultValue("beavent") String issuer,
        @DefaultValue("10m") Duration accessTokenTtl,
        @DefaultValue("5m") Duration stepUpTokenTtl,
        @DefaultValue("30d") Duration refreshTokenTtl,
        /** Admin-console sessions end sooner (no second factor; sensitive actions need a fresh code). */
        @DefaultValue("12h") Duration adminRefreshTokenTtl,
        /** Base64, ≥ 32 bytes. Keys OTP/destination HMACs and the authenticator-secret cipher. */
        String secretKey,
        @DefaultValue Jwt jwt,
        @DefaultValue Otp otp,
        @DefaultValue OAuth oauth) {

    public record Jwt(
            /** Private EC P-256 signing key as a JWK (JSON). */
            String signingJwk,
            /** Retired public JWKs still accepted (key rotation). */
            List<String> previousPublicJwks,
            /** Local development only: generate a throwaway key when none is configured. */
            @DefaultValue("false") boolean allowEphemeralKey) {}

    public record Otp(
            @DefaultValue("6") int codeLength,
            @DefaultValue("5m") Duration ttl,
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("30s") Duration resendAfter,
            @DefaultValue("5") int perPhonePerHour,
            @DefaultValue("30") int perIpPerHour) {}

    public record OAuth(
            @DefaultValue Provider google, @DefaultValue Provider apple) {}

    public record Provider(@DefaultValue List<String> clientIds) {}
}
