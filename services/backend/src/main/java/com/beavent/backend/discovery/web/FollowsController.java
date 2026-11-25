package com.beavent.backend.discovery.web;

import com.beavent.backend.discovery.Follows;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The businesses the signed-in person follows. */
@RestController
class FollowsController {

    record FollowedList(List<Follows.Followed> items) {}

    record Input(Boolean notifications) {}

    private final Follows follows;

    FollowsController(Follows follows) {
        this.follows = follows;
    }

    @GetMapping("/v1/me/follows")
    FollowedList list(@AuthenticationPrincipal Jwt jwt) {
        return new FollowedList(follows.list(UUID.fromString(jwt.getSubject())));
    }

    @PutMapping("/v1/me/follows/{businessSlug}")
    Follows.Followed follow(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String businessSlug,
            @RequestBody(required = false) Input body) {
        boolean notifications = body == null || body.notifications() == null || body.notifications();
        return follows.follow(UUID.fromString(jwt.getSubject()), businessSlug, notifications);
    }

    @DeleteMapping("/v1/me/follows/{businessSlug}")
    ResponseEntity<Void> unfollow(@AuthenticationPrincipal Jwt jwt, @PathVariable String businessSlug) {
        follows.unfollow(UUID.fromString(jwt.getSubject()), businessSlug);
        return ResponseEntity.noContent().build();
    }
}
