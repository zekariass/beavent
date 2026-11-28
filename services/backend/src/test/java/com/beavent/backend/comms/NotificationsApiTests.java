package com.beavent.backend.comms;

import static com.beavent.db.iam.Tables.USERS;
import static com.beavent.db.iam.Tables.USER_STAFF_ROLES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.beavent.backend.scheduling.SchedulingTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Notifications beyond booking confirmations (plan step 8.4): what a person chose about reminders and quiet hours,
 * review requests after a visit, and the messages about reviews and the marketplace.
 */
class NotificationsApiTests extends SchedulingTestSupport {

    @org.springframework.beans.factory.annotation.Autowired
    com.beavent.backend.platform.messaging.LoggingPushGateway push;

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

    UUID userOf(Person person) {
        return system.select(USERS.ID)
                .from(USERS)
                .where(USERS.PHONE_E164.eq(person.phone()))
                .fetchSingle(USERS.ID);
    }

    Call book(Shop shop, String hhmm, Person person) throws Exception {
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

    Map<String, Object> settings(boolean sms, boolean email, boolean quiet, String from, String to) {
        return Map.of(
                "remindersPush", true,
                "remindersSms", sms,
                "remindersEmail", email,
                "quietHours", quiet,
                "quietFrom", from,
                "quietTo", to);
    }

    void save(Person person, Map<String, Object> settings) throws Exception {
        Call saved = call(put("/v1/me/notification-settings"), settings, person.token());
        assertThat(saved.status()).as(String.valueOf(saved.body())).isEqualTo(200);
    }

    /** The notification with this code for the appointment; waits for it, as events reach the queue a moment later. */
    UUID notificationSoon(String code, String appointmentId) throws InterruptedException {
        waitFor(code, () -> notification(code, appointmentId) != null);
        return notification(code, appointmentId);
    }

    /** The notification with this code for the appointment, or null. */
    UUID notification(String code, String appointmentId) {
        return system.resultQuery(
                        "SELECT id FROM comms.notifications WHERE template_code = ? AND data->>'appointmentId' = ?",
                        code,
                        appointmentId)
                .fetchOne(0, UUID.class);
    }

    void makeDue(UUID notification) {
        system.execute("UPDATE comms.notifications SET send_after = now() WHERE id = ?", notification);
    }

    /** {@code [status, error]} of the notification's first delivery, or null while there is none. */
    String[] delivery(UUID notification) {
        var r = system.resultQuery(
                        "SELECT status, coalesce(error, '') AS error FROM comms.deliveries WHERE notification_id = ?"
                                + " ORDER BY created_at LIMIT 1",
                        notification)
                .fetchOne();
        return r == null ? null : new String[] {r.get("status", String.class), r.get("error", String.class)};
    }

    boolean received(Person person, String fragment) {
        return sms.messagesTo(person.phone()).stream().anyMatch(m -> m.contains(fragment));
    }

    String hhmm(Instant at) {
        return at.atZone(ADDIS).format(DateTimeFormatter.ofPattern("HH:mm"));
    }

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

    // --- settings ---------------------------------------------------------------------------------------------

    @Test
    void aPersonReadsAndChangesTheirChoices() throws Exception {
        Person ana = newPerson();
        Call defaults = call(get("/v1/me/notification-settings"), null, ana.token());
        assertThat(defaults.status()).isEqualTo(200);
        assertThat(defaults.body().get("remindersPush").asBoolean()).isTrue();
        assertThat(defaults.body().get("remindersSms").asBoolean()).isTrue();
        assertThat(defaults.body().get("remindersEmail").asBoolean()).isTrue();
        assertThat(defaults.body().get("quietHours").asBoolean()).isFalse();
        assertThat(defaults.text("quietFrom")).isEqualTo("21:00");
        assertThat(defaults.text("quietTo")).isEqualTo("07:00");

        Call saved =
                call(put("/v1/me/notification-settings"), settings(false, true, true, "22:00", "06:30"), ana.token());
        assertThat(saved.status()).as(String.valueOf(saved.body())).isEqualTo(200);
        assertThat(saved.body().get("remindersSms").asBoolean()).isFalse();
        assertThat(saved.text("quietFrom")).isEqualTo("22:00");
        assertThat(call(get("/v1/me/notification-settings"), null, ana.token()).text("quietTo"))
                .isEqualTo("06:30");

        // Each person has their own; the same start and end, and a time that is not a time, are refused.
        Person ben = newPerson();
        assertThat(call(get("/v1/me/notification-settings"), null, ben.token()).text("quietFrom"))
                .isEqualTo("21:00");
        assertThat(call(put("/v1/me/notification-settings"), settings(true, true, true, "08:00", "08:00"), ana.token())
                        .status())
                .isEqualTo(400);
        assertThat(callInvalid(
                                put("/v1/me/notification-settings"),
                                settings(true, true, true, "25:00", "08:00"),
                                ana.token())
                        .status())
                .isEqualTo(400);
        assertThat(call(get("/v1/me/notification-settings"), null, null).status())
                .isEqualTo(401);
    }

    // --- reminders --------------------------------------------------------------------------------------------

    @Test
    void remindersFollowTheChannelsAPersonSwitchedOff() throws Exception {
        Shop shop = shop();
        Person on = newPerson();
        Person off = newPerson();
        save(off, settings(false, true, false, "21:00", "07:00"));
        String onVisit = book(shop, "10:00", on).text("id");
        String offVisit = book(shop, "13:00", off).text("id");

        UUID first = notificationSoon("APPOINTMENT_REMINDER_2H", onVisit);
        UUID second = notificationSoon("APPOINTMENT_REMINDER_2H", offVisit);
        makeDue(first);
        makeDue(second);

        waitFor("the reminder", () -> received(on, "Reminder:"));
        waitFor("the second one to be handled", () -> delivery(second) != null);
        assertThat(delivery(second)[0]).isEqualTo("SUPPRESSED");
        assertThat(delivery(second)[1]).contains("turned off");
        assertThat(received(off, "Reminder:")).isFalse();
        // Confirmations are not optional.
        assertThat(received(off, "confirmed")).isTrue();
    }

    @Test
    void remindersWaitOutQuietHoursOrAreSkippedWhenTheVisitIsTooNear() throws Exception {
        Shop shop = shop();

        // Quiet hours around now: the reminder waits until they end, about an hour from now.
        Person ana = newPerson();
        Instant now = Instant.now();
        save(
                ana,
                settings(true, true, true, hhmm(now.minus(1, ChronoUnit.HOURS)), hhmm(now.plus(1, ChronoUnit.HOURS))));
        String visit = book(shop, "10:00", ana).text("id");
        UUID reminder = notificationSoon("APPOINTMENT_REMINDER_2H", visit);
        makeDue(reminder);
        waitFor("the reminder to be moved", () -> {
            OffsetDateTime after = system.resultQuery(
                            "SELECT send_after FROM comms.notifications WHERE id = ?", reminder)
                    .fetchOne(0, OffsetDateTime.class);
            return after.toInstant().isAfter(Instant.now().plus(30, ChronoUnit.MINUTES));
        });
        assertThat(delivery(reminder)).as("nothing sent in quiet hours").isNull();
        assertThat(received(ana, "Reminder:")).isFalse();

        // The visit starts before quiet hours would end: a reminder at the very end would be too late, so none is sent.
        Person ben = newPerson();
        save(
                ben,
                settings(true, true, true, hhmm(now.minus(1, ChronoUnit.HOURS)), hhmm(now.plus(1, ChronoUnit.HOURS))));
        String soon = book(shop, "13:00", ben).text("id");
        UUID late = notificationSoon("APPOINTMENT_REMINDER_2H", soon);
        Instant start = Instant.now().plus(80, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        system.execute(
                "UPDATE scheduling.appointments SET starts_at = ?::timestamptz, ends_at = ?::timestamptz WHERE id = ?::uuid",
                start.toString(),
                start.plus(1, ChronoUnit.HOURS).toString(),
                soon);
        system.execute(
                "UPDATE comms.notifications SET send_after = now(), data = jsonb_set(data, '{startsAt}', to_jsonb(?::text))"
                        + " WHERE id = ?",
                start.toString(),
                late);
        waitFor("the late reminder to be handled", () -> delivery(late) != null);
        assertThat(delivery(late)[0]).isEqualTo("SUPPRESSED");
        assertThat(delivery(late)[1]).contains("quiet hours");
    }

    @Test
    void quietHoursAreInTheLocalTimeOfThePlaceAndMayRunPastMidnight() {
        var prefs = new NotificationSettings.Settings(true, true, true, true, "21:00", "07:00");
        Map<String, Object> data = Map.of("timezone", "Africa/Addis_Ababa");
        // 23:30 Addis time (20:30 UTC) is inside; quiet hours end at 07:00 the next morning (04:00 UTC).
        Instant lateEvening = Instant.parse("2026-11-26T20:30:00Z");
        assertThat(com.beavent.backend.comms.internal.QuietHours.end(prefs, data, lateEvening))
                .isEqualTo(Instant.parse("2026-11-27T04:00:00Z"));
        // 03:00 Addis time (00:00 UTC) is inside too; they end at 07:00 the same morning.
        assertThat(com.beavent.backend.comms.internal.QuietHours.end(
                        prefs, data, Instant.parse("2026-11-27T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-27T04:00:00Z"));
        // 12:00 Addis time is outside; 07:00 itself is outside (they end at 07:00).
        assertThat(com.beavent.backend.comms.internal.QuietHours.end(
                        prefs, data, Instant.parse("2026-11-26T09:00:00Z")))
                .isNull();
        assertThat(com.beavent.backend.comms.internal.QuietHours.end(
                        prefs, data, Instant.parse("2026-11-27T04:00:00Z")))
                .isNull();
        // Daytime quiet hours (13:00 to 15:00).
        var siesta = new NotificationSettings.Settings(true, true, true, true, "13:00", "15:00");
        assertThat(com.beavent.backend.comms.internal.QuietHours.end(
                        siesta, data, Instant.parse("2026-11-26T10:30:00Z")))
                .isEqualTo(Instant.parse("2026-11-26T12:00:00Z"));
    }

    // --- review requests --------------------------------------------------------------------------------------

    @Test
    void aCompletedVisitGetsAReviewRequestUnlessItWasReviewedFirst() throws Exception {
        Shop shop = shop();
        Person eve = newPerson();
        Person fay = newPerson();
        String eveVisit = book(shop, "10:00", eve).text("id");
        String fayVisit = book(shop, "13:00", fay).text("id");
        complete(shop, eveVisit);
        complete(shop, fayVisit);

        waitFor(
                "both requests",
                () -> notification("APPOINTMENT_REVIEW_REQUEST", eveVisit) != null
                        && notification("APPOINTMENT_REVIEW_REQUEST", fayVisit) != null);
        UUID request = notification("APPOINTMENT_REVIEW_REQUEST", eveVisit);
        OffsetDateTime sendAfter = system.resultQuery(
                        "SELECT send_after FROM comms.notifications WHERE id = ?", request)
                .fetchOne(0, OffsetDateTime.class);
        assertThat(sendAfter.toInstant())
                .as("a couple of hours after the visit, not at once")
                .isBetween(
                        Instant.now().plus(110, ChronoUnit.MINUTES),
                        Instant.now().plus(130, ChronoUnit.MINUTES));
        makeDue(request);
        waitFor("the request", () -> received(eve, "how was your visit"));
        assertThat(sms.messagesTo(eve.phone()).stream()
                        .filter(m -> m.contains("how was your visit"))
                        .findFirst()
                        .orElseThrow())
                .contains("/en/account/reviews");

        // Fay reviewed it before the request was due: nothing is sent.
        Call written = call(post("/v1/me/appointments/" + fayVisit + "/review"), Map.of("rating", 5), fay.token());
        assertThat(written.status()).as(String.valueOf(written.body())).isEqualTo(201);
        UUID second = notification("APPOINTMENT_REVIEW_REQUEST", fayVisit);
        makeDue(second);
        waitFor("the second request to be handled", () -> delivery(second) != null);
        assertThat(delivery(second)[0]).isEqualTo("SUPPRESSED");
        assertThat(delivery(second)[1]).contains("already reviewed");
        assertThat(received(fay, "how was your visit")).isFalse();
    }

    @Test
    void aPersonCanTurnOffReviewRequestsToo() throws Exception {
        Shop shop = shop();
        Person gus = newPerson();
        save(gus, settings(false, false, false, "21:00", "07:00"));
        String visit = book(shop, "10:00", gus).text("id");
        complete(shop, visit);
        waitFor("the request", () -> notification("APPOINTMENT_REVIEW_REQUEST", visit) != null);
        UUID request = notification("APPOINTMENT_REVIEW_REQUEST", visit);
        makeDue(request);
        waitFor("it to be handled", () -> delivery(request) != null);
        assertThat(delivery(request)[0]).isEqualTo("SUPPRESSED");
        assertThat(received(gus, "how was your visit")).isFalse();
    }

    // --- reviews and the marketplace --------------------------------------------------------------------------

    @Test
    void theBusinessAndTheAuthorAreToldAboutReviewsAndStaffDecisions() throws Exception {
        Shop shop = shop();
        Person author = newPerson();
        Person owner = shop.salon().owner();
        String visit = book(shop, "10:00", author).text("id");
        complete(shop, visit);

        // A review arrives: the owner is told.
        Call review = call(
                post("/v1/me/appointments/" + visit + "/review"), Map.of("rating", 1, "body", "Rude."), author.token());
        assertThat(review.status()).as(String.valueOf(review.body())).isEqualTo(201);
        waitFor("the owner to hear of the review", () -> received(owner, "1-star review"));
        assertThat(sms.messagesTo(owner.phone()).stream()
                        .filter(m -> m.contains("1-star review"))
                        .findFirst()
                        .orElseThrow())
                .contains("/en/business/o/" + shop.salon().id() + "/reviews");

        // The owner reports it; staff take it down: the author and the owner both hear.
        String reviewId = review.text("id");
        assertThat(call(
                                post(shop.salon().path("/reviews/" + reviewId + "/report")),
                                Map.of("reason", "INAPPROPRIATE"),
                                owner.token())
                        .status())
                .isEqualTo(200);
        String moderator = staff("MODERATOR");
        Call hidden = call(
                post("/v1/admin/reviews/" + reviewId + "/decision"),
                Map.of("decision", "HIDE", "reason", "Insults the staff."),
                moderator);
        assertThat(hidden.status()).as(String.valueOf(hidden.body())).isEqualTo(200);
        waitFor("the author to hear", () -> received(author, "took down your review"));
        assertThat(sms.messagesTo(author.phone()).stream()
                        .filter(m -> m.contains("took down your review"))
                        .findFirst()
                        .orElseThrow())
                .contains("Insults the staff.");
        waitFor("the owner to hear the outcome", () -> received(owner, "took down the review you reported"));

        // Staff put it back: the author hears that too.
        assertThat(call(
                                post("/v1/admin/reviews/" + reviewId + "/decision"),
                                Map.of("decision", "RESTORE", "reason", "On a second look it is fine."),
                                moderator)
                        .status())
                .isEqualTo(200);
        waitFor("the author to hear it is back", () -> received(author, "is showing again"));

        // Staff list the business: the owner is told. (Asking, as the owner did, is not news.)
        assertThat(call(
                                post("/v1/admin/orgs/" + shop.salon().id() + "/marketplace-decision"),
                                Map.of("decision", "LIST", "reason", "Menu and hours look right."),
                                moderator)
                        .status())
                .isEqualTo(200);
        waitFor("the owner to hear it is listed", () -> received(owner, "now listed on Beavent"));
    }

    // --- push -------------------------------------------------------------------------------------------------

    /** A phone of this person with a push token (the installation id is the token, as the app does it). */
    String phoneApp(Person person, String token) throws Exception {
        Call registered = call(
                post("/v1/me/devices"),
                Map.of(
                        "installationId",
                        token,
                        "platform",
                        "IOS",
                        "pushToken",
                        token,
                        "appVersion",
                        "1.0.0",
                        "locale",
                        "en"),
                person.token());
        assertThat(registered.status()).as(String.valueOf(registered.body())).isEqualTo(204);
        return token;
    }

    @Test
    void aPersonWithThePhoneAppGetsPushInsteadOfTextForRemindersAndBesideItForTheRest() throws Exception {
        Shop shop = shop();
        Person ana = newPerson();
        String token = phoneApp(ana, "ExponentPushToken[" + UUID.randomUUID() + "]");
        String visit = book(shop, "10:00", ana).text("id");

        // The confirmation goes to the phone app and by text message.
        waitFor("the confirmation by text", () -> received(ana, "confirmed"));
        waitFor("the confirmation as a push", () -> !push.messagesTo(token).isEmpty());
        assertThat(push.messagesTo(token).getFirst().data()).containsEntry("route", "/appointment/" + visit);
        assertThat(push.messagesTo(token).getFirst().body()).contains("confirmed");

        // A reminder goes to the phone app only.
        UUID reminder = notificationSoon("APPOINTMENT_REMINDER_2H", visit);
        makeDue(reminder);
        waitFor(
                "the reminder as a push",
                () -> push.messagesTo(token).stream().anyMatch(m -> m.body().contains("Reminder:")));
        waitFor("the delivery", () -> delivery(reminder) != null);
        assertThat(delivery(reminder)[0]).isEqualTo("SENT");
        assertThat(received(ana, "Reminder:"))
                .as("no text message for a reminder the phone got")
                .isFalse();

        // With push turned off the same reminder comes by text message.
        Person ben = newPerson();
        String benToken = phoneApp(ben, "ExponentPushToken[" + UUID.randomUUID() + "]");
        Map<String, Object> noPush = new HashMap<>(settings(true, true, false, "21:00", "07:00"));
        noPush.put("remindersPush", false);
        save(ben, noPush);
        String benVisit = book(shop, "13:00", ben).text("id");
        UUID benReminder = notificationSoon("APPOINTMENT_REMINDER_2H", benVisit);
        makeDue(benReminder);
        waitFor("the reminder by text", () -> received(ben, "Reminder:"));
        assertThat(push.messagesTo(benToken).stream().anyMatch(m -> m.body().contains("Reminder:")))
                .isFalse();
    }

    @Test
    void aPhoneThatCannotReceiveAnyMoreIsForgottenAndAReviewRequestLeadsToTheReviews() throws Exception {
        Shop shop = shop();
        Person cy = newPerson();
        String gone = phoneApp(cy, "gone:" + UUID.randomUUID());
        String visit = book(shop, "10:00", cy).text("id");
        // The confirmation finds the phone gone: it is forgotten, the text message still goes, and later reminders
        // use the text message.
        waitFor(
                "the phone to be forgotten",
                () -> system.resultQuery("SELECT push_token FROM iam.devices WHERE installation_id = ?", gone)
                                .fetchOne(0, String.class)
                        == null);
        waitFor("the confirmation by text", () -> received(cy, "confirmed"));
        UUID confirmation = notificationSoon("APPOINTMENT_CONFIRMED", visit);
        assertThat(system.resultQuery(
                                "SELECT error FROM comms.deliveries WHERE notification_id = ? AND channel = 'PUSH'",
                                confirmation)
                        .fetchOne(0, String.class))
                .contains("cannot receive");
        UUID reminder = notificationSoon("APPOINTMENT_REMINDER_2H", visit);
        makeDue(reminder);
        waitFor("the reminder by text", () -> received(cy, "Reminder:"));

        // A working phone: the review request opens the reviews screen.
        Person dee = newPerson();
        String token = phoneApp(dee, "ExponentPushToken[" + UUID.randomUUID() + "]");
        String deeVisit = book(shop, "13:00", dee).text("id");
        complete(shop, deeVisit);
        UUID request = notificationSoon("APPOINTMENT_REVIEW_REQUEST", deeVisit);
        makeDue(request);
        waitFor(
                "the request as a push",
                () -> push.messagesTo(token).stream()
                        .anyMatch(m -> "/reviews".equals(m.data().get("route"))));
        assertThat(received(dee, "how was your visit"))
                .as("no text message when the phone got it")
                .isFalse();
    }

    @Test
    void signingOutOfAPhoneStopsPushesToIt() throws Exception {
        Shop shop = shop();
        Person eve = newPerson();
        String token = phoneApp(eve, "ExponentPushToken[" + UUID.randomUUID() + "]");
        assertThat(call(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                                        "/v1/me/devices/" + token),
                                null,
                                eve.token())
                        .status())
                .isEqualTo(204);
        String visit = book(shop, "10:00", eve).text("id");
        waitFor("the confirmation by text", () -> received(eve, "confirmed"));
        assertThat(push.messagesTo(token)).isEmpty();
        // Someone else cannot take a phone off a person.
        Person fay = newPerson();
        String fayToken = phoneApp(fay, "ExponentPushToken[" + UUID.randomUUID() + "]");
        assertThat(call(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                                        "/v1/me/devices/" + fayToken),
                                null,
                                eve.token())
                        .status())
                .isEqualTo(204);
        assertThat(system.resultQuery("SELECT push_token FROM iam.devices WHERE installation_id = ?", fayToken)
                        .fetchOne(0, String.class))
                .isEqualTo(fayToken);
        assertThat(visit).isNotBlank();
    }

    // --- the business hears about bookings --------------------------------------------------------------------

    @Test
    void theBusinessHearsAboutBookingsCustomersMakeAndCancelButNotItsOwnDesk() throws Exception {
        Shop shop = shop();
        Person owner = shop.salon().owner();
        Person reception = newMember(shop.salon(), "RECEPTIONIST");
        Person cashier = newMember(shop.salon(), "CASHIER");
        Person ana = newPerson();

        // The front desk books a walk-in: not news to the business.
        Call walkIn = call(
                post(shop.salon().path("/appointments")),
                Map.of(
                        "locationId", shop.salon().location1().toString(),
                        "start", at(day(), "09:00").toInstant().toString(),
                        "items", List.of(Map.of("variantId", shop.variant(), "staffId", shop.abebe())),
                        "customer", Map.of("name", "Selam", "phone", newPhone()),
                        "source", "WALK_IN",
                        "notifyCustomer", false),
                reception.token());
        assertThat(walkIn.status()).as(String.valueOf(walkIn.body())).isEqualTo(201);

        // A customer books online: the owner and the receptionist hear; the cashier (no right to bookings) does not.
        Call booked = book(shop, "12:00", ana);
        String reference = booked.text("reference");
        waitFor("the owner to hear", () -> received(owner, "new booking " + reference));
        waitFor("the receptionist to hear", () -> received(reception, "new booking " + reference));
        String message = sms.messagesTo(owner.phone()).stream()
                .filter(m -> m.contains("new booking " + reference))
                .findFirst()
                .orElseThrow();
        assertThat(message)
                .contains("Haircut")
                .contains("with Abebe")
                .contains("/en/business/o/" + shop.salon().id() + "/calendar");
        assertThat(received(owner, walkIn.text("reference")))
                .as("not the walk-in")
                .isFalse();
        assertThat(received(cashier, "new booking")).isFalse();

        // The customer cancels: the business hears that too.
        assertThat(call(post("/v1/me/appointments/" + booked.text("id") + "/cancel"), Map.of(), ana.token())
                        .status())
                .isEqualTo(200);
        waitFor("the owner to hear of the cancellation", () -> received(owner, "cancelled booking " + reference));
    }
}
