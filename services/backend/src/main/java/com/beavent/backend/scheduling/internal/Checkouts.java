package com.beavent.backend.scheduling.internal;

import static com.beavent.db.commerce.Tables.ORDERS;
import static com.beavent.db.commerce.Tables.ORDER_LINES;
import static com.beavent.db.commerce.Tables.ORDER_STATUS_HISTORY;
import static com.beavent.db.commerce.Tables.PAYMENT_SCHEDULE;
import static com.beavent.db.crm.Tables.CUSTOMERS;
import static com.beavent.db.scheduling.Tables.APPOINTMENTS;
import static com.beavent.db.scheduling.Tables.APPOINTMENT_ITEMS;

import com.beavent.backend.commerce.CashDrawers;
import com.beavent.backend.commerce.Orders;
import com.beavent.backend.commerce.Receipts;
import com.beavent.backend.payments.Payments;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checking a visit out at the counter (plan 4.1): the bill (what the visit comes to, what was paid online, what is due),
 * tips for the team, cash in the drawer, the visit completed and a receipt with the next gapless number. A visit
 * with no order yet gets one now (channel POS); a visit whose deposit was paid online adds the tips to the order it has and
 * collects the balance. Everything happens in one transaction: a sale without its drawer movement or receipt cannot exist.
 * Other ways to pay are plan 4.2.
 */
@Component
public class Checkouts {

    static final String SELL = "pos.sell";

    /** One thing on the bill. */
    public record Line(String description, long totalMinor) {}

    /** A member of the team who worked on the visit (who a tip can go to). */
    public record Performer(UUID staffId, String name) {}

    /**
     * @param dueMinor what is still to be paid at the counter (before any tip)
     * @param canCheckout false when the visit is not in a state to be paid (waiting for its deposit, cancelled …) or was
     *     checked out already
     * @param drawerOpen cash can only be taken while the location's drawer is open
     */
    public record Bill(
            UUID appointmentId,
            UUID locationId,
            String reference,
            String status,
            String customerName,
            String currency,
            List<Line> lines,
            long totalMinor,
            long paidMinor,
            long dueMinor,
            List<Performer> team,
            boolean drawerOpen,
            boolean canCheckout,
            String receiptNumber) {}

    public record Tip(UUID staffId, long amountMinor) {}

    /**
     * @param tenderedMinor the cash handed over
     * @param changeMinor given back
     */
    public record Done(
            UUID orderId,
            String orderNumber,
            String receiptNumber,
            String currency,
            long totalMinor,
            long paidOnlineMinor,
            long tipMinor,
            long cashMinor,
            long tenderedMinor,
            long changeMinor) {}

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Bookings bookings;
    private final Calendar calendar;
    private final Orders orders;
    private final Payments payments;
    private final CashDrawers drawers;
    private final Receipts receipts;

