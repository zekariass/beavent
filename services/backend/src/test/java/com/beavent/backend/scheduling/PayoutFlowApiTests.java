package com.beavent.backend.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.beavent.backend.ledger.Ledger.Account;
import com.beavent.backend.ledger.Ledger.Code;
import com.beavent.backend.payments.Payouts;
import com.beavent.backend.platform.ApiException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plan 3.5: a paid deposit lands in the ledger (owed to the seller less the platform's fees), is released after the
 * visit and the payout delay, and is paid out by a run that holds back what the rules say; finance approves, exports,
 * and marks payouts paid or failed, and every step leaves the books balanced.
 */
class PayoutFlowApiTests extends PayoutTestSupport {

    @Test
    void aPaidDepositIsOwedToTheSellerLessTheFeesAndTheBooksBalance() throws Exception {
        Shop shop = shop(true);
        long revenueBefore = platform(Code.PLATFORM_FEE_REVENUE);
        long taxBefore = platform(Code.TAX_PAYABLE);
        Paid paid = payDeposit(shop, 3);

        // Br 100 of a Br 500 order: the platform's share of the fees is a fifth of Br 50 + 15 % VAT = Br 10 + Br 1.50.
        assertThat(owed(shop, Code.SELLER_PENDING)).isEqualTo(10_000 - 1_150);
        assertThat(-(platform(Code.PLATFORM_FEE_REVENUE) - revenueBefore)).isEqualTo(1_000);
        assertThat(-(platform(Code.TAX_PAYABLE) - taxBefore)).isEqualTo(150);
        assertThat(owed(shop, Code.SELLER_AVAILABLE)).isZero();
        assertThat(ledgerSum()).as("every transaction balances").isZero();

        // Looking at the payment again posts nothing twice.
        assertThat(system.fetchValue(
                        "select count(*) from ledger.transactions where payment_attempt_id = ?::uuid",
                        UUID.fromString(paid.attemptId())))
                .isEqualTo(2L); // the payment, then the fees

        // The release waits for the visit and the payout delay.
        var release = system.fetchOne(
                "select amount_minor, status, release_at from ledger.scheduled_releases where org_id = ?::uuid",
                shop.salon().id());
        assertThat(release.get(0)).isEqualTo(8_850L);
        assertThat(release.get(1)).isEqualTo("SCHEDULED");
    }

    @Test
    void salesWithoutFeesAreOwedInFull() throws Exception {
        Shop shop = shop(false);
        payDeposit(shop, 3);
        assertThat(owed(shop, Code.SELLER_PENDING)).isEqualTo(10_000);
        assertThat(system.fetchValue(
                        "select count(*) from ledger.transactions where org_id = ?::uuid and kind = 'FEES_ASSESSED'",
                        shop.salon().id()))
                .isEqualTo(0L);
    }

    @Test
    void moneyIsReleasedAfterTheVisitAndHeldWhenTheBookingFellThrough() throws Exception {
        Shop shop = shop(true);
        Paid kept = payDeposit(shop, 3);
        Paid cancelled = payDeposit(shop, 4);
        system.execute(
                "update scheduling.appointments set status = 'CANCELLED', cancelled_at = now(), cancelled_by = 'CUSTOMER' where id = ?::uuid",
                UUID.fromString(cancelled.appointmentId()));

        // Before the visit and the delay: nothing is due.
        payouts.releaseDue(Instant.now());
        assertThat(owed(shop, Code.SELLER_AVAILABLE)).isZero();

        // After: the visit that took place is released, the cancelled one is held for a decision on the refund.
        payouts.releaseDue(weeksAfterTheVisit());
        assertThat(owed(shop, Code.SELLER_AVAILABLE)).isEqualTo(8_850);
        assertThat(owed(shop, Code.SELLER_PENDING)).isEqualTo(8_850);
        assertThat(system.fetch(
                                "select status from ledger.scheduled_releases where org_id = ?::uuid order by status",
                                shop.salon().id())
                        .map(r -> r.get(0)))
                .containsExactly("ON_HOLD", "RELEASED");
        // Releasing again changes nothing.
        payouts.releaseDue(weeksAfterTheVisit());
        assertThat(owed(shop, Code.SELLER_AVAILABLE)).isEqualTo(8_850);
        assertThat(kept.appointmentId()).isNotBlank();
        assertThat(ledgerSum()).isZero();
    }

    @Test
    void aVisitMovedAheadIsReleasedOnlyAfterItsNewTime() throws Exception {
        Shop shop = shop(false);
        Paid paid = payDeposit(shop, 3);
        // Moved a month later: the release looks again after the new end plus the delay.
        system.execute(
                "update scheduling.appointments set starts_at = starts_at + interval '30 days', ends_at = ends_at + interval '30 days' where id = ?::uuid",
                UUID.fromString(paid.appointmentId()));
        Instant laterThanOriginal = at(day().plusDays(10), "08:00").toInstant();
        payouts.releaseDue(laterThanOriginal);
        assertThat(owed(shop, Code.SELLER_AVAILABLE)).isZero();
        assertThat(system.fetchValue(
                        "select status from ledger.scheduled_releases where org_id = ?::uuid",
                        shop.salon().id()))
                .isEqualTo("SCHEDULED");
    }

    @Test
    void theFirstPayoutWaitsForApprovalThenIsExportedAndPaid() throws Exception {
        Shop shop = shop(true);
        payDeposit(shop, 3);
        Instant when = weeksAfterTheVisit();
        payouts.releaseDue(when);

        // Not on its day, or inside the new account's cooling-off: no payout.
        assertThat(payouts.createDue(when.plusSeconds(86_400))).as("a Tuesday").isZero();
        assertThat(payouts.createDue(Instant.now()))
                .as("inside the cooling-off")
                .isZero();

        payouts.createDue(when);
        payouts.createDue(when);
        assertThat(payoutCount(shop)).as("one payout a day").isEqualTo(1);
        var payout = system.fetchOne(
                "select id, status, amount_minor, approval_reasons, requires_approval from payments.payouts where org_id = ?::uuid",
                shop.salon().id());
        UUID id = (UUID) payout.get(0);
        assertThat(payout.get(1)).isEqualTo("PENDING_APPROVAL");
        assertThat(payout.get(2)).isEqualTo(8_850L);
        assertThat((String[]) payout.get(3)).contains("FIRST_PAYOUT");
        // The money left the seller's available balance and is on its way.
        assertThat(owed(shop, Code.SELLER_AVAILABLE)).isZero();
        assertThat(platform(Code.PAYOUTS_IN_TRANSIT)).isNegative();

        // Nothing to export or pay before it is approved.
        assertThat(payouts.exportBatch(reviewer(), List.of(id))).doesNotContain(id.toString());
        assertThatThrownBy(() -> payouts.settle(id, reviewer(), Payouts.Settlement.PAID, "TX-1", null))
                .isInstanceOf(ApiException.class);

        payouts.decide(id, reviewer(), true, "Checked the account");
        assertThatThrownBy(() -> payouts.decide(id, reviewer(), true, "Again")).isInstanceOf(ApiException.class);
        assertThat(status(id)).isEqualTo("APPROVED");

        // The bank batch has the full account number and marks the payout as in progress.
        system.execute(
                "update payments.payout_accounts set details_secret_ref = ? where org_id = ?::uuid",
                box.seal("1000123456789"),
                shop.salon().id());
        String csv = payouts.exportBatch(reviewer(), null);
        assertThat(csv)
                .contains(id.toString())
                .contains("1000123456789")
                .contains("88.50")
                .contains("ETB");
        assertThat(status(id)).isEqualTo("PROCESSING");
        assertThat(payouts.exportBatch(reviewer(), null)).as("exported once").doesNotContain(id.toString());

        long clearingBefore = ledger.balance(Account.clearing(clearingOf(id)), "ETB");
        payouts.settle(id, reviewer(), Payouts.Settlement.PAID, "CBE-998877", null);
        assertThat(status(id)).isEqualTo("PAID");
        assertThat(ledger.balance(Account.clearing(clearingOf(id)), "ETB") - clearingBefore)
                .as("the provider's money went down by the payout")
                .isEqualTo(-8_850);
        assertThatThrownBy(() -> payouts.settle(id, reviewer(), Payouts.Settlement.PAID, "CBE-998877", null))
                .as("not twice")
                .isInstanceOf(ApiException.class);
        assertThat(ledgerSum()).isZero();

        // The seller sees it on their side.
        Payouts.Overview overview = payouts.overview(shop.salon().id());
        assertThat(overview.paidOutMinor()).isEqualTo(8_850);
        assertThat(overview.onTheWayMinor()).isZero();
        assertThat(overview.availableMinor()).isZero();
        assertThat(overview.schedule()).isEqualTo("WEEKLY");
        // Payment, fees and payout are on the statement; the release between balances is not.
        assertThat(payouts.statement(shop.salon().id(), null, 20).stream().map(l -> l.kind() + ":" + l.amountMinor()))
                .containsExactly("PAYOUT_INITIATED:-8850", "FEES_ASSESSED:-1150", "PAYMENT_CAPTURED:10000");
    }

    @Test
    void aRejectedOrFailedPayoutGivesTheMoneyBackAndAReturnedOneToo() throws Exception {
        Shop shop = shop(false);
        payDeposit(shop, 3);
        Instant when = weeksAfterTheVisit();
        payouts.releaseDue(when);
        payouts.createDue(when);
        UUID id = payoutOf(shop);

        payouts.decide(id, reviewer(), false, "The account looks wrong");
        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(owed(shop, Code.SELLER_AVAILABLE)).as("back with the seller").isEqualTo(10_000);

        // The next day's run pays it out again; this time the bank refuses it, then another is paid and returned.
        Instant next = when.plusSeconds(7 * 86_400);
        payouts.createDue(next);
        assertThat(payoutCount(shop)).isEqualTo(2);
        UUID second = latestPayout(shop);
        payouts.decide(second, reviewer(), true, "ok");
        payouts.settle(second, reviewer(), Payouts.Settlement.FAILED, null, "The bank refused the account number");
        assertThat(status(second)).isEqualTo("FAILED");
        assertThat(owed(shop, Code.SELLER_AVAILABLE)).isEqualTo(10_000);

        Instant third = next.plusSeconds(7 * 86_400);
        payouts.createDue(third);
        assertThat(payoutCount(shop)).isEqualTo(3);
        UUID last = latestPayout(shop);
        payouts.decide(last, reviewer(), true, "ok");
        payouts.settle(last, reviewer(), Payouts.Settlement.PAID, "REF-1", null);
        assertThat(owed(shop, Code.SELLER_AVAILABLE)).isZero();
        payouts.settle(last, reviewer(), Payouts.Settlement.RETURNED, null, "Returned: account closed");
        assertThat(status(last)).isEqualTo("RETURNED");
        assertThat(owed(shop, Code.SELLER_AVAILABLE))
                .as("returned money is the seller's again")
                .isEqualTo(10_000);
        assertThat(payouts.overview(shop.salon().id()).paidOutMinor()).isZero();
        assertThat(ledgerSum()).isZero();
    }

    @Test
    void anUnverifiedBusinessOrABelowMinimumBalanceIsNotPaidOut() throws Exception {
        Shop shop = shop(false);
        payDeposit(shop, 3);
        Instant when = weeksAfterTheVisit();
        payouts.releaseDue(when);

        system.execute(
                "insert into payments.org_settings (org_id, market_id, collection_mode, payout_schedule, min_payout_minor) "
                        + "select ?::uuid, id, 'PLATFORM_COLLECTS', 'WEEKLY', 20000 from ref.markets where code = 'ET'",
                shop.salon().id());
        payouts.createDue(when);
        assertThat(payoutCount(shop)).as("Br 100 is below the Br 200 minimum").isZero();
        system.execute(
                "update payments.org_settings set min_payout_minor = 0 where org_id = ?::uuid",
                shop.salon().id());

        system.execute(
                "update tenant.organizations set verification_status = 'UNVERIFIED' where id = ?::uuid",
                shop.salon().id());
        payouts.createDue(when);
        assertThat(payoutCount(shop)).as("not verified").isZero();
        system.execute(
                "update tenant.organizations set verification_status = 'VERIFIED' where id = ?::uuid",
                shop.salon().id());
        system.execute(
                "insert into trust.risk_profiles (org_id, tier) values (?::uuid, 'BLOCKED') on conflict (org_id) do update set tier = 'BLOCKED'",
                shop.salon().id());
        payouts.createDue(when);
        assertThat(payoutCount(shop)).as("blocked by risk").isZero();
        system.execute(
                "update trust.risk_profiles set tier = 'STANDARD', manual_payout_review = false where org_id = ?::uuid",
                shop.salon().id());
        payouts.createDue(when);
        assertThat(payoutCount(shop)).isEqualTo(1);
    }

    @Test
    void aDuplicatePaymentIsHeldAsMoneyOwedBackNotAsTheSellersMoney() throws Exception {
        Shop shop = shop(false);
        Person person = newPerson();
        Map<String, Object> hold = new HashMap<>();
        hold.put("items", List.of(item(shop.variant(), shop.abebe())));
        hold.put("start", at(day().plusDays(3), "10:00").toInstant().toString());
        Call held = call(post("/v1/public/locations/" + shop.salon().location1() + "/holds"), hold, null);
        String id = call(
                        post("/v1/me/appointments"),
                        Map.of(
                                "locationId", shop.salon().location1().toString(),
                                "holdId", held.text("holdId"),
                                "holderKey", held.text("holderKey")),
                        person.token())
                .text("id");
        String first = call(post("/v1/me/appointments/" + id + "/payment"), Map.of("language", "en"), person.token())
                .text("attemptId");
        arifpay.decline(first);
        call(post("/v1/me/appointments/" + id + "/payment/check"), null, person.token());
        String second = call(post("/v1/me/appointments/" + id + "/payment"), Map.of("language", "en"), person.token())
                .text("attemptId");
        // The payer had approved the first prompt after all, and approves the second as well.
        arifpay.session(first).status = "SUCCESS";
        call(
                post("/v1/hooks/arifpay"),
                Map.of("sessionId", arifpay.session(first).sessionId, "nonce", first, "transactionStatus", "SUCCESS"),
                null);
        BookingMessagesTests.waitFor(
                "the first payment confirms it",
                () -> "CONFIRMED"
                        .equals(system.fetchValue(
                                "select status from scheduling.appointments where id = ?::uuid", UUID.fromString(id))));
        long refundsBefore = platform(Code.REFUNDS_PAYABLE);
        arifpay.pay(second);
        call(
                post("/v1/hooks/arifpay"),
                Map.of("sessionId", arifpay.session(second).sessionId, "nonce", second, "transactionStatus", "SUCCESS"),
                null);
        BookingMessagesTests.waitFor(
                "the duplicate is recorded", () -> platform(Code.REFUNDS_PAYABLE) != refundsBefore);
        assertThat(-(platform(Code.REFUNDS_PAYABLE) - refundsBefore)).isEqualTo(10_000);
        assertThat(owed(shop, Code.SELLER_PENDING))
                .as("the seller is owed only the first payment")
                .isEqualTo(10_000);
        assertThat(ledgerSum()).isZero();
    }
}
