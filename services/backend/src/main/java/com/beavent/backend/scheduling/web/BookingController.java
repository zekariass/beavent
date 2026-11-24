package com.beavent.backend.scheduling.web;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.PublicEndpoints;
import com.beavent.backend.platform.RateLimiter;
import com.beavent.backend.platform.RequestContext;
import com.beavent.backend.scheduling.Availability;
import com.beavent.backend.scheduling.internal.BookingApi;
import com.beavent.backend.tenancy.PublicLocations;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Booking (services/backend/src/main/openapi/scheduling): anyone can hold a time for a few minutes; a signed-in person
 * turns the hold into an appointment; people see their own appointments.
 */
@RestController
class BookingController {

    record ItemBody(@NotNull UUID variantId, @Size(max = 10) List<UUID> addonIds, UUID staffId) {}

    record HoldBody(
            @NotEmpty @Size(max = 6) List<@Valid @NotNull ItemBody> items,
            @NotNull Instant start,
            UUID serviceAreaId) {}

    record ConfirmBody(
            @NotNull UUID locationId,
            @NotNull UUID holdId,
            @NotNull String holderKey,
            @Size(max = 500) String note) {}

    static final String HOLD_KEY_HEADER = "X-Hold-Key";

    private final PublicLocations locations;
    private final BookingApi bookings;
    private final RateLimiter rateLimiter;
    private final int holdsPerIpPerHour;

    BookingController(
            PublicLocations locations,
            BookingApi bookings,
            RateLimiter rateLimiter,
            @org.springframework.beans.factory.annotation.Value("${beavent.booking.holds-per-ip-per-hour:30}")
                    int holdsPerIpPerHour) {
        this.locations = locations;
        this.bookings = bookings;
        this.rateLimiter = rateLimiter;
        this.holdsPerIpPerHour = holdsPerIpPerHour;
    }

    private PublicLocations.Bookable bookable(UUID locationId) {
        return locations
                .findBookable(locationId)
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "This location does not take bookings."));
    }

    @PostMapping("/v1/public/locations/{locationId}/holds")
    ResponseEntity<BookingApi.HoldBody> hold(
            @PathVariable UUID locationId, @Valid @RequestBody HoldBody body, HttpServletRequest request) {
        rateLimiter.hit("hold:ip:" + request.getRemoteAddr(), holdsPerIpPerHour, Duration.ofHours(1));
        PublicLocations.Bookable place = bookable(locationId);
        List<Availability.Item> items = body.items().stream()
                .map(i -> new Availability.Item(i.variantId(), i.addonIds(), i.staffId()))
                .toList();
        BookingApi.HoldBody held = RequestContext.forOrganization(null, place.orgId())
                .call(() -> bookings.hold(
                        place.orgId(), locationId, items, body.serviceAreaId(), body.start(), Instant.now()));
        return ResponseEntity.status(HttpStatus.CREATED).body(held);
    }

    @DeleteMapping("/v1/public/locations/{locationId}/holds/{holdId}")
    ResponseEntity<Void> release(
            @PathVariable UUID locationId,
            @PathVariable UUID holdId,
            @RequestHeader(HOLD_KEY_HEADER) String holderKey) {
        PublicLocations.Bookable place = bookable(locationId);
        RequestContext.forOrganization(null, place.orgId()).run(() -> bookings.release(holdId, holderKey));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/me/appointments")
    ResponseEntity<BookingApi.AppointmentBody> confirm(
            @Valid @RequestBody ConfirmBody body, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        PublicLocations.Bookable place = bookable(body.locationId());
        BookingApi.AppointmentBody booked = RequestContext.forOrganization(userId, place.orgId())
                .call(() -> bookings.confirm(
                        place.orgId(), userId, body.holdId(), body.holderKey(), body.note(), Instant.now()));
        return ResponseEntity.status(booked.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(booked);
    }

    record CancelBody(@Size(max = 500) String reason) {}

    record RescheduleBody(@NotNull Instant start) {}

    /** The business of one of the caller's appointments (their consumer view decides what they may see). */
    private UUID orgOf(UUID userId, UUID appointmentId) {
        return RequestContext.forUser(userId)
                .call(() -> bookings.orgOf(appointmentId))
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "There is no such appointment."));
    }

    @GetMapping("/v1/me/appointments/{appointmentId}")
    BookingApi.DetailBody detail(@PathVariable UUID appointmentId, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID orgId = orgOf(userId, appointmentId);
        return RequestContext.forOrganization(userId, orgId)
                .call(() -> bookings.detail(userId, appointmentId, Instant.now(), locations));
    }

    @PostMapping("/v1/me/appointments/{appointmentId}/cancel")
    BookingApi.DetailBody cancel(
            @PathVariable UUID appointmentId,
            @Valid @RequestBody(required = false) CancelBody body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID orgId = orgOf(userId, appointmentId);
        String reason = body == null ? null : body.reason();
        return RequestContext.forOrganization(userId, orgId)
                .call(() -> bookings.cancel(orgId, userId, appointmentId, reason, Instant.now(), locations));
    }

    @PostMapping("/v1/me/appointments/{appointmentId}/reschedule")
    BookingApi.DetailBody reschedule(
            @PathVariable UUID appointmentId,
            @Valid @RequestBody RescheduleBody body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID orgId = orgOf(userId, appointmentId);
        return RequestContext.forOrganization(userId, orgId)
                .call(() -> bookings.reschedule(orgId, userId, appointmentId, body.start(), Instant.now(), locations));
    }

    @GetMapping("/v1/me/appointments")
    BookingApi.MineList mine(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return RequestContext.forUser(userId).call(() -> bookings.mine(locations));
    }

    @Configuration(proxyBeanMethods = false)
    static class Endpoints {

        @Bean
        PublicEndpoints bookingPublicEndpoints() {
            return () -> List.of("/v1/public/locations/*/holds", "/v1/public/locations/*/holds/*");
        }
    }
}
