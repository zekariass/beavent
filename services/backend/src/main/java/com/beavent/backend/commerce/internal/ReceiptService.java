package com.beavent.backend.commerce.internal;

import static com.beavent.db.commerce.Tables.DOCUMENT_SEQUENCES;
import static com.beavent.db.commerce.Tables.ORDERS;
import static com.beavent.db.commerce.Tables.ORDER_LINES;
import static com.beavent.db.commerce.Tables.ORDER_LINE_TAXES;
import static com.beavent.db.commerce.Tables.RECEIPTS;
import static com.beavent.db.crm.Tables.CUSTOMERS;
import static com.beavent.db.payments.Tables.METHODS;
import static com.beavent.db.payments.Tables.PAYMENT_ATTEMPTS;
import static com.beavent.db.payments.Tables.PAYMENT_INTENTS;
import static com.beavent.db.tenant.Tables.LEGAL_ENTITIES;
import static com.beavent.db.tenant.Tables.LOCATIONS;
import static com.beavent.db.tenant.Tables.PLACES;

import com.beavent.backend.commerce.Receipts;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;

/**
 * {@link Receipts}: the next gapless number of the order's legal entity, taken in the transaction that issues it; the
 * receipt as it is shown and printed; and the link that opens it without signing in (only its hash is kept).
 */
@Service
class ReceiptService implements Receipts {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DSLContext dsl;
    private final DSLContext system;
    private final Outbox outbox;

    ReceiptService(DSLContext dsl, @SystemDb DSLContext system, Outbox outbox) {
        this.dsl = dsl;
        this.system = system;
        this.outbox = outbox;
    }

    @Override
    public Receipt issue(UUID orgId, UUID orderId, Instant now) {
        var order = dsl.select(ORDERS.LEGAL_ENTITY_ID, ORDERS.PAYMENT_STATUS)
                .from(ORDERS)
                .where(ORDERS.ID.eq(orderId), ORDERS.ORG_ID.eq(orgId))
                .forUpdate()
                .fetchOne();
        if (order == null) throw new ApiException(ProblemCode.NOT_FOUND, "There is no such order.");
        Receipt existing = find(orgId, orderId);
        if (existing != null) return existing;
        UUID legalEntity = order.value1();
        dsl.insertInto(DOCUMENT_SEQUENCES)
                .set(DOCUMENT_SEQUENCES.ORG_ID, orgId)
                .set(DOCUMENT_SEQUENCES.LEGAL_ENTITY_ID, legalEntity)
                .set(DOCUMENT_SEQUENCES.DOC_TYPE, "RECEIPT")
                .set(DOCUMENT_SEQUENCES.PREFIX, "R")
                .onConflictDoNothing()
                .execute();
        String number = dsl.fetchValue(
                DSL.field("commerce.next_document_number({0}, {1})", String.class, legalEntity, DSL.inline("RECEIPT")));
        UUID id = Ids.newId();
        dsl.insertInto(RECEIPTS)
                .set(RECEIPTS.ID, id)
                .set(RECEIPTS.ORG_ID, orgId)
                .set(RECEIPTS.LEGAL_ENTITY_ID, legalEntity)
                .set(RECEIPTS.ORDER_ID, orderId)
                .set(RECEIPTS.NUMBER, number)
                .set(RECEIPTS.ISSUED_AT, now.atOffset(ZoneOffset.UTC))
                .execute();
        return new Receipt(id, orderId, number, now);
    }

    @Override
    public Receipt find(UUID orgId, UUID orderId) {
        return dsl.selectFrom(RECEIPTS)
                .where(RECEIPTS.ORDER_ID.eq(orderId), RECEIPTS.ORG_ID.eq(orgId))
                .fetchOptional(r -> new Receipt(
                        r.getId(),
                        r.getOrderId(),
                        r.getNumber(),
                        r.getIssuedAt().toInstant()))
                .orElse(null);
    }

    @Override
    public View view(UUID orgId, UUID orderId) {
        var receipt = dsl.selectFrom(RECEIPTS)
                .where(RECEIPTS.ORDER_ID.eq(orderId), RECEIPTS.ORG_ID.eq(orgId))
                .fetchOne();
        if (receipt == null) throw new ApiException(ProblemCode.NOT_FOUND, "There is no receipt for this order.");
        return build(dsl, receipt);
    }

    @Override
    public View byToken(String token) {
        if (token == null || token.length() < 20 || token.length() > 100) return null;
        var receipt = system.selectFrom(RECEIPTS)
                .where(RECEIPTS.ACCESS_HASH.eq(hash(token)))
                .fetchOne();
        return receipt == null ? null : build(system, receipt);
    }

