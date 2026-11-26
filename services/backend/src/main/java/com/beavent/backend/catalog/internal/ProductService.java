package com.beavent.backend.catalog.internal;

import static com.beavent.db.catalog.Tables.PRODUCTS;
import static com.beavent.db.catalog.Tables.PRODUCT_VARIANTS;
import static com.beavent.db.stock.Tables.LEVELS;

import com.beavent.backend.catalog.Products;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.ProblemCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** {@link Products}: the retail catalogue; stock is kept by the stock module and only read here. */
@Service
class ProductService implements Products {

    private static final int MAX_VARIANTS = 50;

    private final DSLContext dsl;
    private final Localized localized;
    private final Currencies currencies;
    private final JsonMapper json;

    ProductService(DSLContext dsl, Localized localized, Currencies currencies, JsonMapper json) {
        this.dsl = dsl;
        this.localized = localized;
        this.currencies = currencies;
        this.json = json;
    }

    @Override
    public List<Product> list(UUID orgId, UUID locationId, boolean includeArchived, boolean seeCost) {
        var products = dsl.selectFrom(PRODUCTS)
                .where(PRODUCTS.ORG_ID.eq(orgId))
                .and(includeArchived ? org.jooq.impl.DSL.trueCondition() : PRODUCTS.STATUS.eq("ACTIVE"))
                .orderBy(PRODUCTS.CREATED_AT, PRODUCTS.ID)
                .fetch();
        if (products.isEmpty()) return List.of();
        Map<UUID, List<com.beavent.db.catalog.tables.records.ProductVariantsRecord>> variants = dsl
                .selectFrom(PRODUCT_VARIANTS)
                .where(PRODUCT_VARIANTS.ORG_ID.eq(orgId))
                .and(PRODUCT_VARIANTS.PRODUCT_ID.in(products.map(p -> p.getId())))
                .and(includeArchived ? org.jooq.impl.DSL.trueCondition() : PRODUCT_VARIANTS.STATUS.eq("ACTIVE"))
                .orderBy(PRODUCT_VARIANTS.CREATED_AT, PRODUCT_VARIANTS.ID)
                .fetch()
                .stream()
                .collect(Collectors.groupingBy(v -> v.getProductId()));
        Map<UUID, Long> stock = new HashMap<>();
        if (locationId != null) {
            dsl.select(LEVELS.PRODUCT_VARIANT_ID, LEVELS.ON_HAND)
                    .from(LEVELS)
                    .where(LEVELS.ORG_ID.eq(orgId), LEVELS.LOCATION_ID.eq(locationId))
                    .forEach(r -> stock.put(r.value1(), r.value2().longValue()));
        }
        List<Product> out = new ArrayList<>();
        for (var p : products) {
            List<Variant> vs = variants.getOrDefault(p.getId(), List.of()).stream()
                    .map(v -> variant(
                            v,
                            p.getTrackInventory() && locationId != null ? stock.getOrDefault(v.getId(), 0L) : null,
                            seeCost))
                    .toList();
            out.add(product(p, vs));
        }
        return out;
    }

    @Override
    public Product create(UUID orgId, Input input) {
        validate(input, true);
        UUID id = Ids.newId();
        dsl.insertInto(PRODUCTS)
                .set(PRODUCTS.ID, id)
                .set(PRODUCTS.ORG_ID, orgId)
                .set(PRODUCTS.NAME_I18N, localized.name(input.name(), "name", 120))
                .set(PRODUCTS.BRAND, blankToNull(input.brand()))
                .set(PRODUCTS.TRACK_INVENTORY, input.trackInventory())
                .execute();
        String currency = currencies.priceCurrency(orgId, null);
        for (VariantInput v : input.variants()) insertVariant(orgId, id, v, currency);
        return one(orgId, id);
    }

    @Override
    public Product update(UUID orgId, UUID productId, Input input) {
        validate(input, false);
        var product = dsl.selectFrom(PRODUCTS)
                .where(PRODUCTS.ID.eq(productId), PRODUCTS.ORG_ID.eq(orgId))
                .forUpdate()
                .fetchOne();
        if (product == null) throw new ApiException(ProblemCode.NOT_FOUND, "There is no such product.");
        dsl.update(PRODUCTS)
                .set(PRODUCTS.NAME_I18N, localized.name(input.name(), "name", 120))
                .set(PRODUCTS.BRAND, blankToNull(input.brand()))
                .set(PRODUCTS.TRACK_INVENTORY, input.trackInventory())
                .set(PRODUCTS.STATUS, input.status() == null ? product.getStatus() : input.status())
                .set(PRODUCTS.UPDATED_AT, java.time.OffsetDateTime.now())
                .where(PRODUCTS.ID.eq(productId))
                .execute();
        String currency = currencies.priceCurrency(orgId, null);
        Set<UUID> kept = new HashSet<>();
        for (VariantInput v : input.variants()) {
            if (v.id() == null) {
                kept.add(insertVariant(orgId, productId, v, currency));
            } else {
                int n = dsl.update(PRODUCT_VARIANTS)
                        .set(PRODUCT_VARIANTS.OPTION_VALUES, label(v.label()))
                        .set(PRODUCT_VARIANTS.SKU, blankToNull(v.sku()))
                        .set(PRODUCT_VARIANTS.BARCODE, blankToNull(v.barcode()))
                        .set(PRODUCT_VARIANTS.PRICE_MINOR, v.priceMinor())
                        .set(PRODUCT_VARIANTS.COST_MINOR, v.costMinor())
                        .set(PRODUCT_VARIANTS.STATUS, "ACTIVE")
                        .set(PRODUCT_VARIANTS.UPDATED_AT, java.time.OffsetDateTime.now())
                        .where(PRODUCT_VARIANTS.ID.eq(v.id()), PRODUCT_VARIANTS.PRODUCT_ID.eq(productId))
                        .execute();
                if (n == 0) throw new ApiException(ProblemCode.NOT_FOUND, "There is no such variant.");
                kept.add(v.id());
            }
        }
        dsl.update(PRODUCT_VARIANTS)
                .set(PRODUCT_VARIANTS.STATUS, "ARCHIVED")
                .where(PRODUCT_VARIANTS.PRODUCT_ID.eq(productId), PRODUCT_VARIANTS.ID.notIn(kept))
                .execute();
        return one(orgId, productId);
    }

