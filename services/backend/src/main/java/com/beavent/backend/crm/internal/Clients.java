package com.beavent.backend.crm.internal;

import static com.beavent.db.catalog.Tables.SERVICES;
import static com.beavent.db.catalog.Tables.SERVICE_VARIANTS;
import static com.beavent.db.crm.Tables.CUSTOMERS;
import static com.beavent.db.crm.Tables.CUSTOMER_NOTES;
import static com.beavent.db.crm.Tables.CUSTOMER_TAGS;
import static com.beavent.db.crm.Tables.FORM_SUBMISSIONS;
import static com.beavent.db.crm.Tables.FORM_VERSIONS;
import static com.beavent.db.crm.Tables.PATCH_TESTS;
import static com.beavent.db.crm.Tables.TAGS;
import static com.beavent.db.ref.Tables.LANGUAGES;
import static com.beavent.db.scheduling.Tables.APPOINTMENTS;
import static com.beavent.db.scheduling.Tables.APPOINTMENT_ITEMS;
import static com.beavent.db.tenant.Tables.LOCATIONS;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Cursors;
import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The business's clients (plan step 2.6, P-13): the list and search, a client's page (contact, notes with alerts and
 * allergies, tags, visits), adding and editing clients, notes and tags.
 *
 * <p>Permissions: {@code customers.view} reads, {@code customers.manage} changes, phone numbers and emails need
 * {@code customers.contact_details}. Visits are shown for the locations the member works in.
 */
@Component
public class Clients {

    public static final String VIEW = "customers.view";
    public static final String CONTACT = "customers.contact_details";
    public static final String MANAGE = "customers.manage";
    /** Notes shown as a warning on the client and on their appointments. */
    public static final List<String> ALERT_KINDS = List.of("ALERT", "ALLERGY", "MEDICAL");

    static final Set<String> NOTE_KINDS = Set.of("GENERAL", "ALERT", "ALLERGY", "MEDICAL", "PREFERENCE");
    static final int SEARCH_LIMIT = 20;
    static final int PAST_LIMIT = 50;
    static final int MAX_TAGS = 20;
    private static final Set<String> UPCOMING = Set.of("REQUESTED", "CONFIRMED", "ARRIVED", "IN_PROGRESS");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final Field<String> NAME_KEY = DSL.lower(CUSTOMERS.DISPLAY_NAME);
    private static final Field<Integer> VISITS = DSL.field(DSL.selectCount()
                    .from(APPOINTMENTS)
                    .where(APPOINTMENTS.CUSTOMER_ID.eq(CUSTOMERS.ID), APPOINTMENTS.STATUS.eq("COMPLETED")))
            .as("visits");
    private static final Field<String[]> TAG_NAMES = DSL.field(
                    DSL.select(DSL.arrayAgg(TAGS.NAME).orderBy(TAGS.NAME))
                            .from(CUSTOMER_TAGS)
                            .join(TAGS)
                            .on(TAGS.ID.eq(CUSTOMER_TAGS.TAG_ID))
                            .where(CUSTOMER_TAGS.CUSTOMER_ID.eq(CUSTOMERS.ID)))
            .as("tag_names");
    private static final Field<Integer> ALERTS = DSL.field(DSL.selectCount()
                    .from(CUSTOMER_NOTES)
                    .where(CUSTOMER_NOTES.CUSTOMER_ID.eq(CUSTOMERS.ID), CUSTOMER_NOTES.KIND.in(ALERT_KINDS)))
            .as("alerts");

    private static final tools.jackson.core.type.TypeReference<Map<String, String>> TEXT =
            new tools.jackson.core.type.TypeReference<>() {};

    private final DSLContext dsl;
    private final TransactionTemplate transactions;
    private final tools.jackson.databind.json.JsonMapper json;

    Clients(DSLContext dsl, TransactionTemplate transactions, tools.jackson.databind.json.JsonMapper json) {
        this.dsl = dsl;
        this.transactions = transactions;
        this.json = json;
    }

    // --- the list ------------------------------------------------------------------------------------------------

