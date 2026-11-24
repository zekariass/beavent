package com.beavent.backend.scheduling.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which start times can be booked, and with whom (docs/08-availability-engine.md). A pure function of its inputs: no
 * database, clock or framework, so every rule can be tested exhaustively. The answer is advisory; the reservation
 * ledger's exclusion constraints stay the authority at booking time.
 *
 * <p>Everything is half-open {@code [start, end)}. Staff are assigned first-feasible in the order given; resources are
 * assigned by an exact search (a greedy choice can miss a feasible assignment) that gives up after a fixed number of
 * steps. Giving up only ever <em>omits</em> a slot, never offers an impossible one.
 */
public final class AvailabilityEngine {

    /** Options tried per resource requirement and search steps per candidate slot before the engine gives up. */
    private static final int MAX_OPTIONS_PER_NEED = 64;

    private static final int MAX_SEARCH_STEPS = 20_000;

    private AvailabilityEngine() {}

    public enum StageKind {
        SERVICE,
        PROCESSING,
        FINISHING,
        CONSULTATION,
        /** At-home only: the person travelling to or from the customer (never part of a service template). */
        TRAVEL
    }

    /** A part of a service. While {@code staffBusy} is false (e.g. colour processing) the person can serve others. */
    public record Stage(StageKind kind, int minutes, boolean staffBusy) {
        public Stage {
            if (minutes <= 0) throw new IllegalArgumentException("a stage needs at least one minute");
        }
    }

    /**
     * What a service needs from resources: one specific resource or any of a kind, {@code units} of it, for the whole
     * item or a single stage (1-based).
     */
    public record Requirement(UUID resourceId, String kind, int units, Integer stageSeq) {
        public Requirement {
            if ((resourceId == null) == (kind == null))
                throw new IllegalArgumentException("exactly one of resourceId and kind");
            if (units < 1) throw new IllegalArgumentException("units must be positive");
            if (stageSeq != null && stageSeq < 1) throw new IllegalArgumentException("stageSeq is 1-based");
        }
    }

    /** One service in the booking: its stages, buffers, who may do it and what it needs. */
    public record Item(
            List<Stage> stages,
            int bufferBeforeMinutes,
            int bufferAfterMinutes,
            List<UUID> candidateStaff,
            List<Requirement> requirements) {
        public Item {
            if (stages.isEmpty()) throw new IllegalArgumentException("an item needs at least one stage");
            if (bufferBeforeMinutes < 0 || bufferAfterMinutes < 0)
                throw new IllegalArgumentException("buffers cannot be negative");
            stages = List.copyOf(stages);
            candidateStaff = List.copyOf(candidateStaff);
            requirements = List.copyOf(requirements);
            for (Requirement r : requirements)
                if (r.stageSeq() != null && r.stageSeq() > stages.size())
                    throw new IllegalArgumentException("requirement refers to a stage the item does not have");
        }

        int customerMinutes() {
            return stages.stream().mapToInt(Stage::minutes).sum();
        }
    }

    public record Resource(UUID id, String kind, int units) {}

    public record Interval(Instant start, Instant end) {
        public Interval {
            if (!end.isAfter(start)) throw new IllegalArgumentException("empty or reversed interval");
        }
    }

    /** One numbered unit (1..units) of a resource. */
    public record ResourceUnit(UUID resourceId, int unit) {}

    /**
     * The calendar facts around the location. Reservations are the live ones only: the loader leaves out released
     * reservations and holds that have expired, and includes forced overlaps.
     *
     * @param working working time per person (rota: pattern, shifts, minus time off)
     * @param staffReserved existing reservations per person
     * @param locationOpen open time after closures and holidays; ignored for at-home requests
     * @param resources resources at the location
     * @param resourceReserved existing reservations per resource unit
     */
    public record Facts(
            Map<UUID, List<Interval>> working,
            Map<UUID, List<Interval>> staffReserved,
            List<Interval> locationOpen,
            List<Resource> resources,
            Map<ResourceUnit, List<Interval>> resourceReserved) {}

