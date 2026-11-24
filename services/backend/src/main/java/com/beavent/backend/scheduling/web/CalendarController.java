package com.beavent.backend.scheduling.web;

import com.beavent.backend.scheduling.internal.Calendar;
import com.beavent.backend.tenancy.OrgAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The business's calendar and the front desk's status changes (plan step 2.5). */
@RestController
@RequestMapping("/v1/orgs/{orgId}")
class CalendarController {

    record StatusInput(
            @NotNull @Pattern(regexp = "ARRIVED|IN_PROGRESS|COMPLETED|NO_SHOW")
            String status) {}

    record StatusBody(String status) {}

    private final Calendar calendar;

    CalendarController(Calendar calendar) {
        this.calendar = calendar;
    }

    @GetMapping("/calendar")
    Calendar.Body read(
            @PathVariable UUID orgId,
            @RequestParam UUID locationId,
            @RequestParam LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return calendar.read(OrgAccess.current(), locationId, from, to == null ? from : to);
    }

    @PostMapping("/appointments/{appointmentId}/status")
    StatusBody status(
            @PathVariable UUID orgId, @PathVariable UUID appointmentId, @Valid @RequestBody StatusInput body) {
        return new StatusBody(calendar.changeStatus(OrgAccess.current(), appointmentId, body.status(), Instant.now()));
    }
}
