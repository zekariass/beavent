package com.beavent.backend.scheduling.internal;

import static com.beavent.db.scheduling.Tables.APPOINTMENTS;
import static com.beavent.db.scheduling.Tables.APPOINTMENT_ITEMS;
import static com.beavent.db.scheduling.Tables.HOLDS;
import static com.beavent.db.scheduling.Tables.SCHEDULE_RESERVATIONS;

import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.SystemDb;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Time-based booking rules, run across all businesses with the system role. Each sweep is idempotent and claims rows
 * with {@code SKIP LOCKED}, so several API instances can run it at once.
 *
 * <ul>
 *   <li>Holds that ran out release their reservations (availability already ignores them; this keeps the ledger tidy
 *       and the database in agreement for other writers).
 *   <li>Booking requests the business did not answer expire: after {@link #REQUEST_ANSWER_TIME}, or when the visit is
 *       less than {@link #REQUEST_LAST_CALL} away. The time is freed and the customer told.
 *   <li>The waitlist: offers that ran out move on, and waiting entries are offered times that became free
 *       ({@link Waitlist#sweep}).
 * </ul>
 */
@Component
class BookingSweeps {

    static final Duration REQUEST_ANSWER_TIME = Duration.ofHours(24);
    static final Duration REQUEST_LAST_CALL = Duration.ofHours(2);
    private static final int BATCH = 100;
    private static final Logger log = LoggerFactory.getLogger(BookingSweeps.class);

    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final Outbox outbox;
    private final Waitlist waitlist;

    BookingSweeps(
            @SystemDb DSLContext system, @SystemDb TransactionTemplate transactions, Outbox outbox, Waitlist waitlist) {
        this.system = system;
        this.transactions = transactions;
        this.outbox = outbox;
        this.waitlist = waitlist;
    }

    @Scheduled(fixedDelayString = "${beavent.scheduling.sweep-interval-ms:30000}")
    void run() {
        Instant now = Instant.now();
        try {
            expireHolds(now);
            expireRequests(now);
            waitlist.sweep(now);
        } catch (RuntimeException e) {
            log.warn("Booking sweep failed; it runs again shortly", e);
        }
    }

    int expireHolds(Instant now) {
        return transactions.execute(status -> {
            var due = system.select(HOLDS.ID)
                    .from(HOLDS)
                    .where(HOLDS.STATUS.eq("ACTIVE"), HOLDS.EXPIRES_AT.le(now.atOffset(ZoneOffset.UTC)))
                    .limit(BATCH)
                    .forUpdate()
                    .skipLocked()
                    .fetch(HOLDS.ID);
            if (due.isEmpty()) {
                return 0;
            }
            system.update(SCHEDULE_RESERVATIONS)
                    .set(SCHEDULE_RESERVATIONS.STATUS, "RELEASED")
                    .where(SCHEDULE_RESERVATIONS.HOLD_ID.in(due), SCHEDULE_RESERVATIONS.STATUS.eq("HELD"))
                    .execute();
            return system.update(HOLDS)
                    .set(HOLDS.STATUS, "EXPIRED")
                    .where(HOLDS.ID.in(due))
                    .execute();
        });
    }

    int expireRequests(Instant now) {
        return transactions.execute(status -> {
            var due = system.select(APPOINTMENTS.ID, APPOINTMENTS.ORG_ID)
                    .from(APPOINTMENTS)
                    .where(
                            APPOINTMENTS.STATUS.eq("REQUESTED"),
                            APPOINTMENTS
                                    .CREATED_AT
                                    .le(now.minus(REQUEST_ANSWER_TIME).atOffset(ZoneOffset.UTC))
                                    .or(APPOINTMENTS.STARTS_AT.le(
                                            now.plus(REQUEST_LAST_CALL).atOffset(ZoneOffset.UTC))))
                    .limit(BATCH)
                    .forUpdate()
                    .skipLocked()
                    .fetch();
            for (var a : due) {
                Bookings.releaseReservations(system, a.value1());
                system.update(APPOINTMENT_ITEMS)
                        .set(APPOINTMENT_ITEMS.STATUS, "CANCELLED")
                        .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.eq(a.value1()))
                        .execute();
                system.update(APPOINTMENTS)
                        .set(APPOINTMENTS.STATUS, "EXPIRED")
                        .set(APPOINTMENTS.VERSION, APPOINTMENTS.VERSION.plus(1))
                        .where(APPOINTMENTS.ID.eq(a.value1()))
                        .execute();
                Bookings.history(system, a.value2(), a.value1(), "REQUESTED", "EXPIRED", null, "Not answered in time");
                outbox.publish(new DomainEvent(
                        "scheduling.appointment",
                        a.value1(),
                        "appointment.expired",
                        1,
                        a.value2(),
                        Map.of("appointmentId", a.value1())));
            }
            return due.size();
        });
    }
}
