package com.beavent.backend.catalog.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bodies of the catalogue API (backend/src/main/openapi/catalog/schemas.yaml). */
public final class CatalogBodies {

    private CatalogBodies() {}

    static final String PRICE_TYPE = "FIXED|FROM|FREE|ON_CONSULTATION";
    static final String RESOURCE_KIND = "ROOM|CHAIR|BED|STATION|EQUIPMENT|OTHER";
    static final String CURRENCY = "^[A-Z]{3}$";

    public record Items<T>(List<T> items) {}

    public record ServiceGroupInput(
            @NotEmpty Map<String, String> name,
            @Min(0) @Max(10000) Integer sortOrder) {}

    public record ServiceGroup(UUID id, Map<String, String> name, int sortOrder) {}

    public record VariantStage(
            @NotNull @Pattern(regexp = "SERVICE|PROCESSING|FINISHING|CONSULTATION")
            String kind,

            @NotNull @Min(1) @Max(1440) Integer durationMinutes,
            Boolean staffBusy) {}

    public record ResourceRequirement(
            UUID resourceId,
            @Pattern(regexp = RESOURCE_KIND) String resourceKind,
            @NotNull @Min(1) @Max(500) Integer units,
            @Min(1) Integer stageSeq) {}

    public record ServiceVariantInput(
            UUID id,
            Map<String, String> name,
            @NotNull @Min(5) @Max(1440) Integer durationMinutes,
            @Min(0) @Max(240) Integer bufferBeforeMinutes,
            @Min(0) @Max(240) Integer bufferAfterMinutes,
            @NotNull @Pattern(regexp = PRICE_TYPE) String priceType,
            @NotNull @Min(0) Long priceMinor,
            Boolean isDefault,
            @Size(max = 10) List<@NotNull @Valid VariantStage> stages,
            @Size(max = 10) List<@NotNull @Valid ResourceRequirement> resourceRequirements) {}

    public record ServiceVariant(
            UUID id,
            Map<String, String> name,
            int durationMinutes,
            int bufferBeforeMinutes,
            int bufferAfterMinutes,
            String priceType,
            long priceMinor,
            String currency,
            boolean isDefault,
            List<VariantStage> stages,
            List<ResourceRequirement> resourceRequirements) {}

    public record ServiceInput(
            UUID groupId,
            @NotNull UUID categoryId,
            @NotEmpty Map<String, String> name,
            Map<String, String> description,
            Map<String, String> aftercare,

            @Size(min = 1, max = 3)
            List<@NotNull @Pattern(regexp = "ON_PREMISES|AT_CUSTOMER|ONLINE") String> deliveryModes,

            @Pattern(regexp = "EVERYONE|WOMEN|MEN|CHILDREN") String audience,
            Boolean requiresPatchTest,
            @Min(1) @Max(3650) Integer patchTestValidityDays,
            Boolean onlineBooking,
            Boolean commissionEligible,
            @Pattern(regexp = "ACTIVE|HIDDEN") String status,
            @Min(0) @Max(10000) Integer sortOrder,
            @Pattern(regexp = CURRENCY) String currency,
            @NotEmpty @Size(max = 20) List<@NotNull @Valid ServiceVariantInput> variants,
            @Size(max = 200) List<@NotNull UUID> locationIds,
            @Size(max = 100) List<@NotNull UUID> addonIds) {}

    public record Service(
            UUID id,
            UUID groupId,
            UUID categoryId,
            Map<String, String> name,
            Map<String, String> description,
            Map<String, String> aftercare,
            List<String> deliveryModes,
            String audience,
            boolean requiresPatchTest,
            Integer patchTestValidityDays,
            boolean onlineBooking,
            boolean commissionEligible,
            String status,
            int sortOrder,
            String currency,
            List<ServiceVariant> variants,
            List<UUID> locationIds,
            List<UUID> addonIds,
            List<UUID> staffIds,
            long version) {}

    public record PriceOverride(
            @NotNull UUID variantId,
            UUID locationId,
            UUID staffId,
            UUID pricingLevelId,
            @NotNull @Min(0) Long priceMinor,
            @Min(5) @Max(1440) Integer durationMinutes) {}

    public record PriceOverrideList(
            @NotNull @Size(max = 500) List<@NotNull @Valid PriceOverride> items) {}

    public record AddonInput(
            @NotEmpty Map<String, String> name,
            @Min(0) @Max(480) Integer durationMinutes,
            @NotNull @Min(0) Long priceMinor,
            @Pattern(regexp = CURRENCY) String currency) {}

    public record Addon(
            UUID id, Map<String, String> name, int durationMinutes, long priceMinor, String currency, String status) {}

    public record ResourceInput(
            @NotNull UUID locationId,
            @NotNull @Size(min = 1, max = 80) String name,
            @NotNull @Pattern(regexp = RESOURCE_KIND) String kind,
            @Min(1) @Max(500) Integer units,
            Boolean isActive) {}

    public record Resource(UUID id, UUID locationId, String name, String kind, int units, boolean isActive) {}

    public record BundleItem(
            @NotNull UUID serviceVariantId, @Min(1) @Max(20) Integer quantity) {}

    public record BundleInput(
            @NotEmpty Map<String, String> name,
            Map<String, String> description,

            @NotNull @Pattern(regexp = "SUM_OF_ITEMS|FIXED_PRICE|PERCENT_OFF")
            String pricing,

            @Min(0) Long priceMinor,

            @DecimalMin(value = "0", inclusive = false) @DecimalMax(value = "100", inclusive = false)
            BigDecimal percentOff,

            @Pattern(regexp = "SEQUENTIAL|PARALLEL") String scheduling,
            Boolean onlineBooking,
            @NotNull @Size(min = 2, max = 20) List<@NotNull @Valid BundleItem> items) {}

    public record Bundle(
            UUID id,
            Map<String, String> name,
            Map<String, String> description,
            String pricing,
            Long priceMinor,
            BigDecimal percentOff,
            String currency,
            String scheduling,
            boolean onlineBooking,
            String status,
            List<BundleItem> items,
            long totalPriceMinor,
            int totalDurationMinutes) {}

    public record GalleryItemInput(
            @NotNull UUID fileId,
            UUID locationId,
            UUID serviceId,
            UUID staffId,
            Map<String, String> altText,
            @Min(0) @Max(10000) Integer sortOrder) {}

    public record GalleryItem(
            UUID id,
            UUID fileId,
            UUID locationId,
            UUID serviceId,
            UUID staffId,
            Map<String, String> altText,
            int sortOrder,
            String url,
            Map<String, String> variants) {}
}
