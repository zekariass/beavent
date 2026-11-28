package com.beavent.backend.marketing.internal;

import com.beavent.backend.marketing.Marketing;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.RateLimiter;
import com.beavent.backend.platform.SmsGateway;
import com.beavent.backend.platform.SystemDb;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
class MarketingService implements Marketing {

    private static final TypeReference<Map<String, String>> TEXTS = new TypeReference<>() {};
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int LINK_CODE_LENGTH = 8;
    private static final int DEFAULT_PAST_DAYS = 365;
    private static final int DEFAULT_LAPSED_DAYS = 90;

    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final Audit audit;
    private final SmsGateway sms;
    private final RateLimiter rateLimiter;
    private final String webUrl;

    MarketingService(
            @SystemDb DSLContext system,
            @SystemDb TransactionTemplate transactions,
            JsonMapper json,
            Audit audit,
            SmsGateway sms,
            RateLimiter rateLimiter,
            @Value("${beavent.web.public-url}") String webUrl) {
        this.system = system;
        this.transactions = transactions;
        this.json = json;
        this.audit = audit;
        this.sms = sms;
        this.rateLimiter = rateLimiter;
        this.webUrl = webUrl.replaceAll("/+$", "");
    }

    // --- shared -----------------------------------------------------------------------------------------------

