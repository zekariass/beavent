package com.beavent.backend.team.internal;

import static com.beavent.db.team.Tables.SHIFTS;
import static com.beavent.db.team.Tables.STAFF;
import static com.beavent.db.team.Tables.STAFF_LOCATIONS;
import static com.beavent.db.team.Tables.TIME_OFF;
import static com.beavent.db.team.Tables.WORKING_PATTERNS;
import static com.beavent.db.tenant.Tables.LOCATIONS;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.team.Rota;
import com.beavent.backend.tenancy.OrgAccess;
import com.beavent.db.team.tables.records.StaffRecord;
import com.beavent.db.team.tables.records.WorkingPatternsRecord;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Regular working hours, one-off shifts, time off, and the rota computed from them. */
@Component
public class Hours implements Rota {

    static final int MAX_RANGE_DAYS = 62;
    private static final Field<OffsetDateTime> SHIFT_START =
            DSL.field("lower({0})", OffsetDateTime.class, SHIFTS.DURING);
    private static final Field<OffsetDateTime> SHIFT_END = DSL.field("upper({0})", OffsetDateTime.class, SHIFTS.DURING);
    private static final Field<OffsetDateTime> OFF_START =
            DSL.field("lower({0})", OffsetDateTime.class, TIME_OFF.DURING);
    private static final Field<OffsetDateTime> OFF_END = DSL.field("upper({0})", OffsetDateTime.class, TIME_OFF.DURING);

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final TeamProfiles profiles;
    private final Audit audit;

