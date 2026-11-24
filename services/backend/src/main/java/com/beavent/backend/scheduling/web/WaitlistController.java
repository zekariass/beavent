package com.beavent.backend.scheduling.web;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.RequestContext;
import com.beavent.backend.scheduling.internal.Waitlist;
import com.beavent.backend.tenancy.OrgAccess;
import com.beavent.backend.tenancy.PublicLocations;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The waitlist (plan step 2.6, S-15): joining, the person's entries and offers, and the front desk's list. */
@RestController
class WaitlistController {

    record JoinInput(
            @NotNull UUID locationId,
            @NotNull UUID variantId,
            UUID staffId,
            @NotEmpty @Size(max = 7) List<@NotNull LocalDate> days,
            @NotEmpty @Size(max = 3) List<@NotNull String> parts) {}

    record Created(UUID id) {}

    private final Waitlist waitlist;
    private final PublicLocations locations;

    WaitlistController(Waitlist waitlist, PublicLocations locations) {
        this.waitlist = waitlist;
        this.locations = locations;
    }

    private static UUID user(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    @PostMapping("/v1/me/waitlist")
    ResponseEntity<Created> join(@Valid @RequestBody JoinInput body, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = user(jwt);
        PublicLocations.Bookable place = locations
                .findBookable(body.locationId())
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "This location does not take bookings."));
        UUID id = RequestContext.forOrganization(userId, place.orgId())
                .call(() -> waitlist.join(
                        userId, place, body.variantId(), body.staffId(), body.days(), body.parts(), Instant.now()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new Created(id));
    }

    @GetMapping("/v1/me/waitlist")
    Waitlist.Entries mine(@AuthenticationPrincipal Jwt jwt) {
        return waitlist.mine(user(jwt));
    }

    @GetMapping("/v1/me/waitlist/{entryId}")
    Waitlist.EntryBody one(@PathVariable UUID entryId, @AuthenticationPrincipal Jwt jwt) {
        return waitlist.one(user(jwt), entryId);
    }

    @DeleteMapping("/v1/me/waitlist/{entryId}")
    ResponseEntity<Void> leave(@PathVariable UUID entryId, @AuthenticationPrincipal Jwt jwt) {
        waitlist.leave(user(jwt), entryId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/me/waitlist/{entryId}/accept")
    ResponseEntity<Waitlist.Accepted> accept(@PathVariable UUID entryId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(waitlist.accept(user(jwt), entryId, Instant.now()));
    }

    @PostMapping("/v1/me/waitlist/{entryId}/decline")
    ResponseEntity<Void> decline(@PathVariable UUID entryId, @AuthenticationPrincipal Jwt jwt) {
        waitlist.decline(user(jwt), entryId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/v1/orgs/{orgId}/waitlist")
    Waitlist.Entries desk(@PathVariable UUID orgId, @RequestParam UUID locationId) {
        return waitlist.forDesk(OrgAccess.current(), locationId);
    }
}
