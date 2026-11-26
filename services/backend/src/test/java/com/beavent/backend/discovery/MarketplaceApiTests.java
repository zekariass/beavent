package com.beavent.backend.discovery;

import static com.beavent.db.iam.Tables.USERS;
import static com.beavent.db.iam.Tables.USER_STAFF_ROLES;
import static com.beavent.db.ref.Tables.MARKETS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.beavent.backend.scheduling.SchedulingTestSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Being on the marketplace (plan step 8.1): a business asks, platform staff decide, search follows at once. */
class MarketplaceApiTests extends SchedulingTestSupport {

    // --- helpers ----------------------------------------------------------------------------------------------

    /** A person with a platform staff role, signed in to the admin console. */
    String staff(String roleCode, String marketCode) throws Exception {
        Person person = newPerson();
        grant(person, roleCode, marketCode);
        Call signedIn = signInWithOtp(person.phone(), "ADMIN_CONSOLE");
        assertThat(signedIn.status()).as(String.valueOf(signedIn.body())).isEqualTo(200);
        return signedIn.text("accessToken");
    }

    void grant(Person person, String roleCode, String marketCode) {
        UUID userId = system.select(USERS.ID)
                .from(USERS)
                .where(USERS.PHONE_E164.eq(person.phone()))
                .fetchSingle(USERS.ID);
        UUID market = marketCode == null
                ? null
                : system.select(MARKETS.ID)
                        .from(MARKETS)
                        .where(MARKETS.CODE.eq(marketCode))
                        .fetchSingle(MARKETS.ID);
        system.insertInto(USER_STAFF_ROLES)
                .set(USER_STAFF_ROLES.USER_ID, userId)
                .set(USER_STAFF_ROLES.ROLE_CODE, roleCode)
                .set(USER_STAFF_ROLES.MARKET_ID, market)
                .execute();
    }

    /** A business with a service people can book online, named so a search finds only it. */
    Salon readySalon(String name) throws Exception {
        Salon salon = newSalon(name);
        Map<String, Object> extra = new HashMap<>();
        extra.put("categoryId", category("SERVICE", "BRAIDS"));
        service(salon, "Box braids", List.of(variant(90, 0, null, null)), extra);
        return salon;
    }

    JsonNode status(Salon salon) throws Exception {
        Call c = call(get(salon.path("/marketplace")), null, salon.owner().token());
        assertThat(c.status()).as(String.valueOf(c.body())).isEqualTo(200);
        return c.body();
    }

    Call ask(Salon salon, boolean requested) throws Exception {
        return call(
                put(salon.path("/marketplace")),
                Map.of("requested", requested),
                salon.owner().token());
    }

    Call decide(String token, Salon salon, String decision, String reason) throws Exception {
        return call(
                post("/v1/admin/orgs/" + salon.id() + "/marketplace-decision"),
                Map.of("decision", decision, "reason", reason),
                token);
    }

    List<String> queue(String token, String status) throws Exception {
        String url = "/v1/admin/marketplace-listings?limit=100" + (status == null ? "" : "&status=" + status);
        Call c = call(get(url), null, token);
        assertThat(c.status()).as(String.valueOf(c.body())).isEqualTo(200);
        List<String> out = new java.util.ArrayList<>();
        c.body().get("items").forEach(i -> out.add(i.get("organization").asString()));
        return out;
    }

    boolean findable(String name) throws Exception {
        Call c = call(get("/v1/public/search?q=" + java.net.URLEncoder.encode(name, "UTF-8")), null, null);
        assertThat(c.status()).isEqualTo(200);
        return c.body().get("items").size() > 0;
    }

    // --- the journey ------------------------------------------------------------------------------------------

