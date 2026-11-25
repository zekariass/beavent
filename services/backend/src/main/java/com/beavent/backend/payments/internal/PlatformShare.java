package com.beavent.backend.payments.internal;

import static com.beavent.db.billing.Tables.FEE_ASSESSMENTS;
import static com.beavent.db.commerce.Tables.ORDERS;

import com.beavent.backend.platform.MoneyMath;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * The platform's share of one payment of an order: the fees assessed on the order, with their VAT, in proportion to
 * the part of the order this payment covers. Used to split a Chapa payment at source and to take the fees out of a
 * seller's balance when Beavent collects (docs/04 §10).
 */
record PlatformShare(long feeMinor, long vatMinor) {

    static final PlatformShare NONE = new PlatformShare(0, 0);

    long totalMinor() {
        return feeMinor + vatMinor;
    }

    static PlatformShare of(DSLContext system, UUID orderId, long paymentMinor) {
        var fees = system.select(
                        DSL.coalesce(DSL.sum(FEE_ASSESSMENTS.AMOUNT_MINOR), java.math.BigDecimal.ZERO),
                        DSL.coalesce(DSL.sum(FEE_ASSESSMENTS.VAT_MINOR), java.math.BigDecimal.ZERO))
                .from(FEE_ASSESSMENTS)
                .where(FEE_ASSESSMENTS.ORDER_ID.eq(orderId), FEE_ASSESSMENTS.STATUS.eq("ASSESSED"))
                .fetchOne();
        Long orderTotal = system.select(ORDERS.TOTAL_MINOR)
                .from(ORDERS)
                .where(ORDERS.ID.eq(orderId))
                .fetchOne(ORDERS.TOTAL_MINOR);
        if (fees == null || orderTotal == null || orderTotal <= 0) return NONE;
        long fee = fees.value1().longValueExact();
        long vat = fees.value2().longValueExact();
        long all = Math.min(fee + vat, orderTotal);
        if (all <= 0) return NONE;
        // This payment's part of the fees, then the part of that which is VAT.
        long share = MoneyMath.allocate(paymentMinor, new long[] {all, orderTotal - all})[0];
        long shareVat = all == 0 ? 0 : MoneyMath.allocate(share, new long[] {vat, fee})[0];
        return new PlatformShare(share - shareVat, shareVat);
    }
}
