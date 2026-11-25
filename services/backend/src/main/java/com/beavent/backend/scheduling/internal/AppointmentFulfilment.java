package com.beavent.backend.scheduling.internal;

import static com.beavent.db.commerce.Tables.ORDERS;
import static com.beavent.db.scheduling.Tables.APPOINTMENTS;
import static com.beavent.db.scheduling.Tables.APPOINTMENT_ITEMS;
import static com.beavent.db.scheduling.Tables.APPOINTMENT_STATUS_HISTORY;
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

    @Override
    public Delivery delivery(UUID orgId, UUID orderId, Instant now) {
        var a = system.select(APPOINTMENTS.STATUS, APPOINTMENTS.ENDS_AT)
                .from(APPOINTMENTS)
                .where(APPOINTMENTS.ORDER_ID.eq(orderId))
                .fetchOne();
        if (a == null) return null;
        Instant ends = a.value2().toInstant();
        DeliveryState state = switch (a.value1()) {
            case "COMPLETED" -> DeliveryState.DELIVERED;
            case "CONFIRMED", "ARRIVED", "IN_PROGRESS" ->
                // Few salons tick "completed": a confirmed visit whose time has passed counts as done.
                ends.isAfter(now) ? DeliveryState.PENDING : DeliveryState.DELIVERED;
            case "CANCELLED", "NO_SHOW", "EXPIRED", "DECLINED" -> DeliveryState.VOID;
            default -> DeliveryState.PENDING;
        };
        return new Delivery(state, ends);
    }

    @Override
    public Settlement settlement(UUID orgId, UUID orderId, long paidMinor, Instant now) {
        var a = system.selectFrom(APPOINTMENTS)
                .where(APPOINTMENTS.ORDER_ID.eq(orderId))
                .fetchOne();
        if (a == null) return null;
        long total = system.select(ORDERS.TOTAL_MINOR)
                .from(ORDERS)
                .where(ORDERS.ID.eq(orderId))
                .fetchSingle(ORDERS.TOTAL_MINOR);
        Bookings.Policy policy = json.readValue(a.getPolicySnapshot().data(), Bookings.Policy.class);
        switch (a.getStatus()) {
            case "CANCELLED" -> {
                String by = a.getCancelledBy() == null ? "SYSTEM" : a.getCancelledBy();
                long keep = 0;
                if (by.equals("CUSTOMER")) {
                    // Free while the business had not accepted a request, and until the frozen free-cancellation time.
                    boolean wasRequest = system.fetchExists(
                            APPOINTMENT_STATUS_HISTORY,
                            APPOINTMENT_STATUS_HISTORY
                                    .APPOINTMENT_ID
                                    .eq(a.getId())
                                    .and(APPOINTMENT_STATUS_HISTORY.FROM_STATUS.eq("REQUESTED"))
                                    .and(APPOINTMENT_STATUS_HISTORY.TO_STATUS.eq("CANCELLED")));
                    boolean free = wasRequest
                            || policy.freeCancellationUntil() == null
                            || !a.getCancelledAt().toInstant().isAfter(policy.freeCancellationUntil());
                    if (!free) keep = Math.min(paidMinor, percent(total, policy.lateCancelFeePercent()));
                }
                return new Settlement(keep, "APPOINTMENT_CANCELLED", by);
            }
            case "NO_SHOW" -> {
                return new Settlement(
                        Math.min(paidMinor, percent(total, policy.noShowFeePercent())), "ORDER_ADJUSTMENT", "SYSTEM");
            }
            case "DECLINED" -> {
                return new Settlement(0, "APPOINTMENT_CANCELLED", "ORGANIZATION");
            }
            default -> {
                return null;
            }
        }
    }

    @Override
    public Description describe(UUID orgId, UUID orderId) {
        var a = system.selectFrom(APPOINTMENTS)
                .where(APPOINTMENTS.ORDER_ID.eq(orderId))
                .fetchOne();
        if (a == null) return null;
        java.util.List<String> items = system.select(APPOINTMENT_ITEMS.SERVICE_NAME)
                .from(APPOINTMENT_ITEMS)
                .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.eq(a.getId()))
                .orderBy(APPOINTMENT_ITEMS.SEQ)
                .fetch(APPOINTMENT_ITEMS.SERVICE_NAME);
        String location = system.select(com.beavent.db.tenant.Tables.LOCATIONS.NAME)
                .from(com.beavent.db.tenant.Tables.LOCATIONS)
                .where(com.beavent.db.tenant.Tables.LOCATIONS.ID.eq(a.getLocationId()))
                .fetchOne(com.beavent.db.tenant.Tables.LOCATIONS.NAME);
        return new Description(a.getReference(), a.getStartsAt().toInstant(), a.getTimezone(), items, location);
    }

    /** {@code percent} % of {@code total}, rounded half up. */
    static long percent(long total, java.math.BigDecimal percent) {
        if (percent == null) return 0;
        return java.math.BigDecimal.valueOf(total)
                .multiply(percent)
                .divide(java.math.BigDecimal.valueOf(100), 0, java.math.RoundingMode.HALF_UP)
                .longValueExact();
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
