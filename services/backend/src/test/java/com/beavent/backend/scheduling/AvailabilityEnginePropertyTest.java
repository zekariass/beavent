package com.beavent.backend.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.beavent.backend.scheduling.internal.AvailabilityEngine;
import com.beavent.backend.scheduling.internal.AvailabilityEngine.Assignment;
import com.beavent.backend.scheduling.internal.AvailabilityEngine.Facts;
import com.beavent.backend.scheduling.internal.AvailabilityEngine.Interval;
import com.beavent.backend.scheduling.internal.AvailabilityEngine.Item;
import com.beavent.backend.scheduling.internal.AvailabilityEngine.Request;
import com.beavent.backend.scheduling.internal.AvailabilityEngine.Requirement;
import com.beavent.backend.scheduling.internal.AvailabilityEngine.Resource;
import com.beavent.backend.scheduling.internal.AvailabilityEngine.ResourceUnit;
import com.beavent.backend.scheduling.internal.AvailabilityEngine.Slot;
import com.beavent.backend.scheduling.internal.AvailabilityEngine.Stage;
import com.beavent.backend.scheduling.internal.AvailabilityEngine.StageKind;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Property tests for docs/08-availability-engine.md §7. Random small calendars are generated as plain minute data;
 * the engine runs on them, and its answer is compared with a brute-force oracle and an independent verifier that
 * work minute by minute on boolean arrays and share no code with the engine.
 */
class AvailabilityEnginePropertyTest {

    static final ZoneId ZONE = ZoneId.of("Africa/Addis_Ababa");
    static final LocalDate DAY = LocalDate.of(2026, 12, 1);
    static final Instant MIDNIGHT = DAY.atStartOfDay(ZONE).toInstant();
    static final int ITERATIONS = 1500;

    // --- raw, minute-based model ---------------------------------------------------------------------------------

    record RawReq(UUID resourceId, String kind, int units, Integer stageSeq) {}

    /** stages: {minutes, busy 1|0}. */
    record RawItem(List<int[]> stages, int before, int after, List<UUID> staff, List<RawReq> reqs) {}

    record RawResource(UUID id, String kind, int units) {}

    static final class Raw {
        List<RawItem> items = new ArrayList<>();
        Map<UUID, List<int[]>> working = new HashMap<>();
        Map<UUID, List<int[]>> reserved = new HashMap<>();
        List<int[]> open = new ArrayList<>();
        List<RawResource> resources = new ArrayList<>();
        Map<ResourceUnit, List<int[]>> unitReserved = new HashMap<>();
        int interval;
        int noticeMinutes;
        int nowMinute;
        int maxAdvanceDays;
        boolean onPremises;
        int travel;

        Raw copy() {
            Raw c = new Raw();
            c.items = items;
            working.forEach((k, v) -> c.working.put(k, new ArrayList<>(v)));
            reserved.forEach((k, v) -> c.reserved.put(k, new ArrayList<>(v)));
            c.open = new ArrayList<>(open);
            c.resources = resources;
            unitReserved.forEach((k, v) -> c.unitReserved.put(k, new ArrayList<>(v)));
            c.interval = interval;
            c.noticeMinutes = noticeMinutes;
            c.nowMinute = nowMinute;
            c.maxAdvanceDays = maxAdvanceDays;
            c.onPremises = onPremises;
            c.travel = travel;
            return c;
        }

        String describe() {
            return "interval=" + interval + " notice=" + noticeMinutes + " now=" + nowMinute + " horizonDays="
                    + maxAdvanceDays
                    + " onPremises=" + onPremises + " travel=" + travel + " items=" + items.size() + " staff="
                    + working.size()
                    + " resources=" + resources.size();
        }
    }

    static Instant minute(int m) {
        return MIDNIGHT.plus(Duration.ofMinutes(m));
    }

    static Interval interval(int[] w) {
        return new Interval(minute(w[0]), minute(w[1]));
    }

    static List<Interval> intervals(List<int[]> list) {
        return list.stream().map(AvailabilityEnginePropertyTest::interval).toList();
    }

    // --- raw → engine input --------------------------------------------------------------------------------------

