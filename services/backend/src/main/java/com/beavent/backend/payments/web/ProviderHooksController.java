package com.beavent.backend.payments.web;

import com.beavent.backend.payments.internal.WebhookInbox;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider notifications (docs/04 §7): {@code POST /v1/hooks/arifpay} and {@code /v1/hooks/chapa}. No sign-in; the
 * raw body is stored before anything else and answered quickly. A replayed delivery is acknowledged without being
 * stored twice.
 */
@RestController
class ProviderHooksController {

    private final WebhookInbox inbox;

    ProviderHooksController(WebhookInbox inbox) {
        this.inbox = inbox;
    }

    @PostMapping(path = "/v1/hooks/{provider:arifpay|chapa}")
    ResponseEntity<Map<String, Object>> receive(
            @PathVariable String provider, @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) headers.put(name, request.getHeader(name));
        WebhookInbox.Outcome outcome =
                inbox.receive(provider.toUpperCase(Locale.ROOT), body == null ? new byte[0] : body, headers);
        return switch (outcome) {
            case STORED, DUPLICATE -> ResponseEntity.ok(Map.of("received", true));
            case REJECTED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("received", false));
            case NOT_JSON -> ResponseEntity.badRequest().body(Map.of("received", false));
            case UNKNOWN_PROVIDER -> ResponseEntity.notFound().build();
        };
    }
}
