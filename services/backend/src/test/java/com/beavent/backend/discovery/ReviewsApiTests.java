package com.beavent.backend.discovery;

import static com.beavent.db.iam.Tables.USERS;
import static com.beavent.db.iam.Tables.USER_STAFF_ROLES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.beavent.backend.scheduling.SchedulingTestSupport;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Verified reviews of places (plan step 8.3). */
class ReviewsApiTests extends SchedulingTestSupport {

    @Autowired
    Listings listings;

    // --- helpers ----------------------------------------------------------------------------------------------

    UUID userOf(Person person) {
        return system.select(USERS.ID)
                .from(USERS)
                .where(USERS.PHONE_E164.eq(person.phone()))
                .fetchSingle(USERS.ID);
    }

    void named(Person person, String first, String last) {
        system.execute(
                "INSERT INTO iam.user_profiles (user_id, first_name, last_name) VALUES (?, ?, ?)"
                        + " ON CONFLICT (user_id) DO UPDATE SET first_name = excluded.first_name, last_name = excluded.last_name",
                userOf(person),
                first,
                last);
    }

    String slug(Shop shop) {
        return system.resultQuery(
                        "SELECT slug::text FROM tenant.organizations WHERE id = ?",
                        shop.salon().id())
                .fetchOne(0, String.class);
    }

