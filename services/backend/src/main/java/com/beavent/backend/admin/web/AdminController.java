package com.beavent.backend.admin.web;

import static com.beavent.db.iam.Tables.USER_STAFF_ROLES;
import static com.beavent.db.ref.Tables.MARKETS;

import com.beavent.backend.admin.internal.AdminBodies;
import com.beavent.backend.admin.internal.AuditTrail;
import com.beavent.backend.admin.internal.Directory;
import com.beavent.backend.admin.internal.Flags;
import com.beavent.backend.admin.internal.Kyb;
import com.beavent.backend.admin.internal.MarketplaceReview;
import com.beavent.backend.admin.internal.PayoutDesk;
import com.beavent.backend.admin.internal.PayoutReview;
import com.beavent.backend.admin.internal.RefundDesk;
import com.beavent.backend.admin.internal.StaffAccess;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import jakarta.validation.Valid;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin console API (services/backend/src/main/openapi/admin). Staff access is checked by the admin interceptor. */
@RestController
class AdminController {

    private final Directory directory;
    private final Kyb kyb;
    private final PayoutReview payouts;
    private final MarketplaceReview marketplaceReview;
    private final PayoutDesk desk;
    private final RefundDesk refundDesk;
    private final AuditTrail auditTrail;
    private final Flags flags;
    private final DSLContext system;

    AdminController(
            Directory directory,
            Kyb kyb,
            PayoutReview payouts,
            MarketplaceReview marketplaceReview,
            PayoutDesk desk,
            RefundDesk refundDesk,
            AuditTrail auditTrail,
            Flags flags,
            @SystemDb DSLContext system) {
        this.directory = directory;
        this.kyb = kyb;
        this.payouts = payouts;
        this.marketplaceReview = marketplaceReview;
        this.desk = desk;
        this.refundDesk = refundDesk;
        this.auditTrail = auditTrail;
        this.flags = flags;
        this.system = system;
    }

    @GetMapping("/v1/admin/me")
    AdminBodies.StaffMe me() {
        StaffAccess staff = StaffAccess.current();
        List<AdminBodies.StaffRole> roles = system.select(USER_STAFF_ROLES.ROLE_CODE, MARKETS.CODE)
                .from(USER_STAFF_ROLES)
                .leftJoin(MARKETS)
                .on(MARKETS.ID.eq(USER_STAFF_ROLES.MARKET_ID))
                .where(USER_STAFF_ROLES.USER_ID.eq(staff.userId()), USER_STAFF_ROLES.REVOKED_AT.isNull())
                .orderBy(USER_STAFF_ROLES.ROLE_CODE)
                .fetch(r -> new AdminBodies.StaffRole(r.value1(), r.value2()));
        return new AdminBodies.StaffMe(
                staff.userId(),
                roles,
                List.copyOf(new TreeSet<>(staff.marketsByPermission().keySet())));
    }

    @GetMapping("/v1/admin/orgs")
    AdminBodies.Page<AdminBodies.OrgSummary> searchOrganizations(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return directory.searchOrganizations(StaffAccess.current(), q, status, cursor, limit(limit));
    }

    @GetMapping("/v1/admin/orgs/{orgId}")
    AdminBodies.Org360 organization(@PathVariable UUID orgId) {
        return directory.organization(StaffAccess.current(), orgId);
    }

    @GetMapping("/v1/admin/users")
    AdminBodies.Page<AdminBodies.UserSummary> searchUsers(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return directory.searchUsers(StaffAccess.current(), q, cursor, limit(limit));
    }

    @GetMapping("/v1/admin/verification-cases")
    AdminBodies.Page<AdminBodies.CaseSummary> verificationQueue(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return kyb.queue(StaffAccess.current(), status, cursor, limit(limit));
    }

    @GetMapping("/v1/admin/verification-cases/{caseId}")
    AdminBodies.CaseDetail verificationCase(@PathVariable UUID caseId) {
        return kyb.detail(StaffAccess.current(), caseId);
    }

    @PostMapping("/v1/admin/verification-cases/{caseId}/documents/{documentId}/access-url")
    AdminBodies.AccessUrl documentUrl(@PathVariable UUID caseId, @PathVariable UUID documentId) {
        return kyb.documentUrl(StaffAccess.current(), caseId, documentId);
    }

    @PostMapping("/v1/admin/verification-cases/{caseId}/decision")
    AdminBodies.CaseDetail decide(@PathVariable UUID caseId, @Valid @RequestBody AdminBodies.Decision body) {
        return kyb.decide(StaffAccess.current(), caseId, body);
    }

