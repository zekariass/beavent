package com.beavent.backend.commerce.internal;

import static com.beavent.db.catalog.Tables.ADDONS;
import static com.beavent.db.catalog.Tables.SERVICES;
import static com.beavent.db.catalog.Tables.SERVICE_VARIANTS;
import static com.beavent.db.commerce.Tables.DOCUMENT_SEQUENCES;
import static com.beavent.db.commerce.Tables.ORDERS;
import static com.beavent.db.commerce.Tables.ORDER_LINES;
import static com.beavent.db.commerce.Tables.ORDER_LINE_TAXES;
import static com.beavent.db.commerce.Tables.ORDER_STATUS_HISTORY;
import static com.beavent.db.commerce.Tables.PROMOTIONS;
import static com.beavent.db.commerce.Tables.PROMOTION_TARGETS;
import static com.beavent.db.commerce.Tables.PROMO_CODES;
import static com.beavent.db.commerce.Tables.REDEMPTIONS;
import static com.beavent.db.ref.Tables.MARKETS;
import static com.beavent.db.ref.Tables.TAX_RATES;
import static com.beavent.db.scheduling.Tables.APPOINTMENTS;
import static com.beavent.db.tenant.Tables.LEGAL_ENTITIES;
import static com.beavent.db.tenant.Tables.LOCATIONS;

import com.beavent.backend.billing.Fees;
import com.beavent.backend.commerce.Orders;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import com.beavent.db.commerce.tables.records.PromotionsRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@link Orders}: loads the facts pricing needs, prices with {@link OrderPricing}, and writes placed orders. */
@Service
class OrdersService implements Orders {

    private final DSLContext dsl;
    private final DSLContext system;
    private final Fees fees;
    private final JsonMapper json;

    OrdersService(DSLContext dsl, @SystemDb DSLContext system, Fees fees, JsonMapper json) {
        this.dsl = dsl;
        this.system = system;
        this.fees = fees;
        this.json = json;
    }

    /** Where the sale happens: the location's legal entity decides the tax; its market whether prices include it. */
    record Seller(
            UUID locationId,
            UUID legalEntityId,
            String country,
            String regime,
            boolean pricesIncludeTax,
            ZoneId zone) {}

    record Priced(
            Seller seller,
            String currency,
            List<OrderPricing.Item> items,
            OrderPricing.Result result,
            PromoResult promo) {}

    @Override
    public Quote quote(UUID orgId, Basket basket) {
        return toQuote(price(orgId, basket));
    }

