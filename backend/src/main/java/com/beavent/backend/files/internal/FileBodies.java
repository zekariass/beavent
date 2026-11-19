package com.beavent.backend.files.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Request and response bodies of the files API (backend/src/main/openapi/files). */
public final class FileBodies {

    private FileBodies() {}

    public record FileUploadCreate(
            @NotNull @Pattern(regexp = "LOGO|COVER|GALLERY|EVENT_MEDIA|KYC|DOCUMENT|AVATAR")
            String purpose,

            @NotBlank @Size(max = 100) String contentType,
            @NotNull @Min(1) @Max(100L * 1024 * 1024) Long sizeBytes,
            @Size(max = 200) String fileName) {}

    public record File(
            UUID id,
            String purpose,
            String visibility,
            String contentType,
            long sizeBytes,
            String status,
            String fileName,
            String url,
            Map<String, String> variants,
            Integer width,
            Integer height,
            Instant createdAt) {}

    public record UploadTarget(String url, String method, Map<String, String> headers, Instant expiresAt) {}

    public record FileUpload(File file, UploadTarget upload) {}

    public record AccessUrl(String url, Instant expiresAt) {}
}
