package com.beavent.backend.scheduling.web;

import com.beavent.backend.scheduling.internal.Checkouts;
import com.beavent.backend.tenancy.OrgAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

    record DiscountInput(
            @NotNull @Pattern(regexp = "AMOUNT|PERCENT") String kind,
            @Min(1) long value,
            @NotNull @Size(min = 3, max = 200) String reason) {}

    record ProductInput(
            @NotNull UUID variantId,
            @Min(1) @jakarta.validation.constraints.Max(99) int quantity) {}

    record CheckoutInput(
            List<@Valid ProductInput> products,
            List<@Valid TipInput> tips,
            @Valid DiscountInput discount,
            List<@NotNull @Size(min = 1, max = 40) String> giftCards,
            Boolean useBalance,
            @Min(0) Long cashTenderedMinor) {}

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
        Checkouts.Discount discount = body.discount() == null
                ? null
                : new Checkouts.Discount(
                        body.discount().kind(),
                        body.discount().value(),
                        body.discount().reason());
        return checkouts.checkout(
                OrgAccess.current(),
                appointmentId,
                new Checkouts.Sale(
                        tips,
                        discount,
                        body.giftCards() == null ? List.of() : body.giftCards(),
                        Boolean.TRUE.equals(body.useBalance()),
                        body.cashTenderedMinor(),
                        body.products() == null
                                ? List.of()
                                : body.products().stream()
                                        .map(p -> new Checkouts.ProductItem(p.variantId(), p.quantity()))
                                        .toList()),
                Instant.now());
    }
}
