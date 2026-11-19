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

    public record Call(int status, JsonNode body, String etag) {

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
                content.isEmpty() ? null : json.readTree(content),
                result.getResponse().getHeader("ETag"));
    }

    protected static String newPhone() {
        return "+2519" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    protected String lastCode(String phone) {
        Matcher matcher = Pattern.compile("(\\d{6})").matcher(sms.lastMessageTo(phone));
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    protected String requestCode(String phone, String purpose, String token) throws Exception {
        ageChallenges(phone);
        Call challenge = call(
                post("/v1/auth/otp/request"),
                Map.of("phone", phone, "purpose", purpose, "clientApp", "MOBILE_APP", "language", "en"),
                token);
        assertThat(challenge.status()).isEqualTo(202);
        return challenge.text("challengeId");
    }

    protected Call signInWithOtp(String phone) throws Exception {
        String challengeId = requestCode(phone, "SIGN_IN", null);
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
                Map.of("method", "OTP", "challengeId", challengeId, "code", lastCode(person.phone())),
                person.token());
        assertThat(stepUp.status()).isEqualTo(200);
        return stepUp.text("accessToken");
    }

    protected static JsonNode claims(JsonMapper json, String jwt) {
        return json.readTree(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8));
    }

    /** Moves earlier codes for this phone out of the resend window (tests sign in repeatedly). */
    protected void ageChallenges(String phone) {
        system.update(AUTH_CHALLENGES)
                .set(AUTH_CHALLENGES.CREATED_AT, OffsetDateTime.now().minusMinutes(1))
                .where(DSL.field("{0}->>'phone'", String.class, AUTH_CHALLENGES.CONTEXT)
                        .eq(phone))
                .execute();
    }
}
