package com.beavent.backend;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jooq.DSLContext;

/** Creates users and organizations directly with the system role (bypassing RLS) for test setup. */
public final class TestTenants {

    private final DSLContext system;

    public TestTenants(DSLContext system) {
        this.system = system;
    }

    public UUID user() {
        UUID id = UUID.randomUUID();
        String phone =
                "+2519" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        system.insertInto(com.beavent.db.iam.Tables.USERS)
                .set(com.beavent.db.iam.Tables.USERS.ID, id)
                .set(com.beavent.db.iam.Tables.USERS.PHONE_E164, phone)
                .execute();
        return id;
    }

    public UUID organization(UUID ownerUserId) {
        var markets = com.beavent.db.ref.Tables.MARKETS;
        var organizations = com.beavent.db.tenant.Tables.ORGANIZATIONS;
        UUID id = UUID.randomUUID();
        system.insertInto(organizations)
                .set(organizations.ID, id)
                .set(organizations.SLUG, "test-" + id.toString().substring(0, 8))
                .set(organizations.DISPLAY_NAME, "Test organization")
                .set(organizations.KIND, "BUSINESS")
                .set(
                        organizations.HOME_MARKET_ID,
                        system.select(markets.ID).from(markets).where(markets.CODE.eq("ET")))
                .set(organizations.DEFAULT_LANGUAGE, "am")
                .set(organizations.CREATED_BY_USER_ID, ownerUserId)
                .execute();
        return id;
    }

    public UUID tag(UUID orgId, String name) {
        var tags = com.beavent.db.crm.Tables.TAGS;
        UUID id = UUID.randomUUID();
        system.insertInto(tags)
                .set(tags.ID, id)
                .set(tags.ORG_ID, orgId)
                .set(tags.NAME, name)
                .execute();
        return id;
    }
}
