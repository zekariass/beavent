package com.beavent.backend.files.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code beavent.files.*}: S3-compatible storage, scanner and URL lifetimes. */
@ConfigurationProperties("beavent.files")
public record FilesProperties(
        String endpoint,
        @DefaultValue("auto") String region,
        String accessKey,
        String secretKey,
        String privateBucket,
        String publicBucket,
        /** Base URL serving the public bucket (CDN in production), without a trailing slash. */
        String publicBaseUrl,
        /** Creates both buckets and a public-read policy at startup (local development and tests only). */
        @DefaultValue("false") boolean createBuckets,
        /** {@code clamav} (clamd INSTREAM) or {@code eicar} (test signature only, development). */
        @DefaultValue("clamav") String scanner,
        @DefaultValue("localhost") String clamavHost,
        @DefaultValue("3310") int clamavPort,
        @DefaultValue("15m") Duration uploadUrlTtl,
        @DefaultValue("5m") Duration accessUrlTtl,
        @DefaultValue("60s") Duration sensitiveAccessUrlTtl) {}
