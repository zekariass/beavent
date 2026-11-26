package com.beavent.backend.scheduling.internal;

import static com.beavent.db.commerce.Tables.ORDERS;
import static com.beavent.db.commerce.Tables.ORDER_LINES;
import static com.beavent.db.commerce.Tables.ORDER_STATUS_HISTORY;
import static com.beavent.db.commerce.Tables.PAYMENT_SCHEDULE;
import static com.beavent.db.crm.Tables.CUSTOMERS;

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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retail sales at the counter with no visit (plan 4.5): products picked, the order made and the stock taken off in one
 * step, then paid in a second (so a sale that is paid in parts, or fails on a card, is finished rather than made twice),
 * or cancelled while nothing has been paid, which puts the stock back. Paying is the checkout's: {@link Tendering}.
 */
@Component
public class Sales {

    /** What a sale comes to and where it stands. */
    public record SaleBill(
            UUID orderId,
            UUID locationId,
            UUID customerId,
            String number,
            String status,
            String currency,
            List<Checkouts.Line> lines,
            long totalMinor,
            long dueMinor,
            boolean drawerOpen,
            long balanceMinor,
            boolean canCancel,
            String receiptNumber,
            List<Checkouts.Tender> tenders) {}

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final Orders orders;
    private final Payments payments;
    private final CashDrawers drawers;
    private final Receipts receipts;
    private final Products products;
    private final Stock stock;
    private final Tendering tendering;

