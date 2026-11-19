package com.beavent.backend.platform;

import static com.beavent.db.crm.Tables.TAGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beavent.backend.IntegrationTest;
import com.beavent.backend.TestTenants;
import java.sql.SQLException;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

/** The application role only ever sees the organization in the request context (row-level security). */
@IntegrationTest
class RequestContextIsolationTests {

    @Autowired
    DSLContext app;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    @SystemDb
    DSLContext system;

    @Autowired
    @SystemDb
    TransactionTemplate systemTransactions;

    @Autowired
    @IdentityDb
    DSLContext identity;

    @Autowired
    @IdentityDb
    TransactionTemplate identityTransactions;

    UUID user;
    UUID orgA;
    UUID orgB;

    @BeforeEach
    void createTwoOrganizations() {
        TestTenants tenants = new TestTenants(system);
        user = tenants.user();
        orgA = tenants.organization(user);
        orgB = tenants.organization(user);
        tenants.tag(orgA, "vip");
        tenants.tag(orgB, "regular");
    }

    @Test
    void eachPoolConnectsAsItsOwnLeastPrivilegeRole() {
        var currentUser = DSL.field("current_user", String.class);
        String appUser = transactions.execute(s -> app.select(currentUser).fetchOne(currentUser));
        String systemUser =
                systemTransactions.execute(s -> system.select(currentUser).fetchOne(currentUser));
        String identityUser =
                identityTransactions.execute(s -> identity.select(currentUser).fetchOne(currentUser));
        assertThat(appUser).isEqualTo("beavent_app_login");
        assertThat(systemUser).isEqualTo("beavent_system_login");
        assertThat(identityUser).isEqualTo("beavent_identity_login");
    }

    @Test
    void organizationContextSeesOnlyItsOwnRows() {
        var names = RequestContext.forOrganization(user, orgA)
                .call(() -> transactions.execute(
                        s -> app.select(TAGS.NAME).from(TAGS).fetch(TAGS.NAME)));
        assertThat(names).containsExactly("vip");
    }

    @Test
    void withoutContextTheApplicationRoleSeesNothing() {
        // Context of the previous transaction must not leak into the next one.
        RequestContext.forOrganization(user, orgA).run(() -> transactions.execute(s -> app.fetchCount(TAGS)));
        Integer visible = transactions.execute(s -> app.fetchCount(TAGS));
        assertThat(visible).isZero();
    }

    @Test
    void writingIntoAnotherOrganizationIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> RequestContext.forOrganization(user, orgA)
                        .run(() -> transactions.execute(s -> app.insertInto(TAGS)
                                .set(TAGS.ORG_ID, orgB)
                                .set(TAGS.NAME, "intruder")
                                .execute())))
                .isInstanceOf(DataAccessException.class)
                .satisfies(error -> assertThat(sqlState(error)).isEqualTo("42501"));
    }

    @Test
    void systemRoleSeesAllOrganizations() {
        Integer both = systemTransactions.execute(s -> system.fetchCount(TAGS, TAGS.ORG_ID.in(orgA, orgB)));
        assertThat(both).isEqualTo(2);
    }

    private static String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
