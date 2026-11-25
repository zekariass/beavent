package com.beavent.backend.scheduling;

import static com.beavent.db.iam.Tables.USERS;
import static com.beavent.db.iam.Tables.USER_STAFF_ROLES;
import static com.beavent.db.ref.Tables.MARKETS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.beavent.backend.ledger.Ledger.Code;
import com.beavent.backend.payments.Refunds;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Plan 3.6: a booking that falls through settles what its customer paid by the business's own terms. Free cancellation
 * gives the deposit back; a late cancellation or a no-show keeps the fee and refunds the rest; the business cancelling
 * always refunds in full. The refund comes out of what the seller is owed (with the platform's fees on it given
 * back), finance pays it by hand and marks it paid, and the customer is told.
 */
class RefundFlowApiTests extends PayoutTestSupport {

    @Autowired
    Refunds refunds;

    /** Policy: a 20 % deposit, free cancellation up to {@code windowMinutes} before, late fee and no-show fee in percent. */
    private Shop shopWithTerms(int windowMinutes, int lateFee, int noShowFee) throws Exception {
        Shop shop = shop(true);
        Call policy = call(
                put(shop.salon().path("/booking-policies")),
                Map.of(
                        "deposit", Map.of("mode", "PERCENT", "percent", 20, "appliesTo", "NEW_CUSTOMERS"),
                        "cancellationWindowMinutes", windowMinutes,
                        "lateCancelFeePercent", lateFee,
                        "noShowFeePercent", noShowFee),
                shop.salon().owner().token());
        assertThat(policy.status()).as(String.valueOf(policy.body())).isEqualTo(200);
        return shop;
    }

    private JsonNode refundOf(String appointmentId) {
        var row = system.fetchOne(
                "select r.amount_minor, r.status, r.method, r.reason, r.requested_by from payments.refunds r "
                        + "join scheduling.appointments a on a.order_id = r.order_id where a.id = ?::uuid",
                UUID.fromString(appointmentId));
        if (row == null) return null;
        return json.valueToTree(Map.of(
                "amount", row.get(0),
                "status", row.get(1),
                "method", row.get(2),
                "reason", row.get(3),
                "by", row.get(4)));
    }

    private void waitForRefund(String appointmentId) throws InterruptedException {
        BookingMessagesTests.waitFor("the refund is made", () -> refundOf(appointmentId) != null);
    }

    private JsonNode detail(Paid paid) throws Exception {
        return call(get("/v1/me/appointments/" + paid.appointmentId()), null, paid.token())
                .body();
    }

    private Call cancel(Paid paid) throws Exception {
        return call(post("/v1/me/appointments/" + paid.appointmentId() + "/cancel"), Map.of(), paid.token());
    }

    private UUID refundId(String appointmentId) {
        return (UUID) system.fetchValue(
                "select r.id from payments.refunds r join scheduling.appointments a on a.order_id = r.order_id where a.id = ?::uuid",
                UUID.fromString(appointmentId));
    }

    private UUID orderOf(Paid paid) {
        return (UUID) system.fetchValue(
                "select order_id from scheduling.appointments where id = ?::uuid",
                UUID.fromString(paid.appointmentId()));
    }

