package com.beavent.backend.scheduling.web;

import com.beavent.backend.scheduling.internal.Calendar;
import com.beavent.backend.scheduling.internal.CalendarStream;
import com.beavent.backend.tenancy.OrgAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The business's calendar and the front desk's status changes (plan step 2.5). */
@RestController
@RequestMapping("/v1/orgs/{orgId}")
class CalendarController {

    record StatusInput(
            @NotNull @Pattern(regexp = "ARRIVED|IN_PROGRESS|COMPLETED|NO_SHOW")
            String status) {}

    record StatusBody(String status) {}

    private final Calendar calendar;
    private final CalendarStream stream;

    CalendarController(Calendar calendar, CalendarStream stream) {
        this.calendar = calendar;
        this.stream = stream;
    }

    /** Live updates for a location's calendar (server-sent events: `ready`, then `changed` after each change). */
    @GetMapping(value = "/calendar/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable UUID orgId, @RequestParam UUID locationId) {
        calendar.requireView(OrgAccess.current(), locationId);
        return stream.subscribe(locationId);
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