    /**
     * @param from first local date (inclusive), in the location's zone
     * @param to last local date (inclusive)
     * @param onPremises false for at-home bookings: location hours do not apply, travel does
     * @param travelMinutes at-home only: staff busy this long before the first and after the last item
     */
    public record Request(
            List<Item> items,
            LocalDate from,
            LocalDate to,
            ZoneId zone,
            Instant now,
            int minNoticeMinutes,
            int maxAdvanceDays,
            int slotIntervalMinutes,
            boolean onPremises,
            int travelMinutes) {
        public Request {
            if (items.isEmpty()) throw new IllegalArgumentException("a booking needs at least one item");
            if (to.isBefore(from)) throw new IllegalArgumentException("to is before from");
            if (slotIntervalMinutes < 1) throw new IllegalArgumentException("slot interval must be positive");
            if (minNoticeMinutes < 0 || maxAdvanceDays < 0 || travelMinutes < 0)
                throw new IllegalArgumentException("notice, horizon and travel cannot be negative");
            items = List.copyOf(items);
        }
    }

    /**
     * Who does the item and which resource units it holds. {@code resourceUnits} lists, in the order of the item's
     * requirements, exactly {@code units} entries for each requirement.
     */
    public record Assignment(UUID staffId, List<ResourceUnit> resourceUnits) {}

    /** A bookable start. {@code end} is when the customer is done (buffers and travel excluded). */
    public record Slot(Instant start, Instant end, List<Assignment> assignments) {}

    /** All bookable slots in the range, in start order. */
    public static List<Slot> slots(Request request, Facts facts) {
        Prepared prepared = new Prepared(facts);
        List<Slot> result = new ArrayList<>();
        long earliest = nanos(request.now().plus(Duration.ofMinutes(request.minNoticeMinutes())));
        long latestExclusive = nanos(request.now().plus(Duration.ofDays(request.maxAdvanceDays())));
        for (LocalDate date = request.from(); !date.isAfter(request.to()); date = date.plusDays(1)) {
            LocalDateTime local = date.atStartOfDay();
            while (local.toLocalDate().equals(date)) {
                ZonedDateTime zoned = ZonedDateTime.ofLocal(local, request.zone(), null);
                // A local time that does not exist (a clock change skipped it) is not offered.
                if (zoned.toLocalDateTime().equals(local)) {
                    long start = nanos(zoned.toInstant());
                    if (start >= earliest && start < latestExclusive) {
                        Slot slot = tryStart(request, prepared, start);
                        if (slot != null) result.add(slot);
                    }
                }
                local = local.plusMinutes(request.slotIntervalMinutes());
            }
        }
        return result;
    }

    // --- what a slot occupies (for booking) -----------------------------------------------------------------------

    /** A stage of a booked item in time. Travel appears as {@link StageKind#TRAVEL} stages before and after. */
    public record StageWindow(int seq, StageKind kind, boolean staffBusy, Instant start, Instant end) {}

    /** Time the item's person is blocked (a busy stage with its buffers, or travel), tagged with its stage. */
    public record StaffBlock(int stageSeq, Instant start, Instant end) {}

    /** A resource unit the item holds, over its window (buffers included), for the requirement at that index. */
    public record UnitWindow(int requirementIndex, ResourceUnit unit, Instant start, Instant end) {}

    /** One item of a slot laid out in time: customer-facing span, stages, the person's blocks and resource units. */
    public record ItemLayout(
            Instant start, Instant end, List<StageWindow> stages, List<StaffBlock> blocks, List<UnitWindow> units) {}

