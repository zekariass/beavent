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
        if (code.equals(Notifier.PAYOUT_CHANGED)) {
            return payoutChanged(lang, data);
        }
        if (code.equals(Notifier.PAYOUT_PAID) || code.equals(Notifier.PAYOUT_FAILED)) {
            return payoutOutcome(code.equals(Notifier.PAYOUT_PAID), lang, data);
        }
        String business = String.valueOf(data.get("business"));
        String ref = String.valueOf(data.get("reference"));
        ZonedDateTime start = Instant.parse(String.valueOf(data.get("startsAt")))
                .atZone(ZoneId.of(String.valueOf(data.get("timezone"))));
        String place = data.get("landmark") == null ? "" : String.valueOf(data.get("landmark"));
        String date = date(start, lang);
        String when = time(start.toLocalTime(), lang);
        boolean am = lang.equals("am");
        if (code.equals(Notifier.WAITLIST_OFFER)) {
            ZonedDateTime until =
                    Instant.parse(String.valueOf(data.get("expiresAt"))).atZone(start.getZone());
            String link = data.get("web") + "/" + lang + "/account/waitlist/" + data.get("entryId");
            String hold = time(until.toLocalTime(), lang);
            String offer = am
                    ? business + "፦ ቦታ ተገኝቷል፤ " + date + "፣ " + when + "። እስከ " + hold + " ድረስ ይያዙት፦ " + link
                    : business + ": a time opened up on " + date + ", " + when + ". Book it by " + hold + ": " + link;
            return new Message(am ? business + " · የተጠባባቂ ዝርዝር" : business + " · waitlist", offer);
        }
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
            case Notifier.REFUND_SENT -> {
                String amount = birr(Long.parseLong(String.valueOf(data.get("refundMinor"))), am);
                yield am
                        ? business + "፦ " + amount + " ተመላሽ ተልኳል። ቀጠሮ " + ref + "።"
                        : business + ": " + amount + " was refunded to you for booking " + ref + ".";
            }
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

    /** The people who manage payouts hear that the account changed and when payouts to it start (plan 3.4). */
    private static Message payoutChanged(String lang, Map<String, Object> data) {
        boolean am = lang.equals("am");
        String business = String.valueOf(data.get("business"));
        String account = data.get("institution") + " " + data.get("last4");
        ZonedDateTime from = Instant.parse(String.valueOf(data.get("allowedAfter")))
                .atZone(ZoneId.of(String.valueOf(data.get("timezone"))));
        String when = date(from, lang) + "፣ " + time(from.toLocalTime(), lang);
        return am
                ? new Message(
                        business + " · የክፍያ መቀበያ መለያ",
                        business + "፦ የክፍያ መቀበያ መለያው ወደ " + account + " ተቀይሯል። ክፍያ ወደዚህ መለያ መላክ የሚጀምረው ከ" + when
                                + " በኋላ ነው። ይህን ያደረጉት እርስዎ ካልሆኑ ወዲያውኑ ያሳውቁን።")
                : new Message(
                        business + " · payout account",
                        business + ": the payout account was changed to " + account + ". Payouts to it start after "
                                + date(from, lang) + ", " + time(from.toLocalTime(), lang)
                                + ". If this wasn't you, tell us right away.");
    }

    /** A payout was paid, or did not go through (plan 3.5). */
    private static Message payoutOutcome(boolean paid, String lang, Map<String, Object> data) {
        boolean am = lang.equals("am");
        String business = String.valueOf(data.get("business"));
        String account = data.get("institution") + " " + data.get("last4");
        String amount = birr(Long.parseLong(String.valueOf(data.get("amountMinor"))), am);
        String note = String.valueOf(data.get("note"));
        if (paid) {
            return am
                    ? new Message(business + " · ክፍያ ተልኳል", business + "፦ " + amount + " ወደ " + account + " ተልኳል።")
                    : new Message(
                            business + " · payout sent", business + ": " + amount + " was sent to " + account + ".");
        }
        String why = note.isBlank() ? "" : (am ? " ምክንያት፦ " : " Reason: ") + note + (am ? "።" : ".");
        return am
                ? new Message(
                        business + " · ክፍያ አልተላከም",
                        business + "፦ ወደ " + account + " የተዘጋጀው የ" + amount
                                + " ክፍያ አልተላከም። ገንዘቡ በሒሳብዎ ውስጥ ነው፤ እንደገና ይሞከራል።" + why)
                : new Message(
                        business + " · payout not sent",
                        business + ": the " + amount + " payout to " + account
                                + " did not go through. The money is still yours and will be tried again." + why);
    }

    /** "Br 1,250" or "Br 88.50": whole birr without decimals (docs/design: never .00). */
    static String birr(long minor, boolean am) {
        String number = minor % 100 == 0
                ? String.format(Locale.ENGLISH, "%,d", minor / 100)
                : String.format(Locale.ENGLISH, "%,d.%02d", minor / 100, minor % 100);
        return (am ? "ብር " : "Br ") + number;
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