    /**
     * Without {@code query}: every active client by name, a page at a time. With a query (name, or at least four
     * digits of the phone number): the best {@value #SEARCH_LIMIT} matches, most recent visitors first.
     */
    public ClientBodies.Page list(OrgAccess access, String query, UUID tagId, String cursor, Integer limit) {
        access.require(VIEW);
        boolean contact = access.can(CONTACT);
        String q = query == null ? "" : query.strip();
        int size = limit == null ? 50 : Math.max(1, Math.min(limit, 100));
        return transactions.execute(status -> {
            Condition where = CUSTOMERS.STATUS.eq("ACTIVE");
            if (tagId != null) {
                where = where.and(DSL.exists(DSL.selectOne()
                        .from(CUSTOMER_TAGS)
                        .where(CUSTOMER_TAGS.CUSTOMER_ID.eq(CUSTOMERS.ID), CUSTOMER_TAGS.TAG_ID.eq(tagId))));
            }
            var select = dsl.select(
                            CUSTOMERS.ID,
                            CUSTOMERS.DISPLAY_NAME,
                            CUSTOMERS.PHONE_E164,
                            CUSTOMERS.LAST_VISIT_AT,
                            VISITS,
                            TAG_NAMES,
                            ALERTS,
                            NAME_KEY)
                    .from(CUSTOMERS);
            if (!q.isEmpty()) {
                if (q.length() < 2) {
                    return new ClientBodies.Page(List.of(), null);
                }
                String digits = q.replaceAll("[^0-9]", "");
                Condition match = CUSTOMERS.DISPLAY_NAME.containsIgnoreCase(q);
                if (digits.length() >= 4) {
                    match = match.or(CUSTOMERS.PHONE_E164.contains(digits.replaceFirst("^0", "")));
                }
                List<ClientBodies.Summary> items = select.where(where.and(match))
                        .orderBy(CUSTOMERS.LAST_VISIT_AT.desc().nullsLast(), NAME_KEY, CUSTOMERS.ID)
                        .limit(SEARCH_LIMIT)
                        .fetch(r -> summary(r, contact));
                return new ClientBodies.Page(items, null);
            }
            if (cursor != null) {
                List<String> after = Cursors.decode(cursor, 2);
                where = where.and(DSL.row(NAME_KEY, CUSTOMERS.ID).gt(after.get(0), UUID.fromString(after.get(1))));
            }
            var rows = select.where(where)
                    .orderBy(NAME_KEY, CUSTOMERS.ID)
                    .limit(size + 1)
                    .fetch();
            boolean more = rows.size() > size;
            var page = more ? rows.subList(0, size) : rows;
            List<ClientBodies.Summary> items = new ArrayList<>();
            page.forEach(r -> items.add(summary(r, contact)));
            String next = more
                    ? Cursors.encode(
                            page.getLast().get(NAME_KEY),
                            page.getLast().get(CUSTOMERS.ID).toString())
                    : null;
            return new ClientBodies.Page(items, next);
        });
    }

    private static ClientBodies.Summary summary(org.jooq.Record r, boolean contact) {
        OffsetDateTime last = r.get(CUSTOMERS.LAST_VISIT_AT);
        String[] tags = r.get(TAG_NAMES);
        return new ClientBodies.Summary(
                r.get(CUSTOMERS.ID),
                r.get(CUSTOMERS.DISPLAY_NAME),
                contact ? r.get(CUSTOMERS.PHONE_E164) : null,
                last == null ? null : last.toInstant(),
                r.get(VISITS),
                tags == null ? List.of() : Arrays.asList(tags),
                r.get(ALERTS));
    }

    // --- one client ----------------------------------------------------------------------------------------------

    /** The client's page; a merged record answers with the client it was merged into. */
    public ClientBodies.Client get(OrgAccess access, UUID customerId, Instant now) {
        access.require(VIEW);
        return transactions.execute(status -> read(access, resolve(customerId), now));
    }

    /** Follows merges to the record that is kept. */
    UUID resolve(UUID customerId) {
        UUID id = customerId;
        for (int hop = 0; hop < 10; hop++) {
            var row = dsl.select(CUSTOMERS.STATUS, CUSTOMERS.MERGED_INTO_ID)
                    .from(CUSTOMERS)
                    .where(CUSTOMERS.ID.eq(id))
                    .fetchOne();
            if (row == null || row.value1().equals("ERASED")) {
                throw new ApiException(ProblemCode.NOT_FOUND, "There is no such client.");
            }
            if (row.value1().equals("ACTIVE")) {
                return id;
            }
            id = row.value2();
        }
        throw new ApiException(ProblemCode.NOT_FOUND, "There is no such client.");
    }

