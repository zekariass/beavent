package com.beavent.backend.files.internal;

import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/** Thin wrapper over an S3-compatible store (SeaweedFS locally, Cloudflare R2 or AWS S3); path-style addressing. */
@Component
class Storage implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(Storage.class);

    record UploadTarget(URL url, Map<String, String> headers, Instant expiresAt) {}

    record ObjectInfo(long size, String contentType) {}

    private final S3Client client;
    private final S3Presigner presigner;

    Storage(FilesProperties properties) {
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        URI endpoint = URI.create(properties.endpoint());
        Region region = Region.of(properties.region());
        this.client = S3Client.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(credentials)
                .forcePathStyle(true)
                // Checksums only where the API requires them: broadest compatibility across S3 implementations.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        if (properties.createBuckets()) {
            ensureBuckets(properties);
        }
    }

    private void ensureBuckets(FilesProperties properties) {
        try {
            for (String bucket : List.of(properties.privateBucket(), properties.publicBucket())) {
                try {
                    client.headBucket(b -> b.bucket(bucket));
                } catch (NoSuchBucketException e) {
                    client.createBucket(b -> b.bucket(bucket));
                }
            }
            // Public read on the public bucket. Stores without bucket policies (SeaweedFS) configure it themselves.
            String policy = """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},\
                    "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/*"]}]}""".formatted(properties.publicBucket());
            try {
                client.putBucketPolicy(b -> b.bucket(properties.publicBucket()).policy(policy));
            } catch (S3Exception e) {
                log.info(
                        "Storage does not accept bucket policies ({}); public read must be configured there",
                        e.statusCode());
            }
        } catch (RuntimeException e) {
            // Development convenience only: storage may simply not be running yet.
            log.warn("Could not prepare storage buckets at {}: {}", properties.endpoint(), e.getMessage());
        }
    }

    UploadTarget presignPut(String bucket, String key, String contentType, Duration ttl) {
        PresignedPutObjectRequest request = presigner.presignPutObject(p -> p.signatureDuration(ttl)
                .putObjectRequest(o -> o.bucket(bucket).key(key).contentType(contentType)));
        Map<String, String> headers = new LinkedHashMap<>();
        request.signedHeaders().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("host")) {
                headers.put(name, String.join(",", values));
            }
        });
        return new UploadTarget(request.url(), headers, request.expiration());
    }

    URL presignGet(String bucket, String key, Duration ttl, String downloadName) {
        return presigner
                .presignGetObject(p -> p.signatureDuration(ttl).getObjectRequest(o -> {
                    o.bucket(bucket).key(key);
                    if (downloadName != null) {
                        o.responseContentDisposition("attachment; filename=\"" + downloadName.replace("\"", "") + "\"");
                    }
                }))
                .url();
    }

    /** Size and type of a stored object, or {@code null} if it does not exist. */
    ObjectInfo head(String bucket, String key) {
        try {
            HeadObjectResponse response =
                    client.headObject(h -> h.bucket(bucket).key(key));
            return new ObjectInfo(response.contentLength(), response.contentType());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    byte[] get(String bucket, String key) {
        return client.getObjectAsBytes(g -> g.bucket(bucket).key(key)).asByteArray();
    }

    void put(String bucket, String key, byte[] content, String contentType) {
        client.putObject(
                p -> p.bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .cacheControl("public, max-age=31536000, immutable"),
                RequestBody.fromBytes(content));
    }

    void delete(String bucket, String key) {
        client.deleteObject(d -> d.bucket(bucket).key(key));
    }

    @Override
    public void destroy() {
        presigner.close();
        client.close();
    }
}
