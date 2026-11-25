package com.beavent.backend.admin.internal;

import static com.beavent.db.catalog.Tables.SERVICES;
import static com.beavent.db.ref.Tables.MARKETS;
import static com.beavent.db.tenant.Tables.LOCATIONS;
import static com.beavent.db.tenant.Tables.MEMBERS;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;

import com.beavent.backend.discovery.Marketplace;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * Businesses asking to be on the marketplace (plan 8.1). Staff with {@code admin.marketplace.review} in the business's
 * market list them, send the request back with a reason, or take a listed business off. The reason is shown to the
 * business; staff cannot decide about a business they belong to. Decisions are audited by the marketplace itself.
 */
@Component
public class MarketplaceReview {

    static final String REVIEW = "admin.marketplace.review";
    private static final List<String> STATUSES = List.of("HIDDEN", "PENDING_REVIEW", "LISTED", "DELISTED");

    private static final Field<Integer> LOCATION_COUNT = DSL.field(DSL.selectCount()
                    .from(LOCATIONS)
                    .where(LOCATIONS.ORG_ID.eq(ORGANIZATIONS.ID), LOCATIONS.STATUS.eq("ACTIVE")))
            .as("location_count");
    private static final Field<Integer> SERVICE_COUNT = DSL.field(DSL.selectCount()
                    .from(SERVICES)
                    .where(
                            SERVICES.ORG_ID.eq(ORGANIZATIONS.ID),
                            SERVICES.STATUS.eq("ACTIVE"),
                            SERVICES.ONLINE_BOOKING.isTrue()))
            .as("service_count");

    private final DSLContext system;
    private final Marketplace marketplace;

    MarketplaceReview(@SystemDb DSLContext system, Marketplace marketplace) {
        this.system = system;
        this.marketplace = marketplace;
    }

    /** Waiting requests by default, oldest business first. */
    public AdminBodies.Page<AdminBodies.MarketplaceListing> queue(
            StaffAccess staff, String status, String cursor, int limit) {
        staff.require(REVIEW);
        String wanted = status == null ? "PENDING_REVIEW" : status;
        if (!STATUSES.contains(wanted)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Unknown status: " + status);
        }
        Condition condition = staff.marketFilter(REVIEW, ORGANIZATIONS.HOME_MARKET_ID)
                .and(ORGANIZATIONS.MARKETPLACE_STATUS.eq(wanted));
        if (cursor != null) {
            condition = condition.and(ORGANIZATIONS.ID.gt(Directory.cursorId(cursor)));
        }
        List<AdminBodies.MarketplaceListing> rows = summaries()
                .where(condition)
                .orderBy(ORGANIZATIONS.ID)
                .limit(limit + 1)
                .fetch(MarketplaceReview::summary);
        return Directory.page(rows, limit, AdminBodies.MarketplaceListing::orgId);
    }

    public AdminBodies.MarketplaceListing decide(
            StaffAccess staff, UUID orgId, AdminBodies.MarketplaceDecision decision) {
        Record row = summaries().where(ORGANIZATIONS.ID.eq(orgId)).fetchOne();
        if (row == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        staff.requireInMarket(REVIEW, row.get(ORGANIZATIONS.HOME_MARKET_ID));
        if (system.fetchExists(
                MEMBERS,
                MEMBERS.ORG_ID.eq(orgId).and(MEMBERS.USER_ID.eq(staff.userId())).and(MEMBERS.STATUS.ne("REMOVED")))) {
            throw new ApiException(
                    ProblemCode.FORBIDDEN, "You belong to this organization; another reviewer must decide.");
        }
        marketplace.decide(
                orgId,
                staff.userId(),
                Marketplace.Decision.valueOf(decision.decision()),
                decision.reason().strip());
        return summary(summaries().where(ORGANIZATIONS.ID.eq(orgId)).fetchSingle());
    }

    private org.jooq.SelectOnConditionStep<Record> summaries() {
        return system.select(List.of(
                        ORGANIZATIONS.ID,
                        ORGANIZATIONS.SLUG,
                        ORGANIZATIONS.DISPLAY_NAME,
                        ORGANIZATIONS.HOME_MARKET_ID,
                        ORGANIZATIONS.STATUS,
                        ORGANIZATIONS.MARKETPLACE_STATUS,
                        ORGANIZATIONS.MARKETPLACE_NOTE,
                        ORGANIZATIONS.MARKETPLACE_CHANGED_AT,
                        MARKETS.CODE,
                        LOCATION_COUNT,
                        SERVICE_COUNT))
                .from(ORGANIZATIONS)
                .join(MARKETS)
                .on(MARKETS.ID.eq(ORGANIZATIONS.HOME_MARKET_ID));
    }

    private static AdminBodies.MarketplaceListing summary(Record row) {
        return new AdminBodies.MarketplaceListing(
                row.get(ORGANIZATIONS.ID),
                row.get(ORGANIZATIONS.SLUG),
                row.get(ORGANIZATIONS.DISPLAY_NAME),
                row.get(MARKETS.CODE),
                row.get(ORGANIZATIONS.STATUS),
                row.get(ORGANIZATIONS.MARKETPLACE_STATUS),
                row.get(ORGANIZATIONS.MARKETPLACE_NOTE),
                row.get(ORGANIZATIONS.MARKETPLACE_CHANGED_AT) == null
                        ? null
                        : row.get(ORGANIZATIONS.MARKETPLACE_CHANGED_AT).toInstant(),
                row.get(LOCATION_COUNT),
                row.get(SERVICE_COUNT));
    }
}
