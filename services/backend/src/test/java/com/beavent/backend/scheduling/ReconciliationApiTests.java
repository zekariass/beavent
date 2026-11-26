package com.beavent.backend.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.beavent.backend.ledger.Ledger.Account;
import com.beavent.backend.ledger.Ledger.Code;
import com.beavent.backend.payments.Payouts;
import com.beavent.backend.payments.Refunds;
import com.beavent.backend.payments.internal.ProviderAccounts;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Plan 3.9: finance uploads the provider's statement; its lines are matched to our payments, refunds and payouts, the
 * provider's fees reach the ledger, money nothing explains waits in suspense, and what does not match waits in a queue
 * until someone says why. A provider goes live only when every check passes. Each test imports its statement on a
 * provider account of its own and dates its payments far ahead, so the shared test database cannot interfere.
 */
class ReconciliationApiTests extends PayoutTestSupport {

    @Autowired
    Refunds refunds;

    @Autowired
    ProviderAccounts providerAccounts;

    @Autowired
    @com.beavent.backend.platform.SystemDb
    org.springframework.transaction.support.TransactionTemplate systemTx;

    private static int day = 0;

    /** A day nobody else's payments are on. */
    private static synchronized String freshDay() {
        day += 1;
        return String.format("2040-%02d-%02d", 1 + (day / 28), 1 + (day % 28));
    }

    private UUID newAccount() {
        return (UUID) system.fetchValue(
                "insert into payments.provider_accounts (provider_code, owner, market_id, environment, display_name,"
                        + " settlement_currency, credentials_secret_ref, status) select 'ARIFPAY', 'PLATFORM', id, 'LIVE',"
                        + " ?, 'ETB', 'env:TEST', 'ACTIVE' from ref.markets where code = 'ET' returning id",
                "Test account " + UUID.randomUUID());
    }

    /** The payment as the provider's statement will know it: on the test account, completed on {@code on}. */
    private void moveTo(UUID account, Paid paid, String on) {
        system.execute(
                "update payments.payment_attempts set provider_account_id = ?::uuid,"
                        + " completed_at = (?::date + time '11:00') at time zone 'Africa/Addis_Ababa' where id = ?::uuid",
                account,
                on,
                UUID.fromString(paid.attemptId()));
    }

    private long amountOf(Paid paid) {
        return (Long) system.fetchValue(
                "select amount_minor from payments.payment_attempts where id = ?::uuid",
                UUID.fromString(paid.attemptId()));
    }

    private static String birr(long minor) {
        return String.format("%d.%02d", minor / 100, minor % 100);
    }

