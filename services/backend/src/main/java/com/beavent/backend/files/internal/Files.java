package com.beavent.backend.files.internal;

import static com.beavent.db.platform.Tables.FILES;

import com.beavent.backend.files.FileAccess;
import com.beavent.backend.identity.AvatarFiles;
import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Audit;
import com.beavent.backend.platform.DomainEvent;
import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.Outbox;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.platform.RateLimiter;
import com.beavent.backend.platform.SystemDb;
import com.beavent.backend.tenancy.OrgAccess;
import com.beavent.db.platform.tables.records.FilesRecord;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Upload lifecycle on the application role (row-level security: an organization's files, or a person's own):
 * create (pre-signed PUT) → client uploads → complete (size/type checked) → processed asynchronously.
 */
@Component
public class Files implements AvatarFiles, FileAccess {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private final DSLContext dsl;
    private final DSLContext system;
    private final TransactionTemplate transactions;
    private final Storage storage;
    private final FilesProperties properties;
    private final RateLimiter rateLimiter;
    private final Outbox outbox;
    private final Audit audit;
    private final JsonMapper json;

    Files(
            DSLContext dsl,
            @SystemDb DSLContext system,
            TransactionTemplate transactions,
            Storage storage,
            FilesProperties properties,
            RateLimiter rateLimiter,
            Outbox outbox,
            Audit audit,
            JsonMapper json) {
        this.dsl = dsl;
        this.system = system;
        this.transactions = transactions;
        this.storage = storage;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.outbox = outbox;
        this.audit = audit;
        this.json = json;
    }

    /** Upload in an organization workspace (logo, gallery, KYB documents ...). */
    public FileBodies.FileUpload createForOrganization(OrgAccess access, FileBodies.FileUploadCreate input) {
        Purposes.Policy policy = policy(input, true);
        access.require(policy.permission());
        rateLimiter.hit("uploads:org:" + access.orgId(), 300, Duration.ofHours(1));
        return create(access.orgId(), access.userId(), policy, input, "uploads/orgs/" + access.orgId() + "/");
    }

    /** Upload by a person for themselves (avatar). */
    public FileBodies.FileUpload createForUser(UUID userId, FileBodies.FileUploadCreate input) {
        Purposes.Policy policy = policy(input, false);
        rateLimiter.hit("uploads:user:" + userId, 30, Duration.ofHours(1));
        return create(null, userId, policy, input, "uploads/users/" + userId + "/");
    }

    private static Purposes.Policy policy(FileBodies.FileUploadCreate input, boolean organization) {
        Purposes.Policy policy = Purposes.of(input.purpose());
        if (policy == null || policy.ownedByOrganization() != organization) {
            throw new ApiException(ProblemCode.CONSTRAINT_VIOLATION, "This kind of file can't be uploaded here.");
        }
        if (!policy.contentTypes().contains(input.contentType())) {
            throw new ApiException(
                    ProblemCode.CONSTRAINT_VIOLATION,
                    "Allowed file types for " + input.purpose() + ": " + String.join(", ", policy.contentTypes())
                            + ".");
        }
        if (input.sizeBytes() > policy.maxBytes()) {
            throw new ApiException(
                    ProblemCode.CONSTRAINT_VIOLATION,
                    "The file is too large (at most " + policy.maxBytes() / (1024 * 1024) + " MB).");
        }
        return policy;
    }

    private FileBodies.FileUpload create(
            UUID orgId, UUID userId, Purposes.Policy policy, FileBodies.FileUploadCreate input, String keyPrefix) {
        UUID id = Ids.newId();
        String key = keyPrefix + id;
        FilesRecord file = transactions.execute(status -> {
            FilesRecord row = dsl.newRecord(FILES);
            row.setId(id);
            row.setOrgId(orgId);
            row.setOwnerUserId(userId);
            row.setPurpose(policy.purpose());
            row.setVisibility(policy.visibility());
            row.setBucket(properties.privateBucket());
            row.setStorageKey(key);
            row.setContentType(input.contentType());
            row.setSizeBytes(input.sizeBytes());
            row.setOriginalName(cleanName(input.fileName()));
            row.store();
            row.refresh();
            return row;
        });
        Storage.UploadTarget target =
                storage.presignPut(properties.privateBucket(), key, input.contentType(), properties.uploadUrlTtl());
        return new FileBodies.FileUpload(
                view(file),
                new FileBodies.UploadTarget(target.url().toString(), "PUT", target.headers(), target.expiresAt()));
    }

    /** The client finished uploading: verify the stored object, then queue processing. Idempotent. */
    /** @param orgId organization workspace, or {@code null} for the person's own files */
    public FileBodies.File complete(UUID orgId, UUID fileId) {
        return transactions.execute(status -> {
            FilesRecord file = locked(orgId, fileId);
            if (file.getUploadedAt() != null) {
                return view(file);
            }
            Storage.ObjectInfo stored = storage.head(file.getBucket(), file.getStorageKey());
            if (stored == null) {
                throw new ApiException(ProblemCode.CONFLICT, "The upload has not arrived yet.");
            }
            if (stored.size() != file.getSizeBytes() || !file.getContentType().equals(stored.contentType())) {
                storage.delete(file.getBucket(), file.getStorageKey());
                throw new ApiException(
                        ProblemCode.CONSTRAINT_VIOLATION,
                        "The uploaded file does not match its declared size or type. Upload it again.");
            }
            file.setUploadedAt(OffsetDateTime.now());
            file.store();
            outbox.publish(new DomainEvent(
                    "file", fileId, "file.uploaded", 1, file.getOrgId(), Map.of("fileId", fileId.toString())));
            return view(file);
        });
    }

