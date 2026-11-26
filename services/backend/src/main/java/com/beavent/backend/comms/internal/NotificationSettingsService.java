package com.beavent.backend.comms.internal;

import com.beavent.backend.comms.NotificationSettings;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Component;

@Component
class NotificationSettingsService implements NotificationSettings {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    static final Settings DEFAULTS = new Settings(true, true, true, false, "21:00", "07:00");

    private final DSLContext system;

    NotificationSettingsService(@SystemDb DSLContext system) {
        this.system = system;
    }

    @Override
    public Settings get(UUID userId) {
        Record r = system.resultQuery(
                        "SELECT reminders_push, reminders_sms, reminders_email, quiet_hours_enabled, quiet_from, quiet_to"
                                + " FROM comms.notification_settings WHERE user_id = ?",
                        userId)
                .fetchOne();
        if (r == null) {
            return DEFAULTS;
        }
        return new Settings(
                r.get("reminders_push", Boolean.class),
                r.get("reminders_sms", Boolean.class),
                r.get("reminders_email", Boolean.class),
                r.get("quiet_hours_enabled", Boolean.class),
                r.get("quiet_from", LocalTime.class).format(HH_MM),
                r.get("quiet_to", LocalTime.class).format(HH_MM));
    }

    @Override
    public Settings save(UUID userId, Settings s) {
        LocalTime from = parse(s.quietFrom());
        LocalTime to = parse(s.quietTo());
        if (from.equals(to)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Quiet hours need different start and end times.");
        }
        system.execute("""
                INSERT INTO comms.notification_settings
                  (user_id, reminders_push, reminders_sms, reminders_email, quiet_hours_enabled, quiet_from, quiet_to)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                  reminders_push = excluded.reminders_push, reminders_sms = excluded.reminders_sms, reminders_email = excluded.reminders_email,
                  quiet_hours_enabled = excluded.quiet_hours_enabled, quiet_from = excluded.quiet_from,
                  quiet_to = excluded.quiet_to, updated_at = now()
                """, userId, s.remindersPush(), s.remindersSms(), s.remindersEmail(), s.quietHours(), from, to);
        return get(userId);
    }

    private static LocalTime parse(String text) {
        try {
            return LocalTime.parse(text, HH_MM);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Use times like 21:00.");
        }
    }
}
