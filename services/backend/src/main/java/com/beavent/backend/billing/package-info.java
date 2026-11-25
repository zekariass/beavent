/**
 * Billing (plan Stage 3, step 3.2): the platform's fees on orders (docs/04 §11). Rules come from
 * {@code billing.fee_rules} (an organization's own rule beats the market default); every fee charged is recorded in
 * {@code billing.fee_assessments}. Buyer-paid fees appear on the order as {@code BUYER_FEE} lines so the buyer sees
 * them before paying; seller-paid fees are netted (ArifPay mode) or taken by the split (Chapa mode).
 *
 * <p>Public API: {@link com.beavent.backend.billing.Fees} and the pure {@link com.beavent.backend.billing.FeeEngine}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Billing")
package com.beavent.backend.billing;
