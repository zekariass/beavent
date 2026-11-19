package com.beavent.backend.platform.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Login credentials for the least-privilege database roles (docs/05 §5). The JDBC URL comes from the schema-owner
 * connection ({@code spring.datasource.*} or a Testcontainers service connection) unless {@code beavent.db.url} is
 * set. The schema owner is used by Flyway only.
 */
@ConfigurationProperties("beavent.db")
record DatabaseProperties(String url, Role app, Role identity, Role system) {

    record Role(String username, String password, Integer maximumPoolSize) {}
}
