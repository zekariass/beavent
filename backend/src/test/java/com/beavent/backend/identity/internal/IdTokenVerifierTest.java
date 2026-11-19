package com.beavent.backend.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beavent.backend.platform.ApiException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class IdTokenVerifierTest {

    @Test
    void verifiesGoogleStyleIdTokensIssuerAudienceAndNonce() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("k1").generate();
        NimbusIdTokenVerifier verifier = new NimbusIdTokenVerifier(Map.of(
                "google",
                new NimbusIdTokenVerifier.Provider(
                        Set.of("https://accounts.google.com"),
                        Set.of("client-1"),
                        NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build())));

        String token = sign(key, "https://accounts.google.com", "client-1", "nonce-1");
        var identity = verifier.verify("google", token, "nonce-1");
        assertThat(identity.subject()).isEqualTo("google-subject-1");
        assertThat(identity.emailVerified()).isTrue();

        assertThatThrownBy(() -> verifier.verify("google", token, "other-nonce"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() ->
                        verifier.verify("google", sign(key, "https://evil.example", "client-1", "nonce-1"), "nonce-1"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> verifier.verify(
                        "google", sign(key, "https://accounts.google.com", "someone-else", "nonce-1"), "nonce-1"))
                .isInstanceOf(ApiException.class);
        RSAKey attacker = new RSAKeyGenerator(2048).keyID("k1").generate();
        assertThatThrownBy(() -> verifier.verify(
                        "google", sign(attacker, "https://accounts.google.com", "client-1", "nonce-1"), "nonce-1"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> verifier.verify("apple", token, "nonce-1")).isInstanceOf(ApiException.class);
    }

    private static String sign(RSAKey key, String issuer, String audience, String nonce) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .audience(audience)
                        .subject("google-subject-1")
                        .claim("nonce", nonce)
                        .claim("email", "selam@example.com")
                        .claim("email_verified", true)
                        .issueTime(new Date())
                        .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                        .build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
