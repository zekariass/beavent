package com.beavent.backend;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class BackendApplicationTests {

    @Autowired
    @FlywayDataSource
    DataSource ownerDataSource;

    @Test
    void contextLoadsAndFlywayAppliesTheDatabaseBaseline() {
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource);
        Integer applied = owner.queryForObject(
                "select count(*) from flyway_schema_history where success and version is not null", Integer.class);
        assertThat(applied)
                .isEqualTo(
                        50); // db/migration: V1_00 .. V1_99 baseline + V2_01 .. V2_10 + V3_01 .. V3_08 + V4_01 .. V4_02
        // + V8_01 .. V8_06

        Integer tables = owner.queryForObject("""
                select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where c.relkind = 'r' and n.nspname in ('platform','ref','iam','tenant','team','catalog','crm','events',
                       'commerce','scheduling','retention','stock','payments','ledger','billing','fiscal','marketing',
                       'comms','discovery','trust')
                """, Integer.class);
        assertThat(tables).isEqualTo(266);

        String postgis =
                owner.queryForObject("select extversion from pg_extension where extname = 'postgis'", String.class);
        assertThat(postgis).startsWith("3.");
    }
}