    private Call upload(Person who, UUID account, String from, String to, Long closing, String csv) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("providerAccountId", account.toString());
        body.put("from", from);
        body.put("to", to);
        if (closing != null) body.put("closingBalanceMinor", closing);
        body.put("csv", csv);
        return callInvalid(post("/v1/admin/reconciliation/statements"), body, who.token());
    }

    private JsonNode exceptionsOf(Person who, String statementId) throws Exception {
        JsonNode all = call(get("/v1/admin/reconciliation/exceptions"), null, who.token())
                .body()
                .get("items");
        List<JsonNode> mine = new java.util.ArrayList<>();
        for (JsonNode e : all) if (statementId.equals(e.get("statementId").asString())) mine.add(e);
        return tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(mine);
    }

    private Call resolve(Person who, String statementId, String lineId, String attemptId, String note)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("statementId", statementId);
        if (lineId != null) body.put("lineId", lineId);
        if (attemptId != null) body.put("attemptId", attemptId);
        body.put("note", note);
        return callInvalid(post("/v1/admin/reconciliation/resolutions"), body, who.token());
    }

    @Test
    void aStatementThatAgreesWithOurRecordsReconcilesAndTheProvidersFeesReachTheLedger() throws Exception {
        Shop shop = shop(true);
        Paid first = payDeposit(shop, 3);
        Paid second = payDeposit(shop, 4);
        UUID account = newAccount();
        String on = freshDay();
        moveTo(account, first, on);
        moveTo(account, second, on);
        String secondReference = (String) system.fetchValue(
                "select provider_reference from payments.payment_attempts where id = ?::uuid",
                UUID.fromString(second.attemptId()));
        assertThat(secondReference).isNotBlank();
        long amount = amountOf(first);
        long costBefore = platform(Code.PLATFORM_PROCESSING_COST);

        // By our id, by the provider's own reference, a fee of its own, and the settlement to the bank.
        String csv = "reference,type,amount,fee,date\n"
                + first.attemptId() + ",COLLECTION," + birr(amount) + ",1.50," + on + " 11:00\n"
                + "\"" + secondReference + "\",COLLECTION," + birr(amount) + ",1.50," + on + " 11:05\n"
                + ",FEE,2.00,," + on + " 12:00\n"
                + "SETTLE-1,SETTLEMENT,-" + birr(amount * 2 - 400) + ",," + on + "\n";
        Person finance = financeStaff();
        Call imported = upload(finance, account, on, on, null, csv);
        assertThat(imported.status()).as(String.valueOf(imported.body())).isEqualTo(201);
        JsonNode statement = imported.body();
        assertThat(statement.get("status").asString()).isEqualTo("RECONCILED");
        assertThat(statement.get("lines").asInt()).isEqualTo(4);
        assertThat(statement.get("matched").asInt()).isEqualTo(3);
        assertThat(statement.get("exceptions").asInt()).isZero();
        assertThat(statement.get("provider").asString()).isEqualTo("ARIFPAY");

        assertThat(platform(Code.PLATFORM_PROCESSING_COST) - costBefore).isEqualTo(150 + 150 + 200);
        assertThat(ledger.balance(Account.clearing(account), "ETB")).isEqualTo(-500);
        assertThat(system.fetchValue(
                        "select provider_fee_minor from payments.payment_attempts where id = ?::uuid",
                        UUID.fromString(first.attemptId())))
                .isEqualTo(150L);
        assertThat(ledgerSum()).isZero();

        // Once per period: the same days, or some of them, are refused.
        assertThat(upload(finance, account, on, on, null, csv).status()).isEqualTo(409);
        assertThat(upload(finance, account, "2039-12-30", on, null, csv).status())
                .isEqualTo(409);
        assertThat(call(get("/v1/admin/reconciliation/statements"), null, finance.token())
                        .body()
                        .get("items")
                        .toString())
                .contains(statement.get("id").asString());

        // Only finance.
        assertThat(upload(
                                new Person(
                                        shop.salon().owner().phone(),
                                        shop.salon().owner().token()),
                                account,
                                on,
                                on,
                                null,
                                csv)
                        .status())
                .isEqualTo(403);
        assertThat(callInvalid(get("/v1/admin/reconciliation/exceptions"), null, null)
                        .status())
                .isEqualTo(401);
    }

    @Test
    void whatDoesNotMatchWaitsInTheQueueUntilSomeoneSaysWhy() throws Exception {
        Shop shop = shop(true);
        Paid off = payDeposit(shop, 3);
        Paid missing = payDeposit(shop, 4);
        UUID account = newAccount();
        String on = freshDay();
        moveTo(account, off, on);
        moveTo(account, missing, on);
        long amount = amountOf(off);
        long suspenseBefore = platform(Code.SUSPENSE);

        String csv = "reference,type,amount,fee,date\n"
                + off.attemptId() + ",COLLECTION," + birr(amount + 5000) + ",," + on + " 10:00\n"
                + "NOBODY-KNOWS,COLLECTION,75.00,," + on + " 10:30\n"
                + "REFUND-X,REFUND,-20.00,," + on + " 11:00\n"
                + "CB-1,CHARGEBACK,-30.00,," + on + " 11:30\n";
        Person finance = financeStaff();
        Call imported = upload(finance, account, on, on, null, csv);
        assertThat(imported.status()).as(String.valueOf(imported.body())).isEqualTo(201);
        String statementId = imported.body().get("id").asString();
        assertThat(imported.body().get("status").asString()).isEqualTo("EXCEPTIONS");
        // Four lines to explain, and the payment we confirmed that the statement does not show.
        assertThat(imported.body().get("exceptions").asInt()).isEqualTo(5);
        assertThat(imported.body().get("matched").asInt()).isZero();

        // The Br 75 nothing explains is held in suspense, the books still balance.
        assertThat(platform(Code.SUSPENSE) - suspenseBefore).isEqualTo(-7_500);
        assertThat(ledger.balance(Account.clearing(account), "ETB")).isEqualTo(7_500);
        assertThat(ledgerSum()).isZero();

        JsonNode queue = exceptionsOf(finance, statementId);
        assertThat(queue).hasSize(5);
        Map<String, JsonNode> byKind = new HashMap<>();
        for (JsonNode e : queue)
            byKind.put(e.get("kind").asString() + ":" + e.get("lineType").asString(), e);
        assertThat(byKind)
                .containsKeys(
                        "AMOUNT_MISMATCH:COLLECTION",
                        "MISSING_INTERNALLY:COLLECTION",
                        "MISSING_INTERNALLY:REFUND",
                        "UNMATCHED:CHARGEBACK",
                        "MISSING_ON_STATEMENT:COLLECTION");
        assertThat(byKind.get("MISSING_ON_STATEMENT:COLLECTION")
                        .get("attemptId")
                        .asString())
                .isEqualTo(missing.attemptId());

        // A note is needed, for something that is really in the queue.
        String line = byKind.get("UNMATCHED:CHARGEBACK").get("lineId").asString();
        assertThat(resolve(finance, statementId, line, null, "ok").status()).isEqualTo(400);
        assertThat(resolve(finance, statementId, UUID.randomUUID().toString(), null, "Not in the queue")
                        .status())
                .isEqualTo(404);
        assertThat(resolve(finance, statementId, line, null, "Tested with the provider, refunded")
                        .status())
                .isEqualTo(204);
        assertThat(resolve(finance, statementId, line, null, "Again").status())
                .as("once")
                .isEqualTo(404);
        for (JsonNode e : queue) {
            String kind = e.get("kind").asString();
            if (e.get("lineType").asString().equals("CHARGEBACK")) continue;
            boolean line2 = e.hasNonNull("lineId");
            Call done = resolve(
                    finance,
                    statementId,
                    line2 ? e.get("lineId").asString() : null,
                    line2 ? null : e.get("attemptId").asString(),
                    "Checked by hand: " + kind);
            assertThat(done.status()).as(kind + " " + done.body()).isEqualTo(204);
        }
        assertThat(exceptionsOf(finance, statementId)).isEmpty();
        JsonNode listed = call(get("/v1/admin/reconciliation/statements"), null, finance.token())
                .body()
                .get("items");
        String status = null;
        for (JsonNode s : listed)
            if (s.get("id").asString().equals(statementId))
                status = s.get("status").asString();
        assertThat(status).isEqualTo("RECONCILED");
        assertThat(system.fetchValue(
                        "select count(*) from platform.audit_log where action = 'reconciliation.resolved' and target_id = ?",
                        statementId))
                .isEqualTo(5L);
    }

    @Test
    void aPaymentTheProviderTookThatWeGaveUpOnIsPaidLateAndOneItNeverSawIsFailed() throws Exception {
        Shop shop = shop(true);
        // Started and left waiting: the provider's lookup says it is not paid (we never heard otherwise).
        Paid waiting = bookUnpaid(shop, 3);
        call(
                post("/v1/me/appointments/" + waiting.appointmentId() + "/payment"),
                Map.of("language", "en"),
                waiting.token());
        UUID attempt = (UUID) system.fetchValue(
                "select a.id from payments.payment_attempts a join payments.payment_intents i on i.id = a.intent_id"
                        + " join scheduling.appointments ap on ap.order_id = i.order_id where ap.id = ?::uuid",
                UUID.fromString(waiting.appointmentId()));
        // Another that was left unknown.
        Paid unknown = bookUnpaid(shop, 4);
        call(
                post("/v1/me/appointments/" + unknown.appointmentId() + "/payment"),
                Map.of("language", "en"),
                unknown.token());
        UUID unknownAttempt = (UUID) system.fetchValue(
                "select a.id from payments.payment_attempts a join payments.payment_intents i on i.id = a.intent_id"
                        + " join scheduling.appointments ap on ap.order_id = i.order_id where ap.id = ?::uuid",
                UUID.fromString(unknown.appointmentId()));

        UUID account = newAccount();
        String on = freshDay();
        system.execute(
                "update payments.payment_attempts set provider_account_id = ?::uuid,"
                        + " initiated_at = (?::date + time '09:00') at time zone 'Africa/Addis_Ababa' where id in (?::uuid, ?::uuid)",
                account,
                on,
                attempt,
                unknownAttempt);
        // Out of the payment worker's way: it polls what it finds due, and would look this one up again.
        system.execute(
                "update payments.payment_attempts set status = 'UNKNOWN', next_poll_at = now() + interval '1 day' where id = ?::uuid",
                unknownAttempt);
        long amount = (Long)
                system.fetchValue("select amount_minor from payments.payment_attempts where id = ?::uuid", attempt);

        String csv = "reference,type,amount,fee,date\n" + attempt + ",COLLECTION," + birr(amount) + ",1.00," + on
                + " 10:00\n";
        Person finance = financeStaff();
        Call imported = upload(finance, account, on, on, null, csv);
        assertThat(imported.status()).as(String.valueOf(imported.body())).isEqualTo(201);
        assertThat(imported.body().get("status").asString()).isEqualTo("RECONCILED");
        assertThat(imported.body().get("matched").asInt()).isEqualTo(1);
        assertThat(system.fetchValue("select status from payments.payment_attempts where id = ?::uuid", attempt))
                .isEqualTo("SUCCEEDED");
        assertThat(system.fetchValue(
                        "select status from scheduling.appointments where id = ?::uuid",
                        UUID.fromString(waiting.appointmentId())))
                .as("the booking is confirmed as by a late notification")
                .isEqualTo("CONFIRMED");
        assertThat(system.fetchValue("select status from payments.payment_attempts where id = ?::uuid", unknownAttempt))
                .as("the statement does not show it")
                .isEqualTo("FAILED");
        assertThat(ledgerSum()).isZero();
    }

    @Test
    void theLedgersBalanceIsComparedWithTheStatementsClosingBalance() throws Exception {
        Shop shop = shop(true);
        UUID account = newAccount();
        // Br 1,000 arrived (nobody's payment in particular): the ledger says the account holds it.
        UUID tx = systemTx.execute(status -> ledger.post(new com.beavent.backend.ledger.Ledger.Posting(
                "PAYMENT_CAPTURED",
                shop.salon().id(),
                "test:" + UUID.randomUUID(),
                "A test payment",
                com.beavent.backend.ledger.Ledger.Links.NONE,
                "ETB",
                List.of(
                        new com.beavent.backend.ledger.Ledger.Line(Account.clearing(account), 100_000),
                        new com.beavent.backend.ledger.Ledger.Line(
                                Account.seller(Code.SELLER_PENDING, shop.salon().id()), -100_000)))));
        assertThat(tx).isNotNull();
        Person finance = financeStaff();
        String header = "reference,type,amount,fee,date\n";

        String one = freshDay();
        Call agrees = upload(finance, account, one, one, 100_000L, header + "SETTLE,SETTLEMENT,0.00,," + one + "\n");
        assertThat(agrees.status()).as(String.valueOf(agrees.body())).isEqualTo(201);
        assertThat(agrees.body().get("ledgerBalanceMinor").asLong()).isEqualTo(100_000);
        assertThat(agrees.body().get("differenceMinor").asLong()).isZero();
        assertThat(agrees.body().get("status").asString()).isEqualTo("RECONCILED");

        String two = freshDay();
        Call differs = upload(finance, account, two, two, 98_000L, header + "SETTLE,SETTLEMENT,0.00,," + two + "\n");
        assertThat(differs.body().get("differenceMinor").asLong()).isEqualTo(-2_000);
        assertThat(differs.body().get("status").asString()).isEqualTo("EXCEPTIONS");
        String statementId = differs.body().get("id").asString();
        JsonNode queue = exceptionsOf(finance, statementId);
        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).get("kind").asString()).isEqualTo("BALANCE_DIFFERENCE");
        assertThat(queue.get(0).get("amountMinor").asLong()).isEqualTo(-2_000);
        assertThat(resolve(finance, statementId, null, null, "Br 20 of provider charges on the way")
                        .status())
                .isEqualTo(204);
        assertThat(exceptionsOf(finance, statementId)).isEmpty();
    }

    @Test
    void fileProblemsAreNamedByLineAndOtherSeparatorsAreRead() throws Exception {
        UUID account = newAccount();
        Person finance = financeStaff();
        String on = freshDay();
        Call badType = upload(finance, account, on, on, null, "reference,type,amount,date\nA,SALE,1.00," + on + "\n");
        assertThat(badType.status()).isEqualTo(400);
        assertThat(badType.text("detail")).contains("Line 2").contains("SALE");
        Call badAmount = upload(
                finance,
                account,
                on,
                on,
                null,
                "reference,type,amount,date\nA,FEE,1.00," + on + "\nB,FEE,abc," + on + "\n");
        assertThat(badAmount.text("detail")).contains("Line 3").contains("abc");
        Call badDate = upload(finance, account, on, on, null, "reference,type,amount,date\nA,FEE,1.00,yesterday\n");
        assertThat(badDate.text("detail")).contains("Line 2");
        assertThat(upload(finance, account, on, on, null, "reference,type,amount\nA,FEE,1.00\n")
                        .status())
                .isEqualTo(400);
        assertThat(upload(finance, account, on, on, null, "").status()).isEqualTo(400);
        assertThat(upload(finance, account, "2040-12-01", "2040-02-01", null, "a,b\n")
                        .status())
                .isEqualTo(400);
        assertThat(upload(finance, account, "2041-01-01", "2041-12-31", null, "a,b\n")
                        .status())
                .isEqualTo(400);
        assertThat(upload(
                                finance,
                                UUID.randomUUID(),
                                on,
                                on,
                                null,
                                "reference,type,amount,date\nA,FEE,1.00," + on + "\n")
                        .status())
                .isEqualTo(404);
        // Nothing was kept by the failed ones.
        assertThat(system.fetchValue(
                        "select count(*) from payments.provider_statements where provider_account_id = ?::uuid",
                        account))
                .isEqualTo(0L);

        // Semicolons, quoted cells and "1,800.00" are read.
        Call fine = upload(
                finance,
                account,
                on,
                on,
                null,
                "Reference;Type;Amount;Fee;Date\n\"X;1\";fee;\"1,800.00\";;" + on + "\n");
        assertThat(fine.status()).as(String.valueOf(fine.body())).isEqualTo(201);
        assertThat(system.fetchValue(
                        "select amount_minor from payments.statement_lines l join payments.provider_statements s on s.id = l.statement_id"
                                + " where s.provider_account_id = ?::uuid",
                        account))
                .isEqualTo(180_000L);
    }

    @Test
    void aProviderGoesLiveOnlyWhenEveryCheckPassesAndCanBeSuspended() throws Exception {
        Person finance = financeStaff();
        try {
            // Clear the queue the other tests left, so only this test's checks decide.
            for (JsonNode e : call(get("/v1/admin/reconciliation/exceptions"), null, finance.token())
                    .body()
                    .get("items")) {
                boolean line = e.hasNonNull("lineId");
                boolean attempt = e.hasNonNull("attemptId");
                resolve(
                        finance,
                        e.get("statementId").asString(),
                        line ? e.get("lineId").asString() : null,
                        !line && attempt ? e.get("attemptId").asString() : null,
                        "Cleared before certifying");
            }
            JsonNode before = call(get("/v1/admin/payment-provider"), null, finance.token())
                    .body();
            assertThat(before.get("provider").asString()).isEqualTo("ARIFPAY");
            assertThat(before.get("status").asString()).isEqualTo("SANDBOX");

            // A payment, a refund, a payout and a reconciled statement make it ready.
            Shop shop = shop(true);
            Paid paid = payDeposit(shop, 3);
            Instant when = weeksAfterTheVisit();
            payouts.releaseDue(when);
            payouts.createDue(when);
            UUID payout = latestPayout(shop);
            payouts.decide(payout, reviewer(), true, "Checked");
            system.execute(
                    "update payments.payout_accounts set details_secret_ref = ? where org_id = ?::uuid",
                    box.seal("1000123456789"),
                    shop.salon().id());
            payouts.exportBatch(reviewer(), List.of(payout));
            payouts.settle(payout, reviewer(), Payouts.Settlement.PAID, "CBE-1", null);

            Paid cancelled = payDeposit(shop, 5);
            call(post("/v1/me/appointments/" + cancelled.appointmentId() + "/cancel"), Map.of(), cancelled.token());
            BookingMessagesTests.waitFor(
                    "the refund is made",
                    () -> system.fetchValue(
                                    "select r.id from payments.refunds r join scheduling.appointments a on a.order_id = r.order_id where a.id = ?::uuid",
                                    UUID.fromString(cancelled.appointmentId()))
                            != null);
            UUID refund = (UUID) system.fetchValue(
                    "select r.id from payments.refunds r join scheduling.appointments a on a.order_id = r.order_id where a.id = ?::uuid",
                    UUID.fromString(cancelled.appointmentId()));
            refunds.markPaid(refund, reviewer(), "TELEBIRR-1", null);

            JsonNode notYet = call(get("/v1/admin/payment-provider"), null, finance.token())
                    .body();
            assertThat(notYet.get("ready").asBoolean())
                    .as("no statement reconciled yet")
                    .isFalse();
            assertThat(checks(notYet)).containsEntry("RECONCILED", false).containsEntry("COLLECTION", true);
            String step = step(finance);
            assertThat(callInvalid(
                                    post("/v1/admin/payment-provider/go-live"),
                                    Map.of("evidence", "R-1 live payment"),
                                    step)
                            .status())
                    .as("refused while a check fails")
                    .isEqualTo(409);

            UUID account = newAccount();
            String on = freshDay();
            moveTo(account, paid, on);
            Call imported = upload(
                    finance,
                    account,
                    on,
                    on,
                    null,
                    "reference,type,amount,fee,date\n" + paid.attemptId() + ",COLLECTION," + birr(amountOf(paid))
                            + ",1.00," + on + " 11:00\n");
            assertThat(imported.body().get("status").asString()).isEqualTo("RECONCILED");

            JsonNode ready = call(get("/v1/admin/payment-provider"), null, finance.token())
                    .body();
            assertThat(ready.get("ready").asBoolean())
                    .as(String.valueOf(checks(ready)))
                    .isTrue();

            // A fresh code, a permission, and words about the live test.
            assertThat(callInvalid(
                                    post("/v1/admin/payment-provider/go-live"),
                                    Map.of("evidence", "R-1 live payment"),
                                    finance.token())
                            .status())
                    .isEqualTo(403);
            assertThat(callInvalid(
                                    post("/v1/admin/payment-provider/go-live"),
                                    Map.of("evidence", "short"),
                                    step(finance))
                            .status())
                    .isEqualTo(400);
            JsonNode live = call(
                            post("/v1/admin/payment-provider/go-live"),
                            Map.of("evidence", "Live test: payment LV-1, refund LV-2, payout LV-3"),
                            step)
                    .body();
            assertThat(live.get("status").asString()).isEqualTo("LIVE");
            assertThat(live.hasNonNull("verifiedAt")).isTrue();
            assertThat(
                            system.fetchValue(
                                    "select count(*) from payments.provider_capabilities where provider_code = 'ARIFPAY' and status = 'LIVE' and verified_at is not null"))
                    .isNotEqualTo(0L);

            JsonNode suspended = call(
                            post("/v1/admin/payment-provider/suspension"), Map.of("reason", "Provider outage"), step)
                    .body();
            assertThat(suspended.get("status").asString()).isEqualTo("SUSPENDED");
            assertThat(
                            system.fetchValue(
                                    "select count(*) from payments.routes r join payments.provider_accounts a on a.id = r.provider_account_id"
                                            + " where a.provider_code = 'ARIFPAY' and a.environment = 'SANDBOX' and r.is_active"))
                    .as("no new online payments through it")
                    .isEqualTo(0L);
            assertThat(
                            system.fetchValue(
                                    "select count(*) from platform.audit_log where action in ('provider.live','provider.suspended')"))
                    .isNotEqualTo(0L);
        } finally {
            // The test database is shared: give every later test its sandbox provider back.
            system.execute("update payments.provider_capabilities set status = 'SANDBOX', verified_at = null"
                    + " where provider_code = 'ARIFPAY' and status in ('LIVE', 'SUSPENDED')");
            providerAccounts.register();
        }
    }

    private static Map<String, Boolean> checks(JsonNode certification) {
        Map<String, Boolean> out = new HashMap<>();
        for (JsonNode c : certification.get("checks"))
            out.put(c.get("code").asString(), c.get("passed").asBoolean());
        return out;
    }
}
