package com.beavent.backend.comms.internal;

import com.beavent.backend.platform.EthiopianCalendar;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Message texts for booking notifications, in Amharic and English (Afaan Oromo readers get English until translated).
 * Short enough for one or two SMS. Times are in the business's zone, in both conventions with the reader's first
 * (DD7: Ethiopian time first in Amharic). Native-speaker review is pending before launch (doc 06).
 */
final class Texts {

    private Texts() {}

    record Message(String subject, String text) {}

    static String language(String preferred) {
        return "am".equals(preferred) ? "am" : "en";
    }

    static Message render(String code, String preferred, Map<String, Object> data) {
        String lang = language(preferred);
        String business = String.valueOf(data.get("business"));
        String ref = String.valueOf(data.get("reference"));
        ZonedDateTime start = Instant.parse(String.valueOf(data.get("startsAt")))
                .atZone(ZoneId.of(String.valueOf(data.get("timezone"))));
        String place = data.get("landmark") == null ? "" : String.valueOf(data.get("landmark"));
        String date = date(start, lang);
        String when = time(start.toLocalTime(), lang);
        boolean am = lang.equals("am");
        String text = switch (code) {
            case Notifier.CONFIRMED ->
                am
                        ? business + "፦ ቀጠሮዎ " + ref + " ተረጋግጧል። " + date + "፣ " + when + "።" + suffix(place, am)
                        : business + ": your booking " + ref + " is confirmed for " + date + ", " + when + "."
                                + suffix(place, am);
            case Notifier.REQUESTED ->
                am
                        ? business + " የቀጠሮ ጥያቄዎን " + ref + " (" + date + "፣ " + when + ") ተቀብሏል። በቅርቡ ያረጋግጣሉ።"
                        : business + " received your booking request " + ref + " for " + date + ", " + when
                                + ". They will confirm it soon.";
            case Notifier.DECLINED ->
                am
                        ? business + " የቀጠሮ ጥያቄዎን " + ref + " (" + date + "፣ " + when
                                + ") መቀበል አልቻለም። እባክዎ ሌላ ሰዓት ይምረጡ።"
                        : business + " could not accept your booking request " + ref + " for " + date + ", " + when
                                + ". Please choose another time.";
            case Notifier.EXPIRED ->
                am
                        ? business + " ለቀጠሮ ጥያቄዎ " + ref + " (" + date + "፣ " + when
                                + ") በጊዜው ምላሽ አልሰጠም። እባክዎ ሌላ ሰዓት ይምረጡ።"
                        : business + " did not answer your booking request " + ref + " for " + date + ", " + when
                                + " in time. Please choose another time.";
            case Notifier.CANCELLED ->
                am
                        ? business + " ላይ የነበረዎት ቀጠሮ " + ref + " (" + date + "፣ " + when + ") ተሰርዟል።"
                        : "Your booking " + ref + " at " + business + " on " + date + ", " + when + " is cancelled.";
            case Notifier.RESCHEDULED ->
                am
                        ? business + "፦ ቀጠሮዎ " + ref + " ወደ " + date + "፣ " + when + " ተዛውሯል።"
                        : business + ": your booking " + ref + " is now on " + date + ", " + when + ".";
            case Notifier.REMINDER_24H ->
                am
                        ? "ማስታወሻ፦ ነገ " + date + "፣ " + when + " በ" + business + "። ቀጠሮ " + ref + "።" + suffix(place, am)
                        : "Reminder: " + business + " tomorrow, " + date + " at " + when + ". Booking " + ref + "."
                                + suffix(place, am);
            case Notifier.REMINDER_2H ->
                am
                        ? "ማስታወሻ፦ ዛሬ " + when + " በ" + business + "። ቀጠሮ " + ref + "።"
                        : "Reminder: " + business + " today at " + when + ". Booking " + ref + ".";
            default -> throw new IllegalArgumentException("No text for " + code);
        };
        String subject = am ? business + " · ቀጠሮ " + ref : business + " · booking " + ref;
        return new Message(subject, text);
    }

    private static String suffix(String place, boolean am) {
        return place.isBlank() ? "" : " " + place + (am ? "።" : ".");
    }

    /** "ኅዳር 18 ቀን 2019 ዓ.ም." (Ethiopian calendar, Amharic) or "Fri 27 Nov". */
    static String date(ZonedDateTime start, String lang) {
        return lang.equals("am")
                ? EthiopianCalendar.format(EthiopianCalendar.fromGregorian(start.toLocalDate()), "am")
                : start.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH));
    }

    /** "ጠዋት 4:00 (10:00 AM)" in Amharic, "10:00 AM (4:00 in the morning)" otherwise. */
    static String time(LocalTime time, String lang) {
        String ethiopian = EthiopianCalendar.formatTime(EthiopianCalendar.toEthiopianTime(time), lang);
        int h12 = time.getHour() % 12 == 0 ? 12 : time.getHour() % 12;
        String clock = h12 + ":" + String.format("%02d", time.getMinute()) + (time.getHour() < 12 ? " AM" : " PM");
        return lang.equals("am") ? ethiopian + " (" + clock + ")" : clock + " (" + ethiopian + ")";
    }
}
