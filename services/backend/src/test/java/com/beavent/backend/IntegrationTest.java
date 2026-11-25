package com.beavent.backend;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full application against real PostGIS, RabbitMQ and Redis containers (shared across test classes), with the
 * local profile so Flyway also creates the role logins (db/local).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
        properties = {
            // All test requests come from 127.0.0.1; per-phone limits stay as configured.
            "beavent.auth.otp.per-ip-per-hour=100000",
            "beavent.booking.holds-per-ip-per-hour=100000",
            // Messages and booking time-outs run often enough for tests to wait on them.
            "beavent.comms.dispatch-interval-ms=300",
            "beavent.scheduling.sweep-interval-ms=500",
            // Payments are looked up soon after a notification, and open ones polled often enough for tests to wait on
            // them.
            "beavent.payments.worker-interval-ms=300",
            // The local profile switches the seller gate off for development; tests run with it on, as in production.
            "beavent.payments.require-seller-readiness=true",
            // Search tests refresh the listings themselves; a background sweep would only add load.
            "beavent.discovery.sweep-interval-ms=3600000",
            "beavent.payouts.worker-interval-ms=3600000",
            "beavent.retention.worker-interval-ms=3600000"
        })
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
public @interface IntegrationTest {}
