package com.beavent.backend.admin.internal;

import static com.beavent.db.platform.Tables.FEATURE_FLAGS;
import static com.beavent.db.platform.Tables.FEATURE_FLAG_OVERRIDES;
import static com.beavent.db.ref.Tables.MARKETS;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.SystemDb;
import com.beavent.db.platform.tables.records.FeatureFlagsRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Feature flags: every staff member can read them; changing the default needs global market management. */
@Component
public class Flags {

    static final String MANAGE = "admin.markets.manage";

    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final Audit audit;

    Flags(@SystemDb DSLContext system, @SystemDb TransactionTemplate transactions, Audit audit) {
        this.system = system;
        this.transactions = transactions;
        this.audit = audit;
    }

    public List<AdminBodies.Flag> list() {
        Map<String, List<AdminBodies.FlagOverride>> overrides = system.select(
                        FEATURE_FLAG_OVERRIDES.asterisk(), MARKETS.CODE)
                .from(FEATURE_FLAG_OVERRIDES)
                .leftJoin(MARKETS)
                .on(MARKETS.ID.eq(FEATURE_FLAG_OVERRIDES.MARKET_ID))
                .fetchGroups(
                        FEATURE_FLAG_OVERRIDES.FLAG_CODE,
                        r -> new AdminBodies.FlagOverride(
                                r.get(MARKETS.CODE),
                                r.get(FEATURE_FLAG_OVERRIDES.ORG_ID),
                                r.get(FEATURE_FLAG_OVERRIDES.USER_ID),
                                r.get(FEATURE_FLAG_OVERRIDES.ENABLED)));
        return system.selectFrom(FEATURE_FLAGS)
                .orderBy(FEATURE_FLAGS.CODE)
                .fetch(f -> view(f, overrides.getOrDefault(f.getCode(), List.of())));
    }

    public AdminBodies.Flag update(StaffAccess staff, String code, AdminBodies.FlagUpdate update) {
        if (!staff.global(MANAGE)) {
            throw new ApiException(
                    ProblemCode.FORBIDDEN, "Changing feature flags needs market management for all markets.");
        }
        transactions.executeWithoutResult(status -> {
            FeatureFlagsRecord flag = system.selectFrom(FEATURE_FLAGS)
                    .where(FEATURE_FLAGS.CODE.eq(code))
                    .forUpdate()
                    .fetchOne();
            if (flag == null) {
                throw new ApiException(ProblemCode.NOT_FOUND);
            }
            boolean before = flag.getDefaultEnabled();
            flag.setDefaultEnabled(update.defaultEnabled());
            flag.setUpdatedAt(OffsetDateTime.now());
            flag.store();
            audit.record(new Audit.Entry(
                    Audit.Actor.PLATFORM_STAFF,
                    staff.userId(),
                    null,
                    "feature_flag.updated",
                    "platform.feature_flag",
                    code,
                    Map.of("defaultEnabled", before),
                    Map.of("defaultEnabled", update.defaultEnabled()),
                    update.reason().strip()));
        });
        return list().stream().filter(f -> f.code().equals(code)).findFirst().orElseThrow();
    }

    private static AdminBodies.Flag view(FeatureFlagsRecord flag, List<AdminBodies.FlagOverride> overrides) {
        return new AdminBodies.Flag(
                flag.getCode(),
                flag.getDescription(),
                flag.getDefaultEnabled(),
                overrides,
                flag.getUpdatedAt().toInstant());
    }
}
