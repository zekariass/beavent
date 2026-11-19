package com.beavent.backend.catalog.web;

import com.beavent.backend.catalog.ServicePricing;
import com.beavent.backend.catalog.internal.CatalogBodies;
import com.beavent.backend.catalog.internal.Extras;
import com.beavent.backend.catalog.internal.Prices;
import com.beavent.backend.catalog.internal.Services;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.CursorPage;
import com.beavent.backend.platform.ETags;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Catalogue API (backend/src/main/openapi/catalog). Reads: any member; changes: catalog.manage. */
@RestController
@RequestMapping("/v1/orgs/{orgId}")
class CatalogController {

    record Page<T>(List<T> items, PageInfo page) {}

    record PageInfo(
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
            String nextCursor) {}

    private final Services services;
    private final Prices prices;
    private final Extras extras;
    private final TransactionTemplate transactions;

    CatalogController(Services services, Prices prices, Extras extras, TransactionTemplate transactions) {
        this.services = services;
        this.prices = prices;
        this.extras = extras;
        this.transactions = transactions;
    }

    // --- groups --------------------------------------------------------------------------------------------

    @GetMapping("/service-groups")
    CatalogBodies.Items<CatalogBodies.ServiceGroup> groups(@PathVariable UUID orgId) {
        return new CatalogBodies.Items<>(services.groups());
    }

    @PostMapping("/service-groups")
    ResponseEntity<CatalogBodies.ServiceGroup> createGroup(
            @PathVariable UUID orgId, @Valid @RequestBody CatalogBodies.ServiceGroupInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(services.createGroup(OrgAccess.current(), body));
    }

    @PutMapping("/service-groups/{groupId}")
    CatalogBodies.ServiceGroup updateGroup(
            @PathVariable UUID orgId,
            @PathVariable UUID groupId,
            @Valid @RequestBody CatalogBodies.ServiceGroupInput body) {
        return services.updateGroup(OrgAccess.current(), groupId, body);
    }

    @DeleteMapping("/service-groups/{groupId}")
    ResponseEntity<Void> deleteGroup(@PathVariable UUID orgId, @PathVariable UUID groupId) {
        services.deleteGroup(OrgAccess.current(), groupId);
        return ResponseEntity.noContent().build();
    }

    // --- services ------------------------------------------------------------------------------------------

    @GetMapping("/services")
    Page<CatalogBodies.Service> list(
            @PathVariable UUID orgId,
            @RequestParam(required = false) UUID groupId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        if (status != null && !List.of("ACTIVE", "HIDDEN", "ARCHIVED").contains(status)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Unknown status.");
        }
        CursorPage<CatalogBodies.Service> page = services.list(groupId, locationId, status, cursor, limit(limit));
        return new Page<>(page.items(), new PageInfo(page.nextCursor()));
    }

    @PostMapping("/services")
    ResponseEntity<CatalogBodies.Service> create(
            @PathVariable UUID orgId, @Valid @RequestBody CatalogBodies.ServiceInput body) {
        CatalogBodies.Service service = services.create(OrgAccess.current(), body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ETags.of(service.version()))
                .body(service);
    }

    @GetMapping("/services/{serviceId}")
    ResponseEntity<CatalogBodies.Service> get(@PathVariable UUID orgId, @PathVariable UUID serviceId) {
        CatalogBodies.Service service = services.get(serviceId);
        return ResponseEntity.ok().eTag(ETags.of(service.version())).body(service);
    }

    @PutMapping("/services/{serviceId}")
    ResponseEntity<CatalogBodies.Service> update(
            @PathVariable UUID orgId,
            @PathVariable UUID serviceId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody CatalogBodies.ServiceInput body) {
        CatalogBodies.Service service = services.update(OrgAccess.current(), serviceId, body, ifMatch);
        return ResponseEntity.ok().eTag(ETags.of(service.version())).body(service);
    }