    Hours(DSLContext dsl, TransactionTemplate transactions, TeamProfiles profiles, Audit audit) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.profiles = profiles;
        this.audit = audit;
    }

    // --- who may see and change whose hours ------------------------------------------------------------------

    /** Managers and people who see every calendar may read anyone's hours; others only their own. */
    void requireReadable(OrgAccess access, UUID staffId) {
        if (access.can(TeamProfiles.MANAGE) || access.can("calendar.view_all")) {
            return;
        }
        if (staffId == null || !isOwn(access, staffId)) {
            throw new ApiException(ProblemCode.FORBIDDEN, "You can see only your own schedule.");
        }
    }

    /** Inside a transaction (joins the caller's): row-level security needs the request context it sets. */
    private boolean isOwn(OrgAccess access, UUID staffId) {
        return Boolean.TRUE.equals(transactions.execute(
                status -> dsl.fetchExists(STAFF, STAFF.ID.eq(staffId).and(STAFF.MEMBER_ID.eq(access.memberId())))));
    }

    // --- working patterns ----------------------------------------------------------------------------------

    public TeamBodies.WorkingPattern pattern(OrgAccess access, UUID staffId, LocalDate date) {
        requireReadable(access, staffId);
        LocalDate on = date == null ? LocalDate.now() : date;
        return transactions.execute(status -> {
            profiles.found(staffId, false);
            List<WorkingPatternsRecord> rows = dsl.selectFrom(WORKING_PATTERNS)
                    .where(WORKING_PATTERNS.STAFF_ID.eq(staffId), WORKING_PATTERNS.EFFECTIVE_FROM.le(on))
                    .and(WORKING_PATTERNS.EFFECTIVE_TO.isNull().or(WORKING_PATTERNS.EFFECTIVE_TO.ge(on)))
                    .orderBy(WORKING_PATTERNS.WEEK_INDEX, WORKING_PATTERNS.ISO_WEEKDAY, WORKING_PATTERNS.STARTS_AT)
                    .fetch();
            if (rows.isEmpty()) {
                return new TeamBodies.WorkingPattern(null, 1, List.of());
            }
            return new TeamBodies.WorkingPattern(
                    rows.getFirst().getEffectiveFrom(),
                    rows.getFirst().getRepeatWeeks(),
                    rows.stream()
                            .map(r -> new TeamBodies.PatternEntry(
                                    (int) r.getWeekIndex(),
                                    r.getLocationId(),
                                    (int) r.getIsoWeekday(),
                                    hhmm(r.getStartsAt()),
                                    hhmm(r.getEndsAt())))
                            .toList());
        });
    }

    /** From {@code effectiveFrom} on, the new pattern applies; earlier days keep the previous one (history). */
    public TeamBodies.WorkingPattern setPattern(OrgAccess access, UUID staffId, TeamBodies.WorkingPatternInput input) {
        access.require(TeamProfiles.MANAGE);
        int repeatWeeks = input.repeatWeeks() == null ? 1 : input.repeatWeeks();
        LocalDate from = input.effectiveFrom();
        validateEntries(input.entries(), repeatWeeks);
        transactions.executeWithoutResult(status -> {
            profiles.found(staffId, true);
            Set<UUID> staffLocations = Set.copyOf(dsl.select(STAFF_LOCATIONS.LOCATION_ID)
                    .from(STAFF_LOCATIONS)
                    .where(STAFF_LOCATIONS.STAFF_ID.eq(staffId))
                    .fetch(STAFF_LOCATIONS.LOCATION_ID));
            for (TeamBodies.PatternEntry entry : input.entries()) {
                if (!staffLocations.contains(entry.locationId())) {
                    throw new ApiException(
                            ProblemCode.CONSTRAINT_VIOLATION, "Add the location to the team member's locations first.");
                }
            }
            dsl.deleteFrom(WORKING_PATTERNS)
                    .where(WORKING_PATTERNS.STAFF_ID.eq(staffId), WORKING_PATTERNS.EFFECTIVE_FROM.ge(from))
                    .execute();
            dsl.update(WORKING_PATTERNS)
                    .set(WORKING_PATTERNS.EFFECTIVE_TO, from.minusDays(1))
                    .where(WORKING_PATTERNS.STAFF_ID.eq(staffId))
                    .and(WORKING_PATTERNS.EFFECTIVE_TO.isNull().or(WORKING_PATTERNS.EFFECTIVE_TO.ge(from)))
                    .execute();
            for (TeamBodies.PatternEntry entry : input.entries()) {
                dsl.insertInto(WORKING_PATTERNS)
                        .set(WORKING_PATTERNS.ORG_ID, access.orgId())
                        .set(WORKING_PATTERNS.STAFF_ID, staffId)
                        .set(WORKING_PATTERNS.LOCATION_ID, entry.locationId())
                        .set(WORKING_PATTERNS.ISO_WEEKDAY, entry.isoWeekday().shortValue())
                        .set(WORKING_PATTERNS.STARTS_AT, LocalTime.parse(entry.startsAt()))
                        .set(WORKING_PATTERNS.ENDS_AT, LocalTime.parse(entry.endsAt()))
                        .set(WORKING_PATTERNS.REPEAT_WEEKS, (short) repeatWeeks)
                        .set(WORKING_PATTERNS.WEEK_INDEX, (short) (entry.week() == null ? 1 : entry.week()))
                        .set(WORKING_PATTERNS.EFFECTIVE_FROM, from)
                        .execute();
            }
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    access.userId(),
                    access.orgId(),
                    "staff.working_pattern_set",
                    "team.staff",
                    staffId.toString(),
                    null,
                    Map.of(
                            "effectiveFrom",
                            from.toString(),
                            "entries",
                            input.entries().size()),
                    null));
        });
        return pattern(access, staffId, from);
    }

    private static void validateEntries(List<TeamBodies.PatternEntry> entries, int repeatWeeks) {
        List<TeamBodies.PatternEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing((TeamBodies.PatternEntry e) -> e.week() == null ? 1 : e.week())
                .thenComparing(TeamBodies.PatternEntry::isoWeekday)
                .thenComparing(TeamBodies.PatternEntry::startsAt));
        TeamBodies.PatternEntry previous = null;
        for (TeamBodies.PatternEntry e : sorted) {
            int week = e.week() == null ? 1 : e.week();
            if (week > repeatWeeks) {
                throw new ApiException(
                        ProblemCode.VALIDATION_FAILED,
                        "Week " + week + " is beyond the " + repeatWeeks + "-week rotation.");
            }
            if (e.endsAt().compareTo(e.startsAt()) <= 0) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Working hours must end after they start.");
            }
            if (previous != null
                    && (previous.week() == null ? 1 : previous.week()) == week
                    && previous.isoWeekday().equals(e.isoWeekday())
                    && e.startsAt().compareTo(previous.endsAt()) < 0) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Working hours on the same day overlap.");
            }
            previous = e;
        }
    }

    // --- shifts ----------------------------------------------------------------------------------------------

    public List<TeamBodies.Shift> shifts(
            OrgAccess access, LocalDate from, LocalDate to, UUID staffId, UUID locationId) {
        requireReadable(access, staffId);
        Instant[] range = range(from, to);
        return transactions.execute(status -> dsl.select(
                        SHIFTS.ID,
                        SHIFTS.STAFF_ID,
                        SHIFTS.LOCATION_ID,
                        SHIFT_START,
                        SHIFT_END,
                        SHIFTS.STATUS,
                        SHIFTS.NOTE)
                .from(SHIFTS)
                .where(overlaps(SHIFTS.DURING, range[0], range[1]), SHIFTS.STATUS.ne("CANCELLED"))
                .and(staffId == null ? DSL.noCondition() : SHIFTS.STAFF_ID.eq(staffId))
                .and(locationId == null ? DSL.noCondition() : SHIFTS.LOCATION_ID.eq(locationId))
                .orderBy(SHIFT_START)
                .fetch(r -> new TeamBodies.Shift(
                        r.get(SHIFTS.ID),
                        r.get(SHIFTS.STAFF_ID),
                        r.get(SHIFTS.LOCATION_ID),
                        r.get(SHIFT_START).toInstant(),
                        r.get(SHIFT_END).toInstant(),
                        r.get(SHIFTS.STATUS),
                        r.get(SHIFTS.NOTE))));
    }

    public TeamBodies.Shift addShift(OrgAccess access, TeamBodies.ShiftInput input) {
        access.require(TeamProfiles.MANAGE);
        validateSpan(input.startsAt(), input.endsAt(), Duration.ofHours(24));
        return transactions.execute(status -> {
            profiles.found(input.staffId(), true);
            boolean worksThere = dsl.fetchExists(
                    STAFF_LOCATIONS,
                    STAFF_LOCATIONS
                            .STAFF_ID
                            .eq(input.staffId())
                            .and(STAFF_LOCATIONS.LOCATION_ID.eq(input.locationId())));
            if (!worksThere) {
                throw new ApiException(
                        ProblemCode.CONSTRAINT_VIOLATION, "Add the location to the team member's locations first.");
            }
            boolean overlap = dsl.fetchExists(
                    SHIFTS,
                    SHIFTS.STAFF_ID
                            .eq(input.staffId())
                            .and(SHIFTS.STATUS.eq("PUBLISHED"))
                            .and(overlaps(SHIFTS.DURING, input.startsAt(), input.endsAt())));
            if (overlap) {
                throw new ApiException(ProblemCode.CONFLICT, "This person already has a shift at that time.");
            }
            UUID id = dsl.insertInto(SHIFTS)
                    .set(SHIFTS.ORG_ID, access.orgId())
                    .set(SHIFTS.STAFF_ID, input.staffId())
                    .set(SHIFTS.LOCATION_ID, input.locationId())
                    .set(SHIFTS.DURING, (Object) range(input.startsAt(), input.endsAt()))
                    .set(SHIFTS.NOTE, input.note())
                    .returning(SHIFTS.ID)
                    .fetchSingle(SHIFTS.ID);
            return new TeamBodies.Shift(
                    id,
                    input.staffId(),
                    input.locationId(),
                    input.startsAt(),
                    input.endsAt(),
                    "PUBLISHED",
                    input.note());
        });
    }

    public void cancelShift(OrgAccess access, UUID shiftId) {
        access.require(TeamProfiles.MANAGE);
        transactions.executeWithoutResult(status -> {
            int changed = dsl.update(SHIFTS)
                    .set(SHIFTS.STATUS, "CANCELLED")
                    .where(SHIFTS.ID.eq(shiftId), SHIFTS.STATUS.ne("CANCELLED"))
                    .execute();
            if (changed == 0) {
                throw new ApiException(ProblemCode.NOT_FOUND);
            }
        });
    }

    // --- time off --------------------------------------------------------------------------------------------

    public List<TeamBodies.TimeOff> timeOff(
            OrgAccess access, LocalDate from, LocalDate to, UUID staffId, String status) {
        requireReadable(access, staffId);
        Instant[] range = range(from, to);
        return transactions.execute(tx -> dsl.select(
                        TIME_OFF.ID,
                        TIME_OFF.STAFF_ID,
                        OFF_START,
                        OFF_END,
                        TIME_OFF.KIND,
                        TIME_OFF.STATUS,
                        TIME_OFF.REASON)
                .from(TIME_OFF)
                .where(overlaps(TIME_OFF.DURING, range[0], range[1]))
                .and(staffId == null ? DSL.noCondition() : TIME_OFF.STAFF_ID.eq(staffId))
                .and(status == null ? TIME_OFF.STATUS.ne("CANCELLED") : TIME_OFF.STATUS.eq(status))
                .orderBy(OFF_START)
                .fetch(Hours::timeOff));
    }

    /** Managers record approved time off; anyone else may request it for themselves. */
    public TeamBodies.TimeOff addTimeOff(OrgAccess access, TeamBodies.TimeOffInput input) {
        validateSpan(input.startsAt(), input.endsAt(), Duration.ofDays(366));
        boolean manager = access.can(TeamProfiles.MANAGE);
        if (!manager && !(access.can("team.own_schedule") && isOwn(access, input.staffId()))) {
            throw new ApiException(ProblemCode.FORBIDDEN, "You can request time off only for yourself.");
        }
        return transactions.execute(status -> {
            profiles.found(input.staffId(), true);
            UUID id = dsl.insertInto(TIME_OFF)
                    .set(TIME_OFF.ORG_ID, access.orgId())
                    .set(TIME_OFF.STAFF_ID, input.staffId())
                    .set(TIME_OFF.DURING, (Object) range(input.startsAt(), input.endsAt()))
                    .set(TIME_OFF.KIND, input.kind())
                    .set(TIME_OFF.STATUS, manager ? "APPROVED" : "REQUESTED")
                    .set(TIME_OFF.REASON, input.reason())
                    .set(TIME_OFF.DECIDED_BY, manager ? access.userId() : null)
                    .set(TIME_OFF.DECIDED_AT, manager ? OffsetDateTime.now() : null)
                    .returning(TIME_OFF.ID)
                    .fetchSingle(TIME_OFF.ID);
            return timeOffById(id);
        });
    }

    public TeamBodies.TimeOff decide(OrgAccess access, UUID timeOffId, String decision) {
        access.require(TeamProfiles.MANAGE);
        return transactions.execute(status -> {
            int changed = dsl.update(TIME_OFF)
                    .set(TIME_OFF.STATUS, "APPROVE".equals(decision) ? "APPROVED" : "REJECTED")
                    .set(TIME_OFF.DECIDED_BY, access.userId())
                    .set(TIME_OFF.DECIDED_AT, OffsetDateTime.now())
                    .set(TIME_OFF.UPDATED_AT, OffsetDateTime.now())
                    .where(TIME_OFF.ID.eq(timeOffId), TIME_OFF.STATUS.eq("REQUESTED"))
                    .execute();
            if (changed == 0) {
                if (!dsl.fetchExists(TIME_OFF, TIME_OFF.ID.eq(timeOffId))) {
                    throw new ApiException(ProblemCode.NOT_FOUND);
                }
                throw new ApiException(ProblemCode.CONFLICT, "This request has already been decided.");
            }
            return timeOffById(timeOffId);
        });
    }

    public void cancelTimeOff(OrgAccess access, UUID timeOffId) {
        transactions.executeWithoutResult(status -> {
            var row = dsl.selectFrom(TIME_OFF)
                    .where(TIME_OFF.ID.eq(timeOffId))
                    .forUpdate()
                    .fetchOne();
            if (row == null) {
                throw new ApiException(ProblemCode.NOT_FOUND);
            }
            if (!access.can(TeamProfiles.MANAGE) && !isOwn(access, row.getStaffId())) {
                throw new ApiException(ProblemCode.FORBIDDEN);
            }
            if (List.of("CANCELLED", "REJECTED").contains(row.getStatus())) {
                throw new ApiException(ProblemCode.CONFLICT, "This time off is not active.");
            }
            row.setStatus("CANCELLED");
            row.setUpdatedAt(OffsetDateTime.now());
            row.store();
        });
    }

    private TeamBodies.TimeOff timeOffById(UUID id) {
        return dsl.select(
                        TIME_OFF.ID,
                        TIME_OFF.STAFF_ID,
                        OFF_START,
                        OFF_END,
                        TIME_OFF.KIND,
                        TIME_OFF.STATUS,
                        TIME_OFF.REASON)
                .from(TIME_OFF)
                .where(TIME_OFF.ID.eq(id))
                .fetchSingle(Hours::timeOff);
    }

    private static TeamBodies.TimeOff timeOff(org.jooq.Record r) {
        return new TeamBodies.TimeOff(
                r.get(TIME_OFF.ID),
                r.get(TIME_OFF.STAFF_ID),
                r.get(OFF_START).toInstant(),
                r.get(OFF_END).toInstant(),
                r.get(TIME_OFF.KIND),
                r.get(TIME_OFF.STATUS),
                r.get(TIME_OFF.REASON));
    }

    // --- the rota ------------------------------------------------------------------------------------------

    public TeamBodies.Schedule schedule(OrgAccess access, LocalDate from, LocalDate to, UUID staffId, UUID locationId) {
        requireReadable(access, staffId);
        return transactions.execute(status -> new TeamBodies.Schedule(
                schedule(staffId == null ? List.of() : List.of(staffId), locationId, from, to).stream()
                        .map(d -> new TeamBodies.WorkingDay(
                                d.staffId(),
                                d.date(),
                                d.source(),
                                d.intervals().stream()
                                        .map(i -> new TeamBodies.Interval(i.locationId(), i.startsAt(), i.endsAt()))
                                        .toList()))
                        .toList()));
    }

    @Override
    public List<Day> schedule(Collection<UUID> staffIds, UUID locationId, LocalDate from, LocalDate to) {
        range(from, to);
        List<StaffRecord> staff = dsl.selectFrom(STAFF)
                .where(staffIds.isEmpty() ? STAFF.STATUS.eq("ACTIVE") : STAFF.ID.in(staffIds))
                .orderBy(STAFF.SORT_ORDER, STAFF.DISPLAY_NAME)
                .fetch();
        if (!staffIds.isEmpty() && staff.size() != Set.copyOf(staffIds).size()) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        List<UUID> ids = staff.stream().map(StaffRecord::getId).toList();
        Map<UUID, ZoneId> zones = dsl.select(LOCATIONS.ID, LOCATIONS.TIMEZONE)
                .from(LOCATIONS)
                .fetchMap(LOCATIONS.ID, r -> ZoneId.of(r.value2()));
        Map<UUID, List<WorkingPatternsRecord>> patterns = dsl
                .selectFrom(WORKING_PATTERNS)
                .where(WORKING_PATTERNS.STAFF_ID.in(ids), WORKING_PATTERNS.EFFECTIVE_FROM.le(to))
                .and(WORKING_PATTERNS.EFFECTIVE_TO.isNull().or(WORKING_PATTERNS.EFFECTIVE_TO.ge(from)))
                .orderBy(WORKING_PATTERNS.STARTS_AT)
                .stream()
                .collect(Collectors.groupingBy(WorkingPatternsRecord::getStaffId));
        // Wide UTC window: local days in any time zone of the range.
        Instant windowStart = from.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant windowEnd = to.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<UUID, List<Rota.Interval>> shifts = dsl
                .select(SHIFTS.STAFF_ID, SHIFTS.LOCATION_ID, SHIFT_START, SHIFT_END)
                .from(SHIFTS)
                .where(
                        SHIFTS.STAFF_ID.in(ids),
                        SHIFTS.STATUS.eq("PUBLISHED"),
                        overlaps(SHIFTS.DURING, windowStart, windowEnd))
                .fetch()
                .stream()
                .collect(Collectors.groupingBy(
                        r -> r.get(SHIFTS.STAFF_ID),
                        Collectors.mapping(
                                r -> new Rota.Interval(
                                        r.get(SHIFTS.LOCATION_ID),
                                        r.get(SHIFT_START).toInstant(),
                                        r.get(SHIFT_END).toInstant()),
                                Collectors.toList())));
        Map<UUID, List<Instant[]>> off = dsl
                .select(TIME_OFF.STAFF_ID, OFF_START, OFF_END)
                .from(TIME_OFF)
                .where(
                        TIME_OFF.STAFF_ID.in(ids),
                        TIME_OFF.STATUS.eq("APPROVED"),
                        overlaps(TIME_OFF.DURING, windowStart, windowEnd))
                .fetch()
                .stream()
                .collect(Collectors.groupingBy(
                        r -> r.get(TIME_OFF.STAFF_ID),
                        Collectors.mapping(
                                r -> new Instant[] {
                                    r.get(OFF_START).toInstant(), r.get(OFF_END).toInstant()
                                },
                                Collectors.toList())));

        List<Day> days = new ArrayList<>();
        for (StaffRecord person : staff) {
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                LocalDate day = date;
                List<Rota.Interval> dayShifts = shifts.getOrDefault(person.getId(), List.of()).stream()
                        .filter(s -> s.startsAt()
                                .atZone(zones.get(s.locationId()))
                                .toLocalDate()
                                .equals(day))
                        .toList();
                String source;
                List<Rota.Interval> intervals;
                if (!dayShifts.isEmpty()) {
                    source = "SHIFTS";
                    intervals = new ArrayList<>(dayShifts);
                } else {
                    source = "PATTERN";
                    intervals = new ArrayList<>();
                    for (WorkingPatternsRecord row : patterns.getOrDefault(person.getId(), List.of())) {
                        if (appliesOn(row, day)) {
                            ZoneId zone = zones.get(row.getLocationId());
                            intervals.add(new Rota.Interval(
                                    row.getLocationId(),
                                    day.atTime(row.getStartsAt()).atZone(zone).toInstant(),
                                    day.atTime(row.getEndsAt()).atZone(zone).toInstant()));
                        }
                    }
                }
                if (locationId != null) {
                    intervals.removeIf(i -> !i.locationId().equals(locationId));
                }
                intervals = subtract(intervals, off.getOrDefault(person.getId(), List.of()));
                intervals.sort(Comparator.comparing(Rota.Interval::startsAt));
                days.add(new Day(person.getId(), day, source, intervals));
            }
        }
        return days;
    }

    /** Weekday matches, the row is in effect, and it is this row's week of the rotation. */
    static boolean appliesOn(WorkingPatternsRecord row, LocalDate day) {
        if (day.getDayOfWeek().getValue() != row.getIsoWeekday()
                || day.isBefore(row.getEffectiveFrom())
                || (row.getEffectiveTo() != null && day.isAfter(row.getEffectiveTo()))) {
            return false;
        }
        LocalDate anchor = row.getEffectiveFrom().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long week = ChronoUnit.DAYS.between(anchor, day) / 7;
        return week % row.getRepeatWeeks() == row.getWeekIndex() - 1;
    }

    private static List<Rota.Interval> subtract(List<Rota.Interval> intervals, List<Instant[]> off) {
        List<Rota.Interval> result = new ArrayList<>(intervals);
        for (Instant[] o : off) {
            List<Rota.Interval> next = new ArrayList<>();
            for (Rota.Interval i : result) {
                if (!o[1].isAfter(i.startsAt()) || !o[0].isBefore(i.endsAt())) {
                    next.add(i);
                    continue;
                }
                if (o[0].isAfter(i.startsAt())) {
                    next.add(new Rota.Interval(i.locationId(), i.startsAt(), o[0]));
                }
                if (o[1].isBefore(i.endsAt())) {
                    next.add(new Rota.Interval(i.locationId(), o[1], i.endsAt()));
                }
            }
            result = next;
        }
        return result;
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private static Instant[] range(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED, "Give from/to dates up to " + MAX_RANGE_DAYS + " days apart.");
        }
        return new Instant[] {
            from.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
            to.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant()
        };
    }

    private static void validateSpan(Instant start, Instant end, Duration max) {
        if (!end.isAfter(start) || Duration.between(start, end).compareTo(max) > 0) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The end must be after the start (and not too far).");
        }
    }

    private static Field<Object> range(Instant start, Instant end) {
        return DSL.field(
                "tstzrange({0}, {1}, '[)')",
                Object.class,
                DSL.val(OffsetDateTime.ofInstant(start, ZoneOffset.UTC)),
                DSL.val(OffsetDateTime.ofInstant(end, ZoneOffset.UTC)));
    }

    private static Condition overlaps(Field<Object> during, Instant start, Instant end) {
        return DSL.condition("{0} && {1}", during, range(start, end));
    }

    private static String hhmm(LocalTime time) {
        return time.toString().substring(0, 5);
    }
}
