package com.beavent.backend.identity.internal;

import java.util.Map;
import org.springframework.stereotype.Component;

/** Public signing keys for /.well-known/jwks.json. */
@Component
public class JwksDocument {

    private final JwtKeys keys;

    JwksDocument(JwtKeys keys) {
        this.keys = keys;
    }

    public Map<String, Object> publicKeys() {
        return keys.jwks();
    }
}
