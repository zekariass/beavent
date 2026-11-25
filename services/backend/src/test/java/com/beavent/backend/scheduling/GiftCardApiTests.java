package com.beavent.backend.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.beavent.backend.ledger.Ledger.Code;
import com.beavent.backend.retention.GiftCards;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Plan 3.8: a business sells gift cards, a customer buys one (paid through the provider like any order), the recipient
 * gets a code by text message (at once or on a chosen date) and spends it on a booking's deposit; what the card cannot
 * cover is still paid through the provider, and the booking is honoured when all of it is paid. A refund can be kept as
 * credit with the business and spent the same way. The platform's fees on a tender are charged to the business.
 */
class GiftCardApiTests extends PayoutTestSupport {

    @Autowired
    GiftCards cards;

    private Shop cardShop() throws Exception {
        Shop shop = shop(true);
        Call policy = call(
                put(shop.salon().path("/booking-policies")),
                Map.of("deposit", Map.of("mode", "PERCENT", "percent", 20, "appliesTo", "NEW_CUSTOMERS")),
                shop.salon().owner().token());
        assertThat(policy.status()).as(String.valueOf(policy.body())).isEqualTo(200);
        Call program = call(
                put(shop.salon().path("/gift-cards/program")),
                Map.of(
                        "name",
                        "Gift card",
                        "presetsMinor",
                        List.of(20_000, 50_000, 100_000),
                        "minMinor",
                        10_000,
                        "maxMinor",
                        500_000,
                        "validityMonths",
                        12,
                        "sellOnline",
                        true),
                shop.salon().owner().token());
        assertThat(program.status()).as(String.valueOf(program.body())).isEqualTo(200);
        return shop;
    }

    /** A card the business already has, with a known code (the tests do not read texts for it). */
    private void insertCard(Shop shop, String code, long balanceMinor, Instant expires) {
        String normal = code.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        system.execute(
                "insert into retention.gift_cards (org_id, program_id, code_hash, code_last4, currency_code, initial_minor, "
                        + "balance_minor, status, delivered_at, expires_at) select ?::uuid, p.id, encode(sha256(convert_to(?, 'UTF8')), 'hex'), ?, "
                        + "'ETB', ?, ?, 'ACTIVE', now(), ? from catalog.gift_card_programs p where p.org_id = ?::uuid",
                shop.salon().id(),
                normal,
                normal.substring(12),
                balanceMinor,
                balanceMinor,
                expires == null ? null : java.sql.Timestamp.from(expires),
                shop.salon().id());
    }

    private long cardBalance(Shop shop, String code) {
        String normal = code.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        return (Long) system.fetchValue(
                "select balance_minor from retention.gift_cards where org_id = ?::uuid and code_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')",
                shop.salon().id(),
                normal);
    }