    @Test
    void cancellingInTimeGivesTheDepositBackAndTakesTheFeesBackFromThePlatform() throws Exception {
        Shop shop = shopWithTerms(60, 0, 0); // free until an hour before
        long revenueBefore = platform(Code.PLATFORM_FEE_REVENUE);
        Paid paid = payDeposit(shop, 3);

        JsonNode before = detail(paid).get("cancellation");
        assertThat(before.get("free").asBoolean()).isTrue();
        assertThat(before.get("refundMinor").asLong()).isEqualTo(10_000);
        assertThat(before.get("keptMinor").asLong()).isZero();
        assertThat(detail(paid).has("refund")).isFalse();

        assertThat(cancel(paid).status()).isEqualTo(200);
        waitForRefund(paid.appointmentId());
        JsonNode refund = refundOf(paid.appointmentId());
        assertThat(refund.get("amount").asLong()).isEqualTo(10_000);
        assertThat(refund.get("status").asString()).isEqualTo("APPROVED");
        assertThat(refund.get("method").asString())
                .as("ArifPay cannot refund by API")
                .isEqualTo("MANUAL_BANK_TRANSFER");
        assertThat(refund.get("by").asString()).isEqualTo("CUSTOMER");

        // The seller is owed nothing for it, the platform's fees on it are given back, the customer is owed Br 100.
        assertThat(owed(shop, Code.SELLER_PENDING)).isZero();
        assertThat(platform(Code.PLATFORM_FEE_REVENUE) - revenueBefore)
                .as("fee revenue is back to where it was")
                .isZero();
        assertThat(system.fetchValue(
                        "select status from ledger.scheduled_releases where org_id = ?::uuid",
                        shop.salon().id()))
                .isEqualTo("CANCELLED");
        assertThat(ledgerSum()).isZero();

        // The customer sees it on its way, and is told when it is paid.
        JsonNode after = detail(paid).get("refund");
        assertThat(after.get("amountMinor").asLong()).isEqualTo(10_000);
        assertThat(after.get("state").asString()).isEqualTo("ON_THE_WAY");
        UUID id = refundId(paid.appointmentId());
        refunds.markPaid(id, reviewer(), "TELEBIRR-7788", "Sent to the customer's phone");
        assertThat(detail(paid).get("refund").get("state").asString()).isEqualTo("SENT");
        BookingMessagesTests.waitFor(
                "the refund text",
                () -> sms.messagesTo(paid.phone()).stream()
                        .anyMatch(m -> m.contains("was refunded to you") && m.contains("Br 100")));

        // Settling again changes nothing.
        refunds.settleOrder(shop.salon().id(), orderOf(paid), Instant.now());
        assertThat(system.fetchValue("select count(*) from payments.refunds where order_id = ?::uuid", orderOf(paid)))
                .isEqualTo(1L);
        assertThat(ledgerSum()).isZero();
    }

    @Test
    void aLateCancellationKeepsTheFeeAndRefundsTheRest() throws Exception {
        Shop shop = shopWithTerms(43_200, 10, 0); // free only until 30 days before; a 10 % late fee on Br 500
        Paid paid = payDeposit(shop, 3);

        JsonNode preview = detail(paid).get("cancellation");
        assertThat(preview.get("free").asBoolean()).isFalse();
        assertThat(preview.get("feeMinor").asLong()).isEqualTo(5_000);
        assertThat(preview.get("keptMinor").asLong()).isEqualTo(5_000);
        assertThat(preview.get("refundMinor").asLong()).isEqualTo(5_000);

        cancel(paid);
        waitForRefund(paid.appointmentId());
        assertThat(refundOf(paid.appointmentId()).get("amount").asLong()).isEqualTo(5_000);

        // Of the Br 88.50 the seller was owed, Br 44.25 stays: Br 100 paid, less fees Br 11.50, less Br 50 refunded,
        // plus
        // the fees on that Br 50 given back (Br 5.75).
        assertThat(owed(shop, Code.SELLER_PENDING)).isEqualTo(4_425);
        var release = system.fetchOne(
                "select amount_minor, status, settled from ledger.scheduled_releases where org_id = ?::uuid",
                shop.salon().id());
        assertThat(release.get(0) + ":" + release.get(1) + ":" + release.get(2)).isEqualTo("4425:SCHEDULED:true");

        // The fee is released like any sale, though no visit took place.
        payouts.releaseDue(weeksAfterTheVisit());
        assertThat(owed(shop, Code.SELLER_AVAILABLE)).isEqualTo(4_425);
        assertThat(ledgerSum()).isZero();
        assertThat(detail(paid).get("refund").get("amountMinor").asLong()).isEqualTo(5_000);
    }

    @Test
    void theBusinessCancellingAlwaysRefundsInFullEvenInsideTheLateWindow() throws Exception {
        Shop shop = shopWithTerms(43_200, 100, 100);
        Paid paid = payDeposit(shop, 3);
        Call cancelled = call(
                post(shop.salon().path("/appointments/" + paid.appointmentId() + "/cancel")),
                Map.of("scope", "ONE", "reason", "The stylist is ill"),
                shop.salon().owner().token());
        assertThat(cancelled.status()).as(String.valueOf(cancelled.body())).isEqualTo(200);
        waitForRefund(paid.appointmentId());
        JsonNode refund = refundOf(paid.appointmentId());
        assertThat(refund.get("amount").asLong()).isEqualTo(10_000);
        assertThat(refund.get("by").asString()).isEqualTo("ORGANIZATION");
        assertThat(owed(shop, Code.SELLER_PENDING)).isZero();
    }

