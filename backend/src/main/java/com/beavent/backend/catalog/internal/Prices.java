package com.beavent.backend.catalog.internal;

import static com.beavent.db.catalog.Tables.SERVICE_PRICES;
import static com.beavent.db.catalog.Tables.SERVICE_VARIANTS;
import static com.beavent.db.team.Tables.STAFF;

import com.beavent.backend.catalog.ServicePricing;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import com.beavent.db.catalog.tables.records.ServiceVariantsRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Price overrides per location, team member or pricing level, effective-dated (validity bounds may be ±infinity, so
 * they are compared in SQL and never read into Java): a change closes the current rows and
 * starts new ones, so earlier bookings keep the price they were made at.
 */
@Component
public class Prices implements ServicePricing {

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Audit audit;

    Prices(DSLContext dsl, TransactionTemplate transactions, Audit audit) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.audit = audit;
    }

    public List<CatalogBodies.PriceOverride> current(UUID serviceId) {
        return transactions.execute(status -> {
            requireService(serviceId);
            return dsl.select(
                            SERVICE_PRICES.VARIANT_ID,
                            SERVICE_PRICES.LOCATION_ID,
                            SERVICE_PRICES.STAFF_ID,
                            SERVICE_PRICES.PRICING_LEVEL_ID,
                            SERVICE_PRICES.PRICE_MINOR,
                            SERVICE_PRICES.DURATION_MINUTES)
                    .from(SERVICE_PRICES)
                    .join(SERVICE_VARIANTS)
                    .on(SERVICE_VARIANTS.ID.eq(SERVICE_PRICES.VARIANT_ID))
                    .where(SERVICE_VARIANTS.SERVICE_ID.eq(serviceId), effectiveAt(DSL.currentOffsetDateTime()))
                    .orderBy(SERVICE_PRICES.VARIANT_ID, SERVICE_PRICES.ID)
                    .fetch(Prices::override);
        });
    }

    public List<CatalogBodies.PriceOverride> replace(
            OrgAccess access, UUID serviceId, List<CatalogBodies.PriceOverride> overrides) {
        access.require(Services.MANAGE);
        return transactions.execute(status -> {
            Map<UUID, ServiceVariantsRecord> variants = requireService(serviceId);
            Set<List<Object>> seen = new HashSet<>();
            for (CatalogBodies.PriceOverride o : overrides) {
                ServiceVariantsRecord variant = variants.get(o.variantId());
                if (variant == null || !"ACTIVE".equals(variant.getStatus())) {
                    throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown variant " + o.variantId() + ".");
                }
                if (o.staffId() != null && o.pricingLevelId() != null) {
                    throw new ApiException(
                            ProblemCode.VALIDATION_FAILED,
                            "A price is for a team member or for a pricing level, not both.");
                }
                if (o.locationId() == null && o.staffId() == null && o.pricingLevelId() == null) {
                    throw new ApiException(
                            ProblemCode.VALIDATION_FAILED,
                            "Give a location, team member or pricing level (the variant has the default price).");
                }
                if (!seen.add(
                        java.util.Arrays.asList(o.variantId(), o.locationId(), o.staffId(), o.pricingLevelId()))) {
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "The same price is listed twice.");
                }
            }
            List<UUID> variantIds = List.copyOf(variants.keySet());
            OffsetDateTime now =
                    dsl.select(DSL.currentOffsetDateTime()).fetchSingle().value1();
            // Prices set earlier in this same instant are replaced; older ones end now and stay as history.
            dsl.deleteFrom(SERVICE_PRICES)
                    .where(SERVICE_PRICES.VARIANT_ID.in(variantIds), SERVICE_PRICES.VALID_FROM.ge(now))
                    .execute();
            dsl.update(SERVICE_PRICES)
                    .set(SERVICE_PRICES.VALID_TO, now)
                    .where(SERVICE_PRICES.VARIANT_ID.in(variantIds), effectiveAt(DSL.val(now)))
                    .execute();
            for (CatalogBodies.PriceOverride o : overrides) {
                dsl.insertInto(SERVICE_PRICES)
                        .set(SERVICE_PRICES.ORG_ID, access.orgId())
                        .set(SERVICE_PRICES.VARIANT_ID, o.variantId())
                        .set(SERVICE_PRICES.LOCATION_ID, o.locationId())
                        .set(SERVICE_PRICES.STAFF_ID, o.staffId())
                        .set(SERVICE_PRICES.PRICING_LEVEL_ID, o.pricingLevelId())
                        .set(SERVICE_PRICES.PRICE_MINOR, o.priceMinor())
                        .set(
                                SERVICE_PRICES.CURRENCY_CODE,
                                variants.get(o.variantId()).getCurrencyCode())
                        .set(
                                SERVICE_PRICES.DURATION_MINUTES,
                                o.durationMinutes() == null
                                        ? null
                                        : o.durationMinutes().shortValue())
                        .set(SERVICE_PRICES.VALID_FROM, now)
                        .execute();
            }
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    access.userId(),
                    access.orgId(),
                    "service.prices_changed",
                    "catalog.service",
                    serviceId.toString(),
                    null,
                    Map.of("overrides", overrides.size()),
                    null));
            return overrides;
        });
    }

    @Override
    public Price resolve(UUID variantId, UUID locationId, UUID staffId, Instant at) {
        ServiceVariantsRecord variant = dsl.selectFrom(SERVICE_VARIANTS)
                .where(SERVICE_VARIANTS.ID.eq(variantId))
                .fetchOne();
        if (variant == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        UUID level = staffId == null
                ? null
                : dsl.select(STAFF.PRICING_LEVEL_ID)
                        .from(STAFF)
                        .where(STAFF.ID.eq(staffId))
                        .fetchOne(STAFF.PRICING_LEVEL_ID);
        var candidates = dsl.select(
                        SERVICE_PRICES.VARIANT_ID,
                        SERVICE_PRICES.LOCATION_ID,
                        SERVICE_PRICES.STAFF_ID,
                        SERVICE_PRICES.PRICING_LEVEL_ID,
                        SERVICE_PRICES.PRICE_MINOR,
                        SERVICE_PRICES.DURATION_MINUTES)
                .from(SERVICE_PRICES)
                .where(
                        SERVICE_PRICES.VARIANT_ID.eq(variantId),
                        effectiveAt(DSL.val(OffsetDateTime.ofInstant(at, ZoneOffset.UTC))))
                .fetch();
        Record best = null;
        Source bestSource = Source.DEFAULT;
        for (Record row : candidates) {
            Source source = match(row, locationId, staffId, level);
            if (source != null && source.ordinal() < bestSource.ordinal()) {
                best = row;
                bestSource = source;
            }
        }
        if ("FREE".equals(variant.getPriceType()) || "ON_CONSULTATION".equals(variant.getPriceType())) {
            // These variants have no price to override; durations may still differ.
            return new Price(
                    variantId,
                    variant.getPriceType(),
                    variant.getPriceMinor(),
                    variant.getCurrencyCode(),
                    duration(best, variant),
                    bestSource);
        }
        return new Price(
                variantId,
                variant.getPriceType(),
                best == null ? variant.getPriceMinor() : best.get(SERVICE_PRICES.PRICE_MINOR),
                variant.getCurrencyCode(),
                duration(best, variant),
                bestSource);
    }

    /** How specifically a row applies to the context, or {@code null} if it does not apply. */
    private static Source match(Record row, UUID locationId, UUID staffId, UUID level) {
        UUID rowLocation = row.get(SERVICE_PRICES.LOCATION_ID);
        UUID rowStaff = row.get(SERVICE_PRICES.STAFF_ID);
        UUID rowLevel = row.get(SERVICE_PRICES.PRICING_LEVEL_ID);
        boolean locationOk = rowLocation == null || rowLocation.equals(locationId);
        if (!locationOk) {
            return null;
        }
        boolean atLocation = rowLocation != null;
        if (rowStaff != null) {
            return rowStaff.equals(staffId) ? (atLocation ? Source.STAFF_AND_LOCATION : Source.STAFF) : null;
        }
        if (rowLevel != null) {
            return rowLevel.equals(level) ? (atLocation ? Source.LEVEL_AND_LOCATION : Source.LEVEL) : null;
        }
        return atLocation ? Source.LOCATION : null;
    }

    private static int duration(Record best, ServiceVariantsRecord variant) {
        Short override = best == null ? null : best.get(SERVICE_PRICES.DURATION_MINUTES);
        return override != null ? override : variant.getDurationMinutes();
    }

    private static Condition effectiveAt(org.jooq.Field<OffsetDateTime> at) {
        return SERVICE_PRICES.VALID_FROM.le(at).and(SERVICE_PRICES.VALID_TO.gt(at));
    }

    private Map<UUID, ServiceVariantsRecord> requireService(UUID serviceId) {
        Map<UUID, ServiceVariantsRecord> variants = dsl.selectFrom(SERVICE_VARIANTS)
                .where(SERVICE_VARIANTS.SERVICE_ID.eq(serviceId))
                .fetchMap(SERVICE_VARIANTS.ID);
        if (variants.isEmpty()) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        return variants;
    }

    private static CatalogBodies.PriceOverride override(Record row) {
        Short duration = row.get(SERVICE_PRICES.DURATION_MINUTES);
        return new CatalogBodies.PriceOverride(
                row.get(SERVICE_PRICES.VARIANT_ID),
                row.get(SERVICE_PRICES.LOCATION_ID),
                row.get(SERVICE_PRICES.STAFF_ID),
                row.get(SERVICE_PRICES.PRICING_LEVEL_ID),
                row.get(SERVICE_PRICES.PRICE_MINOR),
                duration == null ? null : (int) duration);
    }
}
