package com.beavent.backend.crm.internal;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.ProblemCode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * What a consultation, intake or consent form asks (stored as {@code crm.form_versions.schema}) and how answers are
 * checked. Labels are per language ({@code {"am": …, "en": …}}); at least one language each.
 *
 * <p>Field kinds: {@code TEXT} (a line), {@code LONG_TEXT}, {@code YES_NO}, {@code SINGLE_CHOICE}, {@code
 * MULTI_CHOICE}, {@code DATE} ({@code yyyy-MM-dd}) and {@code CHECKBOX} (a statement to agree to; when required it
 * must be ticked). A form may ask for a signature: the person types their full name.
 */
public final class FormModel {

    public static final Set<String> KINDS =
            Set.of("TEXT", "LONG_TEXT", "YES_NO", "SINGLE_CHOICE", "MULTI_CHOICE", "DATE", "CHECKBOX");
    public static final Set<String> PURPOSES = Set.of("CONSULTATION", "INTAKE", "CONSENT", "AFTERCARE");
    public static final Set<String> FREQUENCIES = Set.of("EVERY_TIME", "ONCE", "EVERY_N_DAYS");
    static final int MAX_FIELDS = 60;
    static final int MAX_OPTIONS = 30;
    private static final Pattern ID = Pattern.compile("^[a-z0-9_-]{1,40}$");

    private FormModel() {}

    public record Option(String id, Map<String, String> label) {}

    public record Field(
            String id,
            String kind,
            Map<String, String> label,
            Map<String, String> help,
            boolean required,
            List<Option> options) {}

    /** The questions of one version. */
    public record Schema(Map<String, String> title, Map<String, String> intro, List<Field> fields, boolean signature) {}

    /** @throws ApiException {@link ProblemCode#VALIDATION_FAILED} with what to fix */
    static Schema check(Schema schema) {
        Map<String, String> title = text(schema.title(), "The form needs a name.", 120, true);
        Map<String, String> intro = text(schema.intro(), null, 2000, false);
        List<Field> fields = schema.fields() == null ? List.of() : schema.fields();
        if (fields.isEmpty() || fields.size() > MAX_FIELDS) {
            throw invalid("A form has 1 to " + MAX_FIELDS + " questions.");
        }
        Set<String> ids = new HashSet<>();
        List<Field> clean = fields.stream()
                .map(f -> {
                    if (f == null || f.id() == null || !ID.matcher(f.id()).matches() || !ids.add(f.id())) {
                        throw invalid("Each question needs its own id (a-z, 0-9, - and _).");
                    }
                    if (!KINDS.contains(f.kind())) {
                        throw invalid("Unknown kind of question: " + f.kind());
                    }
                    List<Option> options = List.of();
                    if (f.kind().equals("SINGLE_CHOICE") || f.kind().equals("MULTI_CHOICE")) {
                        options = f.options() == null ? List.of() : f.options();
                        if (options.size() < 2 || options.size() > MAX_OPTIONS) {
                            throw invalid("A choice has 2 to " + MAX_OPTIONS + " options.");
                        }
                        Set<String> optionIds = new HashSet<>();
                        options = options.stream()
                                .map(o -> {
                                    if (o == null
                                            || o.id() == null
                                            || !ID.matcher(o.id()).matches()
                                            || !optionIds.add(o.id())) {
                                        throw invalid("Each option needs its own id.");
                                    }
                                    return new Option(o.id(), text(o.label(), "Each option needs a label.", 200, true));
                                })
                                .toList();
                    }
                    return new Field(
                            f.id(),
                            f.kind(),
                            text(f.label(), "Each question needs a label.", 500, true),
                            text(f.help(), null, 500, false),
                            f.required(),
                            options);
                })
                .toList();
        return new Schema(title, intro, clean, schema.signature());
    }

    private static Map<String, String> text(Map<String, String> value, String missing, int max, boolean required) {
        Map<String, String> out = new LinkedHashMap<>();
        if (value != null) {
            value.forEach((language, text) -> {
                if (!language.matches("^[a-z]{2,3}$")) {
                    throw invalid("Texts are keyed by language code, such as am or en.");
                }
                if (text != null && !text.isBlank()) {
                    if (text.length() > max) {
                        throw invalid("Keep texts under " + max + " characters.");
                    }
                    out.put(language, text.strip());
                }
            });
        }
        if (required && out.isEmpty()) {
            throw invalid(missing);
        }
        return out;
    }

    /**
     * Checks answers against the questions and returns them in question order; unknown questions are refused.
     *
     * @throws ApiException {@link ProblemCode#VALIDATION_FAILED} naming the question
     */
    static Map<String, Object> answers(Schema schema, JsonNode given) {
        if (given == null || !given.isObject()) {
            throw invalid("Send the answers as an object keyed by question.");
        }
        Set<String> known = new HashSet<>();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : schema.fields()) {
            known.add(f.id());
            JsonNode a = given.get(f.id());
            boolean empty = a == null || a.isNull();
            Object value = empty ? null : value(f, a);
            boolean missing = value == null
                    || (value instanceof String s && s.isBlank())
                    || (value instanceof List<?> l && l.isEmpty())
                    || (f.kind().equals("CHECKBOX") && Boolean.FALSE.equals(value));
            if (f.required() && missing) {
                throw invalid("Answer the question \"" + any(f.label()) + "\".");
            }
            if (value != null) {
                out.put(f.id(), value);
            }
        }
        given.propertyNames().forEach(name -> {
            if (!known.contains(name)) {
                throw invalid("There is no question " + name + ".");
            }
        });
        return out;
    }

    private static Object value(Field f, JsonNode a) {
        String label = any(f.label());
        switch (f.kind()) {
            case "TEXT", "LONG_TEXT" -> {
                int max = f.kind().equals("TEXT") ? 500 : 4000;
                if (!a.isString() || a.asString().length() > max) {
                    throw invalid("\"" + label + "\" takes text of up to " + max + " characters.");
                }
                return a.asString().strip();
            }
            case "YES_NO", "CHECKBOX" -> {
                if (!a.isBoolean()) {
                    throw invalid("\"" + label + "\" takes true or false.");
                }
                return a.asBoolean();
            }
            case "SINGLE_CHOICE" -> {
                if (!a.isString() || f.options().stream().noneMatch(o -> o.id().equals(a.asString()))) {
                    throw invalid("Choose one of the options for \"" + label + "\".");
                }
                return a.asString();
            }
            case "MULTI_CHOICE" -> {
                if (!a.isArray()) {
                    throw invalid("\"" + label + "\" takes a list of options.");
                }
                Set<String> chosen = new java.util.LinkedHashSet<>();
                for (JsonNode o : a) {
                    if (!o.isString() || f.options().stream().noneMatch(x -> x.id().equals(o.asString()))) {
                        throw invalid("Choose from the options for \"" + label + "\".");
                    }
                    chosen.add(o.asString());
                }
                return List.copyOf(chosen);
            }
            case "DATE" -> {
                try {
                    return LocalDate.parse(a.asString()).toString();
                } catch (DateTimeParseException e) {
                    throw invalid("\"" + label + "\" takes a date such as 2026-11-24.");
                }
            }
            default -> throw invalid("Unknown kind of question.");
        }
    }

    static String any(Map<String, String> text) {
        if (text == null || text.isEmpty()) return "";
        return text.getOrDefault("en", text.values().iterator().next());
    }

    private static ApiException invalid(String detail) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, detail);
    }
}
