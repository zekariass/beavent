package com.beavent.backend.comms.internal;

import com.beavent.backend.comms.NotificationSettings;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

/** Quiet hours (plan 8.4): when a person does not want reminders, in the local time of the place the message is about. */
public final class QuietHours {

    private QuietHours() {}

    /**
     * When quiet hours end if {@code now} is inside them, otherwise {@code null}. Quiet hours that run past midnight
     * (21:00 to 07:00) end the next morning; the end itself is outside them.
     */
    public static Instant end(NotificationSettings.Settings prefs, Map<String, Object> data, Instant now) {
        ZoneId zone = ZoneId.of(String.valueOf(data.getOrDefault("timezone", "Africa/Addis_Ababa")));
        var local = now.atZone(zone);
        LocalTime t = local.toLocalTime();
        LocalTime from = LocalTime.parse(prefs.quietFrom());
        LocalTime to = LocalTime.parse(prefs.quietTo());
        boolean inside = from.isBefore(to) ? !t.isBefore(from) && t.isBefore(to) : !t.isBefore(from) || t.isBefore(to);
        if (!inside) {
            return null;
        }
        var end = local.toLocalDate().atTime(to).atZone(zone);
        return (end.isAfter(local) ? end : end.plusDays(1)).toInstant();
    }
}
