package com.beavent.backend.stock.internal;

import static com.beavent.db.catalog.Tables.PRODUCTS;
import static com.beavent.db.catalog.Tables.PRODUCT_VARIANTS;
import static com.beavent.db.stock.Tables.LEVELS;
import static com.beavent.db.stock.Tables.MOVEMENTS;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.stock.Stock;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/** {@link Stock}: movements are inserted; the level follows from the database's trigger. */
@Service
class StockService implements Stock {

    private final DSLContext dsl;

    StockService(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void receive(
            UUID orgId,
            UUID locationId,
            UUID variantId,
            long quantity,
            Long unitCostMinor,
            String reason,
            UUID userId) {
        if (quantity <= 0) throw new ApiException(ProblemCode.VALIDATION_FAILED, "Say how many arrived.");
        requireReason(reason);
        requireTracked(orgId, variantId);
        insert(orgId, locationId, variantId, "RECEIPT", quantity, unitCostMinor, null, reason, userId);
    }

    @Override
    public void adjust(
            UUID orgId, UUID locationId, UUID variantId, long change, String kind, String reason, UUID userId) {
        if (change == 0) throw new ApiException(ProblemCode.VALIDATION_FAILED, "Say how many to add or take off.");
        if (!kind.equals("ADJUSTMENT") && !kind.equals("DAMAGE")) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "An adjustment or damage.");
        }
        if (kind.equals("DAMAGE") && change > 0) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Damage takes goods off.");
        }
        requireReason(reason);
        requireTracked(orgId, variantId);
        if (change < 0 && available(orgId, locationId, variantId) < -change) {
            throw new ApiException(ProblemCode.CONFLICT, "There are not that many in stock.");
        }
        insert(orgId, locationId, variantId, kind, change, null, null, reason, userId);
    }

    @Override
    public void sell(UUID orgId, UUID locationId, UUID variantId, long quantity, UUID orderLineId, UUID userId) {
        if (quantity <= 0) throw new IllegalArgumentException("A sale is of at least one");
        if (!tracked(orgId, variantId)) return;
        long have = available(orgId, locationId, variantId);
        if (have < quantity) {
            throw new ApiException(ProblemCode.CONFLICT, "Only " + have + " in stock, " + quantity + " asked for.");
        }
        insert(orgId, locationId, variantId, "SALE", -quantity, null, orderLineId, null, userId);
    }

    @Override
    public void restore(UUID orgId, UUID locationId, UUID variantId, long quantity, UUID orderLineId, UUID userId) {
        if (quantity <= 0) throw new IllegalArgumentException("A return is of at least one");
        if (!tracked(orgId, variantId)) return;
        insert(
                orgId,
                locationId,
                variantId,
                "SALE_RETURN",
                quantity,
                null,
                orderLineId,
                "The sale was cancelled",
                userId);
    }

    @Override
    public long available(UUID orgId, UUID locationId, UUID variantId) {
        if (!tracked(orgId, variantId)) return Long.MAX_VALUE;
        BigDecimal onHand = dsl.select(LEVELS.ON_HAND)
                .from(LEVELS)
                .where(
                        LEVELS.ORG_ID.eq(orgId),
                        LEVELS.LOCATION_ID.eq(locationId),
                        LEVELS.PRODUCT_VARIANT_ID.eq(variantId))
                .fetchOne(LEVELS.ON_HAND);
        return onHand == null ? 0 : onHand.longValue();
    }

    @Override
    public List<Movement> movements(UUID orgId, UUID locationId, UUID variantId, int limit) {
        return dsl.selectFrom(MOVEMENTS)
                .where(MOVEMENTS.ORG_ID.eq(orgId), MOVEMENTS.LOCATION_ID.eq(locationId))
                .and(MOVEMENTS.PRODUCT_VARIANT_ID.eq(variantId))
                .orderBy(MOVEMENTS.OCCURRED_AT.desc(), MOVEMENTS.ID.desc())
                .limit(limit)
                .fetch(m -> new Movement(
                        m.getId(),
                        m.getProductVariantId(),
                        m.getKind(),
                        m.getQuantity().longValue(),
                        m.getUnitCostMinor(),
                        m.getReason(),
                        m.getOccurredAt().toInstant(),
                        m.getActorUserId()));
    }

    // --- internals ---------------------------------------------------------------------------------------------------

    private void insert(
            UUID orgId,
            UUID locationId,
            UUID variantId,
            String kind,
            long quantity,
            Long unitCost,
            UUID orderLineId,
            String reason,
            UUID userId) {
        dsl.insertInto(MOVEMENTS)
                .set(MOVEMENTS.ID, Ids.newId())
                .set(MOVEMENTS.ORG_ID, orgId)
                .set(MOVEMENTS.LOCATION_ID, locationId)
                .set(MOVEMENTS.PRODUCT_VARIANT_ID, variantId)
                .set(MOVEMENTS.KIND, kind)
                .set(MOVEMENTS.QUANTITY, BigDecimal.valueOf(quantity))
                .set(MOVEMENTS.UNIT_COST_MINOR, unitCost)
                .set(MOVEMENTS.ORDER_LINE_ID, orderLineId)
                .set(MOVEMENTS.REASON, reason == null ? null : reason.strip())
                .set(MOVEMENTS.ACTOR_USER_ID, userId)
                .execute();
    }

    private boolean tracked(UUID orgId, UUID variantId) {
        Boolean tracked = dsl.select(PRODUCTS.TRACK_INVENTORY)
                .from(PRODUCT_VARIANTS)
                .join(PRODUCTS)
                .on(PRODUCTS.ID.eq(PRODUCT_VARIANTS.PRODUCT_ID))
                .where(PRODUCT_VARIANTS.ID.eq(variantId), PRODUCT_VARIANTS.ORG_ID.eq(orgId))
                .fetchOne(PRODUCTS.TRACK_INVENTORY);
        if (tracked == null) throw new ApiException(ProblemCode.NOT_FOUND, "There is no such product.");
        return tracked;
    }

    private void requireTracked(UUID orgId, UUID variantId) {
        if (!tracked(orgId, variantId)) {
            throw new ApiException(ProblemCode.CONFLICT, "Stock is not kept for this product.");
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.strip().length() < 3) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Say why.");
        }
    }
}
