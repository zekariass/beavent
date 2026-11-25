package com.beavent.backend.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.beavent.backend.ledger.Ledger.Code;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Plan 3.7: a payment link pays one payment without the payer signing in: a phone booking asks for its deposit by text
 * message, a deposit not yet paid gets a fresh link, and a confirmed booking's balance can be collected. The link is a
 * random token, one is live per payment, and what it pays is confirmed only by the provider's word.
 */
class PaymentLinkApiTests extends PayoutTestSupport {

    private Shop depositShop() throws Exception {
        Shop shop = shop(true);
        Call policy = call(
                put(shop.salon().path("/booking-policies")),
                Map.of("deposit", Map.of("mode", "PERCENT", "percent", 20, "appliesTo", "NEW_CUSTOMERS")),
                shop.salon().owner().token());
        assertThat(policy.status()).as(String.valueOf(policy.body())).isEqualTo(200);
        return shop;
    }

    /** The front desk books a phone customer (with a number, or without) a few days ahead. */
    private String phoneBooking(Shop shop, int daysAhead, String phone) throws Exception {
        Map<String, Object> customer = new HashMap<>();
        customer.put("name", "Selam Bekele");
        if (phone != null) customer.put("phone", phone);
        Map<String, Object> body = new HashMap<>();
        body.put("locationId", shop.salon().location1().toString());
        body.put("start", at(day().plusDays(daysAhead), "10:00").toInstant().toString());
        body.put("items", List.of(Map.of("variantId", shop.variant(), "staffId", shop.abebe())));
        body.put("customer", customer);
        body.put("source", "PHONE");
        body.put("notifyCustomer", false);
        Call booked = call(
                post(shop.salon().path("/appointments")),
                body,
                shop.salon().owner().token());
        assertThat(booked.status()).as(String.valueOf(booked.body())).isEqualTo(201);
        return booked.text("id");
    }

    private Call sendLink(Shop shop, String appointmentId) throws Exception {
        return call(
                post(shop.salon().path("/appointments/" + appointmentId + "/payment-link")),
                null,
                shop.salon().owner().token());
    }

