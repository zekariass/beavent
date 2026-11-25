package com.beavent.backend.crm.internal;

import static com.beavent.db.crm.Tables.CUSTOMERS;
import static com.beavent.db.crm.Tables.CUSTOMER_TAGS;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import com.beavent.db.crm.tables.records.CustomersRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Merging two records of the same person (P-13): everything the duplicate owns moves to the record that is kept, its
 * blank contact details are filled from the duplicate, and the duplicate is marked {@code MERGED} (links to it keep
 * working). Cannot be undone.
 */
@Component
public class ClientMerge {

    /** A table and the column in it that names a client. */
    public record Reference(String table, String column) {
        String qualified() {
            return table + "." + column;
        }
    }

    /**
     * What moves to the kept client. Tags are merged separately (the kept client may already carry the tag). Every
     * reference to {@code crm.customers} must be in this list, in {@link #SPECIAL} or in {@link #NOT_YET_MERGEABLE}; a test checks it.
     */
    public static final List<Reference> MOVED = List.of(
            new Reference("crm.customer_addresses", "customer_id"),
            new Reference("crm.customer_notes", "customer_id"),
            new Reference("crm.customer_files", "customer_id"),
            new Reference("crm.treatment_records", "customer_id"),
            new Reference("crm.patch_tests", "customer_id"),
            new Reference("crm.customer_consents", "customer_id"),
            new Reference("crm.form_submissions", "customer_id"),
            new Reference("scheduling.appointments", "customer_id"),
            new Reference("scheduling.appointment_groups", "lead_customer_id"),
            new Reference("scheduling.appointment_series", "customer_id"),
            new Reference("commerce.waitlist_entries", "customer_id"),
            new Reference("comms.conversations", "customer_id"));

    /**
     * Handled on their own (see {@link #merge}), or history that stays with the merged record: messages already
     * sent keep the recipient they were sent to (and only the recipient may change them).
     */
    public static final List<Reference> SPECIAL = List.of(
            new Reference("crm.customer_tags", "customer_id"),
            new Reference("crm.customers", "merged_into_id"),
            new Reference("comms.notifications", "recipient_customer_id"));

    /**
     * Money, memberships and marketing are not built yet (Stages 3, 4 and 8). A duplicate with rows here is not
     * merged until those stages decide how balances and memberships combine.
     */
    public static final List<Reference> NOT_YET_MERGEABLE = List.of(
            new Reference("commerce.orders", "customer_id"),
            new Reference("commerce.redemptions", "customer_id"),
            new Reference("commerce.invoices", "customer_id"),
            new Reference("retention.gift_cards", "purchaser_customer_id"),
            new Reference("retention.gift_cards", "recipient_customer_id"),
            new Reference("retention.customer_balances", "customer_id"),
            new Reference("retention.customer_memberships", "customer_id"),
            new Reference("retention.entitlements", "customer_id"),
            new Reference("retention.loyalty_accounts", "customer_id"),
            new Reference("payments.payment_intents", "payer_customer_id"),
            new Reference("marketing.segment_members", "customer_id"),
            new Reference("marketing.campaign_recipients", "customer_id"),
            new Reference("marketing.automation_runs", "customer_id"),
            new Reference("marketing.referrals", "referrer_customer_id"),
            new Reference("marketing.referrals", "referee_customer_id"));

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Clients clients;
    private final Outbox outbox;
    private final Audit audit;

