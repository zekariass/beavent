package com.beavent.backend.catalog.web;

import com.beavent.backend.catalog.Products;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Retail products (plan 4.5). Seeing them needs {@code pos.sell}, {@code catalog.manage} or {@code inventory.manage};
 * changing them {@code catalog.manage}; cost prices are shown to those who manage the catalogue or the stock.
 */
@RestController
@RequestMapping("/v1/orgs/{orgId}/products")
class ProductController {

    record VariantBody(
            UUID id,
            @Size(max = 60) String label,
            @Size(max = 60) String sku,
            @Size(max = 60) String barcode,
            @Min(0) long priceMinor,
            @Min(0) Long costMinor) {}

    record ProductBody(
            @NotNull @NotEmpty Map<String, String> name,
            @Size(max = 80) String brand,
            boolean trackInventory,
            String status,
            @NotNull @NotEmpty List<@Valid VariantBody> variants) {}

    record ItemsBody(List<Products.Product> items) {}

    private final Products products;
    private final TransactionTemplate transactions;

    ProductController(Products products, TransactionTemplate transactions) {
        this.products = products;
        this.transactions = transactions;
    }

    @GetMapping
    ItemsBody list(
            @PathVariable UUID orgId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        OrgAccess access = OrgAccess.current();
        if (!access.can("pos.sell") && !access.can("catalog.manage") && !access.can("inventory.manage")) {
            access.require("catalog.manage");
        }
        if (locationId != null && !access.canAccessLocation(locationId)) {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such location.");
        }
        boolean seeCost = access.can("catalog.manage") || access.can("inventory.manage");
        return new ItemsBody(
                transactions.execute(status -> products.list(orgId, locationId, includeArchived && seeCost, seeCost)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Products.Product create(@PathVariable UUID orgId, @Valid @RequestBody ProductBody body) {
        OrgAccess.current().require("catalog.manage");
        return transactions.execute(status -> products.create(orgId, input(body)));
    }

    @PutMapping("/{productId}")
    Products.Product update(
            @PathVariable UUID orgId, @PathVariable UUID productId, @Valid @RequestBody ProductBody body) {
        OrgAccess.current().require("catalog.manage");
        return transactions.execute(status -> products.update(orgId, productId, input(body)));
    }

    private static Products.Input input(ProductBody b) {
        return new Products.Input(
                b.name(),
                b.brand(),
                b.trackInventory(),
                b.status(),
                b.variants().stream()
                        .map(v -> new Products.VariantInput(
                                v.id(), v.label(), v.sku(), v.barcode(), v.priceMinor(), v.costMinor()))
                        .toList());
    }
}
