package com.beavent.backend.payments.internal.provider;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ArifPay checkout (docs/07 §1.2): Beavent's merchant account collects ({@code PLATFORM_COLLECTS}). Our reference is
 * the session {@code nonce}, which ArifPay keeps unique, so a retried create cannot open a second session. Notify calls
 * are not documented as signed or retried: they are cues to look the session up with our key, and polling is the main
 * path. ArifPay publishes no refund API.
 */
public final class ArifPayAdapter implements PaymentProviderAdapter {

    /** Where ArifPay's endpoints live; the official sources disagree, so each path is configuration. */
    public record Paths(String create, String session, String byNonce, String cancel) {
        /** The official production Postman collection's paths. */
        public static final Paths PRODUCTION = new Paths(
                "/api/checkout/session",
                "/api/checkout/session/{id}",
                "/api/checkout/getSessionByNonce/{nonce}",
                "/api/checkout/session/cancel/{id}");
    }

    private static final DateTimeFormatter EXPIRE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final RestClient http;
    private final JsonMapper json;
    private final Paths paths;
    private final List<String> paymentMethods;
    private final ZoneId expireZone;

    /**
     * @param paymentMethods sent as {@code paymentMethods}; empty to let ArifPay fill them from the merchant account
     *     (production)
     * @param expireZone the zone {@code expireDate} is written in (ArifPay shows local times without an offset)
     */
    public ArifPayAdapter(
            RestClient.Builder builder,
            String baseUrl,
            String apiKey,
            Paths paths,
            List<String> paymentMethods,
            ZoneId expireZone,
            JsonMapper json) {
        this.http = builder.baseUrl(baseUrl)
                .defaultHeader("x-arifpay-key", apiKey)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
        this.json = json;
        this.paths = paths;
        this.paymentMethods = List.copyOf(paymentMethods);
        this.expireZone = expireZone;
    }

    @Override
    public String providerCode() {
        return "ARIFPAY";
    }

    @Override
    public String collectionMode() {
        return "PLATFORM_COLLECTS";
    }

    @Override
    public boolean canRefundByApi() {
        return false;
    }

    @Override
    public boolean notificationsAreAuthoritative() {
        return false;
    }