    static Request request(Raw raw) {
        List<Item> items = new ArrayList<>();
        for (RawItem r : raw.items) {
            List<Stage> stages = new ArrayList<>();
            for (int[] s : r.stages) stages.add(new Stage(StageKind.SERVICE, s[0], s[1] == 1));
            List<Requirement> reqs = new ArrayList<>();
            for (RawReq q : r.reqs) reqs.add(new Requirement(q.resourceId, q.kind, q.units, q.stageSeq));
            items.add(new Item(stages, r.before, r.after, r.staff, reqs));
        }
        return new Request(
                items,
                DAY,
                DAY,
                ZONE,
                minute(raw.nowMinute),
                raw.noticeMinutes,
                raw.maxAdvanceDays,
                raw.interval,
                raw.onPremises,
                raw.travel);
    }

    static Facts facts(Raw raw) {
        Map<UUID, List<Interval>> working = new HashMap<>();
        raw.working.forEach((k, v) -> working.put(k, intervals(v)));
        Map<UUID, List<Interval>> reserved = new HashMap<>();
        raw.reserved.forEach((k, v) -> reserved.put(k, intervals(v)));
        Map<ResourceUnit, List<Interval>> unitReserved = new HashMap<>();
        raw.unitReserved.forEach((k, v) -> unitReserved.put(k, intervals(v)));
        List<Resource> resources = raw.resources.stream()
                .map(r -> new Resource(r.id, r.kind, r.units))
                .toList();
        return new Facts(working, reserved, intervals(raw.open), resources, unitReserved);
    }

    static List<Slot> run(Raw raw) {
        return AvailabilityEngine.slots(request(raw), facts(raw));
    }

    static Set<Integer> startMinutes(List<Slot> slots) {
        Set<Integer> out = new TreeSet<>();
        for (Slot s : slots) out.add((int) Duration.between(MIDNIGHT, s.start()).toMinutes());
        return out;
    }

    // --- generator -----------------------------------------------------------------------------------------------

    static UUID staffId(int i) {
        return new UUID(0, 1 + i);
    }

    static UUID resourceId(int i) {
        return new UUID(0, 100 + i);
    }

    static int pick(Random r, int... values) {
        return values[r.nextInt(values.length)];
    }

    static int between5(Random r, int fromInclusive, int toInclusive) {
        return fromInclusive + 5 * r.nextInt((toInclusive - fromInclusive) / 5 + 1);
    }

    static int[] block(Random r, int earliest, int latest, int minLen, int maxLen) {
        int start = between5(r, earliest, latest - minLen);
        int len = Math.min(between5(r, minLen, maxLen), latest - start);
        return new int[] {start, start + Math.max(len, 5)};
    }

