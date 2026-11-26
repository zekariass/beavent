package com.beavent.backend.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Plan 4.5: retail products and their stock. Products are added with variants and prices; stock arrives, is corrected or
 * lost through movements; a sale takes goods off the stock. A product is sold with a visit at checkout, or on its own at
 * the counter with no visit, paid like any sale and given a receipt; a sale nothing was paid for can be cancelled and the
 * goods go back.
 */
class RetailApiTests extends PayoutTestSupport {

    private JsonNode product(Shop shop, String sku, long priceMinor, boolean track) throws Exception {
        Map<String, Object> variant = new HashMap<>();
        variant.put("label", "250 ml");
        variant.put("sku", sku);
        variant.put("priceMinor", priceMinor);
        variant.put("costMinor", 15_000);
        Call made = call(
                post(shop.salon().path("/products")),
                Map.of(
                        "name",
                        Map.of("en", "Argan oil", "am", "የአርጋን ዘይት"),
                        "brand",
                        "Atlas",
                        "trackInventory",
                        track,
                        "variants",
                        List.of(variant)),
                shop.salon().owner().token());
        assertThat(made.status()).as(String.valueOf(made.body())).isEqualTo(201);
        return made.body();
    }

    private String variantOf(JsonNode product) {
        return product.get("variants").get(0).get("id").asString();
    }

    private void receive(Shop shop, String variant, long quantity) throws Exception {
        Call c = callInvalid(
                post(shop.salon().path("/stock/receipts")),
                Map.of(
                        "locationId",
                        shop.salon().location1().toString(),
                        "variantId",
                        variant,
                        "quantity",
                        quantity,
                        "reason",
                        "Delivery from the supplier"),
                shop.salon().owner().token());
        assertThat(c.status()).as(String.valueOf(c.body())).isEqualTo(204);
    }

    private long onHand(Shop shop, String variant) throws Exception {
        JsonNode items = call(
                        get(shop.salon()
                                .path("/products?locationId=" + shop.salon().location1())),
                        null,
                        shop.salon().owner().token())
                .body()
                .get("items");
        for (JsonNode p : items) {
            for (JsonNode v : p.get("variants")) {
                if (v.get("id").asString().equals(variant))
                    return v.get("onHand").asLong();
            }
        }
        throw new AssertionError("no such variant");
    }

    private void open(Shop shop) throws Exception {
        call(
                post(shop.salon().path("/cash-drawer/open")),
                Map.of("locationId", shop.salon().location1().toString(), "openingFloatMinor", 0),
                shop.salon().owner().token());
    }

    private Call startSale(Shop shop, Map<String, Object> extra, String token) throws Exception {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("locationId", shop.salon().location1().toString());
        return callInvalid(post(shop.salon().path("/sales")), body, token);
    }

    private Call paySale(Shop shop, String order, Map<String, Object> body, String token) throws Exception {
        return callInvalid(post(shop.salon().path("/sales/" + order + "/payment")), body, token);
    }

