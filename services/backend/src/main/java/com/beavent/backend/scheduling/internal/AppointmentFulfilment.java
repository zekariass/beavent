package com.beavent.backend.scheduling.internal;

import static com.beavent.db.scheduling.Tables.APPOINTMENTS;
import static com.beavent.db.scheduling.Tables.APPOINTMENT_ITEMS;
import static com.beavent.db.scheduling.Tables.SCHEDULE_RESERVATIONS;

import com.beavent.backend.payments.PaymentFulfilment;
import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.SystemDb;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a captured payment means for an appointment (plan 3.3). The deposit confirms a booking that waited for it: the
 * time becomes confirmed, and the customer's confirmation is sent (or the business is asked, for request-to-book). A
 * payment that arrives after the booking expired confirms it again if the time is still free, and goes back to the
 * customer if not (docs/04 §5); a payment for a cancelled booking goes back too.
 */
@Component
class AppointmentFulfilment implements PaymentFulfilment {

    private static final Logger log = LoggerFactory.getLogger(AppointmentFulfilment.class);
    /** PostgreSQL's exclusion_violation: someone else's booking holds the time. */
    private static final String EXCLUSION_VIOLATION = "23P01";

    private final DSLContext system;
    private final Outbox outbox;
    private final JsonMapper json;

    AppointmentFulfilment(@SystemDb DSLContext system, Outbox outbox, JsonMapper json) {
        this.system = system;
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    public Outcome onPaid(UUID orgId, UUID orderId, long paidMinor, boolean late, Instant now) {
        var a = system.selectFrom(APPOINTMENTS)
                .where(APPOINTMENTS.ORDER_ID.eq(orderId))
                .forUpdate()
                .fetchOne();
        if (a == null) return Outcome.NOT_MINE;
        UUID id = a.getId();
        switch (a.getStatus()) {
            case "PENDING_PAYMENT" -> {
                confirm(a, "PENDING_PAYMENT", "Deposit paid", now);
                return Outcome.CONFIRMED;
            }
            case "EXPIRED" -> {
                // Paid after the time ran out: the booking is back if its time is still free and still ahead.
                if (a.getStartsAt().toInstant().isAfter(now) && reserveAgain(id)) {
                    system.update(APPOINTMENT_ITEMS)
                            .set(APPOINTMENT_ITEMS.STATUS, "BOOKED")
                            .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.eq(id), APPOINTMENT_ITEMS.STATUS.eq("CANCELLED"))
                            .execute();
                    confirm(a, "EXPIRED", "Paid after the time ran out; the time was still free", now);
                    return Outcome.CONFIRMED;
                }
                log.info("Late payment for appointment {}: the time is gone, refunding", id);
                return Outcome.REFUND;
            }
            case "CANCELLED", "DECLINED", "NO_SHOW" -> {
                return Outcome.REFUND;
            }
            default -> {
                return Outcome.CONFIRMED; // already confirmed (a repeated call)
            }
        }
    }

    /** The booking is paid for: it is confirmed and the customer (or the business, for a request) is told. */
    private void confirm(
            com.beavent.db.scheduling.tables.records.AppointmentsRecord a, String from, String reason, Instant now) {
        Bookings.Policy policy = json.readValue(a.getPolicySnapshot().data(), Bookings.Policy.class);
        String to = policy.requiresApproval() ? "REQUESTED" : "CONFIRMED";
        system.update(APPOINTMENTS)
                .set(APPOINTMENTS.STATUS, to)
                .set(APPOINTMENTS.VERSION, APPOINTMENTS.VERSION.plus(1))
                .where(APPOINTMENTS.ID.eq(a.getId()))
                .execute();
        Bookings.history(system, a.getOrgId(), a.getId(), from, to, null, reason);
        outbox.publish(new DomainEvent(
                "scheduling.appointment",
                a.getId(),
                to.equals("REQUESTED") ? "appointment.requested" : "appointment.booked",
                1,
                a.getOrgId(),
                Map.of(
                        "appointmentId", a.getId(),
                        "reference", a.getReference(),
                        "locationId", a.getLocationId(),
                        "customerId", a.getCustomerId(),
                        "startsAt", a.getStartsAt().toInstant().toString())));
    }

    /**
     * Takes the appointment's time back. The database's exclusion constraint decides whether it is still free; a
     * conflict is answered "no" and the work so far in the transaction is kept (a savepoint).
     */
    private boolean reserveAgain(UUID appointmentId) {
        return system.connectionResult(connection -> {
            java.sql.Savepoint savepoint = connection.setSavepoint();
            try {
                DSL.using(connection, org.jooq.SQLDialect.POSTGRES)
                        .update(SCHEDULE_RESERVATIONS)
                        .set(SCHEDULE_RESERVATIONS.STATUS, "CONFIRMED")
                        .where(SCHEDULE_RESERVATIONS.STATUS.eq("RELEASED"), Bookings.ofAppointment(appointmentId))
                        .execute();
                return true;
            } catch (DataAccessException e) {
                if (!EXCLUSION_VIOLATION.equals(e.sqlState())) throw e;
                connection.rollback(savepoint);
                return false;
            }
        });
    }
}