    static Raw generate(long seed) {
        Random r = new Random(seed);
        Raw raw = new Raw();
        int staffCount = 1 + r.nextInt(3);
        for (int i = 0; i < staffCount; i++) {
            UUID id = staffId(i);
            raw.working.put(id, new ArrayList<>());
            for (int b = 0, n = 1 + r.nextInt(2); b < n; b++)
                raw.working.get(id).add(block(r, 7 * 60, 18 * 60, 30, 360));
            raw.reserved.put(id, new ArrayList<>());
            for (int b = 0, n = r.nextInt(5); b < n; b++) raw.reserved.get(id).add(block(r, 7 * 60, 18 * 60, 5, 120));
        }
        raw.onPremises = r.nextInt(4) != 0;
        if (r.nextInt(5) < 2) raw.open.add(new int[] {-600, 1440 + 600}); // open all day, margins included
        else for (int b = 0, n = 1 + r.nextInt(2); b < n; b++) raw.open.add(block(r, 7 * 60, 19 * 60, 60, 600));
        for (int i = 0, n = r.nextInt(3); i < n; i++) {
            RawResource resource = new RawResource(resourceId(i), r.nextBoolean() ? "ROOM" : "BASIN", 1 + r.nextInt(2));
            raw.resources.add(resource);
            for (int unit = 1; unit <= resource.units; unit++) {
                List<int[]> blocks = new ArrayList<>();
                for (int b = 0, k = r.nextInt(3); b < k; b++) blocks.add(block(r, 7 * 60, 18 * 60, 15, 150));
                raw.unitReserved.put(new ResourceUnit(resource.id, unit), blocks);
            }
        }
        for (int k = 0, n = 1 + r.nextInt(2); k < n; k++) {
            List<int[]> stages = new ArrayList<>();
            for (int s = 0, m = 1 + r.nextInt(3); s < m; s++)
                stages.add(new int[] {between5(r, 5, 90), r.nextInt(4) == 0 ? 0 : 1});
            List<UUID> all = new ArrayList<>();
            for (int i = 0; i < staffCount; i++) all.add(staffId(i));
            if (r.nextInt(8) == 0) all.add(new UUID(0, 999)); // nobody knows this person
            Collections.shuffle(all, r);
            List<UUID> candidates = new ArrayList<>(all.subList(0, 1 + r.nextInt(all.size())));
            List<RawReq> reqs = new ArrayList<>();
            if (!raw.resources.isEmpty()) {
                for (int q = 0, c = r.nextInt(3); q < c; q++) {
                    Integer stageSeq = r.nextInt(3) == 0 ? Integer.valueOf(1 + r.nextInt(stages.size())) : null;
                    int units = 1 + r.nextInt(3);
                    if (r.nextBoolean()) {
                        RawResource target = raw.resources.get(r.nextInt(raw.resources.size()));
                        reqs.add(new RawReq(target.id, null, units, stageSeq));
                    } else {
                        reqs.add(new RawReq(null, r.nextBoolean() ? "ROOM" : "BASIN", units, stageSeq));
                    }
                }
            }
            raw.items.add(new RawItem(stages, pick(r, 0, 0, 5, 10, 15), pick(r, 0, 0, 5, 10, 15), candidates, reqs));
        }
        raw.interval = pick(r, 5, 10, 15, 30, 60);
        raw.noticeMinutes = pick(r, 0, 0, 30, 60, 120);
        raw.nowMinute = pick(r, -2880, -540, 360, 570);
        raw.maxAdvanceDays = pick(r, 1, 30, 30);
        raw.travel = raw.onPremises ? 0 : pick(r, 0, 15, 30);
        return raw;
    }

    // --- minute arrays (independent of the engine) ---------------------------------------------------------------

    static final int OFF = 1200;
    static final int SIZE = 1440 + 2 * OFF;

    static boolean[] marks(List<int[]> blocks) {
        boolean[] a = new boolean[SIZE];
        if (blocks != null) for (int[] b : blocks) mark(a, b[0], b[1]);
        return a;
    }

    static void mark(boolean[] a, int from, int to) {
        for (int m = Math.max(from, -OFF); m < Math.min(to, 1440 + OFF); m++) a[m + OFF] = true;
    }

    static boolean all(boolean[] a, int from, int to) {
        for (int m = from; m < to; m++) if (m < -OFF || m >= 1440 + OFF || !a[m + OFF]) return false;
        return true;
    }

    static boolean any(boolean[] a, int from, int to) {
        for (int m = Math.max(from, -OFF); m < Math.min(to, 1440 + OFF); m++) if (a[m + OFF]) return true;
        return false;
    }

    /** An item placed at {@code start}: customer span, busy windows and blocking windows, all in minutes. */
    record Placed(
            int start, int end, List<int[]> busy, List<int[]> blocking, int[][] stageWindows, int before, int after) {}

