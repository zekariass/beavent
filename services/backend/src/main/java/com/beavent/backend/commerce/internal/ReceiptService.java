package com.beavent.backend.commerce.internal;

import static com.beavent.db.commerce.Tables.DOCUMENT_SEQUENCES;
import static com.beavent.db.commerce.Tables.ORDERS;
import static com.beavent.db.commerce.Tables.RECEIPTS;

import com.beavent.backend.commerce.Receipts;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.ProblemCode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;

/** {@link Receipts}: the next gapless number of the order's legal entity, taken in the transaction that issues it. */
@Service
class ReceiptService implements Receipts {

    private final DSLContext dsl;

    ReceiptService(DSLContext dsl) {
        this.dsl = dsl;
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
}
