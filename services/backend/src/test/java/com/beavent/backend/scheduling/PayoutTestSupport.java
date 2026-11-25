package com.beavent.backend.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.beavent.backend.ledger.Ledger;
import com.beavent.backend.ledger.Ledger.Account;
import com.beavent.backend.ledger.Ledger.Code;
import com.beavent.backend.payments.FakeArifPay;
import com.beavent.backend.payments.Payouts;
import com.beavent.backend.platform.SecretBox;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Shared set-up for the plan 3.5 tests: a shop whose customers pay a deposit through the in-process ArifPay, helpers to
 * pay one, and views of the ledger and the payouts.
 */
abstract class PayoutTestSupport extends SchedulingTestSupport {

    @Autowired
    protected FakeArifPay arifpay;

    @Autowired
    protected Ledger ledger;

    @Autowired
    protected Payouts payouts;

    @Autowired
    protected SecretBox box;

    /** A haircut costs Br 500; new customers pay a 20 % deposit (Br 100) and the platform takes 10 % + 15 % VAT of the order. */
    protected Shop shop(boolean withFee) throws Exception {
        Shop shop = shop();
        Call policy = call(
                put(shop.salon().path("/booking-policies")),
                Map.of("deposit", Map.of("mode", "PERCENT", "percent", 20, "appliesTo", "NEW_CUSTOMERS")),
                shop.salon().owner().token());
        assertThat(policy.status()).as(String.valueOf(policy.body())).isEqualTo(200);
        makePaymentsReady(shop.salon());
        if (withFee) {
            system.execute(
                    "insert into billing.fee_rules (market_id, org_id, fee_code, payer, applies_to, percent_bps, currency_code, "
                            + "vat_applies, valid_during) values ((select id from ref.markets where code = 'ET'), ?::uuid, "
                            + "'BOOKING_FEE', 'SELLER', array['SERVICE'], 1000, 'ETB', true, tstzrange('2026-01-01', null))",
                    shop.salon().id());
        }
        return shop;
    }

    protected record Paid(String appointmentId, String attemptId) {}

    protected Paid payDeposit(Shop shop, int daysAhead) throws Exception {
        Person person = newPerson();
        Map<String, Object> hold = new HashMap<>();
        hold.put("items", List.of(item(shop.variant(), shop.abebe())));
        hold.put("start", at(day().plusDays(daysAhead), "10:00").toInstant().toString());
        Call held = call(post("/v1/public/locations/" + shop.salon().location1() + "/holds"), hold, null);
        Call booked = call(
                post("/v1/me/appointments"),
                Map.of(
                        "locationId", shop.salon().location1().toString(),
                        "holdId", held.text("holdId"),
                        "holderKey", held.text("holderKey")),
                person.token());
        assertThat(booked.status()).as(String.valueOf(booked.body())).isEqualTo(201);
        String id = booked.text("id");
        String nonce = call(post("/v1/me/appointments/" + id + "/payment"), Map.of("language", "en"), person.token())
                .text("attemptId");
        arifpay.pay(nonce);
        Call checked = call(post("/v1/me/appointments/" + id + "/payment/check"), null, person.token());
        assertThat(checked.text("status")).isEqualTo("CONFIRMED");
        return new Paid(id, nonce);
    }

    protected long owed(Shop shop, Code code) {
        return -ledger.balance(Account.seller(code, shop.salon().id()), "ETB");
    }

    protected static Instant mondayAfter(int days) {
        LocalDate d = day().plusDays(days);
        while (d.getDayOfWeek() != DayOfWeek.MONDAY) d = d.plusDays(1);
        return at(d, "08:00").toInstant();
    }

    protected Instant weeksAfterTheVisit() {
        return mondayAfter(40);
    }

    protected long ledgerSum() {
        return ((java.math.BigDecimal) system.fetchValue("select coalesce(sum(amount_minor), 0) from ledger.entries"))
                .longValue();
    }

    /** A real person to act as the finance reviewer (the payout records who decided). */
    protected UUID reviewer() throws Exception {
        return (UUID) system.fetchValue(
                "select id from iam.users where phone_e164 = ?", newPerson().phone());
    }

    protected long payoutCount(Shop shop) {
        return (Long) system.fetchValue(
                "select count(*) from payments.payouts where org_id = ?::uuid",
                shop.salon().id());
    }

    protected long platform(Code code) {
        return ledger.balance(Account.platform(code), "ETB");
    }

    protected UUID clearingOf(UUID payoutId) {
        return (UUID)
                system.fetchValue("select provider_account_id from payments.payouts where id = ?::uuid", payoutId);
    }

    protected String status(UUID payoutId) {
        return (String) system.fetchValue("select status from payments.payouts where id = ?::uuid", payoutId);
    }

    protected UUID payoutOf(Shop shop) {
        return (UUID) system.fetchValue(
                "select id from payments.payouts where org_id = ?::uuid",
                shop.salon().id());
    }

    protected UUID latestPayout(Shop shop) {
        return (UUID) system.fetchValue(
                "select id from payments.payouts where org_id = ?::uuid order by id desc limit 1",
                shop.salon().id());
    }
}