    @Override
    public CollectionResult createCollection(CollectionRequest r) {
        if (!"ETB".equals(r.currency())) {
            throw new ProviderException(FailureCode.INVALID_REQUEST, "ArifPay collects ETB only", false);
        }
        if (r.split() != null) {
            throw new ProviderException(
                    FailureCode.INVALID_REQUEST, "ArifPay mode collects for Beavent; no split", false);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", r.description());
        item.put("quantity", 1);
        item.put("price", Money.toDecimal(r.amountMinor()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nonce", r.reference());
        if (r.phone() != null) body.put("phone", localPhone(r.phone()));
        if (r.email() != null) body.put("email", r.email());
        body.put("lang", "am".equals(r.language()) ? "AM" : "EN");
        body.put("items", List.of(item));
        body.put("notifyUrl", r.notifyUrl().toString());
        body.put("successUrl", r.returnUrl().toString());
        body.put("cancelUrl", r.cancelUrl().toString());
        body.put("errorUrl", r.errorUrl().toString());
        body.put(
                "expireDate",
                EXPIRE.format(r.expiresAt().truncatedTo(ChronoUnit.SECONDS).atZone(expireZone)));
        if (!paymentMethods.isEmpty()) body.put("paymentMethods", paymentMethods);

        JsonNode answer = call(() -> http.post()
                .uri(paths.create())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class));
        if (failed(answer)) {
            String message = text(answer, "msg");
            if (message != null && message.toLowerCase(Locale.ROOT).contains("already exist")) {
                // A retried create: the session was opened before. Its status decides; the page URL is not returned
                // by lookups, so the caller starts a new attempt if the payer still has to pay.
                StatusResult existing = findCollection(r.reference());
                if (existing != null)
                    return new CollectionResult(existing.providerReference(), existing.status(), null);
                throw new ProviderException(FailureCode.DUPLICATE_REFERENCE, message, false);
            }
            throw new ProviderException(
                    failureOf(answer), message == null ? "ArifPay refused the session" : message, false);
        }
        JsonNode data = answer.path("data");
        String sessionId = firstText(data, "sessionId", "SessionId", "uuid");
        String paymentUrl = firstText(data, "paymentUrl", "approvalUrl");
        if (sessionId == null || paymentUrl == null) {
            throw new ProviderException(FailureCode.UNKNOWN_ERROR, "ArifPay answered without a session", false);
        }
        return new CollectionResult(sessionId, AttemptStatus.AWAITING_CUSTOMER, URI.create(paymentUrl));
    }

    @Override
    public StatusResult queryCollection(String sessionId) {
        JsonNode answer =
                call(() -> http.get().uri(paths.session(), sessionId).retrieve().toEntity(String.class));
        if (failed(answer)) {
            throw new ProviderException(FailureCode.UNKNOWN_ERROR, "ArifPay session lookup failed", false);
        }
        return status(answer.path("data"));
    }

    @Override
    public StatusResult findCollection(String nonce) {
        JsonNode answer =
                call(() -> http.get().uri(paths.byNonce(), nonce).retrieve().toEntity(String.class));
        if (failed(answer)) return null;
        JsonNode data = answer.path("data");
        return data.isObject() ? status(data) : null;
    }

    @Override
    public void cancelCollection(String sessionId) {
        call(() -> http.post().uri(paths.cancel(), sessionId).retrieve().toEntity(String.class));
    }

    /** ArifPay does not document signed notifications: never trusted, always confirmed by a lookup. */
    @Override
    public boolean verifySignature(byte[] rawBody, Map<String, String> headers) {
        return false;
    }

    @Override
    public List<NormalizedEvent> parseNotification(byte[] rawBody) {
        JsonNode body = json.readTree(new String(rawBody, StandardCharsets.UTF_8));
        List<NormalizedEvent> events = new ArrayList<>();
        JsonNode node = body.has("data") && body.path("data").isObject() ? body.path("data") : body;
        String sessionId = firstText(node, "sessionId", "SessionId", "uuid");
        String nonce = text(node, "nonce");
        if (sessionId != null || nonce != null) {
            String raw = firstText(node, "transactionStatus", "status");
            events.add(new NormalizedEvent("COLLECTION", "checkout", nonce, sessionId, map(raw)));
        }
        return events;
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        throw new UnsupportedOperationException("ArifPay has no refund API; refunds are paid by hand (docs/07 §3.1)");
    }

    @Override
    public StatusResult queryRefund(String providerReference) {
        throw new UnsupportedOperationException("ArifPay has no refund API");
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private StatusResult status(JsonNode session) {
        JsonNode transaction = session.path("transaction");
        String raw = transaction.isObject() ? text(transaction, "transactionStatus") : null;
        AttemptStatus status = transaction.isObject() ? map(raw) : AttemptStatus.AWAITING_CUSTOMER;
        Long amount = null;
        JsonNode total = session.path("totalAmount");
        try {
            if (total.isNumber()) amount = Money.toMinor(total.decimalValue());
            else if (total.isString()) amount = Money.toMinor(new BigDecimal(total.asString()));
        } catch (ArithmeticException | NumberFormatException e) {
            amount = null; // not an exact birr amount: fulfilment refuses a missing amount
        }
        return new StatusResult(
                status,
                amount,
                "ETB",
                text(session, "nonce"),
                firstText(session, "sessionId", "uuid", "SessionId"),
                status == AttemptStatus.FAILED ? FailureCode.CUSTOMER_DECLINED : null,
                raw);
    }

    /** ArifPay's status words (not fully documented): unknown ones stay in progress and are looked up again. */
    static AttemptStatus map(String raw) {
        if (raw == null) return AttemptStatus.PROCESSING;
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "SUCCESS", "SUCCEEDED", "COMPLETED", "PAID" -> AttemptStatus.SUCCEEDED;
            case "FAILED", "FAIL", "DECLINED" -> AttemptStatus.FAILED;
            case "CANCELLED", "CANCELED" -> AttemptStatus.CANCELLED;
            case "EXPIRED" -> AttemptStatus.EXPIRED;
            case "PENDING", "CREATED", "INITIATED" -> AttemptStatus.AWAITING_CUSTOMER;
            default -> AttemptStatus.PROCESSING;
        };
    }

    /** +251911… → 251911… (ArifPay's phone format). */
    static String localPhone(String e164) {
        return e164.startsWith("+") ? e164.substring(1) : e164;
    }

    private interface Call {
        ResponseEntity<String> run();
    }

    private JsonNode call(Call call) {
        ResponseEntity<String> response;
        try {
            response = call.run();
        } catch (ResourceAccessException e) {
            // Connection refused or a time-out: the request may or may not have arrived. Callers look the collection
            // up by our reference before trying again.
            throw new ProviderException(
                    FailureCode.PROVIDER_UNAVAILABLE, "ArifPay is unreachable: " + e.getMessage(), false);
        }
        if (response.getStatusCode().is5xxServerError()) {
            throw new ProviderException(
                    FailureCode.PROVIDER_UNAVAILABLE,
                    "ArifPay answered " + response.getStatusCode().value(),
                    false);
        }
        if (response.getStatusCode().value() == 401) {
            throw new ProviderException(FailureCode.INVALID_REQUEST, "ArifPay refused the API key", false);
        }
        String body = response.getBody();
        try {
            JsonNode node = json.readTree(body == null ? "{}" : body);
            if (response.getStatusCode().isError() && !node.has("error")) {
                return json.readTree("{\"error\":true,\"msg\":\"HTTP "
                        + response.getStatusCode().value() + "\"}");
            }
            return node;
        } catch (RuntimeException e) {
            throw new ProviderException(
                    FailureCode.UNKNOWN_ERROR, "ArifPay answered something that is not JSON", false);
        }
    }

    /** Some ArifPay failures come back with HTTP 200 and {@code error: true}. */
    private static boolean failed(JsonNode answer) {
        return answer.path("error").asBoolean(false)
                || answer.path("data").isNull()
                || answer.path("data").isMissingNode();
    }

    private static FailureCode failureOf(JsonNode answer) {
        return "03".equals(text(answer, "code")) ? FailureCode.LIMIT_EXCEEDED : FailureCode.INVALID_REQUEST;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() && !value.isNull() ? value.asString() : null;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
