package com.beavent.backend.platform.persistence;

import com.beavent.backend.platform.IdentityDb;
import com.beavent.backend.platform.SystemDb;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Objects;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.jooq.autoconfigure.ExceptionTranslatorExecuteListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One connection pool per database role (docs/05 §5):
 *
 * <ul>
 *   <li><b>owner</b>: schema owner, Flyway migrations only ({@link FlywayDataSource}).
 *   <li><b>app</b> (primary): {@code beavent_app}, RLS enforced; its transaction manager sets the request context.
 *       The primary jOOQ {@code DSLContext} uses it.
 *   <li><b>identity</b> ({@link IdentityDb}) and <b>system</b> ({@link SystemDb}): their own pools, transaction
 *       managers, templates and jOOQ contexts.
 * </ul>
 *
 * Pools are created lazily, so the app roles' logins may be provisioned by the Flyway run itself (local profile).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseProperties.class)
class DatabaseConfiguration {

    private record Owner(String url, String username, String password) {}

    private static Owner owner(ObjectProvider<JdbcConnectionDetails> details, DataSourceProperties properties) {
        JdbcConnectionDetails connection = details.getIfAvailable();
        if (connection != null) {
            return new Owner(connection.getJdbcUrl(), connection.getUsername(), connection.getPassword());
        }
        return new Owner(properties.determineUrl(), properties.determineUsername(), properties.determinePassword());
    }

    private static HikariDataSource pool(String name, String url, String username, String password, int size) {
        HikariDataSource pool = new HikariDataSource(); // no-arg constructor: connects on first use
        pool.setPoolName("db-" + name);
        pool.setJdbcUrl(Objects.requireNonNull(url, "database URL"));
        pool.setUsername(Objects.requireNonNull(username, "username for database role " + name));
        pool.setPassword(password);
        pool.setMaximumPoolSize(size);
        pool.setAutoCommit(true);
        return pool;
    }

    private static HikariDataSource rolePool(
            String name, DatabaseProperties.Role role, Owner owner, DatabaseProperties properties, int defaultSize) {
        Objects.requireNonNull(role, "beavent.db." + name + " is not configured");
        String url = properties.url() != null ? properties.url() : owner.url();
        int size = role.maximumPoolSize() != null ? role.maximumPoolSize() : defaultSize;
        return pool(name, url, role.username(), role.password(), size);
    }

    /**
     * jOOQ context bound to Spring-managed transactions of {@code dataSource}; database errors are translated to
     * Spring's {@code DataAccessException} hierarchy (as Spring Boot's own jOOQ auto-configuration does).
     */
    private static DSLContext springManagedDsl(DataSource dataSource) {
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.set(new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(dataSource)));
        configuration.set(SQLDialect.POSTGRES);
        configuration.set(new DefaultExecuteListenerProvider(ExceptionTranslatorExecuteListener.DEFAULT));
        return DSL.using(configuration);
    }

    // --- owner (Flyway only) --------------------------------------------------------------------------------

    @Bean
    @FlywayDataSource
    HikariDataSource ownerDataSource(ObjectProvider<JdbcConnectionDetails> details, DataSourceProperties properties) {
        Owner owner = owner(details, properties);
        // Flyway holds a lock connection alongside the migration connection(s).
        return pool("owner", owner.url(), owner.username(), owner.password(), 4);
    }

    // --- application role (primary) -------------------------------------------------------------------------

    @Bean
    @Primary
    HikariDataSource appDataSource(
            ObjectProvider<JdbcConnectionDetails> details,
            DataSourceProperties dataSourceProperties,
            DatabaseProperties properties) {
        return rolePool("app", properties.app(), owner(details, dataSourceProperties), properties, 10);
    }

    @Bean
    @Primary
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new RequestContextTransactionManager(dataSource);
    }

    @Bean
    @Primary
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    @Primary
    DSLContext dsl(DataSource dataSource) {
        return springManagedDsl(dataSource);
    }

    // --- identity role ----------------------------------------------------------------------------------------

    @Bean
    @IdentityDb
    HikariDataSource identityDataSource(
            ObjectProvider<JdbcConnectionDetails> details,
            DataSourceProperties dataSourceProperties,
            DatabaseProperties properties) {
        return rolePool("identity", properties.identity(), owner(details, dataSourceProperties), properties, 5);
    }

    @Bean
    @IdentityDb
    PlatformTransactionManager identityTransactionManager(@IdentityDb DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    @IdentityDb
    TransactionTemplate identityTransactionTemplate(@IdentityDb PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    @IdentityDb
    DSLContext identityDsl(@IdentityDb DataSource dataSource) {
        return springManagedDsl(dataSource);
    }

    // --- system role ------------------------------------------------------------------------------------------

    @Bean
    @SystemDb
    HikariDataSource systemDataSource(
            ObjectProvider<JdbcConnectionDetails> details,
            DataSourceProperties dataSourceProperties,
            DatabaseProperties properties) {
        return rolePool("system", properties.system(), owner(details, dataSourceProperties), properties, 5);
    }

    @Bean
    @SystemDb
    PlatformTransactionManager systemTransactionManager(@SystemDb DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    @SystemDb
    TransactionTemplate systemTransactionTemplate(@SystemDb PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    @SystemDb
    DSLContext systemDsl(@SystemDb DataSource dataSource) {
        return springManagedDsl(dataSource);
    }
}
