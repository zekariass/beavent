package com.beavent.backend;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Real infrastructure for integration tests: PostGIS (same image as docker-compose and CI), Redis, RabbitMQ and
 * SeaweedFS (S3-compatible storage).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        DockerImageName image = DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres");
        // The official PostGIS image is amd64-only; runs under emulation on Apple Silicon.
        return new PostgreSQLContainer(image).withCreateContainerCmdModifier(cmd -> cmd.withPlatform("linux/amd64"));
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitmq() {
        return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.1-management-alpine"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    }

    /** S3-compatible storage, configured with the same file as docker-compose (docker/seaweedfs/s3.json). */
    @Bean
    GenericContainer<?> objectStorage() {
        return new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.52"))
                .withCopyFileToContainer(
                        MountableFile.forHostPath(Path.of("../../docker/seaweedfs/s3.json")), "/etc/seaweedfs/s3.json")
                .withCommand("server", "-s3", "-s3.config=/etc/seaweedfs/s3.json", "-dir=/data")
                .withExposedPorts(8333)
                .waitingFor(Wait.forHttp("/").forPort(8333).forStatusCodeMatching(code -> code < 500))
                .withStartupTimeout(Duration.ofMinutes(2));
    }

    /** Points the files module at the storage container (credentials and buckets as in the local profile). */
    @Bean
    DynamicPropertyRegistrar objectStorageProperties(GenericContainer<?> objectStorage) {
        return registry -> {
            String endpoint = "http://" + objectStorage.getHost() + ":" + objectStorage.getMappedPort(8333);
            registry.add("beavent.files.endpoint", () -> endpoint);
            registry.add("beavent.files.public-base-url", () -> endpoint + "/beavent-public");
        };
    }
}