    @Override
    public Placed place(UUID orgId, Basket basket, String status, Instant expiresAt, UUID actorUserId) {
        if (!status.equals("PENDING_PAYMENT") && !status.equals("CONFIRMED")) {
            throw new IllegalArgumentException("An order is placed PENDING_PAYMENT or CONFIRMED");
        }
        if (status.equals("PENDING_PAYMENT") && expiresAt == null) {
            throw new IllegalArgumentException("A pending order needs expiresAt");
        }
        Priced priced = price(orgId, basket);
        // The buyer saw a price with their code in it: never place at another price without telling them.
        if (priced.promo() != null && !priced.promo().status().equals("APPLIED")) {
            throw new ApiException(
                    ProblemCode.CONFLICT,
                    "That code can't be used any more (" + priced.promo().status() + ").");
        }
        OrderPricing.Result r = priced.result();
        Seller seller = priced.seller();
        String number = nextNumber(orgId, seller.legalEntityId());
        UUID orderId = Ids.newId();
        OffsetDateTime at = basket.at().atOffset(ZoneOffset.UTC);
        dsl.insertInto(ORDERS)
                .set(ORDERS.ID, orderId)
                .set(ORDERS.ORG_ID, orgId)
                .set(ORDERS.LEGAL_ENTITY_ID, seller.legalEntityId())
                .set(ORDERS.LOCATION_ID, seller.locationId())
                .set(ORDERS.NUMBER, number)
                .set(ORDERS.CUSTOMER_ID, basket.customerId())
                .set(ORDERS.BUYER_USER_ID, basket.buyerUserId())
                .set(ORDERS.CHANNEL, basket.channel())
                .set(ORDERS.STATUS, status)
                .set(ORDERS.PAYMENT_STATUS, r.totalMinor() == 0 ? "NOT_REQUIRED" : "UNPAID")
                .set(ORDERS.CURRENCY_CODE, basket.currency())
                .set(ORDERS.PRICES_INCLUDE_TAX, seller.pricesIncludeTax())
                .set(ORDERS.SUBTOTAL_MINOR, r.subtotalMinor())
                .set(ORDERS.DISCOUNT_MINOR, r.discountMinor())
                .set(ORDERS.TAX_MINOR, r.taxMinor())
                .set(ORDERS.TOTAL_MINOR, r.totalMinor())
                .set(ORDERS.EXPIRES_AT, expiresAt == null ? null : expiresAt.atOffset(ZoneOffset.UTC))
                .set(ORDERS.PLACED_AT, at)
                .set(ORDERS.CONFIRMED_AT, status.equals("CONFIRMED") ? at : null)
                .execute();

        Map<Integer, UUID> lineIds = new HashMap<>();
        for (PricedLine line : r.lines()) lineIds.put(line.index(), Ids.newId());
        Map<String, TaxEngine.Rate> rates = sellerRates(seller, basket.at());
        TaxEngine.Rate platformRate = platformFeeRate(seller, basket.at());
        for (PricedLine line : r.lines()) {
            LineInput in = line.index() < basket.lines().size() ? basket.lines().get(line.index()) : null;
            UUID id = lineIds.get(line.index());
            dsl.insertInto(ORDER_LINES)
                    .set(ORDER_LINES.ID, id)
                    .set(ORDER_LINES.ORG_ID, orgId)
                    .set(ORDER_LINES.ORDER_ID, orderId)
                    .set(ORDER_LINES.SEQ, (short) (line.index() + 1))
                    .set(ORDER_LINES.LINE_TYPE, line.type())
                    .set(
                            ORDER_LINES.PARENT_LINE_ID,
                            line.parentIndex() == null ? null : lineIds.get(line.parentIndex()))
                    .set(ORDER_LINES.SERVICE_VARIANT_ID, in == null ? null : in.serviceVariantId())
                    .set(ORDER_LINES.ADDON_ID, in == null ? null : in.addonId())
                    .set(ORDER_LINES.GIFT_CARD_PROGRAM_ID, in == null ? null : in.giftCardProgramId())
                    .set(ORDER_LINES.STAFF_ID, in == null ? null : in.staffId())
                    .set(ORDER_LINES.DESCRIPTION, line.description())
                    .set(ORDER_LINES.QUANTITY, line.quantity())
                    .set(ORDER_LINES.UNIT_PRICE_MINOR, line.unitPriceMinor())
                    .set(ORDER_LINES.DISCOUNT_MINOR, line.discountMinor())
                    .set(ORDER_LINES.TAX_MINOR, line.taxMinor())
                    .set(ORDER_LINES.TOTAL_MINOR, line.totalMinor())
                    .set(ORDER_LINES.CURRENCY_CODE, basket.currency())
                    .set(ORDER_LINES.TAX_CATEGORY_CODE, line.taxCategory())
                    .set(ORDER_LINES.TAX_INCLUSIVE, line.taxInclusive())
                    .execute();
            if (line.taxMinor() > 0) {
                TaxEngine.Rate rate = line.type().equals("BUYER_FEE") ? platformRate : rates.get(line.taxCategory());
                dsl.insertInto(ORDER_LINE_TAXES)
                        .set(ORDER_LINE_TAXES.ORG_ID, orgId)
                        .set(ORDER_LINE_TAXES.ORDER_LINE_ID, id)
                        .set(ORDER_LINE_TAXES.TAX_RATE_ID, rate.id())
                        .set(ORDER_LINE_TAXES.TAX_CODE, rate.taxCode())
                        .set(ORDER_LINE_TAXES.RATE, rate.rate())
                        .set(ORDER_LINE_TAXES.TAXABLE_MINOR, line.totalMinor() - line.taxMinor())
                        .set(ORDER_LINE_TAXES.TAX_MINOR, line.taxMinor())
                        .execute();
            }
        }

        for (PromotionEngine.Applied a : r.promotions()) {
            dsl.insertInto(REDEMPTIONS)
                    .set(REDEMPTIONS.ORG_ID, orgId)
                    .set(REDEMPTIONS.PROMOTION_ID, a.promo().id())
                    .set(REDEMPTIONS.PROMO_CODE_ID, a.promo().codeId())
                    .set(REDEMPTIONS.ORDER_ID, orderId)
                    .set(REDEMPTIONS.CUSTOMER_ID, basket.customerId())
                    .set(REDEMPTIONS.DISCOUNT_MINOR, a.totalMinor())
                    .set(REDEMPTIONS.PLATFORM_FUNDED_MINOR, a.platformFundedMinor())
                    .set(REDEMPTIONS.CURRENCY_CODE, basket.currency())
                    .execute();
        }

        dsl.insertInto(ORDER_STATUS_HISTORY)
                .set(ORDER_STATUS_HISTORY.ORG_ID, orgId)
                .set(ORDER_STATUS_HISTORY.ORDER_ID, orderId)
                .set(ORDER_STATUS_HISTORY.TO_STATUS, status)
                .set(ORDER_STATUS_HISTORY.ACTOR_USER_ID, actorUserId)
                .set(
                        ORDER_STATUS_HISTORY.ACTOR_KIND,
                        actorUserId == null
                                ? "SYSTEM"
                                : actorUserId.equals(basket.buyerUserId()) ? "CUSTOMER" : "STAFF")
                .execute();

        List<Fees.Charged> charged = new ArrayList<>();
        for (OrderPricing.ChargedFee f : r.fees()) {
            charged.add(new Fees.Charged(
                    f.fee(),
                    lineIds.get(f.fee().lineIndex()),
                    f.feeLineIndex() == null ? null : lineIds.get(f.feeLineIndex())));
        }
        fees.record(orgId, orderId, basket.currency(), charged);
        // Last, so a refused claim (the final use was just taken) rolls the whole order back.
        for (PromotionEngine.Applied a : r.promotions()) claimRedemption(a.promo());
        return new Placed(orderId, number, toQuote(priced));
    }

