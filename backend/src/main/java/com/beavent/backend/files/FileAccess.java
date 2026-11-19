package com.beavent.backend.files;

import java.net.URL;
import java.time.Instant;
import java.util.UUID;

/**
 * Signed access to stored files for other modules (the admin console's KYB review). Every call is audited by the
 * caller together with its reason.
 */
public interface FileAccess {

    record SignedUrl(URL url, Instant expiresAt) {}

    /**
     * A short-lived download URL for a processed (clean) file, regardless of organization.
     *
     * @throws com.beavent.backend.platform.ApiException NOT_FOUND / CONFLICT if missing or not processed
     */
    SignedUrl signedUrl(UUID fileId);
}
