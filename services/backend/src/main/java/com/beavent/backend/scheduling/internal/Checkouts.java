package com.beavent.backend.scheduling.internal;

import static com.beavent.db.commerce.Tables.ORDERS;
import static com.beavent.db.commerce.Tables.ORDER_LINES;
import static com.beavent.db.commerce.Tables.PAYMENT_SCHEDULE;
import static com.beavent.db.crm.Tables.CUSTOMERS;
import static com.beavent.db.scheduling.Tables.APPOINTMENTS;
import static com.beavent.db.scheduling.Tables.APPOINTMENT_ITEMS;

import com.beavent.backend.catalog.Products;
import com.beavent.backend.commerce.CashDrawers;
import com.beavent.backend.commerce.Orders;
import com.beavent.backend.commerce.Receipts;
import com.beavent.backend.payments.Payments;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.stock.Stock;
import com.beavent.backend.tenancy.OrgAccess;
import java.time.Instant;
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
            UUID orderId,
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
            String receiptNumber,
            long balanceMinor,
            boolean canDiscount,
            List<Tender> tenders) {}

    public record Tip(UUID staffId, long amountMinor) {}

    /**
     * A discount given at the counter on a visit that has no order yet.
     *
     * @param kind {@code AMOUNT} ({@code value} in minor units) or {@code PERCENT} ({@code value} 1 to 100)
     */
    public record Discount(String kind, long value, String reason) {}

    /**
     * What is paid and how: gift cards (in the order given) and the customer's balance first, as much as is due, then the
     * cash for the rest.
     *
     * @param cashTenderedMinor the cash handed over (more than is due is fine; null: exactly what is due)
     */
    public record Sale(
            List<Tip> tips,
            Discount discount,
            List<String> giftCards,
            boolean useBalance,
            Long cashTenderedMinor,
            List<ProductItem> products) {

        /** A sale of the visit only. */
        public Sale(
                List<Tip> tips, Discount discount, List<String> giftCards, boolean useBalance, Long cashTenderedMinor) {
            this(tips, discount, giftCards, useBalance, cashTenderedMinor, List.of());
        }
    }

    /** A retail product to sell with the visit. */
    public record ProductItem(UUID variantId, int quantity) {}

    /** A part of the sale and how it was paid: {@code GIFT_CARD}, {@code STORE_CREDIT} or {@code CASH}. */
    public record Tender(String method, long amountMinor) {}

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
            long changeMinor,
            long discountMinor,
            List<Tender> tenders) {}

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Bookings bookings;
    private final Calendar calendar;
    private final Orders orders;
    private final Payments payments;
    private final CashDrawers drawers;
    private final Receipts receipts;
    private final Tendering tendering;
    private final Stock stock;
    private final Products products;

    Checkouts(
            DSLContext dsl,
            TransactionTemplate transactions,
            Bookings bookings,
            Calendar calendar,
            Orders orders,
            Payments payments,
            CashDrawers drawers,
            Receipts receipts,
            Tendering tendering,
            Stock stock,
            Products products) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.bookings = bookings;
        this.calendar = calendar;
        this.orders = orders;
        this.payments = payments;
        this.drawers = drawers;
        this.receipts = receipts;
        this.tendering = tendering;
        this.stock = stock;
        this.products = products;
    }

    private static final java.util.Set<String> PAYABLE =
            java.util.Set.of("CONFIRMED", "ARRIVED", "IN_PROGRESS", "COMPLETED");

    public Bill bill(OrgAccess access, UUID appointmentId) {
        access.require(SELL);
        return transactions.execute(status -> {
            var a = appointment(access, appointmentId, false);
            return billOf(a, access);
        });
    }

    private Bill billOf(com.beavent.db.scheduling.tables.records.AppointmentsRecord a, OrgAccess access) {
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
        long balance = payments.customerBalance(a.getOrgId(), a.getCustomerId(), currency);
        List<Tender> tenders = order == null ? List.of() : tendering.tendersOf(order.getId());
        return new Bill(
                a.getId(),
                a.getLocationId(),
                order == null ? null : order.getId(),
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
                receipt == null ? null : receipt.number(),
                balance,
                order == null && access.can("pos.discount"),
                tenders);
    }

    /**
     * Checks the visit out: a discount and tips go on the order, gift cards and the customer's balance pay what they can,
     * the cash pays the rest, the visit is completed and the receipt issued. What is taken from a gift card or balance is
     * committed as it is taken (it is the customer's value, spent); the cash, the completion and the receipt are one
     * transaction. When something fails midway the answer says so and the bill shows what has been paid, so the sale can
     * be finished. A request that can only fail on the cash is refused before anything is changed.
     */
    public Done checkout(OrgAccess access, UUID appointmentId, Sale sale, Instant now) {
        access.require(SELL);
        List<Tip> tips = sale.tips() == null ? List.of() : sale.tips();
        List<String> cards = sale.giftCards() == null ? List.of() : sale.giftCards();
        Discount discount = sale.discount();
        if (discount != null) {
            access.require("pos.discount");
            boolean percent = "PERCENT".equals(discount.kind());
            if (!percent && !"AMOUNT".equals(discount.kind())
                    || discount.value() <= 0
                    || (percent && discount.value() > 100)
                    || discount.reason() == null
                    || discount.reason().strip().length() < 3) {
                throw new ApiException(
                        ProblemCode.VALIDATION_FAILED, "Say how much the discount is and why it is given.");
            }
        }
        Map<UUID, Long> perStaff = new LinkedHashMap<>();
        for (Tip t : tips) {
            if (t.amountMinor() < 0) throw new ApiException(ProblemCode.VALIDATION_FAILED, "A tip cannot be negative.");
            if (t.amountMinor() > 0) perStaff.merge(t.staffId(), t.amountMinor(), Long::sum);
        }
        long tipTotal = perStaff.values().stream().mapToLong(Long::longValue).sum();
        Map<UUID, Integer> perVariant = new LinkedHashMap<>();
        for (ProductItem item : sale.products() == null ? List.<ProductItem>of() : sale.products()) {
            if (item.quantity() < 1 || item.quantity() > 99) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Sell between 1 and 99 of a product.");
            }
            perVariant.merge(item.variantId(), item.quantity(), Integer::sum);
        }

        // Before anything is changed: whatever can already be seen to fail.
        boolean valueTenders = !cards.isEmpty() || sale.useBalance();
        transactions.executeWithoutResult(status -> {
            var a = appointment(access, appointmentId, false);
            if (!PAYABLE.contains(a.getStatus())) {
                throw new ApiException(
                        ProblemCode.CONFLICT, "A visit that is " + a.getStatus() + " cannot be checked out.");
            }
            if (a.getOrderId() != null && receipts.find(a.getOrgId(), a.getOrderId()) != null) {
                throw new ApiException(ProblemCode.CONFLICT, "This visit was checked out already.");
            }
            for (UUID staffId : perStaff.keySet()) staffName(staffId);
            List<Sold> sold = sold(a.getOrgId(), a.getLocationId(), perVariant, items0Currency(a));
            if (!valueTenders) {
                long expected = expectedDue(a, discount, tipTotal, sold);
                if (expected > 0) {
                    if (drawers.current(a.getOrgId(), a.getLocationId()) == null) {
                        throw new ApiException(ProblemCode.CONFLICT, "Open the cash drawer first.");
                    }
                    long handed = sale.cashTenderedMinor() == null ? expected : sale.cashTenderedMinor();
                    if (handed < expected) {
                        throw new ApiException(ProblemCode.VALIDATION_FAILED, "The cash is less than what is due.");
                    }
                }
            }
        });

        // The order, its discount and its tips: committed, so what follows can see them.
        record Prepared(UUID orderId, UUID orgId, UUID locationId, UUID customerId, String currency, long paidOnline) {}
        Prepared prepared = transactions.execute(status -> {
            var a = appointment(access, appointmentId, true);
            UUID orgId = a.getOrgId();
            Bookings.Items items = bookings.loadItems(appointmentId);
            UUID orderId = a.getOrderId();
            Map<Integer, UUID> placedLines = Map.of();
            int serviceLines =
                    items.items().stream().mapToInt(i -> 1 + i.addons().size()).sum();
            List<Sold> sold = sold(orgId, a.getLocationId(), perVariant, items.currency());
            if (orderId == null) {
                Orders.Placed placed = placeOrder(a, items, discount, sold, access.userId(), now);
                orderId = placed.orderId();
                placedLines = placed.lineIds();
                for (int i = 0; i < sold.size(); i++) {
                    stock.sell(
                            orgId,
                            a.getLocationId(),
                            sold.get(i).item().variantId(),
                            sold.get(i).quantity(),
                            placedLines.get(serviceLines + i),
                            access.userId());
                }
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
                if (discount != null && !order.getChannel().equals("PARTNER_POS")) {
                    throw new ApiException(
                            ProblemCode.CONFLICT, "A discount can only be given on a visit that has no order yet.");
                }
                if (dsl.fetchExists(
                        PAYMENT_SCHEDULE,
                        PAYMENT_SCHEDULE
                                .ORDER_ID
                                .eq(orderId)
                                .and(PAYMENT_SCHEDULE.COLLECT_AT.eq("ONLINE"))
                                .and(PAYMENT_SCHEDULE.STATUS.eq("DUE")))) {
                    throw new ApiException(
                            ProblemCode.CONFLICT,
                            "Part of this is waiting to be paid online; it cannot be checked out yet.");
                }
            }
            // Goods go on once, like tips: a sale that was started keeps what it has.
            if (!sold.isEmpty()
                    && !dsl.fetchExists(
                            ORDER_LINES, ORDER_LINES.ORDER_ID.eq(orderId).and(ORDER_LINES.LINE_TYPE.eq("PRODUCT")))) {
                List<UUID> lineIds = orders.addProducts(
                        orgId,
                        orderId,
                        sold.stream()
                                .map(p -> new Orders.ProductLine(
                                        p.item().variantId(),
                                        p.item().name(),
                                        p.quantity(),
                                        p.item().priceMinor()))
                                .toList());
                long added = dsl.select(org.jooq.impl.DSL.coalesce(org.jooq.impl.DSL.sum(ORDER_LINES.TOTAL_MINOR), 0))
                        .from(ORDER_LINES)
                        .where(ORDER_LINES.ID.in(lineIds))
                        .fetchSingle(0, Long.class);
                addToBalance(orgId, orderId, items.currency(), added);
                for (int i = 0; i < sold.size(); i++) {
                    stock.sell(
                            orgId,
                            a.getLocationId(),
                            sold.get(i).item().variantId(),
                            sold.get(i).quantity(),
                            lineIds.get(i),
                            access.userId());
                }
            }
            // Tips go on once; a sale that was started keeps the tips it has.
            boolean hasTips = dsl.fetchExists(
                    ORDER_LINES, ORDER_LINES.ORDER_ID.eq(orderId).and(ORDER_LINES.LINE_TYPE.eq("TIP")));
            if (tipTotal > 0 && !hasTips) {
                List<Orders.Tip> lines = new ArrayList<>();
                for (var e : perStaff.entrySet())
                    lines.add(new Orders.Tip(e.getKey(), staffName(e.getKey()), e.getValue()));
                orders.addTips(orgId, orderId, lines);
                addToBalance(orgId, orderId, items.currency(), tipTotal);
            }
            long paidOnline = dsl.select(ORDERS.PAID_MINOR)
                    .from(ORDERS)
                    .where(ORDERS.ID.eq(orderId))
                    .fetchSingle(ORDERS.PAID_MINOR);
            return new Prepared(orderId, orgId, a.getLocationId(), a.getCustomerId(), items.currency(), paidOnline);
        });
        UUID orderId = prepared.orderId();
        UUID orgId = prepared.orgId();

        return tendering.collect(
                access,
                prepared.orgId(),
                prepared.locationId(),
                orderId,
                appointmentId,
                sale.giftCards() == null ? List.of() : sale.giftCards(),
                sale.useBalance(),
                sale.cashTenderedMinor(),
                prepared.currency(),
                prepared.paidOnline(),
                now);
    }

    /** What the sale will come to at the counter: for a visit without an order, priced now with the discount. */
    private long expectedDue(
            com.beavent.db.scheduling.tables.records.AppointmentsRecord a,
            Discount discount,
            long tipTotal,
            List<Sold> sold) {
        if (a.getOrderId() != null) {
            boolean hasTips = dsl.fetchExists(
                    ORDER_LINES, ORDER_LINES.ORDER_ID.eq(a.getOrderId()).and(ORDER_LINES.LINE_TYPE.eq("TIP")));
            boolean hasProducts = dsl.fetchExists(
                    ORDER_LINES, ORDER_LINES.ORDER_ID.eq(a.getOrderId()).and(ORDER_LINES.LINE_TYPE.eq("PRODUCT")));
            long goods = hasProducts
                    ? 0
                    : sold.stream()
                            .mapToLong(p -> p.item().priceMinor() * p.quantity())
                            .sum();
            return tendering.dueAtVenue(a.getOrderId()) + (hasTips ? 0 : tipTotal) + goods;
        }
        Bookings.Items items = bookings.loadItems(a.getId());
        long discountMinor = discountOf(items, discount);
        Orders.Quote quote = orders.quote(a.getOrgId(), basket(a, items, discountMinor, sold, Instant.now()));
        return quote.totalMinor() + tipTotal;
    }

    private static long discountOf(Bookings.Items items, Discount discount) {
        if (discount == null) return 0;
        long gross = items.total();
        long wanted =
                discount.kind().equals("PERCENT") ? Math.round(gross * (discount.value() / 100.0)) : discount.value();
        return Math.min(wanted, gross);
    }

    /** The products as they are sold here: found, in the visit's currency, and in stock. */
    private List<Sold> sold(UUID orgId, UUID locationId, Map<UUID, Integer> wanted, String currency) {
        if (wanted.isEmpty()) return List.of();
        List<Products.Sellable> found = products.sellable(orgId, new ArrayList<>(wanted.keySet()), "en");
        List<Sold> out = new ArrayList<>();
        for (Products.Sellable item : found) {
            int quantity = wanted.get(item.variantId());
            if (currency != null && !item.currency().equals(currency)) {
                throw new ApiException(ProblemCode.CONFLICT, "A product is priced in another currency.");
            }
            long have = stock.available(orgId, locationId, item.variantId());
            if (item.tracked() && have < quantity) {
                throw new ApiException(
                        ProblemCode.CONFLICT,
                        "Only " + have + " of " + item.name() + " in stock, " + quantity + " asked for.");
            }
            out.add(new Sold(item, quantity));
        }
        return out;
    }

    private String items0Currency(com.beavent.db.scheduling.tables.records.AppointmentsRecord a) {
        return bookings.loadItems(a.getId()).currency();
    }

    private String staffName(UUID staffId) {
        String name = dsl.select(com.beavent.db.team.Tables.STAFF.DISPLAY_NAME)
                .from(com.beavent.db.team.Tables.STAFF)
                .where(com.beavent.db.team.Tables.STAFF.ID.eq(staffId))
                .fetchOne(com.beavent.db.team.Tables.STAFF.DISPLAY_NAME);
        if (name == null) throw new ApiException(ProblemCode.NOT_FOUND, "There is no such team member.");
        return name;
    }

    /** A product as it is sold: its price and name now, and how many. */
    private record Sold(Products.Sellable item, int quantity) {}

    private Orders.Basket basket(
            com.beavent.db.scheduling.tables.records.AppointmentsRecord a,
            Bookings.Items items,
            long discountMinor,
            List<Sold> sold,
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
        for (Sold p : sold) {
            lines.add(new Orders.LineInput(
                    "PRODUCT",
                    null,
                    null,
                    null,
                    null,
                    p.item().name(),
                    p.quantity(),
                    p.item().priceMinor(),
                    null,
                    p.item().variantId()));
        }
        return new Orders.Basket(
                a.getLocationId(),
                a.getCustomerId(),
                null,
                "PARTNER_POS",
                items.currency(),
                lines,
                null,
                a.getStartsAt().toInstant(),
                now,
                discountMinor);
    }

    /** A visit with no order: its services as booked become one (confirmed) and all of it is due at the counter. */
    private Orders.Placed placeOrder(
            com.beavent.db.scheduling.tables.records.AppointmentsRecord a,
            Bookings.Items items,
            Discount discount,
            List<Sold> sold,
            UUID userId,
            Instant now) {
        Orders.Basket basket = basket(a, items, discountOf(items, discount), sold, now);
        Orders.Placed placed = orders.place(a.getOrgId(), basket, "CONFIRMED", null, userId);
        if (placed.quote().totalMinor() > 0) {
            payments.plan(
                    a.getOrgId(),
                    placed.orderId(),
                    items.currency(),
                    List.of(new Payments.Installment("FULL", placed.quote().totalMinor(), "AT_VENUE")));
        }
        if (discount != null) {
            dsl.update(ORDERS)
                    .set(
                            ORDERS.ATTRIBUTION,
                            org.jooq.JSONB.valueOf(
                                    "{\"counterDiscount\":{\"kind\":\"" + discount.kind() + "\",\"value\":"
                                            + discount.value() + ",\"reason\":"
                                            + tools.jackson.databind.json.JsonMapper.builder()
                                                    .build()
                                                    .writeValueAsString(
                                                            discount.reason().strip())
                                            + "}}"))
                    .where(ORDERS.ID.eq(placed.orderId()))
                    .execute();
        }
        dsl.update(APPOINTMENTS)
                .set(APPOINTMENTS.ORDER_ID, placed.orderId())
                .where(APPOINTMENTS.ID.eq(a.getId()))
                .execute();
        return placed;
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
