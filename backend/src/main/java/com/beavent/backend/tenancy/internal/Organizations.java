package com.beavent.backend.tenancy.internal;

import static com.beavent.db.platform.Tables.FILES;
import static com.beavent.db.ref.Tables.CATEGORIES;
import static com.beavent.db.ref.Tables.MARKETS;
import static com.beavent.db.tenant.Tables.MEMBERS;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;
import static com.beavent.db.tenant.Tables.ORGANIZATION_CAPABILITIES;
import static com.beavent.db.tenant.Tables.ROLES;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.ETags;
import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.RequestContext;
import com.beavent.db.ref.tables.records.MarketsRecord;
import com.beavent.db.tenant.tables.records.OrganizationsRecord;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Organizations: onboarding, profile and capabilities. Runs on the application role under row-level security. */
@Component
public class Organizations {

    static final List<String> ALL_CAPABILITIES = Arrays.asList(Bodies.CAPABILITY.split("\\|"));
    private static final Set<String> UPDATABLE = Set.of(
            "displayName",
            "descriptionI18n",
            "logoFileId",
            "coverFileId",
            "websiteUrl",
            "contactEmail",
            "contactPhone",
            "socialLinks",
            "defaultLanguage");
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Markets markets;
    private final Outbox outbox;
    private final Audit audit;
    private final JsonMapper json;

