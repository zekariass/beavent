package com.beavent.backend.platform;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.JulianFields;

/**
 * Ethiopian calendar (Amete Mihret) and Ethiopian local time, for documents rendered on the server (receipts,
 * messages). Display only: storage stays UTC/Gregorian. Twin of {@code packages/calendar}; both are checked against
 * {@code packages/calendar/test/vectors.json}.
 */
public final class EthiopianCalendar {

    /** A date in the Ethiopian calendar; month 13 is Pagume (5 or 6 days). */
    public record Date(int year, int month, int day) {

        public Date {
            if (year < 1 || month < 1 || month > 13 || day < 1 || day > daysInMonth(year, month)) {
                throw new IllegalArgumentException("Invalid Ethiopian date " + year + "-" + month + "-" + day);
            }
        }
    }

    public enum DayPeriod {
        MORNING,
        AFTERNOON,
        EVENING,
        NIGHT
    }

    /** Ethiopian time: hours counted from 06:00 and 18:00, e.g. 07:00 = 1 in the morning. */
    public record Time(int hour, int minute, DayPeriod period) {}

    private static final long EPOCH = 1_724_221L;
    private static final String[] MONTHS_AM = {
        "መስከረም", "ጥቅምት", "ኅዳር", "ታኅሣሥ", "ጥር", "የካቲት", "መጋቢት", "ሚያዝያ", "ግንቦት", "ሰኔ", "ሐምሌ", "ነሐሴ", "ጳጉሜ"
    };
    private static final String[] MONTHS_EN = {
        "Meskerem",
        "Tikimt",
        "Hidar",
        "Tahsas",
        "Tir",
        "Yekatit",
        "Megabit",
        "Miyazya",
        "Ginbot",
        "Sene",
        "Hamle",
        "Nehase",
        "Pagume"
    };

    private EthiopianCalendar() {}

    public static boolean isLeapYear(int year) {
        return Math.floorMod(year, 4) == 3;
    }

    public static int daysInMonth(int year, int month) {
        return month < 13 ? 30 : isLeapYear(year) ? 6 : 5;
    }

    public static Date fromGregorian(LocalDate date) {
        long days = date.getLong(JulianFields.JULIAN_DAY) - EPOCH + 365;
        long cycle = Math.floorDiv(days, 1461);
        long rest = days - cycle * 1461;
        long dayOfYear = rest % 365 + 365 * (rest / 1460);
        return new Date(
                (int) (4 * cycle + rest / 365 - rest / 1460), (int) (dayOfYear / 30) + 1, (int) (dayOfYear % 30) + 1);
    }

    public static LocalDate toGregorian(Date date) {
        long jdn = EPOCH
                + 365L * (date.year() - 1)
                + Math.floorDiv(date.year(), 4)
                + 30L * (date.month() - 1)
                + date.day()
                - 1;
        return LocalDate.MIN.with(JulianFields.JULIAN_DAY, jdn);
    }

    public static Time toEthiopianTime(LocalTime time) {
        int hour = time.getHour();
        int shifted = (hour + 18) % 12;
        DayPeriod period = hour >= 6 && hour < 12
                ? DayPeriod.MORNING
                : hour >= 12 && hour < 18 ? DayPeriod.AFTERNOON : hour >= 18 ? DayPeriod.EVENING : DayPeriod.NIGHT;
        return new Time(shifted == 0 ? 12 : shifted, time.getMinute(), period);
    }

    public static LocalTime fromEthiopianTime(Time time) {
        int blockStart = time.period() == DayPeriod.MORNING || time.period() == DayPeriod.AFTERNOON ? 6 : 18;
        return LocalTime.of((blockStart + time.hour() % 12) % 24, time.minute());
    }

    private static final java.util.Map<String, java.util.Map<DayPeriod, String>> PERIODS = java.util.Map.of(
            "am",
            java.util.Map.of(
                    DayPeriod.MORNING,
                    "ጠዋት",
                    DayPeriod.AFTERNOON,
                    "ከሰዓት",
                    DayPeriod.EVENING,
                    "ማታ",
                    DayPeriod.NIGHT,
                    "ሌሊት"),
            "en",
            java.util.Map.of(
                    DayPeriod.MORNING,
                    "in the morning",
                    DayPeriod.AFTERNOON,
                    "in the afternoon",
                    DayPeriod.EVENING,
                    "in the evening",
                    DayPeriod.NIGHT,
                    "at night"),
            "om",
            java.util.Map.of(
                    DayPeriod.MORNING,
                    "ganama",
                    DayPeriod.AFTERNOON,
                    "waaree booda",
                    DayPeriod.EVENING,
                    "galgala",
                    DayPeriod.NIGHT,
                    "halkan"));

    /**
     * "ጠዋት 3:00" (Amharic puts the period first) or "3:00 in the morning"; same output as
     * {@code formatEthiopianTime} in packages/calendar.
     */
    public static String formatTime(Time time, String language) {
        String clock = time.hour() + ":" + String.format("%02d", time.minute());
        String lang = PERIODS.containsKey(language) ? language : "en";
        return "am".equals(lang)
                ? PERIODS.get("am").get(time.period()) + " " + clock
                : clock + " " + PERIODS.get(lang).get(time.period());
    }

    /** "መስከረም 1 ቀን 2017 ዓ.ም." (am) or "Meskerem 1, 2017 E.C." (other languages). */
    public static String format(Date date, String language) {
        return "am".equals(language)
                ? MONTHS_AM[date.month() - 1] + " " + date.day() + " ቀን " + date.year() + " ዓ.ም."
                : MONTHS_EN[date.month() - 1] + " " + date.day() + ", " + date.year() + " E.C.";
    }
}
