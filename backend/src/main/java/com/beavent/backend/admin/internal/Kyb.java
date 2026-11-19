package com.beavent.backend.admin.internal;

import static com.beavent.db.ref.Tables.MARKETS;
import static com.beavent.db.tenant.Tables.LEGAL_ENTITIES;
import static com.beavent.db.tenant.Tables.MEMBERS;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;
import static com.beavent.db.trust.Tables.VERIFICATION_CASES;
import static com.beavent.db.trust.Tables.VERIFICATION_DOCUMENTS;

import com.beavent.backend.files.FileAccess;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import com.beavent.db.trust.tables.records.VerificationCasesRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** The KYB/KYC review queue and decisions. Staff cannot decide cases of organizations they belong to. */
@Component
public class Kyb {

    static final String REVIEW = "admin.kyb.review";
    static final String DECIDE = "admin.kyb.decide";
    static final Set<String> OPEN = Set.of("OPEN", "AWAITING_INFO", "IN_REVIEW");
    private static final Field<Integer> DOCUMENTS = DSL.field(DSL.selectCount()
                    .from(VERIFICATION_DOCUMENTS)
                    .where(VERIFICATION_DOCUMENTS.CASE_ID.eq(VERIFICATION_CASES.ID)))
            .as("documents");

    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final FileAccess files;
    private final Outbox outbox;
    private final Audit audit;

    Kyb(
            @SystemDb DSLContext system,
            @SystemDb TransactionTemplate transactions,
            FileAccess files,
            Outbox outbox,
            Audit audit) {
        this.system = system;
        this.transactions = transactions;
        this.files = files;
        this.outbox = outbox;
        this.audit = audit;
    }

    /** Oldest first, so nobody waits longest. */
    public AdminBodies.Page<AdminBodies.CaseSummary> queue(StaffAccess staff, String status, String cursor, int limit) {
        staff.require(REVIEW);
        Condition condition = staff.marketFilter(REVIEW, ORGANIZATIONS.HOME_MARKET_ID)
                .and(status == null ? VERIFICATION_CASES.STATUS.in(OPEN) : VERIFICATION_CASES.STATUS.eq(status));
        if (cursor != null) {
            condition = condition.and(VERIFICATION_CASES.ID.gt(Directory.cursorId(cursor)));
        }
        List<AdminBodies.CaseSummary> rows = summaries()
                .where(condition)
                .orderBy(VERIFICATION_CASES.ID)
                .limit(limit + 1)
                .fetch(Kyb::summary);
        return Directory.page(rows, limit, AdminBodies.CaseSummary::id);
    }

    List<AdminBodies.CaseSummary> casesOf(UUID orgId) {
        return summaries()
                .where(VERIFICATION_CASES.ORG_ID.eq(orgId))
                .orderBy(VERIFICATION_CASES.ID.desc())
                .fetch(Kyb::summary);
    }

    public AdminBodies.CaseDetail detail(StaffAccess staff, UUID caseId) {
        Record row = visibleCase(staff, REVIEW, caseId);
        AdminBodies.LegalEntitySummary entity = row.get(VERIFICATION_CASES.LEGAL_ENTITY_ID) == null
                ? null
                : system.selectFrom(LEGAL_ENTITIES)
                        .where(LEGAL_ENTITIES.ID.eq(row.get(VERIFICATION_CASES.LEGAL_ENTITY_ID)))
                        .fetchOne(e -> new AdminBodies.LegalEntitySummary(
                                e.getId(),
                                e.getCountryCode(),
                                e.getRegisteredName(),
                                e.getTin(),
                                e.getTaxRegime(),
                                e.getVerificationStatus()));
        List<AdminBodies.CaseDocument> documents = system.selectFrom(VERIFICATION_DOCUMENTS)
                .where(VERIFICATION_DOCUMENTS.CASE_ID.eq(caseId))
                .orderBy(VERIFICATION_DOCUMENTS.CREATED_AT)
                .fetch(d -> new AdminBodies.CaseDocument(
                        d.getId(), d.getDocType(), d.getFileId(), d.getStatus(), d.getExpiresOn()));
        return new AdminBodies.CaseDetail(summary(row), entity, documents);
    }