    // --- pricing facts ---------------------------------------------------------------------------------------------

    private Priced price(UUID orgId, Basket basket) {
        if (basket.lines().isEmpty()) throw new ApiException(ProblemCode.VALIDATION_FAILED, "The basket is empty.");
        Seller seller = seller(basket.locationId());
        List<OrderPricing.Item> items = items(basket.lines());
        Promotions promotions = promotions(orgId, basket, seller);
        BigDecimal platformVat = fees.platformVatRate(orgId, basket.at());
        TaxEngine.Rate platformRate = platformVat.signum() == 0 ? null : platformFeeRate(seller, basket.at());
        OrderPricing.Facts facts = new OrderPricing.Facts(
                seller.pricesIncludeTax(),
                sellerRates(seller, basket.at()),
                promotions.candidates(),
                fees.orderRules(orgId, basket.currency(), basket.at()),
                platformRate,
                basket.manualDiscountMinor());
        OrderPricing.Result result = OrderPricing.price(items, facts);
        PromoResult promo = promotions.typed();
        if (promo != null && promo.status().equals("APPLIED")) {
            long saved = result.promotions().stream()
                    .filter(a -> a.promo().codeId() != null)
                    .mapToLong(PromotionEngine.Applied::totalMinor)
                    .sum();
            promo = saved > 0
                    ? new PromoResult(promo.code(), "APPLIED", saved)
                    : new PromoResult(promo.code(), "NOT_APPLICABLE", 0);
        }
        return new Priced(seller, basket.currency(), items, result, promo);
    }