    ClientMerge(DSLContext dsl, TransactionTemplate transactions, Clients clients, Outbox outbox, Audit audit) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.clients = clients;
        this.outbox = outbox;
        this.audit = audit;
    }

    /** Merges {@code duplicateId} into {@code keepId} and returns the kept client's page. */
    public ClientBodies.Client merge(OrgAccess access, UUID keepId, UUID duplicateId, Instant now) {
        access.require(Clients.MANAGE);
        if (keepId.equals(duplicateId)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose two different clients.");
        }
        return transactions.execute(status -> {
            // Lock both in a fixed order so two merges of the same pair cannot deadlock.
            var rows = dsl.selectFrom(CUSTOMERS)
                    .where(CUSTOMERS.ID.in(keepId, duplicateId))
                    .orderBy(CUSTOMERS.ID)
                    .forUpdate()
                    .fetchMap(CUSTOMERS.ID);
            CustomersRecord keep = rows.get(keepId);
            CustomersRecord duplicate = rows.get(duplicateId);
            if (keep == null || duplicate == null) {
                throw new ApiException(ProblemCode.NOT_FOUND, "There is no such client.");
            }
            if (!keep.getStatus().equals("ACTIVE") || !duplicate.getStatus().equals("ACTIVE")) {
                throw new ApiException(ProblemCode.IMMUTABLE, "One of these clients was already merged.");
            }
            if (keep.getUserId() != null
                    && duplicate.getUserId() != null
                    && !keep.getUserId().equals(duplicate.getUserId())) {
                throw new ApiException(
                        ProblemCode.CONSTRAINT_VIOLATION,
                        "Both clients have their own Beavent account, so they are different people.");
            }
            for (Reference ref : NOT_YET_MERGEABLE) {
                if (dsl.fetchExists(DSL.selectOne()
                        .from(DSL.table(ref.table()))
                        .where(DSL.field(DSL.name(ref.column())).eq(duplicateId)))) {
                    throw new ApiException(
                            ProblemCode.CONSTRAINT_VIOLATION,
                            "This client has payments, balances or memberships; merging them is not possible yet.");
                }
            }

            dsl.execute(
                    "insert into crm.customer_tags (org_id, customer_id, tag_id, tagged_at) "
                            + "select org_id, ?, tag_id, tagged_at from crm.customer_tags where customer_id = ? "
                            + "on conflict do nothing",
                    keepId,
                    duplicateId);
            dsl.deleteFrom(CUSTOMER_TAGS)
                    .where(CUSTOMER_TAGS.CUSTOMER_ID.eq(duplicateId))
                    .execute();
            for (Reference ref : MOVED) {
                dsl.update(DSL.table(ref.table()))
                        .set(DSL.field(DSL.name(ref.column()), UUID.class), keepId)
                        .where(DSL.field(DSL.name(ref.column()), UUID.class).eq(duplicateId))
                        .execute();
            }

            // The duplicate first stops being active (one active record per account), then the kept one takes over
            // what it lacked.
            dsl.update(CUSTOMERS)
                    .set(CUSTOMERS.STATUS, "MERGED")
                    .set(CUSTOMERS.MERGED_INTO_ID, keepId)
                    .set(CUSTOMERS.UPDATED_AT, OffsetDateTime.now())
                    .where(CUSTOMERS.ID.eq(duplicateId))
                    .execute();
            dsl.update(CUSTOMERS)
                    .set(CUSTOMERS.USER_ID, first(keep.getUserId(), duplicate.getUserId()))
                    .set(CUSTOMERS.FIRST_NAME, first(keep.getFirstName(), duplicate.getFirstName()))
                    .set(CUSTOMERS.LAST_NAME, first(keep.getLastName(), duplicate.getLastName()))
                    .set(CUSTOMERS.PHONE_E164, first(keep.getPhoneE164(), duplicate.getPhoneE164()))
                    .set(CUSTOMERS.EMAIL, first(keep.getEmail(), duplicate.getEmail()))
                    .set(CUSTOMERS.DATE_OF_BIRTH, first(keep.getDateOfBirth(), duplicate.getDateOfBirth()))
                    .set(CUSTOMERS.GENDER, first(keep.getGender(), duplicate.getGender()))
                    .set(
                            CUSTOMERS.PREFERRED_LANGUAGE,
                            first(keep.getPreferredLanguage(), duplicate.getPreferredLanguage()))
                    .set(CUSTOMERS.FIRST_SEEN_AT, earlier(keep.getFirstSeenAt(), duplicate.getFirstSeenAt()))
                    .set(CUSTOMERS.LAST_VISIT_AT, later(keep.getLastVisitAt(), duplicate.getLastVisitAt()))
                    .set(
                            CUSTOMERS.MARKETPLACE_ACQUIRED_AT,
                            earlier(keep.getMarketplaceAcquiredAt(), duplicate.getMarketplaceAcquiredAt()))
                    .set(CUSTOMERS.IS_BLOCKED, keep.getIsBlocked() || duplicate.getIsBlocked())
                    .set(CUSTOMERS.BLOCK_REASON, first(keep.getBlockReason(), duplicate.getBlockReason()))
                    .set(CUSTOMERS.UPDATED_AT, OffsetDateTime.now())
                    .where(CUSTOMERS.ID.eq(keepId))
                    .execute();

            outbox.publish(new DomainEvent(
                    "crm.customer",
                    keepId,
                    "customer.merged",
                    1,
                    access.orgId(),
                    Map.of("customerId", keepId, "mergedId", duplicateId)));
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    access.userId(),
                    access.orgId(),
                    "customer.merged",
                    "crm.customer",
                    keepId.toString(),
                    Map.of("mergedId", duplicateId.toString(), "mergedName", duplicate.getDisplayName()),
                    null,
                    null));
            return clients.read(access, keepId, now);
        });
    }

    private static <T> T first(T kept, T other) {
        return kept != null ? kept : other;
    }

    private static OffsetDateTime earlier(OffsetDateTime a, OffsetDateTime b) {
        return a == null ? b : b == null ? a : a.isBefore(b) ? a : b;
    }

    private static OffsetDateTime later(OffsetDateTime a, OffsetDateTime b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }
}
