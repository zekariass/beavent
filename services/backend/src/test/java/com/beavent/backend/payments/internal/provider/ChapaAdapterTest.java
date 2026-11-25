package com.beavent.backend.payments.internal.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Chapa adapter against Chapa's documented shapes (developer.chapa.co; docs/07 §1.1). */
class ChapaAdapterTest {

    private static final String SECRET = "hook-secret";
    private final JsonMapper json = JsonMapper.builder().build();
    private FakeProvider chapa;
    private ChapaAdapter adapter;

    @BeforeEach
    void start() throws Exception {
        chapa = new FakeProvider();
        adapter = new ChapaAdapter(RestClient.builder(), chapa.url(), "CHASECK_TEST-1", SECRET, json);
    }

    @AfterEach
    void stop() {
        chapa.close();
    }

    static String hmac(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void initializesWithTheSellersSubaccountAndThePlatformShareAsAFlatSplit() {
        chapa.on(
                "POST /v1/transaction/initialize",
                200,
                "{\"message\":\"Hosted Link\",\"status\":\"success\",\"data\":{\"checkout_url\":\"https://checkout.test/att-1\"}}");
        CollectionResult result = adapter.createCollection(new CollectionRequest(
                "att-1",
                180_000,
                "ETB",
                "Deposit",
                "+251911223344",
                null,
                "en",
                URI.create("https://beavent.test/done"),
                URI.create("https://beavent.test/cancel"),
                URI.create("https://beavent.test/error"),
                URI.create("https://api.beavent.test/v1/hooks/chapa"),
                Instant.now(),
                new CollectionRequest.Split("SUB-1", 17_250)));
        assertThat(result.redirectUrl()).hasToString("https://checkout.test/att-1");
        assertThat(chapa.last().header("Authorization")).isEqualTo("Bearer CHASECK_TEST-1");
        JsonNode body = json.readTree(chapa.last().body());
        assertThat(body.path("amount").asString()).isEqualTo("1800.00");
        assertThat(body.path("tx_ref").asString()).isEqualTo("att-1");
        assertThat(body.path("phone_number").asString()).isEqualTo("0911223344");
        assertThat(body.path("subaccounts").path("split_type").asString()).isEqualTo("flat");
        assertThat(body.path("subaccounts").path("split_value").asString()).isEqualTo("172.50");
    }

    @Test
    void verifyMapsStatusAndAmount() {
        chapa.on(
                "GET /v1/transaction/verify/att-1",
                200,
                "{\"status\":\"success\",\"data\":{\"status\":\"success\",\"amount\":\"1800.00\",\"currency\":\"ETB\",\"tx_ref\":\"att-1\"}}");
        StatusResult result = adapter.queryCollection("att-1");
        assertThat(result.status()).isEqualTo(AttemptStatus.SUCCEEDED);
        assertThat(result.amountMinor()).isEqualTo(180_000L);
        chapa.on("GET /v1/transaction/verify/nope", 404, "{\"status\":\"failed\",\"message\":\"not found\"}");
        assertThat(adapter.findCollection("nope")).isNull();
    }

    @Test
    void acceptsOnlyCorrectlySignedNotifications() throws Exception {
        String body = "{\"event\":\"charge.success\",\"tx_ref\":\"att-1\",\"status\":\"success\"}";
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        assertThat(adapter.verifySignature(raw, Map.of("x-chapa-signature", hmac(SECRET, body))))
                .isTrue();
        assertThat(adapter.verifySignature(raw, Map.of("Chapa-Signature", hmac(SECRET, SECRET))))
                .isTrue();
        assertThat(adapter.verifySignature(raw, Map.of("x-chapa-signature", hmac("other", body))))
                .isFalse();
        assertThat(adapter.verifySignature(raw, Map.of("x-chapa-signature", "not-hex")))
                .isFalse();
        assertThat(adapter.verifySignature(raw, Map.of())).isFalse();
        assertThat(adapter.parseNotification(raw)).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo("COLLECTION");
            assertThat(e.claimedStatus()).isEqualTo(AttemptStatus.SUCCEEDED);
        });
        String refund =
                "{\"event\":\"charge.refunded\",\"type\":\"Refund\",\"ref_id\":\"RF1\",\"merchant_reference\":\"rf-1\",\"status\":\"refunded\"}";
        assertThat(adapter.parseNotification(refund.getBytes(StandardCharsets.UTF_8)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.kind()).isEqualTo("REFUND");
                    assertThat(e.reference()).isEqualTo("rf-1");
                });
    }

    @Test
    void refundsThroughTheApi() {
        chapa.on(
                "POST /v1/refund/att-1",
                200,
                "{\"status\":\"success\",\"message\":\"Refund initiated\",\"data\":{\"ref_id\":\"RF1\"}}");
        RefundResult result = adapter.refund(new RefundRequest("att-1", "rf-1", 50_000, "Cancelled in time"));
        assertThat(result.providerReference()).isEqualTo("RF1");
        JsonNode body = json.readTree(chapa.last().body());
        assertThat(body.path("amount").asString()).isEqualTo("500.00");
        assertThat(body.path("reference").asString()).isEqualTo("rf-1");
        chapa.on(
                "POST /v1/refund/att-2",
                400,
                "{\"status\":\"failed\",\"message\":\"Only successful transactions can be refunded\"}");
        assertThatThrownBy(() -> adapter.refund(new RefundRequest("att-2", "rf-2", 100, null)))
                .isInstanceOf(ProviderException.class);
    }
}
