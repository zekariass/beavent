package com.beavent.backend.scheduling;

import static com.beavent.db.iam.Tables.USERS;
import static com.beavent.db.iam.Tables.USER_STAFF_ROLES;
import static com.beavent.db.ref.Tables.MARKETS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.beavent.backend.ledger.Ledger.Code;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Plan 3.5 over HTTP: the seller's earnings and statement, and the finance desk of the admin console (queue, approval,
 * bank batch, paid or failed), each behind its permission and a fresh code, with the seller told of the outcome.
 */
class PayoutDeskApiTests extends PayoutTestSupport {

    /** A paid, released sale with a payout created and waiting for approval. */
    private record Waiting(Shop shop, UUID payoutId, Instant when) {}

    private Waiting waitingPayout() throws Exception {
        Shop shop = shop(true);
        payDeposit(shop, 3);
        Instant when = weeksAfterTheVisit();
        payouts.releaseDue(when);
        payouts.createDue(when);
        return new Waiting(shop, payoutOf(shop), when);
    }

    private JsonNode queue(Person staff, String name) throws Exception {
        return call(get("/v1/admin/payouts?queue=" + name), null, staff.token())
                .body()
                .get("items");
    }

    private JsonNode find(JsonNode items, UUID id) {
        for (JsonNode item : items) if (item.get("id").asString().equals(id.toString())) return item;
        return null;
    }