    private static String token(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    static String hash(String phone) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(phone.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant instant(Record r, String field) {
        OffsetDateTime at = r.get(field, OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }

    // --- audiences --------------------------------------------------------------------------------------------

    /** One person a campaign can reach. */
    private record Person(
            UUID userId, UUID customerId, String phone, String firstName, String language, boolean suppressed) {}

    private List<Person> people(UUID orgId, Audience audience, Integer days, Instant now) {
        List<Record> rows;
        if (audience == Audience.FOLLOWERS) {
            rows = system.resultQuery("""
                            SELECT f.user_id, NULL::uuid AS customer_id, u.phone_e164 AS phone, p.first_name,
                                   u.preferred_language AS language, NULL::timestamptz AS last_visit,
                                   EXISTS (SELECT 1 FROM comms.suppressions s
                                            WHERE s.channel = 'SMS' AND (s.org_id = ? OR s.org_id IS NULL)
                                              AND s.destination_hash = encode(sha256(convert_to(u.phone_e164, 'UTF8')), 'hex')) AS suppressed
                              FROM discovery.follows f
                              JOIN iam.users u ON u.id = f.user_id AND u.status = 'ACTIVE'
                                              AND u.phone_verified_at IS NOT NULL AND u.phone_e164 IS NOT NULL
                              LEFT JOIN iam.user_profiles p ON p.user_id = u.id
                             WHERE f.org_id = ? AND f.notify
                            """, orgId, orgId).fetch();
        } else {
            rows = system.resultQuery("""
                            SELECT c.user_id, c.id AS customer_id, c.phone_e164 AS phone, c.first_name,
                                   c.preferred_language AS language,
                                   (SELECT max(a.ends_at) FROM scheduling.appointments a
                                     WHERE a.org_id = c.org_id AND a.customer_id = c.id AND a.status = 'COMPLETED') AS last_visit,
                                   EXISTS (SELECT 1 FROM comms.suppressions s
                                            WHERE s.channel = 'SMS' AND (s.org_id = ? OR s.org_id IS NULL)
                                              AND s.destination_hash = encode(sha256(convert_to(c.phone_e164, 'UTF8')), 'hex')) AS suppressed
                              FROM crm.customers c
                             WHERE c.org_id = ? AND c.status = 'ACTIVE' AND NOT c.is_blocked AND c.phone_e164 IS NOT NULL
                               AND coalesce((SELECT k.granted FROM crm.customer_consents k
                                              WHERE k.org_id = c.org_id AND k.customer_id = c.id AND k.purpose = 'MARKETING_SMS'
                                              ORDER BY k.captured_at DESC, k.id DESC LIMIT 1), false)
                            """, orgId, orgId).fetch();
        }
        int window =
                days != null ? days : audience == Audience.LAPSED_CLIENTS ? DEFAULT_LAPSED_DAYS : DEFAULT_PAST_DAYS;
        Instant since = now.minus(Duration.ofDays(window));
        Set<String> seen = new HashSet<>();
        List<Person> out = new ArrayList<>();
        for (Record r : rows) {
            Instant last = instant(r, "last_visit");
            if (audience == Audience.PAST_CLIENTS && (last == null || last.isBefore(since))) continue;
            if (audience == Audience.LAPSED_CLIENTS && (last == null || !last.isBefore(since))) continue;
            String phone = r.get("phone", String.class);
            if (!seen.add(phone)) continue;
            out.add(new Person(
                    r.get("user_id", UUID.class),
                    r.get("customer_id", UUID.class),
                    phone,
                    r.get("first_name", String.class),
                    r.get("language", String.class),
                    r.get("suppressed", Boolean.class)));
        }
        return out;
    }

    @Override
    public AudienceCount audience(UUID orgId, Audience audience, Integer days) {
        List<Person> all = people(orgId, audience, days, Instant.now());
        int suppressed = (int) all.stream().filter(Person::suppressed).count();
        return new AudienceCount(audience, all.size() - suppressed, suppressed);
    }

    // --- campaigns --------------------------------------------------------------------------------------------

    private static final String CAMPAIGN = """
            SELECT id, name, audience, days, location_id, content, status, scheduled_at, sent_at, created_at
              FROM marketing.campaigns
            """;

    private Stats stats(UUID campaignId) {
        Record r = system.resultQuery("""
                        SELECT count(*) AS n,
                               count(*) FILTER (WHERE status IN ('SENT','DELIVERED')) AS sent,
                               count(*) FILTER (WHERE status = 'FAILED') AS failed,
                               count(*) FILTER (WHERE status IN ('SUPPRESSED_NO_CONSENT','SUPPRESSED_OPTED_OUT')) AS suppressed,
                               count(*) FILTER (WHERE clicked_at IS NOT NULL) AS clicks
                          FROM marketing.campaign_recipients WHERE campaign_id = ?
                        """, campaignId).fetchSingle();
        return new Stats(
                r.get("n", Integer.class),
                r.get("sent", Integer.class),
                r.get("failed", Integer.class),
                r.get("suppressed", Integer.class),
                r.get("clicks", Integer.class));
    }

    private Campaign campaign(Record r) {
        UUID id = r.get("id", UUID.class);
        return new Campaign(
                id,
                r.get("name", String.class),
                Audience.valueOf(r.get("audience", String.class)),
                r.get("days", Integer.class),
                r.get("location_id", UUID.class),
                json.readValue(r.get("content", JSONB.class).data(), TEXTS),
                r.get("status", String.class),
                instant(r, "scheduled_at"),
                instant(r, "sent_at"),
                stats(id),
                instant(r, "created_at"));
    }

    @Override
    public List<Campaign> campaigns(UUID orgId) {
        List<Campaign> out = new ArrayList<>();
        for (Record r : system.resultQuery(
                        CAMPAIGN + " WHERE org_id = ? ORDER BY created_at DESC, id DESC LIMIT 100", orgId)
                .fetch()) {
            out.add(campaign(r));
        }
        return out;
    }

    @Override
    public Optional<Campaign> campaign(UUID orgId, UUID campaignId) {
        Record r = system.resultQuery(CAMPAIGN + " WHERE org_id = ? AND id = ?", orgId, campaignId)
                .fetchOne();
        return r == null ? Optional.empty() : Optional.of(campaign(r));
    }

    private Campaign require(UUID orgId, UUID campaignId) {
        return campaign(orgId, campaignId)
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "There is no such campaign."));
    }

    private Map<String, String> checked(Draft draft) {
        if (draft.name() == null || draft.name().isBlank() || draft.name().length() > 80) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Give the campaign a name of up to 80 characters.");
        }
        Map<String, String> texts = new LinkedHashMap<>();
        for (String lang : List.of("am", "en")) {
            String text = draft.texts() == null ? null : draft.texts().get(lang);
            if (text != null && !text.isBlank()) {
                if (text.strip().codePointCount(0, text.strip().length()) > MAX_TEXT) {
                    throw new ApiException(
                            ProblemCode.VALIDATION_FAILED, "Keep each text under " + MAX_TEXT + " characters.");
                }
                texts.put(lang, text.strip());
            }
        }
        if (texts.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Write the message in Amharic, English, or both.");
        }
        if (draft.audience() == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose who to send it to.");
        }
        if (draft.days() != null && (draft.days() < 1 || draft.days() > 1095)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose a number of days from 1 to 1095.");
        }
        return texts;
    }

