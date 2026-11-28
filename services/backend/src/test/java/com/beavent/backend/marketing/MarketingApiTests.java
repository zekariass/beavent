package com.beavent.backend.marketing;

import static com.beavent.db.iam.Tables.USERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.beavent.backend.scheduling.SchedulingTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** SMS campaigns, counted links and stopping (plan step 8.5). */
class MarketingApiTests extends SchedulingTestSupport {

    @Autowired
    Marketing marketing;

    // --- helpers ----------------------------------------------------------------------------------------------

    static void waitFor(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(25));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(100);
        }
    }

    String slug(Shop shop) {
        return system.resultQuery("SELECT slug::text FROM tenant.organizations WHERE id = ?", shop.salon().id())
                .fetchOne(0, String.class);
    }

    UUID userOf(Person person) {
        return system.select(USERS.ID)
                .from(USERS)
                .where(USERS.PHONE_E164.eq(person.phone()))
                .fetchSingle(USERS.ID);
    }

    String path(Shop shop, String rest) {
        return shop.salon().path("/marketing" + rest);
    }

    Map<String, Object> campaign(String name, String audience, String en, String am) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("audience", audience);
        Map<String, String> texts = new HashMap<>();
        if (en != null) texts.put("en", en);
        if (am != null) texts.put("am", am);
        body.put("texts", texts);
        return body;
    }

    Call follow(Shop shop, Person person, boolean notifications) throws Exception {
        Call c = call(put("/v1/me/follows/" + slug(shop)), Map.of("notifications", notifications), person.token());
        assertThat(c.status()).as(String.valueOf(c.body())).isEqualTo(200);
        return c;
    }

    String create(Shop shop, Map<String, Object> body) throws Exception {
        Call c = call(post(path(shop, "/campaigns")), body, shop.salon().owner().token());
        assertThat(c.status()).as(String.valueOf(c.body())).isEqualTo(201);
        return c.text("id");
    }

    Call schedule(Shop shop, String id, Instant at) throws Exception {
        return call(
                post(path(shop, "/campaigns/" + id + "/schedule")),
                at == null ? Map.of() : Map.of("at", at.toString()),
                shop.salon().owner().token());
    }

    JsonNode campaignOf(Shop shop, String id) throws Exception {
        Call c = call(get(path(shop, "/campaigns/" + id)), null, shop.salon().owner().token());
        assertThat(c.status()).isEqualTo(200);
        return c.body();
    }

    /** One number from a campaign's stats, for waiting on (no checked exceptions). */
    int stat(Shop shop, String id, String field) {
        try {
            return campaignOf(shop, id).get("stats").get(field).asInt();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    boolean received(Person person, String fragment) {
        return sms.messagesTo(person.phone()).stream().anyMatch(m -> m.contains(fragment));
    }

    String tokenOf(String campaignId, Person person) {
        return system.resultQuery(
                        "SELECT token FROM marketing.campaign_recipients WHERE campaign_id = ?::uuid AND user_id = ?",
                        campaignId,
                        userOf(person))
                .fetchOne(0, String.class);
    }

    // --- audiences and the campaign itself --------------------------------------------------------------------

    @Test
    void aBusinessMessagesItsFollowersAndOnlyThoseWhoKeepNewsAndOffersOn() throws Exception {
        Shop shop = shop();
        Person ana = newPerson();
        Person ben = newPerson();
        Person cy = newPerson();
        follow(shop, ana, true);
        follow(shop, ben, true);
        follow(shop, cy, false); // follows, but turned "news and offers" off

        Call audience = call(get(path(shop, "/audience?audience=FOLLOWERS")), null, shop.salon().owner().token());
        assertThat(audience.status()).as(String.valueOf(audience.body())).isEqualTo(200);
        assertThat(audience.body().get("reachable").asInt()).isEqualTo(2);

        String id = create(shop, campaign("Reopening", "FOLLOWERS", "Hi {name}, we are open again.", "ሰላም {name}፣ እንደገና ከፍተናል።"));

        // The text as people will read it, with the counted link and how to stop; Amharic needs more messages.
        Call preview = call(get(path(shop, "/campaigns/" + id + "/preview")), null, shop.salon().owner().token());
        assertThat(preview.status()).as(String.valueOf(preview.body())).isEqualTo(200);
        JsonNode en = preview.body().get("items").get(0);
        assertThat(en.get("language").asString()).isEqualTo("en");
        assertThat(en.get("text").asString()).contains("/l/").contains("Stop: ").contains("/u/");
        assertThat(en.get("segments").asInt()).isEqualTo(1);

        // A test message goes to the sender's own phone only.
        Person owner = shop.salon().owner();
        Call test = call(post(path(shop, "/campaigns/" + id + "/test")), null, owner.token());
        assertThat(test.status()).as(String.valueOf(test.body())).isEqualTo(200);
        assertThat(received(owner, "[Test]")).isTrue();
        assertThat(received(ana, "we are open again")).isFalse();

        // Nothing goes until it is scheduled.
        assertThat(marketing.sendDue(Instant.now())).isZero();
        Call scheduled = schedule(shop, id, null);
        assertThat(scheduled.status()).as(String.valueOf(scheduled.body())).isEqualTo(200);
        assertThat(scheduled.text("status")).isEqualTo("SCHEDULED");
        assertThat(marketing.sendDue(Instant.now().plusSeconds(2))).isEqualTo(1);

        waitFor("the followers to be texted", () -> received(ana, "we are open again") && received(ben, "we are open again"));
        assertThat(received(cy, "we are open again")).isFalse();
        assertThat(sms.messagesTo(ana.phone()).stream().filter(m -> m.contains("we are open again")).findFirst().orElseThrow())
                .startsWith("Hi,")
                .contains("/l/" + tokenOf(id, ana))
                .contains("/u/" + tokenOf(id, ana));

        // What became of the messages.
        waitFor("the stats", () -> stat(shop, id, "sent") == 2);
        JsonNode stats = campaignOf(shop, id).get("stats");
        assertThat(stats.get("recipients").asInt()).isEqualTo(2);
        assertThat(stats.get("failed").asInt()).isZero();
        assertThat(campaignOf(shop, id).get("status").asString()).isEqualTo("SENT");

        // It was started: it cannot be changed or sent again.
        assertThat(call(put(path(shop, "/campaigns/" + id)), campaign("Reopening", "FOLLOWERS", "Changed", null), owner.token())
                        .status())
                .isEqualTo(409);
        assertThat(schedule(shop, id, null).status()).isEqualTo(409);
    }

    @Test
    void aClickOnTheLinkInAMessageIsCountedAndLeadsToThePlace() throws Exception {
        Shop shop = shop();
        Person ana = newPerson();
        Person ben = newPerson();
        follow(shop, ana, true);
        follow(shop, ben, true);
        String id = create(shop, campaign("Offer", "FOLLOWERS", "Half price braids this week.", null));
        assertThat(schedule(shop, id, null).status()).isEqualTo(200);
        assertThat(marketing.sendDue(Instant.now().plusSeconds(2))).isEqualTo(1);
        waitFor("both texted", () -> received(ana, "braids") && received(ben, "braids"));

        String token = tokenOf(id, ana);
        Call click = call(get("/v1/public/links/" + token), null, null);
        assertThat(click.status()).as(String.valueOf(click.body())).isEqualTo(200);
        assertThat(click.text("path")).startsWith("/b/" + slug(shop));
        assertThat(click.body().get("params").get("utm_source").asString()).isEqualTo("sms");
        assertThat(click.body().get("params").get("utm_campaign").asString()).isEqualTo(id);
        // The same person clicking twice is one click; another person is another.
        call(get("/v1/public/links/" + token), null, null);
        assertThat(campaignOf(shop, id).get("stats").get("clicks").asInt()).isEqualTo(1);
        call(get("/v1/public/links/" + tokenOf(id, ben)), null, null);
        assertThat(campaignOf(shop, id).get("stats").get("clicks").asInt()).isEqualTo(2);
        assertThat(call(get("/v1/public/links/nonsense"), null, null).status()).isEqualTo(404);
    }

    // --- stopping ---------------------------------------------------------------------------------------------

    @Test
    void aPersonWhoStopsGetsNoMoreAndAMessageAlreadyQueuedIsHeld() throws Exception {
        Shop shop = shop();
        Person ana = newPerson();
        Person ben = newPerson();
        follow(shop, ana, true);
        follow(shop, ben, true);

        // Queued for a time that has not come: the messages wait in the dispatcher's queue.
        String first = create(shop, campaign("First", "FOLLOWERS", "First offer for you.", null));
        Instant later = Instant.now().plus(Duration.ofHours(1));
        assertThat(schedule(shop, first, later).status()).isEqualTo(200);
        assertThat(marketing.sendDue(Instant.now().plus(Duration.ofHours(2)))).isEqualTo(1);
        String token = tokenOf(first, ana);

        // The stop link: a page that names the business (it stops nothing), then the stop.
        Call page = call(get("/v1/public/marketing/stop/" + token), null, null);
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.text("business")).isNotBlank();
        assertThat(system.resultQuery("SELECT notify FROM discovery.follows WHERE user_id = ?", userOf(ana)).fetchOne(0, Boolean.class))
                .as("looking at the page does not stop anything")
                .isTrue();
        assertThat(call(post("/v1/public/marketing/stop/" + token), null, null).status()).isEqualTo(204);
        assertThat(call(post("/v1/public/marketing/stop/" + token), null, null).status()).as("again is fine").isEqualTo(204);
        assertThat(call(post("/v1/public/marketing/stop/nonsense"), null, null).status()).isEqualTo(404);
        assertThat(system.resultQuery("SELECT notify FROM discovery.follows WHERE user_id = ?", userOf(ana)).fetchOne(0, Boolean.class))
                .isFalse();

        // The queued message is not sent to her; it is to him, once it is due.
        system.execute("UPDATE comms.notifications SET send_after = now() WHERE template_code = 'MARKETING_CAMPAIGN'"
                + " AND data->>'campaignId' = ?", first);
        waitFor("him to be texted", () -> received(ben, "First offer"));
        Thread.sleep(1500);
        assertThat(received(ana, "First offer")).isFalse();
        waitFor("the stats", () -> stat(shop, first, "sent") == 1);
        assertThat(campaignOf(shop, first).get("stats").get("suppressed").asInt()).isEqualTo(1);

        // A later campaign leaves her out, and the audience says so.
        String second = create(shop, campaign("Second", "FOLLOWERS", "Second offer.", null));
        assertThat(call(get(path(shop, "/audience?audience=FOLLOWERS")), null, shop.salon().owner().token())
                        .body()
                        .get("reachable")
                        .asInt())
                .isEqualTo(1);
        assertThat(schedule(shop, second, null).status()).isEqualTo(200);
        assertThat(marketing.sendDue(Instant.now().plusSeconds(2))).isEqualTo(1);
        waitFor("him again", () -> received(ben, "Second offer"));
        assertThat(received(ana, "Second offer")).isFalse();
    }

    // --- clients and their agreement --------------------------------------------------------------------------

    @Test
    void clientsAreReachedOnlyWithARecordedAgreementAndPastAndLapsedClientsDifferByTheirLastVisit() throws Exception {
        Shop shop = shop();
        Person desk = newMember(shop.salon(), "RECEPTIONIST");
        Person owner = shop.salon().owner();
        String[] phones = new String[3];
        String[] customerIds = new String[3];
        String[] names = {"Selam", "Dawit", "Hana"};
        for (int i = 0; i < 3; i++) {
            phones[i] = newPhone();
            Call walkIn = call(
                    post(shop.salon().path("/appointments")),
                    Map.of(
                            "locationId", shop.salon().location1().toString(),
                            "start", at(day(), i == 0 ? "09:00" : i == 1 ? "11:00" : "13:00").toInstant().toString(),
                            "items", List.of(Map.of("variantId", shop.variant(), "staffId", shop.abebe())),
                            "customer", Map.of("name", names[i], "phone", phones[i]),
                            "source", "WALK_IN",
                            "notifyCustomer", false),
                    desk.token());
            assertThat(walkIn.status()).as(String.valueOf(walkIn.body())).isEqualTo(201);
            customerIds[i] = system.resultQuery("SELECT id::text FROM crm.customers WHERE org_id = ? AND phone_e164 = ?",
                            shop.salon().id(), phones[i]).fetchOne(0, String.class);
            for (String status : List.of("ARRIVED", "IN_PROGRESS", "COMPLETED")) {
                assertThat(call(post(shop.salon().path("/appointments/" + walkIn.text("id") + "/status")), Map.of("status", status), owner.token())
                                .status())
                        .isEqualTo(200);
            }
        }
        // Selam came long ago; Dawit and Hana came lately.
        system.execute("UPDATE scheduling.appointments SET starts_at = starts_at - interval '200 days',"
                + " ends_at = ends_at - interval '200 days' WHERE customer_id = ?::uuid", customerIds[0]);

        // Nobody has agreed yet.
        assertThat(call(get(path(shop, "/audience?audience=CLIENTS")), null, owner.token()).body().get("reachable").asInt()).isZero();
        assertThat(schedule(shop, create(shop, campaign("Nobody", "CLIENTS", "Hello", null)), null).status())
                .as("an empty audience")
                .isEqualTo(422);

        // The business records who agreed (Hana did not).
        for (int i = 0; i < 2; i++) {
            Call recorded = call(
                    put(shop.salon().path("/customers/" + customerIds[i] + "/marketing-consent")),
                    Map.of("granted", true, "note", "Agreed on the paper form."),
                    owner.token());
            assertThat(recorded.status()).as(String.valueOf(recorded.body())).isEqualTo(200);
            assertThat(recorded.body().get("granted").asBoolean()).isTrue();
        }
        assertThat(call(get(shop.salon().path("/customers/" + customerIds[2] + "/marketing-consent")), null, owner.token())
                        .body()
                        .get("granted")
                        .asBoolean())
                .isFalse();
        assertThat(call(get(path(shop, "/audience?audience=CLIENTS")), null, owner.token()).body().get("reachable").asInt()).isEqualTo(2);
        assertThat(call(get(path(shop, "/audience?audience=PAST_CLIENTS&days=90")), null, owner.token()).body().get("reachable").asInt())
                .as("came within 90 days: Dawit")
                .isEqualTo(1);
        assertThat(call(get(path(shop, "/audience?audience=LAPSED_CLIENTS&days=90")), null, owner.token()).body().get("reachable").asInt())
                .as("not for 90 days: Selam")
                .isEqualTo(1);

        // The lapsed campaign reaches Selam only, by her number.
        String id = create(shop, campaign("Come back", "LAPSED_CLIENTS", "We miss you, {name}!", null));
        assertThat(call(put(path(shop, "/campaigns/" + id)), campaign("Come back", "LAPSED_CLIENTS", "We miss you, {name}!", null), owner.token())
                        .status())
                .isEqualTo(200);
        Map<String, Object> lapsed = campaign("Come back", "LAPSED_CLIENTS", "We miss you, {name}!", null);
        lapsed.put("days", 90);
        assertThat(call(put(path(shop, "/campaigns/" + id)), lapsed, owner.token()).status()).isEqualTo(200);
        assertThat(schedule(shop, id, null).status()).isEqualTo(200);
        assertThat(marketing.sendDue(Instant.now().plusSeconds(2))).isEqualTo(1);
        waitFor("Selam to be texted", () -> sms.messagesTo(phones[0]).stream().anyMatch(m -> m.contains("We miss you")));
        assertThat(sms.messagesTo(phones[0]).getFirst()).as("a client with no account is texted by number").contains("We miss you");
        assertThat(sms.messagesTo(phones[1]).stream().anyMatch(m -> m.contains("We miss you"))).isFalse();

        // Withdrawing the agreement takes the client out.
        assertThat(call(put(shop.salon().path("/customers/" + customerIds[0] + "/marketing-consent")), Map.of("granted", false), owner.token())
                        .status())
                .isEqualTo(200);
        assertThat(call(get(path(shop, "/audience?audience=LAPSED_CLIENTS&days=90")), null, owner.token()).body().get("reachable").asInt())
                .isZero();
    }

    // --- counted links, limits, rights ------------------------------------------------------------------------

    @Test
    void aBusinessMakesCountedLinksAndSeesTheirClicks() throws Exception {
        Shop shop = shop();
        Person owner = shop.salon().owner();
        Call made = call(post(path(shop, "/links")), Map.of("name", "Instagram bio"), owner.token());
        assertThat(made.status()).as(String.valueOf(made.body())).isEqualTo(201);
        String code = made.text("code");
        assertThat(code).startsWith("t");
        assertThat(made.text("url")).endsWith("/l/" + code);

        for (int i = 0; i < 3; i++) {
            Call click = call(get("/v1/public/links/" + code), null, null);
            assertThat(click.status()).isEqualTo(200);
            assertThat(click.text("path")).startsWith("/b/" + slug(shop));
            assertThat(click.body().get("params").get("utm_source").asString()).isEqualTo("link");
        }
        JsonNode list = call(get(path(shop, "/links")), null, owner.token()).body().get("items");
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).get("clicks").asInt()).isEqualTo(3);
        assertThat(list.get(0).get("clicksLast7Days").asInt()).isEqualTo(3);

        assertThat(call(delete(path(shop, "/links/" + made.text("id"))), null, owner.token()).status()).isEqualTo(204);
        assertThat(call(get("/v1/public/links/" + code), null, null).status()).isEqualTo(404);
    }

    @Test
    void campaignsHaveLimitsAndNeedTheRightPermission() throws Exception {
        Shop shop = shop();
        Person owner = shop.salon().owner();
        Person reception = newMember(shop.salon(), "RECEPTIONIST");
        Person marketer = newMember(shop.salon(), "MARKETING");
        Person ana = newPerson();
        follow(shop, ana, true);

        // The desk cannot run marketing; the marketing role can.
        assertThat(call(get(path(shop, "/campaigns")), null, reception.token()).status()).isEqualTo(403);
        assertThat(call(post(path(shop, "/campaigns")), campaign("x", "FOLLOWERS", "Hi", null), reception.token()).status())
                .isEqualTo(403);
        assertThat(call(get(path(shop, "/campaigns")), null, marketer.token()).status()).isEqualTo(200);
        // Another business cannot see this one's campaigns.
        Shop other = shop();
        String id = create(shop, campaign("Mine", "FOLLOWERS", "Hi", null));
        assertThat(call(get(other.salon().path("/marketing/campaigns/" + id)), null, other.salon().owner().token()).status())
                .isEqualTo(404);

        // A message must have text, and not too much of it.
        assertThat(call(post(path(shop, "/campaigns")), campaign("Empty", "FOLLOWERS", " ", null), owner.token()).status())
                .isEqualTo(400);
        assertThat(call(post(path(shop, "/campaigns")), campaign("Long", "FOLLOWERS", "x".repeat(481), null), owner.token()).status())
                .isEqualTo(400);

        // Three campaigns a day.
        assertThat(schedule(shop, id, null).status()).isEqualTo(200);
        for (int i = 0; i < 2; i++) {
            assertThat(schedule(shop, create(shop, campaign("More " + i, "FOLLOWERS", "Hi again", null)), null).status()).isEqualTo(200);
        }
        assertThat(schedule(shop, create(shop, campaign("One too many", "FOLLOWERS", "Hi", null)), null).status()).isEqualTo(422);

        // A draft can be deleted, a started campaign cannot; a scheduled one can be cancelled.
        String draft = create(shop, campaign("Draft", "FOLLOWERS", "Hi", null));
        assertThat(call(delete(path(shop, "/campaigns/" + draft)), null, owner.token()).status()).isEqualTo(204);
        assertThat(call(delete(path(shop, "/campaigns/" + id)), null, owner.token()).status()).isEqualTo(409);
        Call cancelled = call(post(path(shop, "/campaigns/" + id + "/cancel")), null, owner.token());
        assertThat(cancelled.status()).as(String.valueOf(cancelled.body())).isEqualTo(200);
        assertThat(cancelled.text("status")).isEqualTo("CANCELLED");
        // The other two (scheduled for now) start; the cancelled one does not.
        assertThat(marketing.sendDue(Instant.now().plusSeconds(5))).isEqualTo(2);
        assertThat(campaignOf(shop, id).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(campaignOf(shop, id).get("stats").get("recipients").asInt()).as("a cancelled campaign is not started").isZero();
    }
}