    /**
     * Lays out a slot that {@link #slots} offered for {@code request}: the exact windows it checked, so a booking writes
     * the same reservations the engine reasoned about.
     */
    public static List<ItemLayout> layout(Request request, Slot slot) {
        List<Item> items = request.items();
        List<ItemLayout> out = new ArrayList<>();
        long cursor = nanos(slot.start());
        for (int k = 0; k < items.size(); k++) {
            Item item = items.get(k);
            long travelBefore = k == 0 && !request.onPremises() ? minutes(request.travelMinutes()) : 0;
            long travelAfter = k == items.size() - 1 && !request.onPremises() ? minutes(request.travelMinutes()) : 0;
            Timeline t = Timeline.of(item, cursor, travelBefore, travelAfter);
            List<StageWindow> stages = new ArrayList<>();
            List<StaffBlock> blocks = new ArrayList<>();
            int seq = 1;
            if (travelBefore > 0) {
                stages.add(
                        new StageWindow(seq, StageKind.TRAVEL, true, instant(cursor - travelBefore), instant(cursor)));
                blocks.add(new StaffBlock(seq, instant(cursor - travelBefore), instant(cursor)));
                seq++;
            }
            int busyIndex = 0;
            int busyCount =
                    (int) item.stages().stream().filter(Stage::staffBusy).count();
            for (int i = 0; i < item.stages().size(); i++) {
                Stage stage = item.stages().get(i);
                long[] w = t.stageWindows[i];
                stages.add(new StageWindow(seq, stage.kind(), stage.staffBusy(), instant(w[0]), instant(w[1])));
                if (stage.staffBusy()) {
                    long from = busyIndex == 0 ? w[0] - t.bufferBefore : w[0];
                    long to = busyIndex == busyCount - 1 ? w[1] + t.bufferAfter : w[1];
                    blocks.add(new StaffBlock(seq, instant(from), instant(to)));
                    busyIndex++;
                }
                seq++;
            }
            if (travelAfter > 0) {
                stages.add(new StageWindow(
                        seq, StageKind.TRAVEL, true, instant(t.customerEnd), instant(t.customerEnd + travelAfter)));
                blocks.add(new StaffBlock(seq, instant(t.customerEnd), instant(t.customerEnd + travelAfter)));
            }
            List<UnitWindow> units = new ArrayList<>();
            List<ResourceUnit> assigned = slot.assignments().get(k).resourceUnits();
            int u = 0;
            for (int r = 0; r < item.requirements().size(); r++) {
                Requirement requirement = item.requirements().get(r);
                long[] window = t.resourceWindow(item, requirement);
                for (int n = 0; n < requirement.units(); n++) {
                    units.add(new UnitWindow(r, assigned.get(u++), instant(window[0]), instant(window[1])));
                }
            }
            out.add(new ItemLayout(instant(t.customerStart), instant(t.customerEnd), stages, blocks, units));
            cursor = t.customerEnd;
        }
        return out;
    }

    // --- one candidate start ---------------------------------------------------------------------------------------

    private static Slot tryStart(Request request, Prepared prepared, long start) {
        List<Item> items = request.items();
        List<Timeline> timelines = new ArrayList<>();
        long cursor = start;
        for (int k = 0; k < items.size(); k++) {
            long travelBefore = k == 0 && !request.onPremises() ? minutes(request.travelMinutes()) : 0;
            long travelAfter = k == items.size() - 1 && !request.onPremises() ? minutes(request.travelMinutes()) : 0;
            Timeline timeline = Timeline.of(items.get(k), cursor, travelBefore, travelAfter);
            timelines.add(timeline);
            cursor = timeline.customerEnd;
        }
        if (request.onPremises() && !prepared.open.contains(start, cursor)) return null;

        // People: independent per item (a person's own consecutive items never conflict), so first-feasible is exact.
        List<UUID> staff = new ArrayList<>();
        for (int k = 0; k < items.size(); k++) {
            UUID chosen = null;
            for (UUID candidate : items.get(k).candidateStaff()) {
                if (staffFree(prepared, candidate, timelines.get(k))) {
                    chosen = candidate;
                    break;
                }
            }
            if (chosen == null) return null;
            staff.add(chosen);
        }

        // Resources: exact search across every need of every item (they can compete for the same units).
        List<Need> needs = new ArrayList<>();
        for (int k = 0; k < items.size(); k++) {
            for (Requirement requirement : items.get(k).requirements()) {
                needs.add(new Need(k, requirement, timelines.get(k).resourceWindow(items.get(k), requirement)));
            }
        }
        List<List<ResourceUnit>> chosenUnits = new ArrayList<>();
        if (!needs.isEmpty()) {
            Search search = new Search(prepared, needs);
            if (!search.run()) return null;
            chosenUnits = search.chosen;
        }

        List<Assignment> assignments = new ArrayList<>();
        for (int k = 0; k < items.size(); k++) {
            List<ResourceUnit> units = new ArrayList<>();
            for (int n = 0; n < needs.size(); n++) if (needs.get(n).item == k) units.addAll(chosenUnits.get(n));
            assignments.add(new Assignment(staff.get(k), List.copyOf(units)));
        }
        return new Slot(instant(start), instant(cursor), List.copyOf(assignments));
    }

    private static boolean staffFree(Prepared prepared, UUID staffId, Timeline timeline) {
        Sorted working = prepared.working.get(staffId);
        if (working == null) return false;
        for (long[] w : timeline.busy) if (!working.contains(w[0], w[1])) return false;
        Sorted reserved = prepared.staffReserved.getOrDefault(staffId, Sorted.EMPTY);
        for (long[] w : timeline.blocking) if (reserved.overlaps(w[0], w[1])) return false;
        return true;
    }

    // --- an item laid out in time ----------------------------------------------------------------------------------