    @Test
    void aNoShowKeepsTheFeeUpToTheDepositAndRefundsTheExcess() throws Exception {
        Shop shop = shopWithTerms(60, 0, 5); // a 5 % no-show fee on Br 500: Br 25
        Paid partial = payDeposit(shop, 3);
        system.execute(
                "update scheduling.appointments set status = 'NO_SHOW' where id = ?::uuid",
                UUID.fromString(partial.appointmentId()));
        refunds.settleOrder(shop.salon().id(), orderOf(partial), Instant.now());
        JsonNode refund = refundOf(partial.appointmentId());
        assertThat(refund.get("amount").asLong()).isEqualTo(7_500);
        assertThat(refund.get("reason").asString()).isEqualTo("ORDER_ADJUSTMENT");
        assertThat(refund.get("by").asString()).isEqualTo("SYSTEM");

        // A fee as large as the deposit keeps all of it: nothing is refunded, and the sale is the business's.
        Shop strict = shopWithTerms(60, 0, 100);
        Paid all = payDeposit(strict, 3);
        system.execute(
                "update scheduling.appointments set status = 'NO_SHOW' where id = ?::uuid",
                UUID.fromString(all.appointmentId()));
        refunds.settleOrder(strict.salon().id(), orderOf(all), Instant.now());
        assertThat(refundOf(all.appointmentId())).isNull();
        assertThat(detail(all).get("refund").get("state").asString()).isEqualTo("NONE");
        payouts.releaseDue(weeksAfterTheVisit());
        assertThat(owed(strict, Code.SELLER_AVAILABLE)).isEqualTo(8_850);
        assertThat(ledgerSum()).isZero();
    }

    @Test
    void aRefundAfterTheMoneyWasPaidOutComesOutOfTheNextPayouts() throws Exception {
        Shop shop = shopWithTerms(60, 0, 0);
        Paid paid = payDeposit(shop, 3);
        Instant when = weeksAfterTheVisit();
        payouts.releaseDue(when);
        assertThat(owed(shop, Code.SELLER_AVAILABLE)).isEqualTo(8_850);

        // The visit is cancelled after the release (marked after the fact): the refund is taken from available money.
        system.execute(
                "update scheduling.appointments set status = 'DECLINED' where id = ?::uuid",
                UUID.fromString(paid.appointmentId()));
        refunds.settleOrder(shop.salon().id(), orderOf(paid), when);
        assertThat(refundOf(paid.appointmentId()).get("amount").asLong()).isEqualTo(10_000);
        // 8,850 owed less the 8,850 the seller bore: nothing is available any more.
        assertThat(owed(shop, Code.SELLER_AVAILABLE)).isZero();
        assertThat(ledgerSum()).isZero();
    }

