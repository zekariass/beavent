package com.beavent.backend.scheduling.web;

import com.beavent.backend.scheduling.internal.Checkouts;
import com.beavent.backend.scheduling.internal.Sales;
import com.beavent.backend.tenancy.OrgAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Retail sales at the counter with no visit (plan 4.5). */
@RestController
@RequestMapping("/v1/orgs/{orgId}/sales")
class SalesController {

    record ItemInput(
            @NotNull UUID variantId, @Min(1) @Max(99) int quantity) {}

    record DiscountInput(
            @NotNull @Pattern(regexp = "AMOUNT|PERCENT") String kind,
            @Min(1) long value,
            @NotNull @Size(min = 3, max = 200) String reason) {}

    record StartInput(
            @NotNull UUID locationId,
            UUID customerId,
            @NotNull @NotEmpty List<@Valid ItemInput> items,
            @Valid DiscountInput discount) {}

    record PayInput(
            List<@NotNull @Size(min = 1, max = 40) String> giftCards,
            Boolean useBalance,
            @Min(0) Long cashTenderedMinor) {}

    private final Sales sales;

    SalesController(Sales sales) {
        this.sales = sales;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Sales.SaleBill start(@PathVariable UUID orgId, @Valid @RequestBody StartInput body) {
        Checkouts.Discount discount = body.discount() == null
                ? null
                : new Checkouts.Discount(
                        body.discount().kind(),
                        body.discount().value(),
                        body.discount().reason());
        return sales.start(
                OrgAccess.current(),
                body.locationId(),
                body.customerId(),
                body.items().stream()
                        .map(i -> new Checkouts.ProductItem(i.variantId(), i.quantity()))
                        .toList(),
                discount,
                Instant.now());
    }

    @GetMapping("/{orderId}")
    Sales.SaleBill bill(@PathVariable UUID orgId, @PathVariable UUID orderId) {
        return sales.bill(OrgAccess.current(), orderId);
    }

    @PostMapping("/{orderId}/payment")
    Checkouts.Done pay(@PathVariable UUID orgId, @PathVariable UUID orderId, @Valid @RequestBody PayInput body) {
        return sales.pay(
                OrgAccess.current(),
                orderId,
                body.giftCards() == null ? List.of() : body.giftCards(),
                Boolean.TRUE.equals(body.useBalance()),
                body.cashTenderedMinor(),
                Instant.now());
    }

    @PostMapping("/{orderId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable UUID orgId, @PathVariable UUID orderId) {
        sales.cancel(OrgAccess.current(), orderId, Instant.now());
    }
}
