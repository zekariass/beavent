package com.beavent.backend.scheduling;

import static com.beavent.db.comms.Tables.NOTIFICATIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.beavent.backend.payments.FakeArifPay;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Plan 3.3: a booking whose policy asks for a deposit is held until it is paid through the active provider (ArifPay,
 * here the in-process {@link FakeArifPay}); the payment is confirmed only by a lookup with Beavent's key, and the
 * awkward cases (late, duplicate, declined, provider down, wrong amount) end in a booking that is right and money that
 * is accounted for.
 */
class PaymentFlowApiTests extends SchedulingTestSupport {

    @Autowired
    FakeArifPay arifpay;

    /** A haircut costs Br 500 (50,000 minor); the policy asks new customers for 20 %: Br 100 now, Br 400 at the venue. */
    private Shop depositShop() throws Exception {
        Shop shop = shop();
        Call policy = call(
                put(shop.salon().path("/booking-policies")),
                Map.of("deposit", Map.of("mode", "PERCENT", "percent", 20, "appliesTo", "NEW_CUSTOMERS")),
                shop.salon().owner().token());
        assertThat(policy.status()).as(String.valueOf(policy.body())).isEqualTo(200);
        return shop;
    }

    private Call hold(Shop shop, Instant start) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(shop.variant(), shop.abebe())));
        body.put("start", start.toString());
        return call(post("/v1/public/locations/" + shop.salon().location1() + "/holds"), body, null);
    }

    private Call book(Shop shop, Call hold, String token) throws Exception {
        return call(
                post("/v1/me/appointments"),
                Map.of(
                        "locationId", shop.salon().location1().toString(),
                        "holdId", hold.text("holdId"),
                        "holderKey", hold.text("holderKey")),
                token);
    }

    private Instant at10(int daysAhead) {
        return at(day().plusDays(daysAhead), "10:00").toInstant();
    }

    private Call startPayment(String appointmentId, String token) throws Exception {
        return call(post("/v1/me/appointments/" + appointmentId + "/payment"), Map.of("language", "en"), token);
    }

    private JsonNode detail(String appointmentId, String token) throws Exception {
        return call(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/v1/me/appointments/" + appointmentId),
                        null,
                        token)
                .body();
    }

    private String status(String appointmentId) {
        return (String) system.fetchValue(
                "select status from scheduling.appointments where id = ?::uuid", UUID.fromString(appointmentId));
    }

    private String orderStatus(String appointmentId) {
        return (String) system.fetchValue(
                "select o.status from commerce.orders o join scheduling.appointments a on a.order_id = o.id where a.id = ?::uuid",
                UUID.fromString(appointmentId));
    }

    private String nonceOf(String appointmentId) {
        return (String) system.fetchValue(
                "select t.id::text from payments.payment_attempts t join payments.payment_intents i on i.id = t.intent_id "
                        + "join scheduling.appointments a on a.order_id = i.order_id where a.id = ?::uuid "
                        + "order by t.initiated_at desc limit 1",
                UUID.fromString(appointmentId));
    }

    private String attemptStatus(String attemptId) {
        return (String) system.fetchValue(
                "select status from payments.payment_attempts where id = ?::uuid", UUID.fromString(attemptId));
    }

    private void notify(String nonce) throws Exception {
        FakeArifPay.Session s = arifpay.session(nonce);
        // ArifPay's notification: unsigned, and only a cue to look the session up.
        assertThat(call(
                                post("/v1/hooks/arifpay"),
                                Map.of("sessionId", s.sessionId, "nonce", nonce, "transactionStatus", "SUCCESS"),
                                null)
                        .status())
                .isEqualTo(200);
    }

    private long confirmations(String appointmentId) {
        UUID customer = (UUID) system.fetchValue(
                "select customer_id from scheduling.appointments where id = ?::uuid", UUID.fromString(appointmentId));
        return system.fetchCount(
                NOTIFICATIONS,
                NOTIFICATIONS
                        .TEMPLATE_CODE
                        .eq("APPOINTMENT_CONFIRMED")
                        .and(NOTIFICATIONS.RECIPIENT_CUSTOMER_ID.eq(customer)));
    }

    @Test
    void theReviewStepShowsWhatIsPaidNowAndWhatIsLeftForTheVenue() throws Exception {
        Shop shop = depositShop();
        Call held = hold(shop, at10(3));
        assertThat(held.status()).as(String.valueOf(held.body())).isEqualTo(201);
        JsonNode payment = held.body().get("payment");
        assertThat(payment.get("depositMinor").asLong()).isEqualTo(10_000);
        assertThat(payment.get("atVenueMinor").asLong()).isEqualTo(40_000);
        assertThat(payment.get("totalMinor").asLong()).isEqualTo(50_000);
        assertThat(payment.get("currency").asString()).isEqualTo("ETB");
        // A business without a deposit has nothing to show.
        Shop free = shop();
        assertThat(hold(free, at10(3)).body().has("payment")).isFalse();
    }

    @Test
    void aBookingWaitsForItsDepositAndIsConfirmedOnlyWhenTheProviderSaysPaid() throws Exception {
        Shop shop = depositShop();
        Person person = newPerson();
        Call booked = book(shop, hold(shop, at10(3)), person.token());
        assertThat(booked.status()).as(String.valueOf(booked.body())).isEqualTo(201);
        String id = booked.text("id");
        assertThat(booked.text("status")).isEqualTo("PENDING_PAYMENT");
        JsonNode pending = booked.body().get("payment");
        assertThat(pending.get("state").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(pending.get("dueNowMinor").asLong()).isEqualTo(10_000);
        assertThat(pending.get("atVenueMinor").asLong()).isEqualTo(40_000);

        // The order and its schedule exist and the time is reserved; nothing was sent to the customer.
        assertThat(orderStatus(id)).isEqualTo("PENDING_PAYMENT");
        assertThat(system.fetch(
                                "select s.kind, s.amount_minor, s.collect_at, s.status from commerce.payment_schedule s "
                                        + "join scheduling.appointments a on a.order_id = s.order_id where a.id = ?::uuid order by s.seq",
                                UUID.fromString(id))
                        .map(r -> r.get(0) + ":" + r.get(1) + ":" + r.get(2) + ":" + r.get(3)))
                .containsExactly("DEPOSIT:10000:ONLINE:DUE", "BALANCE:40000:AT_VENUE:DUE");
        assertThat(confirmations(id)).isZero();

        // Starting the payment opens the provider's page for exactly the deposit; asking again reopens the same one.
        Call started = startPayment(id, person.token());
        assertThat(started.status()).as(String.valueOf(started.body())).isEqualTo(200);
        assertThat(started.text("redirectUrl")).startsWith(arifpay.url() + "/pay/");
        assertThat(started.body().get("amountMinor").asLong()).isEqualTo(10_000);
        String nonce = started.text("attemptId");
        FakeArifPay.Session session = arifpay.session(nonce);
        assertThat(session.totalMinor()).isEqualTo(10_000);
        long createsBefore = arifpay.creates();
        assertThat(startPayment(id, person.token()).text("attemptId")).isEqualTo(nonce);
        assertThat(arifpay.creates())
                .as("asking again reopens the page without a second session")
                .isEqualTo(createsBefore);

        // A notification alone proves nothing: the lookup still says nobody paid.
        notify(nonce);
        Thread.sleep(1_500);
        assertThat(status(id)).isEqualTo("PENDING_PAYMENT");

        // The payer approves; the next notification makes the worker look the session up and confirm.
        arifpay.pay(nonce);
        notify(nonce);
        BookingMessagesTests.waitFor(
                "the booking is confirmed", () -> status(id).equals("CONFIRMED"));
        assertThat(orderStatus(id)).isEqualTo("CONFIRMED");
        var order = system.fetchOne(
                "select paid_minor, payment_status from commerce.orders o join scheduling.appointments a on a.order_id = o.id where a.id = ?::uuid",
                UUID.fromString(id));
        assertThat(order.get(0)).isEqualTo(10_000L);
        assertThat(order.get(1)).isEqualTo("PARTIALLY_PAID"); // the balance is paid at the venue
        assertThat(attemptStatus(nonce)).isEqualTo("SUCCEEDED");
        assertThat(system.fetchValue(
                        "select s.status from commerce.payment_schedule s join scheduling.appointments a on a.order_id = s.order_id "
                                + "where a.id = ?::uuid and s.kind = 'DEPOSIT'",
                        UUID.fromString(id)))
                .isEqualTo("PAID");
        BookingMessagesTests.waitFor("the confirmation text", () -> confirmations(id) == 1);
        JsonNode after = detail(id, person.token());
        assertThat(after.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(after.get("payment").get("state").asString()).isEqualTo("PAID");
        assertThat(after.get("payment").get("paidMinor").asLong()).isEqualTo(10_000);
        // Paying again is refused: it is already paid.
        assertThat(startPayment(id, person.token()).status()).isEqualTo(409);
    }

    @Test
    void comingBackFromThePaymentPageConfirmsWithoutAnyNotification() throws Exception {
        Shop shop = depositShop();
        Person person = newPerson();
        String id = book(shop, hold(shop, at10(4)), person.token()).text("id");
        String nonce = startPayment(id, person.token()).text("attemptId");
        // Not paid yet: checking leaves it waiting.
        JsonNode waiting = call(post("/v1/me/appointments/" + id + "/payment/check"), null, person.token())
                .body();
        assertThat(waiting.get("status").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(waiting.get("payment").get("state").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(waiting.get("payment").get("redirectUrl").asString()).contains("/pay/");

        arifpay.pay(nonce); // ArifPay does not notify (it never retries): the person's return does the asking
        JsonNode paid = call(post("/v1/me/appointments/" + id + "/payment/check"), null, person.token())
                .body();
        assertThat(paid.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(paid.get("payment").get("state").asString()).isEqualTo("PAID");
        // Nobody else can ask about it.
        assertThat(call(
                                post("/v1/me/appointments/" + id + "/payment/check"),
                                null,
                                newPerson().token())
                        .status())
                .isEqualTo(404);
    }

    @Test
    void aMissingNotificationIsCaughtByPolling() throws Exception {
        Shop shop = depositShop();
        Person person = newPerson();
        String id = book(shop, hold(shop, at10(5)), person.token()).text("id");
        String nonce = startPayment(id, person.token()).text("attemptId");
        arifpay.pay(nonce); // no notification, no one comes back
        BookingMessagesTests.waitFor("polling confirms it", () -> status(id).equals("CONFIRMED"), 40_000);
    }

    @Test
    void aDeclinedPaymentCanBeTriedAgainWhileTheTimeIsHeld() throws Exception {
        Shop shop = depositShop();
        Person person = newPerson();
        String id = book(shop, hold(shop, at10(6)), person.token()).text("id");
        String first = startPayment(id, person.token()).text("attemptId");
        arifpay.decline(first);
        JsonNode declined = call(post("/v1/me/appointments/" + id + "/payment/check"), null, person.token())
                .body();
        assertThat(declined.get("payment").get("state").asString()).isEqualTo("FAILED");
        assertThat(attemptStatus(first)).isEqualTo("FAILED");
        assertThat(status(id)).isEqualTo("PENDING_PAYMENT");

        Call second = startPayment(id, person.token());
        assertThat(second.status()).as(String.valueOf(second.body())).isEqualTo(200);
        assertThat(second.text("attemptId")).isNotEqualTo(first);
        arifpay.pay(second.text("attemptId"));
        assertThat(call(post("/v1/me/appointments/" + id + "/payment/check"), null, person.token())
                        .text("status"))
                .isEqualTo("CONFIRMED");
    }

    @Test
    void aSecondApprovalForTheSamePaymentIsRecordedAsADuplicateAndQueuedForRefund() throws Exception {
        Shop shop = depositShop();
        Person person = newPerson();
        String id = book(shop, hold(shop, at10(7)), person.token()).text("id");
        String first = startPayment(id, person.token()).text("attemptId");
        arifpay.decline(first);
        call(post("/v1/me/appointments/" + id + "/payment/check"), null, person.token());
        String second = startPayment(id, person.token()).text("attemptId");

        // The payer had approved the first prompt after all, and approves the second as well.
        FakeArifPay.Session firstSession = arifpay.session(first);
        firstSession.status = "SUCCESS";
        notify(first);
        BookingMessagesTests.waitFor(
                "the first payment confirms it", () -> status(id).equals("CONFIRMED"));
        assertThat(attemptStatus(first)).isEqualTo("SUCCEEDED");

        arifpay.pay(second);
        notify(second);
        BookingMessagesTests.waitFor(
                "the second is recorded as a duplicate",
                () -> attemptStatus(second).equals("SUCCEEDED_DUPLICATE"));
        var refund = system.fetchOne(
                "select amount_minor, reason, status, method from payments.refunds where attempt_id = ?::uuid",
                UUID.fromString(second));
        assertThat(refund.get(0)).isEqualTo(10_000L);
        assertThat(refund.get(1)).isEqualTo("DUPLICATE_PAYMENT");
        assertThat(refund.get(2)).isEqualTo("REQUESTED"); // finance decides (slice 3.6); never counted as captured
        // The booking is untouched and counted once.
        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(system.fetchValue(
                        "select paid_minor from commerce.orders o join scheduling.appointments a on a.order_id = o.id where a.id = ?::uuid",
                        UUID.fromString(id)))
                .isEqualTo(10_000L);
    }

    @Test
    void anUnpaidBookingExpiresAndFreesItsTimeAndAPaymentThatStillArrivesConfirmsItAgainIfTheTimeIsFree()
            throws Exception {
        Shop shop = depositShop();
        Person person = newPerson();
        Instant when = at10(8);
        String id = book(shop, hold(shop, when), person.token()).text("id");
        String nonce = startPayment(id, person.token()).text("attemptId");
        system.execute(
                "update commerce.orders o set expires_at = now() - interval '1 minute' from scheduling.appointments a "
                        + "where a.order_id = o.id and a.id = ?::uuid",
                UUID.fromString(id));

        BookingMessagesTests.waitFor(
                "the unpaid booking expires", () -> status(id).equals("EXPIRED"));
        assertThat(orderStatus(id)).isEqualTo("EXPIRED");
        assertThat(attemptStatus(nonce)).isEqualTo("EXPIRED");
        assertThat(system.fetchValue(
                        "select status from payments.payment_intents where id = (select intent_id from payments.payment_attempts where id = ?::uuid)",
                        UUID.fromString(nonce)))
                .isEqualTo("EXPIRED");
        assertThat(arifpay.session(nonce).cancelled).isTrue();
        assertThat(starts(search(shop, day().plusDays(8), item(shop.variant(), shop.abebe()))))
                .contains("10:00"); // the time is back on the calendar
        assertThat(startPayment(id, person.token()).status()).isEqualTo(409); // no longer waiting for payment
        assertThat(confirmations(id)).isZero();

        // A payment that still arrives (the provider's page was open) finds the time free: the booking is back.
        arifpay.session(nonce).status = "SUCCESS";
        notify(nonce);
        BookingMessagesTests.waitFor(
                "the late payment confirms it again", () -> status(id).equals("CONFIRMED"));
        assertThat(orderStatus(id)).isEqualTo("CONFIRMED");
        assertThat(system.fetchCount(
                        com.beavent.db.payments.Tables.REFUNDS,
                        com.beavent.db.payments.Tables.REFUNDS.ATTEMPT_ID.eq(UUID.fromString(nonce))))
                .isZero();
        assertThat(starts(search(shop, day().plusDays(8), item(shop.variant(), shop.abebe()))))
                .doesNotContain("10:00");
    }

    @Test
    void aLatePaymentForATimeSomeoneElseTookGoesBackToTheCustomer() throws Exception {
        Shop shop = depositShop();
        Person person = newPerson();
        Instant when = at10(9);
        String id = book(shop, hold(shop, when), person.token()).text("id");
        String nonce = startPayment(id, person.token()).text("attemptId");
        system.execute(
                "update commerce.orders o set expires_at = now() - interval '1 minute' from scheduling.appointments a "
                        + "where a.order_id = o.id and a.id = ?::uuid",
                UUID.fromString(id));
        BookingMessagesTests.waitFor(
                "the unpaid booking expires", () -> status(id).equals("EXPIRED"));

        // The front desk gives the freed time to someone else.
        Call desk = call(
                post(shop.salon().path("/appointments")),
                Map.of(
                        "locationId", shop.salon().location1().toString(),
                        "start", when.toString(),
                        "items", List.of(item(shop.variant(), shop.abebe())),
                        "customer", Map.of("name", "Walk-in"),
                        "source", "PHONE"),
                shop.salon().owner().token());
        assertThat(desk.status()).as(String.valueOf(desk.body())).isEqualTo(201);

        arifpay.session(nonce).status = "SUCCESS";
        notify(nonce);
        BookingMessagesTests.waitFor(
                "the late payment is refunded",
                () -> system.fetchCount(
                                com.beavent.db.payments.Tables.REFUNDS,
                                com.beavent.db.payments.Tables.REFUNDS.ATTEMPT_ID.eq(UUID.fromString(nonce)))
                        == 1);
        var refund = system.fetchOne(
                "select amount_minor, reason, status from payments.refunds where attempt_id = ?::uuid",
                UUID.fromString(nonce));
        assertThat(refund.get(0)).isEqualTo(10_000L);
        assertThat(refund.get(1)).isEqualTo("ORDER_ADJUSTMENT");
        assertThat(refund.get(2)).isEqualTo("APPROVED"); // the whole payment goes back; finance pays it (slice 3.6)
        assertThat(status(id)).isEqualTo("EXPIRED"); // the booking stays closed: its time belongs to someone else
        assertThat(attemptStatus(nonce)).isEqualTo("SUCCEEDED");
        assertThat(confirmations(id)).isZero();
    }

    @Test
    void cancellingAnUnpaidBookingClosesItsOrderAndItsPayment() throws Exception {
        Shop shop = depositShop();
        Person person = newPerson();
        String id = book(shop, hold(shop, at10(10)), person.token()).text("id");
        String nonce = startPayment(id, person.token()).text("attemptId");
        Call cancelled = call(post("/v1/me/appointments/" + id + "/cancel"), Map.of(), person.token());
        assertThat(cancelled.status()).as(String.valueOf(cancelled.body())).isEqualTo(200);
        assertThat(cancelled.body().get("cancellation").get("free").asBoolean()).isTrue();
        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(orderStatus(id)).isEqualTo("CANCELLED");
        assertThat(attemptStatus(nonce)).isEqualTo("CANCELLED");
        assertThat(arifpay.session(nonce).cancelled).isTrue();
        assertThat(startPayment(id, person.token()).status()).isEqualTo(409);
    }

    @Test
    void whenTheProviderIsDownThePersonIsToldAndCanTryAgain() throws Exception {
        Shop shop = depositShop();
        Person person = newPerson();
        String id = book(shop, hold(shop, at10(11)), person.token()).text("id");
        arifpay.failCreateWith = 503;
        try {
            Call down = callInvalid(
                    post("/v1/me/appointments/" + id + "/payment"), Map.of("language", "en"), person.token());
            assertThat(down.status()).isEqualTo(503);
            assertThat(down.text("code")).isEqualTo("PAYMENT_UNAVAILABLE");
        } finally {
            arifpay.failCreateWith = 0;
        }
        assertThat(status(id)).isEqualTo("PENDING_PAYMENT"); // the time is still held
        Call again = startPayment(id, person.token());
        assertThat(again.status()).as(String.valueOf(again.body())).isEqualTo(200);
        arifpay.pay(again.text("attemptId"));
        assertThat(call(post("/v1/me/appointments/" + id + "/payment/check"), null, person.token())
                        .text("status"))
                .isEqualTo("CONFIRMED");
    }

    @Test
    void anAnswerWithTheWrongAmountIsNeverActedOn() throws Exception {
        Shop shop = depositShop();
        Person person = newPerson();
        String id = book(shop, hold(shop, at10(12)), person.token()).text("id");
        String nonce = startPayment(id, person.token()).text("attemptId");
        FakeArifPay.Session session = arifpay.session(nonce);
        session.paidAs = new java.math.BigDecimal("1.00"); // the provider reports a different amount
        arifpay.pay(nonce);
        JsonNode checked = call(post("/v1/me/appointments/" + id + "/payment/check"), null, person.token())
                .body();
        assertThat(checked.get("status").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(attemptStatus(nonce)).isEqualTo("UNKNOWN");
        assertThat(system.fetchValue(
                        "select failure_message from payments.payment_attempts where id = ?::uuid",
                        UUID.fromString(nonce)))
                .asString()
                .startsWith("AMOUNT_MISMATCH");
        assertThat(system.fetchValue(
                        "select paid_minor from commerce.orders o join scheduling.appointments a on a.order_id = o.id where a.id = ?::uuid",
                        UUID.fromString(id)))
                .isEqualTo(0L);
        assertThat(OffsetDateTime.now()).isNotNull();
    }
}
