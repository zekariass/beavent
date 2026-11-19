package com.beavent.backend.files.internal;

import java.util.Map;
import java.util.Set;

/** What may be uploaded for each purpose, by whom, and how it is served. */
final class Purposes {

    static final Set<String> IMAGES = Set.of("image/jpeg", "image/png");
    static final Set<String> DOCUMENTS = Set.of("application/pdf", "image/jpeg", "image/png");
    private static final long MB = 1024L * 1024L;

    /**
     * @param permission organization permission needed to upload (and, for private files, to read); {@code null}
     *     for the person's own files
     * @param ownedByOrganization uploaded in an organization workspace, otherwise by a person for themselves
     */
    record Policy(
            String purpose,
            String visibility,
            Set<String> contentTypes,
            long maxBytes,
            String permission,
            boolean ownedByOrganization) {

        boolean isPublicImage() {
            return "PUBLIC".equals(visibility);
        }
    }

    private static final Map<String, Policy> POLICIES = Map.of(
            "LOGO", new Policy("LOGO", "PUBLIC", IMAGES, 5 * MB, "org.settings.manage", true),
            "COVER", new Policy("COVER", "PUBLIC", IMAGES, 10 * MB, "org.settings.manage", true),
            "GALLERY", new Policy("GALLERY", "PUBLIC", IMAGES, 10 * MB, "org.settings.manage", true),
            "EVENT_MEDIA", new Policy("EVENT_MEDIA", "PUBLIC", IMAGES, 10 * MB, "events.manage", true),
            "KYC", new Policy("KYC", "SENSITIVE", DOCUMENTS, 15 * MB, "org.settings.manage", true),
            "DOCUMENT", new Policy("DOCUMENT", "PRIVATE", DOCUMENTS, 15 * MB, "org.settings.manage", true),
            "AVATAR", new Policy("AVATAR", "PUBLIC", IMAGES, 5 * MB, null, false));

    private Purposes() {}

    /** The policy, or {@code null} if this purpose cannot be uploaded through the API (yet). */
    static Policy of(String purpose) {
        return POLICIES.get(purpose);
    }

    /** True if the first bytes match the declared type (uploads are never trusted by their name or header). */
    static boolean contentMatches(String contentType, byte[] content) {
        return switch (contentType) {
            case "image/png" -> startsWith(content, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/jpeg" -> startsWith(content, 0xFF, 0xD8, 0xFF);
            case "application/pdf" -> startsWith(content, 0x25, 0x50, 0x44, 0x46, 0x2D);
            default -> false;
        };
    }

    private static boolean startsWith(byte[] content, int... magic) {
        if (content.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((content[i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