    private static Quote toQuote(Priced p) {
        OrderPricing.Result r = p.result();
        return new Quote(
                p.currency(),
                p.seller().pricesIncludeTax(),
                r.lines(),
                r.subtotalMinor(),
                r.discountMinor(),
                r.taxMinor(),
                r.buyerFeesMinor(),
                r.totalMinor(),
                r.sellerFeesMinor(),
                p.promo());
    }

    private Seller seller(UUID locationId) {
        var row = dsl.select(
                        LOCATIONS.ID,
                        LOCATIONS.LEGAL_ENTITY_ID,
                        LOCATIONS.TIMEZONE,
                        LEGAL_ENTITIES.COUNTRY_CODE,
                        LEGAL_ENTITIES.TAX_REGIME,
                        MARKETS.PRICES_INCLUDE_TAX)
                .from(LOCATIONS)
                .join(LEGAL_ENTITIES)
                .on(LEGAL_ENTITIES.ID.eq(LOCATIONS.LEGAL_ENTITY_ID))
                .leftJoin(MARKETS)
                .on(MARKETS.COUNTRY_CODE.eq(LEGAL_ENTITIES.COUNTRY_CODE))
                .where(LOCATIONS.ID.eq(locationId))
                .fetchOne();
        if (row == null) throw new ApiException(ProblemCode.NOT_FOUND, "Unknown location.");
        return new Seller(
                row.value1(),
                row.value2(),
                row.value4(),
                row.value5(),
                row.value6() == null || row.value6(),
                ZoneId.of(row.value3()));
    }

    @Override
    public void addTips(UUID orgId, UUID orderId, List<Tip> tips) {
        if (tips.isEmpty()) return;
        var order = dsl.selectFrom(ORDERS)
                .where(ORDERS.ID.eq(orderId))
                .and(ORDERS.ORG_ID.eq(orgId))
                .forUpdate()
                .fetchOne();
        if (order == null) throw new ApiException(ProblemCode.NOT_FOUND, "There is no such order.");
        if (!order.getStatus().equals("CONFIRMED")) {
            throw new ApiException(ProblemCode.CONFLICT, "A tip can only go on an order that stands.");
        }
        Short last = dsl.select(DSL.max(ORDER_LINES.SEQ))
                .from(ORDER_LINES)
                .where(ORDER_LINES.ORDER_ID.eq(orderId))
                .fetchOne(0, Short.class);
        int seq = last == null ? 0 : last;
        long sum = 0;
        for (Tip tip : tips) {
            if (tip.amountMinor() <= 0) throw new IllegalArgumentException("A tip is more than zero");
            dsl.insertInto(ORDER_LINES)
                    .set(ORDER_LINES.ID, Ids.newId())
                    .set(ORDER_LINES.ORG_ID, orgId)
                    .set(ORDER_LINES.ORDER_ID, orderId)
                    .set(ORDER_LINES.SEQ, (short) ++seq)
                    .set(ORDER_LINES.LINE_TYPE, "TIP")
                    .set(ORDER_LINES.STAFF_ID, tip.staffId())
                    .set(ORDER_LINES.DESCRIPTION, tip.description())
                    .set(ORDER_LINES.QUANTITY, 1)
                    .set(ORDER_LINES.UNIT_PRICE_MINOR, tip.amountMinor())
                    .set(ORDER_LINES.DISCOUNT_MINOR, 0L)
                    .set(ORDER_LINES.TAX_MINOR, 0L)
                    .set(ORDER_LINES.TOTAL_MINOR, tip.amountMinor())
                    .set(ORDER_LINES.CURRENCY_CODE, order.getCurrencyCode())
                    .set(ORDER_LINES.TAX_CATEGORY_CODE, "EXEMPT")
                    .set(ORDER_LINES.TAX_INCLUSIVE, order.getPricesIncludeTax())
                    .execute();
            sum += tip.amountMinor();
        }
        dsl.update(ORDERS)
                .set(ORDERS.SUBTOTAL_MINOR, ORDERS.SUBTOTAL_MINOR.plus(sum))
                .set(ORDERS.TOTAL_MINOR, ORDERS.TOTAL_MINOR.plus(sum))
                .set(ORDERS.VERSION, ORDERS.VERSION.plus(1))
                .where(ORDERS.ID.eq(orderId))
                .execute();
    }