    Call bookAt(Shop shop, String hhmm, Person person) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(shop.variant(), shop.abebe())));
        body.put("start", at(day(), hhmm).toInstant().toString());
        Call held = call(post("/v1/public/locations/" + shop.salon().location1() + "/holds"), body, null);
        assertThat(held.status()).as(String.valueOf(held.body())).isEqualTo(201);
        Call booked = call(
                post("/v1/me/appointments"),
                Map.of(
                        "locationId", shop.salon().location1().toString(),
                        "holdId", held.text("holdId"),
                        "holderKey", held.text("holderKey")),
                person.token());
        assertThat(booked.status()).as(String.valueOf(booked.body())).isEqualTo(201);
        return booked;
    }

    void complete(Shop shop, String appointmentId) throws Exception {
        for (String status : List.of("ARRIVED", "IN_PROGRESS", "COMPLETED")) {
            Call c = call(
                    post(shop.salon().path("/appointments/" + appointmentId + "/status")),
                    Map.of("status", status),
                    shop.salon().owner().token());
            assertThat(c.status()).as(String.valueOf(c.body())).isEqualTo(200);
        }
    }

    /** A completed visit of this person; returns the appointment id. */
    String visit(Shop shop, String hhmm, Person person) throws Exception {
        String id = bookAt(shop, hhmm, person).text("id");
        complete(shop, id);
        return id;
    }

    Call invalidReview(Person person, String appointmentId, int rating, String body) throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put("rating", rating);
        input.put("body", body);
        return callInvalid(post("/v1/me/appointments/" + appointmentId + "/review"), input, person.token());
    }

    Call review(Person person, String appointmentId, int rating, String body) throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put("rating", rating);
        if (body != null) {
            input.put("body", body);
        }
        return call(post("/v1/me/appointments/" + appointmentId + "/review"), input, person.token());
    }

    JsonNode publicReviews(Shop shop) throws Exception {
        Call c = call(get("/v1/public/businesses/" + slug(shop) + "/reviews"), null, null);
        assertThat(c.status()).as(String.valueOf(c.body())).isEqualTo(200);
        return c.body();
    }

    /** What search says about the place: {@code [ratingAvg or null, ratingCount]}. */
    Object[] searchRating(Shop shop) throws Exception {
        Call c = call(get("/v1/public/search?location=" + shop.salon().location1()), null, null);
        assertThat(c.status()).as(String.valueOf(c.body())).isEqualTo(200);
        JsonNode items = c.body().get("items");
        assertThat(items.size()).isEqualTo(1);
        JsonNode item = items.get(0);
        return new Object[] {
            item.has("ratingAvg") ? item.get("ratingAvg").asDouble() : null,
            item.get("ratingCount").asInt()
        };
    }

    void list(Shop shop) {
        system.execute(
                "UPDATE tenant.organizations SET marketplace_status = 'LISTED' WHERE id = ?",
                shop.salon().id());
        listings.refreshOrganization(shop.salon().id(), Instant.now());
    }

    /** A person with a platform staff role, signed in to the admin console. */
    String staff(String roleCode) throws Exception {
        Person person = newPerson();
        system.insertInto(USER_STAFF_ROLES)
                .set(USER_STAFF_ROLES.USER_ID, userOf(person))
                .set(USER_STAFF_ROLES.ROLE_CODE, roleCode)
                .set(USER_STAFF_ROLES.MARKET_ID, (UUID) null)
                .execute();
        Call signedIn = signInWithOtp(person.phone(), "ADMIN_CONSOLE");
        assertThat(signedIn.status()).as(String.valueOf(signedIn.body())).isEqualTo(200);
        return signedIn.text("accessToken");
    }

    // --- writing ----------------------------------------------------------------------------------------------

    @Test
    void aPersonReviewsACompletedVisitAndTheRatingFollowsInSearch() throws Exception {
        Shop shop = shop();
        Person ana = newPerson();
        Person ben = newPerson();
        named(ana, "Hana", "Tesfaye");

        String anaVisit = visit(shop, "10:00", ana);
        String benVisit = visit(shop, "13:00", ben);

        // Waiting for a review: the visit, with what it was and who did it.
        Call mine = call(get("/v1/me/reviews"), null, ana.token());
        assertThat(mine.status()).isEqualTo(200);
        assertThat(mine.body().get("pending").size()).isEqualTo(1);
        JsonNode pending = mine.body().get("pending").get(0);
        assertThat(pending.get("appointmentId").asString()).isEqualTo(anaVisit);
        assertThat(pending.get("serviceName").get("en").asString()).isEqualTo("Haircut");
        assertThat(pending.get("staffName").asString()).isEqualTo("Abebe");
        assertThat(pending.get("businessSlug").asString()).isEqualTo(slug(shop));
        assertThat(mine.body().get("reviews").size()).isZero();

        // Reviews written before the place is listed count the moment it is.
        Call written = review(ana, anaVisit, 5, "  Lovely haircut, on time.  ");
        assertThat(written.status()).as(String.valueOf(written.body())).isEqualTo(201);
        assertThat(written.body().get("body").asString()).isEqualTo("Lovely haircut, on time.");
        assertThat(written.body().get("status").asString()).isEqualTo("PUBLISHED");
        review(ben, benVisit, 2, null);
        list(shop);
        assertThat(searchRating(shop)).containsExactly(3.5, 2);

        // Once per visit.
        assertThat(review(ana, anaVisit, 1, "again").status()).isEqualTo(409);

        // The public page: newest first, the author as given name and initial, the visit's day, no one else's data.
        JsonNode page = publicReviews(shop);
        assertThat(page.get("summary").get("count").asInt()).isEqualTo(2);
        assertThat(page.get("summary").get("average").asDouble()).isEqualTo(3.5);
        assertThat(page.get("summary").get("distribution").toString()).isEqualTo("[0,1,0,0,1]");
        JsonNode items = page.get("items");
        assertThat(items.size()).isEqualTo(2);
        assertThat(items.get(1).get("authorName").asString()).isEqualTo("Hana T.");
        assertThat(items.get(1).get("body").asString()).isEqualTo("Lovely haircut, on time.");
        assertThat(items.get(1).get("serviceName").get("en").asString()).isEqualTo("Haircut");
        assertThat(items.get(0).has("authorName")).as("no name given").isFalse();
        assertThat(items.get(0).has("body")).isFalse();
        assertThat(page.toString()).doesNotContain(ana.phone()).doesNotContain(ben.phone());

        // Pages.
        Call first = call(get("/v1/public/businesses/" + slug(shop) + "/reviews?limit=1"), null, null);
        assertThat(first.body().get("items").size()).isEqualTo(1);
        String cursor = first.text("nextCursor");
        Call second =
                call(get("/v1/public/businesses/" + slug(shop) + "/reviews?limit=1&cursor=" + cursor), null, null);
        assertThat(second.body().get("items").size()).isEqualTo(1);
        assertThat(second.body().has("nextCursor")).isFalse();
        assertThat(second.body().get("items").get(0).get("id").asString())
                .isNotEqualTo(first.body().get("items").get(0).get("id").asString());

        // Narrowed to a place, and to one that does not exist.
        String place = system.resultQuery(
                        "SELECT slug::text FROM tenant.locations WHERE id = ?",
                        shop.salon().location1())
                .fetchOne(0, String.class);
        assertThat(call(get("/v1/public/businesses/" + slug(shop) + "/reviews?location=" + place), null, null)
                        .body()
                        .get("summary")
                        .get("count")
                        .asInt())
                .isEqualTo(2);
        assertThat(call(get("/v1/public/businesses/" + slug(shop) + "/reviews?location=nowhere"), null, null)
                        .status())
                .isEqualTo(404);
        assertThat(call(get("/v1/public/businesses/no-such-business/reviews"), null, null)
                        .status())
                .isEqualTo(404);

        // The reviewer's own list now holds it, and the visit is no longer pending.
        Call after = call(get("/v1/me/reviews"), null, ana.token());
        assertThat(after.body().get("pending").size()).isZero();
        assertThat(after.body().get("reviews").size()).isEqualTo(1);

        // Editing changes the average at once; deleting takes it out, and the visit can be reviewed again.
        String benReview = call(get("/v1/me/reviews"), null, ben.token())
                .body()
                .get("reviews")
                .get(0)
                .get("id")
                .asString();
        Call edited = call(
                put("/v1/me/reviews/" + benReview),
                Map.of("rating", 4, "body", "Better on a second look."),
                ben.token());
        assertThat(edited.status()).as(String.valueOf(edited.body())).isEqualTo(200);
        assertThat(searchRating(shop)).containsExactly(4.5, 2);
        assertThat(call(delete("/v1/me/reviews/" + benReview), null, ben.token())
                        .status())
                .isEqualTo(204);
        assertThat(searchRating(shop)).containsExactly(5.0, 1);
        assertThat(call(get("/v1/me/reviews"), null, ben.token())
                        .body()
                        .get("pending")
                        .size())
                .isEqualTo(1);

        // Not someone else's review.
        String anaReview = written.text("id");
        assertThat(call(put("/v1/me/reviews/" + anaReview), Map.of("rating", 1), ben.token())
                        .status())
                .isEqualTo(404);
        assertThat(call(delete("/v1/me/reviews/" + anaReview), null, ben.token())
                        .status())
                .isEqualTo(404);
    }

    @Test
    void onlyACompletedRecentVisitOfTheirOwnCanBeReviewed() throws Exception {
        Shop shop = shop();
        Person ana = newPerson();
        Person ben = newPerson();

        // Booked but not completed.
        String upcoming = bookAt(shop, "10:00", ana).text("id");
        assertThat(review(ana, upcoming, 5, null).status()).isEqualTo(422);
        assertThat(call(get("/v1/me/reviews"), null, ana.token())
                        .body()
                        .get("pending")
                        .size())
                .isZero();

        // Completed, but not theirs.
        complete(shop, upcoming);
        assertThat(review(ben, upcoming, 5, null).status()).isEqualTo(404);
        assertThat(review(ben, UUID.randomUUID().toString(), 5, null).status()).isEqualTo(404);
        assertThat(call(post("/v1/me/appointments/" + upcoming + "/review"), Map.of("rating", 5), null)
                        .status())
                .isEqualTo(401);

        // Rating and text limits.
        assertThat(invalidReview(ana, upcoming, 0, null).status()).isEqualTo(400);
        assertThat(invalidReview(ana, upcoming, 6, null).status()).isEqualTo(400);
        assertThat(invalidReview(ana, upcoming, 5, "x".repeat(1001)).status()).isEqualTo(400);

        // Too long ago: out of the pending list and refused.
        system.execute(
                "UPDATE scheduling.appointments SET starts_at = starts_at - interval '120 days',"
                        + " ends_at = ends_at - interval '120 days' WHERE id = ?",
                UUID.fromString(upcoming));
        assertThat(review(ana, upcoming, 5, null).status()).isEqualTo(422);
        assertThat(call(get("/v1/me/reviews"), null, ana.token())
                        .body()
                        .get("pending")
                        .size())
                .isZero();
    }

    // --- the business -----------------------------------------------------------------------------------------

    @Test
    void aBusinessAnswersItsReviewsAndOnlyItsOwn() throws Exception {
        Shop shop = shop();
        Shop other = shop();
        Person ana = newPerson();
        String review = review(ana, visit(shop, "10:00", ana), 4, "Good, but a long wait.")
                .text("id");
        String orgPath = "/v1/orgs/" + shop.salon().id() + "/reviews";
        String token = shop.salon().owner().token();

        Call list = call(get(orgPath + "?unanswered=true"), null, token);
        assertThat(list.status()).as(String.valueOf(list.body())).isEqualTo(200);
        assertThat(list.body().get("items").size()).isEqualTo(1);
        assertThat(list.body().get("items").get(0).get("reported").asBoolean()).isFalse();
        assertThat(list.body().get("summary").get("count").asInt()).isEqualTo(1);

        // A role without the permission cannot read or answer.
        Person reception = newMember(shop.salon(), "RECEPTIONIST");
        assertThat(call(get(orgPath), null, reception.token()).status()).isEqualTo(403);
        assertThat(call(put(orgPath + "/" + review + "/response"), Map.of("body", "Sorry!"), reception.token())
                        .status())
                .isEqualTo(403);
        Person manager = newMember(shop.salon(), "MANAGER");
        assertThat(call(get(orgPath), null, manager.token()).status()).isEqualTo(200);

        // Another business cannot touch it.
        assertThat(call(
                                put("/v1/orgs/" + other.salon().id() + "/reviews/" + review + "/response"),
                                Map.of("body", "Not ours."),
                                other.salon().owner().token())
                        .status())
                .isEqualTo(404);

        // The answer shows under the review for everyone, and the review leaves "unanswered".
        Call answered = call(
                put(orgPath + "/" + review + "/response"), Map.of("body", "  Thank you, we added a stylist. "), token);
        assertThat(answered.status()).as(String.valueOf(answered.body())).isEqualTo(200);
        assertThat(answered.body().get("response").get("body").asString()).isEqualTo("Thank you, we added a stylist.");
        assertThat(call(get(orgPath + "?unanswered=true"), null, token)
                        .body()
                        .get("items")
                        .size())
                .isZero();
        assertThat(publicReviews(shop)
                        .get("items")
                        .get(0)
                        .get("response")
                        .get("body")
                        .asString())
                .isEqualTo("Thank you, we added a stylist.");
        assertThat(call(get("/v1/me/reviews"), null, ana.token())
                        .body()
                        .get("reviews")
                        .get(0)
                        .get("response")
                        .get("body")
                        .asString())
                .isEqualTo("Thank you, we added a stylist.");
        assertThat(callInvalid(put(orgPath + "/" + review + "/response"), Map.of("body", ""), token)
                        .status())
                .isEqualTo(400);

        // Taken back.
        Call removed = call(delete(orgPath + "/" + review + "/response"), null, token);
        assertThat(removed.status()).isEqualTo(200);
        assertThat(removed.body().has("response")).isFalse();
        assertThat(publicReviews(shop).get("items").get(0).has("response")).isFalse();
    }

    // --- moderation -------------------------------------------------------------------------------------------

    @Test
    void aReportedReviewIsHiddenOrKeptByStaffAndTheRatingFollows() throws Exception {
        Shop shop = shop();
        Person ana = newPerson();
        Person ben = newPerson();
        String anaReview = review(ana, visit(shop, "10:00", ana), 1, "Terrible, never again.")
                .text("id");
        review(ben, visit(shop, "13:00", ben), 5, "Great.");
        list(shop);
        assertThat(searchRating(shop)).containsExactly(3.0, 2);

        String orgPath = "/v1/orgs/" + shop.salon().id() + "/reviews/" + anaReview;
        String token = shop.salon().owner().token();
        Call reported = call(
                post(orgPath + "/report"), Map.of("reason", "HARASSMENT", "details", "Names a staff member."), token);
        assertThat(reported.status()).as(String.valueOf(reported.body())).isEqualTo(200);
        assertThat(reported.body().get("reported").asBoolean()).isTrue();
        assertThat(call(post(orgPath + "/report"), Map.of("reason", "OTHER"), token)
                        .status())
                .isEqualTo(200);
        assertThat(callInvalid(post(orgPath + "/report"), Map.of("reason", "NOPE"), token)
                        .status())
                .isEqualTo(400);

        // The moderator sees it once, with the report.
        String moderator = staff("MODERATOR");
        Call queue = call(get("/v1/admin/reviews"), null, moderator);
        assertThat(queue.status()).as(String.valueOf(queue.body())).isEqualTo(200);
        JsonNode mine = null;
        for (JsonNode r : queue.body().get("items")) {
            if (r.get("id").asString().equals(anaReview)) {
                mine = r;
            }
        }
        assertThat(mine).as("in the queue").isNotNull();
        assertThat(mine.get("reports").size()).isEqualTo(1);
        assertThat(mine.get("reports").get(0).get("reason").asString()).isEqualTo("HARASSMENT");
        assertThat(mine.get("rating").asInt()).isEqualTo(1);

        // Not for staff without the permission, and a reason is required.
        assertThat(call(get("/v1/admin/reviews"), null, staff("FINANCE")).status())
                .isEqualTo(403);
        String decision = "/v1/admin/reviews/" + anaReview + "/decision";
        assertThat(callInvalid(post(decision), Map.of("decision", "HIDE", "reason", "no"), moderator)
                        .status())
                .isEqualTo(400);
        assertThat(call(post(decision), Map.of("decision", "HIDE", "reason", "It names a person."), staff("FINANCE"))
                        .status())
                .isEqualTo(403);

        // Hiding takes it out of the page, the count and the rating; the author is told why it is not shown.
        Call hidden = call(post(decision), Map.of("decision", "HIDE", "reason", "It names a person."), moderator);
        assertThat(hidden.status()).as(String.valueOf(hidden.body())).isEqualTo(200);
        assertThat(hidden.body().get("status").asString()).isEqualTo("HIDDEN");
        assertThat(publicReviews(shop).get("summary").get("count").asInt()).isEqualTo(1);
        assertThat(searchRating(shop)).containsExactly(5.0, 1);
        Call authors = call(get("/v1/me/reviews"), null, ana.token());
        assertThat(authors.body().get("reviews").get(0).get("status").asString())
                .isEqualTo("HIDDEN");
        assertThat(call(put("/v1/me/reviews/" + anaReview), Map.of("rating", 5), ana.token())
                        .status())
                .isEqualTo(409);
        assertThat(call(delete("/v1/me/reviews/" + anaReview), null, ana.token())
                        .status())
                .isEqualTo(409);
        assertThat(review(
                                ana,
                                authors.body()
                                        .get("reviews")
                                        .get(0)
                                        .get("appointmentId")
                                        .asString(),
                                5,
                                null)
                        .status())
                .isEqualTo(409);
        assertThat(system.resultQuery(
                                "SELECT status FROM trust.content_reports WHERE review_id = ? ORDER BY created_at",
                                UUID.fromString(anaReview))
                        .fetch(0, String.class))
                .containsOnly("ACTIONED");
        assertThat(system.resultQuery(
                                "SELECT action FROM trust.moderation_actions WHERE review_id = ?",
                                UUID.fromString(anaReview))
                        .fetch(0, String.class))
                .containsExactly("HIDE");
        assertThat(call(post(decision), Map.of("decision", "HIDE", "reason", "Again, to be sure."), moderator)
                        .status())
                .isEqualTo(409);

        // Listed under "hidden" for staff, and the business can no longer answer it.
        boolean listed = false;
        for (JsonNode r : call(get("/v1/admin/reviews?status=HIDDEN"), null, moderator)
                .body()
                .get("items")) {
            listed |= r.get("id").asString().equals(anaReview);
        }
        assertThat(listed).isTrue();
        assertThat(call(put(orgPath + "/response"), Map.of("body", "We are sorry."), token)
                        .status())
                .isEqualTo(409);

        // Putting it back restores the rating.
        Call restored = call(
                post(decision), Map.of("decision", "RESTORE", "reason", "On a second look it is fine."), moderator);
        assertThat(restored.status()).isEqualTo(200);
        assertThat(searchRating(shop)).containsExactly(3.0, 2);

        // Reports found unfounded: the review stays.
        call(post(orgPath + "/report"), Map.of("reason", "INAPPROPRIATE"), token);
        Call dismissed =
                call(post(decision), Map.of("decision", "DISMISS", "reason", "Not against the rules."), moderator);
        assertThat(dismissed.status()).as(String.valueOf(dismissed.body())).isEqualTo(200);
        assertThat(dismissed.body().get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(searchRating(shop)).containsExactly(3.0, 2);
        assertThat(call(post(decision), Map.of("decision", "DISMISS", "reason", "Nothing is open."), moderator)
                        .status())
                .isEqualTo(409);

        // Staff who belong to the business cannot decide about its reviews.
        call(post(orgPath + "/report"), Map.of("reason", "OTHER"), token);
        system.insertInto(USER_STAFF_ROLES)
                .set(USER_STAFF_ROLES.USER_ID, userOf(shop.salon().owner()))
                .set(USER_STAFF_ROLES.ROLE_CODE, "MODERATOR")
                .set(USER_STAFF_ROLES.MARKET_ID, (UUID) null)
                .execute();
        Call ownerStaff = signInWithOtp(shop.salon().owner().phone(), "ADMIN_CONSOLE");
        assertThat(call(
                                post(decision),
                                Map.of("decision", "HIDE", "reason", "My own reviews."),
                                ownerStaff.text("accessToken"))
                        .status())
                .isEqualTo(403);
    }
}