    /** Signed URL for one document of a case; audited with the reviewer. */
    public AdminBodies.AccessUrl documentUrl(StaffAccess staff, UUID caseId, UUID documentId) {
        Record row = visibleCase(staff, REVIEW, caseId);
        UUID fileId = system.select(VERIFICATION_DOCUMENTS.FILE_ID)
                .from(VERIFICATION_DOCUMENTS)
                .where(VERIFICATION_DOCUMENTS.ID.eq(documentId), VERIFICATION_DOCUMENTS.CASE_ID.eq(caseId))
                .fetchOne(VERIFICATION_DOCUMENTS.FILE_ID);
        if (fileId == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        FileAccess.SignedUrl url = files.signedUrl(fileId);
        transactions.executeWithoutResult(status -> audit.record(new Audit.Entry(
                Audit.Actor.PLATFORM_STAFF,
                staff.userId(),
                row.get(VERIFICATION_CASES.ORG_ID),
                "file.accessed",
                "platform.file",
                fileId.toString(),
                null,
                Map.of("verificationCaseId", caseId.toString()),
                "KYB review")));
        return new AdminBodies.AccessUrl(url.url().toString(), url.expiresAt());
    }

    public AdminBodies.CaseDetail decide(StaffAccess staff, UUID caseId, AdminBodies.Decision decision) {
        transactions.executeWithoutResult(status -> {
            Record row = visibleCase(staff, DECIDE, caseId);
            UUID orgId = row.get(VERIFICATION_CASES.ORG_ID);
            if (orgId != null
                    && system.fetchExists(
                            MEMBERS,
                            MEMBERS.ORG_ID
                                    .eq(orgId)
                                    .and(MEMBERS.USER_ID.eq(staff.userId()))
                                    .and(MEMBERS.STATUS.ne("REMOVED")))) {
                throw new ApiException(
                        ProblemCode.FORBIDDEN, "You belong to this organization; another reviewer must decide.");
            }
            VerificationCasesRecord found = system.selectFrom(VERIFICATION_CASES)
                    .where(VERIFICATION_CASES.ID.eq(caseId))
                    .forUpdate()
                    .fetchSingle();
            if (!OPEN.contains(found.getStatus())) {
                throw new ApiException(ProblemCode.CONFLICT, "This case has already been decided.");
            }
            String before = found.getStatus();
            String outcome = switch (decision.decision()) {
                case "APPROVE" -> "APPROVED";
                case "REJECT" -> "REJECTED";
                default -> "AWAITING_INFO";
            };
            found.setStatus(outcome);
            found.setDecisionReason(decision.reason().strip());
            found.setAssignedTo(staff.userId());
            if (!"AWAITING_INFO".equals(outcome)) {
                found.setDecidedBy(staff.userId());
                found.setDecidedAt(OffsetDateTime.now());
                String documentStatus = "APPROVED".equals(outcome) ? "ACCEPTED" : "REJECTED";
                system.update(VERIFICATION_DOCUMENTS)
                        .set(VERIFICATION_DOCUMENTS.STATUS, documentStatus)
                        .where(VERIFICATION_DOCUMENTS.CASE_ID.eq(caseId))
                        .execute();
                String verification = "APPROVED".equals(outcome) ? "VERIFIED" : "REJECTED";
                if (found.getLegalEntityId() != null) {
                    system.update(LEGAL_ENTITIES)
                            .set(LEGAL_ENTITIES.VERIFICATION_STATUS, verification)
                            .where(LEGAL_ENTITIES.ID.eq(found.getLegalEntityId()))
                            .execute();
                }
                if ("ORGANIZATION_KYB".equals(found.getSubject()) && orgId != null) {
                    system.update(ORGANIZATIONS)
                            .set(ORGANIZATIONS.VERIFICATION_STATUS, verification)
                            .where(ORGANIZATIONS.ID.eq(orgId))
                            .execute();
                    if ("VERIFIED".equals(verification)) {
                        outbox.publish(new DomainEvent(
                                "organization",
                                orgId,
                                "org.verified",
                                1,
                                orgId,
                                Map.of("verificationCaseId", caseId.toString())));
                    }
                }
            }
            found.store();
            outbox.publish(new DomainEvent(
                    "verification_case",
                    caseId,
                    "verification_case.decided",
                    1,
                    orgId,
                    Map.of("status", outcome, "subject", found.getSubject())));
            audit.record(new Audit.Entry(
                    Audit.Actor.PLATFORM_STAFF,
                    staff.userId(),
                    orgId,
                    "verification_case." + decision.decision().toLowerCase(java.util.Locale.ROOT),
                    "trust.verification_case",
                    caseId.toString(),
                    Map.of("status", before),
                    Map.of("status", outcome),
                    decision.reason().strip()));
        });
        return detail(staff, caseId);
    }

    private org.jooq.SelectOnConditionStep<Record> summaries() {
        return system.select(
                        VERIFICATION_CASES.asterisk(),
                        ORGANIZATIONS.DISPLAY_NAME,
                        ORGANIZATIONS.HOME_MARKET_ID,
                        MARKETS.CODE,
                        DOCUMENTS)
                .from(VERIFICATION_CASES)
                .join(ORGANIZATIONS)
                .on(ORGANIZATIONS.ID.eq(VERIFICATION_CASES.ORG_ID))
                .join(MARKETS)
                .on(MARKETS.ID.eq(ORGANIZATIONS.HOME_MARKET_ID));
    }

    private Record visibleCase(StaffAccess staff, String permission, UUID caseId) {
        Record row = summaries().where(VERIFICATION_CASES.ID.eq(caseId)).fetchOne();
        if (row == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        staff.requireInMarket(permission, row.get(ORGANIZATIONS.HOME_MARKET_ID));
        return row;
    }

    private static AdminBodies.CaseSummary summary(Record row) {
        return new AdminBodies.CaseSummary(
                row.get(VERIFICATION_CASES.ID),
                row.get(VERIFICATION_CASES.SUBJECT),
                row.get(VERIFICATION_CASES.STATUS),
                row.get(VERIFICATION_CASES.ORG_ID),
                row.get(ORGANIZATIONS.DISPLAY_NAME),
                row.get(MARKETS.CODE),
                row.get(VERIFICATION_CASES.LEGAL_ENTITY_ID),
                row.get(VERIFICATION_CASES.CREATED_AT).toInstant(),
                row.get(VERIFICATION_CASES.DECIDED_AT) == null
                        ? null
                        : row.get(VERIFICATION_CASES.DECIDED_AT).toInstant(),
                row.get(VERIFICATION_CASES.DECISION_REASON),
                row.get(DOCUMENTS));
    }
}