    @Test
    void financePaysRefundsFromAQueueBehindAFreshCodeAndApprovesDuplicates() throws Exception {
        Shop shop = shopWithTerms(60, 0, 0);
        Paid paid = payDeposit(shop, 3);
        cancel(paid);
        waitForRefund(paid.appointmentId());
        String id = refundId(paid.appointmentId()).toString();

        Person finance = financeStaff();
        JsonNode queue = call(get("/v1/admin/refunds?queue=PAY"), null, finance.token())
                .body()
                .get("items");
        JsonNode row = null;
        for (JsonNode r : queue) if (r.get("id").asString().equals(id)) row = r;
        assertThat(row).isNotNull();
        assertThat(row.get("amountMinor").asLong()).isEqualTo(10_000);
        assertThat(row.get("customerPhone").asString()).isEqualTo(paid.phone());
        assertThat(row.get("method").asString()).isEqualTo("MANUAL_BANK_TRANSFER");
        assertThat(callInvalid(
                                get("/v1/admin/refunds"),
                                null,
                                shop.salon().owner().token())
                        .status())
                .isEqualTo(403);
        assertThat(callInvalid(get("/v1/admin/refunds?queue=NOPE"), null, finance.token())
                        .status())
                .isEqualTo(400);

        Map<String, Object> paidBody = Map.of("reference", "TELEBIRR-1234");
        assertThat(callInvalid(post("/v1/admin/refunds/" + id + "/payment"), paidBody, finance.token())
                        .status())
                .isEqualTo(403);
        String step = step(finance);
        assertThat(callInvalid(post("/v1/admin/refunds/" + id + "/payment"), Map.of(), step)
                        .status())
                .isEqualTo(400);
        JsonNode done = call(post("/v1/admin/refunds/" + id + "/payment"), paidBody, step)
                .body();
        assertThat(done.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.get("reference").asString()).isEqualTo("TELEBIRR-1234");
        assertThat(call(post("/v1/admin/refunds/" + id + "/payment"), paidBody, step)
                        .status())
                .isEqualTo(409);
        assertThat(system.fetchCount(
                        com.beavent.db.platform.Tables.AUDIT_LOG,
                        com.beavent.db.platform.Tables.AUDIT_LOG
                                .ACTION
                                .eq("refund.paid")
                                .and(com.beavent.db.platform.Tables.AUDIT_LOG.TARGET_ID.eq(id))))
                .isEqualTo(1);
        JsonNode paidQueue = call(get("/v1/admin/refunds?queue=PAID"), null, finance.token())
                .body()
                .get("items");
        assertThat(paidQueue.toString()).contains(id);

        // A duplicate payment waits for a decision first.
        Paid other = payDeposit(shop, 4);
        system.execute(
                "insert into payments.refunds (org_id, intent_id, attempt_id, order_id, amount_minor, currency_code, reason, method, status, "
                        + "requested_by, idempotency_key) select i.org_id, i.id, t.id, i.order_id, 1000, 'ETB', 'DUPLICATE_PAYMENT', "
                        + "'MANUAL_BANK_TRANSFER', 'REQUESTED', 'SYSTEM', 'dup-' || gen_random_uuid() from payments.payment_intents i "
                        + "join payments.payment_attempts t on t.intent_id = i.id and t.status = 'SUCCEEDED' "
                        + "where i.order_id = (select order_id from scheduling.appointments where id = ?::uuid)",
                UUID.fromString(other.appointmentId()));
        String dup = refundId(other.appointmentId()).toString();
        JsonNode approval = call(get("/v1/admin/refunds?queue=APPROVAL"), null, finance.token())
                .body()
                .get("items");
        assertThat(approval.toString()).contains(dup);
        JsonNode approved = call(
                        post("/v1/admin/refunds/" + dup + "/approval"), Map.of("note", "Same payer twice"), step)
                .body();
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(call(post("/v1/admin/refunds/" + dup + "/approval"), Map.of(), step)
                        .status())
                .isEqualTo(409);
    }

    private Person financeStaff() throws Exception {
        Person person = newPerson();
        UUID userId = system.select(USERS.ID)
                .from(USERS)
                .where(USERS.PHONE_E164.eq(person.phone()))
                .fetchSingle(USERS.ID);
        system.insertInto(USER_STAFF_ROLES)
                .set(USER_STAFF_ROLES.USER_ID, userId)
                .set(USER_STAFF_ROLES.ROLE_CODE, "FINANCE")
                .set(
                        USER_STAFF_ROLES.MARKET_ID,
                        system.select(MARKETS.ID)
                                .from(MARKETS)
                                .where(MARKETS.CODE.eq("ET"))
                                .fetchSingle(MARKETS.ID))
                .execute();
        ageChallenges(person.phone());
        Call signedIn = signInWithOtp(person.phone(), "ADMIN_CONSOLE");
        assertThat(signedIn.status()).as(String.valueOf(signedIn.body())).isEqualTo(200);
        return new Person(person.phone(), signedIn.text("accessToken"));
    }

    private String step(Person person) throws Exception {
        ageChallenges(person.phone());
        return stepUp(person);
    }

    @Test
    void aMissedEventIsCaughtByTheSweepWhenTheReleaseFindsTheBookingGone() throws Exception {
        Shop shop = shopWithTerms(60, 0, 0);
        Paid paid = payDeposit(shop, 3);
        // Cancelled behind the events' back (no message): the release finds it void and holds the money ...
        system.execute(
                "update scheduling.appointments set status = 'CANCELLED', cancelled_at = now(), cancelled_by = 'ORGANIZATION' where id = ?::uuid",
                UUID.fromString(paid.appointmentId()));
        payouts.releaseDue(weeksAfterTheVisit());
        assertThat(system.fetchValue(
                        "select status from ledger.scheduled_releases where org_id = ?::uuid",
                        shop.salon().id()))
                .isEqualTo("ON_HOLD");
        // ... and the sweep settles it.
        refunds.sweep(weeksAfterTheVisit());
        assertThat(refundOf(paid.appointmentId()).get("amount").asLong()).isEqualTo(10_000);
        assertThat(owed(shop, Code.SELLER_PENDING)).isZero();
        assertThat(ledgerSum()).isZero();
    }
}