    private static final class Timeline {
        long customerStart;
        long customerEnd;
        /** Windows the person must be working through: busy stages and travel. */
        final List<long[]> busy = new ArrayList<>();
        /** Windows that must be free of other reservations: busy stages with buffers, and travel. */
        final List<long[]> blocking = new ArrayList<>();

        long[][] stageWindows;
        long bufferBefore;
        long bufferAfter;

        static Timeline of(Item item, long start, long travelBefore, long travelAfter) {
            Timeline t = new Timeline();
            t.customerStart = start;
            t.bufferBefore = minutes(item.bufferBeforeMinutes());
            t.bufferAfter = minutes(item.bufferAfterMinutes());
            t.stageWindows = new long[item.stages().size()][];
            long cursor = start;
            List<long[]> busyStages = new ArrayList<>();
            for (int i = 0; i < item.stages().size(); i++) {
                Stage stage = item.stages().get(i);
                long end = cursor + minutes(stage.minutes());
                t.stageWindows[i] = new long[] {cursor, end};
                if (stage.staffBusy()) busyStages.add(new long[] {cursor, end});
                cursor = end;
            }
            t.customerEnd = cursor;
            for (int i = 0; i < busyStages.size(); i++) {
                long[] w = busyStages.get(i);
                t.busy.add(new long[] {w[0], w[1]});
                long from = i == 0 ? w[0] - t.bufferBefore : w[0];
                long to = i == busyStages.size() - 1 ? w[1] + t.bufferAfter : w[1];
                t.blocking.add(new long[] {from, to});
            }
            if (travelBefore > 0) {
                t.busy.add(new long[] {start - travelBefore, start});
                t.blocking.add(new long[] {start - travelBefore, start});
            }
            if (travelAfter > 0) {
                t.busy.add(new long[] {cursor, cursor + travelAfter});
                t.blocking.add(new long[] {cursor, cursor + travelAfter});
            }
            return t;
        }

        /** The window a resource requirement occupies: the item (or one stage), with buffers at the ends. */
        long[] resourceWindow(Item item, Requirement requirement) {
            if (requirement.stageSeq() == null)
                return new long[] {customerStart - bufferBefore, customerEnd + bufferAfter};
            int index = requirement.stageSeq() - 1;
            long from = stageWindows[index][0] - (index == 0 ? bufferBefore : 0);
            long to = stageWindows[index][1] + (index == item.stages().size() - 1 ? bufferAfter : 0);
            return new long[] {from, to};
        }
    }

    // --- exact resource assignment ---------------------------------------------------------------------------------

    private record Need(int item, Requirement requirement, long[] window) {}

    private static final class Search {
        final Prepared prepared;
        final List<Need> needs;
        final List<List<ResourceUnit>> chosen = new ArrayList<>();
        /** Windows taken by earlier needs of this candidate slot, per unit. */
        final Map<ResourceUnit, List<long[]>> taken = new HashMap<>();

        int steps;

        Search(Prepared prepared, List<Need> needs) {
            this.prepared = prepared;
            this.needs = needs;
            for (int i = 0; i < needs.size(); i++) chosen.add(List.of());
        }

        boolean run() {
            return place(0);
        }

        private boolean place(int index) {
            if (index == needs.size()) return true;
            if (++steps > MAX_SEARCH_STEPS) return false;
            Need need = needs.get(index);
            for (List<ResourceUnit> option : options(need)) {
                chosen.set(index, option);
                for (ResourceUnit unit : option)
                    taken.computeIfAbsent(unit, u -> new ArrayList<>()).add(need.window);
                if (place(index + 1)) return true;
                for (ResourceUnit unit : option) {
                    List<long[]> list = taken.get(unit);
                    list.remove(list.size() - 1);
                }
                if (steps > MAX_SEARCH_STEPS) return false;
            }
            chosen.set(index, List.of());
            return false;
        }

        /** Every way (bounded) to satisfy the requirement now: resources by id, free units in ascending order. */
        private List<List<ResourceUnit>> options(Need need) {
            Requirement requirement = need.requirement;
            List<Resource> candidates = new ArrayList<>();
            for (Resource resource : prepared.resources) {
                boolean match = requirement.resourceId() != null
                        ? resource.id().equals(requirement.resourceId())
                        : resource.kind().equals(requirement.kind());
                if (match) candidates.add(resource);
            }
            candidates.sort(Comparator.comparing(Resource::id));
            List<List<ResourceUnit>> options = new ArrayList<>();
            for (Resource resource : candidates) {
                List<ResourceUnit> free = new ArrayList<>();
                for (int unit = 1; unit <= resource.units(); unit++) {
                    ResourceUnit candidate = new ResourceUnit(resource.id(), unit);
                    if (isFree(candidate, need.window)) free.add(candidate);
                }
                combinations(free, requirement.units(), 0, new ArrayList<>(), options);
                if (options.size() >= MAX_OPTIONS_PER_NEED) break;
            }
            return options;
        }