    private String deskVisit(Shop shop, int daysAhead) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("locationId", shop.salon().location1().toString());
        body.put("start", at(day().plusDays(daysAhead), "10:00").toInstant().toString());
        body.put("items", List.of(Map.of("variantId", shop.variant(), "staffId", shop.abebe())));
        body.put("customer", Map.of("name", "Selam", "phone", newPhone()));
        body.put("source", "PHONE");
        body.put("notifyCustomer", false);
        Call booked = call(
                post(shop.salon().path("/appointments")),
                body,
                shop.salon().owner().token());
        assertThat(booked.status()).as(String.valueOf(booked.body())).isEqualTo(201);
        return booked.text("id");
    }

    private Call checkout(Shop shop, String appointment, Map<String, Object> body) throws Exception {
        return callInvalid(
                post(shop.salon().path("/appointments/" + appointment + "/checkout")),
                body,
                shop.salon().owner().token());
    }

    @Test
    void productsAreAddedWithVariantsAndPricesAndOnlyTheRightPeopleSeeTheCost() throws Exception {
        Shop shop = shop(true);
        String token = shop.salon().owner().token();
        String sku = "OIL-" + UUID.randomUUID().toString().substring(0, 6);
        JsonNode oil = product(shop, sku, 25_000, true);
        assertThat(oil.get("name").get("am").asString()).isEqualTo("የአርጋን ዘይት");
        assertThat(oil.get("variants").get(0).get("currency").asString()).isEqualTo("ETB");
        assertThat(oil.get("variants").get(0).get("costMinor").asLong()).isEqualTo(15_000);

        // A second variant, a changed price, and the first variant left out is archived.
        Call changed = call(
                put(shop.salon().path("/products/" + oil.get("id").asString())),
                Map.of(
                        "name", Map.of("en", "Argan oil"),
                        "trackInventory", true,
                        "variants",
                                List.of(
                                        Map.of("label", "100 ml", "sku", sku + "-S", "priceMinor", 12_000),
                                        Map.of(
                                                "label",
                                                "250 ml",
                                                "id",
                                                variantOf(oil),
                                                "sku",
                                                sku,
                                                "priceMinor",
                                                27_000))),
                token);
        assertThat(changed.status()).as(String.valueOf(changed.body())).isEqualTo(200);
        assertThat(changed.body().get("variants").size()).isEqualTo(2);
        assertThat(changed.body().get("variants").toString()).contains("27000");

        // Validations.
        Map<String, Object> noVariants =
                Map.of("name", Map.of("en", "Soap"), "trackInventory", true, "variants", List.of());
        assertThat(callInvalid(post(shop.salon().path("/products")), noVariants, token)
                        .status())
                .isEqualTo(400);
        assertThat(callInvalid(
                                post(shop.salon().path("/products")),
                                Map.of(
                                        "name", Map.of("en", "Soap"),
                                        "trackInventory", true,
                                        "variants", List.of(Map.of("priceMinor", 100), Map.of("priceMinor", 200))),
                                token)
                        .status())
                .as("variants need names to tell them apart")
                .isEqualTo(400);
        assertThat(callInvalid(
                                post(shop.salon().path("/products")),
                                Map.of(
                                        "name", Map.of("en", "Soap"),
                                        "trackInventory", true,
                                        "variants", List.of(Map.of("sku", sku, "priceMinor", 100))),
                                token)
                        .status())
                .as("a SKU is the business's own, once")
                .isEqualTo(409);

        // Who sees what: the cashier sells and sees prices, not costs; a professional cannot add products.
        Person cashier = newMember(shop.salon(), "CASHIER");
        JsonNode seen = call(get(shop.salon().path("/products")), null, cashier.token())
                .body()
                .get("items");
        assertThat(seen.size()).isEqualTo(1);
        assertThat(seen.toString()).doesNotContain("costMinor");
        assertThat(seen.get(0).get("variants").size())
                .as("archived variants are not shown")
                .isEqualTo(2);
        Person professional = newMember(shop.salon(), "PROFESSIONAL");
        Map<String, Object> soap = Map.of(
                "name", Map.of("en", "Soap"),
                "trackInventory", true,
                "variants", List.of(Map.of("priceMinor", 100)));
        assertThat(callInvalid(post(shop.salon().path("/products")), soap, professional.token())
                        .status())
                .isEqualTo(403);
        Person receptionist = newMember(shop.salon(), "RECEPTIONIST");
        assertThat(callInvalid(get(shop.salon().path("/products")), null, receptionist.token())
                        .status())
                .isIn(200, 403);

        // Archived products leave the cashier's list and stay for those who manage them.
        call(
                put(shop.salon().path("/products/" + oil.get("id").asString())),
                Map.of(
                        "name",
                        Map.of("en", "Argan oil"),
                        "trackInventory",
                        true,
                        "status",
                        "ARCHIVED",
                        "variants",
                        List.of(Map.of("id", variantOf(oil), "label", "250 ml", "priceMinor", 27_000))),
                token);
        assertThat(call(get(shop.salon().path("/products")), null, cashier.token())
                        .body()
                        .get("items")
                        .size())
                .isZero();
        assertThat(call(get(shop.salon().path("/products?includeArchived=true")), null, token)
                        .body()
                        .get("items")
                        .size())
                .isEqualTo(1);
    }

    @Test
    void stockArrivesIsCorrectedAndLostThroughMovementsAndNeverGoesBelowZero() throws Exception {
        Shop shop = shop(true);
        String token = shop.salon().owner().token();
        String variant =
                variantOf(product(shop, "S-" + UUID.randomUUID().toString().substring(0, 6), 25_000, true));
        String location = shop.salon().location1().toString();
        assertThat(onHand(shop, variant)).isZero();

        receive(shop, variant, 10);
        assertThat(onHand(shop, variant)).isEqualTo(10);
        assertThat(callInvalid(
                                post(shop.salon().path("/stock/adjustments")),
                                Map.of(
                                        "locationId",
                                        location,
                                        "variantId",
                                        variant,
                                        "change",
                                        -2,
                                        "kind",
                                        "ADJUSTMENT",
                                        "reason",
                                        "Counted two fewer"),
                                token)
                        .status())
                .isEqualTo(204);
        assertThat(callInvalid(
                                post(shop.salon().path("/stock/adjustments")),
                                Map.of(
                                        "locationId",
                                        location,
                                        "variantId",
                                        variant,
                                        "change",
                                        -1,
                                        "kind",
                                        "DAMAGE",
                                        "reason",
                                        "Dropped and broken"),
                                token)
                        .status())
                .isEqualTo(204);
        assertThat(onHand(shop, variant)).isEqualTo(7);
        assertThat(callInvalid(
                                post(shop.salon().path("/stock/adjustments")),
                                Map.of(
                                        "locationId",
                                        location,
                                        "variantId",
                                        variant,
                                        "change",
                                        -8,
                                        "kind",
                                        "ADJUSTMENT",
                                        "reason",
                                        "Too many"),
                                token)
                        .status())
                .as("not below zero")
                .isEqualTo(409);
        assertThat(callInvalid(
                                post(shop.salon().path("/stock/adjustments")),
                                Map.of(
                                        "locationId",
                                        location,
                                        "variantId",
                                        variant,
                                        "change",
                                        2,
                                        "kind",
                                        "DAMAGE",
                                        "reason",
                                        "Damage adds nothing"),
                                token)
                        .status())
                .isEqualTo(400);
        assertThat(callInvalid(
                                post(shop.salon().path("/stock/adjustments")),
                                Map.of(
                                        "locationId",
                                        location,
                                        "variantId",
                                        variant,
                                        "change",
                                        0,
                                        "kind",
                                        "ADJUSTMENT",
                                        "reason",
                                        "Nothing"),
                                token)
                        .status())
                .isEqualTo(400);
        assertThat(callInvalid(
                                post(shop.salon().path("/stock/receipts")),
                                Map.of("locationId", location, "variantId", variant, "quantity", 1, "reason", "x"),
                                token)
                        .status())
                .as("a reason is kept")
                .isEqualTo(400);

        JsonNode movements = call(
                        get(shop.salon().path("/stock/movements?locationId=" + location + "&variantId=" + variant)),
                        null,
                        token)
                .body()
                .get("items");
        assertThat(movements.size()).isEqualTo(3);
        assertThat(movements.get(0).get("kind").asString()).isEqualTo("DAMAGE");
        assertThat(movements.get(0).get("quantity").asLong()).isEqualTo(-1);
        assertThat(movements.get(2).get("kind").asString()).isEqualTo("RECEIPT");

        // Stock is not kept for every product, and only for people who manage it.
        String untracked =
                variantOf(product(shop, "U-" + UUID.randomUUID().toString().substring(0, 6), 5_000, false));
        assertThat(callInvalid(
                                post(shop.salon().path("/stock/receipts")),
                                Map.of(
                                        "locationId",
                                        location,
                                        "variantId",
                                        untracked,
                                        "quantity",
                                        1,
                                        "reason",
                                        "Delivery"),
                                token)
                        .status())
                .isEqualTo(409);
        Person cashier = newMember(shop.salon(), "CASHIER");
        assertThat(callInvalid(
                                post(shop.salon().path("/stock/receipts")),
                                Map.of(
                                        "locationId",
                                        location,
                                        "variantId",
                                        variant,
                                        "quantity",
                                        1,
                                        "reason",
                                        "Delivery"),
                                cashier.token())
                        .status())
                .isEqualTo(403);
        Shop other = shop(true);
        assertThat(callInvalid(
                                post(other.salon().path("/stock/receipts")),
                                Map.of(
                                        "locationId",
                                        other.salon().location1().toString(),
                                        "variantId",
                                        variant,
                                        "quantity",
                                        1,
                                        "reason",
                                        "Someone else's product"),
                                other.salon().owner().token())
                        .status())
                .isEqualTo(404);
    }

    @Test
    void aProductIsSoldAtTheCounterWithNoVisitPaidAndGivenAReceiptAndTheStockGoesDown() throws Exception {
        Shop shop = shop(true);
        String token = shop.salon().owner().token();
        String oil = variantOf(product(shop, "O-" + UUID.randomUUID().toString().substring(0, 6), 25_000, true));
        String soap =
                variantOf(product(shop, "P-" + UUID.randomUUID().toString().substring(0, 6), 8_000, true));
        receive(shop, oil, 5);
        receive(shop, soap, 2);
        open(shop);
        long ledgerBefore = ledgerSum();

        // Not enough soap: refused, nothing made.
        assertThat(startSale(shop, Map.of("items", List.of(Map.of("variantId", soap, "quantity", 3))), token)
                        .status())
                .isEqualTo(409);
        assertThat(onHand(shop, soap)).isEqualTo(2);
        assertThat(startSale(shop, Map.of("items", List.of()), token).status()).isEqualTo(400);
        assertThat(startSale(
                                shop,
                                Map.of(
                                        "items",
                                        List.of(Map.of(
                                                "variantId", UUID.randomUUID().toString(), "quantity", 1))),
                                token)
                        .status())
                .isEqualTo(404);

        // Two oils and a soap: Br 580.
        Call started = startSale(
                shop,
                Map.of(
                        "items",
                        List.of(Map.of("variantId", oil, "quantity", 2), Map.of("variantId", soap, "quantity", 1))),
                token);
        assertThat(started.status()).as(String.valueOf(started.body())).isEqualTo(201);
        JsonNode bill = started.body();
        String order = bill.get("orderId").asString();
        assertThat(bill.get("totalMinor").asLong()).isEqualTo(58_000);
        assertThat(bill.get("dueMinor").asLong()).isEqualTo(58_000);
        assertThat(bill.get("lines").size()).isEqualTo(2);
        assertThat(bill.get("canCancel").asBoolean()).isTrue();
        assertThat(onHand(shop, oil)).as("taken off at once").isEqualTo(3);
        assertThat(onHand(shop, soap)).isEqualTo(1);

        // The cash is short, then enough: change is given, the receipt is numbered, the order is complete.
        assertThat(paySale(shop, order, Map.of("cashTenderedMinor", 50_000), token)
                        .status())
                .isEqualTo(400);
        Call paid = paySale(shop, order, Map.of("cashTenderedMinor", 60_000), token);
        assertThat(paid.status()).as(String.valueOf(paid.body())).isEqualTo(200);
        assertThat(paid.body().get("totalMinor").asLong()).isEqualTo(58_000);
        assertThat(paid.body().get("changeMinor").asLong()).isEqualTo(2_000);
        assertThat(paid.body().get("receiptNumber").asString()).matches("R\\d{7}");
        assertThat(system.fetchOne(
                                "select status, payment_status, paid_minor from commerce.orders where id = ?::uuid",
                                UUID.fromString(order))
                        .intoList())
                .containsExactly("COMPLETED", "PAID", 58_000L);
        assertThat(system.fetchValue(
                        "select count(*) from stock.movements m join commerce.order_lines l on l.id = m.order_line_id"
                                + " where l.order_id = ?::uuid and m.kind = 'SALE'",
                        UUID.fromString(order)))
                .isEqualTo(2L);
        assertThat(ledgerSum()).isEqualTo(ledgerBefore);
        JsonNode receipt = call(get(shop.salon().path("/orders/" + order + "/receipt")), null, token)
                .body();
        assertThat(receipt.get("lines").toString()).contains("Argan oil");
        assertThat(receipt.get("totalMinor").asLong()).isEqualTo(58_000);
        assertThat(receipt.hasNonNull("customer")).as("no client for a walk-in").isFalse();

        // Paid sales stand: not cancelled, not paid twice.
        assertThat(callInvalid(post(shop.salon().path("/sales/" + order + "/cancel")), null, token)
                        .status())
                .isEqualTo(409);
        assertThat(paySale(shop, order, Map.of("cashTenderedMinor", 58_000), token)
                        .status())
                .isEqualTo(409);
        assertThat(onHand(shop, oil)).isEqualTo(3);
    }

    @Test
    void aSaleNothingWasPaidForIsCancelledAndTheGoodsGoBackAndCardsAndDiscountsWork() throws Exception {
        Shop shop = shop(true);
        String token = shop.salon().owner().token();
        String oil = variantOf(product(shop, "O-" + UUID.randomUUID().toString().substring(0, 6), 25_000, true));
        receive(shop, oil, 5);
        open(shop);

        Call started = startSale(shop, Map.of("items", List.of(Map.of("variantId", oil, "quantity", 2))), token);
        String order = started.body().get("orderId").asString();
        assertThat(onHand(shop, oil)).isEqualTo(3);
        assertThat(callInvalid(post(shop.salon().path("/sales/" + order + "/cancel")), null, token)
                        .status())
                .isEqualTo(204);
        assertThat(onHand(shop, oil)).as("back on the shelf").isEqualTo(5);
        assertThat(system.fetchValue("select status from commerce.orders where id = ?::uuid", UUID.fromString(order)))
                .isEqualTo("CANCELLED");
        assertThat(paySale(shop, order, Map.of("cashTenderedMinor", 50_000), token)
                        .status())
                .isEqualTo(409);
        assertThat(callInvalid(post(shop.salon().path("/sales/" + order + "/cancel")), null, token)
                        .status())
                .isEqualTo(409);

        // A gift card and cash share a sale; a discount of 10 % for a reason is the manager's.
        String letters = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(letters.charAt((int) (Math.random() * letters.length())));
        }
        String code = sb.toString();
        call(
                put(shop.salon().path("/gift-cards/program")),
                Map.of(
                        "name",
                        "Gift card",
                        "presetsMinor",
                        List.of(20_000),
                        "minMinor",
                        10_000,
                        "maxMinor",
                        500_000,
                        "sellOnline",
                        true),
                token);
        system.execute(
                "insert into retention.gift_cards (org_id, program_id, code_hash, code_last4, currency_code, initial_minor, "
                        + "balance_minor, status, delivered_at) select ?::uuid, p.id, encode(sha256(convert_to(?, 'UTF8')), 'hex'), ?, "
                        + "'ETB', 10000, 10000, 'ACTIVE', now() from catalog.gift_card_programs p where p.org_id = ?::uuid",
                shop.salon().id(),
                code.replace("-", ""),
                code.replace("-", "").substring(12),
                shop.salon().id());
        Person cashier = newMember(shop.salon(), "CASHIER");
        Map<String, Object> items = Map.of("items", List.of(Map.of("variantId", oil, "quantity", 2)));
        Map<String, Object> discounted = new HashMap<>(items);
        discounted.put("discount", Map.of("kind", "PERCENT", "value", 10, "reason", "Loyal client"));
        assertThat(startSale(shop, discounted, cashier.token()).status())
                .as("no pos.discount")
                .isEqualTo(403);
        Call sale = startSale(shop, discounted, token);
        assertThat(sale.status()).as(String.valueOf(sale.body())).isEqualTo(201);
        assertThat(sale.body().get("totalMinor").asLong()).isEqualTo(45_000);
        Call split = paySale(
                shop,
                sale.body().get("orderId").asString(),
                Map.of("giftCards", List.of(code), "cashTenderedMinor", 40_000),
                token);
        assertThat(split.status()).as(String.valueOf(split.body())).isEqualTo(200);
        assertThat(split.body().get("cashMinor").asLong()).isEqualTo(35_000);
        assertThat(split.body().get("changeMinor").asLong()).isEqualTo(5_000);
        assertThat(split.body().get("tenders").size()).isEqualTo(2);

        // Someone who does not sell cannot; nor can another business reach the sale.
        Person inventory = newMember(shop.salon(), "INVENTORY_MANAGER");
        assertThat(startSale(shop, items, inventory.token()).status()).isEqualTo(403);
        Shop other = shop(true);
        assertThat(callInvalid(
                                get(other.salon()
                                        .path("/sales/"
                                                + sale.body().get("orderId").asString())),
                                null,
                                other.salon().owner().token())
                        .status())
                .isEqualTo(404);
    }

    @Test
    void goodsAreSoldWithAVisitOnItsOrderOrAnOnlineDepositsOrderAndStockIsChecked() throws Exception {
        Shop shop = shop(true);
        String oil = variantOf(product(shop, "O-" + UUID.randomUUID().toString().substring(0, 6), 25_000, true));
        receive(shop, oil, 4);
        open(shop);

        // A visit with no order: the services and the oil on one order; a discount is on the services only.
        String visit = deskVisit(shop, 3);
        Call done = checkout(
                shop,
                visit,
                Map.of(
                        "products", List.of(Map.of("variantId", oil, "quantity", 1)),
                        "discount", Map.of("kind", "PERCENT", "value", 10, "reason", "Loyal client"),
                        "cashTenderedMinor", 70_000));
        assertThat(done.status()).as(String.valueOf(done.body())).isEqualTo(200);
        assertThat(done.body().get("totalMinor").asLong())
                .as("45,000 for the services and 25,000 for the oil")
                .isEqualTo(70_000);
        assertThat(done.body().get("discountMinor").asLong()).isEqualTo(5_000);
        assertThat(onHand(shop, oil)).isEqualTo(3);
        UUID order = UUID.fromString(done.body().get("orderId").asString());
        assertThat(system.fetchValue(
                        "select count(*) from commerce.order_lines where order_id = ? and line_type = 'PRODUCT'",
                        order))
                .isEqualTo(1L);

        // A visit whose deposit was paid online: the oil goes on its order and the balance grows by its price.
        Paid paid = payDeposit(shop, 4);
        JsonNode before = call(
                        get(shop.salon().path("/appointments/" + paid.appointmentId() + "/checkout")),
                        null,
                        shop.salon().owner().token())
                .body();
        assertThat(before.get("dueMinor").asLong()).isEqualTo(40_000);
        // Too many: refused, nothing changes.
        assertThat(checkout(
                                shop,
                                paid.appointmentId(),
                                Map.of(
                                        "products",
                                        List.of(Map.of("variantId", oil, "quantity", 9)),
                                        "cashTenderedMinor",
                                        999_999))
                        .status())
                .isEqualTo(409);
        assertThat(onHand(shop, oil)).isEqualTo(3);
        assertThat(system.fetchValue(
                        "select status from scheduling.appointments where id = ?::uuid",
                        UUID.fromString(paid.appointmentId())))
                .isEqualTo("CONFIRMED");
        Call second = checkout(
                shop,
                paid.appointmentId(),
                Map.of("products", List.of(Map.of("variantId", oil, "quantity", 2)), "cashTenderedMinor", 90_000));
        assertThat(second.status()).as(String.valueOf(second.body())).isEqualTo(200);
        assertThat(second.body().get("totalMinor").asLong()).isEqualTo(100_000);
        assertThat(second.body().get("cashMinor").asLong())
                .as("40,000 for the rest of the visit and 50,000 for two oils")
                .isEqualTo(90_000);
        assertThat(onHand(shop, oil)).isEqualTo(1);
        assertThat(ledgerSum()).isZero();
        JsonNode receipt = call(
                        get(shop.salon()
                                .path("/orders/" + second.body().get("orderId").asString() + "/receipt")),
                        null,
                        shop.salon().owner().token())
                .body();
        assertThat(receipt.get("lines").toString()).contains("Argan oil");
        assertThat(receipt.get("lines").size()).isEqualTo(2);
    }
}
