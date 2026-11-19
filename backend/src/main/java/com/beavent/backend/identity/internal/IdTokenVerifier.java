package com.beavent.backend.identity.internal;

/** Verifies a Google/Apple ID token: signature, issuer, audience (our client ids), expiry and nonce. */
public interface IdTokenVerifier {

    record VerifiedIdentity(String provider, String subject, String email, boolean emailVerified) {}

    /** @throws com.beavent.backend.platform.ApiException UNAUTHENTICATED if invalid, NOT_FOUND if not enabled */
    VerifiedIdentity verify(String provider, String idToken, String nonce);
}