    @GetMapping("/v1/admin/payout-accounts")
    AdminBodies.Page<AdminBodies.PayoutAccountSummary> payoutAccounts(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return payouts.queue(StaffAccess.current(), status, cursor, limit(limit));
    }

    @PostMapping("/v1/admin/payout-accounts/{accountId}/reveal")
    AdminBodies.AccountNumber revealPayoutAccount(
            @PathVariable UUID accountId, @Valid @RequestBody AdminBodies.Reason body) {
        return payouts.reveal(StaffAccess.current(), accountId, body.reason());
    }

    @PostMapping("/v1/admin/payout-accounts/{accountId}/decision")
    AdminBodies.PayoutAccountSummary decidePayoutAccount(
            @PathVariable UUID accountId, @Valid @RequestBody AdminBodies.Decision body) {
        return payouts.decide(StaffAccess.current(), accountId, body);
    }

    @GetMapping("/v1/admin/marketplace-listings")
    AdminBodies.Page<AdminBodies.MarketplaceListing> marketplaceListings(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return marketplaceReview.queue(StaffAccess.current(), status, cursor, limit(limit));
    }

    @PostMapping("/v1/admin/orgs/{orgId}/marketplace-decision")
    AdminBodies.MarketplaceListing decideMarketplace(
            @PathVariable UUID orgId, @Valid @RequestBody AdminBodies.MarketplaceDecision body) {
        return marketplaceReview.decide(StaffAccess.current(), orgId, body);
    }

    @GetMapping("/v1/admin/payouts")
    AdminBodies.Page<AdminBodies.PayoutSummary> payoutQueue(
            @RequestParam(required = false) String queue,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return desk.queue(StaffAccess.current(), queue, cursor, limit(limit));
    }

    @PostMapping("/v1/admin/payouts/{payoutId}/decision")
    AdminBodies.PayoutSummary decidePayout(@PathVariable UUID payoutId, @Valid @RequestBody AdminBodies.Decision body) {
        return desk.decide(StaffAccess.current(), payoutId, body);
    }

    @PostMapping("/v1/admin/payouts/{payoutId}/settlement")
    AdminBodies.PayoutSummary settlePayout(
            @PathVariable UUID payoutId, @Valid @RequestBody AdminBodies.PayoutSettlement body) {
        return desk.settle(StaffAccess.current(), payoutId, body);
    }

    @PostMapping("/v1/admin/payouts/export")
    org.springframework.http.ResponseEntity<String> exportPayouts(
            @RequestBody(required = false) AdminBodies.PayoutExport body) {
        String csv = desk.export(StaffAccess.current(), body == null ? null : body.payoutIds());
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=\"payouts.csv\"")
                .body(csv);
    }

    @GetMapping("/v1/admin/refunds")
    AdminBodies.Page<AdminBodies.RefundSummary> refundQueue(
            @RequestParam(required = false) String queue,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return refundDesk.queue(StaffAccess.current(), queue, cursor, limit(limit));
    }

    @PostMapping("/v1/admin/refunds/{refundId}/approval")
    AdminBodies.RefundSummary approveRefund(
            @PathVariable UUID refundId, @Valid @RequestBody(required = false) AdminBodies.RefundApproval body) {
        return refundDesk.approve(StaffAccess.current(), refundId, body == null ? null : body.note());
    }

    @PostMapping("/v1/admin/refunds/{refundId}/payment")
    AdminBodies.RefundSummary payRefund(
            @PathVariable UUID refundId, @Valid @RequestBody AdminBodies.RefundPayment body) {
        return refundDesk.pay(StaffAccess.current(), refundId, body);
    }

    @GetMapping("/v1/admin/audit-log")
    AdminBodies.Page<AdminBodies.AuditEntry> auditLog(
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return auditTrail.search(StaffAccess.current(), orgId, actorUserId, action, targetId, cursor, limit(limit));
    }

    @GetMapping("/v1/admin/feature-flags")
    AdminBodies.Items<AdminBodies.Flag> featureFlags() {
        return new AdminBodies.Items<>(flags.list());
    }

    @PutMapping("/v1/admin/feature-flags/{code}")
    AdminBodies.Flag updateFlag(@PathVariable String code, @Valid @RequestBody AdminBodies.FlagUpdate body) {
        return flags.update(StaffAccess.current(), code, body);
    }

    private static int limit(Integer limit) {
        if (limit == null) {
            return 25;
        }
        if (limit < 1 || limit > 100) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "limit must be between 1 and 100.");
        }
        return limit;
    }
}
