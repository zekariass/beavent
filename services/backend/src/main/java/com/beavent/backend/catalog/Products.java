package com.beavent.backend.catalog;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Retail products a business sells at the counter (plan 4.5): a product with one or more variants (a size, a colour),
 * each with its own price and SKU. Call inside a transaction with the organization's context; the caller checks the
 * permission.
 */
public interface Products {

    /**
     * @param label what tells the variant apart ("250 ml"); empty for a product with a single variant
     * @param costMinor what it costs the business; only shown to those who manage the catalogue or the stock
     * @param onHand how many are in stock at the location asked about; null when stock is not kept or no location was
     *     asked about
     */
    record Variant(
            UUID id,
            String label,
            String sku,
            String barcode,
            long priceMinor,
            Long costMinor,
            String currency,
            String status,
            Long onHand) {}

    record Product(
            UUID id,
            Map<String, String> name,
            String brand,
            boolean trackInventory,
            String status,
            List<Variant> variants) {}

    /** A variant to keep; without an id it is a new one, and one left out is archived. */
    record VariantInput(UUID id, String label, String sku, String barcode, long priceMinor, Long costMinor) {}

    record Input(
            Map<String, String> name,
            String brand,
            boolean trackInventory,
            String status,
            List<VariantInput> variants) {}

    /**
     * @param locationId adds each variant's stock there; null for none
     * @param seeCost whether the cost prices are shown
     */
    List<Product> list(UUID orgId, UUID locationId, boolean includeArchived, boolean seeCost);

    Product create(UUID orgId, Input input);

    Product update(UUID orgId, UUID productId, Input input);

    /** What the counter needs to sell a variant: its price and name now. */
    record Sellable(UUID variantId, UUID productId, String name, long priceMinor, String currency, boolean tracked) {}

    /**
     * The variants as they are sold, in the given language order ({@code NOT_FOUND} for one that is unknown, archived or
     * not the business's).
     */
    List<Sellable> sellable(UUID orgId, List<UUID> variantIds, String language);
}
