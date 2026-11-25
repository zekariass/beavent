package com.beavent.backend.payments.internal.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** ArifPay adapter against ArifPay's documented shapes (SDK types, production Postman paths; docs/07 §1.2). */
class ArifPayAdapterTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private FakeProvider arifpay;
    private ArifPayAdapter adapter;

    @BeforeEach
    void start() throws Exception {
        arifpay = new FakeProvider();
        adapter = adapter(arifpay.url(), List.of());
    }

    @AfterEach
    void stop() {
        arifpay.close();
    }

    private ArifPayAdapter adapter(String url, List<String> methods) {
        return new ArifPayAdapter(
                RestClient.builder(),
                url,
                "key-1",
                ArifPayAdapter.Paths.PRODUCTION,
                methods,
                ZoneId.of("Africa/Addis_Ababa"),
                json);
    }

    private CollectionRequest request(String reference, long minor, String language) {
        return new CollectionRequest(
                reference,
                minor,
                "ETB",
                "Deposit: Box braids",
                "+251911000001",
                null,
                language,
                URI.create("https://beavent.test/done"),
                URI.create("https://beavent.test/cancel"),
                URI.create("https://beavent.test/error"),
                URI.create("https://api.beavent.test/v1/hooks/arifpay"),
                Instant.parse("2026-11-25T07:15:30Z"),
                null);
    }

    private static String session(String status) {
        String transaction = status == null
                ? "null"
                : "{\"id\":1,\"transactionId\":\"ATX1\",\"transactionStatus\":\"" + status
                        + "\",\"paymentType\":\"TELEBIRR\"}";
        return "{\"error\":false,\"msg\":\"Session\",\"data\":{\"id\":1,\"uuid\":\"S1\",\"sessionId\":\"S1\",\"nonce\":\"att-1\","
                + "\"totalAmount\":360.5,\"test\":true,\"transaction\":" + transaction + "}}";
    }

    @Test
    void createsASessionWithOurNonceExactAmountAndLocalExpiry() {
        arifpay.on(
                "POST /api/checkout/session",
                200,
                "{\"error\":false,\"msg\":\"Created\",\"data\":{\"sessionId\":\"S1\",\"paymentUrl\":\"https://pay.test/S1\",\"cancelUrl\":\"x\",\"totalAmount\":360.5}}");
        CollectionResult result = adapter.createCollection(request("att-1", 36_050, "am"));

        assertThat(result.providerReference()).isEqualTo("S1");
        assertThat(result.status()).isEqualTo(AttemptStatus.AWAITING_CUSTOMER);
        assertThat(result.redirectUrl()).hasToString("https://pay.test/S1");
        FakeProvider.Request sent = arifpay.last();
        assertThat(sent.header("x-arifpay-key")).isEqualTo("key-1");
        JsonNode body = json.readTree(sent.body());
        assertThat(body.path("nonce").asString()).isEqualTo("att-1");
        assertThat(body.path("items").get(0).path("price").decimalValue()).isEqualByComparingTo("360.50");
        assertThat(body.path("items").get(0).path("quantity").asInt()).isEqualTo(1);
        assertThat(body.path("phone").asString()).isEqualTo("251911000001");
        assertThat(body.path("lang").asString()).isEqualTo("AM");
        // 07:15:30 UTC is 10:15:30 in Addis Ababa; ArifPay shows local times without an offset.
        assertThat(body.path("expireDate").asString()).isEqualTo("2026-11-25T10:15:30");
        assertThat(body.path("notifyUrl").asString()).isEqualTo("https://api.beavent.test/v1/hooks/arifpay");
        // Production fills payment methods and beneficiaries from the merchant account.
        assertThat(body.has("paymentMethods")).isFalse();
        assertThat(body.has("beneficiaries")).isFalse();
    }

    @Test
    void leavesTheNumberOutWhenItIsNotAnEthiopianMobile() {
        arifpay.on(
                "POST /api/checkout/session",
                200,
                "{\"error\":false,\"data\":{\"sessionId\":\"S3\",\"paymentUrl\":\"https://pay.test/S3\"}}");
        CollectionRequest foreign = new CollectionRequest(
                "att-9",
                100,
                "ETB",
                "x",
                "+14155550123",
                null,
                "en",
                URI.create("https://b.test"),
                URI.create("https://b.test"),
                URI.create("https://b.test"),
                URI.create("https://b.test"),
                Instant.now(),
                null);
        adapter.createCollection(foreign);
        assertThat(json.readTree(arifpay.last().body()).has("phone")).isFalse();
    }

    @Test
    void sendsPaymentMethodsWhenConfigured() {
        arifpay.on(
                "POST /api/checkout/session",
                200,
                "{\"error\":false,\"data\":{\"sessionId\":\"S2\",\"paymentUrl\":\"https://pay.test/S2\"}}");
        adapter(arifpay.url(), List.of("TELEBIRR", "CBE")).createCollection(request("att-2", 100, "en"));
        assertThat(json.readTree(arifpay.last().body()).path("paymentMethods").size())
                .isEqualTo(2);
    }

    @Test
    void aRetriedCreateFindsTheSessionByNonceInsteadOfOpeningAnother() {
        arifpay.on(
                        "POST /api/checkout/session",
                        200,
                        "{\"error\":true,\"msg\":\"session already exist\",\"data\":null}")
                .on("GET /api/checkout/getSessionByNonce/att-1", 200, session("SUCCESS"));
        CollectionResult result = adapter.createCollection(request("att-1", 36_050, "en"));
        assertThat(result.status()).isEqualTo(AttemptStatus.SUCCEEDED);
        assertThat(result.providerReference()).isEqualTo("S1");
        assertThat(arifpay.requests).hasSize(2);
    }

    @Test
    void refusalsBecomeNormalisedFailures() {
        arifpay.on(
                "POST /api/checkout/session",
                400,
                "{\"error\":true,\"code\":\"03\",\"msg\":\"Amount exceeds the maximum allowed\",\"data\":null}");
        assertThatThrownBy(() -> adapter.createCollection(request("att-3", 5_000_000, "en")))
                .isInstanceOfSatisfying(
                        ProviderException.class, e -> assertThat(e.code()).isEqualTo(FailureCode.LIMIT_EXCEEDED));
        // Some failures come back as HTTP 200 with error: true.
        arifpay.on("POST /api/checkout/session", 200, "{\"error\":true,\"msg\":\"items are required\",\"data\":null}");
        assertThatThrownBy(() -> adapter.createCollection(request("att-4", 100, "en")))
                .isInstanceOfSatisfying(
                        ProviderException.class, e -> assertThat(e.code()).isEqualTo(FailureCode.INVALID_REQUEST));
        arifpay.on("POST /api/checkout/session", 503, "failure to get a peer from the ring-balancer");
        assertThatThrownBy(() -> adapter.createCollection(request("att-5", 100, "en")))
                .isInstanceOfSatisfying(
                        ProviderException.class, e -> assertThat(e.code()).isEqualTo(FailureCode.PROVIDER_UNAVAILABLE));
        ArifPayAdapter unreachable = adapter("http://127.0.0.1:1", List.of());
        assertThatThrownBy(() -> unreachable.createCollection(request("att-6", 100, "en")))
                .isInstanceOfSatisfying(
                        ProviderException.class, e -> assertThat(e.code()).isEqualTo(FailureCode.PROVIDER_UNAVAILABLE));
    }

    @Test
    void refusesWhatArifPayModeDoesNotDo() {
        CollectionRequest split = new CollectionRequest(
                "att-7",
                100,
                "ETB",
                "x",
                null,
                null,
                "en",
                URI.create("https://b.test"),
                URI.create("https://b.test"),
                URI.create("https://b.test"),
                URI.create("https://b.test"),
                Instant.now(),
                new CollectionRequest.Split("sub-1", 10));
        assertThatThrownBy(() -> adapter.createCollection(split)).isInstanceOf(ProviderException.class);
        assertThat(adapter.canRefundByApi()).isFalse();
        assertThat(adapter.collectionMode()).isEqualTo("PLATFORM_COLLECTS");
        assertThatThrownBy(() -> adapter.refund(new RefundRequest("att-1", "rf-1", 100, null)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(arifpay.requests).isEmpty();
    }

    @Test
    void lookupsMapStatusesAndKeepTheAmountForChecking() {
        arifpay.on("GET /api/checkout/session/S1", 200, session("SUCCESS"));
        StatusResult paid = adapter.queryCollection("S1");
        assertThat(paid.status()).isEqualTo(AttemptStatus.SUCCEEDED);
        assertThat(paid.amountMinor()).isEqualTo(36_050L);
        assertThat(paid.reference()).isEqualTo("att-1");
        assertThat(paid.rawStatus()).isEqualTo("SUCCESS");

        arifpay.on("GET /api/checkout/session/S1", 200, session(null));
        assertThat(adapter.queryCollection("S1").status()).isEqualTo(AttemptStatus.AWAITING_CUSTOMER);
        arifpay.on("GET /api/checkout/session/S1", 200, session("FAILED"));
        assertThat(adapter.queryCollection("S1").failure()).isEqualTo(FailureCode.CUSTOMER_DECLINED);
        // A status ArifPay has not documented stays in progress (looked up again), never "paid".
        arifpay.on("GET /api/checkout/session/S1", 200, session("UNDER_REVIEW"));
        assertThat(adapter.queryCollection("S1").status()).isEqualTo(AttemptStatus.PROCESSING);
        assertThat(adapter.findCollection("unknown-nonce")).isNull();
    }

    @Test
    void notificationsAreHintsNeverProof() {
        byte[] body = "{\"sessionId\":\"S1\",\"nonce\":\"att-1\",\"transactionStatus\":\"SUCCESS\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(adapter.notificationsAreAuthoritative()).isFalse();
        assertThat(adapter.verifySignature(body, Map.of())).isFalse();
        List<NormalizedEvent> events = adapter.parseNotification(body);
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.reference()).isEqualTo("att-1");
            assertThat(e.providerReference()).isEqualTo("S1");
            assertThat(e.claimedStatus()).isEqualTo(AttemptStatus.SUCCEEDED);
        });
        assertThat(adapter.parseNotification("{\"hello\":1}".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
    }

    @Test
    void cancelsAnUnpaidSession() {
        arifpay.on("POST /api/checkout/session/cancel/S1", 200, session(null));
        adapter.cancelCollection("S1");
        assertThat(arifpay.last().path()).isEqualTo("/api/checkout/session/cancel/S1");
    }

    @Test
    void moneyConvertsExactly() {
        assertThat(Money.toDecimal(180_050)).isEqualByComparingTo("1800.50");
        assertThat(Money.toMinor("1800.5")).isEqualTo(180_050);
        assertThatThrownBy(() -> Money.toMinor("1.005")).isInstanceOf(ArithmeticException.class);
    }
}
