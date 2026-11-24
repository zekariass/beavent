package com.beavent.backend.catalog.internal;

import static com.beavent.db.catalog.Tables.ADDONS;
import static com.beavent.db.catalog.Tables.LOCATION_SERVICES;
import static com.beavent.db.catalog.Tables.RESOURCES;
import static com.beavent.db.catalog.Tables.SERVICES;
import static com.beavent.db.catalog.Tables.SERVICE_ADDONS;
import static com.beavent.db.catalog.Tables.SERVICE_GROUPS;
import static com.beavent.db.catalog.Tables.SERVICE_RESOURCE_REQUIREMENTS;
import static com.beavent.db.catalog.Tables.SERVICE_VARIANTS;
import static com.beavent.db.catalog.Tables.SERVICE_VARIANT_STAGES;
import static com.beavent.db.catalog.Tables.STAFF_SERVICES;
import static com.beavent.db.ref.Tables.CATEGORIES;
import static com.beavent.db.tenant.Tables.LOCATIONS;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.CursorPage;
import com.beavent.backend.platform.Cursors;
import com.beavent.backend.platform.ETags;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import com.beavent.db.catalog.tables.records.ServiceGroupsRecord;
import com.beavent.db.catalog.tables.records.ServiceVariantsRecord;
import com.beavent.db.catalog.tables.records.ServicesRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Menu sections, services and their bookable variants. */
@Component
public class Services {

    static final String MANAGE = "catalog.manage";

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Localized localized;
    private final Currencies currencies;
    private final Audit audit;

