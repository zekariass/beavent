package com.beavent.backend.scheduling.web;

import com.beavent.backend.scheduling.internal.BookingApi;
import com.beavent.backend.tenancy.OrgAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A business answers booking requests (request-to-book). Needs {@code appointments.manage}. */
@RestController
@RequestMapping("/v1/orgs/{orgId}/appointments/{appointmentId}")
class RequestDecisionController {

    static final String MANAGE = "appointments.manage";

    record DecisionBody(@Size(max = 500) String reason) {}

    record StatusBody(String status) {}

    private final BookingApi bookings;

    RequestDecisionController(BookingApi bookings) {
        this.bookings = bookings;
    }

    @PostMapping("/accept")
    StatusBody accept(@PathVariable UUID orgId, @PathVariable UUID appointmentId) {
        OrgAccess access = OrgAccess.current().require(MANAGE);
        return new StatusBody(bookings.decide(orgId, access.userId(), appointmentId, true, null));
    }

    @PostMapping("/decline")
    StatusBody decline(
            @PathVariable UUID orgId,
            @PathVariable UUID appointmentId,
            @Valid @RequestBody(required = false) DecisionBody body) {
        OrgAccess access = OrgAccess.current().require(MANAGE);
        return new StatusBody(
                bookings.decide(orgId, access.userId(), appointmentId, false, body == null ? null : body.reason()));
    }
}
