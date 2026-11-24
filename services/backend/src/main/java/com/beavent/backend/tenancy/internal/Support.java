package com.beavent.backend.tenancy.internal;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Small helpers shared by the tenancy services. */
final class Support {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SUFFIX_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile(Bodies.PHONE);

    /** Slugs that would clash with app routes or impersonate the platform. */
    static final Set<String> RESERVED_SLUGS = Set.of(
            "admin",
            "api",
            "app",
            "apps",
            "beavent",
            "business",
            "help",
            "support",
            "www",
            "login",
            "signup",
            "account",
            "settings",
            "events",
            "explore",
            "search",
            "orgs",
            "static",
            "assets",
            "about",
            "terms",
            "privacy",
            "security",
            "status",
            "partners",
            "checkout",
            "tickets",
            "book");

    private Support() {}

    /** Optimistic-concurrency version (ETag) of a row: its {@code updated_at} in microseconds. */
    static long version(OffsetDateTime updatedAt) {
        Instant instant = updatedAt.toInstant();
        return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
    }

    static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** URL slug from a display name; empty if the name has no Latin letters or digits (e.g. Ge'ez script). */
    static String slugify(String name, int maxLength) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        String slug =
                ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > maxLength) {
            slug = slug.substring(0, maxLength).replaceAll("-+$", "");
        }
        return slug;
    }

    static String randomSuffix(int length) {
        StringBuilder suffix = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            suffix.append(SUFFIX_ALPHABET.charAt(RANDOM.nextInt(SUFFIX_ALPHABET.length())));
        }
        return suffix.toString();
    }

    static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- JSON merge-patch reading (absent = unchanged, null = clear) ------------------------------------------

    static String text(JsonNode patch, String name, int maxLength) {
        JsonNode value = patch.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString() || value.asString().length() > maxLength) {
            throw invalid(name);
        }
        return value.asString();
    }

    static String requiredText(JsonNode patch, String name, int minLength, int maxLength) {
        String value = text(patch, name, maxLength);
        if (value == null || value.strip().length() < minLength) {
            throw invalid(name);
        }
        return value.strip();
    }

    static java.util.UUID uuid(JsonNode patch, String name) {
        String value = text(patch, name, 36);
        try {
            return value == null ? null : java.util.UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw invalid(name);
        }
    }

    static String email(JsonNode patch, String name) {
        String value = text(patch, name, 200);
        if (value != null && !EMAIL.matcher(value).matches()) {
            throw invalid(name);
        }
        return value;
    }

    static String phone(JsonNode patch, String name) {
        String value = text(patch, name, 16);
        if (value != null && !PHONE.matcher(value).matches()) {
            throw invalid(name);
        }
        return value;
    }

    static String url(JsonNode patch, String name) {
        String value = text(patch, name, 500);
        if (value != null && !value.matches("^https?://[^\\s]+$")) {
            throw invalid(name);
        }
        return value;
    }

    /** An object of strings (localized text, social links); {@code null} clears it. */
    static Map<String, String> stringMap(JsonNode patch, String name, int maxEntries, int maxLength) {
        JsonNode value = patch.get(name);
        if (value == null || value.isNull()) {
            return Map.of();
        }
        if (!value.isObject() || value.size() > maxEntries) {
            throw invalid(name);
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : value.properties()) {
            if (!entry.getValue().isString()
                    || entry.getKey().length() > 20
                    || entry.getValue().asString().length() > maxLength) {
                throw invalid(name);
            }
            map.put(entry.getKey(), entry.getValue().asString());
        }
        return map;
    }

    static ApiException invalid(String field) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, "Invalid value for " + field + ".");
    }
}
