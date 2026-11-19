package com.beavent.backend.files.internal;

import static com.beavent.db.platform.Tables.FILES;

import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.ProcessedMessages;
import com.beavent.backend.platform.SystemDb;
import com.beavent.db.platform.tables.records.FilesRecord;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Processes confirmed uploads ({@code file.uploaded}): content-type check, malware scan, and for public images
 * re-encoding plus variants, published to the public bucket only now. Rejected files are deleted from storage.
 */
@Component
class FileProcessor {

    static final String CONSUMER = "files.processor";
    private static final Logger log = LoggerFactory.getLogger(FileProcessor.class);

    private final DSLContext system;
    private final ProcessedMessages processedMessages;
    private final Storage storage;
    private final MalwareScanner scanner;
    private final FilesProperties properties;
    private final Audit audit;
    private final JsonMapper json;

    FileProcessor(
            @SystemDb DSLContext system,
            ProcessedMessages processedMessages,
            Storage storage,
            MalwareScanner scanner,
            FilesProperties properties,
            Audit audit,
            JsonMapper json) {
        this.system = system;
        this.processedMessages = processedMessages;
        this.storage = storage;
        this.scanner = scanner;
        this.properties = properties;
        this.audit = audit;
        this.json = json;
    }

    @RabbitListener(queues = FilesConfiguration.QUEUE)
    void onUploaded(Message message) {
        UUID messageId = UUID.fromString(message.getMessageProperties().getMessageId());
        UUID fileId =
                UUID.fromString(json.readTree(message.getBody()).get("fileId").asString());
        processedMessages.runOnce(CONSUMER, messageId, () -> process(fileId));
    }

    void process(UUID fileId) {
        FilesRecord file =
                system.selectFrom(FILES).where(FILES.ID.eq(fileId)).forUpdate().fetchOne();
        if (file == null
                || file.getUploadedAt() == null
                || file.getDeletedAt() != null
                || !"PENDING".equals(file.getScanStatus())) {
            return;
        }
        byte[] content = storage.get(file.getBucket(), file.getStorageKey());
        file.setChecksumSha256(sha256(content));
        if (!Purposes.contentMatches(file.getContentType(), content)) {
            reject(file, "FAILED", "content does not match " + file.getContentType());
            return;
        }
        if (scanner.scan(content) == MalwareScanner.Verdict.INFECTED) {
            reject(file, "INFECTED", "malware detected");
            return;
        }
        if ("PUBLIC".equals(file.getVisibility())) {
            try {
                publish(file, ImageVariants.process(content, file.getContentType()));
            } catch (ImageVariants.UnreadableImage e) {
                reject(file, "FAILED", e.getMessage());
                return;
            }
        }
        file.setScanStatus("CLEAN");
        file.setProcessedAt(OffsetDateTime.now());
        file.store();
    }

    /** Writes the cleaned image and its variants to the public bucket and removes the private upload. */
    private void publish(FilesRecord file, ImageVariants.Processed image) {
        String extension = "image/png".equals(file.getContentType()) ? "png" : "jpg";
        String base = (file.getOrgId() != null ? "orgs/" + file.getOrgId() : "users/" + file.getOwnerUserId()) + "/"
                + file.getId() + "/";
        String bucket = properties.publicBucket();
        String originalKey = base + "original." + extension;
        storage.put(bucket, originalKey, image.original(), file.getContentType());
        Map<String, String> variants = new LinkedHashMap<>();
        image.variants().forEach((name, bytes) -> {
            String key = base + name + "." + extension;
            storage.put(bucket, key, bytes, file.getContentType());
            variants.put(name, key);
        });
        storage.delete(file.getBucket(), file.getStorageKey());
        file.setBucket(bucket);
        file.setStorageKey(originalKey);
        file.setSizeBytes((long) image.original().length);
        file.setWidthPx(image.width());
        file.setHeightPx(image.height());
        file.setVariants(JSONB.valueOf(json.writeValueAsString(variants)));
    }

    private void reject(FilesRecord file, String scanStatus, String reason) {
        log.warn("Rejected file {} ({}): {}", file.getId(), file.getPurpose(), reason);
        storage.delete(file.getBucket(), file.getStorageKey());
        file.setScanStatus(scanStatus);
        file.setProcessedAt(OffsetDateTime.now());
        file.store();
        audit.record(new Audit.Entry(
                Audit.Actor.SYSTEM,
                null,
                file.getOrgId(),
                "file.rejected",
                "platform.file",
                file.getId().toString(),
                null,
                Map.of("scanStatus", scanStatus, "reason", reason),
                null));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
