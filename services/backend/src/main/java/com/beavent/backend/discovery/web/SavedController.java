package com.beavent.backend.discovery.web;

import com.beavent.backend.discovery.Saved;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in person's saved places (the Saved tab). */
@RestController
class SavedController {

    record SavedList(List<Saved.Entry> items) {}

    private final Saved saved;

    SavedController(Saved saved) {
        this.saved = saved;
    }

    @GetMapping("/v1/me/saved")
    SavedList list(@AuthenticationPrincipal Jwt jwt) {
        return new SavedList(saved.list(UUID.fromString(jwt.getSubject()), Instant.now()));
    }

    @PutMapping("/v1/me/saved/locations/{locationId}")
    ResponseEntity<Void> save(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID locationId) {
        saved.save(UUID.fromString(jwt.getSubject()), locationId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/v1/me/saved/locations/{locationId}")
    ResponseEntity<Void> unsave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID locationId) {
        saved.unsave(UUID.fromString(jwt.getSubject()), locationId);
        return ResponseEntity.noContent().build();
    }
}
