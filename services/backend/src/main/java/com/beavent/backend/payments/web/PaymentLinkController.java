package com.beavent.backend.payments.web;

import com.beavent.backend.payments.PaymentLinks;
import com.beavent.backend.payments.Payments;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a payment link's page needs (plan 3.7): no sign-in, the token in the address is the credential. The token only
 * ever pays the one payment it was made for.
 */
@RestController
class PaymentLinkController {

    record InfoBody(
            String state,
            String purpose,
            long amountMinor,
            String currency,
            Instant expiresAt,
            String business,
            String location,
            String reference,
            Instant startsAt,
            String timezone,
            List<String> items) {}

    record StartInput(@Pattern(regexp = "am|en|om") String language) {}

    record StartBody(UUID attemptId, String redirectUrl, Instant expiresAt, long amountMinor, String currency) {}

    private final PaymentLinks links;
    private final String webUrl;

    PaymentLinkController(PaymentLinks links, @Value("${beavent.web.public-url}") String webUrl) {
        this.links = links;
        this.webUrl = webUrl.replaceAll("/+$", "");
    }

    private static InfoBody body(PaymentLinks.Info i) {
        if (i == null) throw new ApiException(ProblemCode.NOT_FOUND, "This link is not valid.");
        return new InfoBody(
                i.state(),
                i.purpose(),
                i.amountMinor(),
                i.currency(),
                i.expiresAt(),
                i.business(),
                i.location(),
                i.reference(),
                i.startsAt(),
                i.timezone(),
                i.items());
    }

    @GetMapping("/v1/public/payment-links/{token}")
    InfoBody info(@PathVariable String token) {
        return body(links.info(token, Instant.now()));
    }

    @PostMapping("/v1/public/payment-links/{token}/start")
    StartBody start(@PathVariable String token, @Valid @RequestBody(required = false) StartInput input) {
        String lang = input == null || input.language() == null ? "en" : input.language();
        String back = webUrl + "/" + lang + "/pay/" + token + "?result=";
        Payments.Started started = links.start(
                token,
                lang,
                new Payments.Urls(
                        URI.create(back + "success"), URI.create(back + "cancel"), URI.create(back + "error")),
                Instant.now());
        return new StartBody(
                started.attemptId(),
                started.redirectUrl() == null ? null : started.redirectUrl().toString(),
                started.expiresAt(),
                started.amountMinor(),
                started.currency());
    }

    @PostMapping("/v1/public/payment-links/{token}/check")
    InfoBody check(@PathVariable String token) {
        return body(links.check(token, Instant.now()));
    }
}
