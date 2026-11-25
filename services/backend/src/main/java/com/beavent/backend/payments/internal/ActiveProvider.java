package com.beavent.backend.payments.internal;

import com.beavent.backend.payments.internal.provider.PaymentProviderAdapter;
import java.util.Map;
import java.util.Optional;

/**
 * The deployment's payment provider (docs/07 L1) and every configured adapter. Only the active one takes new
 * payments; the others stay reachable so late notifications and lookups for earlier payments still work after a
 * switch.
 */
public final class ActiveProvider {

    private final PaymentProviderAdapter active;
    private final Map<String, PaymentProviderAdapter> configured;
    private final String environment;

    ActiveProvider(PaymentProviderAdapter active, Map<String, PaymentProviderAdapter> configured, String environment) {
        this.active = active;
        this.configured = Map.copyOf(configured);
        this.environment = environment;
    }

    public PaymentProviderAdapter adapter() {
        return active;
    }

    public String code() {
        return active.providerCode();
    }

    public String collectionMode() {
        return active.collectionMode();
    }

    public String environment() {
        return environment;
    }

    /** A configured adapter by provider code ({@code ARIFPAY}, {@code CHAPA}). */
    public Optional<PaymentProviderAdapter> byCode(String code) {
        return Optional.ofNullable(configured.get(code));
    }
}
