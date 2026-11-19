package com.beavent.backend.files.web;

import com.beavent.backend.files.internal.FileBodies;
import com.beavent.backend.files.internal.Files;
import com.beavent.backend.tenancy.OrgAccess;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Files API (backend/src/main/openapi/files). */
@RestController
class FilesController {

    private final Files files;

    FilesController(Files files) {
        this.files = files;
    }

    @PostMapping("/v1/orgs/{orgId}/files")
    ResponseEntity<FileBodies.FileUpload> createUpload(
            @PathVariable UUID orgId, @Valid @RequestBody FileBodies.FileUploadCreate body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(files.createForOrganization(OrgAccess.current(), body));
    }

    @PostMapping("/v1/orgs/{orgId}/files/{fileId}/complete")
    FileBodies.File complete(@PathVariable UUID orgId, @PathVariable UUID fileId) {
        return files.complete(OrgAccess.current().orgId(), fileId);
    }

    @GetMapping("/v1/orgs/{orgId}/files/{fileId}")
    FileBodies.File get(@PathVariable UUID orgId, @PathVariable UUID fileId) {
        return files.get(OrgAccess.current().orgId(), fileId);
    }

    @PostMapping("/v1/orgs/{orgId}/files/{fileId}/access-url")
    FileBodies.AccessUrl accessUrl(@PathVariable UUID orgId, @PathVariable UUID fileId) {
        return files.accessUrl(OrgAccess.current(), fileId);
    }

    @PostMapping("/v1/me/files")
    ResponseEntity<FileBodies.FileUpload> createMyUpload(
            @Valid @RequestBody FileBodies.FileUploadCreate body, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(files.createForUser(UUID.fromString(jwt.getSubject()), body));
    }

    @PostMapping("/v1/me/files/{fileId}/complete")
    FileBodies.File completeMine(@PathVariable UUID fileId) {
        return files.complete(null, fileId);
    }

    @GetMapping("/v1/me/files/{fileId}")
    FileBodies.File getMine(@PathVariable UUID fileId) {
        return files.get(null, fileId);
    }
}
