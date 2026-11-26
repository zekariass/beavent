package com.beavent.backend.scheduling.web;

import com.beavent.backend.scheduling.internal.Checkouts;
import com.beavent.backend.tenancy.OrgAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Checking a visit out at the counter (plan 4.1). */
@RestController
@RequestMapping("/v1/orgs/{orgId}/appointments/{appointmentId}/checkout")
class CheckoutController {

    record TipInput(@NotNull UUID staffId, @Min(0) long amountMinor) {}

    record CheckoutInput(List<@Valid TipInput> tips, @Min(0) Long cashTenderedMinor) {}

    private final Checkouts checkouts;

    CheckoutController(Checkouts checkouts) {
        this.checkouts = checkouts;
    }

    @GetMapping
    Checkouts.Bill bill(@PathVariable UUID orgId, @PathVariable UUID appointmentId) {
        return checkouts.bill(OrgAccess.current(), appointmentId);
    }

    @PostMapping
    Checkouts.Done checkout(
            @PathVariable UUID orgId, @PathVariable UUID appointmentId, @Valid @RequestBody CheckoutInput body) {
        List<Checkouts.Tip> tips = body.tips() == null
                ? List.of()
                : body.tips().stream()
                        .map(t -> new Checkouts.Tip(t.staffId(), t.amountMinor()))
                        .toList();
        return checkouts.checkout(OrgAccess.current(), appointmentId, tips, body.cashTenderedMinor(), Instant.now());
    }
}
