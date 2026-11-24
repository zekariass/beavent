package com.beavent.backend.tenancy.internal;

import static com.beavent.db.iam.Tables.PERMISSIONS;

import com.beavent.backend.identity.Assurance;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import java.util.Collection;
import java.util.Map;
import java.util.TreeSet;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/** Organization permission codes and which of them are sensitive (granting them needs step-up). */
@Component
class PermissionCatalog {

    private final DSLContext dsl;
    private volatile Map<String, Boolean> sensitiveByCode;

    PermissionCatalog(DSLContext dsl) {
        this.dsl = dsl;
    }

    private Map<String, Boolean> catalog() {
        Map<String, Boolean> catalog = sensitiveByCode;
        if (catalog == null) {
            // Seeded by migrations only, so it never changes while the application runs.
            catalog = dsl.select(PERMISSIONS.CODE, PERMISSIONS.IS_SENSITIVE)
                    .from(PERMISSIONS)
                    .where(PERMISSIONS.SCOPE.eq("ORGANIZATION"))
                    .fetchMap(PERMISSIONS.CODE, PERMISSIONS.IS_SENSITIVE);
            sensitiveByCode = catalog;
        }
        return catalog;
    }

    void requireKnown(Collection<String> codes) {
        TreeSet<String> unknown = new TreeSet<>(codes);
        unknown.removeAll(catalog().keySet());
        if (!unknown.isEmpty()) {
            throw new ApiException(ProblemCode.REFERENCE_NOT_FOUND, "Unknown permissions: " + unknown + ".");
        }
    }

    boolean anySensitive(Collection<String> codes) {
        return codes.stream().anyMatch(code -> catalog().getOrDefault(code, false));
    }

    /** Granting or taking away sensitive access needs a fresh step-up (docs/03 §2.6). */
    void requireStepUpIfSensitive(Collection<String> codes) {
        if (anySensitive(codes)) {
            Assurance.requireStepUp();
        }
    }
}
