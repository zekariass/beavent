package com.beavent.backend.comms.web;

import com.beavent.backend.comms.NotificationSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in person's choices about reminders and quiet hours. */
@RestController
class NotificationSettingsController {

    private static final String TIME = "^([01][0-9]|2[0-3]):[0-5][0-9]$";

    record Input(
            @NotNull Boolean remindersPush,
            @NotNull Boolean remindersSms,
            @NotNull Boolean remindersEmail,
            @NotNull Boolean quietHours,
            @NotNull @Pattern(regexp = TIME) String quietFrom,
            @NotNull @Pattern(regexp = TIME) String quietTo) {}

    private final NotificationSettings settings;

    NotificationSettingsController(NotificationSettings settings) {
        this.settings = settings;
    }

    @GetMapping("/v1/me/notification-settings")
    NotificationSettings.Settings get(@AuthenticationPrincipal Jwt jwt) {
        return settings.get(UUID.fromString(jwt.getSubject()));
    }

    @PutMapping("/v1/me/notification-settings")
    NotificationSettings.Settings save(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Input body) {
        return settings.save(
                UUID.fromString(jwt.getSubject()),
                new NotificationSettings.Settings(
                        body.remindersPush(),
                        body.remindersSms(),
                        body.remindersEmail(),
                        body.quietHours(),
                        body.quietFrom(),
                        body.quietTo()));
    }
}
