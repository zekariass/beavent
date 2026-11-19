package com.beavent.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Same vectors as packages/calendar, so the TypeScript and Java implementations cannot drift apart. */
class EthiopianCalendarTest {

    static final JsonNode VECTORS = JsonMapper.builder()
            .build()
            .readTree(Path.of("../packages/calendar/test/vectors.json").toFile());

    @Test
    void sharedDateVectors() {
        for (JsonNode vector : VECTORS.get("dates")) {
            JsonNode e = vector.get("ethiopian");
            JsonNode g = vector.get("gregorian");
            var ethiopian = new EthiopianCalendar.Date(
                    e.get(0).asInt(), e.get(1).asInt(), e.get(2).asInt());
            LocalDate gregorian =
                    LocalDate.of(g.get(0).asInt(), g.get(1).asInt(), g.get(2).asInt());
            assertThat(EthiopianCalendar.toGregorian(ethiopian))
                    .as(vector.toString())
                    .isEqualTo(gregorian);
            assertThat(EthiopianCalendar.fromGregorian(gregorian))
                    .as(vector.toString())
                    .isEqualTo(ethiopian);
        }
    }

    @Test
    void sharedTimeVectors() {
        for (JsonNode vector : VECTORS.get("times")) {
            LocalTime standard = LocalTime.of(
                    vector.get("standard").get(0).asInt(),
                    vector.get("standard").get(1).asInt());
            JsonNode e = vector.get("ethiopian");
            var ethiopian = new EthiopianCalendar.Time(
                    e.get(0).asInt(),
                    e.get(1).asInt(),
                    EthiopianCalendar.DayPeriod.valueOf(e.get(2).asString()));
            assertThat(EthiopianCalendar.toEthiopianTime(standard)).isEqualTo(ethiopian);
            assertThat(EthiopianCalendar.fromEthiopianTime(ethiopian)).isEqualTo(standard);
        }
    }

    @Test
    void everyDayOfThreeCenturiesRoundTripsAndIsConsecutive() {
        EthiopianCalendar.Date previous = EthiopianCalendar.fromGregorian(LocalDate.of(1899, 12, 31));
        for (LocalDate day = LocalDate.of(1900, 1, 1); day.getYear() < 2200; day = day.plusDays(1)) {
            EthiopianCalendar.Date ethiopian = EthiopianCalendar.fromGregorian(day);
            assertThat(EthiopianCalendar.toGregorian(ethiopian)).isEqualTo(day);
            boolean next = ethiopian.day() == previous.day() + 1
                    || (ethiopian.day() == 1
                            && previous.day() == EthiopianCalendar.daysInMonth(previous.year(), previous.month())
                            && (ethiopian.month() == previous.month() + 1
                                    || (ethiopian.month() == 1 && ethiopian.year() == previous.year() + 1)));
            assertThat(next).as(previous + " → " + ethiopian).isTrue();
            previous = ethiopian;
        }
    }

    @Test
    void formatsAndValidates() {
        var newYear = new EthiopianCalendar.Date(2017, 1, 1);
        assertThat(EthiopianCalendar.format(newYear, "am")).isEqualTo("መስከረም 1 ቀን 2017 ዓ.ም.");
        assertThat(EthiopianCalendar.format(newYear, "en")).isEqualTo("Meskerem 1, 2017 E.C.");
        assertThatThrownBy(() -> new EthiopianCalendar.Date(2016, 13, 6)).isInstanceOf(IllegalArgumentException.class);
    }
}