    /** The catalogue facts of each line: its service and category (promotion targets) and its tax category. */
    private List<OrderPricing.Item> items(List<LineInput> lines) {
        List<OrderPricing.Item> items = new ArrayList<>();
        for (LineInput in : lines) {
            if (in.quantity() <= 0 || in.unitPriceMinor() < 0) {
                throw new ApiException(
                        ProblemCode.VALIDATION_FAILED, "Quantities are positive and prices not negative.");
            }
            if (in.giftCardProgramId() != null) {
                // A gift card is a multi-purpose voucher: not taxed when sold, taxed when it is spent (docs/04 §12).
                items.add(new OrderPricing.Item(in, null, null, "VOUCHER_MULTI_PURPOSE"));
            } else if (in.serviceVariantId() != null) {
                var s = dsl.select(SERVICES.ID, SERVICES.CATEGORY_ID, SERVICES.TAX_CATEGORY_CODE)
                        .from(SERVICE_VARIANTS)
                        .join(SERVICES)
                        .on(SERVICES.ID.eq(SERVICE_VARIANTS.SERVICE_ID))
                        .where(SERVICE_VARIANTS.ID.eq(in.serviceVariantId()))
                        .fetchOne();
                if (s == null) throw new ApiException(ProblemCode.NOT_FOUND, "Unknown service.");
                items.add(new OrderPricing.Item(in, s.value1(), s.value2(), s.value3()));
            } else if (in.addonId() != null) {
                String category = dsl.select(ADDONS.TAX_CATEGORY_CODE)
                        .from(ADDONS)
                        .where(ADDONS.ID.eq(in.addonId()))
                        .fetchOne(ADDONS.TAX_CATEGORY_CODE);
                if (category == null) throw new ApiException(ProblemCode.NOT_FOUND, "Unknown add-on.");
                items.add(new OrderPricing.Item(in, null, null, category));
            } else {
                items.add(new OrderPricing.Item(in, null, null, "SERVICE"));
            }
        }
        return items;
    }

    /**
     * The seller's rates by tax category on the day of the sale: VAT-registered sellers charge VAT, turnover-tax
     * sellers TOT; presumptive and exempt sellers charge none (docs/04 §12; rates unverified until D7).
     */
    private Map<String, TaxEngine.Rate> sellerRates(Seller seller, Instant at) {
        String regime = seller.regime();
        if (!regime.equals("VAT") && !regime.equals("TOT")) return Map.of();
        LocalDate day = at.atZone(seller.zone()).toLocalDate();
        Map<String, TaxEngine.Rate> rates = new HashMap<>();
        for (var r : dsl.selectFrom(TAX_RATES)
                .where(TAX_RATES.COUNTRY_CODE.eq(seller.country()))
                .and(TAX_RATES.TAX_CODE.eq(regime))
                .and(TAX_RATES.SELLER_REGIME.in(regime, "ANY"))
                .and(DSL.condition("{0} @> {1}::date", TAX_RATES.VALID_DURING, day))
                .fetch()) {
            rates.put(r.getTaxCategoryCode(), new TaxEngine.Rate(r.getId(), r.getTaxCode(), r.getRate()));
        }
        return rates;
    }

    /** The platform's VAT on its own fees in the seller's country (the platform is VAT-registered). */
    private TaxEngine.Rate platformFeeRate(Seller seller, Instant at) {
        LocalDate day = at.atZone(seller.zone()).toLocalDate();
        var r = dsl.selectFrom(TAX_RATES)
                .where(TAX_RATES.COUNTRY_CODE.eq(seller.country()))
                .and(TAX_RATES.TAX_CODE.eq("VAT"))
                .and(TAX_RATES.TAX_CATEGORY_CODE.eq("PLATFORM_FEE"))
                .and(TAX_RATES.SELLER_REGIME.in("VAT", "ANY"))
                .and(DSL.condition("{0} @> {1}::date", TAX_RATES.VALID_DURING, day))
                .limit(1)
                .fetchOne();
        return r == null ? null : new TaxEngine.Rate(r.getId(), r.getTaxCode(), r.getRate());
    }

