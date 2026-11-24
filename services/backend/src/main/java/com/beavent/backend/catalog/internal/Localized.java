package com.beavent.backend.catalog.internal;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Text per language ({"en": "Haircut", "am": "የፀጉር ቁርጥ"}) stored as JSONB; validated on the way in. */
@Component
class Localized {

    private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}$");
    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() {};

    private final JsonMapper json;

    Localized(JsonMapper json) {
        this.json = json;
    }

    /** A name: at least one language, each value non-blank. */
    JSONB name(Map<String, String> text, String field, int maxLength) {
        Map<String, String> clean = clean(text, field, maxLength);
        if (clean.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, field + " needs a text in at least one language.");
        }
        return JSONB.valueOf(json.writeValueAsString(clean));
    }

    /** Optional text; {@code null} or empty means none. */
    JSONB optional(Map<String, String> text, String field, int maxLength) {
        return JSONB.valueOf(json.writeValueAsString(clean(text, field, maxLength)));
    }

    Map<String, String> read(JSONB value) {
        return value == null ? Map.of() : json.readValue(value.data(), MAP);
    }

    private static Map<String, String> clean(Map<String, String> text, String field, int maxLength) {
        Map<String, String> clean = new LinkedHashMap<>();
        if (text == null) {
            return clean;
        }
        if (text.size() > 20) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, field + " has too many languages.");
        }
        text.forEach((language, value) -> {
            if (!LANGUAGE.matcher(language).matches() || value == null || value.length() > maxLength) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Invalid " + field + " (" + language + ").");
            }
            if (!value.isBlank()) {
                clean.put(language, value.strip());
            }
        });
        return clean;
    }
}
