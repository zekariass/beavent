package com.beavent.backend.tenancy.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response bodies of the tenancy API (services/backend/src/main/openapi/tenancy/schemas.yaml). */
public final class Bodies {

    private Bodies() {}

    public static final String PHONE = "^\\+[1-9][0-9]{6,14}$";
    public static final String SLUG = "^[a-z0-9]([a-z0-9-]{1,61}[a-z0-9])?$";
    public static final String LOCATION_SLUG = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$";
    public static final String KIND =
            "BUSINESS|INDEPENDENT_PROFESSIONAL|EVENT_ORGANIZER|VENUE_OPERATOR|NON_PROFIT|COMMUNITY_GROUP";
    public static final String CAPABILITY = "APPOINTMENTS|CLASSES|EVENTS|RESERVED_SEATING|RETAIL|ONLINE_STORE"
            + "|AT_HOME_SERVICES|GIFT_CARDS|MEMBERSHIPS|PACKAGES|LOYALTY|MARKETING|TEAM_PAY|AI_CONCIERGE|BOX_OFFICE"
            + "|PUBLIC_API";
    public static final String TIME = "^([01][0-9]|2[0-3]):[0-5][0-9]$";

    public record Items<T>(List<T> items) {}

    public record PageInfo(
            @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {}

    public record Page<T>(List<T> items, PageInfo page) {}

    // --- organizations ---------------------------------------------------------------------------------------

    public record OrganizationCreate(
            @NotNull @Size(min = 2, max = 120) String displayName,
            @Pattern(regexp = SLUG) String slug,
            @NotNull @Pattern(regexp = KIND) String kind,
            @NotNull UUID primaryCategoryId,
            @Size(max = 8) String marketCode,
            @NotBlank @Size(max = 10) String defaultLanguage,
            List<@NotNull @Pattern(regexp = CAPABILITY) String> capabilities) {}

    public record Organization(
            UUID id,
            String slug,
            String displayName,
            String kind,
            UUID primaryCategoryId,
            String status,
            String verificationStatus,
            String marketplaceStatus,
            String marketCode,
            Map<String, String> descriptionI18n,
            UUID logoFileId,
            UUID coverFileId,
            String websiteUrl,
            String contactEmail,
            String contactPhone,
            Map<String, String> socialLinks,
            String defaultLanguage,
            List<String> capabilities,
            Instant createdAt,
            long version) {}

    public record CapabilitiesUpdate(@NotNull List<@NotNull @Pattern(regexp = CAPABILITY) String> capabilities) {}

    // --- legal entities and locations ----------------------------------------------------------------------

    public record GeoPoint(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {}

    public record Address(
            @NotNull @Pattern(regexp = "^[A-Z]{2}$") String countryCode,
            UUID adminAreaId,
            @Size(max = 300) String addressLine,
            @Size(max = 300) String landmark,
            @Size(max = 120) String city,
            @Size(max = 20) String postalCode,
            @Valid GeoPoint geo) {}

    public record LegalEntityInput(
            @NotNull @Pattern(regexp = "^[A-Z]{2}$") String countryCode,

            @NotNull @Pattern(regexp = "SOLE_PROPRIETOR|PLC|SHARE_COMPANY|PARTNERSHIP|NGO|INDIVIDUAL|OTHER")
            String entityType,

            @NotBlank @Size(max = 200) String registeredName,
            @Size(max = 60) String tradeLicenseNumber,
            @Size(max = 30) String tin,
            @Size(max = 30) String vatNumber,

            @NotNull @Pattern(regexp = "VAT|TOT|PRESUMPTIVE|EXEMPT")
            String taxRegime,

            @Valid Address registeredAddress,
            Boolean isDefault) {}

    public record LegalEntity(
            UUID id,
            String countryCode,
            String entityType,
            String registeredName,
            String tradeLicenseNumber,
            String tin,
            String vatNumber,
            String taxRegime,
            Address registeredAddress,
            boolean isDefault,
            String functionalCurrency,
            String verificationStatus) {}

    public record OpeningHours(
            @NotNull @Min(1) @Max(7) Integer isoWeekday,
            @NotNull @Pattern(regexp = TIME) String opensAt,
            @NotNull @Pattern(regexp = TIME) String closesAt) {}

    public record LocationInput(
            @NotBlank @Size(max = 120) String name,
            @Pattern(regexp = LOCATION_SLUG) String slug,
            @NotNull UUID legalEntityId,
            @NotNull @Valid Address address,
            @NotBlank @Size(max = 60) String timezone,
            @Pattern(regexp = PHONE) String phone,
            @Email @Size(max = 200) String email,

            @Pattern(regexp = "ON_PREMISES|MOBILE_ONLY|BOTH")
            String serviceMode,

            Boolean onlineBooking,
            @Size(max = 50) List<@NotNull @Valid OpeningHours> hours,
            Boolean closedOnPublicHolidays) {}

    public record Location(
            UUID id,
            String name,
            String slug,
            UUID legalEntityId,
            Address address,
            String timezone,
            String phone,
            String email,
            String serviceMode,
            boolean onlineBooking,
            List<OpeningHours> hours,
            boolean closedOnPublicHolidays,
            UUID placeId,
            String currency,
            String status,
            boolean marketplaceListed,
            long version) {}

    public record ClosureInput(
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @Size(max = 200) String reason) {}

    public record Closure(UUID id, Instant startsAt, Instant endsAt, String reason) {}

    public record ServiceAreaInput(
            @NotBlank @Size(max = 80) String name,
            UUID adminAreaId,
            @Valid GeoPoint center,
            @Min(100) @Max(100000) Integer radiusMeters,
            @NotNull @Min(0) Long travelFeeMinor,
            @NotNull @Min(0) @Max(240) Integer travelMinutes,
            Boolean isActive) {}

    public record ServiceArea(
            UUID id,
            UUID locationId,
            String name,
            UUID adminAreaId,
            GeoPoint center,
            Integer radiusMeters,
            long travelFeeMinor,
            String currency,
            int travelMinutes,
            boolean isActive) {}

    // --- roles, members, invitations -----------------------------------------------------------------------

    public record Role(
            UUID id, String code, String name, String description, boolean isSystem, List<String> permissions) {}

    public record RoleCreate(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 300) String description,
            UUID basedOnRoleId,
            @NotNull @Size(max = 100) List<@NotBlank String> permissions) {}

    public record RoleUpdate(
            @Size(min = 1, max = 80) String name,
            @Size(max = 300) String description,
            @Size(max = 100) List<@NotBlank String> permissions) {}

    public record Member(
            UUID id,
            UUID userId,
            String displayName,
            String phone,
            UUID roleId,
            String roleCode,
            String status,
            boolean isOwner,
            boolean allLocations,
            List<UUID> locationIds,
            Instant joinedAt) {}

    public record MemberUpdate(
            UUID roleId,
            @Pattern(regexp = "ACTIVE|SUSPENDED") String status,
            Boolean allLocations,
            @Size(max = 500) List<@NotNull UUID> locationIds) {}

    public record InvitationCreate(
            @Pattern(regexp = PHONE) String phone,
            @Email @Size(max = 200) String email,
            @NotNull UUID roleId,
            @Size(max = 500) List<@NotNull UUID> locationIds) {}

    public record Invitation(
            UUID id,
            String phone,
            String email,
            UUID roleId,
            List<UUID> locationIds,
            String status,
            Instant expiresAt,
            Instant createdAt) {}

    public record InvitationAccept(
            @NotBlank @Size(max = 200) String token) {}

    // --- verification --------------------------------------------------------------------------------------

    public static final String DOC_TYPE = "TRADE_LICENSE|TIN_CERTIFICATE|VAT_CERTIFICATE|NATIONAL_ID|FAYDA_ID|PASSPORT"
            + "|KEBELE_ID|BANK_LETTER|PROFESSIONAL_LICENSE|VENUE_PERMIT|MEMORANDUM_OF_ASSOCIATION";

    public record VerificationDocumentInput(
            @NotNull @Pattern(regexp = DOC_TYPE) String docType,
            @NotNull UUID fileId,
            LocalDate expiresOn) {}

    public record VerificationCaseCreate(
            @NotNull @Pattern(regexp = "ORGANIZATION_KYB|OWNER_KYC")
            String subject,

            UUID legalEntityId,
            @NotEmpty @Size(max = 20) List<@NotNull @Valid VerificationDocumentInput> documents) {}

    public record VerificationDocument(UUID id, String docType, UUID fileId, LocalDate expiresOn, String status) {}

    public record VerificationCase(
            UUID id,
            String subject,
            String status,
            String decisionReason,
            Instant createdAt,
            Instant decidedAt,
            List<VerificationDocument> documents) {}
}