    private void checkPlace(UUID orgId, UUID locationId) {
        if (locationId != null
                && !system.resultQuery(
                                "SELECT EXISTS (SELECT 1 FROM tenant.locations WHERE org_id = ? AND id = ?)",
                                orgId,
                                locationId)
                        .fetchOne(0, Boolean.class)) {
            throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "That place is not one of the business's.");
        }
    }

    @Override
    public Campaign create(UUID orgId, UUID userId, Draft draft) {
        Map<String, String> texts = checked(draft);
        checkPlace(orgId, draft.locationId());
        UUID id = Ids.newId();
        system.execute(
                """
                INSERT INTO marketing.campaigns (id, org_id, name, channel, audience, content, location_id, days, created_by)
                VALUES (?, ?, ?, 'SMS', ?, ?::jsonb, ?, ?, ?)
                """,
                id,
                orgId,
                draft.name().strip(),
                draft.audience().name(),
                json.writeValueAsString(texts),
                draft.locationId(),
                draft.days(),
                userId);
        return require(orgId, id);
    }

    @Override
    public Campaign update(UUID orgId, UUID campaignId, Draft draft) {
        Campaign current = require(orgId, campaignId);
        if (!"DRAFT".equals(current.status())) {
            throw new ApiException(ProblemCode.CONFLICT, "A campaign that was started cannot be changed.");
        }
        Map<String, String> texts = checked(draft);
        checkPlace(orgId, draft.locationId());
        system.execute(
                """
                UPDATE marketing.campaigns SET name = ?, audience = ?, content = ?::jsonb, location_id = ?, days = ?,
                       updated_at = now()
                 WHERE org_id = ? AND id = ?
                """,
                draft.name().strip(),
                draft.audience().name(),
                json.writeValueAsString(texts),
                draft.locationId(),
                draft.days(),
                orgId,
                campaignId);
        return require(orgId, campaignId);
    }

    @Override
    public void delete(UUID orgId, UUID campaignId) {
        Campaign current = require(orgId, campaignId);
        boolean unsent = "DRAFT".equals(current.status())
                || ("CANCELLED".equals(current.status()) && current.stats().recipients() == 0);
        if (!unsent) {
            throw new ApiException(ProblemCode.CONFLICT, "Only a draft can be deleted.");
        }
        system.execute("DELETE FROM marketing.campaigns WHERE org_id = ? AND id = ?", orgId, campaignId);
    }

    /** The place the link opens: the campaign's, else the business's first active one. */
    private Record place(UUID orgId, UUID locationId) {
        return system.resultQuery("""
                        SELECT o.slug::text AS business, o.display_name, l.slug::text AS location
                          FROM tenant.organizations o
                          LEFT JOIN tenant.locations l ON l.org_id = o.id AND l.status = 'ACTIVE'
                               AND (?::uuid IS NULL OR l.id = ?::uuid)
                         WHERE o.id = ?
                         ORDER BY l.created_at LIMIT 1
                        """, locationId, locationId, orgId).fetchOne();
    }

    private String venueUrl(Record place) {
        String location = place.get("location", String.class);
        return webUrl + "/en/b/" + place.get("business", String.class) + (location == null ? "" : "/" + location);
    }

    @Override
    public List<Preview> preview(UUID orgId, UUID campaignId) {
        Campaign c = require(orgId, campaignId);
        List<Preview> out = new ArrayList<>();
        for (String lang : List.of("en", "am")) {
            if (c.texts().get(lang) == null) continue;
            String text = Messages.compose(
                    c.texts().get(lang),
                    "Meron",
                    lang,
                    webUrl + "/l/" + Messages.SAMPLE_TOKEN,
                    webUrl + "/u/" + Messages.SAMPLE_TOKEN);
            out.add(new Preview(lang, text, text.codePointCount(0, text.length()), Messages.segments(text)));
        }
        return out;
    }

    @Override
    public Preview testSend(UUID orgId, UUID campaignId, UUID userId) {
        Campaign c = require(orgId, campaignId);
        rateLimiter.hit("marketing:test:" + userId, 10, Duration.ofDays(1));
        Record me = system.resultQuery(
                        "SELECT u.phone_e164, u.preferred_language, p.first_name FROM iam.users u"
                                + " LEFT JOIN iam.user_profiles p ON p.user_id = u.id"
                                + " WHERE u.id = ? AND u.phone_verified_at IS NOT NULL",
                        userId)
                .fetchOne();
        if (me == null || me.get("phone_e164") == null) {
            throw new ApiException(
                    ProblemCode.CONSTRAINT_VIOLATION, "Add and confirm your phone number to get a test message.");
        }
        String lang = "am".equals(me.get("preferred_language", String.class)) ? "am" : "en";
        String text = Messages.pick(c.texts(), lang);
        String url = venueUrl(place(orgId, c.locationId()));
        String body =
                "[Test] " + Messages.compose(text, me.get("first_name", String.class), lang, url, webUrl + "/u/test");
        sms.send(me.get("phone_e164", String.class), SmsGateway.Channel.SMS, body);
        return new Preview(lang, body, body.codePointCount(0, body.length()), Messages.segments(body));
    }

    @Override
    public Campaign schedule(UUID orgId, UUID campaignId, UUID userId, Instant at, Instant now) {
        Instant when = at == null ? now : at;
        if (when.isBefore(now.minusSeconds(60)) || when.isAfter(now.plus(Duration.ofDays(90)))) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose a time from now to 90 days ahead.");
        }
        transactions.executeWithoutResult(s -> {
            Record row = system.resultQuery(
                            "SELECT status, audience, days FROM marketing.campaigns WHERE org_id = ? AND id = ? FOR UPDATE",
                            orgId,
                            campaignId)
                    .fetchOne();
            if (row == null) {
                throw new ApiException(ProblemCode.NOT_FOUND, "There is no such campaign.");
            }
            if (!"DRAFT".equals(row.get("status", String.class))) {
                throw new ApiException(ProblemCode.CONFLICT, "This campaign was already started.");
            }
            int started = system.resultQuery(
                            "SELECT count(*) FROM marketing.campaigns WHERE org_id = ?"
                                    + " AND status IN ('SCHEDULED','SENDING','SENT') AND scheduled_at > ?::timestamptz",
                            orgId,
                            now.minus(Duration.ofDays(1)).toString())
                    .fetchOne(0, Integer.class);
            if (started >= MAX_CAMPAIGNS_PER_DAY) {
                throw new ApiException(
                        ProblemCode.CONSTRAINT_VIOLATION,
                        "At most " + MAX_CAMPAIGNS_PER_DAY + " campaigns can be started a day.");
            }
            AudienceCount count = audience(
                    orgId, Audience.valueOf(row.get("audience", String.class)), row.get("days", Integer.class));
            if (count.reachable() == 0) {
                throw new ApiException(ProblemCode.CONSTRAINT_VIOLATION, "There is nobody to send this to yet.");
            }
            system.execute(
                    "UPDATE marketing.campaigns SET status = 'SCHEDULED', scheduled_at = ?::timestamptz, updated_at = now()"
                            + " WHERE org_id = ? AND id = ?",
                    when.toString(),
                    orgId,
                    campaignId);
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    userId,
                    orgId,
                    "campaign.scheduled",
                    "marketing.campaign",
                    campaignId.toString(),
                    null,
                    Map.of("at", when.toString(), "reachable", count.reachable()),
                    null));
        });
        return require(orgId, campaignId);
    }

    @Override
    public Campaign cancel(UUID orgId, UUID campaignId, UUID userId) {
        transactions.executeWithoutResult(s -> {
            String status = system.resultQuery(
                            "SELECT status FROM marketing.campaigns WHERE org_id = ? AND id = ? FOR UPDATE",
                            orgId,
                            campaignId)
                    .fetchOne(0, String.class);
            if (status == null) {
                throw new ApiException(ProblemCode.NOT_FOUND, "There is no such campaign.");
            }
            if (!List.of("DRAFT", "SCHEDULED", "SENDING", "SENT").contains(status)) {
                throw new ApiException(ProblemCode.CONFLICT, "This campaign is already cancelled.");
            }
            system.execute(
                    "UPDATE marketing.campaigns SET status = 'CANCELLED', updated_at = now() WHERE org_id = ? AND id = ?",
                    orgId,
                    campaignId);
            // Messages not yet sent are not sent: the dispatcher skips recipients that are no longer queued.
            system.execute(
                    "UPDATE marketing.campaign_recipients SET status = 'SUPPRESSED_OPTED_OUT'"
                            + " WHERE campaign_id = ? AND status = 'QUEUED'",
                    campaignId);
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    userId,
                    orgId,
                    "campaign.cancelled",
                    "marketing.campaign",
                    campaignId.toString(),
                    Map.of("status", status),
                    null,
                    null));
        });
        return require(orgId, campaignId);
    }

    @Override
    public int sendDue(Instant now) {
        List<UUID> due = system.resultQuery(
                        "SELECT id FROM marketing.campaigns WHERE status = 'SCHEDULED' AND scheduled_at <= ?::timestamptz"
                                + " ORDER BY scheduled_at LIMIT 10",
                        now.toString())
                .fetch(0, UUID.class);
        int started = 0;
        for (UUID id : due) {
            Boolean ok = transactions.execute(s -> start(id, now));
            if (Boolean.TRUE.equals(ok)) started++;
        }
        return started;
    }

    /** Queues one campaign's messages. Runs in a system transaction. */
    private boolean start(UUID campaignId, Instant now) {
        Record c = system.resultQuery(
                        "SELECT org_id, audience, days, location_id, content FROM marketing.campaigns"
                                + " WHERE id = ? AND status = 'SCHEDULED' FOR UPDATE SKIP LOCKED",
                        campaignId)
                .fetchOne();
        if (c == null) {
            return false;
        }
        UUID orgId = c.get("org_id", UUID.class);
        Map<String, String> texts = json.readValue(c.get("content", JSONB.class).data(), TEXTS);
        Record place = place(orgId, c.get("location_id", UUID.class));
        String orgLanguage = system.resultQuery("SELECT default_language FROM tenant.organizations WHERE id = ?", orgId)
                .fetchOne(0, String.class);
        system.execute(
                "UPDATE marketing.campaigns SET status = 'SENDING', updated_at = now() WHERE id = ?", campaignId);

        List<Person> all =
                people(orgId, Audience.valueOf(c.get("audience", String.class)), c.get("days", Integer.class), now);
        int queued = 0;
        int suppressed = 0;
        for (Person person : all) {
            if (person.suppressed()) {
                suppressed++;
                continue;
            }
            if (queued >= MAX_RECIPIENTS) {
                break;
            }
            String token = token(16);
            UUID recipient = Ids.newId();
            int inserted = system.execute(
                    """
                    INSERT INTO marketing.campaign_recipients
                      (id, org_id, campaign_id, customer_id, user_id, destination_hash, token)
                    VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (campaign_id, destination_hash) DO NOTHING
                    """,
                    recipient,
                    orgId,
                    campaignId,
                    person.customerId(),
                    person.userId(),
                    hash(person.phone()),
                    token);
            if (inserted == 0) continue;
            String language = person.language() != null ? person.language() : orgLanguage;
            String text = Messages.compose(
                    Messages.pick(texts, language),
                    person.firstName(),
                    "am".equals(language) ? "am" : "en",
                    webUrl + "/l/" + token,
                    webUrl + "/u/" + token);
            UUID notification = Ids.newId();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("campaignId", campaignId.toString());
            data.put("token", token);
            data.put("text", text);
            data.put("business", place.get("display_name", String.class));
            data.put("timezone", "Africa/Addis_Ababa");
            system.execute(
                    """
                    INSERT INTO comms.notifications
                      (id, org_id, recipient_user_id, recipient_customer_id, category, template_code, data, show_in_app,
                       dedupe_key, send_after)
                    VALUES (?, ?, ?, ?, 'MARKETING', 'MARKETING_CAMPAIGN', ?::jsonb, false, ?, ?::timestamptz)
                    """,
                    notification,
                    orgId,
                    person.customerId() == null ? person.userId() : null,
                    person.customerId(),
                    json.writeValueAsString(data),
                    "campaign:" + campaignId + ":" + recipient,
                    now.toString());
            system.execute(
                    "UPDATE marketing.campaign_recipients SET notification_id = ? WHERE id = ?",
                    notification,
                    recipient);
            queued++;
        }
        system.execute(
                "UPDATE marketing.campaigns SET status = 'SENT', sent_at = now(), updated_at = now(), stats = ?::jsonb WHERE id = ?",
                json.writeValueAsString(Map.of("queued", queued, "left_out", suppressed)),
                campaignId);
        return true;
    }

    // --- tracking links ---------------------------------------------------------------------------------------

    private TrackingLink link(Record r) {
        return new TrackingLink(
                r.get("id", UUID.class),
                r.get("code", String.class),
                webUrl + "/l/" + r.get("code", String.class),
                r.get("name", String.class),
                r.get("location_id", UUID.class),
                r.get("clicks", Integer.class),
                r.get("clicks_week", Integer.class),
                instant(r, "created_at"));
    }

    private static final String LINK = """
            SELECT t.id, t.code::text AS code, t.name, t.location_id, t.created_at,
                   coalesce((SELECT sum(d.clicks) FROM marketing.tracking_link_daily d WHERE d.link_id = t.id), 0)::int AS clicks,
                   coalesce((SELECT sum(d.clicks) FROM marketing.tracking_link_daily d
                              WHERE d.link_id = t.id AND d.day > current_date - 7), 0)::int AS clicks_week
              FROM marketing.tracking_links t
            """;

    @Override
    public List<TrackingLink> links(UUID orgId) {
        List<TrackingLink> out = new ArrayList<>();
        for (Record r : system.resultQuery(
                        LINK + " WHERE t.org_id = ? ORDER BY t.created_at DESC, t.id DESC LIMIT 200", orgId)
                .fetch()) {
            out.add(link(r));
        }
        return out;
    }

    @Override
    public TrackingLink createLink(UUID orgId, UUID userId, String name, UUID locationId) {
        if (name == null || name.isBlank() || name.length() > 80) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Give the link a name of up to 80 characters.");
        }
        checkPlace(orgId, locationId);
        UUID id = Ids.newId();
        for (int attempt = 0; ; attempt++) {
            try {
                system.execute(
                        "INSERT INTO marketing.tracking_links (id, org_id, code, name, location_id, created_by)"
                                + " VALUES (?, ?, ?, ?, ?, ?)",
                        id,
                        orgId,
                        "t" + token(7),
                        name.strip(),
                        locationId,
                        userId);
                break;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                if (attempt > 5) throw e;
            }
        }
        return link(system.resultQuery(LINK + " WHERE t.id = ?", id).fetchSingle());
    }

    @Override
    public void deleteLink(UUID orgId, UUID linkId) {
        int deleted = system.execute("DELETE FROM marketing.tracking_links WHERE org_id = ? AND id = ?", orgId, linkId);
        if (deleted == 0) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such link.");
        }
    }

    @Override
    public Optional<Target> click(String code, Instant now) {
        if (code == null || code.length() < 4 || code.length() > 40) {
            return Optional.empty();
        }
        // A shared link's code is 8 characters ("t" and 7 more); a message's own code is 16.
        if (code.length() == LINK_CODE_LENGTH) {
            Record link = system.resultQuery(
                            "SELECT id, org_id, name, location_id FROM marketing.tracking_links WHERE code = ?::citext",
                            code)
                    .fetchOne();
            if (link == null) {
                return Optional.empty();
            }
            system.execute("""
                    INSERT INTO marketing.tracking_link_daily (org_id, link_id, day, clicks) VALUES (?, ?, current_date, 1)
                    ON CONFLICT (link_id, day) DO UPDATE SET clicks = marketing.tracking_link_daily.clicks + 1
                    """, link.get("org_id", UUID.class), link.get("id", UUID.class));
            return Optional.of(target(
                    link.get("org_id", UUID.class),
                    link.get("location_id", UUID.class),
                    "link",
                    "link",
                    link.get("name", String.class)));
        }
        Record r = system.resultQuery("""
                        SELECT r.id, r.org_id, r.campaign_id, c.location_id, c.name
                          FROM marketing.campaign_recipients r JOIN marketing.campaigns c ON c.id = r.campaign_id
                         WHERE r.token = ?
                        """, code).fetchOne();
        if (r == null) {
            return Optional.empty();
        }
        system.execute(
                "UPDATE marketing.campaign_recipients SET clicked_at = coalesce(clicked_at, now()) WHERE id = ?",
                r.get("id", UUID.class));
        return Optional.of(target(
                r.get("org_id", UUID.class),
                r.get("location_id", UUID.class),
                "sms",
                "campaign",
                r.get("campaign_id", UUID.class).toString()));
    }

    private Target target(UUID orgId, UUID locationId, String source, String medium, String campaign) {
        Record place = place(orgId, locationId);
        String location = place.get("location", String.class);
        String path = "/b/" + place.get("business", String.class) + (location == null ? "" : "/" + location);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("utm_source", source);
        params.put("utm_medium", medium);
        params.put("utm_campaign", campaign);
        return new Target(path, params);
    }

    // --- agreement and stopping -------------------------------------------------------------------------------

    @Override
    public Consent consent(UUID orgId, UUID customerId) {
        Record r = system.resultQuery(
                        "SELECT granted, captured_at, source FROM crm.customer_consents WHERE org_id = ? AND customer_id = ?"
                                + " AND purpose = 'MARKETING_SMS' ORDER BY captured_at DESC, id DESC LIMIT 1",
                        orgId,
                        customerId)
                .fetchOne();
        return r == null
                ? new Consent(false, null, null)
                : new Consent(
                        r.get("granted", Boolean.class), instant(r, "captured_at"), r.get("source", String.class));
    }

    @Override
    public Consent recordConsent(UUID orgId, UUID customerId, UUID userId, boolean granted, String note) {
        boolean exists = system.resultQuery(
                        "SELECT EXISTS (SELECT 1 FROM crm.customers WHERE org_id = ? AND id = ? AND status = 'ACTIVE')",
                        orgId,
                        customerId)
                .fetchOne(0, Boolean.class);
        if (!exists) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such client.");
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("recordedBy", userId.toString());
        if (note != null && !note.isBlank()) evidence.put("note", note.strip());
        transactions.executeWithoutResult(s -> {
            system.execute(
                    "INSERT INTO crm.customer_consents (org_id, customer_id, purpose, granted, source, evidence)"
                            + " VALUES (?, ?, 'MARKETING_SMS', ?, 'FORM', ?::jsonb)",
                    orgId,
                    customerId,
                    granted,
                    json.writeValueAsString(evidence));
            if (!granted) {
                system.execute(
                        "UPDATE marketing.campaign_recipients SET status = 'SUPPRESSED_OPTED_OUT'"
                                + " WHERE org_id = ? AND customer_id = ? AND status = 'QUEUED'",
                        orgId,
                        customerId);
            }
            audit.record(new Audit.Entry(
                    Audit.Actor.USER,
                    userId,
                    orgId,
                    granted ? "consent.marketing_granted" : "consent.marketing_withdrawn",
                    "crm.customer",
                    customerId.toString(),
                    null,
                    Map.of("purpose", "MARKETING_SMS"),
                    note));
        });
        return consent(orgId, customerId);
    }

    @Override
    public Optional<String> stopLink(String token) {
        return Optional.ofNullable(system.resultQuery(
                        "SELECT o.display_name FROM marketing.campaign_recipients r JOIN tenant.organizations o ON o.id = r.org_id"
                                + " WHERE r.token = ?",
                        token)
                .fetchOne(0, String.class));
    }

    @Override
    public boolean stop(String token, Instant now) {
        Record r = system.resultQuery(
                        "SELECT org_id, destination_hash, customer_id, user_id FROM marketing.campaign_recipients WHERE token = ?",
                        token)
                .fetchOne();
        if (r == null) {
            return false;
        }
        UUID orgId = r.get("org_id", UUID.class);
        String destination = r.get("destination_hash", String.class);
        UUID customer = r.get("customer_id", UUID.class);
        UUID user = r.get("user_id", UUID.class);
        transactions.executeWithoutResult(s -> {
            system.execute(
                    "INSERT INTO comms.suppressions (channel, destination_hash, org_id, reason)"
                            + " VALUES ('SMS', ?, ?, 'UNSUBSCRIBED') ON CONFLICT DO NOTHING",
                    destination,
                    orgId);
            // Followers stop hearing news and offers; clients' agreement is withdrawn (evidence: the stop link).
            if (user != null) {
                system.execute(
                        "UPDATE discovery.follows SET notify = false WHERE org_id = ? AND user_id = ?", orgId, user);
            }
            List<UUID> clients = new ArrayList<>();
            if (customer != null) clients.add(customer);
            if (user != null) {
                clients.addAll(system.resultQuery(
                                "SELECT id FROM crm.customers WHERE org_id = ? AND user_id = ? AND status = 'ACTIVE'",
                                orgId,
                                user)
                        .fetch(0, UUID.class));
            }
            for (UUID client : new HashSet<>(clients)) {
                system.execute(
                        "INSERT INTO crm.customer_consents (org_id, customer_id, purpose, granted, source, evidence)"
                                + " VALUES (?, ?, 'MARKETING_SMS', false, 'UNSUBSCRIBE_LINK', '{}'::jsonb)",
                        orgId,
                        client);
            }
            system.execute(
                    "UPDATE marketing.campaign_recipients SET status = 'SUPPRESSED_OPTED_OUT'"
                            + " WHERE org_id = ? AND destination_hash = ? AND status = 'QUEUED'",
                    orgId,
                    destination);
        });
        return true;
    }
}
