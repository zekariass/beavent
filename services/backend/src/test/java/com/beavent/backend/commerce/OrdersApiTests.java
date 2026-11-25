package com.beavent.backend.commerce;

import static com.beavent.db.billing.Tables.FEE_ASSESSMENTS;
import static com.beavent.db.commerce.Tables.ORDERS;
import static com.beavent.db.commerce.Tables.ORDER_LINES;
import static com.beavent.db.commerce.Tables.ORDER_LINE_TAXES;
import static com.beavent.db.commerce.Tables.PROMOTIONS;
import static com.beavent.db.commerce.Tables.PROMO_CODES;
import static com.beavent.db.commerce.Tables.REDEMPTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beavent.backend.billing.FeeEngine;
import com.beavent.backend.billing.Fees;
import com.beavent.backend.commerce.Orders.Basket;
import com.beavent.backend.commerce.Orders.LineInput;
import com.beavent.backend.commerce.Orders.Placed;
import com.beavent.backend.commerce.Orders.Quote;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.RequestContext;
import com.beavent.backend.scheduling.SchedulingTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 3.2 against PostGIS: orders priced and placed with tax snapshots, fees, promotions and gapless numbers, under
 * the database's own arithmetic checks (line totals, header totals, immutable tax rows).
 */
class OrdersApiTests extends SchedulingTestSupport {

    @Autowired
    Orders orders;

    @Autowired
    Fees fees;

    @Autowired
    TransactionTemplate transactions;

    private static final Instant NOW = Instant.parse("2026-12-01T08:00:00Z");

    private UUID market() {
        return (UUID) system.fetchValue("select id from ref.markets where code = 'ET'");
    }

