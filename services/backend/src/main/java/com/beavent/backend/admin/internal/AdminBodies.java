package com.beavent.backend.admin.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bodies of the admin API (services/backend/src/main/openapi/admin/schemas.yaml). */
public final class AdminBodies {

    private AdminBodies() {}

    public record PageInfo(
            @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {}

    public record Page<T>(List<T> items, PageInfo page) {}

    public record Items<T>(List<T> items) {}

    public record StaffRole(String roleCode, String marketCode) {}

    public record StaffMe(UUID userId, List<StaffRole> roles, List<String> permissions) {}

    public record OrgSummary(
            UUID id,
            String slug,
            String displayName,
            String kind,
            String status,
            String verificationStatus,
            String marketCode,
            Instant createdAt) {}

    public record Person(UUID userId, String displayName, String phone) {}

    public record LegalEntitySummary(
            UUID id,
            String countryCode,
            String registeredName,
            String tin,
            String taxRegime,
            String verificationStatus) {}

    public record CaseSummary(
            UUID id,
            String subject,
            String status,
            UUID orgId,
            String orgName,
            String marketCode,
            UUID legalEntityId,
            Instant createdAt,
            Instant decidedAt,
            String decisionReason,
            int documentCount) {}

    public record CaseDocument(UUID id, String docType, UUID fileId, String status, LocalDate expiresOn) {}

    public record CaseDetail(
            CaseSummary verificationCase, LegalEntitySummary legalEntity, List<CaseDocument> documents) {}

    public record AuditEntry(
            UUID id,
            Instant occurredAt,
            String actorKind,
            UUID actorUserId,
            UUID orgId,
            String action,
            String targetType,
            String targetId,
            Map<String, Object> before,
            Map<String, Object> after,
            String reason,
            String requestId) {}

    public record Org360(
            OrgSummary organization,
            List<String> capabilities,
            Person owner,
            int memberCount,
            int locationCount,
            List<LegalEntitySummary> legalEntities,
            List<CaseSummary> verificationCases,
            List<AuditEntry> recentAudit) {}

    public record UserSummary(
            UUID id,
            String phone,
            String email,
            String displayName,
            String status,
            String countryCode,
            Instant createdAt,
            Instant lastLoginAt,
            boolean isStaff,
            int membershipCount) {}

    public record Decision(
            @NotNull @Pattern(regexp = "APPROVE|REJECT|REQUEST_INFO")
            String decision,

            @NotBlank @Size(min = 5, max = 500) String reason) {}

    /** A payout account waiting for (or past) review; the number is masked, see {@code reveal}. */
    public record PayoutAccountSummary(
            UUID id,
            UUID orgId,
            String organization,
            String marketCode,
            String organizationStatus,
            String legalEntityName,
            String legalEntityStatus,
            String kind,
            String institutionName,
            String accountName,
            String accountNumberMasked,
            String status,
            Instant createdAt,
            Instant payoutsAllowedAfter,
            String decisionNote) {}

    public record AccountNumber(String accountNumber) {}

    /** A seller payout in the finance queue; the account is masked (the bank batch carries the full number). */
    public record PayoutSummary(
            UUID id,
            UUID orgId,
            String organization,
            String marketCode,
            long amountMinor,
            String currency,
            String status,
            List<String> approvalReasons,
            String institutionName,
            String accountName,
            String accountNumberMasked,
            Instant createdAt,
            Instant exportedAt,
            Instant paidAt,
            String reference,
            String note) {}

    public record PayoutSettlement(
            @NotNull @Pattern(regexp = "PAID|FAILED|RETURNED")
            String outcome,

            @Size(max = 120) String reference,
            @Size(max = 500) String reason) {}

    public record PayoutExport(List<UUID> payoutIds) {}

    /** A refund in the finance queue, with who to pay and how. */
    public record RefundSummary(
            UUID id,
            UUID orgId,
            String organization,
            String marketCode,
            long amountMinor,
            String currency,
            String status,
            String method,
            String reason,
            String requestedBy,
            String customerName,
            String customerPhone,
            String paidWith,
            String bookingReference,
            Instant createdAt,
            Instant paidAt,
            String reference,
            String note) {}

    public record RefundPayment(
            @NotBlank @Size(max = 120) String reference,
            @Size(max = 500) String note) {}

    public record RefundApproval(@Size(max = 500) String note) {}

    public record Reason(@NotBlank @Size(min = 5, max = 500) String reason) {}

    public record AccessUrl(String url, Instant expiresAt) {}

    public record FlagOverride(String marketCode, UUID orgId, UUID userId, boolean enabled) {}

    public record Flag(
            String code, String description, boolean defaultEnabled, List<FlagOverride> overrides, Instant updatedAt) {}

    public record FlagUpdate(
            @NotNull Boolean defaultEnabled,
            @NotBlank @Size(min = 5, max = 500) String reason) {}

    /** A statement the finance team uploads (plan 3.9): the provider's lines for some days, as CSV. */
    public record StatementUpload(
            @jakarta.validation.constraints.NotNull UUID providerAccountId,
            @jakarta.validation.constraints.NotNull java.time.LocalDate from,
            @jakarta.validation.constraints.NotNull java.time.LocalDate to,
            Long openingBalanceMinor,
            Long closingBalanceMinor,
            @NotBlank @Size(max = 2_000_000) String csv) {}

    /** Why an exception is fine: the statement line, or the payment the statement lacks, or the balance (neither). */
    public record ExceptionResolution(
            @jakarta.validation.constraints.NotNull UUID statementId,
            UUID lineId,
            UUID attemptId,
            @NotBlank @Size(max = 500) String note) {}

    public record GoLive(
            @NotBlank @Size(min = 10, max = 1000) String evidence) {}

    public record Suspension(@NotBlank @Size(max = 500) String reason) {}
}
