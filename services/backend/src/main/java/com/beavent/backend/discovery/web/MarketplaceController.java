package com.beavent.backend.discovery.web;

import com.beavent.backend.discovery.Marketplace;
import com.beavent.backend.tenancy.OrgAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** A business's own view of being on the marketplace: where it stands, what it still needs, ask or step back. */
@RestController
class MarketplaceController {

    record Input(@NotNull Boolean requested) {}

    private final Marketplace marketplace;

    MarketplaceController(Marketplace marketplace) {
        this.marketplace = marketplace;
    }

    @GetMapping("/v1/orgs/{orgId}/marketplace")
    Marketplace.Status status(@PathVariable UUID orgId) {
        return marketplace.status(
                OrgAccess.current().require("org.settings.manage").orgId());
    }

    @PutMapping("/v1/orgs/{orgId}/marketplace")
    Marketplace.Status set(@PathVariable UUID orgId, @Valid @RequestBody Input body) {
        OrgAccess access = OrgAccess.current().require("org.settings.manage");
        return body.requested()
                ? marketplace.request(access.orgId(), access.userId())
                : marketplace.withdraw(access.orgId(), access.userId());
    }
}
