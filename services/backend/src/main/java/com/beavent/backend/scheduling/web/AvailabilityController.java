package com.beavent.backend.scheduling.web;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.PublicEndpoints;
import com.beavent.backend.platform.RateLimiter;
import com.beavent.backend.platform.RequestContext;
import com.beavent.backend.scheduling.Availability;
import com.beavent.backend.tenancy.PublicLocations;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public availability (services/backend/src/main/openapi/scheduling): anyone with the link can ask which times can be
 * booked. The business is found from the location; the read runs in that business's context.
 */
@RestController
@RequestMapping("/v1/public/locations/{locationId}")
class AvailabilityController {

    record ItemBody(@NotNull UUID variantId, @Size(max = 10) List<UUID> addonIds, UUID staffId) {}

    record QueryBody(
            @NotEmpty @Size(max = 6) List<@Valid @NotNull ItemBody> items,
            @NotNull LocalDate from,
            @NotNull LocalDate to,
            UUID serviceAreaId) {}

    record AssignmentBody(UUID variantId, UUID staffId) {}

    record SlotBody(Instant start, Instant end, List<AssignmentBody> items) {}

    record ResultBody(String timezone, List<SlotBody> slots) {}

    private static final int PER_IP_PER_MINUTE = 120;

    private final PublicLocations locations;
    private final Availability availability;
    private final RateLimiter rateLimiter;

    AvailabilityController(PublicLocations locations, Availability availability, RateLimiter rateLimiter) {
        this.locations = locations;
        this.availability = availability;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/availability")
    ResultBody search(@PathVariable UUID locationId, @Valid @RequestBody QueryBody body, HttpServletRequest request) {
        rateLimiter.hit("availability:ip:" + request.getRemoteAddr(), PER_IP_PER_MINUTE, Duration.ofMinutes(1));
        PublicLocations.Bookable place = locations
                .findBookable(locationId)
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "This location does not take bookings."));
        Availability.Query query = new Availability.Query(
                locationId,
                body.items().stream()
                        .map(i -> new Availability.Item(i.variantId(), i.addonIds(), i.staffId()))
                        .toList(),
                body.from(),
                body.to(),
                body.serviceAreaId());
        Availability.Result result =
                RequestContext.forOrganization(null, place.orgId()).call(() -> availability.find(query, Instant.now()));
        return new ResultBody(
                result.zone().getId(),
                result.slots().stream()
                        .map(o -> new SlotBody(
                                o.start(),
                                o.end(),
                                o.items().stream()
                                        .map(a -> new AssignmentBody(a.variantId(), a.staffId()))
                                        .toList()))
                        .toList());
    }

    @Configuration(proxyBeanMethods = false)
    static class Endpoints {

        @Bean
        PublicEndpoints availabilityPublicEndpoints() {
            return () -> List.of("/v1/public/locations/*/availability");
        }
    }
}
