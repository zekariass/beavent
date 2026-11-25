package com.beavent.backend.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The platform's fees on an organization's orders. Call inside a transaction with the organization's context. */
public interface Fees {

    /**
     * The order fee rules in force for the organization at {@code at}, one per fee code and payer: the organization's
     * own rule if it has one, otherwise its market's default. Fees charged elsewhere (the marketplace new-client
     * commission on completion, usage fees) are not included.
     */
    List<FeeEngine.Rule> orderRules(UUID orgId, String currency, Instant at);

    /** The platform's VAT rate on its fees in the organization's market at {@code at}, or zero. */
    BigDecimal platformVatRate(UUID orgId, Instant at);

    /**
     * A fee charged on a placed order.
     *
     * @param baseLineId the line the fee was computed on
     * @param feeLineId the {@code BUYER_FEE} line showing it, for buyer-paid fees; null for seller-paid ones
     */
    record Charged(FeeEngine.Fee fee, UUID baseLineId, UUID feeLineId) {}

    /** Records the fees of a placed order as {@code billing.fee_assessments} ({@code ASSESSED}). */
    void record(UUID orgId, UUID orderId, String currency, List<Charged> fees);
}
