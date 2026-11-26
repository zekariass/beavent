package com.beavent.backend.admin.internal;

import com.beavent.backend.identity.Assurance;
import com.beavent.backend.payments.Reconciliation;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.SystemDb;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciling the provider's statement and certifying the provider for live use (plan 3.9). Needs
 * {@code admin.reconciliation} (finance); going live and suspending also need a fresh code. Everything is audited.
 */
@Component
public class ReconciliationDesk {

    static final String PERMISSION = "admin.reconciliation";
    private static final int EXCEPTION_LIMIT = 200;

    private final Reconciliation reconciliation;
    private final Audit audit;
    private final TransactionTemplate transactions;

    ReconciliationDesk(Reconciliation reconciliation, Audit audit, @SystemDb TransactionTemplate transactions) {
        this.reconciliation = reconciliation;
        this.audit = audit;
        this.transactions = transactions;
    }

    public List<Reconciliation.AccountRow> accounts(StaffAccess staff) {
        staff.require(PERMISSION);
        return reconciliation.accounts();
    }

    public List<Reconciliation.StatementRow> statements(StaffAccess staff) {
        staff.require(PERMISSION);
        return reconciliation.statements(50);
    }

    public Reconciliation.StatementRow upload(StaffAccess staff, AdminBodies.StatementUpload body) {
        staff.require(PERMISSION);
        Reconciliation.StatementRow row = reconciliation.importStatement(
                new Reconciliation.Upload(
                        body.providerAccountId(),
                        body.from(),
                        body.to(),
                        body.openingBalanceMinor(),
                        body.closingBalanceMinor(),
                        body.csv()),
                staff.userId(),
                Instant.now());
        record(
                staff,
                "statement.imported",
                "payments.provider_statement",
                row.id().toString(),
                null,
                Map.of(
                        "lines",
                        row.lines(),
                        "matched",
                        row.matched(),
                        "exceptions",
                        row.exceptions(),
                        "status",
                        row.status()),
                null);
        return row;
    }

    public List<Reconciliation.ExceptionRow> exceptions(StaffAccess staff) {
        staff.require(PERMISSION);
        return reconciliation.exceptions(EXCEPTION_LIMIT);
    }

    public void resolve(StaffAccess staff, AdminBodies.ExceptionResolution body) {
        staff.require(PERMISSION);
        reconciliation.resolve(
                body.statementId(), body.lineId(), body.attemptId(), staff.userId(), body.note(), Instant.now());
        record(
                staff,
                "reconciliation.resolved",
                "payments.provider_statement",
                body.statementId().toString(),
                null,
                Map.of(
                        "lineId", String.valueOf(body.lineId()),
                        "attemptId", String.valueOf(body.attemptId())),
                body.note());
    }

    public Reconciliation.Certification certification(StaffAccess staff) {
        staff.require(PERMISSION);
        return reconciliation.certification();
    }

    public Reconciliation.Certification goLive(StaffAccess staff, String evidence) {
        staff.require(PERMISSION);
        Assurance.requireStepUp();
        Reconciliation.Certification before = reconciliation.certification();
        reconciliation.goLive(staff.userId(), evidence, Instant.now());
        record(
                staff,
                "provider.live",
                "payments.provider",
                before.provider(),
                Map.of("status", before.status()),
                Map.of("status", "LIVE"),
                evidence);
        return reconciliation.certification();
    }

    public Reconciliation.Certification suspend(StaffAccess staff, String reason) {
        staff.require(PERMISSION);
        Assurance.requireStepUp();
        Reconciliation.Certification before = reconciliation.certification();
        reconciliation.suspend(staff.userId(), reason, Instant.now());
        record(
                staff,
                "provider.suspended",
                "payments.provider",
                before.provider(),
                Map.of("status", before.status()),
                Map.of("status", "SUSPENDED"),
                reason);
        return reconciliation.certification();
    }

    private void record(
            StaffAccess staff,
            String action,
            String type,
            String id,
            Map<String, Object> before,
            Map<String, Object> after,
            String reason) {
        transactions.executeWithoutResult(status -> audit.record(new Audit.Entry(
                Audit.Actor.PLATFORM_STAFF, staff.userId(), null, action, type, id, before, after, reason)));
    }
}
