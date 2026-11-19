package com.beavent.backend.tenancy.web;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.CursorPage;
import com.beavent.backend.platform.ETags;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.RateLimiter;
import com.beavent.backend.tenancy.OrgAccess;
import com.beavent.backend.tenancy.internal.Bodies;
import com.beavent.backend.tenancy.internal.LegalEntities;
import com.beavent.backend.tenancy.internal.Locations;
import com.beavent.backend.tenancy.internal.Organizations;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Organizations, legal entities and locations (backend/src/main/openapi/tenancy). */
@RestController
class OrganizationsController {

    static final String SETTINGS = "org.settings.manage";

    private final Organizations organizations;
    private final LegalEntities legalEntities;
    private final Locations locations;
    private final RateLimiter rateLimiter;

    OrganizationsController(
            Organizations organizations, LegalEntities legalEntities, Locations locations, RateLimiter rateLimiter) {
        this.organizations = organizations;
        this.legalEntities = legalEntities;
        this.locations = locations;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/v1/orgs")
    ResponseEntity<Bodies.Organization> create(
            @Valid @RequestBody Bodies.OrganizationCreate body, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        rateLimiter.hit("org-create:" + userId, 5, Duration.ofDays(1));
        return withETag(HttpStatus.CREATED, organizations.create(userId, body));
    }

    @GetMapping("/v1/orgs/{orgId}")
    ResponseEntity<Bodies.Organization> get(@PathVariable UUID orgId) {
        return withETag(HttpStatus.OK, organizations.get(OrgAccess.current().orgId()));
    }

    @PatchMapping("/v1/orgs/{orgId}")
    ResponseEntity<Bodies.Organization> update(
            @PathVariable UUID orgId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody JsonNode patch) {
        OrgAccess access = OrgAccess.current().require(SETTINGS);
        return withETag(HttpStatus.OK, organizations.update(access.orgId(), patch, ifMatch, access.userId()));
    }

    @PutMapping("/v1/orgs/{orgId}/capabilities")
    ResponseEntity<Bodies.Organization> setCapabilities(
            @PathVariable UUID orgId, @Valid @RequestBody Bodies.CapabilitiesUpdate body) {
        OrgAccess access = OrgAccess.current().require(SETTINGS);
        return withETag(
                HttpStatus.OK, organizations.setCapabilities(access.orgId(), body.capabilities(), access.userId()));
    }

    @GetMapping("/v1/orgs/{orgId}/legal-entities")
    Bodies.Items<Bodies.LegalEntity> listLegalEntities(@PathVariable UUID orgId) {
        OrgAccess.current().require(SETTINGS);
        return new Bodies.Items<>(legalEntities.list());
    }

    @PostMapping("/v1/orgs/{orgId}/legal-entities")
    ResponseEntity<Bodies.LegalEntity> createLegalEntity(
            @PathVariable UUID orgId, @Valid @RequestBody Bodies.LegalEntityInput body) {
        OrgAccess access = OrgAccess.current().require(SETTINGS);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(legalEntities.create(access.orgId(), body, access.userId()));
    }

    @PutMapping("/v1/orgs/{orgId}/legal-entities/{legalEntityId}")
    Bodies.LegalEntity updateLegalEntity(
            @PathVariable UUID orgId,
            @PathVariable UUID legalEntityId,
            @Valid @RequestBody Bodies.LegalEntityInput body) {
        OrgAccess access = OrgAccess.current().require(SETTINGS);
        return legalEntities.update(access.orgId(), legalEntityId, body, access.userId());
    }

    @GetMapping("/v1/orgs/{orgId}/locations")
    Bodies.Page<Bodies.Location> listLocations(
            @PathVariable UUID orgId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        CursorPage<Bodies.Location> page = locations.list(OrgAccess.current(), cursor, limit(limit));
        return new Bodies.Page<>(page.items(), new Bodies.PageInfo(page.nextCursor()));
    }

    @PostMapping("/v1/orgs/{orgId}/locations")
    ResponseEntity<Bodies.Location> createLocation(
            @PathVariable UUID orgId, @Valid @RequestBody Bodies.LocationInput body) {
        OrgAccess access = OrgAccess.current().require(SETTINGS);
        Bodies.Location location = locations.create(access.orgId(), body, access.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ETags.of(location.version()))
                .body(location);
    }

    @GetMapping("/v1/orgs/{orgId}/locations/{locationId}")
    ResponseEntity<Bodies.Location> getLocation(@PathVariable UUID orgId, @PathVariable UUID locationId) {
        Bodies.Location location = locations.get(OrgAccess.current(), locationId);
        return ResponseEntity.ok().eTag(ETags.of(location.version())).body(location);
    }

    @PutMapping("/v1/orgs/{orgId}/locations/{locationId}")
    ResponseEntity<Bodies.Location> updateLocation(
            @PathVariable UUID orgId,
            @PathVariable UUID locationId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody Bodies.LocationInput body) {
        OrgAccess access = OrgAccess.current().require(SETTINGS);
        Bodies.Location location = locations.update(access.orgId(), locationId, body, ifMatch, access.userId());
        return ResponseEntity.ok().eTag(ETags.of(location.version())).body(location);
    }

    static int limit(Integer limit) {
        if (limit == null) {
            return 25;
        }
        if (limit < 1 || limit > 100) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "limit must be between 1 and 100.");
        }
        return limit;
    }

    private static ResponseEntity<Bodies.Organization> withETag(HttpStatus status, Bodies.Organization organization) {
        return ResponseEntity.status(status)
                .eTag(ETags.of(organization.version()))
                .body(organization);
    }
}
