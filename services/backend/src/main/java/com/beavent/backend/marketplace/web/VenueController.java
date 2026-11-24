package com.beavent.backend.marketplace.web;

import com.beavent.backend.marketplace.Venues;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.PublicEndpoints;
import com.beavent.backend.platform.RateLimiter;
import com.beavent.backend.platform.RequestContext;
import com.beavent.backend.tenancy.PublicLocations;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public venue pages (services/backend/src/main/openapi/marketplace): anyone with the link can read them. */
@RestController
@RequestMapping("/v1/public/venues")
class VenueController {

    record Locations(List<String> locationSlugs) {}

    private static final int PER_IP_PER_MINUTE = 240;
    // Short, because a business edits its page and expects to see the change; shared caches absorb a shared link.
    private static final CacheControl CACHE =
            CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic();

    private final PublicLocations locations;
    private final Venues venues;
    private final RateLimiter rateLimiter;

    VenueController(PublicLocations locations, Venues venues, RateLimiter rateLimiter) {
        this.locations = locations;
        this.venues = venues;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/{businessSlug}/locations")
    ResponseEntity<Locations> locationsOf(@PathVariable String businessSlug, HttpServletRequest request) {
        limit(request);
        List<String> slugs = locations.locationSlugs(businessSlug);
        if (slugs.isEmpty()) {
            throw notFound();
        }
        return ResponseEntity.ok().cacheControl(CACHE).body(new Locations(slugs));
    }

    @GetMapping("/{businessSlug}/{locationSlug}")
    ResponseEntity<Venues.Venue> venue(
            @PathVariable String businessSlug, @PathVariable String locationSlug, HttpServletRequest request) {
        limit(request);
        PublicLocations.Visible place =
                locations.findVisible(businessSlug, locationSlug).orElseThrow(VenueController::notFound);
        Venues.Venue venue = RequestContext.forOrganization(null, place.orgId())
                .call(() -> venues.venue(place.locationId(), place.onlineBooking(), place.listed(), Instant.now()))
                .orElseThrow(VenueController::notFound);
        return ResponseEntity.ok().cacheControl(CACHE).body(venue);
    }

    private void limit(HttpServletRequest request) {
        rateLimiter.hit("venue:ip:" + request.getRemoteAddr(), PER_IP_PER_MINUTE, Duration.ofMinutes(1));
    }

    private static ApiException notFound() {
        return new ApiException(ProblemCode.NOT_FOUND, "This place is not on Beavent.");
    }

    @Configuration(proxyBeanMethods = false)
    static class Endpoints {

        @Bean
        PublicEndpoints venuePublicEndpoints() {
            return () -> List.of("/v1/public/venues/**");
        }
    }
}
