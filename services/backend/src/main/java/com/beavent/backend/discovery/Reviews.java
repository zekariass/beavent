package com.beavent.backend.discovery;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Verified reviews of places (plan step 8.3): only a person with a completed appointment at the place can review it,
 * once per visit and within {@link #WINDOW_DAYS} days of it. The business may answer a review once and report one that
 * breaks the rules; platform staff hide or restore reviews. A place's average and count in search follow its published
 * reviews at once.
 */
public interface Reviews {

    /** A visit can be reviewed for this long after it ended. */
    int WINDOW_DAYS = 90;

    int MAX_BODY = 1000;
    int MAX_RESPONSE = 1000;

    /** A business's answer to a review. */
    record Response(String body, Instant at) {}

    /**
     * A published review as the public reads it. The author is shown by given name and the initial of the father's
     * name ("Hana T.").
     */
    record Review(
            UUID id,
            int rating,
            String body,
            String authorName,
            String locationSlug,
            String placeName,
            Map<String, String> serviceName,
            String staffName,
            LocalDate visitedOn,
            Instant createdAt,
            Response response) {}

    /** @param distribution how many reviews gave 1, 2, 3, 4 and 5 stars, in that order */
    record Summary(Double average, int count, List<Integer> distribution) {}

    record Page(Summary summary, List<Review> items, String nextCursor) {}

    /** A completed visit that has no review yet and can still get one. */
    record Pending(
            UUID appointmentId,
            UUID locationId,
            String businessSlug,
            String locationSlug,
            String placeName,
            Map<String, String> serviceName,
            String staffName,
            Instant visitedAt,
            Instant canReviewUntil) {}

    /** A person's own review, whatever its state ({@code PUBLISHED} or {@code HIDDEN} by staff). */
    record Mine(
            UUID id,
            UUID appointmentId,
            String businessSlug,
            String locationSlug,
            String placeName,
            Map<String, String> serviceName,
            int rating,
            String body,
            String status,
            Instant createdAt,
            Response response) {}

    record MyReviews(List<Pending> pending, List<Mine> reviews) {}

    /** A review in the business's list. */
    record ForBusiness(
            UUID id,
            int rating,
            String body,
            String authorName,
            UUID locationId,
            String placeName,
            Map<String, String> serviceName,
            String staffName,
            LocalDate visitedOn,
            Instant createdAt,
            String status,
            Response response,
            boolean reported) {}

    record BusinessPage(Summary summary, List<ForBusiness> items, String nextCursor) {}

    /** What staff see: a review, who wrote it for whom, and the reports against it. */
    record ForStaff(
            UUID id,
            UUID orgId,
            String businessName,
            String placeName,
            int rating,
            String body,
            String authorName,
            String status,
            Instant createdAt,
            Response response,
            List<Report> reports) {}

    record Report(UUID id, String reason, String details, String status, Instant at) {}

    enum Decision {
        /** Take the review down; it no longer counts. */
        HIDE,
        /** Put a hidden review back. */
        RESTORE,
        /** The reports are unfounded; the review stays. */
        DISMISS
    }

    // --- the public -------------------------------------------------------------------------------------------

    /**
     * Published reviews of a business, newest first; {@code locationSlug} narrows to one place. {@code null} when the
     * business or place does not exist.
     */
    Page forBusiness(String businessSlug, String locationSlug, String cursor, int limit);

    // --- the reviewer -----------------------------------------------------------------------------------------

    /** Visits waiting for a review, and the reviews written. */
    MyReviews mine(UUID userId, Instant now);

    /**
     * Writes the review of a completed visit.
     *
     * @throws com.beavent.backend.platform.ApiException {@code NOT_FOUND} when the visit is not the person's;
     *     {@code CONFLICT} when it is already reviewed; {@code CONSTRAINT_VIOLATION} when it was not completed or the
     *     window has passed
     */
    Mine write(UUID userId, UUID appointmentId, int rating, String body, Instant now);

    /** Changes the rating or text of the person's own published review; the business's answer stays. */
    Mine edit(UUID userId, UUID reviewId, int rating, String body);

    /** Deletes the person's own published review (a hidden one stays with staff). */
    void delete(UUID userId, UUID reviewId);

    // --- the business -----------------------------------------------------------------------------------------

    /** All reviews of the business, newest first; {@code unanswered} keeps the ones with no answer yet. */
    BusinessPage ofOrganization(UUID orgId, boolean unanswered, String cursor, int limit);

    /** Answers a published review, or replaces the answer. */
    ForBusiness respond(UUID orgId, UUID userId, UUID reviewId, String body);

    /** Takes the answer back. */
    ForBusiness removeResponse(UUID orgId, UUID userId, UUID reviewId);

    /** Asks staff to look at a review; reporting it again while a report is open changes nothing. */
    ForBusiness report(UUID orgId, UUID userId, UUID reviewId, String reason, String details);

    // --- staff ------------------------------------------------------------------------------------------------

    /** These reviews as staff see them, in the order given. */
    List<ForStaff> forStaff(List<UUID> reviewIds);

    /** The market of the business a review belongs to, or {@code null} when there is no such review. */
    UUID marketOf(UUID reviewId);

    /** Applies a moderation decision; every one is recorded with its reason. */
    ForStaff moderate(UUID reviewId, UUID staffUserId, Decision decision, String reason);
}