    Organizations(
            DSLContext dsl,
            TransactionTemplate transactions,
            Markets markets,
            Outbox outbox,
            Audit audit,
            JsonMapper json) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.markets = markets;
        this.outbox = outbox;
        this.audit = audit;
        this.json = json;
    }

    /** Creates the organization with the caller as owner. Its id is chosen first so it can be the context. */
    public Bodies.Organization create(UUID userId, Bodies.OrganizationCreate input) {
        UUID orgId = Ids.newId();
        return RequestContext.forOrganization(userId, orgId)
                .call(() -> transactions.execute(status -> {
                    MarketsRecord market =
                            markets.requireOpenByCode(input.marketCode() == null ? "ET" : input.marketCode());
                    boolean category = dsl.fetchExists(
                            CATEGORIES,
                            CATEGORIES
                                    .ID
                                    .eq(input.primaryCategoryId())
                                    .and(CATEGORIES.KIND.eq("BUSINESS"))
                                    .and(CATEGORIES.IS_ACTIVE));
                    if (!category) {
                        throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown business category.");
                    }
                    markets.requireLanguage(market.getId(), input.defaultLanguage());
                    Set<String> capabilities =
                            input.capabilities() == null || input.capabilities().isEmpty()
                                    ? defaultCapabilities(input.kind())
                                    : new TreeSet<>(input.capabilities());
                    markets.requireCapabilitiesAvailable(market.getId(), capabilities);

                    String slug = insertWithFreeSlug(orgId, userId, input, market);
                    UUID ownerRole = dsl.select(ROLES.ID)
                            .from(ROLES)
                            .where(ROLES.ORG_ID.isNull(), ROLES.CODE.eq("OWNER"))
                            .fetchSingle(ROLES.ID);
                    dsl.insertInto(MEMBERS)
                            .set(MEMBERS.ORG_ID, orgId)
                            .set(MEMBERS.USER_ID, userId)
                            .set(MEMBERS.ROLE_ID, ownerRole)
                            .set(MEMBERS.IS_OWNER, true)
                            .set(MEMBERS.ALL_LOCATIONS, true)
                            .execute();
                    for (String capability : capabilities) {
                        dsl.insertInto(ORGANIZATION_CAPABILITIES)
                                .set(ORGANIZATION_CAPABILITIES.ORG_ID, orgId)
                                .set(ORGANIZATION_CAPABILITIES.CAPABILITY, capability)
                                .execute();
                    }
                    outbox.publish(new DomainEvent(
                            "organization",
                            orgId,
                            "organization.created",
                            1,
                            orgId,
                            Map.of("kind", input.kind(), "marketCode", market.getCode(), "ownerUserId", userId)));
                    audit.record(new Audit.Entry(
                            Audit.Actor.USER,
                            userId,
                            orgId,
                            "organization.created",
                            "tenant.organization",
                            orgId.toString(),
                            null,
                            Map.of("slug", slug, "displayName", input.displayName(), "capabilities", capabilities),
                            null));
                    return view(orgId);
                }));
    }

    private String insertWithFreeSlug(UUID orgId, UUID userId, Bodies.OrganizationCreate input, MarketsRecord market) {
        boolean chosen = input.slug() != null;
        String base = chosen ? input.slug() : Support.slugify(input.displayName(), 50);
        if (chosen && Support.RESERVED_SLUGS.contains(base)) {
            throw new ApiException(ProblemCode.CONFLICT, "That address is reserved. Choose another one.");
        }
        if (!chosen && (base.length() < 3 || Support.RESERVED_SLUGS.contains(base))) {
            base = (base.length() < 3 ? "org" : base) + "-" + Support.randomSuffix(5);
        }
        for (int attempt = 0; attempt < 6; attempt++) {
            String candidate = attempt == 0 ? base : base + "-" + Support.randomSuffix(4);
            int inserted = dsl.insertInto(ORGANIZATIONS)
                    .set(ORGANIZATIONS.ID, orgId)
                    .set(ORGANIZATIONS.SLUG, candidate)
                    .set(ORGANIZATIONS.DISPLAY_NAME, input.displayName().strip())
                    .set(ORGANIZATIONS.KIND, input.kind())
                    .set(ORGANIZATIONS.PRIMARY_CATEGORY_ID, input.primaryCategoryId())
                    .set(ORGANIZATIONS.HOME_MARKET_ID, market.getId())
                    .set(ORGANIZATIONS.DEFAULT_LANGUAGE, input.defaultLanguage())
                    .set(ORGANIZATIONS.CREATED_BY_USER_ID, userId)
                    .onConflict(ORGANIZATIONS.SLUG)
                    .doNothing()
                    .execute();
            if (inserted == 1) {
                return candidate;
            }
            if (chosen) {
                throw new ApiException(ProblemCode.CONFLICT, "That address is taken. Choose another one.");
            }
        }
        throw new ApiException(ProblemCode.CONFLICT, "Could not find a free address. Choose one.");
    }

    private static Set<String> defaultCapabilities(String kind) {
        return switch (kind) {
            case "BUSINESS", "INDEPENDENT_PROFESSIONAL" -> Set.of("APPOINTMENTS");
            default -> Set.of("EVENTS");
        };
    }

    public Bodies.Organization get(UUID orgId) {
        return transactions.execute(status -> view(orgId));
    }

    /** JSON merge-patch of the profile, guarded by If-Match. */
    public Bodies.Organization update(UUID orgId, JsonNode patch, String ifMatch, UUID userId) {
        if (patch == null || !patch.isObject() || patch.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Send at least one field to change.");
        }
        for (String field : patch.propertyNames()) {
            if (!UPDATABLE.contains(field)) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Unknown field " + field + ".");
            }
        }
        return transactions.execute(status -> {
            OrganizationsRecord org = lockedOrg(orgId);
            ETags.requireMatch(ifMatch, Support.version(org.getUpdatedAt()));
            Map<String, Object> before = new LinkedHashMap<>();
            Map<String, Object> after = new LinkedHashMap<>();
            if (patch.has("displayName")) {
                change(
                        before,
                        after,
                        "displayName",
                        org.getDisplayName(),
                        Support.requiredText(patch, "displayName", 2, 120));
                org.setDisplayName(Support.requiredText(patch, "displayName", 2, 120));
            }
            if (patch.has("descriptionI18n")) {
                org.setDescriptionI18n(jsonb(Support.stringMap(patch, "descriptionI18n", 20, 5000)));
            }
            if (patch.has("logoFileId")) {
                org.setLogoFileId(requireImage(orgId, Support.uuid(patch, "logoFileId")));
            }
            if (patch.has("coverFileId")) {
                org.setCoverFileId(requireImage(orgId, Support.uuid(patch, "coverFileId")));
            }
            if (patch.has("websiteUrl")) {
                org.setWebsiteUrl(Support.url(patch, "websiteUrl"));
            }
            if (patch.has("contactEmail")) {
                org.setContactEmail(Support.email(patch, "contactEmail"));
            }
            if (patch.has("contactPhone")) {
                org.setContactPhone(Support.phone(patch, "contactPhone"));
            }
            if (patch.has("socialLinks")) {
                org.setSocialLinks(jsonb(Support.stringMap(patch, "socialLinks", 10, 500)));
            }
            if (patch.has("defaultLanguage")) {
                String language = Support.requiredText(patch, "defaultLanguage", 2, 10);
                markets.requireLanguage(org.getHomeMarketId(), language);
                change(before, after, "defaultLanguage", org.getDefaultLanguage(), language);
                org.setDefaultLanguage(language);
            }
            org.store();
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    userId,
                    orgId,
                    "organization.updated",
                    "tenant.organization",
                    orgId.toString(),
                    before,
                    after,
                    null));
            return view(orgId);
        });
    }

    /** Replaces the enabled capability set. */
    public Bodies.Organization setCapabilities(UUID orgId, List<String> wanted, UUID userId) {
        Set<String> capabilities = new TreeSet<>(wanted);
        return transactions.execute(status -> {
            OrganizationsRecord org = lockedOrg(orgId);
            markets.requireCapabilitiesAvailable(org.getHomeMarketId(), capabilities);
            List<String> before = enabledCapabilities(orgId);
            for (String capability : ALL_CAPABILITIES) {
                if (capabilities.contains(capability)) {
                    dsl.insertInto(ORGANIZATION_CAPABILITIES)
                            .set(ORGANIZATION_CAPABILITIES.ORG_ID, orgId)
                            .set(ORGANIZATION_CAPABILITIES.CAPABILITY, capability)
                            .onConflict(ORGANIZATION_CAPABILITIES.ORG_ID, ORGANIZATION_CAPABILITIES.CAPABILITY)
                            .doUpdate()
                            .set(ORGANIZATION_CAPABILITIES.STATUS, "ENABLED")
                            .set(ORGANIZATION_CAPABILITIES.ENABLED_AT, DSL.currentOffsetDateTime())
                            .set(ORGANIZATION_CAPABILITIES.UPDATED_AT, DSL.currentOffsetDateTime())
                            .where(ORGANIZATION_CAPABILITIES.STATUS.ne("ENABLED"))
                            .execute();
                } else {
                    dsl.update(ORGANIZATION_CAPABILITIES)
                            .set(ORGANIZATION_CAPABILITIES.STATUS, "DISABLED")
                            .set(ORGANIZATION_CAPABILITIES.UPDATED_AT, DSL.currentOffsetDateTime())
                            .where(
                                    ORGANIZATION_CAPABILITIES.ORG_ID.eq(orgId),
                                    ORGANIZATION_CAPABILITIES.CAPABILITY.eq(capability),
                                    ORGANIZATION_CAPABILITIES.STATUS.ne("DISABLED"))
                            .execute();
                }
            }
            // Capabilities are part of the organization's representation: move its version on.
            dsl.update(ORGANIZATIONS)
                    .set(ORGANIZATIONS.UPDATED_AT, DSL.currentOffsetDateTime())
                    .where(ORGANIZATIONS.ID.eq(orgId))
                    .execute();
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    userId,
                    orgId,
                    "organization.capabilities_changed",
                    "tenant.organization",
                    orgId.toString(),
                    Map.of("capabilities", before),
                    Map.of("capabilities", capabilities),
                    null));
            return view(orgId);
        });
    }

    private OrganizationsRecord lockedOrg(UUID orgId) {
        OrganizationsRecord org = dsl.selectFrom(ORGANIZATIONS)
                .where(ORGANIZATIONS.ID.eq(orgId))
                .forUpdate()
                .fetchOne();
        if (org == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        return org;
    }

    private UUID requireImage(UUID orgId, UUID fileId) {
        if (fileId == null) {
            return null;
        }
        boolean usable = dsl.fetchExists(
                FILES,
                FILES.ID
                        .eq(fileId)
                        .and(FILES.ORG_ID.eq(orgId))
                        .and(FILES.DELETED_AT.isNull())
                        .and(FILES.VISIBILITY.eq("PUBLIC"))
                        .and(FILES.PURPOSE.in("LOGO", "COVER", "GALLERY")));
        if (!usable) {
            throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown image.");
        }
        return fileId;
    }

    private List<String> enabledCapabilities(UUID orgId) {
        return dsl.select(ORGANIZATION_CAPABILITIES.CAPABILITY)
                .from(ORGANIZATION_CAPABILITIES)
                .where(ORGANIZATION_CAPABILITIES.ORG_ID.eq(orgId), ORGANIZATION_CAPABILITIES.STATUS.eq("ENABLED"))
                .orderBy(ORGANIZATION_CAPABILITIES.CAPABILITY)
                .fetch(ORGANIZATION_CAPABILITIES.CAPABILITY);
    }

    Bodies.Organization view(UUID orgId) {
        var row = dsl.select(ORGANIZATIONS.asterisk(), MARKETS.CODE)
                .from(ORGANIZATIONS)
                .join(MARKETS)
                .on(MARKETS.ID.eq(ORGANIZATIONS.HOME_MARKET_ID))
                .where(ORGANIZATIONS.ID.eq(orgId))
                .fetchOne();
        if (row == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        OrganizationsRecord org = row.into(ORGANIZATIONS);
        return new Bodies.Organization(
                org.getId(),
                org.getSlug(),
                org.getDisplayName(),
                org.getKind(),
                org.getPrimaryCategoryId(),
                org.getStatus(),
                org.getVerificationStatus(),
                org.getMarketplaceStatus(),
                row.get(MARKETS.CODE),
                json.readValue(org.getDescriptionI18n().data(), STRING_MAP),
                org.getLogoFileId(),
                org.getCoverFileId(),
                org.getWebsiteUrl(),
                org.getContactEmail(),
                org.getContactPhone(),
                json.readValue(org.getSocialLinks().data(), STRING_MAP),
                org.getDefaultLanguage(),
                enabledCapabilities(orgId),
                Support.instant(org.getCreatedAt()),
                Support.version(org.getUpdatedAt()));
    }

    private JSONB jsonb(Object value) {
        return JSONB.valueOf(json.writeValueAsString(value));
    }

    private static void change(
            Map<String, Object> before, Map<String, Object> after, String field, Object old, Object now) {
        before.put(field, old);
        after.put(field, now);
    }
}
