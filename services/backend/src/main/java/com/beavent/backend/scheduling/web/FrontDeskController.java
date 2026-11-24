package com.beavent.backend.scheduling.web;

import com.beavent.backend.scheduling.Availability;
import com.beavent.backend.scheduling.internal.BookingApi;
import com.beavent.backend.scheduling.internal.Calendar;
import com.beavent.backend.tenancy.OrgAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The front desk: walk-ins and phone bookings, moving appointments, blocking time (plan step 2.5). */
@RestController
@RequestMapping("/v1/orgs/{orgId}")
class FrontDeskController {

    record ItemInput(
            @NotNull UUID variantId,
            @NotNull UUID staffId,
            @Size(max = 10) List<UUID> addonIds) {}

    record CustomerInput(
            UUID customerId,
            @Size(max = 120) String name,
            @Pattern(regexp = "^\\+[1-9][0-9]{6,14}$") String phone) {}

    record BookInput(
            @NotNull UUID locationId,
            @NotNull Instant start,
            @NotEmpty @Size(max = 6) List<@Valid @NotNull ItemInput> items,
            @NotNull @Valid CustomerInput customer,
            @Size(max = 500) String note,
            @Pattern(regexp = "WALK_IN|PHONE") String source,
            Boolean bookAnyway,
            Boolean notifyCustomer) {}

    record MoveInput(@NotNull Instant start, UUID staffId, Boolean bookAnyway, Boolean notifyCustomer) {}

    record BlockInput(
            @NotNull UUID locationId,
            @NotNull UUID staffId,
            @NotNull Instant start,
            @NotNull Instant end,

            @NotNull @Pattern(regexp = "BREAK|MEETING|TRAINING|PERSONAL|MAINTENANCE|OTHER")
            String reason,

            @Size(max = 120) String title) {}

    record Created(UUID id) {}

    private final BookingApi bookings;
    private final Calendar calendar;

    FrontDeskController(BookingApi bookings, Calendar calendar) {
        this.bookings = bookings;
        this.calendar = calendar;
    }

    @PostMapping("/appointments")
    ResponseEntity<BookingApi.DeskBookedBody> book(@PathVariable UUID orgId, @Valid @RequestBody BookInput body) {
        OrgAccess access = OrgAccess.current();
        if (!access.canAccessLocation(body.locationId())) {
            throw new com.beavent.backend.platform.ApiException(
                    com.beavent.backend.platform.ProblemCode.NOT_FOUND, "There is no such location.");
        }
        calendar.requireDesk(
                access, body.items().stream().map(ItemInput::staffId).toList());
        BookingApi.DeskBookedBody booked = bookings.deskBook(
                orgId,
                access.userId(),
                body.locationId(),
                body.items().stream()
                        .map(i -> new Availability.Item(i.variantId(), i.addonIds(), i.staffId()))
                        .toList(),
                body.start(),
                body.customer().customerId(),
                body.customer().name(),
                body.customer().phone(),
                body.note(),
                body.source() == null ? "WALK_IN" : body.source(),
                Boolean.TRUE.equals(body.bookAnyway()),
                !Boolean.FALSE.equals(body.notifyCustomer()),
                Instant.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(booked);
    }

    @PostMapping("/appointments/{appointmentId}/move")
    ResponseEntity<Void> move(
            @PathVariable UUID orgId, @PathVariable UUID appointmentId, @Valid @RequestBody MoveInput body) {
        OrgAccess access = OrgAccess.current();
        List<UUID> staff = new java.util.ArrayList<>(calendar.staffOf(appointmentId));
        if (body.staffId() != null) {
            staff.add(body.staffId());
        }
        calendar.requireDesk(access, staff);
        bookings.deskMove(
                orgId,
                access.userId(),
                appointmentId,
                body.start(),
                body.staffId(),
                Boolean.TRUE.equals(body.bookAnyway()),
                !Boolean.FALSE.equals(body.notifyCustomer()),
                Instant.now());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/blocks")
    ResponseEntity<Created> block(@PathVariable UUID orgId, @Valid @RequestBody BlockInput body) {
        OrgAccess access = OrgAccess.current();
        calendar.requireDesk(access, List.of(body.staffId()));
        UUID id = bookings.block(
                orgId,
                access.userId(),
                body.locationId(),
                body.staffId(),
                body.start(),
                body.end(),
                body.reason(),
                body.title());
        return ResponseEntity.status(HttpStatus.CREATED).body(new Created(id));
    }

    @DeleteMapping("/blocks/{blockId}")
    ResponseEntity<Void> unblock(@PathVariable UUID orgId, @PathVariable UUID blockId) {
        calendar.requireDesk(OrgAccess.current(), List.of(calendar.staffOfBlock(blockId)));
        bookings.unblock(blockId);
        return ResponseEntity.noContent().build();
    }
}
