package com.beavent.backend.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The rules of docs/08-availability-engine.md §4, one case each (§6). Addis Ababa is UTC+3 with no daylight saving. */
class AvailabilityEngineTest {

    static final ZoneId ADDIS = ZoneId.of("Africa/Addis_Ababa");
    static final LocalDate DAY = LocalDate.of(2026, 12, 1);
    static final UUID A = new UUID(0, 1);
    static final UUID B = new UUID(0, 2);
    static final UUID ROOM1 = new UUID(0, 11);
    static final UUID ROOM2 = new UUID(0, 12);

    // --- builders ------------------------------------------------------------------------------------------------

    static Instant at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(ADDIS).toInstant();
    }

    static Interval iv(int h1, int m1, int h2, int m2) {
        return new Interval(at(h1, m1), at(h2, m2));
    }

    static Interval hours(int from, int to) {
        return iv(from, 0, to, 0);
    }

    /** A plain service of one busy stage. */
    static Item service(int minutes, UUID... staff) {
        return new Item(List.of(new Stage(StageKind.SERVICE, minutes, true)), 0, 0, List.of(staff), List.of());
    }

    static Item withBuffers(int minutes, int before, int after, UUID... staff) {
        return new Item(List.of(new Stage(StageKind.SERVICE, minutes, true)), before, after, List.of(staff), List.of());
    }

    static Item withRequirements(int minutes, List<Requirement> requirements, UUID... staff) {
        return new Item(List.of(new Stage(StageKind.SERVICE, minutes, true)), 0, 0, List.of(staff), requirements);
    }

    static final class Calendar {
        final Map<UUID, List<Interval>> working = new HashMap<>();
        final Map<UUID, List<Interval>> reserved = new HashMap<>();
        List<Interval> open = List.of(hours(9, 17));
        final List<Resource> resources = new ArrayList<>();
        final Map<ResourceUnit, List<Interval>> resourceReserved = new HashMap<>();

        Calendar works(UUID staff, Interval... intervals) {
            working.computeIfAbsent(staff, s -> new ArrayList<>()).addAll(List.of(intervals));
            return this;
        }

        Calendar reserves(UUID staff, Interval interval) {
            reserved.computeIfAbsent(staff, s -> new ArrayList<>()).add(interval);
            return this;
        }

        Calendar opens(Interval... intervals) {
            open = List.of(intervals);
            return this;
        }

        Calendar resource(UUID id, String kind, int units) {
            resources.add(new Resource(id, kind, units));
            return this;
        }

        Calendar reservesUnit(UUID resource, int unit, Interval interval) {
            resourceReserved
                    .computeIfAbsent(new ResourceUnit(resource, unit), u -> new ArrayList<>())
                    .add(interval);
            return this;
        }

        Facts facts() {
            return new Facts(working, reserved, open, resources, resourceReserved);
        }
    }

    static Request request(int intervalMinutes, Item... items) {
        return new Request(
                List.of(items),
                DAY,
                DAY,
                ADDIS,
                DAY.minusDays(2).atStartOfDay(ADDIS).toInstant(),
                0,
                30,
                intervalMinutes,
                true,
                0);
    }

    static List<LocalTime> starts(List<Slot> slots) {
        return slots.stream()
                .map(s -> ZonedDateTime.ofInstant(s.start(), ADDIS).toLocalTime())
                .toList();
    }

    static LocalTime t(int hour, int minute) {
        return LocalTime.of(hour, minute);
    }

    // --- opening hours ------------------------------------------------------------------------------------------

    @Test
    void startsAtOpeningAndMayEndExactlyAtClosing() {
        Calendar calendar = new Calendar().works(A, hours(9, 17));
        List<Slot> slots = AvailabilityEngine.slots(request(15, service(60, A)), calendar.facts());
        assertThat(starts(slots)).first().isEqualTo(t(9, 0));
        assertThat(starts(slots)).last().isEqualTo(t(16, 0));
        assertThat(slots).first().satisfies(s -> assertThat(s.end()).isEqualTo(at(10, 0)));
    }

    @Test
    void oneMinutePastClosingIsNotOffered() {
        Calendar calendar = new Calendar().works(A, hours(9, 17));
        List<Slot> slots = AvailabilityEngine.slots(request(15, service(61, A)), calendar.facts());
        assertThat(starts(slots)).last().isEqualTo(t(15, 45));
        assertThat(starts(slots)).doesNotContain(t(16, 0));
    }

    @Test
    void closedLocationOffersNothingOnPremises() {
        Calendar calendar = new Calendar().works(A, hours(9, 17)).opens();
        assertThat(AvailabilityEngine.slots(request(15, service(30, A)), calendar.facts()))
                .isEmpty();
    }

    @Test
    void locationClosureGapBlocksSpanningItems() {
        // A closure was already removed from the open time: open 9–12 and 13–17.
        Calendar calendar = new Calendar().works(A, hours(9, 17)).opens(hours(9, 12), hours(13, 17));
        List<LocalTime> starts = starts(AvailabilityEngine.slots(request(30, service(60, A)), calendar.facts()));
        assertThat(starts).contains(t(11, 0), t(13, 0)).doesNotContain(t(11, 30), t(12, 0), t(12, 30));
    }

    // --- reservations -------------------------------------------------------------------------------------------

    @Test
    void backToBackIsAllowedButOverlapIsNot() {
        Calendar calendar = new Calendar().works(A, hours(9, 17)).reserves(A, iv(10, 0, 11, 0));
        List<LocalTime> exact = starts(AvailabilityEngine.slots(request(5, service(60, A)), calendar.facts()));
        assertThat(exact).contains(t(9, 0), t(11, 0)).doesNotContain(t(9, 5), t(10, 0), t(10, 55));
        List<LocalTime> minutePast = starts(AvailabilityEngine.slots(request(5, service(65, A)), calendar.facts()));
        assertThat(minutePast).doesNotContain(t(9, 0)); // 09:00 + 65 min runs into the 10:00 reservation
        assertThat(minutePast).contains(t(11, 0));
    }

    @Test
    void overlappingAndDuplicateReservationsMerge() {
        Calendar calendar = new Calendar()
                .works(A, hours(9, 17))
                .reserves(A, iv(10, 0, 11, 0))
                .reserves(A, iv(10, 30, 12, 0))
                .reserves(A, iv(10, 30, 12, 0));
        List<LocalTime> starts = starts(AvailabilityEngine.slots(request(30, service(30, A)), calendar.facts()));
        assertThat(starts).contains(t(9, 30), t(12, 0)).doesNotContain(t(10, 0), t(10, 30), t(11, 0), t(11, 30));
    }

    @Test
    void bufferThatCollidesWithTheNextReservationBlocksTheSlot() {
        Calendar calendar =
                new Calendar().works(A, hours(8, 17)).opens(hours(8, 17)).reserves(A, iv(10, 0, 11, 0));
        List<LocalTime> starts =
                starts(AvailabilityEngine.slots(request(15, withBuffers(60, 0, 15, A)), calendar.facts()));
        assertThat(starts).contains(t(8, 45), t(11, 0)); // 08:45–09:45, buffer to 10:00 exactly
        assertThat(starts).doesNotContain(t(9, 0)); // would end at 10:00 but its 15-minute buffer runs to 10:15
    }

    @Test
    void bufferBeforeMustAlsoBeFree() {
        Calendar calendar =
                new Calendar().works(A, hours(8, 17)).opens(hours(8, 17)).reserves(A, iv(8, 0, 9, 0));
        List<LocalTime> starts =
                starts(AvailabilityEngine.slots(request(15, withBuffers(30, 15, 0, A)), calendar.facts()));
        assertThat(starts).doesNotContain(t(9, 0), t(9, 10)).contains(t(9, 15));
    }

    // --- working time -------------------------------------------------------------------------------------------

    @Test
    void shiftsAndLunchGap() {
        Calendar calendar = new Calendar().works(A, hours(9, 13), hours(14, 18)).opens(hours(9, 18));
        List<LocalTime> starts = starts(AvailabilityEngine.slots(request(30, service(90, A)), calendar.facts()));
        // 11:30–13:00 ends exactly when the first shift ends; from 12:00 on the item would need the person through
        // lunch
        assertThat(starts).contains(t(10, 30), t(11, 30), t(14, 0)).doesNotContain(t(12, 0), t(12, 30), t(13, 0));
    }

    @Test
    void adjacentShiftsCountAsOneBlock() {
        Calendar calendar = new Calendar().works(A, hours(9, 13), hours(13, 17));
        List<LocalTime> starts = starts(AvailabilityEngine.slots(request(30, service(60, A)), calendar.facts()));
        assertThat(starts).contains(t(12, 30)); // 12:30–13:30 spans the two shifts
    }

    @Test
    void aProcessingStageMaySpanTheGapAndFreesThePerson() {
        Item colour = new Item(
                List.of(
                        new Stage(StageKind.SERVICE, 30, true),
                        new Stage(StageKind.PROCESSING, 90, false),
                        new Stage(StageKind.FINISHING, 30, true)),
                0,
                0,
                List.of(A),
                List.of());
        Calendar calendar = new Calendar().works(A, hours(9, 13), hours(14, 18)).opens(hours(9, 18));
        List<LocalTime> starts = starts(AvailabilityEngine.slots(request(30, colour), calendar.facts()));
        // 12:30: apply 12:30–13:00, process 13:00–14:30 (nobody needed), finish 14:30–15:00.
        // 12:00: apply 12:00–12:30, process to 14:00, finish 14:00–14:30. Both fit the two shifts.
        assertThat(starts).contains(t(12, 0), t(12, 30));
        // 11:30 would finish 13:30–14:00 and 13:00 would apply 13:00–13:30: the person is not working then.
        assertThat(starts).doesNotContain(t(11, 30), t(13, 0));
    }

    @Test
    void personServesSomeoneElseDuringProcessing() {
        Item colour = new Item(
                List.of(
                        new Stage(StageKind.SERVICE, 30, true),
                        new Stage(StageKind.PROCESSING, 60, false),
                        new Stage(StageKind.FINISHING, 30, true)),
                0,
                0,
                List.of(A),
                List.of());
        Calendar calendar = new Calendar().works(A, hours(9, 17)).reserves(A, iv(10, 30, 11, 30));
        List<LocalTime> starts = starts(AvailabilityEngine.slots(request(30, colour), calendar.facts()));
        // 10:00: apply 10:00–10:30, process 10:30–11:30 (overlaps the other customer, and that is fine), finish
        // 11:30–12:00
        assertThat(starts).contains(t(10, 0));
        // 10:30 would apply during the other customer; 09:00 and 09:30 would finish inside their reservation
        assertThat(starts).doesNotContain(t(9, 0), t(9, 30), t(10, 30));
    }

    // --- notice and horizon -------------------------------------------------------------------------------------

    @Test
    void noticeBoundaryIsInclusive() {
        Calendar calendar = new Calendar().works(A, hours(9, 17));
        Request request = new Request(List.of(service(30, A)), DAY, DAY, ADDIS, at(8, 0), 60, 30, 15, true, 0);
        List<LocalTime> starts = starts(AvailabilityEngine.slots(request, calendar.facts()));
        assertThat(starts).first().isEqualTo(t(9, 0)); // now 08:00 + 60 minutes = 09:00 exactly
        Request tooSoon = new Request(List.of(service(30, A)), DAY, DAY, ADDIS, at(8, 1), 60, 30, 15, true, 0);
        assertThat(starts(AvailabilityEngine.slots(tooSoon, calendar.facts())))
                .first()
                .isEqualTo(t(9, 15));
    }

    @Test
    void horizonBoundaryIsExclusive() {
        Calendar calendar = new Calendar().works(A, hours(9, 17));
        // now = 12:00; one day ahead = 12:00 tomorrow, which is not offered
        Request request = new Request(
                List.of(service(30, A)),
                DAY,
                DAY,
                ADDIS,
                DAY.minusDays(1).atTime(12, 0).atZone(ADDIS).toInstant(),
                0,
                1,
                30,
                true,
                0);
        List<LocalTime> starts = starts(AvailabilityEngine.slots(request, calendar.facts()));
        assertThat(starts).contains(t(11, 30)).doesNotContain(t(12, 0), t(12, 30));
    }

    // --- several services and people ----------------------------------------------------------------------------

    @Test
    void differentPeopleInSequence() {
        Calendar calendar =
                new Calendar().works(A, hours(9, 10)).works(B, hours(10, 12)).opens(hours(9, 12));
        List<Slot> slots = AvailabilityEngine.slots(request(15, service(60, A), service(60, B)), calendar.facts());
        assertThat(starts(slots)).containsExactly(t(9, 0));
        assertThat(slots.get(0).assignments()).extracting(Assignment::staffId).containsExactly(A, B);
        assertThat(slots.get(0).end()).isEqualTo(at(11, 0));

        Calendar later = new Calendar()
                .works(A, hours(9, 10))
                .works(B, iv(10, 30, 12, 0))
                .opens(hours(9, 12));
        assertThat(AvailabilityEngine.slots(request(15, service(60, A), service(60, B)), later.facts()))
                .isEmpty();
    }

    @Test
    void samePersonTwiceInARowDoesNotConflictWithTheirOwnBuffers() {
        Calendar calendar = new Calendar().works(A, hours(9, 17));
        Item first = withBuffers(30, 15, 15, A);
        Item second = withBuffers(30, 15, 15, A);
        List<Slot> slots = AvailabilityEngine.slots(request(15, first, second), calendar.facts());
        assertThat(slots).isNotEmpty();
        assertThat(slots.get(0).assignments()).extracting(Assignment::staffId).containsExactly(A, A);
    }

    @Test
    void firstFeasiblePersonInInputOrderIsAssigned() {
        Calendar calendar =
                new Calendar().works(A, hours(9, 17)).works(B, hours(9, 17)).reserves(A, iv(9, 0, 10, 0));
        List<Slot> slots = AvailabilityEngine.slots(request(30, service(30, A, B)), calendar.facts());
        assertThat(slots.get(0).assignments().get(0).staffId()).isEqualTo(B); // A is busy at 09:00
        Slot ten = slots.stream()
                .filter(s -> s.start().equals(at(10, 0)))
                .findFirst()
                .orElseThrow();
        assertThat(ten.assignments().get(0).staffId()).isEqualTo(A); // both free: input order wins
    }

    @Test
    void personWithoutWorkingTimeIsNeverACandidate() {
        Calendar calendar = new Calendar().works(A, hours(9, 17));
        assertThat(AvailabilityEngine.slots(request(30, service(30, B)), calendar.facts()))
                .isEmpty();
    }

    @Test
    void anItemLongerThanEveryFreeWindowYieldsNothing() {
        Calendar calendar = new Calendar().works(A, hours(9, 17));
        assertThat(AvailabilityEngine.slots(request(15, service(600, A)), calendar.facts()))
                .isEmpty();
    }

    // --- resources ----------------------------------------------------------------------------------------------

    @Test
    void aTwoUnitResourceLeavesOneUnitThenNone() {
        Requirement anyRoom = new Requirement(null, "ROOM", 1, null);
        Calendar calendar = new Calendar()
                .works(A, hours(9, 17))
                .resource(ROOM1, "ROOM", 2)
                .reservesUnit(ROOM1, 1, iv(10, 0, 11, 0));
        Request request = request(60, withRequirements(60, List.of(anyRoom), A));
        List<Slot> one = AvailabilityEngine.slots(request, calendar.facts());
        Slot ten = one.stream()
                .filter(s -> s.start().equals(at(10, 0)))
                .findFirst()
                .orElseThrow();
        assertThat(ten.assignments().get(0).resourceUnits()).containsExactly(new ResourceUnit(ROOM1, 2));

        calendar.reservesUnit(ROOM1, 2, iv(10, 0, 11, 0));
        assertThat(starts(AvailabilityEngine.slots(request, calendar.facts()))).doesNotContain(t(10, 0));
    }

    @Test
    void aSpecificResourceIsNotInterchangeableWithAnotherOfTheSameKind() {
        Requirement room1 = new Requirement(ROOM1, null, 1, null);
        Calendar calendar = new Calendar()
                .works(A, hours(9, 17))
                .resource(ROOM1, "ROOM", 1)
                .resource(ROOM2, "ROOM", 1)
                .reservesUnit(ROOM1, 1, iv(10, 0, 11, 0));
        List<LocalTime> starts = starts(
                AvailabilityEngine.slots(request(60, withRequirements(60, List.of(room1), A)), calendar.facts()));
        assertThat(starts).doesNotContain(t(10, 0)).contains(t(9, 0), t(11, 0));
    }

    @Test
    void resourceChoiceIsExactNotGreedy() {
        // Needs: any room for 1 unit, and a 2-unit room. A greedy "lowest id first" gives the 2-unit room to the first
        // need.
        Requirement anyRoom = new Requirement(null, "ROOM", 1, null);
        Requirement bigRoom = new Requirement(ROOM1, null, 2, null);
        Calendar calendar =
                new Calendar().works(A, hours(9, 17)).resource(ROOM1, "ROOM", 2).resource(ROOM2, "ROOM", 1);
        List<Slot> slots = AvailabilityEngine.slots(
                request(60, withRequirements(60, List.of(anyRoom, bigRoom), A)), calendar.facts());
        assertThat(slots).isNotEmpty();
        List<ResourceUnit> units = slots.get(0).assignments().get(0).resourceUnits();
        assertThat(units)
                .containsExactly(new ResourceUnit(ROOM2, 1), new ResourceUnit(ROOM1, 1), new ResourceUnit(ROOM1, 2));
    }

    @Test
    void aBufferHoldsTheResourceToo() {
        Requirement room = new Requirement(ROOM1, null, 1, null);
        Calendar calendar = new Calendar()
                .works(A, hours(9, 17))
                .resource(ROOM1, "ROOM", 1)
                .reservesUnit(ROOM1, 1, iv(10, 0, 11, 0));
        Item item = new Item(List.of(new Stage(StageKind.SERVICE, 60, true)), 0, 15, List.of(A), List.of(room));
        assertThat(starts(AvailabilityEngine.slots(request(15, item), calendar.facts())))
                .doesNotContain(t(9, 0)) // the room is still being reset until 10:15
                .contains(t(11, 0));
    }

    @Test
    void aStageRequirementOnlyHoldsTheResourceDuringThatStage() {
        Requirement basin = new Requirement(ROOM1, null, 1, 3); // the rinse at the end
        Item colour = new Item(
                List.of(
                        new Stage(StageKind.SERVICE, 30, true),
                        new Stage(StageKind.PROCESSING, 30, false),
                        new Stage(StageKind.FINISHING, 30, true)),
                0,
                0,
                List.of(A),
                List.of(basin));
        Calendar calendar = new Calendar()
                .works(A, hours(9, 17))
                .resource(ROOM1, "BASIN", 1)
                .reservesUnit(ROOM1, 1, iv(10, 0, 10, 30));
        List<LocalTime> starts = starts(AvailabilityEngine.slots(request(30, colour), calendar.facts()));
        // The basin is needed only in the last stage (start + 60 to start + 90). Holding it for the whole item would
        // block 09:30 and 10:00 as well.
        assertThat(starts).contains(t(9, 30), t(10, 0)).doesNotContain(t(9, 0));
    }

    // --- at-home ------------------------------------------------------------------------------------------------

    @Test
    void travelCountsAgainstWorkingTimeAndLocationHoursAreIgnored() {
        Calendar calendar = new Calendar().works(A, hours(9, 12)).opens();
        Request request = new Request(
                List.of(service(60, A)),
                DAY,
                DAY,
                ADDIS,
                DAY.minusDays(2).atStartOfDay(ADDIS).toInstant(),
                0,
                30,
                15,
                false,
                30);
        List<LocalTime> starts = starts(AvailabilityEngine.slots(request, calendar.facts()));
        // 30 min travel + 60 min service + 30 min travel = 09:00..12:00 at the latest start 10:30, earliest 09:30
        assertThat(starts).containsExactly(t(9, 30), t(9, 45), t(10, 0), t(10, 15), t(10, 30));
    }

    @Test
    void travelMustAlsoBeFreeOfReservations() {
        Calendar calendar = new Calendar().works(A, hours(9, 17)).reserves(A, iv(9, 0, 10, 0));
        Request request = new Request(
                List.of(service(60, A)),
                DAY,
                DAY,
                ADDIS,
                DAY.minusDays(2).atStartOfDay(ADDIS).toInstant(),
                0,
                30,
                30,
                false,
                30);
        assertThat(starts(AvailabilityEngine.slots(request, calendar.facts())).get(0))
                .isEqualTo(t(10, 30));
    }

    // --- clock changes ------------------------------------------------------------------------------------------

    @Test
    void aSkippedLocalHourIsNotOffered() {
        // London, 29 March 2026: clocks go from 01:00 to 02:00, so 01:00 and 01:30 do not exist.
        ZoneId london = ZoneId.of("Europe/London");
        LocalDate day = LocalDate.of(2026, 3, 29);
        Interval allDay = new Interval(
                day.atStartOfDay(london).toInstant(),
                day.plusDays(1).atStartOfDay(london).toInstant());
        Map<UUID, List<Interval>> working = Map.of(A, List.of(allDay));
        Facts facts = new Facts(working, Map.of(), List.of(allDay), List.of(), Map.of());
        Request request = new Request(
                List.of(service(30, A)),
                day,
                day,
                london,
                day.minusDays(2).atStartOfDay(london).toInstant(),
                0,
                30,
                30,
                true,
                0);
        List<Slot> slots = AvailabilityEngine.slots(request, facts);
        assertThat(slots).hasSize(46);
        assertThat(slots.stream()
                        .map(s -> ZonedDateTime.ofInstant(s.start(), london).toLocalTime()))
                .doesNotContain(t(1, 0), t(1, 30))
                .contains(t(0, 30), t(2, 0));
    }

    // --- input validation ---------------------------------------------------------------------------------------

    @Test
    void invalidInputsAreRejected() {
        assertThatThrownBy(() -> new Stage(StageKind.SERVICE, 0, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Requirement(null, null, 1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Requirement(ROOM1, "ROOM", 1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Interval(at(10, 0), at(10, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(15)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withRequirements(30, List.of(new Requirement(ROOM1, null, 1, 2)), A))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
