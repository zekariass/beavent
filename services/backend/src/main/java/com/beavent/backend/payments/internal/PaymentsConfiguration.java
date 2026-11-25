package com.beavent.backend.payments.internal;

import com.beavent.backend.payments.internal.provider.ArifPayAdapter;
import com.beavent.backend.payments.internal.provider.ChapaAdapter;
import com.beavent.backend.payments.internal.provider.PaymentProviderAdapter;
import com.beavent.backend.platform.PublicEndpoints;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentsProperties.class)
class PaymentsConfiguration {

    /**
     * Builds every provider that has credentials and picks the active one. Fails at startup when the active provider
     * is unknown or not configured, so a deployment never runs without a way to take payments it advertises.
     */
    @Bean
    ActiveProvider activePaymentProvider(PaymentsProperties props, JsonMapper json) {
        String active = props.provider().strip().toUpperCase(Locale.ROOT);
        if (!active.equals("ARIFPAY") && !active.equals("CHAPA")) {
            throw new IllegalStateException(
                    "beavent.payments.provider must be ARIFPAY or CHAPA, not " + props.provider());
        }
        String environment = props.environment().strip().toUpperCase(Locale.ROOT);
        if (!environment.equals("SANDBOX") && !environment.equals("LIVE")) {
            throw new IllegalStateException("beavent.payments.environment must be SANDBOX or LIVE");
        }
        if (props.notifyBaseUrl() == null || props.notifyBaseUrl().isBlank()) {
            throw new IllegalStateException("beavent.payments.notify-base-url (PUBLIC_API_URL) is required");
        }
        Map<String, PaymentProviderAdapter> adapters = new LinkedHashMap<>();
        PaymentsProperties.ArifPay a = props.arifpay();
        if (present(a.baseUrl()) && present(a.apiKey())) {
            adapters.put(
                    "ARIFPAY",
                    new ArifPayAdapter(
                            client(props),
                            a.baseUrl(),
                            a.apiKey(),
                            new ArifPayAdapter.Paths(a.createPath(), a.sessionPath(), a.noncePath(), a.cancelPath()),
                            a.paymentMethods(),
                            a.expireZone(),
                            json));
        }
        PaymentsProperties.Chapa c = props.chapa();
        if (present(c.baseUrl()) && present(c.secretKey())) {
            adapters.put("CHAPA", new ChapaAdapter(client(props), c.baseUrl(), c.secretKey(), c.webhookSecret(), json));
        }
        PaymentProviderAdapter chosen = adapters.get(active);
        if (chosen == null) {
            throw new IllegalStateException("The active payment provider " + active
                    + " has no base URL or key (ARIFPAY_BASE_URL/ARIFPAY_API_KEY or CHAPA_BASE_URL/CHAPA_SECRET_KEY)");
        }
        return new ActiveProvider(chosen, adapters, environment);
    }

    @Bean
    PublicEndpoints paymentHooksPublicEndpoints() {
        return () -> List.of("/v1/hooks/arifpay", "/v1/hooks/chapa");
    }

    private static RestClient.Builder client(PaymentsProperties props) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build());
        factory.setReadTimeout(props.readTimeout());
        return RestClient.builder().requestFactory(factory);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
