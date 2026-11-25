package com.beavent.backend.billing.internal;

import static com.beavent.db.billing.Tables.FEE_ASSESSMENTS;
import static com.beavent.db.billing.Tables.FEE_RULES;
import static com.beavent.db.ref.Tables.MARKETS;
import static com.beavent.db.ref.Tables.TAX_RATES;
import static com.beavent.db.tenant.Tables.ORGANIZATIONS;

import com.beavent.backend.billing.FeeEngine;
import com.beavent.backend.billing.Fees;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/** {@link Fees} over {@code billing.fee_rules} and {@code billing.fee_assessments} (docs/04 §11). */
@Component
class FeeRules implements Fees {

    /** Charged on orders when placed; the new-client commission waits for a completed visit (docs/04 §11). */
    private static final Set<String> ORDER_FEES = Set.of("BOOKING_FEE", "TICKET_SERVICE_FEE", "PAYMENT_PROCESSING");

    private final DSLContext dsl;

    FeeRules(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<FeeEngine.Rule> orderRules(UUID orgId, String currency, Instant at) {
        UUID market = market(orgId);
        OffsetDateTime when = at.atOffset(ZoneOffset.UTC);
        Map<String, FeeEngine.Rule> chosen = new LinkedHashMap<>();
        Map<String, Boolean> ownRule = new LinkedHashMap<>();
        for (var r : dsl.selectFrom(FEE_RULES)
                .where(FEE_RULES.MARKET_ID.eq(market))
                .and(FEE_RULES.STATUS.eq("ACTIVE"))
                .and(FEE_RULES.CURRENCY_CODE.eq(currency))
                .and(FEE_RULES.FEE_CODE.in(ORDER_FEES))
                .and(FEE_RULES.ORG_ID.eq(orgId).or(FEE_RULES.ORG_ID.isNull()))
                .and(FEE_RULES.PLAN_ID.isNull())
                .and(DSL.condition("{0} @> {1}::timestamptz", FEE_RULES.VALID_DURING, when))
                .orderBy(FEE_RULES.CREATED_AT)
                .fetch()) {
            String key = r.getFeeCode() + "/" + r.getPayer();
            boolean own = r.getOrgId() != null;
            if (chosen.containsKey(key) && ownRule.get(key) && !own) continue;
            chosen.put(
                    key,
                    new FeeEngine.Rule(
                            r.getId(),
                            r.getFeeCode(),
                            r.getPayer(),
                            Set.of(r.getAppliesTo()),
                            r.getPercentBps(),
                            r.getFixedMinor(),
                            r.getMinMinor(),
                            r.getMaxMinor(),
                            r.getVatApplies()));
            ownRule.put(key, own);
        }
        return new ArrayList<>(chosen.values());
    }

    @Override
    public BigDecimal platformVatRate(UUID orgId, Instant at) {
        String country = dsl.select(MARKETS.COUNTRY_CODE)
                .from(ORGANIZATIONS)
                .join(MARKETS)
                .on(MARKETS.ID.eq(ORGANIZATIONS.HOME_MARKET_ID))
                .where(ORGANIZATIONS.ID.eq(orgId))
                .fetchSingle(MARKETS.COUNTRY_CODE);
        LocalDate day = at.atOffset(ZoneOffset.UTC).toLocalDate();
        BigDecimal rate = dsl.select(TAX_RATES.RATE)
                .from(TAX_RATES)
                .where(TAX_RATES.COUNTRY_CODE.eq(country))
                .and(TAX_RATES.TAX_CODE.eq("VAT"))
                .and(TAX_RATES.TAX_CATEGORY_CODE.eq("PLATFORM_FEE"))
                .and(TAX_RATES.SELLER_REGIME.in("VAT", "ANY"))
                .and(DSL.condition("{0} @> {1}::date", TAX_RATES.VALID_DURING, day))
                .limit(1)
                .fetchOne(TAX_RATES.RATE);
        return rate == null ? BigDecimal.ZERO : rate;
    }

    @Override
    public void record(UUID orgId, UUID orderId, String currency, List<Charged> fees) {
        for (Charged c : fees) {
            FeeEngine.Fee fee = c.fee();
            dsl.insertInto(FEE_ASSESSMENTS)
                    .set(FEE_ASSESSMENTS.ORG_ID, orgId)
                    .set(FEE_ASSESSMENTS.FEE_RULE_ID, fee.ruleId())
                    .set(FEE_ASSESSMENTS.FEE_CODE, fee.feeCode())
                    .set(FEE_ASSESSMENTS.PAYER, fee.payer())
                    .set(FEE_ASSESSMENTS.ORDER_ID, orderId)
                    .set(FEE_ASSESSMENTS.ORDER_LINE_ID, c.feeLineId() != null ? c.feeLineId() : c.baseLineId())
                    .set(FEE_ASSESSMENTS.BASE_MINOR, fee.baseMinor())
                    .set(FEE_ASSESSMENTS.AMOUNT_MINOR, fee.amountMinor())
                    .set(FEE_ASSESSMENTS.VAT_MINOR, fee.vatMinor())
                    .set(FEE_ASSESSMENTS.CURRENCY_CODE, currency)
                    .execute();
        }
    }

    private UUID market(UUID orgId) {
        return dsl.select(ORGANIZATIONS.HOME_MARKET_ID)
                .from(ORGANIZATIONS)
                .where(ORGANIZATIONS.ID.eq(orgId))
                .fetchSingle(ORGANIZATIONS.HOME_MARKET_ID);
    }
}
