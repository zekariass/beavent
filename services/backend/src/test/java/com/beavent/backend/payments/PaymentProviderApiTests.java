package com.beavent.backend.payments;

import static com.beavent.db.payments.Tables.PROVIDER_ACCOUNTS;
import static com.beavent.db.payments.Tables.ROUTES;
import static com.beavent.db.payments.Tables.WEBHOOK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.beavent.backend.ApiTestSupport;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Slice 3.1: ArifPay is the active provider by default (docs/07 L1); its platform account and online routes exist at
 * startup; provider notifications are stored once, signed ones only with a valid signature.
 */
class PaymentProviderApiTests extends ApiTestSupport {

    @Test
    void arifPayIsTheDefaultWithItsPlatformAccountAndOnlineRoutes() {
        var account = system.selectFrom(PROVIDER_ACCOUNTS)
                .where(PROVIDER_ACCOUNTS.PROVIDER_CODE.eq("ARIFPAY"))
                .and(PROVIDER_ACCOUNTS.OWNER.eq("PLATFORM"))
                .fetchOne();
        assertThat(account).isNotNull();
        assertThat(account.getStatus()).isEqualTo("ACTIVE");
        assertThat(account.getEnvironment()).isEqualTo("SANDBOX");
        assertThat(account.getCredentialsSecretRef()).isEqualTo("env:ARIFPAY_API_KEY");
        assertThat(system.select(ROUTES.METHOD_CODE)
                        .from(ROUTES)
                        .where(ROUTES.PROVIDER_ACCOUNT_ID.eq(account.getId()))
                        .and(ROUTES.IS_ACTIVE.isTrue())
                        .and(ROUTES.CHANNEL.eq("ONLINE"))
                        .fetch(ROUTES.METHOD_CODE))
                .contains("TELEBIRR", "CBE_BIRR", "MPESA", "CARD");
        // Only the active provider routes payments.
        assertThat(system.fetchCount(
                        ROUTES,
                        ROUTES.IS_ACTIVE
                                .isTrue()
                                .and(ROUTES.ORG_ID.isNull())
                                .and(ROUTES.PROVIDER_ACCOUNT_ID.ne(account.getId()))))
                .isZero();
        assertThat(system.fetchValue(
                        "select f.status from ref.market_features f join ref.markets m on m.id = f.market_id "
                                + "where m.code = 'ET' and f.feature_code = 'PLATFORM_COLLECTION'"))
                .isEqualTo("BETA");
    }

    @Test
    void anArifPayNotificationIsStoredOnceAsAnUnsignedHint() throws Exception {
        String nonce = UUID.randomUUID().toString();
        String body =
                "{\"sessionId\":\"S-" + nonce + "\",\"nonce\":\"" + nonce + "\",\"transactionStatus\":\"SUCCESS\"}";
        assertThat(call(post("/v1/hooks/arifpay"), body, null).status()).isEqualTo(200);
        // ArifPay does not retry, but a replay (or our own test) must not be stored twice.
        assertThat(call(post("/v1/hooks/arifpay"), body, null).status()).isEqualTo(200);
        var stored = system.selectFrom(WEBHOOK_EVENTS)
                .where(WEBHOOK_EVENTS.PROVIDER_CODE.eq("ARIFPAY"))
                .and(WEBHOOK_EVENTS.PAYLOAD.cast(String.class).contains(nonce))
                .fetch();
        assertThat(stored).singleElement().satisfies(e -> {
            assertThat(e.getSignatureValid()).isFalse();
            // Stored as received; the payment worker may already have looked at it (nothing of ours matches it).
            assertThat(e.getStatus()).isIn("RECEIVED", "IGNORED");
            assertThat(e.getProviderAccountId()).isNotNull();
        });
    }

    @Test
    void chapaNotificationsNeedAValidSignature() throws Exception {
        String txRef = UUID.randomUUID().toString();
        String body = "{\"event\":\"charge.success\",\"tx_ref\":\"" + txRef + "\",\"status\":\"success\"}";
        assertThat(call(post("/v1/hooks/chapa").header("x-chapa-signature", hmac("wrong", body)), body, null)
                        .status())
                .isEqualTo(401);
        assertThat(call(post("/v1/hooks/chapa"), body, null).status()).isEqualTo(401);
        assertThat(call(
                                post("/v1/hooks/chapa")
                                        .header("x-chapa-signature", hmac("beavent-stub-webhook-secret", body)),
                                body,
                                null)
                        .status())
                .isEqualTo(200);
        var stored = system.selectFrom(WEBHOOK_EVENTS)
                .where(WEBHOOK_EVENTS.PROVIDER_CODE.eq("CHAPA"))
                .and(WEBHOOK_EVENTS.PAYLOAD.cast(String.class).contains(txRef))
                .fetch();
        assertThat(stored).singleElement().satisfies(e -> {
            assertThat(e.getSignatureValid()).isTrue();
            assertThat(e.getEventType()).isEqualTo("charge.success");
            assertThat(e.getHeaders().data()).contains("\"x-chapa-signature\": \"present\"");
        });
    }

    @Test
    void aBodyThatIsNotJsonIsRefused() throws Exception {
        assertThat(callInvalid(post("/v1/hooks/arifpay"), "[1,2]", null).status())
                .isEqualTo(400);
        assertThat(callInvalid(post("/v1/hooks/arifpay"), "not json", null).status())
                .isEqualTo(400);
    }

    private static String hmac(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
