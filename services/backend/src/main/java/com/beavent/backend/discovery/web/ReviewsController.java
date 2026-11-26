package com.beavent.backend.discovery.web;

import com.beavent.backend.discovery.Reviews;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Reviews as the public reads them, and as the person who writes them manages them. */
@RestController
class ReviewsController {

    record Input(
            @Min(1) @Max(5) int rating,
            @Size(max = Reviews.MAX_BODY) String body) {}

    private static final CacheControl CACHE =
            CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic();

    private final Reviews reviews;
    private final RateLimiter rateLimiter;

    ReviewsController(Reviews reviews, RateLimiter rateLimiter) {
        this.reviews = reviews;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/v1/public/businesses/{businessSlug}/reviews")
    ResponseEntity<Reviews.Page> ofBusiness(
            @PathVariable String businessSlug,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        rateLimiter.hit("reviews:ip:" + request.getRemoteAddr(), 240, Duration.ofMinutes(1));
        int size = limit == null ? 10 : Math.max(1, Math.min(limit, 50));
        Reviews.Page page = reviews.forBusiness(businessSlug, location, cursor, size);
        if (page == null) {
            throw new ApiException(ProblemCode.NOT_FOUND, "This business or place is not on Beavent.");
        }
        return ResponseEntity.ok().cacheControl(CACHE).body(page);
    }

    @GetMapping("/v1/me/reviews")
    Reviews.MyReviews mine(@AuthenticationPrincipal Jwt jwt) {
        return reviews.mine(UUID.fromString(jwt.getSubject()), Instant.now());
    }

    @PostMapping("/v1/me/appointments/{appointmentId}/review")
    @ResponseStatus(HttpStatus.CREATED)
    Reviews.Mine write(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID appointmentId, @Valid @RequestBody Input body) {
        return reviews.write(
                UUID.fromString(jwt.getSubject()), appointmentId, body.rating(), body.body(), Instant.now());
    }

    @PutMapping("/v1/me/reviews/{reviewId}")
    Reviews.Mine edit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID reviewId, @Valid @RequestBody Input body) {
        return reviews.edit(UUID.fromString(jwt.getSubject()), reviewId, body.rating(), body.body());
    }

    @DeleteMapping("/v1/me/reviews/{reviewId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID reviewId) {
        reviews.delete(UUID.fromString(jwt.getSubject()), reviewId);
        return ResponseEntity.noContent().build();
    }
}