    @Override
    public Sent send(UUID orgId, UUID orderId, UUID byUserId, Instant now) {
        var receipt = dsl.selectFrom(RECEIPTS)
                .where(RECEIPTS.ORDER_ID.eq(orderId), RECEIPTS.ORG_ID.eq(orgId))
                .forUpdate()
                .fetchOne();
        if (receipt == null) throw new ApiException(ProblemCode.NOT_FOUND, "There is no receipt for this order.");
        var customer = dsl.select(CUSTOMERS.ID, CUSTOMERS.PHONE_E164, CUSTOMERS.EMAIL)
                .from(ORDERS)
                .join(CUSTOMERS)
                .on(CUSTOMERS.ID.eq(ORDERS.CUSTOMER_ID))
                .where(ORDERS.ID.eq(orderId))
                .fetchOne();
        String via = customer == null
                ? null
                : customer.value2() != null ? "SMS" : customer.value3() != null ? "EMAIL" : null;
        if (via == null) {
            throw new ApiException(ProblemCode.CONFLICT, "This customer has no phone number or email to send it to.");
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        View view = build(dsl, receipt);
        dsl.update(RECEIPTS)
                .set(RECEIPTS.ACCESS_HASH, hash(token))
                .set(
                        RECEIPTS.DELIVERED_VIA,
                        DSL.field(
                                "array_append(array_remove({0}, {1}), {1})",
                                String[].class, RECEIPTS.DELIVERED_VIA, via))
                .where(RECEIPTS.ID.eq(receipt.getId()))
                .execute();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("receiptId", receipt.getId());
        data.put("orderId", orderId);
        data.put("customerId", customer.value1());
        data.put("number", view.number());
        data.put("business", view.business());
        data.put("totalMinor", view.totalMinor());
        data.put("currency", view.currency());
        data.put("issuedAt", view.issuedAt().toString());
        data.put("timezone", view.timezone());
        data.put("token", token);
        outbox.publish(new DomainEvent("commerce.receipt", receipt.getId(), "receipt.sent", 1, orgId, data));
        return new Sent(via);
    }

    // --- the receipt as shown ----------------------------------------------------------------------------------------

    private View build(DSLContext db, com.beavent.db.commerce.tables.records.ReceiptsRecord receipt) {
        var order =
                db.selectFrom(ORDERS).where(ORDERS.ID.eq(receipt.getOrderId())).fetchSingle();
        var entity = db.selectFrom(LEGAL_ENTITIES)
                .where(LEGAL_ENTITIES.ID.eq(order.getLegalEntityId()))
                .fetchSingle();
        var location = order.getLocationId() == null
                ? null
                : db.selectFrom(LOCATIONS)
                        .where(LOCATIONS.ID.eq(order.getLocationId()))
                        .fetchOne();
        String customer = order.getCustomerId() == null
                ? null
                : db.select(CUSTOMERS.DISPLAY_NAME)
                        .from(CUSTOMERS)
                        .where(CUSTOMERS.ID.eq(order.getCustomerId()))
                        .fetchOne(CUSTOMERS.DISPLAY_NAME);
        List<Line> lines = db.selectFrom(ORDER_LINES)
                .where(ORDER_LINES.ORDER_ID.eq(order.getId()), ORDER_LINES.STATUS.eq("ACTIVE"))
                .orderBy(ORDER_LINES.SEQ)
                .fetch(l -> new Line(
                        l.getLineType(),
                        l.getDescription(),
                        l.getQuantity(),
                        l.getUnitPriceMinor(),
                        l.getDiscountMinor(),
                        l.getTotalMinor()));
        List<Tax> taxes = new ArrayList<>(db.select(
                        ORDER_LINE_TAXES.TAX_CODE,
                        ORDER_LINE_TAXES.RATE,
                        DSL.sum(ORDER_LINE_TAXES.TAXABLE_MINOR),
                        DSL.sum(ORDER_LINE_TAXES.TAX_MINOR))
                .from(ORDER_LINE_TAXES)
                .join(ORDER_LINES)
                .on(ORDER_LINES.ID.eq(ORDER_LINE_TAXES.ORDER_LINE_ID))
                .where(ORDER_LINES.ORDER_ID.eq(order.getId()))
                .groupBy(ORDER_LINE_TAXES.TAX_CODE, ORDER_LINE_TAXES.RATE)
                .fetch(r -> new Tax(
                        r.value1(),
                        r.value2(),
                        r.value3().longValue(),
                        r.value4().longValue())));
        List<Tender> tenders = db.select(
                        PAYMENT_ATTEMPTS.METHOD_CODE,
                        METHODS.NAME,
                        PAYMENT_ATTEMPTS.AMOUNT_MINOR,
                        PAYMENT_ATTEMPTS.COMPLETED_AT)
                .from(PAYMENT_ATTEMPTS)
                .join(PAYMENT_INTENTS)
                .on(PAYMENT_INTENTS.ID.eq(PAYMENT_ATTEMPTS.INTENT_ID))
                .join(METHODS)
                .on(METHODS.CODE.eq(PAYMENT_ATTEMPTS.METHOD_CODE))
                .where(PAYMENT_INTENTS.ORDER_ID.eq(order.getId()))
                .and(PAYMENT_ATTEMPTS.STATUS.eq("SUCCEEDED"))
                .orderBy(PAYMENT_ATTEMPTS.COMPLETED_AT, PAYMENT_ATTEMPTS.INITIATED_AT)
                .fetch(r -> new Tender(
                        r.value1(),
                        r.value2(),
                        r.value3(),
                        r.value4() == null ? null : r.value4().toInstant()));
        String landmark = location == null
                ? null
                : db.select(PLACES.LANDMARK)
                        .from(PLACES)
                        .where(PLACES.ID.eq(location.getPlaceId()))
                        .fetchOne(PLACES.LANDMARK);
        long tax = lines.isEmpty() ? 0 : taxes.stream().mapToLong(Tax::taxMinor).sum();
        return new View(
                receipt.getNumber(),
                receipt.getIssuedAt().toInstant(),
                order.getNumber(),
                order.getCurrencyCode().trim(),
                location == null ? "Africa/Addis_Ababa" : location.getTimezone(),
                entity.getRegisteredName(),
                entity.getTin(),
                location == null ? null : location.getName(),
                landmark,
                location == null ? null : location.getPhone(),
                customer,
                order.getPricesIncludeTax(),
                lines,
                order.getSubtotalMinor(),
                order.getDiscountMinor(),
                tax,
                order.getTotalMinor(),
                taxes,
                tenders);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