    ClientBodies.Client read(OrgAccess access, UUID id, Instant now) {
        boolean contact = access.can(CONTACT);
        var c = dsl.selectFrom(CUSTOMERS).where(CUSTOMERS.ID.eq(id)).fetchOne();
        if (c == null) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such client.");
        }
        List<ClientBodies.Note> notes = dsl.selectFrom(CUSTOMER_NOTES)
                .where(CUSTOMER_NOTES.CUSTOMER_ID.eq(id), visibleNotes(access))
                .orderBy(CUSTOMER_NOTES.IS_PINNED.desc(), CUSTOMER_NOTES.CREATED_AT.desc())
                .fetch(n -> new ClientBodies.Note(
                        n.getId(),
                        n.getKind(),
                        n.getBody(),
                        n.getIsPinned(),
                        access.userId().equals(n.getAuthorId()),
                        n.getCreatedAt().toInstant()));
        List<String> tags = dsl.select(TAGS.NAME)
                .from(CUSTOMER_TAGS)
                .join(TAGS)
                .on(TAGS.ID.eq(CUSTOMER_TAGS.TAG_ID))
                .where(CUSTOMER_TAGS.CUSTOMER_ID.eq(id))
                .orderBy(TAGS.NAME)
                .fetch(TAGS.NAME);

