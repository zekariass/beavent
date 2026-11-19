package com.beavent.backend.platform.persistence;

import com.beavent.backend.platform.IdentityDb;
import com.beavent.backend.platform.SystemDb;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Finds the jOOQ context of whichever role's transaction is active on this thread, so platform services (outbox,
 * audit) write in the caller's transaction regardless of the role it uses.
 */
@Component
public class CurrentTransactionDsl {

    private final DataSource appDataSource;
    private final DSLContext appDsl;
    private final DataSource identityDataSource;
    private final DSLContext identityDsl;
    private final DataSource systemDataSource;
    private final DSLContext systemDsl;

    CurrentTransactionDsl(
            DataSource appDataSource,
            DSLContext appDsl,
            @IdentityDb DataSource identityDataSource,
            @IdentityDb DSLContext identityDsl,
            @SystemDb DataSource systemDataSource,
            @SystemDb DSLContext systemDsl) {
        this.appDataSource = appDataSource;
        this.appDsl = appDsl;
        this.identityDataSource = identityDataSource;
        this.identityDsl = identityDsl;
        this.systemDataSource = systemDataSource;
        this.systemDsl = systemDsl;
    }

    /** @throws IllegalStateException with {@code purpose} in the message when no transaction is active */
    public DSLContext require(String purpose) {
        if (TransactionSynchronizationManager.getResource(appDataSource) != null) {
            return appDsl;
        }
        if (TransactionSynchronizationManager.getResource(identityDataSource) != null) {
            return identityDsl;
        }
        if (TransactionSynchronizationManager.getResource(systemDataSource) != null) {
            return systemDsl;
        }
        throw new IllegalStateException(purpose + " must run inside the transaction that makes the change");
    }
}
