package com.beavent.backend.retention.web;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.PublicEndpoints;
import com.beavent.backend.platform.RequestContext;
import com.beavent.backend.retention.GiftCards;
import com.beavent.backend.tenancy.OrgAccess;
import com.beavent.backend.tenancy.PublicLocations;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gift cards (plan 3.8): the business sets up and watches them in its console; the public page of a place says what
 * can be bought; a signed-in customer buys one and pays through a link.
 */
@RestController
class GiftCardController {

    record ProgramSetup(GiftCards.Program program) {}

    record ProgramBody(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 8) List<@Min(1) Long> presetsMinor,
            @Min(1) long minMinor,
            @Min(1) long maxMinor,
            @Min(1) @Max(60) Integer validityMonths,
            boolean sellOnline) {}

    record PageInfo(
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
            String nextCursor) {}

    record CardPage(List<GiftCards.CardRow> items, PageInfo page) {}

    record VoidBody(@NotBlank @Size(min = 5, max = 500) String reason) {}

    /** What a place's page offers: nothing when the business does not sell cards online. */
    record Offer(
            boolean available,
            String name,
            String currency,
            List<Long> presetsMinor,
            Long minMinor,
            Long maxMinor,
            Integer validityMonths) {}

    record PurchaseBody(
            @NotNull UUID locationId,
            @Min(1) long amountMinor,
            @Size(max = 120) String recipientName,
            @Size(max = 20) String recipientPhone,
            @Size(max = 200) String recipientEmail,
            @Size(max = 500) String message,
            Instant deliverAt) {}

    record PurchaseResult(UUID cardId, UUID orderId, String payUrl, Instant expiresAt) {}

    private final GiftCards cards;
    private final PublicLocations locations;

    GiftCardController(GiftCards cards, PublicLocations locations) {
        this.cards = cards;
        this.locations = locations;
    }

    @Bean
    PublicEndpoints giftCardPublicEndpoints() {
        return () -> List.of("/v1/public/locations/*/gift-cards");
    }

    // --- the business ----------------------------------------------------------------------------------------------

    @GetMapping("/v1/orgs/{orgId}/gift-cards/program")
    ProgramSetup program(@PathVariable UUID orgId) {
        return new ProgramSetup(
                cards.program(OrgAccess.current().require("catalog.manage").orgId()));
    }

    @PutMapping("/v1/orgs/{orgId}/gift-cards/program")
    GiftCards.Program saveProgram(@PathVariable UUID orgId, @Valid @RequestBody ProgramBody body) {
        UUID org = OrgAccess.current().require("catalog.manage").orgId();
        return cards.saveProgram(
                org,
                new GiftCards.ProgramInput(
                        body.name(),
                        body.presetsMinor() == null ? List.of() : body.presetsMinor(),
                        body.minMinor(),
                        body.maxMinor(),
                        body.validityMonths(),
                        body.sellOnline()));
    }

    @GetMapping("/v1/orgs/{orgId}/gift-cards")
    CardPage list(
            @PathVariable UUID orgId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "25") int limit) {
        UUID org = OrgAccess.current().require("catalog.manage").orgId();
        int size = Math.max(1, Math.min(limit, 100));
        UUID before;
        try {
            before = cursor == null ? null : UUID.fromString(cursor);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The cursor is not valid.");
        }
        List<GiftCards.CardRow> rows = cards.cards(org, before, size + 1);
        boolean more = rows.size() > size;
        List<GiftCards.CardRow> items = more ? rows.subList(0, size) : rows;
        return new CardPage(
                items, new PageInfo(more ? items.get(items.size() - 1).id().toString() : null));
    }

    @PostMapping("/v1/orgs/{orgId}/gift-cards/{cardId}/void")
    ResponseEntity<Void> voidCard(
            @PathVariable UUID orgId, @PathVariable UUID cardId, @Valid @RequestBody VoidBody body) {
        OrgAccess access = OrgAccess.current().require("refunds.create");
        cards.voidCard(access.orgId(), cardId, access.userId(), body.reason().strip());
        return ResponseEntity.noContent().build();
    }

    // --- the customer ----------------------------------------------------------------------------------------------

    @GetMapping("/v1/public/locations/{locationId}/gift-cards")
    Offer offer(@PathVariable UUID locationId) {
        var place = locations.findBookable(locationId).orElse(null);
        GiftCards.Program p = place == null ? null : cards.program(place.orgId());
        if (p == null || !p.sellOnline()) return new Offer(false, null, null, List.of(), null, null, null);
        return new Offer(
                true, p.name(), p.currency(), p.presetsMinor(), p.minMinor(), p.maxMinor(), p.validityMonths());
    }

    @PostMapping("/v1/me/gift-cards/purchases")
    ResponseEntity<PurchaseResult> purchase(@Valid @RequestBody PurchaseBody body, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        var place = locations
                .findBookable(body.locationId())
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "There is no such place."));
        GiftCards.Purchased bought = RequestContext.forOrganization(userId, place.orgId())
                .call(() -> cards.purchase(
                        place.orgId(),
                        userId,
                        new GiftCards.Purchase(
                                body.locationId(),
                                body.amountMinor(),
                                body.recipientName(),
                                body.recipientPhone(),
                                body.recipientEmail(),
                                body.message(),
                                body.deliverAt()),
                        Instant.now()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new PurchaseResult(
                        bought.cardId(),
                        bought.orderId(),
                        bought.link().url(),
                        bought.link().expiresAt()));
    }
}