        var appointments = dsl.select(
                        APPOINTMENTS.ID,
                        APPOINTMENTS.REFERENCE,
                        APPOINTMENTS.STATUS,
                        APPOINTMENTS.STARTS_AT,
                        APPOINTMENTS.ENDS_AT,
                        APPOINTMENTS.LOCATION_ID,
                        LOCATIONS.NAME,
                        APPOINTMENTS.CANCELLED_BY,
                        APPOINTMENTS.TIMEZONE)
                .from(APPOINTMENTS)
                .join(LOCATIONS)
                .on(LOCATIONS.ID.eq(APPOINTMENTS.LOCATION_ID))
                .where(APPOINTMENTS.CUSTOMER_ID.eq(id), APPOINTMENTS.STATUS.ne("PENDING_PAYMENT"))
                .orderBy(APPOINTMENTS.STARTS_AT.desc())
                .fetch();
        int visits = 0;
        int noShows = 0;
        int cancellations = 0;
        for (var a : appointments) {
            switch (a.value3()) {
                case "COMPLETED" -> visits++;
                case "NO_SHOW" -> noShows++;
                case "CANCELLED" -> cancellations += "CUSTOMER".equals(a.value8()) ? 1 : 0;
                default -> {}
            }
        }
        var shown = appointments.stream()
                .filter(a -> access.canAccessLocation(a.value6()))
                .toList();
        List<UUID> ids = shown.stream().map(a -> a.value1()).toList();
        Map<UUID, List<ClientBodies.VisitItem>> items = visitItems(ids);
        List<ClientBodies.Visit> upcoming = new ArrayList<>();
        List<ClientBodies.Visit> past = new ArrayList<>();
        for (var a : shown) {
            var visit = new ClientBodies.Visit(
                    a.value1(),
                    a.value2(),
                    a.value3(),
                    a.value4().toInstant(),
                    a.value5().toInstant(),
                    a.value9(),
                    a.value6(),
                    a.value7(),
                    items.getOrDefault(a.value1(), List.of()));
            if (UPCOMING.contains(a.value3()) && a.value5().toInstant().isAfter(now)) {
                upcoming.addFirst(visit);
            } else if (past.size() < PAST_LIMIT) {
                past.add(visit);
            }
        }
        return new ClientBodies.Client(
                c.getId(),
                c.getDisplayName(),
                contact ? c.getPhoneE164() : null,
                contact ? c.getEmail() : null,
                c.getPreferredLanguage(),
                c.getSource(),
                c.getFirstSeenAt().toInstant(),
                c.getLastVisitAt() == null ? null : c.getLastVisitAt().toInstant(),
                c.getIsBlocked(),
                c.getBlockReason(),
                c.getUserId() != null,
                tags,
                notes,
                new ClientBodies.Stats(visits, noShows, cancellations),
                upcoming,
                past,
                answered(id),
                patchTests(id),
                new ClientBodies.Abilities(access.can(MANAGE), contact));
    }

    /** Forms the client answered (the current answers, newest first). */
    private List<ClientBodies.Answered> answered(UUID customerId) {
        return dsl.select(
                        FORM_SUBMISSIONS.ID,
                        FORM_VERSIONS.FORM_ID,
                        FORM_VERSIONS.SCHEMA,
                        FORM_SUBMISSIONS.SUBMITTED_AT,
                        FORM_SUBMISSIONS.APPOINTMENT_ID)
                .from(FORM_SUBMISSIONS)
                .join(FORM_VERSIONS)
                .on(FORM_VERSIONS.ID.eq(FORM_SUBMISSIONS.FORM_VERSION_ID))
                .where(FORM_SUBMISSIONS.CUSTOMER_ID.eq(customerId), FORM_SUBMISSIONS.STATUS.in("SUBMITTED", "REVIEWED"))
                .orderBy(FORM_SUBMISSIONS.SUBMITTED_AT.desc())
                .limit(50)
                .fetch(r -> new ClientBodies.Answered(
                        r.value1(),
                        r.value2(),
                        json.readValue(r.value3().data(), FormModel.Schema.class)
                                .title(),
                        r.value4().toInstant(),
                        r.value5()));
    }

    private List<ClientBodies.PatchTest> patchTests(UUID customerId) {
        return dsl.select(
                        PATCH_TESTS.ID,
                        PATCH_TESTS.SERVICE_ID,
                        SERVICES.NAME_I18N,
                        PATCH_TESTS.PERFORMED_AT,
                        PATCH_TESTS.RESULT,
                        PATCH_TESTS.VALID_UNTIL,
                        PATCH_TESTS.NOTES)
                .from(PATCH_TESTS)
                .join(SERVICES)
                .on(SERVICES.ID.eq(PATCH_TESTS.SERVICE_ID))
                .where(PATCH_TESTS.CUSTOMER_ID.eq(customerId))
                .orderBy(PATCH_TESTS.PERFORMED_AT.desc())
                .fetch(r -> new ClientBodies.PatchTest(
                        r.value1(),
                        r.value2(),
                        text(r.value3()),
                        r.value4().toInstant(),
                        r.value5(),
                        r.value6() == null ? null : r.value6().toInstant(),
                        r.value7()));
    }

    /** One set of the client's answers, with the questions of the version they answered. */
    public ClientBodies.Submission submission(OrgAccess access, UUID customerId, UUID submissionId) {
        access.require(VIEW);
        return transactions.execute(status -> {
            var r = dsl.select(
                            FORM_SUBMISSIONS.ID,
                            FORM_VERSIONS.FORM_ID,
                            FORM_VERSIONS.SCHEMA,
                            FORM_SUBMISSIONS.SUBMITTED_AT,
                            FORM_SUBMISSIONS.APPOINTMENT_ID,
                            FORM_SUBMISSIONS.ANSWERS,
                            com.beavent.db.crm.Tables.FORMS.PURPOSE)
                    .from(FORM_SUBMISSIONS)
                    .join(FORM_VERSIONS)
                    .on(FORM_VERSIONS.ID.eq(FORM_SUBMISSIONS.FORM_VERSION_ID))
                    .join(com.beavent.db.crm.Tables.FORMS)
                    .on(com.beavent.db.crm.Tables.FORMS.ID.eq(FORM_VERSIONS.FORM_ID))
                    .where(FORM_SUBMISSIONS.ID.eq(submissionId), FORM_SUBMISSIONS.CUSTOMER_ID.eq(resolve(customerId)))
                    .fetchOne();
            if (r == null) {
                throw new ApiException(ProblemCode.NOT_FOUND, "There are no such answers.");
            }
            FormModel.Schema schema = json.readValue(r.value3().data(), FormModel.Schema.class);
            JsonNode stored = json.readTree(r.value6().data());
            Map<String, Object> values = json.convertValue(
                    stored.get("values"), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
            JsonNode signature = stored.get("signature");
            return new ClientBodies.Submission(
                    r.value1(),
                    r.value2(),
                    schema.title(),
                    r.value7(),
                    r.value4().toInstant(),
                    r.value5(),
                    schema.fields(),
                    values == null ? Map.of() : values,
                    signature == null
                            ? null
                            : new ClientBodies.Signature(
                                    signature.get("name").asString(),
                                    Instant.parse(signature.get("signedAt").asString())));
        });
    }

    /** Team notes for everyone; manager-only notes for those who manage clients; private notes for their author. */
    static Condition visibleNotes(OrgAccess access) {
        Condition visible = CUSTOMER_NOTES.VISIBILITY.eq("TEAM");
        if (access.can(MANAGE)) {
            visible = visible.or(CUSTOMER_NOTES.VISIBILITY.eq("MANAGERS"));
        }
        return visible.or(CUSTOMER_NOTES.VISIBILITY.eq("AUTHOR").and(CUSTOMER_NOTES.AUTHOR_ID.eq(access.userId())));
    }

    private Map<UUID, List<ClientBodies.VisitItem>> visitItems(List<UUID> appointmentIds) {
        Map<UUID, List<ClientBodies.VisitItem>> out = new HashMap<>();
        if (appointmentIds.isEmpty()) {
            return out;
        }
        Map<UUID, Map<String, Map<String, String>>> names = new HashMap<>();
        dsl.select(SERVICE_VARIANTS.ID, SERVICES.NAME_I18N, SERVICE_VARIANTS.NAME_I18N)
                .from(SERVICE_VARIANTS)
                .join(SERVICES)
                .on(SERVICES.ID.eq(SERVICE_VARIANTS.SERVICE_ID))
                .where(SERVICE_VARIANTS.ID.in(DSL.select(APPOINTMENT_ITEMS.SERVICE_VARIANT_ID)
                        .from(APPOINTMENT_ITEMS)
                        .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.in(appointmentIds))))
                .forEach(r -> names.put(r.value1(), Map.of("service", text(r.value2()), "variant", text(r.value3()))));
        dsl.selectFrom(APPOINTMENT_ITEMS)
                .where(APPOINTMENT_ITEMS.APPOINTMENT_ID.in(appointmentIds))
                .orderBy(APPOINTMENT_ITEMS.APPOINTMENT_ID, APPOINTMENT_ITEMS.SEQ)
                .forEach(i -> out.computeIfAbsent(i.getAppointmentId(), k -> new ArrayList<>())
                        .add(new ClientBodies.VisitItem(
                                i.getServiceName(),
                                i.getVariantName(),
                                names.getOrDefault(i.getServiceVariantId(), Map.of()),
                                i.getStaffName())));
        return out;
    }

    private Map<String, String> text(org.jooq.JSONB value) {
        return value == null ? Map.of() : json.readValue(value.data(), TEXT);
    }

    // --- adding and editing --------------------------------------------------------------------------------------

    /** Adds a client by hand; refused when an active client already has the phone number. */
    public UUID create(OrgAccess access, ClientBodies.NewClient input) {
        access.require(MANAGE);
        String name = name(input.name());
        String phone = phone(input.phone());
        String email = email(input.email());
        if ((phone != null || email != null) && !access.can(CONTACT)) {
            throw new ApiException(ProblemCode.FORBIDDEN, "Your role does not show contact details.");
        }
        return transactions.execute(status -> {
            if (phone != null
                    && dsl.fetchExists(CUSTOMERS, CUSTOMERS.PHONE_E164.eq(phone).and(CUSTOMERS.STATUS.eq("ACTIVE")))) {
                throw new ApiException(ProblemCode.CONFLICT, "A client with this phone number already exists.");
            }
            String language = language(input.preferredLanguage());
            UUID id = Ids.newId();
            dsl.insertInto(CUSTOMERS)
                    .set(CUSTOMERS.ID, id)
                    .set(CUSTOMERS.ORG_ID, access.orgId())
                    .set(CUSTOMERS.DISPLAY_NAME, name)
                    .set(CUSTOMERS.PHONE_E164, phone)
                    .set(CUSTOMERS.EMAIL, email)
                    .set(CUSTOMERS.PREFERRED_LANGUAGE, language)
                    .set(CUSTOMERS.SOURCE, "MANUAL")
                    .execute();
            if (input.note() != null && !input.note().isBlank()) {
                insertNote(access, id, "GENERAL", input.note(), false);
            }
            return id;
        });
    }

    /** JSON merge-patch: name, phone, email, preferredLanguage, blocked, blockReason. */
    public ClientBodies.Client update(OrgAccess access, UUID customerId, JsonNode patch, Instant now) {
        access.require(MANAGE);
        if (patch == null || !patch.isObject() || patch.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Send at least one field to change.");
        }
        if ((patch.has("phone") || patch.has("email")) && !access.can(CONTACT)) {
            throw new ApiException(ProblemCode.FORBIDDEN, "Your role does not show contact details.");
        }
        return transactions.execute(status -> {
            UUID id = active(customerId);
            var update = dsl.updateQuery(CUSTOMERS);
            update.addConditions(CUSTOMERS.ID.eq(id));
            if (patch.has("name")) {
                update.addValue(CUSTOMERS.DISPLAY_NAME, name(text(patch, "name")));
            }
            if (patch.has("phone")) {
                String phone = phone(text(patch, "phone"));
                if (phone != null
                        && dsl.fetchExists(
                                CUSTOMERS,
                                CUSTOMERS
                                        .PHONE_E164
                                        .eq(phone)
                                        .and(CUSTOMERS.STATUS.eq("ACTIVE"))
                                        .and(CUSTOMERS.ID.ne(id)))) {
                    throw new ApiException(
                            ProblemCode.CONFLICT, "Another client has this phone number. Merge them instead.");
                }
                update.addValue(CUSTOMERS.PHONE_E164, phone);
            }
            if (patch.has("email")) {
                update.addValue(CUSTOMERS.EMAIL, email(text(patch, "email")));
            }
            if (patch.has("preferredLanguage")) {
                update.addValue(CUSTOMERS.PREFERRED_LANGUAGE, language(text(patch, "preferredLanguage")));
            }
            if (patch.has("blocked")) {
                JsonNode blocked = patch.get("blocked");
                if (!blocked.isBoolean()) {
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "blocked is true or false.");
                }
                update.addValue(CUSTOMERS.IS_BLOCKED, blocked.asBoolean());
                if (!blocked.asBoolean()) {
                    update.addValue(CUSTOMERS.BLOCK_REASON, (String) null);
                }
            }
            if (patch.has("blockReason")) {
                String reason = text(patch, "blockReason");
                if (reason != null && reason.length() > 500) {
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "Keep the reason under 500 characters.");
                }
                update.addValue(CUSTOMERS.BLOCK_REASON, reason == null || reason.isBlank() ? null : reason.strip());
            }
            update.addValue(CUSTOMERS.UPDATED_AT, OffsetDateTime.now());
            update.execute();
            return read(access, id, now);
        });
    }

    private static String text(JsonNode patch, String field) {
        JsonNode value = patch.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, field + " must be text.");
        }
        return value.asString();
    }

    /** An active client of this business (row-level security scopes the lookup). */
    UUID active(UUID customerId) {
        String status = dsl.select(CUSTOMERS.STATUS)
                .from(CUSTOMERS)
                .where(CUSTOMERS.ID.eq(customerId))
                .forUpdate()
                .fetchOne(CUSTOMERS.STATUS);
        if (status == null || status.equals("ERASED")) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such client.");
        }
        if (status.equals("MERGED")) {
            throw new ApiException(ProblemCode.IMMUTABLE, "This client was merged into another.");
        }
        return customerId;
    }

    static String name(String typed) {
        if (typed == null || typed.isBlank()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Enter the client's name.");
        }
        String name = typed.strip().replaceAll("\\s+", " ");
        if (name.length() > 120) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Keep the name under 120 characters.");
        }
        return name;
    }

    static String phone(String typed) {
        try {
            return Phones.normalize(typed);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Enter a phone number such as 0911 234 567.");
        }
    }

    static String email(String typed) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        String email = typed.strip();
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Enter an email address such as name@example.com.");
        }
        return email;
    }

    private String language(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        if (!dsl.fetchExists(LANGUAGES, LANGUAGES.CODE.eq(code))) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Unknown language.");
        }
        return code;
    }

    // --- notes ---------------------------------------------------------------------------------------------------

    public ClientBodies.Note addNote(OrgAccess access, UUID customerId, ClientBodies.NewNote input) {
        access.require(MANAGE);
        String kind = input.kind() == null ? "GENERAL" : input.kind();
        if (!NOTE_KINDS.contains(kind)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Unknown kind of note.");
        }
        if (input.body() == null || input.body().isBlank() || input.body().length() > 2000) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Write a note of up to 2,000 characters.");
        }
        return transactions.execute(status -> {
            UUID id = active(customerId);
            return insertNote(access, id, kind, input.body(), Boolean.TRUE.equals(input.pinned()));
        });
    }

    private ClientBodies.Note insertNote(OrgAccess access, UUID customerId, String kind, String body, boolean pinned) {
        var note = dsl.insertInto(CUSTOMER_NOTES)
                .set(CUSTOMER_NOTES.ID, Ids.newId())
                .set(CUSTOMER_NOTES.ORG_ID, access.orgId())
                .set(CUSTOMER_NOTES.CUSTOMER_ID, customerId)
                .set(CUSTOMER_NOTES.AUTHOR_ID, access.userId())
                .set(CUSTOMER_NOTES.KIND, kind)
                .set(CUSTOMER_NOTES.BODY, body.strip())
                .set(CUSTOMER_NOTES.IS_PINNED, pinned)
                .returning()
                .fetchSingle();
        return new ClientBodies.Note(
                note.getId(),
                kind,
                note.getBody(),
                pinned,
                true,
                note.getCreatedAt().toInstant());
    }

    public void deleteNote(OrgAccess access, UUID customerId, UUID noteId) {
        access.require(MANAGE);
        int deleted = transactions.execute(status -> dsl.deleteFrom(CUSTOMER_NOTES)
                .where(CUSTOMER_NOTES.ID.eq(noteId), CUSTOMER_NOTES.CUSTOMER_ID.eq(customerId), visibleNotes(access))
                .execute());
        if (deleted == 0) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such note.");
        }
    }

    // --- tags ----------------------------------------------------------------------------------------------------

    /** The business's tags with how many active clients carry each. */
    public ClientBodies.Tags tags(OrgAccess access) {
        access.require(VIEW);
        Field<Integer> count = DSL.field(DSL.selectCount()
                .from(CUSTOMER_TAGS)
                .join(CUSTOMERS)
                .on(CUSTOMERS.ID.eq(CUSTOMER_TAGS.CUSTOMER_ID))
                .where(CUSTOMER_TAGS.TAG_ID.eq(TAGS.ID), CUSTOMERS.STATUS.eq("ACTIVE")));
        return new ClientBodies.Tags(transactions.execute(status -> dsl.select(TAGS.ID, TAGS.NAME, count)
                .from(TAGS)
                .orderBy(DSL.lower(TAGS.NAME))
                .fetch(r -> new ClientBodies.Tag(r.value1(), r.value2(), r.value3()))));
    }

    /** Replaces the client's tags; new tag names become tags of the business. */
    public List<String> setTags(OrgAccess access, UUID customerId, List<String> names) {
        access.require(MANAGE);
        Map<String, String> wanted = new LinkedHashMap<>();
        for (String typed : names == null ? List.<String>of() : names) {
            if (typed == null || typed.isBlank()) {
                continue;
            }
            String tag = typed.strip().replaceAll("\\s+", " ");
            if (tag.length() > 40) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Keep tags under 40 characters.");
            }
            wanted.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag);
        }
        if (wanted.size() > MAX_TAGS) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "A client can have up to " + MAX_TAGS + " tags.");
        }
        return transactions.execute(status -> {
            UUID id = active(customerId);
            List<UUID> tagIds = new ArrayList<>();
            for (String tag : wanted.values()) {
                // An existing tag with the same name in another case is reused.
                UUID existing = dsl.select(TAGS.ID)
                        .from(TAGS)
                        .where(DSL.lower(TAGS.NAME).eq(tag.toLowerCase(Locale.ROOT)))
                        .limit(1)
                        .fetchOne(TAGS.ID);
                if (existing == null) {
                    existing = Ids.newId();
                    dsl.insertInto(TAGS)
                            .set(TAGS.ID, existing)
                            .set(TAGS.ORG_ID, access.orgId())
                            .set(TAGS.NAME, tag)
                            .execute();
                }
                tagIds.add(existing);
            }
            dsl.deleteFrom(CUSTOMER_TAGS)
                    .where(CUSTOMER_TAGS.CUSTOMER_ID.eq(id), CUSTOMER_TAGS.TAG_ID.notIn(tagIds))
                    .execute();
            for (UUID tagId : tagIds) {
                dsl.insertInto(CUSTOMER_TAGS)
                        .set(CUSTOMER_TAGS.ORG_ID, access.orgId())
                        .set(CUSTOMER_TAGS.CUSTOMER_ID, id)
                        .set(CUSTOMER_TAGS.TAG_ID, tagId)
                        .onConflictDoNothing()
                        .execute();
            }
            return dsl.select(TAGS.NAME)
                    .from(CUSTOMER_TAGS)
                    .join(TAGS)
                    .on(TAGS.ID.eq(CUSTOMER_TAGS.TAG_ID))
                    .where(CUSTOMER_TAGS.CUSTOMER_ID.eq(id))
                    .orderBy(TAGS.NAME)
                    .fetch(TAGS.NAME);
        });
    }
}
