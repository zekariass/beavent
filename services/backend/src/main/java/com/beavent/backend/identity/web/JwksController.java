package com.beavent.backend.identity.web;

import com.beavent.backend.identity.internal.JwksDocument;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class JwksController {

    private final JwksDocument jwks;

    JwksController(JwksDocument jwks) {
        this.jwks = jwks;
    }

    @GetMapping("/.well-known/jwks.json")
    ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(
                        CacheControl.maxAge(java.time.Duration.ofMinutes(10)).cachePublic())
                .body(jwks.publicKeys());
    }
}
