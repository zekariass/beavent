package com.beavent.backend;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static com.beavent.db.iam.Tables.AUTH_CHALLENGES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.beavent.backend.platform.SystemDb;
import com.beavent.backend.platform.messaging.LoggingSmsGateway;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base for API tests: MockMvc against the real stack (PostGIS, Redis, RabbitMQ), every request and response validated
 * against the bundled OpenAPI contract, plus sign-in helpers using the logging SMS provider.
 */
@IntegrationTest
@AutoConfigureMockMvc
public abstract class ApiTestSupport {

    public static final String CONTRACT = "openapi/beavent.yaml";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JsonMapper json;

    @Autowired
    protected LoggingSmsGateway sms;

    @Autowired
    @SystemDb
    protected DSLContext system;

    /** {@code raw} is the response text; {@code body} is its JSON, or null for an empty or non-JSON answer. */
    public record Call(int status, JsonNode body, String etag, String raw) {

        public String text(String field) {
            return body.get(field).asString();
        }
    }

    /** A signed-in person: phone and access token. */
    public record Person(String phone, String token) {}

    /** Checks responses only, for tests that send requests the contract forbids on purpose. */
    private static final OpenApiInteractionValidator RESPONSES_ONLY =
            OpenApiInteractionValidator.createForSpecificationUrl(CONTRACT)
                    .withLevelResolver(LevelResolver.create()
                            .withLevel("validation.request", ValidationReport.Level.IGNORE)
                            .build())
                    .build();

    protected Call call(MockHttpServletRequestBuilder request, Object body, String token) throws Exception {
        return perform(request, body, token, false);
    }

    /** Like {@link #call} for a request that deliberately breaks the contract; the response is still checked. */
    protected Call callInvalid(MockHttpServletRequestBuilder request, Object body, String token) throws Exception {
        return perform(request, body, token, true);
    }