    Services(
            DSLContext dsl, TransactionTemplate transactions, Localized localized, Currencies currencies, Audit audit) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.localized = localized;
        this.currencies = currencies;
        this.audit = audit;
    }

    // --- groups --------------------------------------------------------------------------------------------

    public List<CatalogBodies.ServiceGroup> groups() {
        return transactions.execute(status -> dsl.selectFrom(SERVICE_GROUPS)
                .orderBy(SERVICE_GROUPS.SORT_ORDER, SERVICE_GROUPS.ID)
                .fetch(this::group));
    }

    public CatalogBodies.ServiceGroup createGroup(OrgAccess access, CatalogBodies.ServiceGroupInput input) {
        access.require(MANAGE);
        return transactions.execute(status -> {
            ServiceGroupsRecord group = dsl.newRecord(SERVICE_GROUPS);
            group.setOrgId(access.orgId());
            group.setNameI18n(localized.name(input.name(), "name", 80));
            group.setSortOrder(input.sortOrder() == null ? 0 : input.sortOrder().shortValue());
            group.store();
            return group(group);
        });
    }

    public CatalogBodies.ServiceGroup updateGroup(
            OrgAccess access, UUID groupId, CatalogBodies.ServiceGroupInput input) {
        access.require(MANAGE);
        return transactions.execute(status -> {
            ServiceGroupsRecord group = foundGroup(groupId);
            group.setNameI18n(localized.name(input.name(), "name", 80));
            if (input.sortOrder() != null) {
                group.setSortOrder(input.sortOrder().shortValue());
            }
            group.store();
            return group(group);
        });
    }

    public void deleteGroup(OrgAccess access, UUID groupId) {
        access.require(MANAGE);
        transactions.executeWithoutResult(status -> {
            ServiceGroupsRecord group = foundGroup(groupId);
            if (dsl.fetchExists(SERVICES, SERVICES.GROUP_ID.eq(groupId).and(SERVICES.STATUS.ne("ARCHIVED")))) {
                throw new ApiException(ProblemCode.CONFLICT, "Move or archive the services in this section first.");
            }
            dsl.update(SERVICES)
                    .setNull(SERVICES.GROUP_ID)
                    .where(SERVICES.GROUP_ID.eq(groupId))
                    .execute();
            group.delete();
        });
    }

    private ServiceGroupsRecord foundGroup(UUID groupId) {
        ServiceGroupsRecord group = dsl.selectFrom(SERVICE_GROUPS)
                .where(SERVICE_GROUPS.ID.eq(groupId))
                .fetchOne();
        if (group == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        return group;
    }

    private CatalogBodies.ServiceGroup group(ServiceGroupsRecord group) {
        return new CatalogBodies.ServiceGroup(group.getId(), localized.read(group.getNameI18n()), group.getSortOrder());
    }

    // --- services ------------------------------------------------------------------------------------------

    public CursorPage<CatalogBodies.Service> list(
            UUID groupId, UUID locationId, String status, String cursor, int limit) {
        return transactions.execute(tx -> {
            Condition condition = status == null ? SERVICES.STATUS.ne("ARCHIVED") : SERVICES.STATUS.eq(status);
            if (groupId != null) {
                condition = condition.and(SERVICES.GROUP_ID.eq(groupId));
            }
            if (locationId != null) {
                condition = condition.and(DSL.exists(DSL.selectOne()
                        .from(LOCATION_SERVICES)
                        .where(
                                LOCATION_SERVICES.SERVICE_ID.eq(SERVICES.ID),
                                LOCATION_SERVICES.LOCATION_ID.eq(locationId))));
            }
            if (cursor != null) {
                try {
                    condition = condition.and(SERVICES.ID.gt(
                            UUID.fromString(Cursors.decode(cursor, 1).getFirst())));
                } catch (IllegalArgumentException e) {
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "Invalid cursor.");
                }
            }
            List<ServicesRecord> rows = dsl.selectFrom(SERVICES)
                    .where(condition)
                    .orderBy(SERVICES.ID)
                    .limit(limit + 1)
                    .fetch();
            boolean more = rows.size() > limit;
            List<CatalogBodies.Service> items = views(more ? rows.subList(0, limit) : rows);
            return new CursorPage<>(
                    items, more ? Cursors.encode(items.getLast().id().toString()) : null);
        });
    }

    public CatalogBodies.Service get(UUID serviceId) {
        return transactions.execute(
                status -> views(List.of(found(serviceId, false))).getFirst());
    }

    public CatalogBodies.Service create(OrgAccess access, CatalogBodies.ServiceInput input) {
        access.require(MANAGE);
        return transactions.execute(status -> {
            ServicesRecord service = dsl.newRecord(SERVICES);
            service.setOrgId(access.orgId());
            String currency = currencies.priceCurrency(access.orgId(), input.currency());
            apply(service, input);
            service.store();
            writeChildren(access.orgId(), service.getId(), input, currency);
            audit.record(entry(access, "service.created", service.getId(), Map.of("name", input.name())));
            return views(List.of(found(service.getId(), false))).getFirst();
        });
    }

    public CatalogBodies.Service update(
            OrgAccess access, UUID serviceId, CatalogBodies.ServiceInput input, String ifMatch) {
        access.require(MANAGE);
        return transactions.execute(status -> {
            ServicesRecord service = found(serviceId, true);
            ETags.requireMatch(ifMatch, version(service.getUpdatedAt()));
            String current = currencyOf(serviceId);
            String currency = input.currency() == null && current != null
                    ? current
                    : currencies.priceCurrency(access.orgId(), input.currency());
            apply(service, input);
            // Children change below: move the service's version on even if its own columns did not change.
            service.setUpdatedAt(OffsetDateTime.now());
            service.store();
            writeChildren(access.orgId(), serviceId, input, currency);
            audit.record(entry(access, "service.updated", serviceId, Map.of("name", input.name())));
            return views(List.of(found(serviceId, false))).getFirst();
        });
    }

    public void archive(OrgAccess access, UUID serviceId) {
        access.require(MANAGE);
        transactions.executeWithoutResult(status -> {
            ServicesRecord service = found(serviceId, true);
            service.setStatus("ARCHIVED");
            service.store();
            audit.record(entry(access, "service.archived", serviceId, Map.of()));
        });
    }

    private ServicesRecord found(UUID serviceId, boolean lock) {
        var query = dsl.selectFrom(SERVICES).where(SERVICES.ID.eq(serviceId));
        ServicesRecord service = lock ? query.forUpdate().fetchOne() : query.fetchOne();
        if (service == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        return service;
    }

    private String currencyOf(UUID serviceId) {
        return dsl.select(SERVICE_VARIANTS.CURRENCY_CODE)
                .from(SERVICE_VARIANTS)
                .where(SERVICE_VARIANTS.SERVICE_ID.eq(serviceId))
                .limit(1)
                .fetchOne(SERVICE_VARIANTS.CURRENCY_CODE);
    }

    private void apply(ServicesRecord service, CatalogBodies.ServiceInput input) {
        if (input.groupId() != null && !dsl.fetchExists(SERVICE_GROUPS, SERVICE_GROUPS.ID.eq(input.groupId()))) {
            throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown menu section.");
        }
        boolean category = dsl.fetchExists(
                CATEGORIES,
                CATEGORIES
                        .ID
                        .eq(input.categoryId())
                        .and(CATEGORIES.KIND.eq("SERVICE"))
                        .and(CATEGORIES.IS_ACTIVE));
        if (!category) {
            throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown service category.");
        }
        boolean patchTest = Boolean.TRUE.equals(input.requiresPatchTest());
        service.setGroupId(input.groupId());
        service.setCategoryId(input.categoryId());
        service.setNameI18n(localized.name(input.name(), "name", 120));
        service.setDescriptionI18n(localized.optional(input.description(), "description", 2000));
        service.setAftercareI18n(localized.optional(input.aftercare(), "aftercare", 2000));
        service.setDeliveryModes(
                input.deliveryModes() == null
                        ? new String[] {"ON_PREMISES"}
                        : new LinkedHashSet<>(input.deliveryModes()).toArray(String[]::new));
        service.setAudience(input.audience() == null ? "EVERYONE" : input.audience());
        service.setRequiresPatchTest(patchTest);
        service.setPatchTestValidityDays(
                patchTest && input.patchTestValidityDays() != null
                        ? input.patchTestValidityDays().shortValue()
                        : null);
        service.setOnlineBooking(input.onlineBooking() == null || input.onlineBooking());
        service.setCommissionEligible(input.commissionEligible() == null || input.commissionEligible());
        service.setStatus(input.status() == null ? "ACTIVE" : input.status());
        service.setSortOrder(input.sortOrder() == null ? 0 : input.sortOrder().shortValue());
    }

    /** Variants (kept by id, added, or archived when left out), locations and add-ons. */
    private void writeChildren(UUID orgId, UUID serviceId, CatalogBodies.ServiceInput input, String currency) {
        List<CatalogBodies.ServiceVariantInput> variants = input.variants();
        long defaults = variants.stream()
                .filter(v -> Boolean.TRUE.equals(v.isDefault()))
                .count();
        if (defaults > 1) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Only one variant can be the default.");
        }
        int defaultIndex = defaults == 0 ? 0 : indexOfDefault(variants);
        Map<UUID, ServiceVariantsRecord> existing = dsl.selectFrom(SERVICE_VARIANTS)
                .where(SERVICE_VARIANTS.SERVICE_ID.eq(serviceId))
                .fetchMap(SERVICE_VARIANTS.ID);
        // Clear the default first: one active default per service is enforced by a unique index.
        dsl.update(SERVICE_VARIANTS)
                .set(SERVICE_VARIANTS.IS_DEFAULT, false)
                .where(SERVICE_VARIANTS.SERVICE_ID.eq(serviceId))
                .execute();
        Set<UUID> kept = new HashSet<>();
        for (int i = 0; i < variants.size(); i++) {
            CatalogBodies.ServiceVariantInput v = variants.get(i);
            validateVariant(v);
            ServiceVariantsRecord row;
            if (v.id() != null) {
                row = existing.get(v.id());
                if (row == null || !kept.add(v.id())) {
                    throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown variant " + v.id() + ".");
                }
            } else {
                row = dsl.newRecord(SERVICE_VARIANTS);
                row.setOrgId(orgId);
                row.setServiceId(serviceId);
            }
            row.setNameI18n(localized.optional(v.name(), "variant name", 120));
            row.setDurationMinutes(v.durationMinutes().shortValue());
            row.setBufferBeforeMin((short) (v.bufferBeforeMinutes() == null ? 0 : v.bufferBeforeMinutes()));
            row.setBufferAfterMin((short) (v.bufferAfterMinutes() == null ? 0 : v.bufferAfterMinutes()));
            row.setPriceType(v.priceType());
            row.setPriceMinor("FREE".equals(v.priceType()) ? 0L : v.priceMinor());
            row.setCurrencyCode(currency);
            row.setStatus("ACTIVE");
            row.setSortOrder((short) i);
            row.setIsDefault(i == defaultIndex);
            row.store();
            kept.add(row.getId());
            writeStages(orgId, row.getId(), v);
        }
        // Variants left out are archived, never deleted: past bookings refer to them.
        dsl.update(SERVICE_VARIANTS)
                .set(SERVICE_VARIANTS.STATUS, "ARCHIVED")
                .where(SERVICE_VARIANTS.SERVICE_ID.eq(serviceId), SERVICE_VARIANTS.ID.notIn(kept))
                .execute();
        if (!kept.containsAll(existing.keySet())
                || existing.values().stream().anyMatch(r -> !r.getCurrencyCode().equals(currency))) {
            dsl.update(SERVICE_VARIANTS)
                    .set(SERVICE_VARIANTS.CURRENCY_CODE, currency)
                    .where(SERVICE_VARIANTS.SERVICE_ID.eq(serviceId))
                    .execute();
        }

        List<UUID> locations = input.locationIds() == null
                ? dsl.select(LOCATIONS.ID)
                        .from(LOCATIONS)
                        .where(LOCATIONS.STATUS.ne("ARCHIVED"))
                        .fetch(LOCATIONS.ID)
                : List.copyOf(new LinkedHashSet<>(input.locationIds()));
        if (dsl.fetchCount(LOCATIONS, LOCATIONS.ID.in(locations)) != locations.size()) {
            throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown location.");
        }
        dsl.deleteFrom(LOCATION_SERVICES)
                .where(LOCATION_SERVICES.SERVICE_ID.eq(serviceId))
                .execute();
        for (UUID locationId : locations) {
            dsl.insertInto(LOCATION_SERVICES)
                    .set(LOCATION_SERVICES.ORG_ID, orgId)
                    .set(LOCATION_SERVICES.LOCATION_ID, locationId)
                    .set(LOCATION_SERVICES.SERVICE_ID, serviceId)
                    .execute();
        }

        List<UUID> addons = input.addonIds() == null ? List.of() : List.copyOf(new LinkedHashSet<>(input.addonIds()));
        if (dsl.fetchCount(ADDONS, ADDONS.ID.in(addons).and(ADDONS.STATUS.eq("ACTIVE"))) != addons.size()) {
            throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown add-on.");
        }
        dsl.deleteFrom(SERVICE_ADDONS)
                .where(SERVICE_ADDONS.SERVICE_ID.eq(serviceId))
                .execute();
        for (UUID addonId : addons) {
            dsl.insertInto(SERVICE_ADDONS)
                    .set(SERVICE_ADDONS.ORG_ID, orgId)
                    .set(SERVICE_ADDONS.SERVICE_ID, serviceId)
                    .set(SERVICE_ADDONS.ADDON_ID, addonId)
                    .execute();
        }
    }

    private static int indexOfDefault(List<CatalogBodies.ServiceVariantInput> variants) {
        for (int i = 0; i < variants.size(); i++) {
            if (Boolean.TRUE.equals(variants.get(i).isDefault())) {
                return i;
            }
        }
        return 0;
    }

    private static void validateVariant(CatalogBodies.ServiceVariantInput v) {
        if ("FIXED".equals(v.priceType()) && v.priceMinor() <= 0) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "A fixed price must be more than zero (use FREE).");
        }
        List<CatalogBodies.VariantStage> stages = v.stages() == null ? List.of() : v.stages();
        if (!stages.isEmpty()) {
            int total = stages.stream()
                    .mapToInt(CatalogBodies.VariantStage::durationMinutes)
                    .sum();
            if (total != v.durationMinutes()) {
                throw new ApiException(
                        ProblemCode.VALIDATION_FAILED,
                        "The stages add up to " + total + " minutes but the variant lasts " + v.durationMinutes()
                                + ".");
            }
        }
        for (CatalogBodies.ResourceRequirement r : v.resourceRequirements() == null
                ? List.<CatalogBodies.ResourceRequirement>of()
                : v.resourceRequirements()) {
            if ((r.resourceId() == null) == (r.resourceKind() == null)) {
                throw new ApiException(
                        ProblemCode.VALIDATION_FAILED,
                        "A resource need names either a resource or a kind of resource.");
            }
            if (r.stageSeq() != null && r.stageSeq() > stages.size()) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "A resource need refers to a missing stage.");
            }
        }
    }

    private void writeStages(UUID orgId, UUID variantId, CatalogBodies.ServiceVariantInput v) {
        dsl.deleteFrom(SERVICE_VARIANT_STAGES)
                .where(SERVICE_VARIANT_STAGES.VARIANT_ID.eq(variantId))
                .execute();
        dsl.deleteFrom(SERVICE_RESOURCE_REQUIREMENTS)
                .where(SERVICE_RESOURCE_REQUIREMENTS.VARIANT_ID.eq(variantId))
                .execute();
        List<CatalogBodies.VariantStage> stages = v.stages() == null ? List.of() : v.stages();
        for (int i = 0; i < stages.size(); i++) {
            CatalogBodies.VariantStage stage = stages.get(i);
            dsl.insertInto(SERVICE_VARIANT_STAGES)
                    .set(SERVICE_VARIANT_STAGES.ORG_ID, orgId)
                    .set(SERVICE_VARIANT_STAGES.VARIANT_ID, variantId)
                    .set(SERVICE_VARIANT_STAGES.SEQ, (short) (i + 1))
                    .set(SERVICE_VARIANT_STAGES.KIND, stage.kind())
                    .set(
                            SERVICE_VARIANT_STAGES.DURATION_MINUTES,
                            stage.durationMinutes().shortValue())
                    .set(SERVICE_VARIANT_STAGES.STAFF_BUSY, stage.staffBusy() == null || stage.staffBusy())
                    .execute();
        }
        if (v.resourceRequirements() != null) {
            for (CatalogBodies.ResourceRequirement r : v.resourceRequirements()) {
                if (r.resourceId() != null && !dsl.fetchExists(RESOURCES, RESOURCES.ID.eq(r.resourceId()))) {
                    throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown resource.");
                }
                dsl.insertInto(SERVICE_RESOURCE_REQUIREMENTS)
                        .set(SERVICE_RESOURCE_REQUIREMENTS.ORG_ID, orgId)
                        .set(SERVICE_RESOURCE_REQUIREMENTS.VARIANT_ID, variantId)
                        .set(SERVICE_RESOURCE_REQUIREMENTS.RESOURCE_ID, r.resourceId())
                        .set(SERVICE_RESOURCE_REQUIREMENTS.RESOURCE_KIND, r.resourceKind())
                        .set(SERVICE_RESOURCE_REQUIREMENTS.UNITS, r.units().shortValue())
                        .set(
                                SERVICE_RESOURCE_REQUIREMENTS.STAGE_SEQ,
                                r.stageSeq() == null ? null : r.stageSeq().shortValue())
                        .execute();
            }
        }
    }

    // --- views ---------------------------------------------------------------------------------------------

    private List<CatalogBodies.Service> views(List<ServicesRecord> services) {
        if (services.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = services.stream().map(ServicesRecord::getId).toList();
        Map<UUID, List<ServiceVariantsRecord>> variants = dsl
                .selectFrom(SERVICE_VARIANTS)
                .where(SERVICE_VARIANTS.SERVICE_ID.in(ids), SERVICE_VARIANTS.STATUS.eq("ACTIVE"))
                .orderBy(SERVICE_VARIANTS.SORT_ORDER)
                .stream()
                .collect(Collectors.groupingBy(
                        ServiceVariantsRecord::getServiceId, java.util.LinkedHashMap::new, Collectors.toList()));
        List<UUID> variantIds = variants.values().stream()
                .flatMap(List::stream)
                .map(ServiceVariantsRecord::getId)
                .toList();
        Map<UUID, List<CatalogBodies.VariantStage>> stages = dsl.selectFrom(SERVICE_VARIANT_STAGES)
                .where(SERVICE_VARIANT_STAGES.VARIANT_ID.in(variantIds))
                .orderBy(SERVICE_VARIANT_STAGES.SEQ)
                .fetchGroups(
                        SERVICE_VARIANT_STAGES.VARIANT_ID,
                        s -> new CatalogBodies.VariantStage(
                                s.getKind(), (int) s.getDurationMinutes(), s.getStaffBusy()));
        Map<UUID, List<CatalogBodies.ResourceRequirement>> requirements = dsl.selectFrom(SERVICE_RESOURCE_REQUIREMENTS)
                .where(SERVICE_RESOURCE_REQUIREMENTS.VARIANT_ID.in(variantIds))
                .fetchGroups(
                        SERVICE_RESOURCE_REQUIREMENTS.VARIANT_ID,
                        r -> new CatalogBodies.ResourceRequirement(
                                r.getResourceId(),
                                r.getResourceKind(),
                                (int) r.getUnits(),
                                r.getStageSeq() == null ? null : (int) r.getStageSeq()));
        Map<UUID, List<UUID>> locations = dsl.select(LOCATION_SERVICES.SERVICE_ID, LOCATION_SERVICES.LOCATION_ID)
                .from(LOCATION_SERVICES)
                .where(LOCATION_SERVICES.SERVICE_ID.in(ids))
                .fetchGroups(LOCATION_SERVICES.SERVICE_ID, LOCATION_SERVICES.LOCATION_ID);
        Map<UUID, List<UUID>> addons = dsl.select(SERVICE_ADDONS.SERVICE_ID, SERVICE_ADDONS.ADDON_ID)
                .from(SERVICE_ADDONS)
                .where(SERVICE_ADDONS.SERVICE_ID.in(ids))
                .fetchGroups(SERVICE_ADDONS.SERVICE_ID, SERVICE_ADDONS.ADDON_ID);
        Map<UUID, List<UUID>> staff = dsl.select(STAFF_SERVICES.SERVICE_ID, STAFF_SERVICES.STAFF_ID)
                .from(STAFF_SERVICES)
                .where(STAFF_SERVICES.SERVICE_ID.in(ids))
                .fetchGroups(STAFF_SERVICES.SERVICE_ID, STAFF_SERVICES.STAFF_ID);
        List<CatalogBodies.Service> out = new ArrayList<>();
        for (ServicesRecord s : services) {
            List<ServiceVariantsRecord> rows = variants.getOrDefault(s.getId(), List.of());
            List<CatalogBodies.ServiceVariant> variantViews = rows.stream()
                    .map(v -> new CatalogBodies.ServiceVariant(
                            v.getId(),
                            localized.read(v.getNameI18n()),
                            v.getDurationMinutes(),
                            v.getBufferBeforeMin(),
                            v.getBufferAfterMin(),
                            v.getPriceType(),
                            v.getPriceMinor(),
                            v.getCurrencyCode(),
                            v.getIsDefault(),
                            stages.getOrDefault(v.getId(), List.of()),
                            requirements.getOrDefault(v.getId(), List.of())))
                    .collect(Collectors.toList());
            out.add(new CatalogBodies.Service(
                    s.getId(),
                    s.getGroupId(),
                    s.getCategoryId(),
                    localized.read(s.getNameI18n()),
                    localized.read(s.getDescriptionI18n()),
                    localized.read(s.getAftercareI18n()),
                    Arrays.asList(s.getDeliveryModes()),
                    s.getAudience(),
                    s.getRequiresPatchTest(),
                    s.getPatchTestValidityDays() == null ? null : (int) s.getPatchTestValidityDays(),
                    s.getOnlineBooking(),
                    s.getCommissionEligible(),
                    s.getStatus(),
                    s.getSortOrder(),
                    rows.isEmpty() ? null : rows.getFirst().getCurrencyCode(),
                    variantViews,
                    locations.getOrDefault(s.getId(), List.of()),
                    addons.getOrDefault(s.getId(), List.of()),
                    staff.getOrDefault(s.getId(), List.of()),
                    version(s.getUpdatedAt())));
        }
        return out;
    }

    static long version(OffsetDateTime updatedAt) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, updatedAt.toInstant());
    }

    private static Audit.Entry entry(OrgAccess access, String action, UUID serviceId, Map<String, Object> after) {
        return new Audit.Entry(
                Audit.Actor.USER,
                access.userId(),
                access.orgId(),
                action,
                "catalog.service",
                serviceId.toString(),
                null,
                after,
                null);
    }
}