    @Test
    void aBusinessAsksStaffListItAndSearchFindsItAtOnce() throws Exception {
        String m = SearchApiTests.marker();
        Salon salon = newSalon(m + " Salon");
        String ops = staff("OPS_ADMIN", null);

        // Nothing to book yet: it cannot ask.
        JsonNode first = status(salon);
        assertThat(first.get("status").asString()).isEqualTo("HIDDEN");
        assertThat(first.get("canRequest").asBoolean()).isFalse();
        assertThat(first.get("requirements").get(0).get("code").asString()).isEqualTo("LOCATION");
        assertThat(first.get("requirements").get(0).get("met").asBoolean()).isTrue();
        assertThat(first.get("requirements").get(1).get("code").asString()).isEqualTo("SERVICE");
        assertThat(first.get("requirements").get(1).get("met").asBoolean()).isFalse();
        assertThat(ask(salon, true).status()).isEqualTo(422);

        Map<String, Object> extra = new HashMap<>();
        extra.put("categoryId", category("SERVICE", "BRAIDS"));
        service(salon, "Box braids", List.of(variant(90, 0, null, null)), extra);
        assertThat(status(salon).get("canRequest").asBoolean()).isTrue();

        Call asked = ask(salon, true);
        assertThat(asked.status()).as(String.valueOf(asked.body())).isEqualTo(200);
        assertThat(asked.body().get("status").asString()).isEqualTo("PENDING_REVIEW");
        assertThat(ask(salon, true).status()).isEqualTo(409); // already waiting

        // Staff see it waiting, with what it has.
        assertThat(queue(ops, null)).contains(m + " Salon");
        Call waiting = call(get("/v1/admin/marketplace-listings?status=PENDING_REVIEW&limit=100"), null, ops);
        JsonNode row = null;
        for (JsonNode item : waiting.body().get("items")) {
            if (item.get("organization").asString().equals(m + " Salon")) {
                row = item;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("locations").asInt()).isEqualTo(2);
        assertThat(row.get("services").asInt()).isEqualTo(1);
        assertThat(row.get("marketCode").asString()).isEqualTo("ET");
        assertThat(findable(m)).as("not listed yet").isFalse();

        Call listed = decide(ops, salon, "LIST", "Looks good.");
        assertThat(listed.status()).as(String.valueOf(listed.body())).isEqualTo(200);
        assertThat(listed.body().get("status").asString()).isEqualTo("LISTED");
        // The business has not finished onboarding, and is findable the moment staff say yes.
        assertThat(findable(m)).isTrue();
        assertThat(status(salon).get("status").asString()).isEqualTo("LISTED");
        assertThat(queue(ops, null)).doesNotContain(m + " Salon");

        // The decision is in the audit log with who and why.
        String reason = system.resultQuery(
                        "SELECT reason FROM platform.audit_log WHERE org_id = ? AND action = 'organization.marketplace.list'",
                        salon.id())
                .fetchOne(0, String.class);
        assertThat(reason).isEqualTo("Looks good.");
    }

    @Test
    void aRequestSentBackShowsTheReasonUntilTheBusinessAsksAgain() throws Exception {
        Salon salon = readySalon(SearchApiTests.marker() + " Salon");
        String ops = staff("MODERATOR", null);
        assertThat(ask(salon, true).status()).isEqualTo(200);

        assertThat(decide(ops, salon, "REJECT", "Add photos and a landmark first.")
                        .status())
                .isEqualTo(200);
        JsonNode sent = status(salon);
        assertThat(sent.get("status").asString()).isEqualTo("HIDDEN");
        assertThat(sent.get("note").asString()).isEqualTo("Add photos and a landmark first.");
        assertThat(sent.get("canRequest").asBoolean()).isTrue();
        // Nothing to reject now.
        assertThat(decide(ops, salon, "REJECT", "Again please.").status()).isEqualTo(409);

        assertThat(ask(salon, true).status()).isEqualTo(200);
        assertThat(status(salon).has("note")).as("the old reason is gone").isFalse();
    }

    @Test
    void takingAListedBusinessOffRemovesItFromSearchAndOnlyStaffCanBringItBack() throws Exception {
        String m = SearchApiTests.marker();
        Salon salon = readySalon(m + " Salon");
        String ops = staff("OPS_ADMIN", null);
        assertThat(decide(ops, salon, "LIST", "Listed directly.").status())
                .isEqualTo(200); // staff may list without a request
        assertThat(findable(m)).isTrue();

        assertThat(decide(ops, salon, "LIST", "Already.").status()).isEqualTo(409);
        assertThat(decide(ops, salon, "DELIST", "Complaints about no-shows.").status())
                .isEqualTo(200);
        assertThat(findable(m)).isFalse();
        JsonNode off = status(salon);
        assertThat(off.get("status").asString()).isEqualTo("DELISTED");
        assertThat(off.get("note").asString()).isEqualTo("Complaints about no-shows.");
        assertThat(off.get("canRequest").asBoolean()).isFalse();
        assertThat(ask(salon, true).status()).isEqualTo(409);
        assertThat(queue(ops, "DELISTED")).contains(m + " Salon");

        assertThat(decide(ops, salon, "LIST", "Sorted out with the owner.").status())
                .isEqualTo(200);
        assertThat(findable(m)).isTrue();
    }

    @Test
    void aBusinessCanTakeItsListingBackItself() throws Exception {
        String m = SearchApiTests.marker();
        Salon salon = readySalon(m + " Salon");
        String ops = staff("OPS_ADMIN", null);
        assertThat(decide(ops, salon, "LIST", "Listed directly.").status()).isEqualTo(200);
        assertThat(findable(m)).isTrue();

        assertThat(ask(salon, false).status()).isEqualTo(200);
        assertThat(findable(m)).isFalse();
        assertThat(status(salon).get("status").asString()).isEqualTo("HIDDEN");
        assertThat(ask(salon, false).status()).as("nothing to take back").isEqualTo(409);
        assertThat(ask(salon, true).status()).isEqualTo(200); // and it can ask again
    }

    @Test
    void staffCannotListWhatIsNotReadyOrNotInGoodStanding() throws Exception {
        String ops = staff("OPS_ADMIN", null);
        Salon empty = newSalon(SearchApiTests.marker() + " Empty");
        assertThat(decide(ops, empty, "LIST", "Trying anyway.").status()).isEqualTo(422);

        Salon suspended = readySalon(SearchApiTests.marker() + " Suspended");
        system.execute("UPDATE tenant.organizations SET status = 'SUSPENDED' WHERE id = ?", suspended.id());
        assertThat(decide(ops, suspended, "LIST", "Trying anyway.").status()).isEqualTo(409);
    }

    @Test
    void onlyTheRightStaffInTheRightMarketDecide() throws Exception {
        String name = SearchApiTests.marker() + " Salon";
        Salon salon = readySalon(name);
        assertThat(ask(salon, true).status()).isEqualTo(200);

        // Support agents do not review the marketplace.
        String support = staff("SUPPORT_AGENT", null);
        assertThat(call(get("/v1/admin/marketplace-listings"), null, support).status())
                .isEqualTo(403);
        assertThat(decide(support, salon, "LIST", "Not my job.").status()).isEqualTo(403);

        // A market manager of Kenya cannot see or decide about an Ethiopian business.
        String kenya = staff("MARKET_MANAGER", "KE");
        assertThat(queue(kenya, null)).doesNotContain(name);
        // (Outside their market staff are told it does not exist, as everywhere in the admin console.)
        assertThat(decide(kenya, salon, "LIST", "Wrong market.").status()).isEqualTo(404);

        // Staff who belong to the business cannot decide about it.
        grant(salon.owner(), "OPS_ADMIN", null);
        Call ownerAsStaff = signInWithOtp(salon.owner().phone(), "ADMIN_CONSOLE");
        assertThat(decide(ownerAsStaff.text("accessToken"), salon, "LIST", "My own business.")
                        .status())
                .isEqualTo(403);
        assertThat(status(salon).get("status").asString()).isEqualTo("PENDING_REVIEW");

        // Decisions need a reason, and a known decision.
        String ops = staff("OPS_ADMIN", null);
        assertThat(callInvalid(
                                post("/v1/admin/orgs/" + salon.id() + "/marketplace-decision"),
                                Map.of("decision", "LIST", "reason", "ok"),
                                ops)
                        .status())
                .isEqualTo(400);
        assertThat(callInvalid(
                                post("/v1/admin/orgs/" + salon.id() + "/marketplace-decision"),
                                Map.of("decision", "MAYBE", "reason", "Not sure about this."),
                                ops)
                        .status())
                .isEqualTo(400);
    }

    @Test
    void onlyThoseWhoManageTheBusinessCanAsk() throws Exception {
        Salon salon = readySalon(SearchApiTests.marker() + " Salon");
        Person stylist = newMember(salon, "PROFESSIONAL");
        assertThat(call(get(salon.path("/marketplace")), null, stylist.token()).status())
                .isEqualTo(403);
        assertThat(call(put(salon.path("/marketplace")), Map.of("requested", true), stylist.token())
                        .status())
                .isEqualTo(403);
        assertThat(call(get(salon.path("/marketplace")), null, null).status()).isEqualTo(401);
    }
}