    Sales(
            DSLContext dsl,
            TransactionTemplate transactions,
            Orders orders,
            Payments payments,
            CashDrawers drawers,
            Receipts receipts,
            Products products,
            Stock stock,
            Tendering tendering) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.orders = orders;
        this.payments = payments;
        this.drawers = drawers;
        this.receipts = receipts;
        this.products = products;
        this.stock = stock;
        this.tendering = tendering;
    }

    /** Makes the sale: its order is confirmed and all of it is due at the counter; the stock is taken off. */
    public SaleBill start(
            OrgAccess access,
            UUID locationId,
            UUID customerId,
            List<Checkouts.ProductItem> items,
            Checkouts.Discount discount,
            Instant now) {
        access.require(Checkouts.SELL);
        if (!access.canAccessLocation(locationId)) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such location.");
        }
        if (items == null || items.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose what is sold.");
        }
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
        Map<UUID, Integer> wanted = new LinkedHashMap<>();
        for (Checkouts.ProductItem item : items) {
            if (item.quantity() < 1 || item.quantity() > 99) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Sell between 1 and 99 of a product.");
            }
            wanted.merge(item.variantId(), item.quantity(), Integer::sum);
        }
        UUID orderId = transactions.execute(status -> {
            UUID orgId = access.orgId();
            if (customerId != null
                    && !dsl.fetchExists(CUSTOMERS, CUSTOMERS.ID.eq(customerId).and(CUSTOMERS.ORG_ID.eq(orgId)))) {
                throw new ApiException(ProblemCode.NOT_FOUND, "There is no such client.");
            }
            List<Products.Sellable> found = products.sellable(orgId, new ArrayList<>(wanted.keySet()), "en");
            String currency = found.getFirst().currency();
            long gross = 0;
            List<Orders.LineInput> lines = new ArrayList<>();
            for (Products.Sellable item : found) {
                int quantity = wanted.get(item.variantId());
                if (!item.currency().equals(currency)) {
                    throw new ApiException(ProblemCode.CONFLICT, "Products are priced in different currencies.");
                }
                long have = stock.available(orgId, locationId, item.variantId());
                if (item.tracked() && have < quantity) {
                    throw new ApiException(
                            ProblemCode.CONFLICT,
                            "Only " + have + " of " + item.name() + " in stock, " + quantity + " asked for.");
                }
                gross += item.priceMinor() * quantity;
                lines.add(new Orders.LineInput(
                        "PRODUCT",
                        null,
                        null,
                        null,
                        null,
                        item.name(),
                        quantity,
                        item.priceMinor(),
                        null,
                        item.variantId()));
            }
            long discountMinor = discount == null
                    ? 0
                    : Math.min(
                            gross,
                            discount.kind().equals("PERCENT")
                                    ? Math.round(gross * (discount.value() / 100.0))
                                    : discount.value());
            Orders.Placed placed = orders.place(
                    orgId,
                    new Orders.Basket(
                            locationId,
                            customerId,
                            null,
                            "PARTNER_POS",
                            currency,
                            lines,
                            null,
                            null,
                            now,
                            discountMinor),
                    "CONFIRMED",
                    null,
                    access.userId());
            if (placed.quote().totalMinor() > 0) {
                payments.plan(
                        orgId,
                        placed.orderId(),
                        currency,
                        List.of(new Payments.Installment("FULL", placed.quote().totalMinor(), "AT_VENUE")));
            }
            for (int i = 0; i < found.size(); i++) {
                stock.sell(
                        orgId,
                        locationId,
                        found.get(i).variantId(),
                        wanted.get(found.get(i).variantId()),
                        placed.lineIds().get(i),
                        access.userId());
            }
            if (discount != null) {
                dsl.update(ORDERS)
                        .set(
                                ORDERS.ATTRIBUTION,
                                JSONB.valueOf("{\"counterDiscount\":{\"kind\":\"" + discount.kind() + "\",\"value\":"
                                        + discount.value() + ",\"reason\":"
                                        + tools.jackson.databind.json.JsonMapper.builder()
                                                .build()
                                                .writeValueAsString(
                                                        discount.reason().strip())
                                        + "}}"))
                        .where(ORDERS.ID.eq(placed.orderId()))
                        .execute();
            }
            return placed.orderId();
        });
        return bill(access, orderId);
    }

    public SaleBill bill(OrgAccess access, UUID orderId) {
        access.require(Checkouts.SELL);
        return transactions.execute(status -> {
            var order = order(access, orderId, false);
            List<Checkouts.Line> lines = new ArrayList<>();
            dsl.selectFrom(ORDER_LINES)
                    .where(ORDER_LINES.ORDER_ID.eq(orderId), ORDER_LINES.STATUS.eq("ACTIVE"))
                    .orderBy(ORDER_LINES.SEQ)
                    .forEach(l -> lines.add(new Checkouts.Line(
                            l.getQuantity() > 1 ? l.getDescription() + " × " + l.getQuantity() : l.getDescription(),
                            l.getTotalMinor())));
            long paid = dsl.select(
                            org.jooq.impl.DSL.coalesce(org.jooq.impl.DSL.sum(PAYMENT_SCHEDULE.COLLECTED_MINOR), 0))
                    .from(PAYMENT_SCHEDULE)
                    .where(PAYMENT_SCHEDULE.ORDER_ID.eq(orderId))
                    .fetchSingle(0, Long.class);
            Receipts.Receipt receipt = receipts.find(order.getOrgId(), orderId);
            return new SaleBill(
                    orderId,
                    order.getLocationId(),
                    order.getCustomerId(),
                    order.getNumber(),
                    order.getStatus(),
                    order.getCurrencyCode().trim(),
                    lines,
                    order.getTotalMinor(),
                    tendering.dueAtVenue(orderId),
                    drawers.current(order.getOrgId(), order.getLocationId()) != null,
                    payments.customerBalance(order.getOrgId(), order.getCustomerId(), order.getCurrencyCode()),
                    order.getStatus().equals("CONFIRMED") && paid == 0,
                    receipt == null ? null : receipt.number(),
                    tendering.tendersOf(orderId));
        });
    }

    /** Takes the payment and completes the sale: gift cards and credit first, then the cash for the rest; the receipt. */
    public Checkouts.Done pay(
            OrgAccess access,
            UUID orderId,
            List<String> cards,
            boolean useBalance,
            Long cashTenderedMinor,
            Instant now) {
        access.require(Checkouts.SELL);
        var order = transactions.execute(status -> {
            var o = order(access, orderId, false);
            if (!o.getStatus().equals("CONFIRMED")) {
                throw new ApiException(ProblemCode.CONFLICT, "This sale is " + o.getStatus() + ".");
            }
            if (cards == null || cards.isEmpty()) {
                if (!useBalance) {
                    long due = tendering.dueAtVenue(orderId);
                    if (due > 0) {
                        if (drawers.current(o.getOrgId(), o.getLocationId()) == null) {
                            throw new ApiException(ProblemCode.CONFLICT, "Open the cash drawer first.");
                        }
                        if ((cashTenderedMinor == null ? due : cashTenderedMinor) < due) {
                            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The cash is less than what is due.");
                        }
                    }
                }
            }
            return o;
        });
        return tendering.collect(
                access,
                order.getOrgId(),
                order.getLocationId(),
                orderId,
                null,
                cards == null ? List.of() : cards,
                useBalance,
                cashTenderedMinor,
                order.getCurrencyCode().trim(),
                order.getPaidMinor(),
                now);
    }

    /** Cancels a sale nothing has been paid for: the goods go back on the shelf. */
    public void cancel(OrgAccess access, UUID orderId, Instant now) {
        access.require(Checkouts.SELL);
        transactions.executeWithoutResult(status -> {
            var order = order(access, orderId, true);
            long paid = dsl.select(
                            org.jooq.impl.DSL.coalesce(org.jooq.impl.DSL.sum(PAYMENT_SCHEDULE.COLLECTED_MINOR), 0))
                    .from(PAYMENT_SCHEDULE)
                    .where(PAYMENT_SCHEDULE.ORDER_ID.eq(orderId))
                    .fetchSingle(0, Long.class);
            if (!order.getStatus().equals("CONFIRMED") || paid > 0) {
                throw new ApiException(ProblemCode.CONFLICT, "Only a sale nothing has been paid for can be cancelled.");
            }
            UUID orgId = order.getOrgId();
            dsl.selectFrom(ORDER_LINES)
                    .where(ORDER_LINES.ORDER_ID.eq(orderId), ORDER_LINES.LINE_TYPE.eq("PRODUCT"))
                    .forEach(l -> stock.restore(
                            orgId,
                            order.getLocationId(),
                            l.getProductVariantId(),
                            l.getQuantity(),
                            l.getId(),
                            access.userId()));
            dsl.update(PAYMENT_SCHEDULE)
                    .set(PAYMENT_SCHEDULE.STATUS, "CANCELLED")
                    .where(PAYMENT_SCHEDULE.ORDER_ID.eq(orderId), PAYMENT_SCHEDULE.STATUS.eq("DUE"))
                    .execute();
            dsl.update(ORDERS)
                    .set(ORDERS.STATUS, "CANCELLED")
                    .set(ORDERS.CANCELLED_AT, now.atOffset(ZoneOffset.UTC))
                    .set(ORDERS.CANCEL_REASON, "Cancelled at the counter")
                    .set(ORDERS.VERSION, ORDERS.VERSION.plus(1))
                    .where(ORDERS.ID.eq(orderId))
                    .execute();
            dsl.insertInto(ORDER_STATUS_HISTORY)
                    .set(ORDER_STATUS_HISTORY.ORG_ID, orgId)
                    .set(ORDER_STATUS_HISTORY.ORDER_ID, orderId)
                    .set(ORDER_STATUS_HISTORY.FROM_STATUS, "CONFIRMED")
                    .set(ORDER_STATUS_HISTORY.TO_STATUS, "CANCELLED")
                    .set(ORDER_STATUS_HISTORY.ACTOR_KIND, "STAFF")
                    .set(ORDER_STATUS_HISTORY.ACTOR_USER_ID, access.userId())
                    .set(ORDER_STATUS_HISTORY.REASON, "Cancelled at the counter")
                    .execute();
        });
    }

    /** A counter sale of the business, at a location the caller may work in; not a visit's order. */
    private com.beavent.db.commerce.tables.records.OrdersRecord order(OrgAccess access, UUID orderId, boolean lock) {
        var q = dsl.selectFrom(ORDERS).where(ORDERS.ID.eq(orderId), ORDERS.ORG_ID.eq(access.orgId()));
        var o = (lock ? q.forUpdate() : q).fetchOne();
        if (o == null
                || !o.getChannel().equals("PARTNER_POS")
                || o.getLocationId() == null
                || !access.canAccessLocation(o.getLocationId())
                || dsl.fetchExists(
                        com.beavent.db.scheduling.Tables.APPOINTMENTS,
                        com.beavent.db.scheduling.Tables.APPOINTMENTS.ORDER_ID.eq(orderId))) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such sale.");
        }
        return o;
    }
}
