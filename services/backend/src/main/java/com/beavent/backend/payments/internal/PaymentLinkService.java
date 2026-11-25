package com.beavent.backend.payments.internal;

import static com.beavent.db.commerce.Tables.ORDERS;
import static com.beavent.db.commerce.Tables.PAYMENT_SCHEDULE;
import static com.beavent.db.crm.Tables.CUSTOMERS;
import static com.beavent.db.payments.Tables.PAYMENT_INTENTS;
import static com.beavent.db.payments.Tables.PAYMENT_LINKS;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;

import com.beavent.backend.payments.PaymentFulfilment;
import com.beavent.backend.payments.PaymentLinks;
import com.beavent.backend.payments.Payments;
import com.beavent.backend.payments.StoredValue;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import com.beavent.db.commerce.tables.records.PaymentScheduleRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payment links (plan 3.7). The link is a random token, kept only as a hash. It points at one payment (an intent for
 * one row of the order's payment schedule): the deposit of an order waiting for it, or the balance of a booking that
 * stands. Opening it needs no account; paying goes through the same provider flow as everything else, so the payment
 * is confirmed only by a lookup with Beavent's key and the order's module decides what the payment means.
 */
@Component
class PaymentLinkService implements PaymentLinks {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final PaymentsService payments;
    private final SellerAccounts sellers;
    private final ActiveProvider providers;
    private final List<PaymentFulfilment> fulfilments;
    private final Outbox outbox;
    private final PaymentsProperties props;
    private final String webUrl;

    PaymentLinkService(
            @SystemDb DSLContext system,
            @SystemDb TransactionTemplate transactions,
            PaymentsService payments,
            SellerAccounts sellers,
            ActiveProvider providers,
            List<PaymentFulfilment> fulfilments,
            Outbox outbox,
            PaymentsProperties props,
            @org.springframework.beans.factory.annotation.Value("${beavent.web.public-url}") String webUrl) {
        this.system = system;
        this.transactions = transactions;
        this.payments = payments;
        this.sellers = sellers;
        this.providers = providers;
        this.fulfilments = fulfilments;
        this.outbox = outbox;
        this.props = props;
        this.webUrl = webUrl.replaceAll("/+$", "");
    }

    // --- making a link ---------------------------------------------------------------------------------------------

    @Override
    public Created create(UUID orgId, UUID orderId, UUID createdBy, Instant now) {
        if (!sellers.available(orgId)) {
            throw new ApiException(ProblemCode.PAYMENT_UNAVAILABLE, "This business cannot take online payments yet.");
        }
        return transactions.execute(status -> {
            var order = system.select(ORDERS.STATUS, ORDERS.EXPIRES_AT, ORDERS.CURRENCY_CODE, ORDERS.CUSTOMER_ID)
                    .from(ORDERS)
                    .where(ORDERS.ID.eq(orderId).and(ORDERS.ORG_ID.eq(orgId)))
                    .forUpdate()
                    .fetchOne();
            if (order == null) throw new ApiException(ProblemCode.NOT_FOUND, "There is no such order.");
            Instant orderExpires =
                    order.value2() == null ? null : order.value2().toInstant();
            PaymentScheduleRecord row;
            Instant expires;
            if (order.value1().equals("PENDING_PAYMENT") && orderExpires != null && orderExpires.isAfter(now)) {
                row = system.selectFrom(PAYMENT_SCHEDULE)
                        .where(PAYMENT_SCHEDULE.ORDER_ID.eq(orderId))
                        .and(PAYMENT_SCHEDULE.COLLECT_AT.eq("ONLINE"))
                        .and(PAYMENT_SCHEDULE.STATUS.eq("DUE"))
                        .orderBy(PAYMENT_SCHEDULE.SEQ)
                        .limit(1)
                        .fetchOne();
                expires = orderExpires;
            } else if (order.value1().equals("CONFIRMED") && !voided(orgId, orderId, now)) {
                row = system.selectFrom(PAYMENT_SCHEDULE)
                        .where(PAYMENT_SCHEDULE.ORDER_ID.eq(orderId))
                        .and(PAYMENT_SCHEDULE.KIND.eq("BALANCE"))
                        .and(PAYMENT_SCHEDULE.STATUS.eq("DUE"))
                        .limit(1)
                        .fetchOne();
                expires = now.plus(props.balanceLinkTtl());
            } else {
                throw new ApiException(ProblemCode.CONFLICT, "Nothing can be paid on this booking any more.");
            }
            if (row == null) throw new ApiException(ProblemCode.CONFLICT, "Nothing is due on this booking.");
            String currency = order.value3().trim();
            UUID intentId = payments.ensureIntent(
                    orgId, orderId, row, currency, order.value4(), null, expires, providers.collectionMode());
            // A balance link reopens an intent that ran out with an earlier link.
            if (row.getKind().equals("BALANCE")) {
                system.update(PAYMENT_INTENTS)
                        .set(PAYMENT_INTENTS.EXPIRES_AT, expires.atOffset(ZoneOffset.UTC))
                        .set(PAYMENT_INTENTS.STATUS, "REQUIRES_METHOD")
                        .where(PAYMENT_INTENTS.ID.eq(intentId))
                        .and(PAYMENT_INTENTS.STATUS.in("EXPIRED", "CANCELLED"))
                        .execute();
                system.update(PAYMENT_INTENTS)
                        .set(PAYMENT_INTENTS.EXPIRES_AT, expires.atOffset(ZoneOffset.UTC))
                        .where(PAYMENT_INTENTS.ID.eq(intentId))
                        .execute();
            }
            system.update(PAYMENT_LINKS)
                    .set(PAYMENT_LINKS.STATUS, "REVOKED")
                    .where(PAYMENT_LINKS.INTENT_ID.eq(intentId), PAYMENT_LINKS.STATUS.eq("ACTIVE"))
                    .execute();
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            UUID id = system.insertInto(PAYMENT_LINKS)
                    .set(PAYMENT_LINKS.ORG_ID, orgId)
                    .set(PAYMENT_LINKS.INTENT_ID, intentId)
                    .set(PAYMENT_LINKS.TOKEN_HASH, hash(token))
                    .set(PAYMENT_LINKS.EXPIRES_AT, expires.atOffset(ZoneOffset.UTC))
                    .set(PAYMENT_LINKS.CREATED_BY, createdBy)
                    .returning(PAYMENT_LINKS.ID)
                    .fetchSingle(PAYMENT_LINKS.ID);
            outbox.publish(new DomainEvent(
                    "payments.link",
                    id,
                    "payment_link.created",
                    1,
                    orgId,
                    Map.of(
                            "linkId", id.toString(),
                            "orderId", orderId.toString(),
                            "token", token,
                            "kind", row.getKind(),
                            "amountMinor", row.getAmountMinor(),
                            "currency", currency,
                            "expiresAt", expires.toString())));
            return new Created(id, token, addressOf(token), expires, row.getAmountMinor(), currency, row.getKind());
        });
    }

    /** A booking that was cancelled, declined or missed has nothing left to collect. */
    private boolean voided(UUID orgId, UUID orderId, Instant now) {
        for (PaymentFulfilment f : fulfilments) {
            PaymentFulfilment.Delivery d = f.delivery(orgId, orderId, now);
            if (d != null) return d.state() == PaymentFulfilment.DeliveryState.VOID;
        }
        return false;
    }

    static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- what the payer sees -----------------------------------------------------------------------------------------

    private record Found(
            UUID linkId,
            UUID orgId,
            UUID orderId,
            UUID scheduleId,
            String kind,
            String linkStatus,
            Instant linkExpires,
            String intentStatus,
            long amountMinor,
            String currency,
            String orderStatus,
            String business,
            UUID customerId) {}

    private Found find(String token) {
        if (token == null || token.length() < 20 || token.length() > 100) return null;
        var r = system.select(
                        PAYMENT_LINKS.ID,
                        PAYMENT_LINKS.ORG_ID,
                        PAYMENT_LINKS.STATUS,
                        PAYMENT_LINKS.EXPIRES_AT,
                        PAYMENT_INTENTS.ORDER_ID,
                        PAYMENT_INTENTS.PAYMENT_SCHEDULE_ID,
                        PAYMENT_INTENTS.STATUS,
                        PAYMENT_INTENTS.AMOUNT_MINOR,
                        PAYMENT_INTENTS.CURRENCY_CODE,
                        PAYMENT_SCHEDULE.KIND,
                        ORDERS.STATUS,
                        ORDERS.CUSTOMER_ID,
                        ORGANIZATIONS.DISPLAY_NAME)
                .from(PAYMENT_LINKS)
                .join(PAYMENT_INTENTS)
                .on(PAYMENT_INTENTS.ID.eq(PAYMENT_LINKS.INTENT_ID))
                .join(PAYMENT_SCHEDULE)
                .on(PAYMENT_SCHEDULE.ID.eq(PAYMENT_INTENTS.PAYMENT_SCHEDULE_ID))
                .join(ORDERS)
                .on(ORDERS.ID.eq(PAYMENT_INTENTS.ORDER_ID))
                .join(ORGANIZATIONS)
                .on(ORGANIZATIONS.ID.eq(PAYMENT_LINKS.ORG_ID))
                .where(PAYMENT_LINKS.TOKEN_HASH.eq(hash(token)))
                .fetchOne();
        if (r == null) return null;
        return new Found(
                r.get(PAYMENT_LINKS.ID),
                r.get(PAYMENT_LINKS.ORG_ID),
                r.get(PAYMENT_INTENTS.ORDER_ID),
                r.get(PAYMENT_INTENTS.PAYMENT_SCHEDULE_ID),
                r.get(PAYMENT_SCHEDULE.KIND),
                r.get(PAYMENT_LINKS.STATUS),
                r.get(PAYMENT_LINKS.EXPIRES_AT).toInstant(),
                r.get(PAYMENT_INTENTS.STATUS),
                r.get(PAYMENT_INTENTS.AMOUNT_MINOR),
                r.get(PAYMENT_INTENTS.CURRENCY_CODE).trim(),
                r.get(ORDERS.STATUS),
                r.get(ORGANIZATIONS.DISPLAY_NAME),
                r.get(ORDERS.CUSTOMER_ID));
    }

    private String stateOf(Found f, Instant now) {
        if (f.intentStatus().equals("SUCCEEDED") || f.linkStatus().equals("PAID")) return "PAID";
        if (!f.linkStatus().equals("ACTIVE")
                || !f.linkExpires().isAfter(now)
                || f.intentStatus().equals("EXPIRED")
                || f.intentStatus().equals("CANCELLED")) {
            return "EXPIRED";
        }
        boolean balance = f.kind().equals("BALANCE");
        // A deposit is paid while the booking waits for it; a balance while the booking stands.
        boolean stands = balance
                ? f.orderStatus().equals("CONFIRMED") && !voided(f.orgId(), f.orderId(), now)
                : f.orderStatus().equals("PENDING_PAYMENT");
        return stands ? "OPEN" : "EXPIRED";
    }

    @Override
    public Info info(String token, Instant now) {
        Found f = find(token);
        if (f == null) return null;
        return transactions.execute(status -> {
            system.update(PAYMENT_LINKS)
                    .set(PAYMENT_LINKS.OPENED_AT, now.atOffset(ZoneOffset.UTC))
                    .where(PAYMENT_LINKS.ID.eq(f.linkId()), PAYMENT_LINKS.OPENED_AT.isNull())
                    .execute();
            PaymentFulfilment.Description d = null;
            for (PaymentFulfilment fulfilment : fulfilments) {
                d = fulfilment.describe(f.orgId(), f.orderId());
                if (d != null) break;
            }
            return new Info(
                    stateOf(f, now),
                    f.kind(),
                    d == null ? "BOOKING" : d.subject(),
                    f.amountMinor(),
                    f.currency(),
                    f.linkExpires(),
                    f.business(),
                    d == null ? null : d.locationName(),
                    d == null ? null : d.reference(),
                    d == null ? null : d.startsAt(),
                    d == null ? null : d.timezone(),
                    d == null ? List.of() : d.items());
        });
    }

    // --- paying ------------------------------------------------------------------------------------------------------

    @Override
    public Payments.Started start(String token, String language, Payments.Urls urls, Instant now) {
        Found f = find(token);
        if (f == null) throw new ApiException(ProblemCode.NOT_FOUND, "This link is not valid.");
        String state = stateOf(f, now);
        if (state.equals("PAID")) throw new ApiException(ProblemCode.CONFLICT, "This has already been paid.");
        if (!state.equals("OPEN")) throw new ApiException(ProblemCode.HOLD_EXPIRED, "This link is no longer open.");
        var customer = f.customerId() == null
                ? null
                : system.select(CUSTOMERS.PHONE_E164, CUSTOMERS.EMAIL)
                        .from(CUSTOMERS)
                        .where(CUSTOMERS.ID.eq(f.customerId()))
                        .fetchOne();
        PaymentFulfilment.Description d = null;
        for (PaymentFulfilment fulfilment : fulfilments) {
            d = fulfilment.describe(f.orgId(), f.orderId());
            if (d != null) break;
        }
        String what =
                d == null || d.items().isEmpty() ? f.business() : d.items().getFirst();
        String lang = language == null || language.isBlank() ? "en" : language;
        return payments.start(
                f.orgId(),
                f.orderId(),
                new PaymentsService.Target(f.scheduleId(), f.linkExpires()),
                new Payments.Payer(
                        null,
                        customer == null ? null : customer.value1(),
                        customer == null ? null : customer.value2(),
                        lang),
                urls,
                "Beavent · " + what + (d == null ? "" : " · " + d.reference()),
                now);
    }

    @Override
    public Info check(String token, Instant now) {
        Found f = find(token);
        if (f == null) return null;
        if (!stateOf(f, now).equals("PAID")) payments.check(f.orgId(), f.orderId(), now);
        return info(token, now);
    }

    @Override
    public Info tender(String token, String code, Instant now) {
        Found f = find(token);
        if (f == null) throw new ApiException(ProblemCode.NOT_FOUND, "This link is not valid.");
        String state = stateOf(f, now);
        if (state.equals("PAID")) throw new ApiException(ProblemCode.CONFLICT, "This has already been paid.");
        if (!state.equals("OPEN")) throw new ApiException(ProblemCode.HOLD_EXPIRED, "This link is no longer open.");
        payments.tender(f.orgId(), f.orderId(), StoredValue.Kind.GIFT_CARD, code, null, now);
        return info(token, now);
    }

    /** The address a customer opens. */
    String addressOf(String token) {
        return webUrl + "/pay/" + token;
    }
}