    @Test
    void theSellerSeesWhatIsOwedWhatIsOnItsWayAndItsStatement() throws Exception {
        Waiting w = waitingPayout();
        Person owner = w.shop().salon().owner();
        JsonNode earnings = call(get(w.shop().salon().path("/earnings")), null, owner.token())
                .body();
        assertThat(earnings.get("currency").asString()).isEqualTo("ETB");
        assertThat(earnings.get("pendingMinor").asLong()).isZero();
        assertThat(earnings.get("availableMinor").asLong()).isZero();
        assertThat(earnings.get("onTheWayMinor").asLong()).isEqualTo(8_850);
        assertThat(earnings.get("schedule").asString()).isEqualTo("WEEKLY");
        assertThat(earnings.get("weekday").asInt()).isEqualTo(1);
        JsonNode payout = earnings.get("payouts").get(0);
        assertThat(payout.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(payout.get("accountMasked").asString()).isEqualTo("••••0001");

        JsonNode statement = call(get(w.shop().salon().path("/statement")), null, owner.token())
                .body();
        assertThat(statement.get("items").size()).isEqualTo(3);
        assertThat(statement.get("items").get(0).get("kind").asString()).isEqualTo("PAYOUT_INITIATED");
        assertThat(statement.get("items").get(0).get("amountMinor").asLong()).isEqualTo(-8_850);
        assertThat(statement.get("page").get("nextCursor").isNull()).isTrue();
        // Paging: one line at a time.
        JsonNode first = call(get(w.shop().salon().path("/statement?limit=1")), null, owner.token())
                .body();
        assertThat(first.get("items").size()).isEqualTo(1);
        String cursor = first.get("page").get("nextCursor").asString();
        JsonNode second = call(get(w.shop().salon().path("/statement?limit=1&cursor=" + cursor)), null, owner.token())
                .body();
        assertThat(second.get("items").get(0).get("kind").asString()).isEqualTo("FEES_ASSESSED");
        assertThat(callInvalid(get(w.shop().salon().path("/statement?cursor=nope")), null, owner.token())
                        .status())
                .isEqualTo(400);

        // Only people who may see payouts do.
        Person professional = newMember(w.shop().salon(), "PROFESSIONAL");
        assertThat(callInvalid(get(w.shop().salon().path("/earnings")), null, professional.token())
                        .status())
                .isEqualTo(403);
    }

    @Test
    void financeApprovesExportsAndMarksPaidBehindFreshCodesAndTheSellerIsTold() throws Exception {
        Waiting w = waitingPayout();
        Person finance = financeStaff();
        String id = w.payoutId().toString();

        JsonNode waiting = find(queue(finance, "APPROVAL"), w.payoutId());
        assertThat(waiting).isNotNull();
        assertThat(waiting.get("organization").asString()).isNotBlank();
        assertThat(waiting.get("amountMinor").asLong()).isEqualTo(8_850);
        assertThat(waiting.get("approvalReasons").toString()).contains("FIRST_PAYOUT");
        assertThat(waiting.get("accountNumberMasked").asString()).isEqualTo("••••0001");
        // The owner is not staff.
        assertThat(callInvalid(
                                get("/v1/admin/payouts"),
                                null,
                                w.shop().salon().owner().token())
                        .status())
                .isEqualTo(403);

        // Deciding needs a fresh code, and a reason; unknown queues are refused.
        Map<String, Object> approve = Map.of("decision", "APPROVE", "reason", "Account checked");
        assertThat(callInvalid(post("/v1/admin/payouts/" + id + "/decision"), approve, finance.token())
                        .status())
                .isEqualTo(403);
        assertThat(callInvalid(get("/v1/admin/payouts?queue=NOPE"), null, finance.token())
                        .status())
                .isEqualTo(400);
        String step = step(finance);
        JsonNode approved = call(post("/v1/admin/payouts/" + id + "/decision"), approve, step)
                .body();
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(call(post("/v1/admin/payouts/" + id + "/decision"), approve, step)
                        .status())
                .isEqualTo(409);
        assertThat(find(queue(finance, "READY"), w.payoutId())).isNotNull();

        // The bank batch is a CSV with the full number, behind a fresh code.
        system.execute(
                "update payments.payout_accounts set details_secret_ref = ? where org_id = ?::uuid",
                box.seal("1000123456789"),
                w.shop().salon().id());
        assertThat(callInvalid(post("/v1/admin/payouts/export"), Map.of(), finance.token())
                        .status())
                .isEqualTo(403);
        Call batch = call(post("/v1/admin/payouts/export"), Map.of("payoutIds", List.of(id)), step);
        assertThat(batch.status()).isEqualTo(200);
        assertThat(batch.raw()).contains(id).contains("1000123456789").contains("88.50");
        assertThat(find(queue(finance, "PROCESSING"), w.payoutId())).isNotNull();
        assertThat(system.fetchCount(
                        com.beavent.db.platform.Tables.AUDIT_LOG,
                        com.beavent.db.platform.Tables.AUDIT_LOG.ACTION.eq("payout.batch_exported")))
                .isGreaterThanOrEqualTo(1);

        // Paid needs the bank's reference.
        Map<String, Object> noReference = Map.of("outcome", "PAID");
        assertThat(callInvalid(post("/v1/admin/payouts/" + id + "/settlement"), noReference, step)
                        .status())
                .isEqualTo(400);
        JsonNode paid = call(
                        post("/v1/admin/payouts/" + id + "/settlement"),
                        Map.of("outcome", "PAID", "reference", "CBE-554433"),
                        step)
                .body();
        assertThat(paid.get("status").asString()).isEqualTo("PAID");
        assertThat(paid.get("reference").asString()).isEqualTo("CBE-554433");
        assertThat(find(queue(finance, "PAID"), w.payoutId())).isNotNull();
        assertThat(system.fetchCount(
                        com.beavent.db.platform.Tables.AUDIT_LOG,
                        com.beavent.db.platform.Tables.AUDIT_LOG
                                .ACTION
                                .eq("payout.paid")
                                .and(com.beavent.db.platform.Tables.AUDIT_LOG.TARGET_ID.eq(id))))
                .isEqualTo(1);
        assertThat(ledgerSum()).isZero();

        // The seller hears about it, and sees it as paid.
        BookingMessagesTests.waitFor(
                "the payout text",
                () -> sms.messagesTo(w.shop().salon().owner().phone()).stream()
                        .anyMatch(m -> m.contains("was sent to") && m.contains("Br 88.50")));
        JsonNode earnings = call(
                        get(w.shop().salon().path("/earnings")),
                        null,
                        w.shop().salon().owner().token())
                .body();
        assertThat(earnings.get("paidOutMinor").asLong()).isEqualTo(8_850);
        assertThat(earnings.get("onTheWayMinor").asLong()).isZero();
    }

    @Test
    void aRejectedPayoutGivesTheMoneyBackAndTheSellerIsToldWhy() throws Exception {
        Waiting w = waitingPayout();
        Person finance = financeStaff();
        String id = w.payoutId().toString();
        String step = step(finance);
        // Reasons are required.
        assertThat(callInvalid(
                                post("/v1/admin/payouts/" + id + "/decision"),
                                Map.of("decision", "REJECT", "reason", "no"),
                                step)
                        .status())
                .isEqualTo(400);
        JsonNode rejected = call(
                        post("/v1/admin/payouts/" + id + "/decision"),
                        Map.of("decision", "REJECT", "reason", "The account holder is not the business"),
                        step)
                .body();
        assertThat(rejected.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(rejected.get("note").asString()).contains("not the business");
        assertThat(find(queue(finance, "FAILED"), w.payoutId())).isNotNull();
        assertThat(owed(w.shop(), Code.SELLER_AVAILABLE)).isEqualTo(8_850);
        BookingMessagesTests.waitFor(
                "the rejection text",
                () -> sms.messagesTo(w.shop().salon().owner().phone()).stream()
                        .anyMatch(m -> m.contains("did not go through") && m.contains("not the business")));
    }

    @Test
    void aFailedPayoutIsSettledWithAReasonAndStaffCannotHandleTheirOwnBusinessPayouts() throws Exception {
        Waiting w = waitingPayout();
        Person finance = financeStaff();
        String id = w.payoutId().toString();
        String step = step(finance);
        call(post("/v1/admin/payouts/" + id + "/decision"), Map.of("decision", "APPROVE", "reason", "ok ok"), step);
        assertThat(callInvalid(post("/v1/admin/payouts/" + id + "/settlement"), Map.of("outcome", "FAILED"), step)
                        .status())
                .as("a reason is needed")
                .isEqualTo(400);
        JsonNode failed = call(
                        post("/v1/admin/payouts/" + id + "/settlement"),
                        Map.of("outcome", "FAILED", "reason", "The bank refused the account number"),
                        step)
                .body();
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(owed(w.shop(), Code.SELLER_AVAILABLE)).isEqualTo(8_850);

        // A reviewer who belongs to the business cannot decide its payouts.
        Waiting other = waitingPayout();
        Person owner = other.shop().salon().owner();
        UUID ownerId = system.select(USERS.ID)
                .from(USERS)
                .where(USERS.PHONE_E164.eq(owner.phone()))
                .fetchSingle(USERS.ID);
        system.insertInto(USER_STAFF_ROLES)
                .set(USER_STAFF_ROLES.USER_ID, ownerId)
                .set(USER_STAFF_ROLES.ROLE_CODE, "FINANCE")
                .set(
                        USER_STAFF_ROLES.MARKET_ID,
                        system.select(MARKETS.ID)
                                .from(MARKETS)
                                .where(MARKETS.CODE.eq("ET"))
                                .fetchSingle(MARKETS.ID))
                .execute();
        ageChallenges(owner.phone());
        Person asStaff = new Person(
                owner.phone(), signInWithOtp(owner.phone(), "ADMIN_CONSOLE").text("accessToken"));
        assertThat(callInvalid(
                                post("/v1/admin/payouts/" + other.payoutId() + "/decision"),
                                Map.of("decision", "APPROVE", "reason", "I trust myself"),
                                step(asStaff))
                        .status())
                .isEqualTo(403);
    }
}
