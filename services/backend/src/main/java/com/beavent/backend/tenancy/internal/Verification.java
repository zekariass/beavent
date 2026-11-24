package com.beavent.backend.tenancy.internal;

import static com.beavent.db.platform.Tables.FILES;
import static com.beavent.db.tenant.Tables.LEGAL_ENTITIES;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;
import static com.beavent.db.trust.Tables.VERIFICATION_CASES;
import static com.beavent.db.trust.Tables.VERIFICATION_DOCUMENTS;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import com.beavent.db.trust.tables.records.VerificationCasesRecord;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Business (KYB) and owner identity (KYC) verification submitted by the organization. Platform staff decide in the
 * admin console (step 0.10); this side only submits and reads.
 */
@Component
public class Verification {

    private static final Set<String> OPEN = Set.of("OPEN", "AWAITING_INFO", "IN_REVIEW");

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Outbox outbox;
    private final Audit audit;

    Verification(DSLContext dsl, TransactionTemplate transactions, Outbox outbox, Audit audit) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.outbox = outbox;
        this.audit = audit;
    }

    public List<Bodies.VerificationCase> list() {
        return transactions.execute(status -> {
            List<VerificationCasesRecord> cases = dsl.selectFrom(VERIFICATION_CASES)
                    .orderBy(VERIFICATION_CASES.CREATED_AT.desc())
                    .fetch();
            return cases.stream().map(this::view).toList();
        });
    }

    public Bodies.VerificationCase get(UUID caseId) {
        return transactions.execute(status -> {
            VerificationCasesRecord found = dsl.selectFrom(VERIFICATION_CASES)
                    .where(VERIFICATION_CASES.ID.eq(caseId))
                    .fetchOne();
            if (found == null) {
                throw new ApiException(ProblemCode.NOT_FOUND);
            }
            return view(found);
        });
    }

    public Bodies.VerificationCase submit(OrgAccess access, Bodies.VerificationCaseCreate input) {
        boolean kyb = "ORGANIZATION_KYB".equals(input.subject());
        if (kyb && input.legalEntityId() == null) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED, "legalEntityId is required for business verification.");
        }
        if (!kyb && !access.isOwner()) {
            throw new ApiException(ProblemCode.FORBIDDEN, "Only the owner can submit owner identity documents.");
        }
        return transactions.execute(status -> {
            if (kyb && !dsl.fetchExists(LEGAL_ENTITIES, LEGAL_ENTITIES.ID.eq(input.legalEntityId()))) {
                throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown legal entity.");
            }
            boolean open = dsl.fetchExists(
                    VERIFICATION_CASES,
                    VERIFICATION_CASES
                            .SUBJECT
                            .eq(input.subject())
                            .and(VERIFICATION_CASES.STATUS.in(OPEN))
                            .and(
                                    kyb
                                            ? VERIFICATION_CASES.LEGAL_ENTITY_ID.eq(input.legalEntityId())
                                            : VERIFICATION_CASES.USER_ID.eq(access.userId())));
            if (open) {
                throw new ApiException(ProblemCode.CONFLICT, "A verification for this is already being reviewed.");
            }
            Set<UUID> fileIds = input.documents().stream()
                    .map(Bodies.VerificationDocumentInput::fileId)
                    .collect(Collectors.toSet());
            int usable = dsl.fetchCount(
                    FILES,
                    FILES.ID
                            .in(fileIds)
                            .and(FILES.ORG_ID.eq(access.orgId()))
                            .and(FILES.DELETED_AT.isNull())
                            .and(FILES.UPLOADED_AT.isNotNull())
                            .and(FILES.SCAN_STATUS.in("PENDING", "CLEAN"))
                            .and(FILES.PURPOSE.in("KYC", "DOCUMENT")));
            if (usable != fileIds.size()) {
                throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown document file.");
            }
            VerificationCasesRecord created = dsl.insertInto(VERIFICATION_CASES)
                    .set(VERIFICATION_CASES.SUBJECT, input.subject())
                    .set(VERIFICATION_CASES.ORG_ID, access.orgId())
                    .set(VERIFICATION_CASES.USER_ID, kyb ? null : access.userId())
                    .set(VERIFICATION_CASES.LEGAL_ENTITY_ID, kyb ? input.legalEntityId() : null)
                    .returning()
                    .fetchSingle();
            for (Bodies.VerificationDocumentInput document : input.documents()) {
                dsl.insertInto(VERIFICATION_DOCUMENTS)
                        .set(VERIFICATION_DOCUMENTS.CASE_ID, created.getId())
                        .set(VERIFICATION_DOCUMENTS.DOC_TYPE, document.docType())
                        .set(VERIFICATION_DOCUMENTS.FILE_ID, document.fileId())
                        .set(VERIFICATION_DOCUMENTS.EXPIRES_ON, document.expiresOn())
                        .execute();
            }
            if (kyb) {
                dsl.update(LEGAL_ENTITIES)
                        .set(LEGAL_ENTITIES.VERIFICATION_STATUS, "PENDING")
                        .where(LEGAL_ENTITIES.ID.eq(input.legalEntityId()))
                        .and(LEGAL_ENTITIES.VERIFICATION_STATUS.in("UNVERIFIED", "REJECTED"))
                        .execute();
                dsl.update(ORGANIZATIONS)
                        .set(ORGANIZATIONS.VERIFICATION_STATUS, "PENDING")
                        .where(ORGANIZATIONS.ID.eq(access.orgId()))
                        .and(ORGANIZATIONS.VERIFICATION_STATUS.in("UNVERIFIED", "REJECTED"))
                        .execute();
            }
            outbox.publish(new DomainEvent(
                    "verification_case",
                    created.getId(),
                    "verification_case.submitted",
                    1,
                    access.orgId(),
                    Map.of("subject", input.subject())));
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    access.userId(),
                    access.orgId(),
                    "verification_case.submitted",
                    "trust.verification_case",
                    created.getId().toString(),
                    null,
                    Map.of(
                            "subject",
                            input.subject(),
                            "documents",
                            input.documents().size()),
                    null));
            return view(created);
        });
    }

    private Bodies.VerificationCase view(VerificationCasesRecord found) {
        List<Bodies.VerificationDocument> documents = dsl.selectFrom(VERIFICATION_DOCUMENTS)
                .where(VERIFICATION_DOCUMENTS.CASE_ID.eq(found.getId()))
                .orderBy(VERIFICATION_DOCUMENTS.CREATED_AT)
                .fetch(d -> new Bodies.VerificationDocument(
                        d.getId(), d.getDocType(), d.getFileId(), d.getExpiresOn(), d.getStatus()));
        return new Bodies.VerificationCase(
                found.getId(),
                found.getSubject(),
                found.getStatus(),
                found.getDecisionReason(),
                Support.instant(found.getCreatedAt()),
                Support.instant(found.getDecidedAt()),
                documents);
    }
}