    private static String freshCode() {
        String letters = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(letters.charAt((int) (Math.random() * letters.length())));
        }
        return sb.toString();
    }

    private Call tender(Paid booking, String kind, String code) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", kind);
        if (code != null) body.put("code", code);
        return call(post("/v1/me/appointments/" + booking.appointmentId() + "/payment/tender"), body, booking.token());
    }

    private String status(String appointmentId) {
        return (String) system.fetchValue(
                "select status from scheduling.appointments where id = ?::uuid", UUID.fromString(appointmentId));
    }

    @Test
    void aBusinessSetsUpWhatItSellsAndTheShopWindowShowsIt() throws Exception {
        Shop shop = shop(true);
        String owner = shop.salon().owner().token();
        assertThat(call(get(shop.salon().path("/gift-cards/program")), null, owner)
                        .body()
                        .path("program")
                        .isObject())
                .as("nothing is set up yet")
                .isFalse();
        String location = shop.salon().location1().toString();
        assertThat(call(get("/v1/public/locations/" + location + "/gift-cards"), null, null)
                        .body()
                        .get("available")
                        .asBoolean())
                .as("nothing is sold until it is set up")
                .isFalse();

        // Limits and amounts are checked.
        Map<String, Object> bad = Map.of(
                "name",
                "Gift card",
                "presetsMinor",
                List.of(5_000),
                "minMinor",
                10_000,
                "maxMinor",
                500_000,
                "sellOnline",
                true);
        assertThat(callInvalid(put(shop.salon().path("/gift-cards/program")), bad, owner)
                        .status())
                .isEqualTo(400);
        Shop sells = cardShop();
        JsonNode offer = call(get("/v1/public/locations/" + sells.salon().location1() + "/gift-cards"), null, null)
                .body();
        assertThat(offer.get("available").asBoolean()).isTrue();
        assertThat(offer.get("currency").asString()).isEqualTo("ETB");
        assertThat(offer.get("presetsMinor").toString()).isEqualTo("[20000,50000,100000]");
        assertThat(offer.get("minMinor").asLong()).isEqualTo(10_000);

        // Only people who manage the catalogue.
        Person professional = newMember(sells.salon(), "PROFESSIONAL");
        assertThat(callInvalid(get(sells.salon().path("/gift-cards/program")), null, professional.token())
                        .status())
                .isEqualTo(403);
    }

    @Test
    void aCustomerBuysACardPaysAndTheRecipientGetsItsCodeByText() throws Exception {
        Shop shop = cardShop();
        Person buyer = newPerson();
        String recipientPhone = newPhone();
        Call bought = call(
                post("/v1/me/gift-cards/purchases"),
                Map.of(
                        "locationId",
                        shop.salon().location1().toString(),
                        "amountMinor",
                        50_000,
                        "recipientName",
                        "Selam",
                        "recipientPhone",
                        recipientPhone,
                        "message",
                        "Happy birthday"),
                buyer.token());
        assertThat(bought.status()).as(String.valueOf(bought.body())).isEqualTo(201);
        String token = bought.text("payUrl").substring(bought.text("payUrl").lastIndexOf('/') + 1);

        // The page says what is being bought.
        JsonNode page =
                call(get("/v1/public/payment-links/" + token), null, null).body();
        assertThat(page.get("subject").asString()).isEqualTo("GIFT_CARD");
        assertThat(page.get("amountMinor").asLong()).isEqualTo(50_000);
        assertThat(page.get("items").toString()).contains("Selam");

        // Not delivered before it is paid.
        assertThat(cards.deliverDue(Instant.now())).isZero();
        assertThat(call(
                                get(shop.salon().path("/gift-cards")),
                                null,
                                shop.salon().owner().token())
                        .body()
                        .get("items")
                        .size())
                .as("unpaid cards are not listed")
                .isZero();

        Call started = call(post("/v1/public/payment-links/" + token + "/start"), Map.of("language", "en"), null);
        assertThat(started.status()).as(String.valueOf(started.body())).isEqualTo(200);
        arifpay.pay(started.text("attemptId"));
        assertThat(call(post("/v1/public/payment-links/" + token + "/check"), null, null)
                        .body()
                        .get("state")
                        .asString())
                .isEqualTo("PAID");

        assertThat(cards.deliverDue(Instant.now())).isEqualTo(1);
        assertThat(cards.deliverDue(Instant.now())).as("delivered once").isZero();
        BookingMessagesTests.waitFor(
                "the recipient's text", () -> !sms.messagesTo(recipientPhone).isEmpty());
        String text = sms.lastMessageTo(recipientPhone);
        Matcher m = Pattern.compile("([A-Z2-9]{4}(?:-[A-Z2-9]{4}){3})").matcher(text);
        assertThat(m.find()).as(text).isTrue();
        String code = m.group(1);
        assertThat(text).contains("Happy birthday");
        assertThat(text.contains("Br 500") || text.contains("ብር 500")).isTrue();

        // The business sees it, without the code; the sale is its money.
        JsonNode row = call(
                        get(shop.salon().path("/gift-cards")),
                        null,
                        shop.salon().owner().token())
                .body()
                .get("items")
                .get(0);
        assertThat(row.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(row.get("codeLast4").asString())
                .isEqualTo(code.replace("-", "").substring(12));
        assertThat(row.get("balanceMinor").asLong()).isEqualTo(50_000);
        assertThat(row.get("recipientName").asString()).isEqualTo("Selam");
        assertThat(row.toString()).doesNotContain(code);
        assertThat(system.fetchValue("select count(*) from retention.gift_cards where code_hash = ?", code))
                .as("only a hash is kept")
                .isEqualTo(0L);
        assertThat(owed(shop, Code.SELLER_PENDING)).isEqualTo(50_000);
        assertThat(ledgerSum()).isZero();

        // The card works for the business that sold it.
        Shop other = cardShop();
        Paid booking = bookUnpaid(other, 4);
        assertThat(tender(booking, "GIFT_CARD", code).status())
                .as("another business's card")
                .isEqualTo(404);
        Paid mine = bookUnpaid(shop, 5);
        Call used = tender(mine, "GIFT_CARD", code.toLowerCase());
        assertThat(used.status()).as(String.valueOf(used.body())).isEqualTo(200);
        assertThat(used.text("status")).isEqualTo("CONFIRMED");
    }

    @Test
    void aCardChosenForALaterDateIsDeliveredThen() throws Exception {
        Shop shop = cardShop();
        Person buyer = newPerson();
        String recipientPhone = newPhone();
        Instant later = Instant.now().plus(10, ChronoUnit.DAYS);
        Call bought = call(
                post("/v1/me/gift-cards/purchases"),
                Map.of(
                        "locationId",
                        shop.salon().location1().toString(),
                        "amountMinor",
                        20_000,
                        "recipientName",
                        "Hiwot",
                        "recipientPhone",
                        recipientPhone,
                        "deliverAt",
                        later.toString()),
                buyer.token());
        String token = bought.text("payUrl").substring(bought.text("payUrl").lastIndexOf('/') + 1);
        String nonce = call(post("/v1/public/payment-links/" + token + "/start"), Map.of("language", "en"), null)
                .text("attemptId");
        arifpay.pay(nonce);
        call(post("/v1/public/payment-links/" + token + "/check"), null, null);

        assertThat(cards.deliverDue(Instant.now())).as("not before its date").isZero();
        assertThat(sms.messagesTo(recipientPhone)).isEmpty();
        assertThat(cards.deliverDue(later.plusSeconds(60))).isEqualTo(1);
        BookingMessagesTests.waitFor(
                "the recipient's text", () -> !sms.messagesTo(recipientPhone).isEmpty());
        assertThat(sms.lastMessageTo(recipientPhone))
                .containsPattern("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}");
    }

    @Test
    void aCardPaysTheWholeDepositAndTheBusinessIsChargedTheFees() throws Exception {
        Shop shop = cardShop();
        String code = freshCode();
        insertCard(shop, code, 50_000, null);
        Paid booking = bookUnpaid(shop, 4);

        Call covered = tender(booking, "GIFT_CARD", code);
        assertThat(covered.status()).as(String.valueOf(covered.body())).isEqualTo(200);
        assertThat(covered.text("status")).isEqualTo("CONFIRMED");
        assertThat(covered.body().get("payment").get("state").asString()).isEqualTo("PAID");
        assertThat(covered.body().get("payment").get("paidMinor").asLong()).isEqualTo(10_000);
        assertThat(cardBalance(shop, code)).isEqualTo(40_000);
        assertThat(system.fetchValue(
                        "select paid_minor from commerce.orders o join scheduling.appointments a on a.order_id = o.id where a.id = ?::uuid",
                        UUID.fromString(booking.appointmentId())))
                .isEqualTo(10_000L);
        // No money arrived; the business, already paid when the card was sold, is charged the fees on Br 100 of Br 500.
        assertThat(owed(shop, Code.SELLER_PENDING)).isZero();
        assertThat(owed(shop, Code.SELLER_AVAILABLE)).isEqualTo(-1_150);
        assertThat(ledgerSum()).isZero();
        assertThat(system.fetchValue(
                        "select count(*) from payments.payment_attempts t join payments.payment_intents i on i.id = t.intent_id "
                                + "join scheduling.appointments a on a.order_id = i.order_id where a.id = ?::uuid and t.method_code = 'GIFT_CARD'",
                        UUID.fromString(booking.appointmentId())))
                .isEqualTo(1L);
        // Nothing is left to pay.
        assertThat(call(post("/v1/me/appointments/" + booking.appointmentId() + "/payment"), Map.of(), booking.token())
                        .status())
                .isEqualTo(409);
    }

    @Test
    void aCardThatCoversPartOfTheDepositLeavesTheRestToTheProvider() throws Exception {
        Shop shop = cardShop();
        String code = freshCode();
        insertCard(shop, code, 6_000, null);
        Paid booking = bookUnpaid(shop, 4);

        Call part = tender(booking, "GIFT_CARD", code);
        assertThat(part.status()).as(String.valueOf(part.body())).isEqualTo(200);
        assertThat(part.text("status")).as("not confirmed until all is paid").isEqualTo("PENDING_PAYMENT");
        assertThat(part.body().get("payment").get("dueNowMinor").asLong()).isEqualTo(4_000);
        assertThat(part.body().get("payment").get("paidMinor").asLong()).isEqualTo(6_000);
        assertThat(cardBalance(shop, code)).isZero();

        Call started = call(
                post("/v1/me/appointments/" + booking.appointmentId() + "/payment"),
                Map.of("language", "en"),
                booking.token());
        assertThat(started.status()).as(String.valueOf(started.body())).isEqualTo(200);
        assertThat(started.body().get("amountMinor").asLong()).isEqualTo(4_000);
        assertThat(arifpay.session(started.text("attemptId")).totalMinor()).isEqualTo(4_000);
        arifpay.pay(started.text("attemptId"));
        Call done =
                call(post("/v1/me/appointments/" + booking.appointmentId() + "/payment/check"), null, booking.token());
        assertThat(done.text("status")).isEqualTo("CONFIRMED");
        assertThat(done.body().get("payment").get("paidMinor").asLong()).isEqualTo(10_000);

        // The provider's part is owed to the business less its fees; the card's part is charged the fees on it.
        assertThat(owed(shop, Code.SELLER_PENDING)).isEqualTo(4_000 - 460);
        assertThat(owed(shop, Code.SELLER_AVAILABLE)).isEqualTo(-690);
        assertThat(ledgerSum()).isZero();
    }

    @Test
    void aCardThatCannotBeUsedIsRefusedWithoutSayingWhy() throws Exception {
        Shop shop = cardShop();
        String expired = freshCode();
        insertCard(shop, expired, 50_000, Instant.now().minus(1, ChronoUnit.DAYS));
        String empty = freshCode();
        insertCard(shop, empty, 50_000, null);
        system.execute(
                "update retention.gift_cards set status = 'VOID' where org_id = ?::uuid and code_last4 = ?",
                shop.salon().id(),
                empty.replace("-", "").substring(12));
        Paid booking = bookUnpaid(shop, 4);
        assertThat(tender(booking, "GIFT_CARD", expired).status()).isEqualTo(404);
        assertThat(tender(booking, "GIFT_CARD", empty).status()).isEqualTo(404);
        assertThat(tender(booking, "GIFT_CARD", "NOPE").status()).isEqualTo(404);
        assertThat(callInvalid(
                                post("/v1/me/appointments/" + booking.appointmentId() + "/payment/tender"),
                                Map.of("kind", "GIFT_CARD", "code", ""),
                                booking.token())
                        .status())
                .isEqualTo(404);
        assertThat(status(booking.appointmentId())).isEqualTo("PENDING_PAYMENT");

        // Someone else's booking cannot be paid for through this endpoint.
        String code = freshCode();
        insertCard(shop, code, 50_000, null);
        assertThat(callInvalid(
                                post("/v1/me/appointments/" + booking.appointmentId() + "/payment/tender"),
                                Map.of("kind", "GIFT_CARD", "code", code),
                                newPerson().token())
                        .status())
                .isEqualTo(404);
        // A card paying for a card is refused.
        Person buyer = newPerson();
        Call bought = call(
                post("/v1/me/gift-cards/purchases"),
                Map.of("locationId", shop.salon().location1().toString(), "amountMinor", 20_000),
                buyer.token());
        String token = bought.text("payUrl").substring(bought.text("payUrl").lastIndexOf('/') + 1);
        assertThat(callInvalid(post("/v1/public/payment-links/" + token + "/tender"), Map.of("code", code), null)
                        .status())
                .isEqualTo(409);
    }

    @Test
    void aBookingThatIsNotPaidInTimeGivesTheCardItsMoneyBack() throws Exception {
        Shop shop = cardShop();
        String code = freshCode();
        insertCard(shop, code, 6_000, null);
        Paid booking = bookUnpaid(shop, 4);
        assertThat(tender(booking, "GIFT_CARD", code).status()).isEqualTo(200);
        assertThat(cardBalance(shop, code)).isZero();
        assertThat(owed(shop, Code.SELLER_AVAILABLE)).isEqualTo(-690);

        system.execute(
                "update commerce.orders set expires_at = now() - interval '1 minute' where id = (select order_id from scheduling.appointments where id = ?::uuid)",
                UUID.fromString(booking.appointmentId()));
        BookingMessagesTests.waitFor(
                "the booking expires", () -> status(booking.appointmentId()).equals("EXPIRED"));
        BookingMessagesTests.waitFor("the card gets its money back", () -> cardBalance(shop, code) == 6_000);
        assertThat(owed(shop, Code.SELLER_AVAILABLE))
                .as("the fees on it are given back")
                .isZero();
        assertThat(ledgerSum()).isZero();
        assertThat(system.fetchValue(
                        "select count(*) from payments.refunds r join scheduling.appointments a on a.order_id = r.order_id where a.id = ?::uuid and r.method = 'GIFT_CARD' and r.status = 'SUCCEEDED'",
                        UUID.fromString(booking.appointmentId())))
                .isEqualTo(1L);
    }

    @Test
    void cancellingKeepsTheRefundAsCreditWhichPaysTheNextBooking() throws Exception {
        Shop shop = cardShop();
        Paid paid = payDeposit(shop, 3);
        assertThat(owed(shop, Code.SELLER_PENDING)).isEqualTo(10_000 - 1_150);
        long revenueBefore =
                ledger.balance(com.beavent.backend.ledger.Ledger.Account.platform(Code.PLATFORM_FEE_REVENUE), "ETB");

        Call cancelled = call(
                post("/v1/me/appointments/" + paid.appointmentId() + "/cancel"),
                Map.of("refundTo", "BALANCE"),
                paid.token());
        assertThat(cancelled.status()).as(String.valueOf(cancelled.body())).isEqualTo(200);
        BookingMessagesTests.waitFor("the credit is made", () -> creditOf(paid) == 10_000);
        JsonNode detail = call(get("/v1/me/appointments/" + paid.appointmentId()), null, paid.token())
                .body();
        assertThat(detail.get("refund").get("state").asString()).isEqualTo("CREDITED");
        assertThat(detail.get("refund").get("amountMinor").asLong()).isEqualTo(10_000);
        assertThat(system.fetchValue(
                        "select r.method || ':' || r.status from payments.refunds r join scheduling.appointments a on a.order_id = r.order_id where a.id = ?::uuid",
                        UUID.fromString(paid.appointmentId())))
                .isEqualTo("STORE_CREDIT:SUCCEEDED");
        // The business keeps the money (it owes the credit); the fees on the deposit were given back.
        assertThat(owed(shop, Code.SELLER_PENDING)).isEqualTo(10_000);
        assertThat(ledger.balance(com.beavent.backend.ledger.Ledger.Account.platform(Code.PLATFORM_FEE_REVENUE), "ETB"))
                .isEqualTo(revenueBefore + 1_000);
        assertThat(ledgerSum()).isZero();
        BookingMessagesTests.waitFor(
                "the credit text",
                () -> sms.messagesTo(paid.phone()).stream()
                        .anyMatch(m -> m.contains("ብር 100") || m.contains("Br 100")));

        // The next booking is paid from the credit; the fees are charged on it then.
        Call second = call(post("/v1/me/appointments"), bookingBody(shop, 5, paid), paid.token());
        assertThat(second.status()).as(String.valueOf(second.body())).isEqualTo(201);
        String id = second.text("id");
        JsonNode pending =
                call(get("/v1/me/appointments/" + id), null, paid.token()).body();
        assertThat(pending.get("payment").get("balanceMinor").asLong()).isEqualTo(10_000);
        Paid booking = new Paid(id, null, paid.token(), paid.phone());
        Call spent = tender(booking, "BALANCE", null);
        assertThat(spent.status()).as(String.valueOf(spent.body())).isEqualTo(200);
        assertThat(spent.text("status")).isEqualTo("CONFIRMED");
        assertThat(creditOf(paid)).isZero();
        assertThat(owed(shop, Code.SELLER_AVAILABLE)).isEqualTo(-1_150);
        assertThat(ledgerSum()).isZero();
        // Someone else's balance cannot be spent.
        Paid stranger = bookUnpaid(shop, 6);
        assertThat(tender(stranger, "BALANCE", null).status()).isEqualTo(409);
    }

    private Map<String, Object> bookingBody(Shop shop, int daysAhead, Paid who) throws Exception {
        Map<String, Object> hold = new HashMap<>();
        hold.put("items", List.of(item(shop.variant(), shop.abebe())));
        hold.put("start", at(day().plusDays(daysAhead), "10:00").toInstant().toString());
        Call held = call(post("/v1/public/locations/" + shop.salon().location1() + "/holds"), hold, null);
        return Map.of(
                "locationId", shop.salon().location1().toString(),
                "holdId", held.text("holdId"),
                "holderKey", held.text("holderKey"));
    }

    private long creditOf(Paid who) {
        Object v = system.fetchValue(
                "select coalesce(sum(b.balance_minor), 0) from retention.customer_balances b join crm.customers c on c.id = b.customer_id "
                        + "join iam.users u on u.id = c.user_id where u.phone_e164 = ?",
                who.phone());
        return ((Number) v).longValue();
    }

    @Test
    void aCardPastItsEndIsWrittenOffAndAVoidedCardNoLongerWorks() throws Exception {
        Shop shop = cardShop();
        String code = freshCode();
        insertCard(shop, code, 30_000, Instant.now().plus(1, ChronoUnit.DAYS));
        assertThat(cards.expireDue(Instant.now())).isZero();
        assertThat(cards.expireDue(Instant.now().plus(2, ChronoUnit.DAYS))).isEqualTo(1);
        assertThat(cardBalance(shop, code)).isZero();
        assertThat(system.fetchValue(
                        "select status from retention.gift_cards where org_id = ?::uuid and code_last4 = ?",
                        shop.salon().id(),
                        code.replace("-", "").substring(12)))
                .isEqualTo("EXPIRED");

        // The business voids a card (a reason is needed, and only people who may refund).
        String lost = freshCode();
        insertCard(shop, lost, 30_000, null);
        UUID id = (UUID) system.fetchValue(
                "select id from retention.gift_cards where org_id = ?::uuid and code_last4 = ?",
                shop.salon().id(),
                lost.replace("-", "").substring(12));
        system.execute("update retention.gift_cards set issued_order_line_id = null where id = ?::uuid", id);
        Person professional = newMember(shop.salon(), "PROFESSIONAL");
        assertThat(callInvalid(
                                post(shop.salon().path("/gift-cards/" + id + "/void")),
                                Map.of("reason", "Lost card"),
                                professional.token())
                        .status())
                .isEqualTo(403);
        assertThat(callInvalid(
                                post(shop.salon().path("/gift-cards/" + id + "/void")),
                                Map.of("reason", "no"),
                                shop.salon().owner().token())
                        .status())
                .isEqualTo(400);
        assertThat(call(
                                post(shop.salon().path("/gift-cards/" + id + "/void")),
                                Map.of("reason", "Lost card"),
                                shop.salon().owner().token())
                        .status())
                .isEqualTo(204);
        assertThat(cardBalance(shop, lost)).isZero();
        Paid booking = bookUnpaid(shop, 4);
        assertThat(tender(booking, "GIFT_CARD", lost).status()).isEqualTo(404);
        assertThat(call(
                                post(shop.salon().path("/gift-cards/" + id + "/void")),
                                Map.of("reason", "Again please"),
                                shop.salon().owner().token())
                        .status())
                .isEqualTo(409);
    }

    @Test
    void aCardNobodyPaidForIsClosedWhenTheTimeRunsOut() throws Exception {
        Shop shop = cardShop();
        Person buyer = newPerson();
        Call bought = call(
                post("/v1/me/gift-cards/purchases"),
                Map.of("locationId", shop.salon().location1().toString(), "amountMinor", 20_000),
                buyer.token());
        assertThat(bought.status()).isEqualTo(201);
        String token = bought.text("payUrl").substring(bought.text("payUrl").lastIndexOf('/') + 1);
        assertThat(cards.closeUnpaid(Instant.now())).isZero();
        assertThat(cards.closeUnpaid(Instant.now().plus(2, ChronoUnit.HOURS))).isGreaterThanOrEqualTo(1);
        assertThat(system.fetchValue(
                        "select status from commerce.orders where id = ?::uuid",
                        UUID.fromString(bought.text("orderId"))))
                .isEqualTo("EXPIRED");
        assertThat(system.fetchValue(
                        "select status from retention.gift_cards where id = ?::uuid",
                        UUID.fromString(bought.text("cardId"))))
                .isEqualTo("VOID");
        assertThat(call(get("/v1/public/payment-links/" + token), null, null)
                        .body()
                        .get("state")
                        .asString())
                .isEqualTo("EXPIRED");
        // Amounts outside what the business sells are refused.
        assertThat(callInvalid(
                                post("/v1/me/gift-cards/purchases"),
                                Map.of("locationId", shop.salon().location1().toString(), "amountMinor", 5_000),
                                buyer.token())
                        .status())
                .isEqualTo(400);
    }
}