    /** Runs the work in one transaction with the organization's context, as a request would. */
    private <T> T asOrg(UUID org, Callable<T> work) {
        return RequestContext.forOrganization(null, org)
                .call(() -> transactions.execute(status -> {
                    try {
                        return work.call();
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
    }

    private Basket basket(Shop shop, String code, long unit, int quantity) {
        UUID location = shop.salon().location1();
        return new Basket(
                location,
                null,
                null,
                "PARTNER_POS",
                "ETB",
                List.of(new LineInput(
                        "SERVICE", UUID.fromString(shop.variant()), null, null, null, "Haircut", quantity, unit)),
                code,
                NOW.plusSeconds(3 * 86_400),
                NOW);
    }

    private void fee(UUID org, String code, String payer, int bps, String appliesTo) {
        system.execute(
                "insert into billing.fee_rules (market_id, org_id, fee_code, payer, applies_to, percent_bps, currency_code, "
                        + "vat_applies, valid_during) values (?::uuid, ?::uuid, ?, ?, array[?], ?, 'ETB', true, "
                        + "tstzrange('2026-01-01', null))",
                market(),
                org,
                code,
                payer,
                appliesTo,
                bps);
    }

    private UUID promotion(
            UUID org, String kind, String percent, Long amount, String eligibility, Integer maxRedemptions) {
        return (UUID) system.fetchValue(
                "insert into commerce.promotions (org_id, market_id, name, kind, percent_off, amount_minor, currency_code, "
                        + "eligibility, valid_during, max_redemptions) values (?::uuid, ?::uuid, 'Test offer', ?, ?::numeric, ?::bigint, "
                        + "'ETB', ?, tstzrange('2026-01-01', '2027-12-31'), ?::int) returning id",
                org,
                market(),
                kind,
                percent,
                amount,
                eligibility,
                maxRedemptions);
    }

    private void code(UUID org, UUID promotion, String code, Integer maxUses, String validDuring) {
        system.execute(
                "insert into commerce.promo_codes (org_id, promotion_id, code, kind, max_uses, valid_during) "
                        + "values (?::uuid, ?::uuid, ?, 'DISCOUNT', ?::int, ?::tstzrange)",
                org,
                promotion,
                code,
                maxUses,
                validDuring);
    }

    @Test
    void anOrderIsPlacedWithTheTaxInsideItsPriceAndASnapshotOfTheRate() throws Exception {
        Shop shop = shop();
        UUID org = shop.salon().id();
        Placed placed = asOrg(org, () -> orders.place(org, basket(shop, null, 180_000, 1), "CONFIRMED", null, null));
        // A turnover-tax seller: services at 10 %, inside the all-in price (docs/04 §12; rates unverified, D7).
        assertThat(placed.quote().totalMinor()).isEqualTo(180_000);
        assertThat(placed.quote().taxMinor()).isEqualTo(16_364); // 180,000 × 0.10 / 1.10
        assertThat(placed.quote().pricesIncludeTax()).isTrue();
        assertThat(placed.number()).isEqualTo("0000001");

        var order =
                system.selectFrom(ORDERS).where(ORDERS.ID.eq(placed.orderId())).fetchSingle();
        assertThat(order.getTotalMinor()).isEqualTo(180_000);
        assertThat(order.getStatus()).isEqualTo("CONFIRMED");
        assertThat(order.getPaymentStatus()).isEqualTo("UNPAID");
        assertThat(order.getLocationId()).isEqualTo(shop.salon().location1());
        var line = system.selectFrom(ORDER_LINES)
                .where(ORDER_LINES.ORDER_ID.eq(placed.orderId()))
                .fetchSingle();
        assertThat(line.getLineType()).isEqualTo("SERVICE");
        assertThat(line.getTaxInclusive()).isTrue();
        assertThat(line.getTaxCategoryCode()).isEqualTo("SERVICE");
        var tax = system.selectFrom(ORDER_LINE_TAXES)
                .where(ORDER_LINE_TAXES.ORDER_LINE_ID.eq(line.getId()))
                .fetchSingle();
        assertThat(tax.getTaxCode()).isEqualTo("TOT");
        assertThat(tax.getRate()).isEqualByComparingTo("0.1000");
        assertThat(tax.getTaxableMinor() + tax.getTaxMinor()).isEqualTo(180_000);
        // The tax snapshot is immutable (never recalculated from today's rates).
        assertThatThrownBy(() -> system.update(ORDER_LINE_TAXES)
                        .set(ORDER_LINE_TAXES.TAX_MINOR, 1L)
                        .where(ORDER_LINE_TAXES.ID.eq(tax.getId()))
                        .execute())
                .isInstanceOf(Exception.class);
        assertThat(system.fetchCount(
                        com.beavent.db.commerce.Tables.ORDER_STATUS_HISTORY,
                        com.beavent.db.commerce.Tables.ORDER_STATUS_HISTORY.ORDER_ID.eq(placed.orderId())))
                .isEqualTo(1);
    }

    @Test
    void aVatSellerChargesVatAndOrderNumbersAreGaplessPerLegalEntity() throws Exception {
        Shop shop = shop();
        UUID org = shop.salon().id();
        system.execute(
                "update tenant.legal_entities set tax_regime = 'VAT', vat_number = '0012345678' where org_id = ?::uuid",
                org);
        Placed first = asOrg(org, () -> orders.place(org, basket(shop, null, 115_000, 1), "CONFIRMED", null, null));
        Placed second = asOrg(
                org,
                () -> orders.place(org, basket(shop, null, 50_000, 2), "PENDING_PAYMENT", NOW.plusSeconds(900), null));
        assertThat(first.quote().taxMinor()).isEqualTo(15_000); // 115,000 × 0.15 / 1.15
        assertThat(second.quote().taxMinor()).isEqualTo(13_043); // 100,000 × 0.15 / 1.15 = 13,043.48
        assertThat(List.of(first.number(), second.number())).containsExactly("0000001", "0000002");
        assertThat(system.select(ORDERS.STATUS)
                        .from(ORDERS)
                        .where(ORDERS.ID.eq(second.orderId()))
                        .fetchSingle(ORDERS.STATUS))
                .isEqualTo("PENDING_PAYMENT");
        // Another business starts its own sequence.
        Shop other = shop();
        UUID otherOrg = other.salon().id();
        assertThat(asOrg(otherOrg, () -> orders.place(otherOrg, basket(other, null, 1_000, 1), "CONFIRMED", null, null))
                        .number())
                .isEqualTo("0000001");
    }

    @Test
    void buyerFeesAreALineOnTheOrderAndSellerFeesAreRecordedForPayout() throws Exception {
        Shop shop = shop();
        UUID org = shop.salon().id();
        fee(org, "BOOKING_FEE", "BUYER", 500, "SERVICE");
        fee(org, "PAYMENT_PROCESSING", "SELLER", 100, "SERVICE");
        Quote quote = asOrg(org, () -> orders.quote(org, basket(shop, null, 180_000, 1)));
        // 5 % of 180,000 = 9,000 and the platform's 15 % VAT on it = 1,350.
        assertThat(quote.buyerFeesMinor()).isEqualTo(10_350);
        assertThat(quote.totalMinor()).isEqualTo(190_350);
        assertThat(quote.sellerFeesMinor()).isEqualTo(2_070); // 1,800 + 270 VAT, not on the buyer's order

        Placed placed = asOrg(org, () -> orders.place(org, basket(shop, null, 180_000, 1), "CONFIRMED", null, null));
        assertThat(placed.quote().totalMinor()).isEqualTo(190_350);
        var feeLine = system.selectFrom(ORDER_LINES)
                .where(ORDER_LINES.ORDER_ID.eq(placed.orderId()).and(ORDER_LINES.LINE_TYPE.eq("BUYER_FEE")))
                .fetchSingle();
        assertThat(feeLine.getTotalMinor()).isEqualTo(10_350);
        assertThat(feeLine.getParentLineId()).isNotNull();
        var assessments = system.selectFrom(FEE_ASSESSMENTS)
                .where(FEE_ASSESSMENTS.ORDER_ID.eq(placed.orderId()))
                .fetch();
        assertThat(assessments).hasSize(2);
        var buyer = assessments.stream()
                .filter(a -> a.getPayer().equals("BUYER"))
                .findFirst()
                .orElseThrow();
        assertThat(buyer.getOrderLineId()).isEqualTo(feeLine.getId());
        assertThat(buyer.getBaseMinor()).isEqualTo(180_000);
        assertThat(buyer.getAmountMinor()).isEqualTo(9_000);
        assertThat(buyer.getVatMinor()).isEqualTo(1_350);
        assertThat(buyer.getStatus()).isEqualTo("ASSESSED");
        var seller = assessments.stream()
                .filter(a -> a.getPayer().equals("SELLER"))
                .findFirst()
                .orElseThrow();
        assertThat(seller.getAmountMinor() + seller.getVatMinor()).isEqualTo(2_070);
    }

    @Test
    void anOrganizationsOwnFeeRuleBeatsTheMarketDefault() throws Exception {
        Shop shop = shop();
        UUID org = shop.salon().id();
        system.execute(
                "insert into billing.fee_rules (market_id, fee_code, payer, applies_to, percent_bps, currency_code, valid_during) "
                        + "select ?::uuid, 'TICKET_SERVICE_FEE', 'BUYER', array['TICKET'], 300, 'ETB', tstzrange('2026-01-01', null) "
                        + "where not exists (select 1 from billing.fee_rules where market_id = ?::uuid and org_id is null "
                        + "and fee_code = 'TICKET_SERVICE_FEE' and payer = 'BUYER' and status = 'ACTIVE')",
                market(),
                market());
        Shop other = shop();
        List<FeeEngine.Rule> before = asOrg(org, () -> fees.orderRules(org, "ETB", NOW));
        assertThat(before)
                .filteredOn(r -> r.feeCode().equals("TICKET_SERVICE_FEE"))
                .singleElement()
                .satisfies(r -> assertThat(r.percentBps()).isEqualTo(300));
        fee(org, "TICKET_SERVICE_FEE", "BUYER", 800, "TICKET");
        assertThat(asOrg(org, () -> fees.orderRules(org, "ETB", NOW)))
                .filteredOn(r -> r.feeCode().equals("TICKET_SERVICE_FEE"))
                .singleElement()
                .satisfies(r -> assertThat(r.percentBps()).isEqualTo(800));
        UUID otherOrg = other.salon().id();
        assertThat(asOrg(otherOrg, () -> fees.orderRules(otherOrg, "ETB", NOW)))
                .filteredOn(r -> r.feeCode().equals("TICKET_SERVICE_FEE"))
                .singleElement()
                .satisfies(r -> assertThat(r.percentBps()).isEqualTo(300));
    }

    @Test
    void aPromoCodeDiscountsBeforeTaxIsCountedOnPlacingAndRunsOut() throws Exception {
        Shop shop = shop();
        UUID org = shop.salon().id();
        UUID tenOff = promotion(org, "PERCENT_OFF", "10", null, "EVERYONE", null);
        code(org, tenOff, "EID10", 1, null);

        Quote quote = asOrg(org, () -> orders.quote(org, basket(shop, "eid10", 180_000, 1)));
        assertThat(quote.promo().status()).isEqualTo("APPLIED");
        assertThat(quote.promo().code()).isEqualTo("EID10");
        assertThat(quote.promo().discountMinor()).isEqualTo(18_000);
        assertThat(quote.discountMinor()).isEqualTo(18_000);
        assertThat(quote.totalMinor()).isEqualTo(162_000);
        assertThat(quote.taxMinor()).isEqualTo(14_727); // 162,000 × 0.10 / 1.10

        Placed placed = asOrg(org, () -> orders.place(org, basket(shop, "EID10", 180_000, 1), "CONFIRMED", null, null));
        assertThat(placed.quote().totalMinor()).isEqualTo(162_000);
        var redemption = system.selectFrom(REDEMPTIONS)
                .where(REDEMPTIONS.ORDER_ID.eq(placed.orderId()))
                .fetchSingle();
        assertThat(redemption.getDiscountMinor()).isEqualTo(18_000);
        assertThat(redemption.getPlatformFundedMinor()).isZero();
        assertThat(system.selectFrom(PROMO_CODES)
                        .where(PROMO_CODES.PROMOTION_ID.eq(tenOff))
                        .fetchSingle()
                        .getUseCount())
                .isEqualTo(1);
        assertThat(system.selectFrom(PROMOTIONS)
                        .where(PROMOTIONS.ID.eq(tenOff))
                        .fetchSingle()
                        .getRedemptionCount())
                .isEqualTo(1);

        // The only use is gone: shown as used up, and placing with it is refused rather than priced differently.
        assertThat(asOrg(org, () -> orders.quote(org, basket(shop, "EID10", 180_000, 1)))
                        .promo()
                        .status())
                .isEqualTo("USED_UP");
        assertThatThrownBy(() ->
                        asOrg(org, () -> orders.place(org, basket(shop, "EID10", 180_000, 1), "CONFIRMED", null, null)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code().name()).isEqualTo("CONFLICT"));
    }

    @Test
    void codesThatDoNotApplyAreSaidSoAndNeverChangeThePrice() throws Exception {
        Shop shop = shop();
        UUID org = shop.salon().id();
        UUID everyone = promotion(org, "PERCENT_OFF", "10", null, "EVERYONE", null);
        code(org, everyone, "OLDCODE", null, "[2026-01-01,2026-06-30)");
        UUID returning = promotion(org, "PERCENT_OFF", "20", null, "RETURNING_CUSTOMERS", null);
        code(org, returning, "WELCOMEBACK", null, null);
        UUID tooBig = promotion(org, "PERCENT_OFF", "10", null, "EVERYONE", null);
        system.execute("update commerce.promotions set min_order_minor = 999999999 where id = ?::uuid", tooBig);
        code(org, tooBig, "BIGSPENDER", null, null);

        for (String[] c : new String[][] {
            {"NOSUCHCODE", "UNKNOWN"},
            {"OLDCODE", "EXPIRED"},
            {"WELCOMEBACK", "NOT_ELIGIBLE"},
            {"BIGSPENDER", "NOT_APPLICABLE"}
        }) {
            Quote quote = asOrg(org, () -> orders.quote(org, basket(shop, c[0], 180_000, 1)));
            assertThat(quote.promo().status()).as(c[0]).isEqualTo(c[1]);
            assertThat(quote.totalMinor()).as(c[0]).isEqualTo(180_000);
            assertThat(quote.discountMinor()).as(c[0]).isZero();
        }
    }

    @Test
    void anAutomaticOffOnlyAppliesInsideItsTimeWindow() throws Exception {
        Shop shop = shop();
        UUID org = shop.salon().id();
        UUID happyHour = promotion(org, "PERCENT_OFF", "20", null, "EVERYONE", null);
        // Tuesdays to Thursdays, 10:00-14:00 in Addis Ababa; applies by itself.
        system.execute(
                "update commerce.promotions set auto_apply = true, time_windows = "
                        + "'[{\"iso_weekdays\":[2,3,4],\"from\":\"10:00\",\"to\":\"14:00\"}]'::jsonb where id = ?::uuid",
                happyHour);
        // 2026-12-01 is a Tuesday: 08:00 UTC is 11:00 in Addis Ababa, inside; 03:00 UTC (06:00) is outside; no start,
        // none.
        Basket inside = basket(shop, null, 100_000, 1);
        assertThat(asOrg(org, () -> orders.quote(org, withStart(inside, Instant.parse("2026-12-01T08:00:00Z"))))
                        .discountMinor())
                .isEqualTo(20_000);
        assertThat(asOrg(org, () -> orders.quote(org, withStart(inside, Instant.parse("2026-12-01T03:00:00Z"))))
                        .discountMinor())
                .isZero();
        assertThat(asOrg(org, () -> orders.quote(org, withStart(inside, Instant.parse("2026-12-02T08:00:00Z"))))
                        .discountMinor())
                .as("Wednesday")
                .isEqualTo(20_000);
        assertThat(asOrg(org, () -> orders.quote(org, withStart(inside, Instant.parse("2026-12-05T08:00:00Z"))))
                        .discountMinor())
                .as("Saturday")
                .isZero();
        assertThat(asOrg(org, () -> orders.quote(org, withStart(inside, null))).discountMinor())
                .isZero();
    }

    private static Basket withStart(Basket b, Instant start) {
        return new Basket(
                b.locationId(),
                b.customerId(),
                b.buyerUserId(),
                b.channel(),
                b.currency(),
                b.lines(),
                b.promoCode(),
                start,
                b.at());
    }

    @Test
    void twoBuyersRacingForTheLastUseOfACodeOneWinsAndTheLoserLeavesNothingBehind() throws Exception {
        Shop shop = shop();
        UUID org = shop.salon().id();
        UUID offer = promotion(org, "PERCENT_OFF", "10", null, "EVERYONE", null);
        code(org, offer, "LASTONE", 1, null);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        asOrg(
                                org,
                                () -> orders.place(org, basket(shop, "LASTONE", 100_000, 1), "CONFIRMED", null, null));
                        return "placed";
                    } catch (ApiException e) {
                        return e.code().name();
                    }
                }));
            }
            ready.await();
            go.countDown();
            List<String> outcomes = new java.util.ArrayList<>();
            for (Future<String> f : results) outcomes.add(f.get());
            assertThat(outcomes).containsExactlyInAnyOrder("placed", "CONFLICT");
        } finally {
            pool.shutdownNow();
        }
        assertThat(system.fetchCount(
                        REDEMPTIONS, REDEMPTIONS.PROMO_CODE_ID.isNotNull().and(REDEMPTIONS.ORG_ID.eq(org))))
                .isEqualTo(1);
        assertThat(system.selectFrom(PROMO_CODES)
                        .where(PROMO_CODES.PROMOTION_ID.eq(offer))
                        .fetchSingle()
                        .getUseCount())
                .isEqualTo(1);
        assertThat(system.selectFrom(PROMOTIONS)
                        .where(PROMOTIONS.ID.eq(offer))
                        .fetchSingle()
                        .getRedemptionCount())
                .isEqualTo(1);
        // The loser's order number went back with its rolled-back transaction: numbers stay gapless.
        assertThat(system.select(ORDERS.NUMBER)
                        .from(ORDERS)
                        .where(ORDERS.ORG_ID.eq(org))
                        .orderBy(ORDERS.NUMBER)
                        .fetch(ORDERS.NUMBER))
                .containsExactly("0000001");
        Placed next = asOrg(org, () -> orders.place(org, basket(shop, null, 1_000, 1), "CONFIRMED", null, null));
        assertThat(next.number()).isEqualTo("0000002");
    }

    @Test
    void anEmptyBasketAndBadPricesAreRefused() throws Exception {
        Shop shop = shop();
        UUID org = shop.salon().id();
        Basket empty =
                new Basket(shop.salon().location1(), null, null, "PARTNER_POS", "ETB", List.of(), null, null, NOW);
        assertThatThrownBy(() -> asOrg(org, () -> orders.quote(org, empty)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code().name()).isEqualTo("VALIDATION_FAILED"));
        assertThatThrownBy(() -> asOrg(org, () -> orders.quote(org, basket(shop, null, -5, 1))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code().name()).isEqualTo("VALIDATION_FAILED"));
        // Only another business's location is "unknown" to this one: tenants cannot price each other's places.
        Shop other = shop();
        assertThatThrownBy(() -> asOrg(org, () -> orders.quote(org, basket(other, null, 1_000, 1))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code().name()).isEqualTo("NOT_FOUND"));
    }
}
