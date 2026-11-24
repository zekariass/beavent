package com.beavent.backend.catalog.internal;

import static com.beavent.db.catalog.Tables.ADDONS;
import static com.beavent.db.catalog.Tables.BUNDLES;
import static com.beavent.db.catalog.Tables.BUNDLE_ITEMS;
import static com.beavent.db.catalog.Tables.GALLERY_ITEMS;
import static com.beavent.db.catalog.Tables.RESOURCES;
import static com.beavent.db.catalog.Tables.SERVICE_VARIANTS;
import static com.beavent.db.tenant.Tables.LOCATIONS;

import com.beavent.backend.files.FileAccess;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import com.beavent.db.catalog.tables.records.AddonsRecord;
import com.beavent.db.catalog.tables.records.BundlesRecord;
import com.beavent.db.catalog.tables.records.GalleryItemsRecord;
import com.beavent.db.catalog.tables.records.ResourcesRecord;
import com.beavent.db.catalog.tables.records.ServiceVariantsRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Add-ons, resources, bundles and galleries. */
@Component
public class Extras {

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Localized localized;
    private final Currencies currencies;
    private final FileAccess files;

    Extras(
            DSLContext dsl,
            TransactionTemplate transactions,
            Localized localized,
            Currencies currencies,
            FileAccess files) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.localized = localized;
        this.currencies = currencies;
        this.files = files;
    }

    // --- add-ons -------------------------------------------------------------------------------------------

    public List<CatalogBodies.Addon> addons() {
        return transactions.execute(status -> dsl.selectFrom(ADDONS)
                .where(ADDONS.STATUS.eq("ACTIVE"))
                .orderBy(ADDONS.ID)
                .fetch(this::addon));
    }

    public CatalogBodies.Addon saveAddon(OrgAccess access, UUID addonId, CatalogBodies.AddonInput input) {
        access.require(Services.MANAGE);
        return transactions.execute(status -> {
            AddonsRecord addon = addonId == null ? dsl.newRecord(ADDONS) : found(ADDONS, ADDONS.ID.eq(addonId));
            addon.setOrgId(access.orgId());
            addon.setNameI18n(localized.name(input.name(), "name", 120));
            addon.setDurationMinutes((short) (input.durationMinutes() == null ? 0 : input.durationMinutes()));
            addon.setPriceMinor(input.priceMinor());
            addon.setCurrencyCode(currencies.priceCurrency(
                    access.orgId(),
                    input.currency() == null && addonId != null ? addon.getCurrencyCode() : input.currency()));
            addon.store();
            addon.refresh();
            return addon(addon);
        });
    }

    public void archiveAddon(OrgAccess access, UUID addonId) {
        access.require(Services.MANAGE);
        transactions.executeWithoutResult(status -> {
            AddonsRecord addon = found(ADDONS, ADDONS.ID.eq(addonId));
            addon.setStatus("ARCHIVED");
            addon.store();
        });
    }

    private CatalogBodies.Addon addon(AddonsRecord a) {
        return new CatalogBodies.Addon(
                a.getId(),
                localized.read(a.getNameI18n()),
                a.getDurationMinutes(),
                a.getPriceMinor(),
                a.getCurrencyCode(),
                a.getStatus());
    }

    // --- resources -----------------------------------------------------------------------------------------

    public List<CatalogBodies.Resource> resources(UUID locationId) {
        return transactions.execute(status -> dsl.selectFrom(RESOURCES)
                .where(locationId == null ? DSL.noCondition() : RESOURCES.LOCATION_ID.eq(locationId))
                .orderBy(RESOURCES.LOCATION_ID, RESOURCES.NAME)
                .fetch(Extras::resource));
    }

    public CatalogBodies.Resource saveResource(OrgAccess access, UUID resourceId, CatalogBodies.ResourceInput input) {
        access.require(Services.MANAGE);
        return transactions.execute(status -> {
            if (!dsl.fetchExists(LOCATIONS, LOCATIONS.ID.eq(input.locationId()))) {
                throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown location.");
            }
            ResourcesRecord resource =
                    resourceId == null ? dsl.newRecord(RESOURCES) : found(RESOURCES, RESOURCES.ID.eq(resourceId));
            resource.setOrgId(access.orgId());
            resource.setLocationId(input.locationId());
            resource.setName(input.name().strip());
            resource.setKind(input.kind());
            resource.setUnits((short) (input.units() == null ? 1 : input.units()));
            resource.setIsActive(input.isActive() == null || input.isActive());
            resource.store();
            return resource(resource);
        });
    }

    private static CatalogBodies.Resource resource(ResourcesRecord r) {
        return new CatalogBodies.Resource(
                r.getId(), r.getLocationId(), r.getName(), r.getKind(), r.getUnits(), r.getIsActive());
    }

    // --- bundles -------------------------------------------------------------------------------------------

    public List<CatalogBodies.Bundle> bundles() {
        return transactions.execute(status -> dsl.selectFrom(BUNDLES)
                .where(BUNDLES.STATUS.eq("ACTIVE"))
                .orderBy(BUNDLES.ID)
                .fetch(this::bundle));
    }

    public CatalogBodies.Bundle saveBundle(OrgAccess access, UUID bundleId, CatalogBodies.BundleInput input) {
        access.require(Services.MANAGE);
        boolean fixed = "FIXED_PRICE".equals(input.pricing());
        boolean percent = "PERCENT_OFF".equals(input.pricing());
        if (fixed != (input.priceMinor() != null) || percent != (input.percentOff() != null)) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED,
                    "Give priceMinor only for FIXED_PRICE and percentOff only for PERCENT_OFF.");
        }
        return transactions.execute(status -> {
            List<UUID> variantIds = input.items().stream()
                    .map(CatalogBodies.BundleItem::serviceVariantId)
                    .toList();
            Map<UUID, ServiceVariantsRecord> variants = dsl.selectFrom(SERVICE_VARIANTS)
                    .where(SERVICE_VARIANTS.ID.in(variantIds), SERVICE_VARIANTS.STATUS.eq("ACTIVE"))
                    .fetchMap(SERVICE_VARIANTS.ID);
            if (variants.size() != new java.util.HashSet<>(variantIds).size()) {
                throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown service variant.");
            }
            if (variants.values().stream()
                            .map(ServiceVariantsRecord::getCurrencyCode)
                            .distinct()
                            .count()
                    > 1) {
                throw new ApiException(
                        ProblemCode.CONSTRAINT_VIOLATION, "All services in a bundle need the same currency.");
            }
            BundlesRecord bundle = bundleId == null ? dsl.newRecord(BUNDLES) : found(BUNDLES, BUNDLES.ID.eq(bundleId));
            bundle.setOrgId(access.orgId());
            bundle.setNameI18n(localized.name(input.name(), "name", 120));
            bundle.setDescriptionI18n(localized.optional(input.description(), "description", 2000));
            bundle.setPricing(input.pricing());
            bundle.setPriceMinor(input.priceMinor());
            bundle.setPercentOff(
                    input.percentOff() == null ? null : input.percentOff().setScale(2, RoundingMode.HALF_UP));
            bundle.setCurrencyCode(variants.values().iterator().next().getCurrencyCode());
            bundle.setScheduling(input.scheduling() == null ? "SEQUENTIAL" : input.scheduling());
            bundle.setOnlineBooking(input.onlineBooking() == null || input.onlineBooking());
            bundle.store();
            dsl.deleteFrom(BUNDLE_ITEMS)
                    .where(BUNDLE_ITEMS.BUNDLE_ID.eq(bundle.getId()))
                    .execute();
            for (int i = 0; i < input.items().size(); i++) {
                CatalogBodies.BundleItem item = input.items().get(i);
                dsl.insertInto(BUNDLE_ITEMS)
                        .set(BUNDLE_ITEMS.ORG_ID, access.orgId())
                        .set(BUNDLE_ITEMS.BUNDLE_ID, bundle.getId())
                        .set(BUNDLE_ITEMS.SEQ, (short) (i + 1))
                        .set(BUNDLE_ITEMS.SERVICE_VARIANT_ID, item.serviceVariantId())
                        .set(BUNDLE_ITEMS.QUANTITY, (short) (item.quantity() == null ? 1 : item.quantity()))
                        .execute();
            }
            bundle.refresh();
            return bundle(bundle);
        });
    }

    public void archiveBundle(OrgAccess access, UUID bundleId) {
        access.require(Services.MANAGE);
        transactions.executeWithoutResult(status -> {
            BundlesRecord bundle = found(BUNDLES, BUNDLES.ID.eq(bundleId));
            bundle.setStatus("ARCHIVED");
            bundle.store();
        });
    }

    private CatalogBodies.Bundle bundle(BundlesRecord b) {
        var rows = dsl.select(
                        BUNDLE_ITEMS.SERVICE_VARIANT_ID,
                        BUNDLE_ITEMS.QUANTITY,
                        SERVICE_VARIANTS.PRICE_MINOR,
                        SERVICE_VARIANTS.DURATION_MINUTES)
                .from(BUNDLE_ITEMS)
                .join(SERVICE_VARIANTS)
                .on(SERVICE_VARIANTS.ID.eq(BUNDLE_ITEMS.SERVICE_VARIANT_ID))
                .where(BUNDLE_ITEMS.BUNDLE_ID.eq(b.getId()))
                .orderBy(BUNDLE_ITEMS.SEQ)
                .fetch();
        long sum = 0;
        int sequential = 0;
        int longest = 0;
        for (var row : rows) {
            int quantity = row.get(BUNDLE_ITEMS.QUANTITY);
            sum += row.get(SERVICE_VARIANTS.PRICE_MINOR) * quantity;
            sequential += row.get(SERVICE_VARIANTS.DURATION_MINUTES) * quantity;
            longest = Math.max(longest, row.get(SERVICE_VARIANTS.DURATION_MINUTES));
        }
        long total = switch (b.getPricing()) {
            case "FIXED_PRICE" -> b.getPriceMinor();
            // Rounded half up to the minor unit, like every percentage on the platform.
            case "PERCENT_OFF" ->
                BigDecimal.valueOf(sum)
                        .multiply(BigDecimal.valueOf(100).subtract(b.getPercentOff()))
                        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                        .longValueExact();
            default -> sum;
        };
        return new CatalogBodies.Bundle(
                b.getId(),
                localized.read(b.getNameI18n()),
                localized.read(b.getDescriptionI18n()),
                b.getPricing(),
                b.getPriceMinor(),
                b.getPercentOff(),
                b.getCurrencyCode(),
                b.getScheduling(),
                b.getOnlineBooking(),
                b.getStatus(),
                rows.stream()
                        .map(r -> new CatalogBodies.BundleItem(
                                r.get(BUNDLE_ITEMS.SERVICE_VARIANT_ID), (int) r.get(BUNDLE_ITEMS.QUANTITY)))
                        .toList(),
                total,
                "PARALLEL".equals(b.getScheduling()) ? longest : sequential);
    }

    // --- gallery -------------------------------------------------------------------------------------------

    public List<CatalogBodies.GalleryItem> gallery(UUID locationId, UUID serviceId, UUID staffId) {
        Condition subject = locationId != null
                ? GALLERY_ITEMS.LOCATION_ID.eq(locationId)
                : serviceId != null
                        ? GALLERY_ITEMS.SERVICE_ID.eq(serviceId)
                        : staffId != null
                                ? GALLERY_ITEMS.STAFF_ID.eq(staffId)
                                : GALLERY_ITEMS
                                        .LOCATION_ID
                                        .isNull()
                                        .and(GALLERY_ITEMS.SERVICE_ID.isNull())
                                        .and(GALLERY_ITEMS.STAFF_ID.isNull())
                                        .and(GALLERY_ITEMS.PRODUCT_ID.isNull())
                                        .and(GALLERY_ITEMS.EVENT_ID.isNull());
        return transactions.execute(
                status -> dsl
                        .selectFrom(GALLERY_ITEMS)
                        .where(subject)
                        .orderBy(GALLERY_ITEMS.SORT_ORDER, GALLERY_ITEMS.ID)
                        .fetch()
                        .stream()
                        .map(this::galleryItem)
                        .flatMap(Optional::stream)
                        .toList());
    }

    public CatalogBodies.GalleryItem addToGallery(OrgAccess access, CatalogBodies.GalleryItemInput input) {
        access.require(Services.MANAGE);
        long subjects = java.util.stream.Stream.of(input.locationId(), input.serviceId(), input.staffId())
                .filter(java.util.Objects::nonNull)
                .count();
        if (subjects > 1) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED, "An image belongs to one location, service or team member.");
        }
        FileAccess.PublicImage image = files.publicImage(input.fileId())
                .filter(i -> access.orgId().equals(i.orgId()))
                .filter(i -> "GALLERY".equals(i.purpose()) || "COVER".equals(i.purpose()))
                .orElseThrow(() -> new ApiException(
                        ProblemCode.REFERENCE_NOT_FOUND, "Upload a gallery image first and wait until it is ready."));
        return transactions.execute(status -> {
            GalleryItemsRecord item = dsl.newRecord(GALLERY_ITEMS);
            item.setOrgId(access.orgId());
            item.setFileId(image.fileId());
            item.setLocationId(input.locationId());
            item.setServiceId(input.serviceId());
            item.setStaffId(input.staffId());
            item.setAltTextI18n(localized.optional(input.altText(), "altText", 300));
            item.setSortOrder((short) (input.sortOrder() == null ? 0 : input.sortOrder()));
            item.store();
            return galleryItem(item).orElseThrow();
        });
    }

    public void removeFromGallery(OrgAccess access, UUID itemId) {
        access.require(Services.MANAGE);
        transactions.executeWithoutResult(
                status -> found(GALLERY_ITEMS, GALLERY_ITEMS.ID.eq(itemId)).delete());
    }

    private Optional<CatalogBodies.GalleryItem> galleryItem(GalleryItemsRecord item) {
        return files.publicImage(item.getFileId())
                .map(image -> new CatalogBodies.GalleryItem(
                        item.getId(),
                        item.getFileId(),
                        item.getLocationId(),
                        item.getServiceId(),
                        item.getStaffId(),
                        localized.read(item.getAltTextI18n()),
                        item.getSortOrder(),
                        image.url(),
                        image.variants()));
    }

    private <R extends org.jooq.TableRecord<R>> R found(org.jooq.Table<R> table, Condition condition) {
        R record = dsl.selectFrom(table).where(condition).fetchOne();
        if (record == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        return record;
    }
}
