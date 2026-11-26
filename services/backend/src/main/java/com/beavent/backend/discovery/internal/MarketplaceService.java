package com.beavent.backend.discovery.internal;

import com.beavent.backend.discovery.Listings;
import com.beavent.backend.discovery.Marketplace;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The marketplace lifecycle of a business: {@code HIDDEN → PENDING_REVIEW → LISTED}, sent back to {@code HIDDEN} with a
 * reason, or taken off to {@code DELISTED}. Every change is audited and published; staff decisions refresh the
 * business's listings straight away, so a business approved a moment ago is already in search.
 */
@Component
class MarketplaceService implements Marketplace {

    /** A business in one of these states can be listed. */
    private static final List<String> OPEN_FOR_BUSINESS = List.of("ACTIVE", "ONBOARDING");

    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final Listings listings;
    private final Outbox outbox;
    private final Audit audit;

    MarketplaceService(
            @SystemDb DSLContext system,
            @SystemDb TransactionTemplate transactions,
            Listings listings,
            Outbox outbox,
            Audit audit) {
        this.system = system;
        this.transactions = transactions;
        this.listings = listings;
        this.outbox = outbox;
        this.audit = audit;
    }

    private Record row(UUID orgId) {
        Record row = system.resultQuery(
                        "SELECT status, marketplace_status, marketplace_note, marketplace_changed_at"
                                + " FROM tenant.organizations WHERE id = ?",
                        orgId)
                .fetchOne();
        if (row == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        return row;
    }

    private List<Requirement> requirements(UUID orgId) {
        boolean location = system.resultQuery(
                        "SELECT EXISTS (SELECT 1 FROM tenant.locations WHERE org_id = ? AND status = 'ACTIVE')", orgId)
                .fetchOne(0, Boolean.class);
        boolean service = system.resultQuery("""
                                SELECT EXISTS (
                                  SELECT 1 FROM catalog.location_services ls
                                    JOIN tenant.locations l ON l.id = ls.location_id AND l.status = 'ACTIVE'
                                    JOIN catalog.services s ON s.id = ls.service_id AND s.org_id = ls.org_id
                                   WHERE ls.org_id = ? AND s.status = 'ACTIVE' AND s.online_booking
                                     AND EXISTS (SELECT 1 FROM catalog.service_variants v
                                                  WHERE v.service_id = s.id AND v.status = 'ACTIVE'))
                                """, orgId).fetchOne(0, Boolean.class);
        return List.of(new Requirement("LOCATION", location), new Requirement("SERVICE", service));
    }

    private static boolean ready(List<Requirement> requirements) {
        return requirements.stream().allMatch(Requirement::met);
    }

    private Status status(Record row, List<Requirement> requirements) {
        String status = row.get("marketplace_status", String.class);
        Object changed = row.get("marketplace_changed_at");
        return new Status(
                status,
                row.get("marketplace_note", String.class),
                changed == null ? null : ((java.time.OffsetDateTime) changed).toInstant(),
                requirements,
                "HIDDEN".equals(status)
                        && ready(requirements)
                        && OPEN_FOR_BUSINESS.contains(row.get("status", String.class)));
    }

    @Override
    public Status status(UUID orgId) {
        return status(row(orgId), requirements(orgId));
    }

    @Override
    public Status request(UUID orgId, UUID userId) {
        transactions.executeWithoutResult(s -> {
            Record row = row(orgId);
            String status = row.get("marketplace_status", String.class);
            if (!"HIDDEN".equals(status)) {
                throw new ApiException(
                        ProblemCode.CONFLICT,
                        "DELISTED".equals(status)
                                ? "Beavent took this business off the marketplace. Contact support to be listed again."
                                : "This business is already "
                                        + ("LISTED".equals(status) ? "listed." : "waiting for review."));
            }
            if (!OPEN_FOR_BUSINESS.contains(row.get("status", String.class))) {
                throw new ApiException(ProblemCode.CONFLICT, "This business cannot be listed right now.");
            }
            if (!ready(requirements(orgId))) {
                throw new ApiException(
                        ProblemCode.CONSTRAINT_VIOLATION,
                        "Add a location and a service people can book online before asking to be listed.");
            }
            change(orgId, "PENDING_REVIEW", null, userId, Audit.Actor.USER, "marketplace.requested", "HIDDEN", null);
        });
        return status(orgId);
    }

    @Override
    public Status withdraw(UUID orgId, UUID userId) {
        boolean wasListed = transactions.execute(s -> {
            String status = row(orgId).get("marketplace_status", String.class);
            if (!"PENDING_REVIEW".equals(status) && !"LISTED".equals(status)) {
                throw new ApiException(ProblemCode.CONFLICT, "This business is not listed and has no request waiting.");
            }
            change(orgId, "HIDDEN", null, userId, Audit.Actor.USER, "marketplace.withdrawn", status, null);
            return "LISTED".equals(status);
        });
        if (wasListed) {
            listings.refreshOrganization(orgId, Instant.now());
        }
        return status(orgId);
    }

    @Override
    public Decided decide(UUID orgId, UUID staffUserId, Decision decision, String reason) {
        Decided decided = transactions.execute(s -> {
            Record row = row(orgId);
            String before = row.get("marketplace_status", String.class);
            String after = switch (decision) {
                case LIST -> {
                    if ("LISTED".equals(before)) {
                        throw new ApiException(ProblemCode.CONFLICT, "This business is already listed.");
                    }
                    if (!OPEN_FOR_BUSINESS.contains(row.get("status", String.class))) {
                        throw new ApiException(
                                ProblemCode.CONFLICT, "A suspended, restricted or closed business cannot be listed.");
                    }
                    if (!ready(requirements(orgId))) {
                        throw new ApiException(
                                ProblemCode.CONSTRAINT_VIOLATION,
                                "This business has no active location, or no service people can book online.");
                    }
                    yield "LISTED";
                }
                case REJECT -> {
                    if (!"PENDING_REVIEW".equals(before)) {
                        throw new ApiException(ProblemCode.CONFLICT, "This business has no request waiting.");
                    }
                    yield "HIDDEN";
                }
                case DELIST -> {
                    if (!"LISTED".equals(before)) {
                        throw new ApiException(ProblemCode.CONFLICT, "This business is not listed.");
                    }
                    yield "DELISTED";
                }
            };
            // The reason is for the business to read after a "no" or a take-down; a listing needs none shown.
            String note = decision == Decision.LIST ? null : reason;
            change(
                    orgId,
                    after,
                    note,
                    staffUserId,
                    Audit.Actor.PLATFORM_STAFF,
                    "marketplace." + decision.name().toLowerCase(java.util.Locale.ROOT),
                    before,
                    reason);
            return new Decided(before, after);
        });
        listings.refreshOrganization(orgId, Instant.now());
        return decided;
    }

    private void change(
            UUID orgId,
            String to,
            String note,
            UUID actor,
            Audit.Actor kind,
            String action,
            String from,
            String reason) {
        system.execute(
                "UPDATE tenant.organizations SET marketplace_status = ?, marketplace_note = ?,"
                        + " marketplace_changed_at = now(), updated_at = now() WHERE id = ?",
                to,
                note,
                orgId);
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("status", to);
        payload.put("at", Instant.now().toString());
        if (note != null) {
            payload.put("note", note);
        }
        outbox.publish(new DomainEvent("organization", orgId, "org.marketplace_changed", 1, orgId, payload));
        audit.record(new Audit.Entry(
                kind,
                actor,
                orgId,
                "organization." + action,
                "tenant.organization",
                orgId.toString(),
                Map.of("marketplaceStatus", from),
                Map.of("marketplaceStatus", to),
                reason));
    }
}
