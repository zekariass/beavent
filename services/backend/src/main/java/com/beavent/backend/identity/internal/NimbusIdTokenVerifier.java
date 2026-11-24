package com.beavent.backend.identity.internal;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Verifies provider ID tokens against the providers' published keys. A provider is enabled only when client ids
 * are configured ({@code beavent.auth.oauth.<provider>.client-ids}).
 */
@Component
class NimbusIdTokenVerifier implements IdTokenVerifier {

    record Provider(Set<String> issuers, Set<String> clientIds, JwtDecoder decoder) {}

    private final Map<String, Provider> providers;

    @Autowired
    NimbusIdTokenVerifier(IdentityProperties properties) {
        this(fromProperties(properties));
    }

    NimbusIdTokenVerifier(Map<String, Provider> providers) {
        this.providers = Map.copyOf(providers);
    }

    private static Map<String, Provider> fromProperties(IdentityProperties properties) {
        Map<String, Provider> configured = new HashMap<>();
        IdentityProperties.OAuth oauth = properties.oauth();
        if (oauth != null && enabled(oauth.google())) {
            configured.put(
                    "google",
                    new Provider(
                            Set.of("https://accounts.google.com", "accounts.google.com"),
                            Set.copyOf(oauth.google().clientIds()),
                            NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                                    .jwsAlgorithm(SignatureAlgorithm.RS256)
                                    .build()));
        }
        if (oauth != null && enabled(oauth.apple())) {
            configured.put(
                    "apple",
                    new Provider(
                            Set.of("https://appleid.apple.com"),
                            Set.copyOf(oauth.apple().clientIds()),
                            NimbusJwtDecoder.withJwkSetUri("https://appleid.apple.com/auth/keys")
                                    .jwsAlgorithm(SignatureAlgorithm.RS256)
                                    .build()));
        }
        return configured;
    }

    private static boolean enabled(IdentityProperties.Provider provider) {
        return provider != null
                && provider.clientIds() != null
                && !provider.clientIds().isEmpty();
    }

    @Override
    public VerifiedIdentity verify(String provider, String idToken, String nonce) {
        Provider config = providers.get(provider);
        if (config == null) {
            throw new ApiException(ProblemCode.NOT_FOUND, "Sign-in with " + provider + " is not enabled.");
        }
        Jwt jwt;
        try {
            jwt = config.decoder().decode(idToken); // signature + exp/nbf
        } catch (JwtException e) {
            throw invalid();
        }
        String issuer = jwt.getClaimAsString("iss");
        List<String> audience = jwt.getAudience();
        if (issuer == null
                || !config.issuers().contains(issuer)
                || audience == null
                || audience.stream().noneMatch(config.clientIds()::contains)) {
            throw invalid();
        }
        String tokenNonce = jwt.getClaimAsString("nonce");
        if (nonce == null
                || tokenNonce == null
                || !(Crypto.constantTimeEquals(tokenNonce, nonce)
                        || Crypto.constantTimeEquals(tokenNonce, sha256(nonce)))) {
            throw invalid();
        }
        Object verified = jwt.getClaims().get("email_verified");
        boolean emailVerified = Boolean.TRUE.equals(verified) || "true".equals(verified);
        return new VerifiedIdentity(provider, jwt.getSubject(), jwt.getClaimAsString("email"), emailVerified);
    }

    private static ApiException invalid() {
        return new ApiException(ProblemCode.UNAUTHENTICATED, "The sign-in token is not valid.");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