    // --- promotions ----------------------------------------------------------------------------------------------

    record Promotions(List<PromotionEngine.Promo> candidates, PromoResult typed) {}

    /**
     * Automatic promotions and the typed code's promotion that are valid for this order: active, in their dates and
     * time windows, under their limits, for this kind of client, at this location, in this currency.
     */
    private Promotions promotions(UUID orgId, Basket basket, Seller seller) {
        OffsetDateTime at = basket.at().atOffset(ZoneOffset.UTC);
        boolean newClient = isNewClient(orgId, basket.customerId());
        List<PromotionEngine.Promo> candidates = new ArrayList<>();
        for (PromotionsRecord p : dsl.selectFrom(PROMOTIONS)
                .where(PROMOTIONS.ORG_ID.eq(orgId).or(PROMOTIONS.ORG_ID.isNull()))
                .and(PROMOTIONS.AUTO_APPLY.isTrue())
                .and(PROMOTIONS.STATUS.eq("ACTIVE"))
                .and(DSL.condition("{0} @> {1}::timestamptz", PROMOTIONS.VALID_DURING, at))
                .fetch()) {
            PromotionEngine.Promo promo = usable(p, null, basket, seller, newClient);
            if (promo != null) candidates.add(promo);
        }
        PromoResult typed = null;
        String code = basket.promoCode() == null ? null : basket.promoCode().strip();
        if (code != null && !code.isEmpty()) {
            typed = typedCode(orgId, code, basket, seller, newClient, candidates);
        }
        return new Promotions(candidates, typed);
    }

    private PromoResult typedCode(
            UUID orgId,
            String code,
            Basket basket,
            Seller seller,
            boolean newClient,
            List<PromotionEngine.Promo> into) {
        OffsetDateTime at = basket.at().atOffset(ZoneOffset.UTC);
        var c = dsl.selectFrom(PROMO_CODES)
                .where(DSL.condition("{0} = {1}::citext", PROMO_CODES.CODE, code))
                .and(PROMO_CODES.ORG_ID.eq(orgId).or(PROMO_CODES.ORG_ID.isNull()))
                .and(PROMO_CODES.KIND.in("DISCOUNT", "DISCOUNT_AND_ACCESS"))
                .orderBy(PROMO_CODES.ORG_ID.isNull())
                .limit(1)
                .fetchOne();
        String shown = code.toUpperCase(Locale.ROOT);
        if (c == null) return new PromoResult(shown, "UNKNOWN", 0);
        // Ranges are compared in SQL: jOOQ has no Java type for tstzrange.
        boolean codeLive = c.getStatus().equals("ACTIVE")
                && dsl.fetchExists(
                        PROMO_CODES,
                        PROMO_CODES
                                .ID
                                .eq(c.getId())
                                .and(PROMO_CODES
                                        .VALID_DURING
                                        .isNull()
                                        .or(DSL.condition("{0} @> {1}::timestamptz", PROMO_CODES.VALID_DURING, at))));
        if (!codeLive) return new PromoResult(shown, "EXPIRED", 0);
        if (c.getMaxUses() != null && c.getUseCount() >= c.getMaxUses()) return new PromoResult(shown, "USED_UP", 0);
        PromotionsRecord p = dsl.selectFrom(PROMOTIONS)
                .where(PROMOTIONS.ID.eq(c.getPromotionId()))
                .fetchOne();
        if (p == null) return new PromoResult(shown, "UNKNOWN", 0);
        boolean promotionLive = p.getStatus().equals("ACTIVE")
                && dsl.fetchExists(
                        PROMOTIONS,
                        PROMOTIONS
                                .ID
                                .eq(p.getId())
                                .and(DSL.condition("{0} @> {1}::timestamptz", PROMOTIONS.VALID_DURING, at)));
        if (!promotionLive) return new PromoResult(shown, "EXPIRED", 0);
        if (p.getMaxRedemptions() != null && p.getRedemptionCount() >= p.getMaxRedemptions()) {
            return new PromoResult(shown, "USED_UP", 0);
        }
        if (!eligible(p, newClient)) return new PromoResult(shown, "NOT_ELIGIBLE", 0);
        if (overCustomerLimit(p, basket.customerId())) return new PromoResult(shown, "USED_UP", 0);
        PromotionEngine.Promo promo = usable(p, c.getId(), basket, seller, newClient);
        if (promo == null) return new PromoResult(shown, "NOT_APPLICABLE", 0);
        // The typed code's promotion replaces the same promotion applied automatically.
        into.removeIf(x -> x.id().equals(promo.id()));
        into.add(promo);
        return new PromoResult(shown, "APPLIED", 0);
    }