    private Call perform(MockHttpServletRequestBuilder request, Object body, String token, boolean invalidRequest)
            throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content(body instanceof String raw ? raw : json.writeValueAsString(body));
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        MvcResult result = mvc.perform(request).andReturn();
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        try {
            (invalidRequest ? openApi().isValid(RESPONSES_ONLY) : openApi().isValid(CONTRACT)).match(result);
        } catch (Exception | AssertionError e) {
            throw new AssertionError(
                    e.getMessage() + "\nResponse " + result.getResponse().getStatus() + ": " + content, e);
        }
        return new Call(
                result.getResponse().getStatus(),
                content.isEmpty()
                                || !String.valueOf(result.getResponse().getContentType())
                                        .contains("json")
                        ? null
                        : json.readTree(content),
                result.getResponse().getHeader("ETag"),
                content);
    }

    protected static String newPhone() {
        return "+2519" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    /** The newest one-time code sent to the phone (other texts, such as notices, may arrive after it). */
    protected String lastCode(String phone) {
        List<String> messages = sms.messagesTo(phone);
        for (int i = messages.size() - 1; i >= 0; i--) {
            Matcher matcher = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)").matcher(messages.get(i));
            if (matcher.find()) return matcher.group(1);
        }
        throw new AssertionError("No code was sent to " + phone);
    }

    protected String requestCode(String phone, String purpose, String token) throws Exception {
        return requestCode(phone, purpose, token, "MOBILE_APP");
    }

    protected String requestCode(String phone, String purpose, String token, String clientApp) throws Exception {
        ageChallenges(phone);
        Call challenge = call(
                post("/v1/auth/otp/request"),
                Map.of("phone", phone, "purpose", purpose, "clientApp", clientApp, "language", "en"),
                token);
        assertThat(challenge.status()).isEqualTo(202);
        return challenge.text("challengeId");
    }

    protected Call signInWithOtp(String phone) throws Exception {
        return signInWithOtp(phone, "MOBILE_APP");
    }

    protected Call signInWithOtp(String phone, String clientApp) throws Exception {
        String challengeId = requestCode(phone, "SIGN_IN", null, clientApp);
        return call(
                post("/v1/auth/otp/verify"),
                Map.of(
                        "challengeId",
                        challengeId,
                        "code",
                        lastCode(phone),
                        "device",
                        Map.of("installationId", "test-" + phone, "platform", "ANDROID", "appVersion", "1.0.0")),
                null);
    }

    /** Signs in a new person by phone and returns their access token. */
    protected Person newPerson() throws Exception {
        String phone = newPhone();
        Call signedIn = signInWithOtp(phone);
        assertThat(signedIn.status()).isEqualTo(200);
        return new Person(phone, signedIn.text("accessToken"));
    }

    /** A short-lived STEP_UP token for the person (fresh one-time code). */
    protected String stepUp(Person person) throws Exception {
        String challengeId = requestCode(person.phone(), "STEP_UP", person.token());
        Call stepUp = call(
                post("/v1/auth/step-up"),
                Map.of("challengeId", challengeId, "code", lastCode(person.phone())),
                person.token());
        assertThat(stepUp.status()).isEqualTo(200);
        return stepUp.text("accessToken");
    }

    protected static JsonNode claims(JsonMapper json, String jwt) {
        return json.readTree(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8));
    }

    /** Moves earlier codes for this phone or email out of the resend window (tests sign in repeatedly). */
    protected void ageChallenges(String phone) {
        system.update(AUTH_CHALLENGES)
                .set(AUTH_CHALLENGES.CREATED_AT, OffsetDateTime.now().minusMinutes(1))
                .where(DSL.field("{0}->>'phone'", String.class, AUTH_CHALLENGES.CONTEXT)
                        .eq(phone)
                        .or(DSL.field("{0}->>'email'", String.class, AUTH_CHALLENGES.CONTEXT)
                                .eq(phone.toLowerCase(java.util.Locale.ROOT))))
                .execute();
    }

    // --- organizations ----------------------------------------------------------------------------------------

    /**
     * Makes the business ready to take online payments, as after platform review: verified, with a verified default
     * payout account (plan 3.4).
     */
    protected void makePaymentsReady(Salon salon) {
        system.execute(
                "update tenant.organizations set verification_status = 'VERIFIED' where id = ?::uuid", salon.id());
        system.execute(
                "update tenant.legal_entities set verification_status = 'VERIFIED' where org_id = ?::uuid", salon.id());
        system.execute(
                "insert into payments.payout_accounts (org_id, legal_entity_id, kind, country_code, currency_code, "
                        + "institution_code, institution_name, account_name, account_number_masked, details_secret_ref, "
                        + "verification_status, verified_via, is_default, reviewed_by, reviewed_at) "
                        + "select e.org_id, e.id, 'BANK_ACCOUNT', e.country_code, e.functional_currency, 'CBETETAA', "
                        + "'Commercial Bank of Ethiopia', e.registered_name, '••••0001', 'box:v1:test', 'VERIFIED', 'MANUAL', "
                        + "true, (select id from iam.users limit 1), now() from tenant.legal_entities e where e.org_id = ?::uuid "
                        + "limit 1",
                salon.id());
    }

    /** An organization with an owner, a legal entity and two locations in Addis Ababa. */
    public record Salon(java.util.UUID id, Person owner, java.util.UUID location1, java.util.UUID location2) {

        public String path(String rest) {
            return "/v1/orgs/" + id + rest;
        }
    }

    protected java.util.UUID category(String kind, String code) {
        return system.select(com.beavent.db.ref.Tables.CATEGORIES.ID)
                .from(com.beavent.db.ref.Tables.CATEGORIES)
                .where(
                        com.beavent.db.ref.Tables.CATEGORIES.KIND.eq(kind),
                        com.beavent.db.ref.Tables.CATEGORIES.CODE.eq(code))
                .fetchSingle(com.beavent.db.ref.Tables.CATEGORIES.ID);
    }

    protected Salon newSalon(String name) throws Exception {
        Person owner = newPerson();
        java.util.UUID orgId = java.util.UUID.fromString(call(
                        post("/v1/orgs"),
                        Map.of(
                                "displayName",
                                name,
                                "kind",
                                "BUSINESS",
                                "primaryCategoryId",
                                category("BUSINESS", "HAIR_SALON"),
                                "defaultLanguage",
                                "am"),
                        owner.token())
                .text("id"));
        String entity = call(
                        post("/v1/orgs/" + orgId + "/legal-entities"),
                        Map.of(
                                "countryCode",
                                "ET",
                                "entityType",
                                "PLC",
                                "registeredName",
                                name + " PLC",
                                "taxRegime",
                                "TOT"),
                        owner.token())
                .text("id");
        java.util.UUID area = system.select(com.beavent.db.ref.Tables.ADMINISTRATIVE_AREAS.ID)
                .from(com.beavent.db.ref.Tables.ADMINISTRATIVE_AREAS)
                .where(
                        com.beavent.db.ref.Tables.ADMINISTRATIVE_AREAS.COUNTRY_CODE.eq("ET"),
                        com.beavent.db.ref.Tables.ADMINISTRATIVE_AREAS.LEVEL.eq((short) 2))
                .limit(1)
                .fetchSingle(com.beavent.db.ref.Tables.ADMINISTRATIVE_AREAS.ID);
        java.util.UUID[] locations = new java.util.UUID[2];
        for (int i = 0; i < 2; i++) {
            locations[i] = java.util.UUID.fromString(call(
                            post("/v1/orgs/" + orgId + "/locations"),
                            Map.of(
                                    "name",
                                    "Branch " + (i + 1),
                                    "legalEntityId",
                                    entity,
                                    "timezone",
                                    "Africa/Addis_Ababa",
                                    "address",
                                    Map.of("countryCode", "ET", "adminAreaId", area, "landmark", "Near Edna Mall")),
                            owner.token())
                    .text("id"));
        }
        return new Salon(orgId, owner, locations[0], locations[1]);
    }

    /** Invites a new person with a system role and has them accept; returns them signed in. */
    protected Person newMember(Salon salon, String roleCode) throws Exception {
        String roleId = null;
        for (JsonNode role : call(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(salon.path("/roles")),
                        null,
                        salon.owner().token())
                .body()
                .get("items")) {
            if (role.get("code").asString().equals(roleCode)) {
                roleId = role.get("id").asString();
            }
        }
        String phone = newPhone();
        String inviter =
                roleCode.equals("RECEPTIONIST") || roleCode.equals("PROFESSIONAL") || roleCode.equals("CASHIER")
                        ? salon.owner().token()
                        : stepUp(salon.owner());
        Call invited = call(post(salon.path("/invitations")), Map.of("phone", phone, "roleId", roleId), inviter);
        assertThat(invited.status()).as(String.valueOf(invited.body())).isEqualTo(201);
        String token = sms.lastMessageTo(phone).replaceAll("(?s).*token=([A-Za-z0-9_-]+).*", "$1");
        String access = signInWithOtp(phone).text("accessToken");
        assertThat(call(post("/v1/invitations/accept"), Map.of("token", token), access)
                        .status())
                .isEqualTo(200);
        return new Person(phone, access);
    }
}
