package com.beavent.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BackendApplicationTests {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextLoadsAndFlywayAppliesTheDatabaseBaseline() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success and version is not null", Integer.class);
        Integer migrationFiles = 24; // db/migration/V1_00 .. V1_99
        assertThat(applied).isEqualTo(migrationFiles);

        Integer tables = jdbc.queryForObject("""
                select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where c.relkind = 'r' and n.nspname in ('platform','ref','iam','tenant','team','catalog','crm','events',
                       'commerce','scheduling','retention','stock','payments','ledger','billing','fiscal','marketing',
                       'comms','discovery','trust')
                """, Integer.class);
        assertThat(tables).isEqualTo(266);

        String postgis =
                jdbc.queryForObject("select extversion from pg_extension where extname = 'postgis'", String.class);
        assertThat(postgis).startsWith("3.");
    }
}