    /** The promotion as the engine sees it, or null when it cannot apply to this basket. */
    private PromotionEngine.Promo usable(
            PromotionsRecord p, UUID codeId, Basket basket, Seller seller, boolean newClient) {
        if (p.getMaxRedemptions() != null && p.getRedemptionCount() >= p.getMaxRedemptions()) return null;
        if (!eligible(p, newClient)) return null;
        if (overCustomerLimit(p, basket.customerId())) return null;
        if (p.getCurrencyCode() != null && !p.getCurrencyCode().equals(basket.currency())) return null;
        if (!inWindow(
                p.getTimeWindows() == null ? null : p.getTimeWindows().data(), basket.serviceStart(), seller.zone())) {
            return null;
        }
        Set<UUID> services = new HashSet<>();
        Set<UUID> categories = new HashSet<>();
        Set<UUID> locations = new HashSet<>();
        boolean otherTargets = false;
        for (var t : dsl.selectFrom(PROMOTION_TARGETS)
                .where(PROMOTION_TARGETS.PROMOTION_ID.eq(p.getId()))
                .fetch()) {
            if (t.getServiceId() != null) services.add(t.getServiceId());
            else if (t.getCategoryId() != null) categories.add(t.getCategoryId());
            else if (t.getLocationId() != null) locations.add(t.getLocationId());
            else otherTargets = true;
        }
        if (!locations.isEmpty() && !locations.contains(seller.locationId())) return null;
        // Targets for products, events or tickets only: nothing in an appointment basket matches.
        if (otherTargets && services.isEmpty() && categories.isEmpty()) return null;
        return new PromotionEngine.Promo(
                p.getId(),
                codeId,
                p.getKind(),
                p.getPercentOff(),
                p.getAmountMinor(),
                p.getMaxDiscountMinor(),
                p.getMinOrderMinor(),
                p.getStackable(),
                p.getOrgId() == null ? "PLATFORM" : p.getFundedBy(),
                p.getPlatformShareBps(),
                services,
                categories);
    }

    /** Everyone, new clients (no completed visit yet) or returning ones; members and segments come later. */
    private static boolean eligible(PromotionsRecord p, boolean newClient) {
        return switch (p.getEligibility()) {
            case "EVERYONE" -> true;
            case "NEW_CUSTOMERS" -> newClient;
            case "RETURNING_CUSTOMERS" -> !newClient;
            default -> false;
        };
    }

    private boolean overCustomerLimit(PromotionsRecord p, UUID customerId) {
        if (p.getPerCustomerLimit() == null || customerId == null) return false;
        int used = dsl.fetchCount(
                REDEMPTIONS,
                REDEMPTIONS
                        .PROMOTION_ID
                        .eq(p.getId())
                        .and(REDEMPTIONS.CUSTOMER_ID.eq(customerId))
                        .and(REDEMPTIONS.STATUS.eq("APPLIED")));
        return used >= p.getPerCustomerLimit();
    }