        private boolean isFree(ResourceUnit unit, long[] window) {
            if (prepared.resourceReserved.getOrDefault(unit, Sorted.EMPTY).overlaps(window[0], window[1])) return false;
            for (long[] other : taken.getOrDefault(unit, List.of()))
                if (other[0] < window[1] && window[0] < other[1]) return false;
            return true;
        }

        private static void combinations(
                List<ResourceUnit> free, int k, int from, List<ResourceUnit> current, List<List<ResourceUnit>> out) {
            if (out.size() >= MAX_OPTIONS_PER_NEED) return;
            if (current.size() == k) {
                out.add(List.copyOf(current));
                return;
            }
            for (int i = from; i <= free.size() - (k - current.size()); i++) {
                current.add(free.get(i));
                combinations(free, k, i + 1, current, out);
                current.remove(current.size() - 1);
                if (out.size() >= MAX_OPTIONS_PER_NEED) return;
            }
        }
    }

    // --- normalised facts ------------------------------------------------------------------------------------------

    private static final class Prepared {
        final Map<UUID, Sorted> working = new HashMap<>();
        final Map<UUID, Sorted> staffReserved = new HashMap<>();
        final Sorted open;
        final List<Resource> resources;
        final Map<ResourceUnit, Sorted> resourceReserved = new HashMap<>();

        Prepared(Facts facts) {
            facts.working().forEach((id, list) -> working.put(id, Sorted.of(list)));
            facts.staffReserved().forEach((id, list) -> staffReserved.put(id, Sorted.of(list)));
            open = Sorted.of(facts.locationOpen());
            resources = List.copyOf(facts.resources());
            facts.resourceReserved().forEach((unit, list) -> resourceReserved.put(unit, Sorted.of(list)));
        }
    }

    /** Sorted, merged half-open intervals as nanoseconds since the epoch, with binary-search queries. */
    private static final class Sorted {
        static final Sorted EMPTY = new Sorted(new long[0], new long[0]);

        final long[] starts;
        final long[] ends;

        private Sorted(long[] starts, long[] ends) {
            this.starts = starts;
            this.ends = ends;
        }

        static Sorted of(List<Interval> intervals) {
            if (intervals.isEmpty()) return EMPTY;
            long[][] pairs = intervals.stream()
                    .map(i -> new long[] {nanos(i.start()), nanos(i.end())})
                    .sorted(Comparator.comparingLong((long[] p) -> p[0]).thenComparingLong(p -> p[1]))
                    .toArray(long[][]::new);
            long[] s = new long[pairs.length];
            long[] e = new long[pairs.length];
            int n = 0;
            for (long[] p : pairs) {
                if (n > 0 && p[0] <= e[n - 1]) {
                    e[n - 1] = Math.max(e[n - 1], p[1]); // overlapping or touching: one block
                } else {
                    s[n] = p[0];
                    e[n] = p[1];
                    n++;
                }
            }
            return new Sorted(Arrays.copyOf(s, n), Arrays.copyOf(e, n));
        }

        /** Does any interval overlap {@code [a, b)}? */
        boolean overlaps(long a, long b) {
            // first interval whose end is after a
            int lo = 0;
            int hi = ends.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (ends[mid] > a) hi = mid;
                else lo = mid + 1;
            }
            return lo < starts.length && starts[lo] < b;
        }

        /** Is {@code [a, b)} fully inside one interval? */
        boolean contains(long a, long b) {
            // last interval that starts at or before a
            int lo = 0;
            int hi = starts.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (starts[mid] <= a) lo = mid + 1;
                else hi = mid;
            }
            return lo > 0 && ends[lo - 1] >= b;
        }
    }

    // --- time helpers ----------------------------------------------------------------------------------------------

    private static final long NANOS_PER_MINUTE = 60_000_000_000L;

    private static long minutes(int minutes) {
        return minutes * NANOS_PER_MINUTE;
    }

    private static long nanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
    }

    private static Instant instant(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
    }
}
