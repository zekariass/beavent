package com.beavent.backend.platform.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the bundled OpenAPI contract (packages/api-client/openapi.yaml, packaged at build time). The source of
 * truth is services/backend/src/main/openapi; clients are generated from the same bundle.
 */
@RestController
class ApiSpecController {

    static final String PATH = "/v1/openapi.yaml";
    private static final Resource SPEC = new ClassPathResource("openapi/beavent.yaml");

    @GetMapping(value = PATH, produces = "application/yaml")
    ResponseEntity<Resource> spec() {
        if (!SPEC.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/yaml"))
                .body(SPEC);
    }
}
