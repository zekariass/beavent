package com.beavent.backend.admin.internal;

import static com.beavent.db.tenant.Tables.MEMBERS;

import com.beavent.backend.discovery.Reviews;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * Reviews that businesses reported or staff took down (plan 8.3). Staff with {@code admin.moderation} in the market of
 * the business hide a review (it stops counting at once), put it back, or dismiss the reports; every decision needs a
 * reason and is recorded. Staff cannot decide about a business they belong to.
 */
@Component
public class ReviewModeration {

    static final String PERMISSION = "admin.moderation";
    private static final Field<UUID> MARKET = DSL.field("o.home_market_id", UUID.class);

    private final DSLContext system;
    private final Reviews reviews;

    ReviewModeration(@SystemDb DSLContext system, Reviews reviews) {
        this.system = system;
        this.reviews = reviews;
    }

    /** Reported reviews by default (oldest report first); {@code HIDDEN} lists the ones taken down. */
    public AdminBodies.Page<Reviews.ForStaff> queue(StaffAccess staff, String status, String cursor, int limit) {
        staff.require(PERMISSION);
        String wanted = status == null ? "REPORTED" : status;
        Condition condition = staff.marketFilter(PERMISSION, MARKET);
        switch (wanted) {
            case "REPORTED" -> condition = condition.and("EXISTS (SELECT 1 FROM trust.content_reports cr"
                    + " WHERE cr.review_id = r.id AND cr.status IN ('OPEN','IN_REVIEW'))");
            case "HIDDEN" -> condition = condition.and(DSL.field("r.status").eq("HIDDEN"));
            default -> throw new ApiException(ProblemCode.VALIDATION_FAILED, "Unknown status: " + status);
        }
        if (cursor != null) {
            condition = condition.and(DSL.field("r.id", UUID.class).gt(Directory.cursorId(cursor)));
        }
        List<UUID> ids = system.select(DSL.field("r.id", UUID.class))
                .from("discovery.reviews r JOIN tenant.organizations o ON o.id = r.org_id")
                .where(condition)
                .orderBy(DSL.field("r.id"))
                .limit(limit + 1)
                .fetch(0, UUID.class);
        List<Reviews.ForStaff> rows = reviews.forStaff(ids);
        return Directory.page(rows, limit, Reviews.ForStaff::id);
    }

    public Reviews.ForStaff decide(StaffAccess staff, UUID reviewId, Reviews.Decision decision, String reason) {
        UUID market = reviews.marketOf(reviewId);
        if (market == null) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such review.");
        }
        staff.requireInMarket(PERMISSION, market);
        UUID orgId = reviews.forStaff(List.of(reviewId)).getFirst().orgId();
        if (system.fetchExists(
                MEMBERS,
                MEMBERS.ORG_ID.eq(orgId).and(MEMBERS.USER_ID.eq(staff.userId())).and(MEMBERS.STATUS.ne("REMOVED")))) {
            throw new ApiException(
                    ProblemCode.FORBIDDEN, "You belong to this organization; another moderator must decide.");
        }
        return reviews.moderate(reviewId, staff.userId(), decision, reason.strip());
    }
}