    public FileBodies.File get(UUID orgId, UUID fileId) {
        return transactions.execute(status -> view(found(orgId, fileId)));
    }

    /**
     * A short-lived download URL for a private or sensitive file, after the purpose's permission check. Every
     * sensitive access is audited.
     */
    public FileBodies.AccessUrl accessUrl(OrgAccess access, UUID fileId) {
        return transactions.execute(status -> {
            FilesRecord file = found(access.orgId(), fileId);
            Purposes.Policy policy = Purposes.of(file.getPurpose());
            if ("PUBLIC".equals(file.getVisibility())) {
                FileBodies.File view = view(file);
                if (view.url() == null) {
                    throw new ApiException(ProblemCode.CONFLICT, "The file is not ready yet.");
                }
                return new FileBodies.AccessUrl(view.url(), null);
            }
            access.require(policy == null ? "org.settings.manage" : policy.permission());
            if (!"CLEAN".equals(file.getScanStatus())) {
                throw new ApiException(ProblemCode.CONFLICT, "The file is not ready yet.");
            }
            boolean sensitive = "SENSITIVE".equals(file.getVisibility());
            Duration ttl = sensitive ? properties.sensitiveAccessUrlTtl() : properties.accessUrlTtl();
            URL url = storage.presignGet(file.getBucket(), file.getStorageKey(), ttl, file.getOriginalName());
            if (sensitive) {
                audit.record(new Audit.Entry(
                        Audit.Actor.USER,
                        access.userId(),
                        access.orgId(),
                        "file.accessed",
                        "platform.file",
                        fileId.toString(),
                        null,
                        Map.of("purpose", file.getPurpose()),
                        null));
            }
            return new FileBodies.AccessUrl(url.toString(), Instant.now().plus(ttl));
        });
    }

    @Override
    public SignedUrl signedUrl(UUID fileId) {
        FilesRecord file = system.selectFrom(FILES)
                .where(FILES.ID.eq(fileId), FILES.DELETED_AT.isNull())
                .fetchOne();
        if (file == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        if (!"CLEAN".equals(file.getScanStatus())) {
            throw new ApiException(ProblemCode.CONFLICT, "The file is not ready or was rejected.");
        }
        Duration ttl = properties.sensitiveAccessUrlTtl();
        return new SignedUrl(
                storage.presignGet(file.getBucket(), file.getStorageKey(), ttl, file.getOriginalName()),
                Instant.now().plus(ttl));
    }

    @Override
    public java.util.Optional<PublicImage> publicImage(UUID fileId) {
        FilesRecord file = system.selectFrom(FILES)
                .where(FILES.ID.eq(fileId), FILES.DELETED_AT.isNull())
                .fetchOne();
        if (file == null || !"PUBLIC".equals(file.getVisibility())) {
            return java.util.Optional.empty();
        }
        FileBodies.File view = view(file);
        return view.url() == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(
                        new PublicImage(fileId, file.getOrgId(), file.getPurpose(), view.url(), view.variants()));
    }

    @Override
    public boolean isUsableAvatar(UUID userId, UUID fileId) {
        return system.fetchExists(
                FILES,
                FILES.ID
                        .eq(fileId)
                        .and(FILES.ORG_ID.isNull())
                        .and(FILES.OWNER_USER_ID.eq(userId))
                        .and(FILES.PURPOSE.eq("AVATAR"))
                        .and(FILES.SCAN_STATUS.eq("CLEAN"))
                        .and(FILES.DELETED_AT.isNull()));
    }

    /** Explicit owner scope: under an organization context the policy would also show the caller's own files. */
    private static org.jooq.Condition scope(UUID orgId) {
        return orgId == null ? FILES.ORG_ID.isNull() : FILES.ORG_ID.eq(orgId);
    }

    private FilesRecord found(UUID orgId, UUID fileId) {
        FilesRecord file = dsl.selectFrom(FILES)
                .where(FILES.ID.eq(fileId), scope(orgId), FILES.DELETED_AT.isNull())
                .fetchOne();
        if (file == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        return file;
    }

    private FilesRecord locked(UUID orgId, UUID fileId) {
        FilesRecord file = dsl.selectFrom(FILES)
                .where(FILES.ID.eq(fileId), scope(orgId), FILES.DELETED_AT.isNull())
                .forUpdate()
                .fetchOne();
        if (file == null) {
            throw new ApiException(ProblemCode.NOT_FOUND);
        }
        return file;
    }

    FileBodies.File view(FilesRecord file) {
        String status;
        if (file.getUploadedAt() == null) {
            status = "PENDING_UPLOAD";
        } else {
            status = switch (file.getScanStatus()) {
                case "PENDING" -> "PROCESSING";
                case "CLEAN" -> "READY";
                default -> "REJECTED";
            };
        }
        boolean servedPublicly =
                "READY".equals(status) && properties.publicBucket().equals(file.getBucket());
        Map<String, String> variants = new LinkedHashMap<>();
        if (servedPublicly) {
            json.readValue(file.getVariants().data(), STRING_MAP)
                    .forEach((name, key) -> variants.put(name, publicUrl(key)));
        }
        return new FileBodies.File(
                file.getId(),
                file.getPurpose(),
                file.getVisibility(),
                file.getContentType(),
                file.getSizeBytes(),
                status,
                file.getOriginalName(),
                servedPublicly ? publicUrl(file.getStorageKey()) : null,
                servedPublicly ? variants : null,
                file.getWidthPx(),
                file.getHeightPx(),
                file.getCreatedAt().toInstant());
    }

    String publicUrl(String key) {
        return properties.publicBaseUrl() + "/" + key;
    }

    /** Keeps a display name only: no paths, no control characters. */
    private static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}\"]", "")
                .strip();
        return base.isEmpty() ? null : base;
    }
}
