package com.beavent.backend.discovery.web;

import com.beavent.backend.discovery.Reviews;
import com.beavent.backend.tenancy.OrgAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A business reads the reviews of its places, answers them and reports the ones that break the rules. */
@RestController
class OrgReviewsController {

    static final String PERMISSION = "reviews.respond";

    record Answer(
            @NotBlank @Size(max = Reviews.MAX_RESPONSE) String body) {}

    record ReportInput(
            @NotBlank String reason, @Size(max = 500) String details) {}

    private final Reviews reviews;

    OrgReviewsController(Reviews reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/v1/orgs/{orgId}/reviews")
    Reviews.BusinessPage list(
            @PathVariable UUID orgId,
            @RequestParam(required = false) Boolean unanswered,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        OrgAccess access = OrgAccess.current().require(PERMISSION);
        int size = limit == null ? 20 : Math.max(1, Math.min(limit, 50));
        return reviews.ofOrganization(access.orgId(), Boolean.TRUE.equals(unanswered), cursor, size);
    }

    @PutMapping("/v1/orgs/{orgId}/reviews/{reviewId}/response")
    Reviews.ForBusiness respond(
            @PathVariable UUID orgId, @PathVariable UUID reviewId, @Valid @RequestBody Answer body) {
        OrgAccess access = OrgAccess.current().require(PERMISSION);
        return reviews.respond(access.orgId(), access.userId(), reviewId, body.body());
    }

    @DeleteMapping("/v1/orgs/{orgId}/reviews/{reviewId}/response")
    Reviews.ForBusiness removeResponse(@PathVariable UUID orgId, @PathVariable UUID reviewId) {
        OrgAccess access = OrgAccess.current().require(PERMISSION);
        return reviews.removeResponse(access.orgId(), access.userId(), reviewId);
    }

    @PostMapping("/v1/orgs/{orgId}/reviews/{reviewId}/report")
    Reviews.ForBusiness report(
            @PathVariable UUID orgId, @PathVariable UUID reviewId, @Valid @RequestBody ReportInput body) {
        OrgAccess access = OrgAccess.current().require(PERMISSION);
        return reviews.report(access.orgId(), access.userId(), reviewId, body.reason(), body.details());
    }
}
