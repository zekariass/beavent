package com.beavent.backend.comms.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.beavent.backend.platform.EthiopianCalendar;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Booking message texts: every code in both languages, dates and times in both conventions. */
class TextsTest {

    static final Map<String, Object> DATA = Map.of(
            "business", "Abeba Beauty",
            "reference", "K7M2QX",
            "startsAt", "2026-11-27T07:00:00Z",
            "timezone", "Africa/Addis_Ababa",
            "landmark", "Behind Edna Mall");

    @Test
    void everyCodeRendersInAmharicAndEnglish() {
        for (String code : new String[] {
            Notifier.CONFIRMED, Notifier.REQUESTED, Notifier.DECLINED, Notifier.EXPIRED,
            Notifier.CANCELLED, Notifier.RESCHEDULED, Notifier.REMINDER_24H, Notifier.REMINDER_2H
        }) {
            for (String language : new String[] {"am", "en", "om", null}) {
                Texts.Message m = Texts.render(code, language, DATA);
                assertThat(m.text())
                        .as(code + " " + language)
                        .contains("K7M2QX")
                        .contains("Abeba Beauty");
                assertThat(m.text().length()).as("fits two SMS").isLessThan(300);
            }
        }
    }

    @Test
    void timesComeInBothConventionsTheReadersFirst() {
        // 10:00 in Addis Ababa (07:00 UTC) on Friday 27 November 2026 = Hidar 18, 2019 E.C.
        assertThat(Texts.render(Notifier.CONFIRMED, "en", DATA).text())
                .isEqualTo("Abeba Beauty: your booking K7M2QX is confirmed for Fri 27 Nov, 10:00 AM (4:00 in the"
                        + " morning). Behind Edna Mall.");
        assertThat(Texts.render(Notifier.CONFIRMED, "am", DATA).text())
                .isEqualTo("Abeba Beauty፦ ቀጠሮዎ K7M2QX ተረጋግጧል። ኅዳር 18 ቀን 2019 ዓ.ም.፣ ጠዋት 4:00 (10:00 AM)።"
                        + " Behind Edna Mall።");
    }

    @Test
    void ethiopianTimeMatchesTheWebAndAppFormatter() {
        // Same strings as formatEthiopianTime in packages/calendar.
        assertThat(EthiopianCalendar.formatTime(EthiopianCalendar.toEthiopianTime(LocalTime.of(9, 0)), "am"))
                .isEqualTo("ጠዋት 3:00");
        assertThat(EthiopianCalendar.formatTime(EthiopianCalendar.toEthiopianTime(LocalTime.of(15, 30)), "en"))
                .isEqualTo("9:30 in the afternoon");
        assertThat(EthiopianCalendar.formatTime(EthiopianCalendar.toEthiopianTime(LocalTime.of(18, 0)), "am"))
                .isEqualTo("ማታ 12:00");
        assertThat(Dispatcher.mask("+251911223344")).isEqualTo("+2519•••44");
        assertThat(Dispatcher.mask("hana@example.com")).isEqualTo("h•••@example.com");
    }
}
