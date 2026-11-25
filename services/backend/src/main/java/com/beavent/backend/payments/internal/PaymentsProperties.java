package com.beavent.backend.payments.internal;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code beavent.payments.*}: the active provider (docs/07 L1) and each provider's connection. Keys are read from the
 * environment and never stored in the database ({@code provider_accounts.credentials_secret_ref} names the variable).
 *
 * @param provider {@code ARIFPAY} (default) or {@code CHAPA}
 * @param environment {@code SANDBOX} or {@code LIVE} (a live capability needs {@code verified_at})
 * @param notifyBaseUrl the API's public base URL providers call back, without a trailing slash
 */
@ConfigurationProperties("beavent.payments")
public record PaymentsProperties(
        @DefaultValue("ARIFPAY") String provider,
        @DefaultValue("SANDBOX") String environment,
        /** Open online payments only to verified businesses with a verified payout account (docs/07 3.4). */
        @DefaultValue("true") boolean requireSellerReadiness,
        String notifyBaseUrl,
        /** How long a link for a booking's balance stays open (a deposit link lasts as long as the booking is held). */
        @DefaultValue("7d") Duration balanceLinkTtl,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("20s") Duration readTimeout,
        @DefaultValue ArifPay arifpay,
        @DefaultValue Chapa chapa) {

    /**
     * ArifPay's gateway. Paths default to the official production collection; the sandbox uses
     * {@code /api/sandbox/c2b/session} for creating sessions (docs/07 §1.2).
     */
    public record ArifPay(
            String baseUrl,
            String apiKey,
            @DefaultValue("/api/checkout/session") String createPath,
            @DefaultValue("/api/checkout/session/{id}") String sessionPath,

            @DefaultValue("/api/checkout/getSessionByNonce/{nonce}")
            String noncePath,

            @DefaultValue("/api/checkout/session/cancel/{id}")
            String cancelPath,
            /** Sent as {@code paymentMethods}; empty lets ArifPay use the merchant account's methods (production). */
            @DefaultValue List<String> paymentMethods,
            @DefaultValue("Africa/Addis_Ababa") ZoneId expireZone) {}

    public record Chapa(String baseUrl, String secretKey, String webhookSecret) {}
}