    private static String tokenOf(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private JsonNode info(String token) throws Exception {
        return call(get("/v1/public/payment-links/" + token), null, null).body();
    }

    private Call startLink(String token) throws Exception {
        return call(post("/v1/public/payment-links/" + token + "/start"), Map.of("language", "en"), null);
    }

    private String status(String appointmentId) {
        return (String) system.fetchValue(
                "select status from scheduling.appointments where id = ?::uuid", UUID.fromString(appointmentId));
    }

    private String freePhone() {
        return newPhone();
    }

    @Test
    void aPhoneBookingIsAskedForItsDepositByTextAndConfirmedWhenItIsPaidThroughTheLink() throws Exception {
        Shop shop = depositShop();
        String phone = freePhone();
        String id = phoneBooking(shop, 4, phone);
        assertThat(status(id)).isEqualTo("CONFIRMED");

        Call sent = sendLink(shop, id);
        assertThat(sent.status()).as(String.valueOf(sent.body())).isEqualTo(200);
        assertThat(sent.text("kind")).isEqualTo("DEPOSIT");
        assertThat(sent.body().get("amountMinor").asLong()).isEqualTo(10_000);
        assertThat(sent.text("sentTo")).isEqualTo("SMS");
        assertThat(sent.text("url")).contains("/pay/");
        String token = tokenOf(sent.text("url"));
        // The booking waits for the deposit, and the customer gets the link by text.
        assertThat(status(id)).isEqualTo("PENDING_PAYMENT");
        BookingMessagesTests.waitFor(
                "the link text",
                () -> sms.messagesTo(phone).stream()
                        .anyMatch(m -> m.contains(sent.text("url")) && (m.contains("ብር 100") || m.contains("Br 100"))));

        // What the page shows, without signing in.
        JsonNode page = info(token);
        assertThat(page.get("state").asString()).isEqualTo("OPEN");
        assertThat(page.get("purpose").asString()).isEqualTo("DEPOSIT");
        assertThat(page.get("amountMinor").asLong()).isEqualTo(10_000);
        assertThat(page.get("business").asString()).isNotBlank();
        assertThat(page.get("reference").asString()).hasSize(6);
        assertThat(page.get("items").size()).isEqualTo(1);

        // Paying: the provider's page, then only the provider's word confirms it.
        Call started = startLink(token);
        assertThat(started.status()).as(String.valueOf(started.body())).isEqualTo(200);
        assertThat(started.text("redirectUrl")).startsWith(arifpay.url() + "/pay/");
        String nonce = started.text("attemptId");
        assertThat(arifpay.session(nonce).totalMinor()).isEqualTo(10_000);
        assertThat(call(post("/v1/public/payment-links/" + token + "/check"), null, null)
                        .body()
                        .get("state")
                        .asString())
                .isEqualTo("OPEN");
        assertThat(status(id)).isEqualTo("PENDING_PAYMENT");
        arifpay.pay(nonce);
        assertThat(call(post("/v1/public/payment-links/" + token + "/check"), null, null)
                        .body()
                        .get("state")
                        .asString())
                .isEqualTo("PAID");
        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(startLink(token).status()).as("it is paid").isEqualTo(409);
        // The sale is in the ledger, owed to the seller less fees.
        assertThat(owed(shop, Code.SELLER_PENDING)).isEqualTo(10_000 - 1_150);
        assertThat(ledgerSum()).isZero();
        BookingMessagesTests.waitFor(
                "the confirmation text",
                () -> sms.messagesTo(phone).stream().anyMatch(m -> !m.contains("/pay/") && m.contains("ተረጋግጧል")));
    }

    @Test
    void aFreshLinkReplacesTheEarlierOneAndALinkOutlivesNeitherItsTimeNorItsBooking() throws Exception {
        Shop shop = depositShop();
        String id = phoneBooking(shop, 4, freePhone());
        String first = tokenOf(sendLink(shop, id).text("url"));
        String second = tokenOf(sendLink(shop, id).text("url"));
        assertThat(first).isNotEqualTo(second);
        assertThat(info(first).get("state").asString()).as("revoked").isEqualTo("EXPIRED");
        assertThat(startLink(first).status()).isEqualTo(409);
        assertThat(info(second).get("state").asString()).isEqualTo("OPEN");

        // Only a hash is kept.
        assertThat(system.fetchValue("select count(*) from payments.payment_links where token_hash = ?", second))
                .isEqualTo(0L);

        // A link that ran out is closed.
        system.execute(
                "update payments.payment_links set expires_at = now() - interval '1 minute' where status = 'ACTIVE' and org_id = ?::uuid",
                shop.salon().id());
        assertThat(info(second).get("state").asString()).isEqualTo("EXPIRED");
        assertThat(startLink(second).status()).isEqualTo(409);

        // So is one whose booking was cancelled meanwhile.
        String third = tokenOf(sendLink(shop, id).text("url"));
        assertThat(info(third).get("state").asString()).isEqualTo("OPEN");
        system.execute(
                "update scheduling.appointments set status = 'CANCELLED', cancelled_at = now(), cancelled_by = 'ORGANIZATION' where id = ?::uuid",
                UUID.fromString(id));
        system.execute(
                "update commerce.orders set status = 'CANCELLED' where id = (select order_id from scheduling.appointments where id = ?::uuid)",
                UUID.fromString(id));
        assertThat(info(third).get("state").asString()).isEqualTo("EXPIRED");

        // A made-up token is nothing.
        assertThat(callInvalid(get("/v1/public/payment-links/short"), null, null)
                        .status())
                .isEqualTo(404);
        assertThat(callInvalid(get("/v1/public/payment-links/" + "A".repeat(43)), null, null)
                        .status())
                .isEqualTo(404);
    }

    @Test
    void theBalanceOfAConfirmedBookingCanBeCollectedThroughALink() throws Exception {
        Shop shop = depositShop();
        Paid paid = payDeposit(shop, 3);
        assertThat(owed(shop, Code.SELLER_PENDING)).isEqualTo(10_000 - 1_150);

        Call sent = sendLink(shop, paid.appointmentId());
        assertThat(sent.status()).as(String.valueOf(sent.body())).isEqualTo(200);
        assertThat(sent.text("kind")).isEqualTo("BALANCE");
        assertThat(sent.body().get("amountMinor").asLong()).isEqualTo(40_000);
        String token = tokenOf(sent.text("url"));
        BookingMessagesTests.waitFor(
                "the link text",
                () -> sms.messagesTo(paid.phone()).stream()
                        .anyMatch(m -> m.contains(sent.text("url")) && (m.contains("ብር 400") || m.contains("Br 400"))));
        assertThat(info(token).get("purpose").asString()).isEqualTo("BALANCE");

        Call started = startLink(token);
        assertThat(started.status()).as(String.valueOf(started.body())).isEqualTo(200);
        arifpay.pay(started.text("attemptId"));
        assertThat(call(post("/v1/public/payment-links/" + token + "/check"), null, null)
                        .body()
                        .get("state")
                        .asString())
                .isEqualTo("PAID");

        // The booking is as it was; the order is paid in full; the balance joined the ledger with its share of the
        // fees.
        assertThat(status(paid.appointmentId())).isEqualTo("CONFIRMED");
        var order = system.fetchOne(
                "select o.paid_minor, o.payment_status, o.status from commerce.orders o join scheduling.appointments a on a.order_id = o.id where a.id = ?::uuid",
                UUID.fromString(paid.appointmentId()));
        assertThat(order.get(0)).isEqualTo(50_000L);
        assertThat(order.get(1)).isEqualTo("PAID");
        assertThat(order.get(2)).isEqualTo("CONFIRMED");
        assertThat(system.fetchValue(
                        "select count(*) from commerce.order_status_history h join scheduling.appointments a on a.order_id = h.order_id "
                                + "where a.id = ?::uuid and h.reason like 'Paid late%'",
                        UUID.fromString(paid.appointmentId())))
                .isEqualTo(0L);
        // Of Br 500: fees Br 57.50, owed to the seller Br 442.50, in two releases.
        assertThat(owed(shop, Code.SELLER_PENDING)).isEqualTo(50_000 - 5_750);
        assertThat(system.fetchValue(
                        "select count(*) from ledger.scheduled_releases where org_id = ?::uuid",
                        shop.salon().id()))
                .isEqualTo(2L);
        assertThat(ledgerSum()).isZero();

        // Nothing is left to collect.
        assertThat(sendLink(shop, paid.appointmentId()).status()).isEqualTo(409);
    }

    @Test
    void aCancelledBookingHasNoBalanceToCollectAndTheDeskIsToldWhatIsMissing() throws Exception {
        Shop shop = depositShop();
        Paid paid = payDeposit(shop, 3);
        system.execute(
                "update scheduling.appointments set status = 'CANCELLED', cancelled_at = now(), cancelled_by = 'CUSTOMER' where id = ?::uuid",
                UUID.fromString(paid.appointmentId()));
        assertThat(sendLink(shop, paid.appointmentId()).status()).isEqualTo(409);

        // A client without a phone number or email cannot be sent a link.
        String noContact = phoneBooking(shop, 5, null);
        assertThat(sendLink(shop, noContact).status()).isEqualTo(409);
        // A business that asks for no deposit has nothing to ask.
        Shop free = shop();
        makePaymentsReady(free.salon());
        String plain = phoneBooking(free, 4, freePhone());
        assertThat(sendLink(free, plain).status()).isEqualTo(409);
        // A booking that already started cannot be asked.
        String late = phoneBooking(shop, 6, freePhone());
        system.execute(
                "update scheduling.appointments set starts_at = now() - interval '1 hour', ends_at = now() + interval '1 hour' where id = ?::uuid",
                UUID.fromString(late));
        assertThat(sendLink(shop, late).status()).isEqualTo(409);
        // Only people who work the desk may send one.
        Person professional = newMember(shop.salon(), "PROFESSIONAL");
        assertThat(callInvalid(
                                post(shop.salon()
                                        .path("/appointments/" + phoneBooking(shop, 7, freePhone()) + "/payment-link")),
                                null,
                                professional.token())
                        .status())
                .isEqualTo(403);
    }

    @Test
    void aBusinessThatCannotTakeOnlinePaymentsYetCannotSendLinksAndAProviderThatIsDownKeepsTheLinkOpen()
            throws Exception {
        Shop unready = shop();
        call(
                put(unready.salon().path("/booking-policies")),
                Map.of("deposit", Map.of("mode", "PERCENT", "percent", 20, "appliesTo", "EVERYONE")),
                unready.salon().owner().token());
        String id = phoneBooking(unready, 4, freePhone());
        assertThat(sendLink(unready, id).status())
                .as("not verified, no payout account")
                .isEqualTo(503);

        Shop shop = depositShop();
        String booked = phoneBooking(shop, 4, freePhone());
        String token = tokenOf(sendLink(shop, booked).text("url"));
        arifpay.failCreateWith = 500;
        try {
            assertThat(startLink(token).status()).isEqualTo(503);
        } finally {
            arifpay.failCreateWith = 0;
        }
        assertThat(info(token).get("state").asString())
                .as("still open to try again")
                .isEqualTo("OPEN");
        assertThat(system.fetchValue(
                        "select status from scheduling.appointments where id = ?::uuid", UUID.fromString(booked)))
                .isEqualTo("PENDING_PAYMENT");
        assertThat(startLink(token).status()).isEqualTo(200);
    }
}
