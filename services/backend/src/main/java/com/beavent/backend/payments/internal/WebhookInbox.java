package com.beavent.backend.payments.internal;

import static com.beavent.db.payments.Tables.WEBHOOK_EVENTS;

import com.beavent.backend.payments.internal.provider.NormalizedEvent;
import com.beavent.backend.payments.internal.provider.PaymentProviderAdapter;
import com.beavent.backend.platform.SystemDb;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Provider notifications land here first (docs/04 §7): the raw body is checked, stored in
 * {@code payments.webhook_events} and deduplicated before anything acts on it. Signed providers (Chapa) with a wrong
 * signature are refused; unsigned ones (ArifPay) are stored as hints that the processing step confirms by a lookup.
 */
@Component
public class WebhookInbox {

    public enum Outcome {
        STORED,
        DUPLICATE,
        REJECTED,
        UNKNOWN_PROVIDER,
        NOT_JSON
    }

    /** Headers kept with the event; signature values are replaced by "present". */
    private static final Set<String> KEPT = Set.of("content-type", "user-agent", "x-forwarded-for");

    private static final Set<String> SIGNATURES = Set.of("x-chapa-signature", "chapa-signature");

    private final DSLContext system;
    private final ActiveProvider providers;
    private final ProviderAccounts accounts;
    private final JsonMapper json;

    WebhookInbox(@SystemDb DSLContext system, ActiveProvider providers, ProviderAccounts accounts, JsonMapper json) {
        this.system = system;
        this.providers = providers;
        this.accounts = accounts;
        this.json = json;
    }

    public Outcome receive(String providerCode, byte[] rawBody, Map<String, String> headers) {
        PaymentProviderAdapter adapter = providers.byCode(providerCode).orElse(null);
        if (adapter == null) return Outcome.UNKNOWN_PROVIDER;
        JsonNode payload;
        try {
            payload = json.readTree(new String(rawBody, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return Outcome.NOT_JSON;
        }
        if (!payload.isObject()) return Outcome.NOT_JSON;
        boolean signed = adapter.verifySignature(rawBody, headers);
        if (adapter.notificationsAreAuthoritative() && !signed) return Outcome.REJECTED;
        List<NormalizedEvent> events;
        try {
            events = adapter.parseNotification(rawBody);
        } catch (RuntimeException e) {
            events = List.of();
        }
        UUID account = accounts.platformAccount(providerCode).orElse(null);
        int inserted = system.insertInto(WEBHOOK_EVENTS)
                .set(WEBHOOK_EVENTS.PROVIDER_CODE, providerCode)
                .set(WEBHOOK_EVENTS.PROVIDER_ACCOUNT_ID, account)
                .set(WEBHOOK_EVENTS.DEDUPE_KEY, sha256(rawBody))
                .set(
                        WEBHOOK_EVENTS.EVENT_TYPE,
                        events.isEmpty() ? null : events.getFirst().eventType())
                .set(WEBHOOK_EVENTS.SIGNATURE_VALID, signed)
                .set(WEBHOOK_EVENTS.PAYLOAD, JSONB.valueOf(json.writeValueAsString(payload)))
                .set(WEBHOOK_EVENTS.HEADERS, JSONB.valueOf(json.writeValueAsString(redact(headers))))
                .set(WEBHOOK_EVENTS.STATUS, events.isEmpty() ? "IGNORED" : "RECEIVED")
                .onConflictDoNothing()
                .execute();
        return inserted == 1 ? Outcome.STORED : Outcome.DUPLICATE;
    }

    /** Providers without event ids: the body's hash identifies a delivery, so an exact replay is stored once. */
    static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> redact(Map<String, String> headers) {
        Map<String, String> kept = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            String key = name.toLowerCase(Locale.ROOT);
            if (KEPT.contains(key)) kept.put(key, value);
            else if (SIGNATURES.contains(key)) kept.put(key, "present");
        });
        return kept;
    }
}