    static List<Placed> place(Raw raw, int start) {
        List<Placed> placed = new ArrayList<>();
        int cursor = start;
        for (int k = 0; k < raw.items.size(); k++) {
            RawItem item = raw.items.get(k);
            int itemStart = cursor;
            int[][] windows = new int[item.stages.size()][];
            List<int[]> busyStages = new ArrayList<>();
            for (int s = 0; s < item.stages.size(); s++) {
                int[] stage = item.stages.get(s);
                windows[s] = new int[] {cursor, cursor + stage[0]};
                if (stage[1] == 1) busyStages.add(windows[s]);
                cursor += stage[0];
            }
            List<int[]> busy = new ArrayList<>(busyStages);
            List<int[]> blocking = new ArrayList<>();
            for (int s = 0; s < busyStages.size(); s++) {
                int from = busyStages.get(s)[0] - (s == 0 ? item.before : 0);
                int to = busyStages.get(s)[1] + (s == busyStages.size() - 1 ? item.after : 0);
                blocking.add(new int[] {from, to});
            }
            if (!raw.onPremises && k == 0 && raw.travel > 0) {
                busy.add(new int[] {itemStart - raw.travel, itemStart});
                blocking.add(new int[] {itemStart - raw.travel, itemStart});
            }
            placed.add(new Placed(itemStart, cursor, busy, blocking, windows, item.before, item.after));
        }
        if (!raw.onPremises && raw.travel > 0) {
            Placed last = placed.get(placed.size() - 1);
            last.busy.add(new int[] {last.end, last.end + raw.travel});
            last.blocking.add(new int[] {last.end, last.end + raw.travel});
        }
        return placed;
    }

    /** The window a requirement holds: the whole item or one stage, with the buffers at the ends. */
    static int[] window(Placed p, RawItem item, RawReq q) {
        if (q.stageSeq == null) return new int[] {p.start - p.before, p.end + p.after};
        int i = q.stageSeq - 1;
        return new int[] {
            p.stageWindows[i][0] - (i == 0 ? p.before : 0),
            p.stageWindows[i][1] + (i == item.stages.size() - 1 ? p.after : 0)
        };
    }

    static boolean startAllowed(Raw raw, int start) {
        long fromNow = start - (long) raw.nowMinute;
        return fromNow >= raw.noticeMinutes && fromNow < (long) raw.maxAdvanceDays * 1440;
    }

    static boolean staffOk(Raw raw, UUID staff, Placed p) {
        List<int[]> working = raw.working.get(staff);
        if (working == null) return false;
        boolean[] w = marks(working);
        for (int[] b : p.busy) if (!all(w, b[0], b[1])) return false;
        boolean[] reserved = marks(raw.reserved.get(staff));
        for (int[] b : p.blocking) if (any(reserved, b[0], b[1])) return false;
        return true;
    }

    // --- the oracle: every grid time, every person, every resource assignment ------------------------------------

    static Set<Integer> oracle(Raw raw) {
        Set<Integer> starts = new TreeSet<>();
        boolean[] open = marks(raw.open);
        for (int start = 0; start < 1440; start += raw.interval) {
            if (!startAllowed(raw, start)) continue;
            List<Placed> placed = place(raw, start);
            int end = placed.get(placed.size() - 1).end;
            if (raw.onPremises && !all(open, start, end)) continue;
            boolean staffFine = true;
            for (int k = 0; k < raw.items.size() && staffFine; k++) {
                boolean any = false;
                for (UUID candidate : raw.items.get(k).staff) any |= staffOk(raw, candidate, placed.get(k));
                staffFine = any;
            }
            if (!staffFine) continue;
            List<int[]> needWindows = new ArrayList<>();
            List<RawReq> needs = new ArrayList<>();
            for (int k = 0; k < raw.items.size(); k++)
                for (RawReq q : raw.items.get(k).reqs) {
                    needs.add(q);
                    needWindows.add(window(placed.get(k), raw.items.get(k), q));
                }
            if (resourcesPossible(raw, needs, needWindows, 0, new HashMap<>())) starts.add(start);
        }
        return starts;
    }

    static boolean resourcesPossible(
            Raw raw, List<RawReq> needs, List<int[]> windows, int index, Map<ResourceUnit, boolean[]> taken) {
        if (index == needs.size()) return true;
        RawReq q = needs.get(index);
        int[] w = windows.get(index);
        for (RawResource resource : raw.resources) {
            boolean match = q.resourceId != null ? resource.id.equals(q.resourceId) : resource.kind.equals(q.kind);
            if (!match) continue;
            List<ResourceUnit> free = new ArrayList<>();
            for (int unit = 1; unit <= resource.units; unit++) {
                ResourceUnit u = new ResourceUnit(resource.id, unit);
                if (any(marks(raw.unitReserved.get(u)), w[0], w[1])) continue;
                boolean[] t = taken.get(u);
                if (t != null && any(t, w[0], w[1])) continue;
                free.add(u);
            }
            if (subsets(raw, needs, windows, index, taken, free, q.units, 0, new ArrayList<>(), w)) return true;
        }
        return false;
    }

