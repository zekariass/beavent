package com.beavent.backend.team.internal;

import static com.beavent.db.catalog.Tables.SERVICES;
import static com.beavent.db.catalog.Tables.SERVICE_PRICES;
import static com.beavent.db.catalog.Tables.STAFF_SERVICES;
import static com.beavent.db.team.Tables.PRICING_LEVELS;
import static com.beavent.db.team.Tables.STAFF;
import static com.beavent.db.team.Tables.STAFF_LOCATIONS;
import static com.beavent.db.tenant.Tables.LOCATIONS;
import static com.beavent.db.tenant.Tables.MEMBERS;

import com.beavent.backend.files.FileAccess;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.ETags;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import com.beavent.db.team.tables.records.PricingLevelsRecord;
import com.beavent.db.team.tables.records.StaffRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Team profiles and pricing levels. */
@Component
public class TeamProfiles {

    static final String MANAGE = "team.manage";
    private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}$");
    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() {};

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final FileAccess files;
    private final Audit audit;
    private final JsonMapper json;

    TeamProfiles(DSLContext dsl, TransactionTemplate transactions, FileAccess files, Audit audit, JsonMapper json) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.files = files;
        this.audit = audit;
        this.json = json;
    }

    // --- pricing levels ------------------------------------------------------------------------------------

    public List<TeamBodies.PricingLevel> levels() {
        return transactions.execute(status -> dsl.selectFrom(PRICING_LEVELS)
                .orderBy(PRICING_LEVELS.RANK, PRICING_LEVELS.NAME)
                .fetch(TeamProfiles::level));
    }

    public TeamBodies.PricingLevel saveLevel(OrgAccess access, UUID levelId, TeamBodies.PricingLevelInput input) {
        access.require(MANAGE);
        return transactions.execute(status -> {
            String name = input.name().strip();
            if (dsl.fetchExists(
                    PRICING_LEVELS,
                    DSL.lower(PRICING_LEVELS.NAME)
                            .eq(name.toLowerCase(java.util.Locale.ROOT))
                            .and(levelId == null ? DSL.noCondition() : PRICING_LEVELS.ID.ne(levelId)))) {
                throw new ApiException(ProblemCode.CONFLICT, "A pricing level with this name already exists.");
            }
            PricingLevelsRecord level = levelId == null ? dsl.newRecord(PRICING_LEVELS) : foundLevel(levelId);
            level.setOrgId(access.orgId());
            level.setName(name);
            level.setRank((short) (input.rank() == null ? 0 : input.rank()));
            level.store();
            return level(level);
        });
    }

    public void deleteLevel(OrgAccess access, UUID levelId) {
        access.require(MANAGE);
        transactions.executeWithoutResult(status -> {
            PricingLevelsRecord level = foundLevel(levelId);
            if (dsl.fetchExists(STAFF, STAFF.PRICING_LEVEL_ID.eq(levelId))
                    || dsl.fetchExists(SERVICE_PRICES, SERVICE_PRICES.PRICING_LEVEL_ID.eq(levelId))) {
                throw new ApiException(
                        ProblemCode.CONFLICT, "Team members or prices use this level. Change them first.");
            }
            level.delete();
        });
    }

    private PricingLevelsRecord foundLevel(UUID levelId) {
        PricingLevelsRecord level = dsl.selectFrom(PRICING_LEVELS)
                .where(PRICING_LEVELS.ID.eq(levelId))
                .fetchOne();
        if (level == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        return level;
    }

    private static TeamBodies.PricingLevel level(PricingLevelsRecord level) {
        return new TeamBodies.PricingLevel(level.getId(), level.getName(), level.getRank());
    }

    // --- staff ---------------------------------------------------------------------------------------------

    public List<TeamBodies.Staff> list(UUID locationId, String status) {
        return transactions.execute(tx -> {
            Condition condition = status == null ? STAFF.STATUS.ne("ARCHIVED") : STAFF.STATUS.eq(status);
            if (locationId != null) {
                condition = condition.and(DSL.exists(DSL.selectOne()
                        .from(STAFF_LOCATIONS)
                        .where(STAFF_LOCATIONS.STAFF_ID.eq(STAFF.ID), STAFF_LOCATIONS.LOCATION_ID.eq(locationId))));
            }
            return views(dsl.selectFrom(STAFF)
                    .where(condition)
                    .orderBy(STAFF.SORT_ORDER, STAFF.DISPLAY_NAME)
                    .fetch());
        });
    }

    public TeamBodies.Staff get(UUID staffId) {
        return transactions.execute(
                status -> views(List.of(found(staffId, false))).getFirst());
    }

    public TeamBodies.Staff save(OrgAccess access, UUID staffId, TeamBodies.StaffInput input, String ifMatch) {
        access.require(MANAGE);
        return transactions.execute(status -> {
            StaffRecord staff;
            if (staffId == null) {
                staff = dsl.newRecord(STAFF);
                staff.setOrgId(access.orgId());
            } else {
                staff = found(staffId, true);
                ETags.requireMatch(ifMatch, version(staff.getUpdatedAt()));
                staff.setUpdatedAt(OffsetDateTime.now());
            }
            apply(access, staff, input);
            staff.store();
            writeAssignments(access.orgId(), staff.getId(), input);
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    access.userId(),
                    access.orgId(),
                    staffId == null ? "staff.created" : "staff.updated",
                    "team.staff",
                    staff.getId().toString(),
                    null,
                    Map.of("displayName", staff.getDisplayName()),
                    null));
            return views(List.of(found(staff.getId(), false))).getFirst();
        });
    }

    public void archive(OrgAccess access, UUID staffId) {
        access.require(MANAGE);
        transactions.executeWithoutResult(status -> {
            StaffRecord staff = found(staffId, true);
            staff.setStatus("ARCHIVED");
            staff.setIsBookable(false);
            if (staff.getEndedOn() == null) {
                staff.setEndedOn(LocalDate.now());
            }
            staff.store();
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    access.userId(),
                    access.orgId(),
                    "staff.archived",
                    "team.staff",
                    staffId.toString(),
                    null,
                    null,
                    null));
        });
    }

    StaffRecord found(UUID staffId, boolean lock) {
        var query = dsl.selectFrom(STAFF).where(STAFF.ID.eq(staffId));
        StaffRecord staff = lock ? query.forUpdate().fetchOne() : query.fetchOne();
        if (staff == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        return staff;
    }

    private void apply(OrgAccess access, StaffRecord staff, TeamBodies.StaffInput input) {
        if (input.pricingLevelId() != null
                && !dsl.fetchExists(PRICING_LEVELS, PRICING_LEVELS.ID.eq(input.pricingLevelId()))) {
            throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown pricing level.");
        }
        if (input.memberId() != null) {
            // Explicit organization filter: members_self_read also shows the caller's memberships elsewhere.
            boolean member = dsl.fetchExists(
                    MEMBERS,
                    MEMBERS.ID
                            .eq(input.memberId())
                            .and(MEMBERS.ORG_ID.eq(access.orgId()))
                            .and(MEMBERS.STATUS.ne("REMOVED")));
            if (!member) {
                throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown team member login.");
            }
            boolean linkedElsewhere = dsl.fetchExists(
                    STAFF,
                    STAFF.MEMBER_ID
                            .eq(input.memberId())
                            .and(staff.getId() == null ? DSL.noCondition() : STAFF.ID.ne(staff.getId())));
            if (linkedElsewhere) {
                throw new ApiException(ProblemCode.CONFLICT, "This login already belongs to another team profile.");
            }
        }
        if (input.photoFileId() != null) {
            boolean usable = files.publicImage(input.photoFileId())
                    .filter(i -> access.orgId().equals(i.orgId()))
                    .filter(i -> List.of("GALLERY", "COVER", "LOGO").contains(i.purpose()))
                    .isPresent();
            if (!usable) {
                throw new ApiException(
                        ProblemCode.REFERENCE_NOT_FOUND, "Upload the photo first and wait until it is ready.");
            }
        }
        Map<String, String> bio = new LinkedHashMap<>();
        if (input.bio() != null) {
            input.bio().forEach((language, text) -> {
                if (!LANGUAGE.matcher(language).matches() || text == null || text.length() > 2000) {
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "Invalid bio (" + language + ").");
                }
                if (!text.isBlank()) {
                    bio.put(language, text.strip());
                }
            });
        }
        staff.setDisplayName(input.displayName().strip());
        staff.setJobTitle(input.jobTitle());
        staff.setBioI18n(JSONB.valueOf(json.writeValueAsString(bio)));
        staff.setAvatarFileId(input.photoFileId());
        staff.setPhoneE164(input.phone());
        staff.setEmail(input.email());
        staff.setEmploymentType(input.employmentType() == null ? "EMPLOYEE" : input.employmentType());
        staff.setPricingLevelId(input.pricingLevelId());
        staff.setMemberId(input.memberId());
        staff.setIsBookable(input.isBookable() == null || input.isBookable());
        staff.setBookableOnline(input.bookableOnline() == null || input.bookableOnline());
        staff.setLanguages(
                input.languages() == null
                        ? new String[0]
                        : new LinkedHashSet<>(input.languages()).toArray(String[]::new));
        staff.setCalendarColor(input.calendarColor());
        staff.setSortOrder((short) (input.sortOrder() == null ? 0 : input.sortOrder()));
        staff.setStartedOn(input.startedOn());
    }

    private void writeAssignments(UUID orgId, UUID staffId, TeamBodies.StaffInput input) {
        Set<UUID> locations = new LinkedHashSet<>(input.locationIds());
        if (dsl.fetchCount(LOCATIONS, LOCATIONS.ID.in(locations)) != locations.size()) {
            throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown location.");
        }
        Set<UUID> services = new LinkedHashSet<>(input.serviceIds() == null ? List.of() : input.serviceIds());
        if (dsl.fetchCount(SERVICES, SERVICES.ID.in(services).and(SERVICES.STATUS.ne("ARCHIVED"))) != services.size()) {
            throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown service.");
        }
        dsl.deleteFrom(STAFF_LOCATIONS)
                .where(STAFF_LOCATIONS.STAFF_ID.eq(staffId))
                .execute();
        for (UUID locationId : locations) {
            dsl.insertInto(STAFF_LOCATIONS)
                    .set(STAFF_LOCATIONS.ORG_ID, orgId)
                    .set(STAFF_LOCATIONS.STAFF_ID, staffId)
                    .set(STAFF_LOCATIONS.LOCATION_ID, locationId)
                    .execute();
        }
        dsl.deleteFrom(STAFF_SERVICES)
                .where(STAFF_SERVICES.STAFF_ID.eq(staffId))
                .execute();
        for (UUID serviceId : services) {
            dsl.insertInto(STAFF_SERVICES)
                    .set(STAFF_SERVICES.ORG_ID, orgId)
                    .set(STAFF_SERVICES.STAFF_ID, staffId)
                    .set(STAFF_SERVICES.SERVICE_ID, serviceId)
                    .execute();
        }
    }

    private List<TeamBodies.Staff> views(List<StaffRecord> staff) {
        if (staff.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = staff.stream().map(StaffRecord::getId).toList();
        Map<UUID, List<UUID>> locations = dsl.select(STAFF_LOCATIONS.STAFF_ID, STAFF_LOCATIONS.LOCATION_ID)
                .from(STAFF_LOCATIONS)
                .where(STAFF_LOCATIONS.STAFF_ID.in(ids))
                .fetchGroups(STAFF_LOCATIONS.STAFF_ID, STAFF_LOCATIONS.LOCATION_ID);
        Map<UUID, List<UUID>> services = dsl.select(STAFF_SERVICES.STAFF_ID, STAFF_SERVICES.SERVICE_ID)
                .from(STAFF_SERVICES)
                .where(STAFF_SERVICES.STAFF_ID.in(ids))
                .fetchGroups(STAFF_SERVICES.STAFF_ID, STAFF_SERVICES.SERVICE_ID);
        return staff.stream()
                .map(s -> new TeamBodies.Staff(
                        s.getId(),
                        s.getDisplayName(),
                        s.getJobTitle(),
                        json.readValue(s.getBioI18n().data(), MAP),
                        s.getAvatarFileId(),
                        s.getPhoneE164(),
                        s.getEmail(),
                        s.getEmploymentType(),
                        s.getPricingLevelId(),
                        s.getMemberId(),
                        s.getIsBookable(),
                        s.getBookableOnline(),
                        Arrays.asList(s.getLanguages()),
                        s.getCalendarColor(),
                        s.getSortOrder(),
                        s.getStartedOn(),
                        s.getEndedOn(),
                        s.getStatus(),
                        locations.getOrDefault(s.getId(), List.of()),
                        services.getOrDefault(s.getId(), List.of()),
                        version(s.getUpdatedAt())))
                .toList();
    }

    static long version(OffsetDateTime updatedAt) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, updatedAt.toInstant());
    }
}
