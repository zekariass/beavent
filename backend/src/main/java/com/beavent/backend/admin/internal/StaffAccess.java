package com.beavent.backend.admin.internal;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * A staff member's permissions for this request, each with the markets it applies to ({@link #ALL_MARKETS} = every
 * market).
 */
public record StaffAccess(UUID userId, Map<String, Set<UUID>> marketsByPermission) {

    /** Marker for "all markets" in {@link #marketsByPermission}. */
    public static final UUID ALL_MARKETS = new UUID(0, 0);

    private static final ThreadLocal<StaffAccess> CURRENT = new ThreadLocal<>();

    public static StaffAccess current() {
        StaffAccess access = CURRENT.get();
        if (access == null) {
            throw new IllegalStateException("No staff access bound: not a /v1/admin request");
        }
        return access;
    }

    static AutoCloseable bind(StaffAccess access) {
        CURRENT.set(access);
        return CURRENT::remove;
    }

    public boolean can(String permission) {
        return marketsByPermission.containsKey(permission);
    }

    public boolean can(String permission, UUID marketId) {
        Set<UUID> markets = marketsByPermission.get(permission);
        return markets != null && (markets.contains(ALL_MARKETS) || markets.contains(marketId));
    }

    public boolean global(String permission) {
        Set<UUID> markets = marketsByPermission.get(permission);
        return markets != null && markets.contains(ALL_MARKETS);
    }

    /** @throws ApiException FORBIDDEN unless the permission is held in at least one market */
    public StaffAccess require(String permission) {
        if (!can(permission)) {
            throw new ApiException(ProblemCode.FORBIDDEN, "Your staff role does not allow this (" + permission + ").");
        }
        return this;
    }

    /** Rows outside the staff member's markets are treated as not existing. */
    public void requireInMarket(String permission, UUID marketId) {
        require(permission);
        if (!can(permission, marketId)) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
    }

    /** SQL filter limiting {@code marketColumn} to the markets where the permission is held. */
    public Condition marketFilter(String permission, Field<UUID> marketColumn) {
        Set<UUID> markets = marketsByPermission.get(permission);
        if (markets == null) {
            return DSL.falseCondition();
        }
        return markets.contains(ALL_MARKETS) ? DSL.noCondition() : marketColumn.in(markets);
    }
}