    static boolean subsets(
            Raw raw,
            List<RawReq> needs,
            List<int[]> windows,
            int index,
            Map<ResourceUnit, boolean[]> taken,
            List<ResourceUnit> free,
            int k,
            int from,
            List<ResourceUnit> chosen,
            int[] w) {
        if (chosen.size() == k) {
            Map<ResourceUnit, boolean[]> next = new HashMap<>();
            taken.forEach((u, a) -> next.put(u, a.clone()));
            for (ResourceUnit u : chosen) mark(next.computeIfAbsent(u, x -> new boolean[SIZE]), w[0], w[1]);
            return resourcesPossible(raw, needs, windows, index + 1, next);
        }
        for (int i = from; i < free.size(); i++) {
            chosen.add(free.get(i));
            if (subsets(raw, needs, windows, index, taken, free, k, i + 1, chosen, w)) return true;
            chosen.remove(chosen.size() - 1);
        }
        return false;
    }

    // --- the verifier: is each offered slot really valid? ---------------------------------------------------------

    static void verify(Raw raw, Slot slot, String where) {
        int start = (int) Duration.between(MIDNIGHT, slot.start()).toMinutes();
        assertThat(start % raw.interval).as(where + " on the grid").isZero();
        assertThat(startAllowed(raw, start)).as(where + " notice and horizon").isTrue();
        List<Placed> placed = place(raw, start);
        int end = placed.get(placed.size() - 1).end;
        assertThat(Duration.between(MIDNIGHT, slot.end()).toMinutes())
                .as(where + " end")
                .isEqualTo(end);
        if (raw.onPremises)
            assertThat(all(marks(raw.open), start, end))
                    .as(where + " inside opening hours")
                    .isTrue();
        assertThat(slot.assignments()).as(where + " one assignment per item").hasSize(raw.items.size());
        Map<ResourceUnit, boolean[]> taken = new HashMap<>();
        for (int k = 0; k < raw.items.size(); k++) {
            RawItem item = raw.items.get(k);
            Assignment assignment = slot.assignments().get(k);
            assertThat(item.staff).as(where + " assigned person is a candidate").contains(assignment.staffId());
            assertThat(staffOk(raw, assignment.staffId(), placed.get(k)))
                    .as(where + " person free and working, item " + k)
                    .isTrue();
            int cursor = 0;
            for (RawReq q : item.reqs) {
                List<ResourceUnit> units = assignment.resourceUnits().subList(cursor, cursor + q.units);
                cursor += q.units;
                int[] w = window(placed.get(k), item, q);
                assertThat(units).as(where + " units are distinct").doesNotHaveDuplicates();
                for (ResourceUnit u : units) {
                    RawResource resource = raw.resources.stream()
                            .filter(x -> x.id.equals(u.resourceId()))
                            .findFirst()
                            .orElseThrow();
                    assertThat(q.resourceId != null ? resource.id.equals(q.resourceId) : resource.kind.equals(q.kind))
                            .as(where + " right resource")
                            .isTrue();
                    assertThat(u.unit()).as(where + " real unit").isBetween(1, resource.units);
                    assertThat(any(marks(raw.unitReserved.get(u)), w[0], w[1]))
                            .as(where + " unit free of reservations")
                            .isFalse();
                    boolean[] t = taken.computeIfAbsent(u, x -> new boolean[SIZE]);
                    assertThat(any(t, w[0], w[1]))
                            .as(where + " unit not used twice")
                            .isFalse();
                    mark(t, w[0], w[1]);
                }
            }
            assertThat(cursor)
                    .as(where + " every unit accounted for")
                    .isEqualTo(assignment.resourceUnits().size());
        }
    }

    // --- the properties ------------------------------------------------------------------------------------------