    /** A client the business has never completed a visit for (unknown buyers count as new until they sign in). */
    private boolean isNewClient(UUID orgId, UUID customerId) {
        if (customerId == null) return true;
        return !dsl.fetchExists(
                APPOINTMENTS,
                APPOINTMENTS
                        .ORG_ID
                        .eq(orgId)
                        .and(APPOINTMENTS.CUSTOMER_ID.eq(customerId))
                        .and(APPOINTMENTS.STATUS.eq("COMPLETED")));
    }

    /**
     * Off-peak windows, {@code [{"iso_weekdays":[1,2],"from":"10:00","to":"14:00"}]}, in the location's time: none
     * means any time; a basket without a start time matches none.
     */
    boolean inWindow(String windowsJson, Instant start, ZoneId zone) {
        if (windowsJson == null) return true;
        JsonNode windows = json.readTree(windowsJson);
        if (!windows.isArray() || windows.isEmpty()) return true;
        if (start == null) return false;
        ZonedDateTime local = start.atZone(zone);
        int weekday = local.getDayOfWeek().getValue();
        LocalTime time = local.toLocalTime();
        for (JsonNode w : windows) {
            boolean day = !w.has("iso_weekdays")
                    || w.path("iso_weekdays").valueStream().anyMatch(d -> d.asInt() == weekday);
            LocalTime from = LocalTime.parse(w.path("from").asString("00:00"));
            LocalTime to = LocalTime.parse(w.path("to").asString("23:59"));
            if (day && !time.isBefore(from) && time.isBefore(to)) return true;
        }
        return false;
    }

    /**
     * Counts one use of the promotion (and its code), refused when the last one was just taken. An organization's own
     * promotions are updated in the order's transaction; the platform's are shared rows tenants may only read, so
     * those use the system role (and are claimed last to keep the window small).
     */
    private void claimRedemption(PromotionEngine.Promo promo) {
        boolean platform =
                dsl.fetchExists(PROMOTIONS, PROMOTIONS.ID.eq(promo.id()).and(PROMOTIONS.ORG_ID.isNull()));
        DSLContext db = platform ? system : dsl;
        int taken = db.update(PROMOTIONS)
                .set(PROMOTIONS.REDEMPTION_COUNT, PROMOTIONS.REDEMPTION_COUNT.plus(1))
                .where(PROMOTIONS.ID.eq(promo.id()))
                .and(PROMOTIONS.MAX_REDEMPTIONS.isNull().or(PROMOTIONS.REDEMPTION_COUNT.lt(PROMOTIONS.MAX_REDEMPTIONS)))
                .execute();
        if (taken == 0) throw new ApiException(ProblemCode.CONFLICT, "That offer has just been used up.");
        if (promo.codeId() != null) {
            boolean platformCode = dsl.fetchExists(
                    PROMO_CODES, PROMO_CODES.ID.eq(promo.codeId()).and(PROMO_CODES.ORG_ID.isNull()));
            int code = (platformCode ? system : dsl)
                    .update(PROMO_CODES)
                    .set(PROMO_CODES.USE_COUNT, PROMO_CODES.USE_COUNT.plus(1))
                    .where(PROMO_CODES.ID.eq(promo.codeId()))
                    .and(PROMO_CODES.MAX_USES.isNull().or(PROMO_CODES.USE_COUNT.lt(PROMO_CODES.MAX_USES)))
                    .execute();
            if (code == 0) throw new ApiException(ProblemCode.CONFLICT, "That code has just been used up.");
        }
    }

    /** The next gapless order number of the legal entity (its sequence starts at 1 on the first order). */
    private String nextNumber(UUID orgId, UUID legalEntityId) {
        dsl.insertInto(DOCUMENT_SEQUENCES)
                .set(DOCUMENT_SEQUENCES.ORG_ID, orgId)
                .set(DOCUMENT_SEQUENCES.LEGAL_ENTITY_ID, legalEntityId)
                .set(DOCUMENT_SEQUENCES.DOC_TYPE, "ORDER")
                .onConflictDoNothing()
                .execute();
        return dsl.fetchValue(
                DSL.field("commerce.next_document_number({0}, {1})", String.class, legalEntityId, DSL.inline("ORDER")));
    }
}
