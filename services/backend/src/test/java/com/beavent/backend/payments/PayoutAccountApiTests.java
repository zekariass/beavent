package com.beavent.backend.payments;

import static com.beavent.db.comms.Tables.NOTIFICATIONS;
import static com.beavent.db.iam.Tables.USERS;
import static com.beavent.db.iam.Tables.USER_STAFF_ROLES;
import static com.beavent.db.ref.Tables.MARKETS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.beavent.backend.ApiTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Plan 3.4: a business adds a payout account (after a fresh code, in its registered name), platform staff verify it,
 * and only then, with the business verified, may it take online payments. The number is sealed in the database and
 * seen only by the reviewer, after a fresh code and with a reason; the owner hears about every change.
 */
class PayoutAccountApiTests extends ApiTestSupport {

    private static void waitFor(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(100);
        }
    }

    /** A fresh step-up token; earlier codes are moved out of the resend window first. */
    private String step(Person person) throws Exception {
        ageChallenges(person.phone());
        return stepUp(person);
    }

    private Map<String, Object> input(Salon salon, String name, String number) {
        return Map.of("institutionCode", "CBETETAA", "accountName", name, "accountNumber", number);
    }

    private String registeredName(Salon salon) {
        return (String) system.fetchValue(
                "select registered_name from tenant.legal_entities where org_id = ?::uuid", salon.id());
    }

    private Call add(Salon salon, Map<String, Object> body, String token) throws Exception {
        return call(post(salon.path("/payout-accounts")), body, token);
    }

    private Person staff(String roleCode) throws Exception {
        Person person = newPerson();
        UUID userId = system.select(USERS.ID)
                .from(USERS)
                .where(USERS.PHONE_E164.eq(person.phone()))
                .fetchSingle(USERS.ID);
        system.insertInto(USER_STAFF_ROLES)
                .set(USER_STAFF_ROLES.USER_ID, userId)
                .set(USER_STAFF_ROLES.ROLE_CODE, roleCode)
                .set(
                        USER_STAFF_ROLES.MARKET_ID,
                        system.select(MARKETS.ID)
                                .from(MARKETS)
                                .where(MARKETS.CODE.eq("ET"))
                                .fetchSingle(MARKETS.ID))
                .execute();
        Call signedIn = signInWithOtp(person.phone(), "ADMIN_CONSOLE");
        assertThat(signedIn.status()).as(String.valueOf(signedIn.body())).isEqualTo(200);
        return new Person(person.phone(), signedIn.text("accessToken"));
    }

    @Test
    void theBanksAndWalletsAreListedInBothLanguages() throws Exception {
        JsonNode list =
                call(get("/v1/ref/banks?countryCode=ET"), null, null).body().get("items");
        JsonNode cbe = null;
        JsonNode telebirr = null;
        for (JsonNode b : list) {
            if (b.get("code").asString().equals("CBETETAA")) cbe = b;
            if (b.get("code").asString().equals("TELEBIRR")) telebirr = b;
        }
        assertThat(cbe.get("kind").asString()).isEqualTo("BANK");
        assertThat(cbe.get("names").get("en").asString()).isEqualTo("Commercial Bank of Ethiopia");
        assertThat(cbe.get("names").get("am").asString()).isNotBlank();
        assertThat(telebirr.get("kind").asString()).isEqualTo("MOBILE_MONEY");
        assertThat(list.get(0).get("code").asString())
                .as("the big banks come first")
                .isEqualTo("CBETETAA");
    }

    @Test
    void aNewBusinessSeesWhatIsMissingBeforeItCanTakeOnlinePayments() throws Exception {
        Salon salon = newSalon("Setup " + UUID.randomUUID().toString().substring(0, 6));
        JsonNode setup = call(
                        get(salon.path("/payments/setup")), null, salon.owner().token())
                .body();
        assertThat(setup.get("provider").asString()).isEqualTo("ARIFPAY");
        assertThat(setup.get("collectionMode").asString()).isEqualTo("PLATFORM_COLLECTS");
        assertThat(setup.get("ready").asBoolean()).isFalse();
        assertThat(setup.get("enforced").asBoolean()).isTrue();
        assertThat(setup.get("organizationStatus").asString()).isEqualTo("UNVERIFIED");
        assertThat(setup.get("legalEntityName").asString()).isEqualTo(registeredName(salon));
        assertThat(setup.get("blockers").toString()).contains("ORGANIZATION_NOT_VERIFIED", "NO_PAYOUT_ACCOUNT");
        // Someone without the right to see payouts is refused.
        Person professional = newMember(salon, "PROFESSIONAL");
        assertThat(callInvalid(get(salon.path("/payments/setup")), null, professional.token())
                        .status())
                .isEqualTo(403);
        assertThat(call(
                                get(salon.path("/payments/setup")),
                                null,
                                null == null ? salon.owner().token() : null)
                        .status())
                .isEqualTo(200);
    }

    @Test
    void addingAnAccountNeedsAFreshCodeTheBusinessNameAndAGoodNumber() throws Exception {
        Salon salon = newSalon("Add " + UUID.randomUUID().toString().substring(0, 6));
        String name = registeredName(salon);
        // No fresh code: refused.
        Call plain = callInvalid(
                post(salon.path("/payout-accounts")),
                input(salon, name, "1000123456789"),
                salon.owner().token());
        assertThat(plain.status()).isEqualTo(403);
        assertThat(plain.text("code")).isEqualTo("STEP_UP_REQUIRED");

        String step = step(salon.owner());
        assertThat(add(salon, input(salon, "Somebody Else", "1000123456789"), step)
                        .status())
                .isEqualTo(422);
        assertThat(callInvalid(post(salon.path("/payout-accounts")), input(salon, name, "12345"), step)
                        .status())
                .isEqualTo(400);
        assertThat(callInvalid(
                                post(salon.path("/payout-accounts")),
                                Map.of(
                                        "institutionCode",
                                        "NOSUCHBANK",
                                        "accountName",
                                        name,
                                        "accountNumber",
                                        "1000123456789"),
                                step)
                        .status())
                .isEqualTo(400);

        Call added = add(salon, input(salon, name.toUpperCase(), "1000 1234 56789"), step);
        assertThat(added.status()).as(String.valueOf(added.body())).isEqualTo(201);
        assertThat(added.text("status")).isEqualTo("PENDING");
        assertThat(added.text("accountNumberMasked")).isEqualTo("••••6789");
        assertThat(added.text("institutionName")).isEqualTo("Commercial Bank of Ethiopia");
        assertThat(added.body().toString()).doesNotContain("1000123456789");
        Instant allowed = Instant.parse(added.text("payoutsAllowedAfter"));
        assertThat(Duration.between(Instant.now(), allowed).toHours())
                .as("72 hours of cooling-off")
                .isBetween(70L, 72L);
        // The number is sealed: the database never holds it in the clear.
        String sealed = (String) system.fetchValue(
                "select details_secret_ref from payments.payout_accounts where id = ?::uuid",
                UUID.fromString(added.text("id")));
        assertThat(sealed).startsWith("box:v1:").doesNotContain("1000123456789");

        // One at a time: a second one waits for the first to be decided or withdrawn.
        assertThat(add(salon, input(salon, name, "2000123456789"), step).status())
                .isEqualTo(409);
        JsonNode setup = call(
                        get(salon.path("/payments/setup")), null, salon.owner().token())
                .body();
        assertThat(setup.get("pending").get("id").asString()).isEqualTo(added.text("id"));
        assertThat(setup.get("blockers").toString()).contains("PAYOUT_ACCOUNT_IN_REVIEW");
        assertThat(call(delete(salon.path("/payout-accounts/" + added.text("id"))), null, step(salon.owner()))
                        .status())
                .isEqualTo(204);
        assertThat(add(salon, input(salon, name, "2000123456789"), step(salon.owner()))
                        .status())
                .isEqualTo(201);
    }

    @Test
    void theOwnerIsToldWheneverTheAccountChanges() throws Exception {
        Salon salon = newSalon("Notice " + UUID.randomUUID().toString().substring(0, 6));
        Call added = add(salon, input(salon, registeredName(salon), "1000123456789"), step(salon.owner()));
        assertThat(added.status()).isEqualTo(201);
        waitFor(
                "the owner's text message",
                () -> sms.messagesTo(salon.owner().phone()).stream()
                        .anyMatch(m ->
                                m.contains("Commercial Bank of Ethiopia ••••6789") && m.contains("payout account")));
        assertThat(system.fetchCount(
                        NOTIFICATIONS,
                        NOTIFICATIONS
                                .TEMPLATE_CODE
                                .eq("PAYOUT_ACCOUNT_CHANGED")
                                .and(NOTIFICATIONS.ORG_ID.eq(salon.id()))))
                .isEqualTo(1);
    }

    @Test
    void staffVerifyTheAccountAndTheBusinessIsReadyOnceItIsVerifiedToo() throws Exception {
        Salon salon = newSalon("Review " + UUID.randomUUID().toString().substring(0, 6));
        String name = registeredName(salon);
        Call added = add(salon, input(salon, name, "1000123456789"), step(salon.owner()));
        String id = added.text("id");
        Person finance = staff("FINANCE");

        // The queue shows it, masked; nobody without the right sees it.
        JsonNode queue = call(get("/v1/admin/payout-accounts"), null, finance.token())
                .body()
                .get("items");
        JsonNode row = null;
        for (JsonNode r : queue) if (r.get("id").asString().equals(id)) row = r;
        assertThat(row).isNotNull();
        assertThat(row.get("organization").asString()).startsWith("Review");
        assertThat(row.get("legalEntityName").asString()).isEqualTo(name);
        assertThat(row.get("accountNumberMasked").asString()).isEqualTo("••••6789");
        assertThat(callInvalid(
                                get("/v1/admin/payout-accounts"),
                                null,
                                salon.owner().token())
                        .status())
                .isEqualTo(403);

        // The full number needs a fresh code and a reason, and is audited.
        String path = "/v1/admin/payout-accounts/" + id;
        assertThat(callInvalid(
                                post(path + "/reveal"),
                                Map.of("reason", "Check against the bank letter"),
                                finance.token())
                        .status())
                .isEqualTo(403);
        String step = step(finance);
        assertThat(call(post(path + "/reveal"), Map.of("reason", "Check against the bank letter"), step)
                        .text("accountNumber"))
                .isEqualTo("1000123456789");
        assertThat(system.fetchCount(
                        com.beavent.db.platform.Tables.AUDIT_LOG,
                        com.beavent.db.platform.Tables.AUDIT_LOG
                                .ACTION
                                .eq("payout_account.number_revealed")
                                .and(com.beavent.db.platform.Tables.AUDIT_LOG.TARGET_ID.eq(id))))
                .isEqualTo(1);

        // A decision needs a fresh code too, and a reason; REQUEST_INFO is not a thing for accounts.
        assertThat(callInvalid(
                                post(path + "/decision"),
                                Map.of("decision", "APPROVE", "reason", "Matches the letter"),
                                finance.token())
                        .status())
                .isEqualTo(403);
        assertThat(callInvalid(
                                post(path + "/decision"),
                                Map.of("decision", "REQUEST_INFO", "reason", "Please send a letter"),
                                step)
                        .status())
                .isEqualTo(400);
        JsonNode decided = call(
                        post(path + "/decision"), Map.of("decision", "APPROVE", "reason", "Matches the letter"), step)
                .body();
        assertThat(decided.get("status").asString()).isEqualTo("VERIFIED");
        assertThat(call(post(path + "/decision"), Map.of("decision", "APPROVE", "reason", "Again please"), step)
                        .status())
                .isEqualTo(409);

        // Verified account, unverified business: still not ready.
        JsonNode setup = call(
                        get(salon.path("/payments/setup")), null, salon.owner().token())
                .body();
        assertThat(setup.get("active").get("id").asString()).isEqualTo(id);
        assertThat(setup.get("active").get("isDefault").asBoolean()).isTrue();
        assertThat(setup.get("ready").asBoolean()).isFalse();
        assertThat(setup.get("blockers").toString()).isEqualTo("[\"ORGANIZATION_NOT_VERIFIED\"]");
        system.execute(
                "update tenant.organizations set verification_status = 'VERIFIED' where id = ?::uuid", salon.id());
        assertThat(call(get(salon.path("/payments/setup")), null, salon.owner().token())
                        .body()
                        .get("ready")
                        .asBoolean())
                .isTrue();

        // A new account replaces the old one only once the new one is verified.
        String second = add(salon, input(salon, name, "2000123456789"), step(salon.owner()))
                .text("id");
        assertThat(call(get(salon.path("/payments/setup")), null, salon.owner().token())
                        .body()
                        .get("active")
                        .get("id")
                        .asString())
                .as("the old account stays in use while the new one waits")
                .isEqualTo(id);
        String step2 = step(finance);
        call(
                post("/v1/admin/payout-accounts/" + second + "/decision"),
                Map.of("decision", "APPROVE", "reason", "Matches the letter"),
                step2);
        JsonNode after = call(
                        get(salon.path("/payments/setup")), null, salon.owner().token())
                .body();
        assertThat(after.get("active").get("id").asString()).isEqualTo(second);
        assertThat(system.fetchValue(
                        "select status from payments.payout_accounts where id = ?::uuid", UUID.fromString(id)))
                .isEqualTo("DISABLED");
    }

    @Test
    void aRejectionIsExplainedToTheSellerAndTheyCanTryAgain() throws Exception {
        Salon salon = newSalon("Reject " + UUID.randomUUID().toString().substring(0, 6));
        String name = registeredName(salon);
        String id = add(salon, input(salon, name, "1000123456789"), step(salon.owner()))
                .text("id");
        Person finance = staff("FINANCE");
        JsonNode rejected = call(
                        post("/v1/admin/payout-accounts/" + id + "/decision"),
                        Map.of("decision", "REJECT", "reason", "The number does not match the bank's letter"),
                        step(finance))
                .body();
        assertThat(rejected.get("status").asString()).isEqualTo("FAILED");
        JsonNode setup = call(
                        get(salon.path("/payments/setup")), null, salon.owner().token())
                .body();
        assertThat(setup.get("rejected").get("decisionNote").asString()).contains("does not match");
        assertThat(setup.get("blockers").toString()).contains("PAYOUT_ACCOUNT_REJECTED");
        assertThat(add(salon, input(salon, name, "1000999999999"), step(salon.owner()))
                        .status())
                .isEqualTo(201);
    }

    @Test
    void staffCannotDecideTheirOwnBusinessAccount() throws Exception {
        Salon salon = newSalon("Own " + UUID.randomUUID().toString().substring(0, 6));
        String id = add(salon, input(salon, registeredName(salon), "1000123456789"), step(salon.owner()))
                .text("id");
        // The owner is also platform staff: another reviewer must decide.
        UUID owner = system.select(USERS.ID)
                .from(USERS)
                .where(USERS.PHONE_E164.eq(salon.owner().phone()))
                .fetchSingle(USERS.ID);
        system.insertInto(USER_STAFF_ROLES)
                .set(USER_STAFF_ROLES.USER_ID, owner)
                .set(USER_STAFF_ROLES.ROLE_CODE, "FINANCE")
                .set(
                        USER_STAFF_ROLES.MARKET_ID,
                        system.select(MARKETS.ID)
                                .from(MARKETS)
                                .where(MARKETS.CODE.eq("ET"))
                                .fetchSingle(MARKETS.ID))
                .execute();
        ageChallenges(salon.owner().phone());
        Call signedIn = signInWithOtp(salon.owner().phone(), "ADMIN_CONSOLE");
        Person asStaff = new Person(salon.owner().phone(), signedIn.text("accessToken"));
        Call refused = callInvalid(
                post("/v1/admin/payout-accounts/" + id + "/decision"),
                Map.of("decision", "APPROVE", "reason", "I trust myself"),
                step(asStaff));
        assertThat(refused.status()).isEqualTo(403);
    }
}