    @Test
    void everyOfferedSlotIsValidAndNoValidStartIsMissing() {
        int offered = 0;
        int instancesWithSlots = 0;
        for (long seed = 0; seed < ITERATIONS; seed++) {
            Raw raw = generate(seed);
            List<Slot> slots = run(raw);
            String where = "seed " + seed + " (" + raw.describe() + ")";
            for (Slot slot : slots) verify(raw, slot, where);
            assertThat(startMinutes(slots))
                    .as(where + ": offered starts equal the oracle's")
                    .isEqualTo(oracle(raw));
            offered += slots.size();
            if (!slots.isEmpty()) instancesWithSlots++;
        }
        // The generator must exercise both outcomes, or the properties prove nothing.
        assertThat(instancesWithSlots)
                .as("instances that offer something")
                .isBetween(ITERATIONS / 5, ITERATIONS - ITERATIONS / 20);
        assertThat(offered).isGreaterThan(ITERATIONS);
    }

    @Test
    void addingAReservationNeverAddsSlotsAndRemovingOneNeverRemovesSlots() {
        Random random = new Random(7);
        int compared = 0;
        for (long seed = 0; seed < ITERATIONS; seed++) {
            Raw raw = generate(seed);
            Set<Integer> before = startMinutes(run(raw));

            Raw more = raw.copy();
            List<UUID> people = new ArrayList<>(more.reserved.keySet());
            UUID who = people.get(random.nextInt(people.size()));
            more.reserved.get(who).add(block(random, 7 * 60, 18 * 60, 5, 180));
            if (!more.unitReserved.isEmpty() && random.nextBoolean()) {
                List<ResourceUnit> units = new ArrayList<>(more.unitReserved.keySet());
                more.unitReserved
                        .get(units.get(random.nextInt(units.size())))
                        .add(block(random, 7 * 60, 18 * 60, 5, 180));
            }
            assertThat(startMinutes(run(more)))
                    .as("seed " + seed + ": more reservations")
                    .isSubsetOf(before);

            Raw fewer = raw.copy();
            List<List<int[]>> nonEmpty = new ArrayList<>();
            fewer.reserved.values().stream().filter(l -> !l.isEmpty()).forEach(nonEmpty::add);
            fewer.unitReserved.values().stream().filter(l -> !l.isEmpty()).forEach(nonEmpty::add);
            if (nonEmpty.isEmpty()) continue;
            List<int[]> victim = nonEmpty.get(random.nextInt(nonEmpty.size()));
            victim.remove(random.nextInt(victim.size()));
            assertThat(startMinutes(run(fewer)))
                    .as("seed " + seed + ": fewer reservations")
                    .containsAll(before);
            compared++;
        }
        assertThat(compared).isGreaterThan(ITERATIONS / 2);
    }

    @Test
    void oneLocationDayWithTenPeopleIsFast() {
        Random r = new Random(42);
        Raw raw = new Raw();
        for (int i = 0; i < 10; i++) {
            UUID id = staffId(i);
            raw.working.put(id, new ArrayList<>(List.of(new int[] {8 * 60, 18 * 60})));
            raw.reserved.put(id, new ArrayList<>());
        }
        for (int b = 0; b < 60; b++) raw.reserved.get(staffId(r.nextInt(10))).add(block(r, 8 * 60, 18 * 60, 30, 90));
        raw.open.add(new int[] {8 * 60, 18 * 60});
        List<UUID> everyone = new ArrayList<>();
        for (int i = 0; i < 10; i++) everyone.add(staffId(i));
        raw.items.add(new RawItem(List.of(new int[] {60, 1}), 0, 0, everyone, List.of()));
        raw.items.add(new RawItem(List.of(new int[] {45, 1}), 5, 5, everyone, List.of()));
        raw.items.add(new RawItem(List.of(new int[] {30, 1}), 0, 0, everyone, List.of()));
        raw.interval = 5;
        raw.nowMinute = -2880;
        raw.maxAdvanceDays = 30;
        raw.onPremises = true;

        for (int warm = 0; warm < 5; warm++) run(raw);
        long begin = System.nanoTime();
        List<Slot> slots = run(raw);
        long millis = (System.nanoTime() - begin) / 1_000_000;
        assertThat(slots).isNotEmpty();
        assertThat(millis)
                .as("engine time for one location-day, 10 people, 60 reservations, 3 items")
                .isLessThan(300);
        slots.forEach(s -> verify(raw, s, "performance case"));
    }
}
