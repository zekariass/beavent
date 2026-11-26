package com.beavent.backend.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.beavent.backend.ledger.Ledger.Code;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Plan 4.1: the cash drawer is opened with a float and closed with a count; a visit is checked out at the counter in cash
 * with tips for the team, completed, and given a receipt with the next gapless number. A visit with no order gets one; a
 * visit whose deposit was paid online pays only the balance. Cash moves nothing in the ledger (the fees on a cash sale are
 * plan 4.4).
 */
class CounterApiTests extends PayoutTestSupport {

    private String drawer(Shop shop) {
        return shop.salon().path("/cash-drawer");
    }

    private JsonNode drawerState(Shop shop, Person who) throws Exception {
        return call(get(drawer(shop) + "?locationId=" + shop.salon().location1()), null, who.token())
                .body();
    }

    private Person owner(Shop shop) {
        return new Person(shop.salon().owner().phone(), shop.salon().owner().token());
    }

    private Call open(Shop shop, long floatMinor) throws Exception {
        return callInvalid(
                post(drawer(shop) + "/open"),
                Map.of("locationId", shop.salon().location1().toString(), "openingFloatMinor", floatMinor),
                shop.salon().owner().token());
    }

    /** A phone booking: confirmed, no order. */
    private String deskVisit(Shop shop, int daysAhead, String hhmm, String staffId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("locationId", shop.salon().location1().toString());
        body.put("start", at(day().plusDays(daysAhead), hhmm).toInstant().toString());
        body.put("items", List.of(Map.of("variantId", shop.variant(), "staffId", staffId)));
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

    private String checkoutPath(Shop shop, String appointmentId) {
        return shop.salon().path("/appointments/" + appointmentId + "/checkout");
    }

    private Call checkout(Shop shop, String appointmentId, Map<String, Object> body, String token) throws Exception {
        return callInvalid(post(checkoutPath(shop, appointmentId)), body, token);
    }

    private JsonNode bill(Shop shop, String appointmentId) throws Exception {
        return call(
                        get(checkoutPath(shop, appointmentId)),
                        null,
                        shop.salon().owner().token())
                .body();
    }

    private Object value(String sql, Object... args) {
        return system.fetchValue(sql, args);
    }

    @Test
    void theDrawerIsOpenedWithAFloatKeptByMovementsAndClosedWithACount() throws Exception {
        Shop shop = shop(true);
        String token = shop.salon().owner().token();
        assertThat(drawerState(shop, owner(shop)).hasNonNull("session")).isFalse();
        assertThat(open(shop, -5).status()).isEqualTo(400);

        Call opened = open(shop, 5_000);
        assertThat(opened.status()).as(String.valueOf(opened.body())).isEqualTo(201);
        assertThat(opened.body().get("status").asString()).isEqualTo("OPEN");
        assertThat(opened.body().get("expectedMinor").asLong()).isEqualTo(5_000);
        assertThat(opened.body().get("drawerName").asString()).isEqualTo("Front desk");
        assertThat(open(shop, 1_000).status()).as("one open drawer").isEqualTo(409);
        String session = opened.text("id");

        String movements = drawer(shop) + "/" + session + "/movements";
        assertThat(call(post(movements), Map.of("kind", "PAY_IN", "amountMinor", 1_000, "note", "Float top-up"), token)
                        .body()
                        .get("expectedMinor")
                        .asLong())
                .isEqualTo(6_000);
        assertThat(callInvalid(post(movements), Map.of("kind", "PAY_OUT", "amountMinor", 7_000), token)
                        .status())
                .as("not more than there is")
                .isEqualTo(422);
        assertThat(callInvalid(post(movements), Map.of("kind", "SALE", "amountMinor", 100), token)
                        .status())
                .isEqualTo(400);
        Call out = call(post(movements), Map.of("kind", "PAY_OUT", "amountMinor", 1_500, "note", "Milk"), token);
        assertThat(out.body().get("payOutMinor").asLong()).isEqualTo(1_500);
        assertThat(out.body().get("payInMinor").asLong()).isEqualTo(1_000);
        Call drop = call(post(movements), Map.of("kind", "DROP", "amountMinor", 500), token);
        assertThat(drop.body().get("expectedMinor").asLong()).isEqualTo(4_000);
        assertThat(drop.body().get("movements").size()).isEqualTo(3);

        // Closed with a count: what was expected, and what is missing.
        Call closed = call(post(drawer(shop) + "/" + session + "/close"), Map.of("countedMinor", 3_800), token);
        assertThat(closed.body().get("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.body().get("expectedMinor").asLong()).isEqualTo(4_000);
        assertThat(closed.body().get("countedMinor").asLong()).isEqualTo(3_800);
        assertThat(closed.body().get("varianceMinor").asLong()).isEqualTo(-200);
        assertThat(callInvalid(post(drawer(shop) + "/" + session + "/close"), Map.of("countedMinor", 3_800), token)
                        .status())
                .isEqualTo(409);
        assertThat(callInvalid(post(movements), Map.of("kind", "PAY_IN", "amountMinor", 100), token)
                        .status())
                .as("a closed drawer takes nothing")
                .isEqualTo(409);
        JsonNode state = drawerState(shop, owner(shop));
        assertThat(state.hasNonNull("session")).isFalse();
        assertThat(state.get("history").size()).isEqualTo(1);
        assertThat(state.get("history").get(0).get("varianceMinor").asLong()).isEqualTo(-200);
        assertThat(open(shop, 0).status()).as("it can be opened again").isEqualTo(201);

        // Who may do what: a cashier keeps the drawer, a professional only sells.
        Person cashier = newMember(shop.salon(), "CASHIER");
        Person professional = newMember(shop.salon(), "PROFESSIONAL");
        assertThat(call(get(drawer(shop) + "?locationId=" + shop.salon().location1()), null, professional.token())
                        .status())
                .isEqualTo(200);
        assertThat(callInvalid(
                                post(drawer(shop) + "/open"),
                                Map.of("locationId", shop.salon().location1().toString(), "openingFloatMinor", 0),
                                professional.token())
                        .status())
                .isEqualTo(403);
        assertThat(callInvalid(post(movements), Map.of("kind", "PAY_IN", "amountMinor", 100), cashier.token())
                        .status())
                .as("that session is closed, but the cashier is let in")
                .isEqualTo(409);
        Person inventory = newMember(shop.salon(), "INVENTORY_MANAGER");
        assertThat(callInvalid(get(drawer(shop) + "?locationId=" + shop.salon().location1()), null, inventory.token())
                        .status())
                .isEqualTo(403);
    }

    @Test
    void aVisitWithNoOrderIsCheckedOutInCashWithTipsAndAReceipt() throws Exception {
        Shop shop = shop(true);
        String token = shop.salon().owner().token();
        String visit = deskVisit(shop, 3, "10:00", shop.abebe());
        long ledgerBefore = ledgerSum();

        JsonNode before = bill(shop, visit);
        assertThat(before.get("totalMinor").asLong()).isEqualTo(50_000);
        assertThat(before.get("paidMinor").asLong()).isZero();
        assertThat(before.get("dueMinor").asLong()).isEqualTo(50_000);
        assertThat(before.get("canCheckout").asBoolean()).isTrue();
        assertThat(before.get("drawerOpen").asBoolean()).isFalse();
        assertThat(before.get("lines").size()).isEqualTo(1);
        assertThat(before.get("team").size()).isEqualTo(1);
        assertThat(before.get("team").get(0).get("staffId").asString()).isEqualTo(shop.abebe());

        Map<String, Object> body = Map.of(
                "tips",
                List.of(
                        Map.of("staffId", shop.abebe(), "amountMinor", 3_000),
                        Map.of("staffId", shop.sara(), "amountMinor", 2_000)),
                "cashTenderedMinor",
                60_000);
        assertThat(checkout(shop, visit, body, token).status()).as("no drawer").isEqualTo(409);
        String session = open(shop, 10_000).text("id");

        assertThat(checkout(shop, visit, Map.of("cashTenderedMinor", 40_000), token)
                        .status())
                .as("less than is due")
                .isEqualTo(400);
        assertThat(checkout(
                                shop,
                                visit,
                                Map.of(
                                        "tips",
                                        List.of(Map.of("staffId", shop.abebe(), "amountMinor", -1)),
                                        "cashTenderedMinor",
                                        60_000),
                                token)
                        .status())
                .isEqualTo(400);
        assertThat(checkout(
                                shop,
                                visit,
                                Map.of(
                                        "tips",
                                        List.of(Map.of(
                                                "staffId", UUID.randomUUID().toString(), "amountMinor", 100)),
                                        "cashTenderedMinor",
                                        60_000),
                                token)
                        .status())
                .isEqualTo(404);
        assertThat(value("select status from scheduling.appointments where id = ?::uuid", UUID.fromString(visit)))
                .as("a refused checkout changes nothing")
                .isEqualTo("CONFIRMED");

        Call done = checkout(shop, visit, body, token);
        assertThat(done.status()).as(String.valueOf(done.body())).isEqualTo(200);
        JsonNode d = done.body();
        assertThat(d.get("totalMinor").asLong()).isEqualTo(55_000);
        assertThat(d.get("paidOnlineMinor").asLong()).isZero();
        assertThat(d.get("tipMinor").asLong()).isEqualTo(5_000);
        assertThat(d.get("cashMinor").asLong()).isEqualTo(55_000);
        assertThat(d.get("tenderedMinor").asLong()).isEqualTo(60_000);
        assertThat(d.get("changeMinor").asLong()).isEqualTo(5_000);
        assertThat(d.get("receiptNumber").asString()).matches("R\\d{7}");

        UUID visitId = UUID.fromString(visit);
        assertThat(value("select status from scheduling.appointments where id = ?::uuid", visitId))
                .isEqualTo("COMPLETED");
        UUID order = UUID.fromString(d.get("orderId").asString());
        assertThat(system.fetchOne(
                                "select status, payment_status, total_minor, paid_minor from commerce.orders where id = ?",
                                order)
                        .intoList())
                .containsExactly("COMPLETED", "PAID", 55_000L, 55_000L);
        assertThat(system.fetch(
                                "select staff_id::text, total_minor from commerce.order_lines where order_id = ? and line_type = 'TIP' order by total_minor",
                                order)
                        .map(r -> r.get(0) + ":" + r.get(1)))
                .containsExactly(shop.sara() + ":2000", shop.abebe() + ":3000");
        assertThat(value(
                        "select count(*) from commerce.order_lines where order_id = ? and line_type = 'SERVICE'",
                        order))
                .isEqualTo(1L);
        // The sale is in the drawer, net of the change; the cash payment is recorded.
        JsonNode state = drawerState(shop, owner(shop));
        assertThat(state.get("session").get("salesMinor").asLong()).isEqualTo(55_000);
        assertThat(state.get("session").get("expectedMinor").asLong()).isEqualTo(65_000);
        assertThat(system.fetchOne(
                                "select a.method_code, a.status, a.amount_minor from payments.payment_attempts a"
                                        + " join payments.payment_intents i on i.id = a.intent_id where i.order_id = ?",
                                order)
                        .intoList())
                .containsExactly("CASH", "SUCCEEDED", 55_000L);
        assertThat(ledgerSum())
                .as("cash is not in the ledger until the fees on it are (4.4)")
                .isEqualTo(ledgerBefore);

        // Once only; the bill says so.
        assertThat(checkout(shop, visit, body, token).status()).isEqualTo(409);
        JsonNode after = bill(shop, visit);
        assertThat(after.get("canCheckout").asBoolean()).isFalse();
        assertThat(after.get("receiptNumber").asString())
                .isEqualTo(d.get("receiptNumber").asString());

        // Receipt numbers go on without gaps, across visits.
        String second = deskVisit(shop, 4, "10:00", shop.abebe());
        Call again = checkout(shop, second, Map.of("cashTenderedMinor", 50_000), token);
        assertThat(again.status()).as(String.valueOf(again.body())).isEqualTo(200);
        assertThat(again.body().get("changeMinor").asLong()).isZero();
        assertThat(again.body().get("receiptNumber").asString()).isEqualTo("R0000002");
        assertThat(d.get("receiptNumber").asString()).isEqualTo("R0000001");

        // The drawer closes with the cash that should be there.
        Call closed = call(post(drawer(shop) + "/" + session + "/close"), Map.of("countedMinor", 115_000), token);
        assertThat(closed.body().get("expectedMinor").asLong()).isEqualTo(115_000);
        assertThat(closed.body().get("varianceMinor").asLong()).isZero();
    }

    @Test
    void aVisitWhoseDepositWasPaidOnlinePaysTheBalanceAtTheCounter() throws Exception {
        Shop shop = shop(true);
        String token = shop.salon().owner().token();
        Paid paid = payDeposit(shop, 3);
        long pending = owed(shop, Code.SELLER_PENDING);
        long ledgerBefore = ledgerSum();

        JsonNode before = bill(shop, paid.appointmentId());
        assertThat(before.get("totalMinor").asLong()).isEqualTo(50_000);
        assertThat(before.get("paidMinor").asLong()).isEqualTo(10_000);
        assertThat(before.get("dueMinor").asLong()).isEqualTo(40_000);
        open(shop, 0);
        Call done = checkout(
                shop,
                paid.appointmentId(),
                Map.of(
                        "tips",
                        List.of(Map.of("staffId", shop.abebe(), "amountMinor", 1_000)),
                        "cashTenderedMinor",
                        41_000),
                token);
        assertThat(done.status()).as(String.valueOf(done.body())).isEqualTo(200);
        assertThat(done.body().get("paidOnlineMinor").asLong()).isEqualTo(10_000);
        assertThat(done.body().get("cashMinor").asLong()).isEqualTo(41_000);
        assertThat(done.body().get("totalMinor").asLong()).isEqualTo(51_000);
        UUID order = UUID.fromString(done.body().get("orderId").asString());
        assertThat(system.fetchOne("select status, payment_status, paid_minor from commerce.orders where id = ?", order)
                        .intoList())
                .containsExactly("COMPLETED", "PAID", 51_000L);
        assertThat(value(
                        "select status from scheduling.appointments where id = ?::uuid",
                        UUID.fromString(paid.appointmentId())))
                .isEqualTo("COMPLETED");
        assertThat(owed(shop, Code.SELLER_PENDING))
                .as("the online deposit is unchanged")
                .isEqualTo(pending);
        assertThat(ledgerSum()).isEqualTo(ledgerBefore);
        assertThat(drawerState(shop, owner(shop))
                        .get("session")
                        .get("expectedMinor")
                        .asLong())
                .isEqualTo(41_000);
        assertThat(checkout(shop, paid.appointmentId(), Map.of("cashTenderedMinor", 41_000), token)
                        .status())
                .isEqualTo(409);
    }

    @Test
    void aVisitThatCannotBePaidYetIsRefusedAndTheCounterIsForPeopleWhoSell() throws Exception {
        Shop shop = shop(true);
        String token = shop.salon().owner().token();
        open(shop, 0);

        // Waiting for its deposit: nothing to take at the counter yet.
        Paid waiting = bookUnpaid(shop, 4);
        JsonNode bill = bill(shop, waiting.appointmentId());
        assertThat(bill.get("canCheckout").asBoolean()).isFalse();
        assertThat(checkout(shop, waiting.appointmentId(), Map.of("cashTenderedMinor", 50_000), token)
                        .status())
                .isEqualTo(409);

        // Cancelled.
        String visit = deskVisit(shop, 5, "10:00", shop.abebe());
        call(post(shop.salon().path("/appointments/" + visit + "/cancel")), Map.of(), token);
        assertThat(checkout(shop, visit, Map.of("cashTenderedMinor", 50_000), token)
                        .status())
                .isEqualTo(409);

        // A cashier and a professional sell; someone who does not sell cannot even see the bill.
        String sold = deskVisit(shop, 6, "10:00", shop.abebe());
        Person inventory = newMember(shop.salon(), "INVENTORY_MANAGER");
        assertThat(callInvalid(get(checkoutPath(shop, sold)), null, inventory.token())
                        .status())
                .isEqualTo(403);
        assertThat(checkout(shop, sold, Map.of("cashTenderedMinor", 50_000), inventory.token())
                        .status())
                .isEqualTo(403);
        Person cashier = newMember(shop.salon(), "CASHIER");
        Call done = checkout(shop, sold, Map.of("cashTenderedMinor", 50_000), cashier.token());
        assertThat(done.status()).as(String.valueOf(done.body())).isEqualTo(200);

        // Someone else's business cannot see the visit.
        Shop other = shop(true);
        assertThat(callInvalid(
                                get(other.salon().path("/appointments/" + sold + "/checkout")),
                                null,
                                other.salon().owner().token())
                        .status())
                .isEqualTo(404);
    }
}
