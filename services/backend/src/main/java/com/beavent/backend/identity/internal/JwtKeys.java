package com.beavent.backend.identity.internal;

import com.beavent.backend.identity.Assurance;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * ES256 signing key (current) plus retired public keys (rotation), the JWKS document, token signing and the
 * {@link JwtDecoder} the API uses to authenticate requests.
 */
@Configuration(proxyBeanMethods = false)
class JwtKeys {

    private static final Logger log = LoggerFactory.getLogger(JwtKeys.class);
    static final Set<String> AUDIENCES = Set.of("MOBILE_APP", "WEB_MARKETPLACE", "PARTNER_PORTAL", "ADMIN_CONSOLE");

    private final ECKey signingKey;
    private final JWKSet publicKeys;
    private final String issuer;

    JwtKeys(IdentityProperties properties) throws ParseException, JOSEException {
        this.issuer = properties.issuer();
        IdentityProperties.Jwt config = properties.jwt();
        String jwk = config == null ? null : config.signingJwk();
        if (jwk == null || jwk.isBlank()) {
            if (config == null || !config.allowEphemeralKey()) {
                throw new IllegalStateException("beavent.auth.jwt.signing-jwk is required");
            }
            log.warn("Using an ephemeral JWT signing key (local development only): tokens die on restart");
            this.signingKey = new ECKeyGenerator(Curve.P_256)
                    .keyID("ephemeral-" + UUID.randomUUID())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.ES256)
                    .generate();
        } else {
            this.signingKey = ECKey.parse(jwk);
        }
        if (!signingKey.isPrivate() || signingKey.getKeyID() == null) {
            throw new IllegalStateException("The signing JWK must be a private EC P-256 key with a kid");
        }
        List<JWK> keys = new ArrayList<>();
        keys.add(signingKey.toPublicJWK());
        if (config != null && config.previousPublicJwks() != null) {
            for (String previous : config.previousPublicJwks()) {
                keys.add(JWK.parse(previous).toPublicJWK());
            }
        }
        this.publicKeys = new JWKSet(keys);
    }

    /** Public keys for {@code /.well-known/jwks.json}. */
    Map<String, Object> jwks() {
        return publicKeys.toJSONObject(true);
    }

    String sign(
            UUID userId,
            UUID sessionFamilyId,
            String clientApp,
            Assurance assurance,
            Instant issuedAt,
            Instant expiresAt) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(userId.toString())
                .audience(clientApp)
                .claim(Assurance.SESSION_CLAIM, sessionFamilyId.toString())
                .claim(Assurance.CLAIM, assurance.name())
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256)
                        .keyID(signingKey.getKeyID())
                        .type(JOSEObjectType.JWT)
                        .build(),
                claims);
        try {
            jwt.sign(new ECDSASigner(signingKey));
        } catch (JOSEException e) {
            throw new IllegalStateException("JWT signing failed", e);
        }
        return jwt.serialize();
    }

    @Bean
    JwtDecoder jwtDecoder() {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(
                new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, new ImmutableJWKSet<>(publicKeys)));
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), JwtKeys::validateAudienceAndClaims));
        return decoder;
    }

    private static OAuth2TokenValidatorResult validateAudienceAndClaims(Jwt jwt) {
        boolean audienceOk =
                jwt.getAudience() != null && jwt.getAudience().stream().anyMatch(AUDIENCES::contains);
        boolean claimsOk =
                jwt.getClaimAsString(Assurance.SESSION_CLAIM) != null && jwt.getClaimAsString(Assurance.CLAIM) != null;
        return audienceOk && claimsOk
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Unexpected audience or claims", null));
    }
}