    @Override
    public List<Sellable> sellable(UUID orgId, List<UUID> variantIds, String language) {
        List<Sellable> out = new ArrayList<>();
        for (UUID id : variantIds) {
            var row = dsl.select(
                            PRODUCT_VARIANTS.ID,
                            PRODUCTS.ID,
                            PRODUCTS.NAME_I18N,
                            PRODUCT_VARIANTS.OPTION_VALUES,
                            PRODUCT_VARIANTS.PRICE_MINOR,
                            PRODUCT_VARIANTS.CURRENCY_CODE,
                            PRODUCTS.TRACK_INVENTORY)
                    .from(PRODUCT_VARIANTS)
                    .join(PRODUCTS)
                    .on(PRODUCTS.ID.eq(PRODUCT_VARIANTS.PRODUCT_ID))
                    .where(PRODUCT_VARIANTS.ID.eq(id), PRODUCT_VARIANTS.ORG_ID.eq(orgId))
                    .and(PRODUCT_VARIANTS.STATUS.eq("ACTIVE"))
                    .and(PRODUCTS.STATUS.eq("ACTIVE"))
                    .fetchOne();
            if (row == null) throw new ApiException(ProblemCode.NOT_FOUND, "There is no such product.");
            Map<String, String> names = localized.read(row.value3());
            String name = names.getOrDefault(
                    language,
                    names.getOrDefault("en", names.values().stream().findFirst().orElse("")));
            String label = labelOf(row.value4());
            out.add(new Sellable(
                    row.value1(),
                    row.value2(),
                    label.isBlank() ? name : name + " · " + label,
                    row.value5(),
                    row.value6().trim(),
                    row.value7()));
        }
        return out;
    }

    // --- internals ---------------------------------------------------------------------------------------------------

    private Product one(UUID orgId, UUID productId) {
        return list(orgId, null, true, true).stream()
                .filter(p -> p.id().equals(productId))
                .findFirst()
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "There is no such product."));
    }

    private UUID insertVariant(UUID orgId, UUID productId, VariantInput v, String currency) {
        UUID id = Ids.newId();
        dsl.insertInto(PRODUCT_VARIANTS)
                .set(PRODUCT_VARIANTS.ID, id)
                .set(PRODUCT_VARIANTS.ORG_ID, orgId)
                .set(PRODUCT_VARIANTS.PRODUCT_ID, productId)
                .set(PRODUCT_VARIANTS.SKU, blankToNull(v.sku()))
                .set(PRODUCT_VARIANTS.BARCODE, blankToNull(v.barcode()))
                .set(PRODUCT_VARIANTS.OPTION_VALUES, label(v.label()))
                .set(PRODUCT_VARIANTS.PRICE_MINOR, v.priceMinor())
                .set(PRODUCT_VARIANTS.CURRENCY_CODE, currency)
                .set(PRODUCT_VARIANTS.COST_MINOR, v.costMinor())
                .execute();
        return id;
    }

    private void validate(Input input, boolean creating) {
        if (input.variants() == null
                || input.variants().isEmpty()
                || input.variants().size() > MAX_VARIANTS) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED, "A product has between 1 and " + MAX_VARIANTS + " variants.");
        }
        if (input.status() != null
                && !input.status().equals("ACTIVE")
                && !input.status().equals("ARCHIVED")) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "A product is active or archived.");
        }
        for (VariantInput v : input.variants()) {
            if (v.priceMinor() < 0 || (v.costMinor() != null && v.costMinor() < 0)) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Prices are not negative.");
            }
            if (v.label() != null && v.label().length() > 60) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "A variant's name is at most 60 characters.");
            }
        }
        if (input.variants().size() > 1
                && input.variants().stream()
                        .anyMatch(v -> v.label() == null || v.label().isBlank())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Variants need a name to tell them apart.");
        }
        Set<String> skus = new HashSet<>();
        for (VariantInput v : input.variants()) {
            if (v.sku() != null && !v.sku().isBlank() && !skus.add(v.sku().strip())) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Two variants have the same SKU.");
            }
        }
    }

    private JSONB label(String label) {
        return JSONB.valueOf(
                json.writeValueAsString(label == null || label.isBlank() ? Map.of() : Map.of("label", label.strip())));
    }

    private String labelOf(JSONB values) {
        if (values == null) return "";
        return json.readTree(values.data()).path("label").asString("");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private Product product(com.beavent.db.catalog.tables.records.ProductsRecord p, List<Variant> variants) {
        return new Product(
                p.getId(),
                localized.read(p.getNameI18n()),
                p.getBrand(),
                p.getTrackInventory(),
                p.getStatus(),
                variants);
    }

    private Variant variant(
            com.beavent.db.catalog.tables.records.ProductVariantsRecord v, Long onHand, boolean seeCost) {
        return new Variant(
                v.getId(),
                labelOf(v.getOptionValues()),
                v.getSku(),
                v.getBarcode(),
                v.getPriceMinor(),
                seeCost ? v.getCostMinor() : null,
                v.getCurrencyCode().trim(),
                v.getStatus(),
                onHand);
    }
}