    Checkouts(
            DSLContext dsl,
            TransactionTemplate transactions,
            Bookings bookings,
            Calendar calendar,
            Orders orders,
            Payments payments,
            CashDrawers drawers,
            Receipts receipts) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.bookings = bookings;
        this.calendar = calendar;
        this.orders = orders;
        this.payments = payments;
        this.drawers = drawers;
        this.receipts = receipts;
    }

    private static final java.util.Set<String> PAYABLE =
            java.util.Set.of("CONFIRMED", "ARRIVED", "IN_PROGRESS", "COMPLETED");

    public Bill bill(OrgAccess access, UUID appointmentId) {
        access.require(SELL);
        return transactions.execute(status -> {
            var a = appointment(access, appointmentId, false);
            return billOf(a);
        });
    }

    private Bill billOf(com.beavent.db.scheduling.tables.records.AppointmentsRecord a) {
        UUID orgId = a.getOrgId();
        Bookings.Items items = bookings.loadItems(a.getId());
        var order = a.getOrderId() == null
                ? null
                : dsl.selectFrom(ORDERS).where(ORDERS.ID.eq(a.getOrderId())).fetchOne();
        List<Line> lines = new ArrayList<>();
        long total;
        long paid = 0;
        long due;
        boolean waitingOnline = false;
        if (order == null) {
            for (Bookings.PlanItem i : items.items()) {
                String name = i.variantName() == null || i.variantName().isBlank()
                        ? i.serviceName()
                        : i.serviceName() + " · " + i.variantName();
                lines.add(new Line(name, i.priceMinor()));
                for (Bookings.PlanAddon addon : i.addons()) lines.add(new Line(addon.name(), addon.priceMinor()));
            }
            total = items.total();
            due = total;
        } else {
            dsl.selectFrom(ORDER_LINES)
                    .where(ORDER_LINES.ORDER_ID.eq(order.getId()))
                    .and(ORDER_LINES.STATUS.eq("ACTIVE"))
                    .and(ORDER_LINES.LINE_TYPE.ne("TIP"))
                    .orderBy(ORDER_LINES.SEQ)
                    .forEach(l -> lines.add(new Line(l.getDescription(), l.getTotalMinor())));
            total = order.getTotalMinor();
            paid = order.getPaidMinor();
            long atVenue = 0;
            for (var row : dsl.selectFrom(PAYMENT_SCHEDULE)
                    .where(PAYMENT_SCHEDULE.ORDER_ID.eq(order.getId()))
                    .and(PAYMENT_SCHEDULE.STATUS.eq("DUE"))
                    .fetch()) {
                if (row.getCollectAt().equals("AT_VENUE")) atVenue += row.getAmountMinor() - row.getCollectedMinor();
                else waitingOnline = true;
            }
            due = atVenue;
        }
        String currency = items.currency() == null ? "ETB" : items.currency();
        String customer = dsl.select(CUSTOMERS.DISPLAY_NAME)
                .from(CUSTOMERS)
                .where(CUSTOMERS.ID.eq(a.getCustomerId()))
                .fetchOne(CUSTOMERS.DISPLAY_NAME);
        Map<UUID, String> team = new LinkedHashMap<>();
        for (var i : dsl.selectFrom(APPOINTMENT_ITEMS)
                .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.eq(a.getId()))
                .orderBy(APPOINTMENT_ITEMS.SEQ)
                .fetch()) {
            if (i.getStaffId() != null) team.putIfAbsent(i.getStaffId(), i.getStaffName());
        }
        Receipts.Receipt receipt = order == null ? null : receipts.find(orgId, order.getId());
        boolean ok = PAYABLE.contains(a.getStatus()) && !waitingOnline && receipt == null;
        return new Bill(
                a.getId(),
                a.getLocationId(),
                a.getReference(),
                a.getStatus(),
                customer,
                currency,
                lines,
                total,
                paid,
                due,
                team.entrySet().stream()
                        .map(e -> new Performer(e.getKey(), e.getValue()))
                        .toList(),
                drawers.current(orgId, a.getLocationId()) != null,
                ok,
                receipt == null ? null : receipt.number());
    }

    /**
     * Takes the cash, adds the tips, completes the visit and issues the receipt.
     *
     * @param tenderedMinor the cash handed over; at least what is due with the tips, and may be more (change is given
     *     back); null when nothing is due
     */
    public Done checkout(OrgAccess access, UUID appointmentId, List<Tip> tips, Long tenderedMinor, Instant now) {
        access.require(SELL);
        return transactions.execute(status -> {
            var a = appointment(access, appointmentId, true);
            UUID orgId = a.getOrgId();
            if (!PAYABLE.contains(a.getStatus())) {
                throw new ApiException(
                        ProblemCode.CONFLICT, "A visit that is " + a.getStatus() + " cannot be checked out.");
            }
            if (a.getOrderId() != null && receipts.find(orgId, a.getOrderId()) != null) {
                throw new ApiException(ProblemCode.CONFLICT, "This visit was checked out already.");
            }
            Bookings.Items items = bookings.loadItems(appointmentId);
            String currency = items.currency();
            UUID orderId = a.getOrderId();
            if (orderId == null) {
                orderId = placeOrder(a, items, access.userId(), now);
            } else {
                var order = dsl.selectFrom(ORDERS)
                        .where(ORDERS.ID.eq(orderId))
                        .forUpdate()
                        .fetchSingle();
                if (!order.getStatus().equals("CONFIRMED")) {
                    throw new ApiException(
                            ProblemCode.CONFLICT,
                            "This visit is waiting for its deposit; it cannot be checked out yet.");
                }
                boolean online = dsl.fetchExists(
                        PAYMENT_SCHEDULE,
                        PAYMENT_SCHEDULE
                                .ORDER_ID
                                .eq(orderId)
                                .and(PAYMENT_SCHEDULE.COLLECT_AT.eq("ONLINE"))
                                .and(PAYMENT_SCHEDULE.STATUS.eq("DUE")));
                if (online) {
                    throw new ApiException(
                            ProblemCode.CONFLICT,
                            "Part of this is waiting to be paid online; it cannot be checked out yet.");
                }
            }
            long paidOnline = dsl.select(ORDERS.PAID_MINOR)
                    .from(ORDERS)
                    .where(ORDERS.ID.eq(orderId))
                    .fetchSingle(ORDERS.PAID_MINOR);

            // Tips go on the order as lines for the people they are for, and onto what is due at the counter.
            long tipTotal = 0;
            List<Orders.Tip> tipLines = new ArrayList<>();
            Map<UUID, Long> perStaff = new LinkedHashMap<>();
            for (Tip t : tips == null ? List.<Tip>of() : tips) {
                if (t.amountMinor() < 0)
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "A tip cannot be negative.");
                if (t.amountMinor() > 0) perStaff.merge(t.staffId(), t.amountMinor(), Long::sum);
            }
            for (var e : perStaff.entrySet()) {
                String name = dsl.select(com.beavent.db.team.Tables.STAFF.DISPLAY_NAME)
                        .from(com.beavent.db.team.Tables.STAFF)
                        .where(com.beavent.db.team.Tables.STAFF.ID.eq(e.getKey()))
                        .fetchOne(com.beavent.db.team.Tables.STAFF.DISPLAY_NAME);
                if (name == null) throw new ApiException(ProblemCode.NOT_FOUND, "There is no such team member.");
                tipLines.add(new Orders.Tip(e.getKey(), name, e.getValue()));
                tipTotal += e.getValue();
            }
            if (tipTotal > 0) {
                orders.addTips(orgId, orderId, tipLines);
                addToBalance(orgId, orderId, currency, tipTotal);
            }

            long due = dsl.select(org.jooq.impl.DSL.coalesce(
                            org.jooq.impl.DSL.sum(
                                    PAYMENT_SCHEDULE.AMOUNT_MINOR.minus(PAYMENT_SCHEDULE.COLLECTED_MINOR)),
                            0))
                    .from(PAYMENT_SCHEDULE)
                    .where(PAYMENT_SCHEDULE.ORDER_ID.eq(orderId))
                    .and(PAYMENT_SCHEDULE.COLLECT_AT.eq("AT_VENUE"))
                    .and(PAYMENT_SCHEDULE.STATUS.eq("DUE"))
                    .fetchSingle(0, Long.class);
            long handed = 0;
            long change = 0;
            if (due > 0) {
                handed = tenderedMinor == null ? due : tenderedMinor;
                if (handed < due) {
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "The cash is less than what is due.");
                }
                var session = drawers.current(orgId, a.getLocationId());
                if (session == null) throw new ApiException(ProblemCode.CONFLICT, "Open the cash drawer first.");
                change = handed - due;
                UUID attempt = payments.recordCash(orgId, orderId, session.id(), due, now);
                drawers.recordSale(orgId, session.id(), attempt, due, access.userId());
            }
            calendar.completeAtCounter(appointmentId, access.userId());
            dsl.update(ORDERS)
                    .set(ORDERS.STATUS, "COMPLETED")
                    .set(ORDERS.COMPLETED_AT, now.atOffset(ZoneOffset.UTC))
                    .set(ORDERS.VERSION, ORDERS.VERSION.plus(1))
                    .where(ORDERS.ID.eq(orderId))
                    .execute();
            dsl.insertInto(ORDER_STATUS_HISTORY)
                    .set(ORDER_STATUS_HISTORY.ORG_ID, orgId)
                    .set(ORDER_STATUS_HISTORY.ORDER_ID, orderId)
                    .set(ORDER_STATUS_HISTORY.FROM_STATUS, "CONFIRMED")
                    .set(ORDER_STATUS_HISTORY.TO_STATUS, "COMPLETED")
                    .set(ORDER_STATUS_HISTORY.ACTOR_KIND, "STAFF")
                    .set(ORDER_STATUS_HISTORY.ACTOR_USER_ID, access.userId())
                    .set(ORDER_STATUS_HISTORY.REASON, "Checked out at the counter")
                    .execute();
            Receipts.Receipt receipt = receipts.issue(orgId, orderId, now);
            var order = dsl.selectFrom(ORDERS).where(ORDERS.ID.eq(orderId)).fetchSingle();
            return new Done(
                    orderId,
                    order.getNumber(),
                    receipt.number(),
                    currency,
                    order.getTotalMinor(),
                    paidOnline,
                    tipTotal,
                    due,
                    handed,
                    change);
        });
    }

    /** A visit with no order: its services as booked become one (confirmed) and all of it is due at the counter. */
    private UUID placeOrder(
            com.beavent.db.scheduling.tables.records.AppointmentsRecord a,
            Bookings.Items items,
            UUID userId,
            Instant now) {
        List<Orders.LineInput> lines = new ArrayList<>();
        for (Bookings.PlanItem item : items.items()) {
            int parent = lines.size();
            String name = item.variantName() == null || item.variantName().isBlank()
                    ? item.serviceName()
                    : item.serviceName() + " · " + item.variantName();
            lines.add(new Orders.LineInput(
                    "SERVICE", item.variantId(), null, item.staffId(), null, name, 1, item.priceMinor()));
            for (Bookings.PlanAddon addon : item.addons()) {
                lines.add(new Orders.LineInput(
                        "ADDON", null, addon.id(), item.staffId(), parent, addon.name(), 1, addon.priceMinor()));
            }
        }
        Orders.Basket basket = new Orders.Basket(
                a.getLocationId(),
                a.getCustomerId(),
                null,
                "PARTNER_POS",
                items.currency(),
                lines,
                null,
                a.getStartsAt().toInstant(),
                now);
        Orders.Placed placed = orders.place(a.getOrgId(), basket, "CONFIRMED", null, userId);
        if (placed.quote().totalMinor() > 0) {
            payments.plan(
                    a.getOrgId(),
                    placed.orderId(),
                    items.currency(),
                    List.of(new Payments.Installment("FULL", placed.quote().totalMinor(), "AT_VENUE")));
        }
        dsl.update(APPOINTMENTS)
                .set(APPOINTMENTS.ORDER_ID, placed.orderId())
                .where(APPOINTMENTS.ID.eq(a.getId()))
                .execute();
        return placed.orderId();
    }

    /** What the tips add is due at the counter with the rest: onto the open balance, or a balance of its own. */
    private void addToBalance(UUID orgId, UUID orderId, String currency, long amountMinor) {
        var open = dsl.selectFrom(PAYMENT_SCHEDULE)
                .where(PAYMENT_SCHEDULE.ORDER_ID.eq(orderId))
                .and(PAYMENT_SCHEDULE.COLLECT_AT.eq("AT_VENUE"))
                .and(PAYMENT_SCHEDULE.STATUS.eq("DUE"))
                .orderBy(PAYMENT_SCHEDULE.SEQ)
                .forUpdate()
                .fetchOne();
        if (open != null) {
            dsl.update(PAYMENT_SCHEDULE)
                    .set(PAYMENT_SCHEDULE.AMOUNT_MINOR, PAYMENT_SCHEDULE.AMOUNT_MINOR.plus(amountMinor))
                    .where(PAYMENT_SCHEDULE.ID.eq(open.getId()))
                    .execute();
            return;
        }
        Short last = dsl.select(org.jooq.impl.DSL.max(PAYMENT_SCHEDULE.SEQ))
                .from(PAYMENT_SCHEDULE)
                .where(PAYMENT_SCHEDULE.ORDER_ID.eq(orderId))
                .fetchOne(0, Short.class);
        dsl.insertInto(PAYMENT_SCHEDULE)
                .set(PAYMENT_SCHEDULE.ORG_ID, orgId)
                .set(PAYMENT_SCHEDULE.ORDER_ID, orderId)
                .set(PAYMENT_SCHEDULE.SEQ, (short) ((last == null ? 0 : last) + 1))
                .set(PAYMENT_SCHEDULE.KIND, "BALANCE")
                .set(PAYMENT_SCHEDULE.AMOUNT_MINOR, amountMinor)
                .set(PAYMENT_SCHEDULE.CURRENCY_CODE, currency)
                .set(PAYMENT_SCHEDULE.COLLECT_AT, "AT_VENUE")
                .execute();
    }

    private com.beavent.db.scheduling.tables.records.AppointmentsRecord appointment(
            OrgAccess access, UUID appointmentId, boolean lock) {
        var q = dsl.selectFrom(APPOINTMENTS).where(APPOINTMENTS.ID.eq(appointmentId));
        var a = (lock ? q.forUpdate() : q).fetchOne();
        if (a == null || !access.canAccessLocation(a.getLocationId())) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such appointment.");
        }
        return a;
    }
}
