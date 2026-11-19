package com.beavent.backend.files;

import java.net.URL;
import java.time.Instant;
import java.util.UUID;

/**
 * File access for other modules: signed URLs for staff review (audited by the caller) and public image URLs for
 * galleries and profiles.
 */
public interface FileAccess {

    record SignedUrl(URL url, Instant expiresAt) {}

    /**
     * A short-lived download URL for a processed (clean) file, regardless of organization.
     *
     * @throws com.beavent.backend.platform.ApiException NOT_FOUND / CONFLICT if missing or not processed
     */
    SignedUrl signedUrl(UUID fileId);

    /** A processed public image: its URL and smaller variants by name (w320, w960). */
    record PublicImage(UUID fileId, UUID orgId, String purpose, String url, java.util.Map<String, String> variants) {}

    /** The public image, if the file is a processed (clean) public file. */
    java.util.Optional<PublicImage> publicImage(UUID fileId);
}