    @DeleteMapping("/services/{serviceId}")
    ResponseEntity<Void> archive(@PathVariable UUID orgId, @PathVariable UUID serviceId) {
        services.archive(OrgAccess.current(), serviceId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/services/{serviceId}/prices")
    CatalogBodies.PriceOverrideList prices(@PathVariable UUID orgId, @PathVariable UUID serviceId) {
        return new CatalogBodies.PriceOverrideList(prices.current(serviceId));
    }

    @PutMapping("/services/{serviceId}/prices")
    CatalogBodies.PriceOverrideList setPrices(
            @PathVariable UUID orgId,
            @PathVariable UUID serviceId,
            @Valid @RequestBody CatalogBodies.PriceOverrideList body) {
        return new CatalogBodies.PriceOverrideList(prices.replace(OrgAccess.current(), serviceId, body.items()));
    }

    @GetMapping("/services/{serviceId}/variants/{variantId}/price")
    ServicePricing.Price resolve(
            @PathVariable UUID orgId,
            @PathVariable UUID serviceId,
            @PathVariable UUID variantId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) UUID staffId) {
        return transactions.execute(status -> {
            ServicePricing.Price price = prices.resolve(variantId, locationId, staffId, Instant.now());
            if (!services.get(serviceId).variants().stream().anyMatch(v -> v.id().equals(variantId))) {
                throw new ApiException(ProblemCode.NOT_FOUND);
            }
            return price;
        });
    }

    // --- add-ons, resources, bundles, gallery ---------------------------------------------------------------

    @GetMapping("/addons")
    CatalogBodies.Items<CatalogBodies.Addon> addons(@PathVariable UUID orgId) {
        return new CatalogBodies.Items<>(extras.addons());
    }

    @PostMapping("/addons")
    ResponseEntity<CatalogBodies.Addon> createAddon(
            @PathVariable UUID orgId, @Valid @RequestBody CatalogBodies.AddonInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(extras.saveAddon(OrgAccess.current(), null, body));
    }

    @PutMapping("/addons/{addonId}")
    CatalogBodies.Addon updateAddon(
            @PathVariable UUID orgId, @PathVariable UUID addonId, @Valid @RequestBody CatalogBodies.AddonInput body) {
        return extras.saveAddon(OrgAccess.current(), addonId, body);
    }

    @DeleteMapping("/addons/{addonId}")
    ResponseEntity<Void> archiveAddon(@PathVariable UUID orgId, @PathVariable UUID addonId) {
        extras.archiveAddon(OrgAccess.current(), addonId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/resources")
    CatalogBodies.Items<CatalogBodies.Resource> resources(
            @PathVariable UUID orgId, @RequestParam(required = false) UUID locationId) {
        return new CatalogBodies.Items<>(extras.resources(locationId));
    }

    @PostMapping("/resources")
    ResponseEntity<CatalogBodies.Resource> createResource(
            @PathVariable UUID orgId, @Valid @RequestBody CatalogBodies.ResourceInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(extras.saveResource(OrgAccess.current(), null, body));
    }

    @PutMapping("/resources/{resourceId}")
    CatalogBodies.Resource updateResource(
            @PathVariable UUID orgId,
            @PathVariable UUID resourceId,
            @Valid @RequestBody CatalogBodies.ResourceInput body) {
        return extras.saveResource(OrgAccess.current(), resourceId, body);
    }

    @GetMapping("/bundles")
    CatalogBodies.Items<CatalogBodies.Bundle> bundles(@PathVariable UUID orgId) {
        return new CatalogBodies.Items<>(extras.bundles());
    }

    @PostMapping("/bundles")
    ResponseEntity<CatalogBodies.Bundle> createBundle(
            @PathVariable UUID orgId, @Valid @RequestBody CatalogBodies.BundleInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(extras.saveBundle(OrgAccess.current(), null, body));
    }

    @PutMapping("/bundles/{bundleId}")
    CatalogBodies.Bundle updateBundle(
            @PathVariable UUID orgId, @PathVariable UUID bundleId, @Valid @RequestBody CatalogBodies.BundleInput body) {
        return extras.saveBundle(OrgAccess.current(), bundleId, body);
    }

    @DeleteMapping("/bundles/{bundleId}")
    ResponseEntity<Void> archiveBundle(@PathVariable UUID orgId, @PathVariable UUID bundleId) {
        extras.archiveBundle(OrgAccess.current(), bundleId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/gallery")
    CatalogBodies.Items<CatalogBodies.GalleryItem> gallery(
            @PathVariable UUID orgId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) UUID serviceId,
            @RequestParam(required = false) UUID staffId) {
        return new CatalogBodies.Items<>(extras.gallery(locationId, serviceId, staffId));
    }

    @PostMapping("/gallery")
    ResponseEntity<CatalogBodies.GalleryItem> addToGallery(
            @PathVariable UUID orgId, @Valid @RequestBody CatalogBodies.GalleryItemInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(extras.addToGallery(OrgAccess.current(), body));
    }

    @DeleteMapping("/gallery/{itemId}")
    ResponseEntity<Void> removeFromGallery(@PathVariable UUID orgId, @PathVariable UUID itemId) {
        extras.removeFromGallery(OrgAccess.current(), itemId);
        return ResponseEntity.noContent().build();
    }

    private static int limit(Integer limit) {
        if (limit == null) {
            return 25;
        }
        if (limit < 1 || limit > 100) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "limit must be between 1 and 100.");
        }
        return limit;
    }
}
