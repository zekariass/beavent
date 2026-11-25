package com.beavent.backend.payments.internal.provider;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Chapa (docs/07 §1.1): hosted checkout with the seller's subaccount and the platform's share as a flat split
 * ({@code PROVIDER_SPLIT}), verify by {@code tx_ref}, HMAC-signed webhooks, refunds by API.
 */
public final class ChapaAdapter implements PaymentProviderAdapter {

    private final RestClient http;
    private final JsonMapper json;
    private final byte[] webhookSecret;

    public ChapaAdapter(
            RestClient.Builder builder, String baseUrl, String secretKey, String webhookSecret, JsonMapper json) {
        this.http = builder.baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + secretKey)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
        this.json = json;
        this.webhookSecret = webhookSecret == null ? new byte[0] : webhookSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String providerCode() {
        return "CHAPA";
    }

    @Override
    public String collectionMode() {
        return "PROVIDER_SPLIT";
    }

    @Override
    public boolean canRefundByApi() {
        return true;
    }

    @Override
    public boolean notificationsAreAuthoritative() {
        return true;
    }

    @Override
    public CollectionResult createCollection(CollectionRequest r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", Money.toDecimal(r.amountMinor()).toPlainString());
        body.put("currency", r.currency());
        body.put("tx_ref", r.reference());
        body.put("callback_url", r.notifyUrl().toString());
        body.put("return_url", r.returnUrl().toString());
        if (r.phone() != null) body.put("phone_number", localPhone(r.phone()));
        if (r.email() != null) body.put("email", r.email());
        body.put("customization", Map.of("title", "Beavent", "description", r.description()));
        if (r.split() != null) {
            body.put(
                    "subaccounts",
                    Map.of(
                            "id", r.split().subAccountId(),
                            "split_type", "flat",
                            "split_value",
                                    Money.toDecimal(r.split().platformShareMinor())
                                            .toPlainString()));
        }
        JsonNode answer = call(() -> http.post()
                .uri("/v1/transaction/initialize")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class));
        if (!"success".equals(answer.path("status").asString(""))) {
            String message =
                    answer.path("message").isString() ? answer.path("message").asString() : "Chapa refused the payment";
            FailureCode code = message.toLowerCase(Locale.ROOT).contains("used before")
                    ? FailureCode.DUPLICATE_REFERENCE
                    : FailureCode.INVALID_REQUEST;
            throw new ProviderException(code, message, false);
        }
        String url = answer.path("data").path("checkout_url").asString("");
        if (url.isBlank())
            throw new ProviderException(FailureCode.UNKNOWN_ERROR, "Chapa answered without a page", false);
        return new CollectionResult(r.reference(), AttemptStatus.AWAITING_CUSTOMER, URI.create(url));
    }

    @Override
    public StatusResult queryCollection(String txRef) {
        StatusResult found = findCollection(txRef);
        if (found == null) throw new ProviderException(FailureCode.UNKNOWN_ERROR, "Chapa has no such payment", false);
        return found;
    }

    @Override
    public StatusResult findCollection(String txRef) {
        JsonNode answer = call(() ->
                http.get().uri("/v1/transaction/verify/{ref}", txRef).retrieve().toEntity(String.class));
        if (!"success".equals(answer.path("status").asString(""))) return null;
        JsonNode data = answer.path("data");
        String raw = data.path("status").asString(null);
        return new StatusResult(
                map(raw),
                amount(data.path("amount")),
                data.path("currency").asString(null),
                data.path("tx_ref").asString(null),
                data.path("tx_ref").asString(null),
                "failed".equals(raw) ? FailureCode.CUSTOMER_DECLINED : null,
                raw);
    }

    /** Chapa has no cancel call; an unpaid checkout simply expires. */
    @Override
    public void cancelCollection(String providerReference) {}

    /**
     * {@code x-chapa-signature} = HMAC-SHA256(secret, raw body), or {@code chapa-signature} = HMAC-SHA256(secret,
     * secret); compared in constant time.
     */
    @Override
    public boolean verifySignature(byte[] rawBody, Map<String, String> headers) {
        if (webhookSecret.length == 0) return false;
        String bodySignature = header(headers, "x-chapa-signature");
        if (bodySignature != null && same(hmac(rawBody), bodySignature)) return true;
        String secretSignature = header(headers, "chapa-signature");
        return secretSignature != null && same(hmac(webhookSecret), secretSignature);
    }

    @Override
    public List<NormalizedEvent> parseNotification(byte[] rawBody) {
        JsonNode body = json.readTree(new String(rawBody, StandardCharsets.UTF_8));
        String event = body.path("event").asString("");
        String raw = body.path("status").asString(null);
        if ("Refund".equalsIgnoreCase(body.path("type").asString(""))
                || event.equals("charge.refunded")
                || event.equals("charge.reversed")) {
            return List.of(new NormalizedEvent(
                    "REFUND",
                    event,
                    body.path("merchant_reference").asString(null),
                    body.path("ref_id").asString(null),
                    "refunded".equals(raw)
                            ? AttemptStatus.SUCCEEDED
                            : "reversed".equals(raw) ? AttemptStatus.FAILED : AttemptStatus.PROCESSING));
        }
        String txRef = body.path("tx_ref").asString(null);
        if (txRef == null) return List.of();
        return List.of(new NormalizedEvent("COLLECTION", event, txRef, txRef, map(raw)));
    }

    /** A subaccount whose own split is zero: every payment carries its split explicitly (the platform's flat share). */
    @Override
    public String createSubAccount(String businessName, String accountName, String bankCode, String accountNumber) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("business_name", businessName);
        body.put("account_name", accountName);
        body.put("bank_code", bankCode);
        body.put("account_number", accountNumber);
        body.put("split_type", "flat");
        body.put("split_value", 0);
        JsonNode answer = call(() -> http.post()
                .uri("/v1/subaccount")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class));
        String id = answer.path("data").path("subaccount_id").asString("");
        if (!"success".equals(answer.path("status").asString("")) || id.isBlank()) {
            String message = answer.path("message").isString()
                    ? answer.path("message").asString()
                    : answer.path("message").toString();
            throw new ProviderException(FailureCode.INVALID_REQUEST, "Chapa refused the subaccount: " + message, false);
        }
        return id;
    }

    @Override
    public RefundResult refund(RefundRequest r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", Money.toDecimal(r.amountMinor()).toPlainString());
        body.put("reference", r.reference());
        if (r.reason() != null) body.put("reason", r.reason());
        JsonNode answer = call(() -> http.post()
                .uri("/v1/refund/{ref}", r.collectionReference())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class));
        if (!"success".equals(answer.path("status").asString(""))) {
            String message =
                    answer.path("message").isString() ? answer.path("message").asString() : "Chapa refused the refund";
            throw new ProviderException(FailureCode.INVALID_REQUEST, message, false);
        }
        return new RefundResult(answer.path("data").path("ref_id").asString(null), AttemptStatus.PROCESSING);
    }

    @Override
    public StatusResult queryRefund(String refId) {
        JsonNode answer = call(() ->
                http.get().uri("/v1/refund/{ref}/verify", refId).retrieve().toEntity(String.class));
        JsonNode data = answer.path("data");
        String raw = data.path("status").asString(null);
        AttemptStatus status = switch (raw == null ? "" : raw) {
            case "refunded" -> AttemptStatus.SUCCEEDED;
            case "reversed" -> AttemptStatus.FAILED;
            default -> AttemptStatus.PROCESSING;
        };
        return new StatusResult(
                status,
                amount(data.path("amount")),
                data.path("currency").asString(null),
                data.path("merchant_reference").asString(null),
                refId,
                null,
                raw);
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    static AttemptStatus map(String raw) {
        if (raw == null) return AttemptStatus.PROCESSING;
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "success" -> AttemptStatus.SUCCEEDED;
            case "failed" -> AttemptStatus.FAILED;
            case "cancelled" -> AttemptStatus.CANCELLED;
            case "pending" -> AttemptStatus.AWAITING_CUSTOMER;
            default -> AttemptStatus.PROCESSING;
        };
    }

    /** +251911223344 → 0911223344 (Chapa's local format). */
    static String localPhone(String e164) {
        return e164.startsWith("+251") ? "0" + e164.substring(4) : e164;
    }

    private static Long amount(JsonNode node) {
        try {
            if (node.isNumber()) return Money.toMinor(node.decimalValue());
            if (node.isString()) return Money.toMinor(new BigDecimal(node.asString()));
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
        return null;
    }

    private interface Call {
        ResponseEntity<String> run();
    }

    private JsonNode call(Call call) {
        ResponseEntity<String> response;
        try {
            response = call.run();
        } catch (ResourceAccessException e) {
            throw new ProviderException(
                    FailureCode.PROVIDER_UNAVAILABLE, "Chapa is unreachable: " + e.getMessage(), false);
        }
        if (response.getStatusCode().is5xxServerError()) {
            throw new ProviderException(
                    FailureCode.PROVIDER_UNAVAILABLE,
                    "Chapa answered " + response.getStatusCode().value(),
                    false);
        }
        try {
            String body = response.getBody();
            return json.readTree(body == null ? "{}" : body);
        } catch (RuntimeException e) {
            throw new ProviderException(FailureCode.UNKNOWN_ERROR, "Chapa answered something that is not JSON", false);
        }
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean same(byte[] expected, String hexSignature) {
        try {
            return MessageDigest.isEqual(
                    expected, HexFormat.of().parseHex(hexSignature.strip().toLowerCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }
}
