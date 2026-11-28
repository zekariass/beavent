package com.beavent.backend.marketing.internal;

import java.util.Map;

/** How a campaign's text becomes the SMS a person reads: their name, their language, the link and the stop line. */
final class Messages {

    private Messages() {}

    /** A token as long as the real ones, to size a preview. */
    static final String SAMPLE_TOKEN = "xxxxxxxxxxxxxxxx";

    /** The person's language's text, or the other language's when this one was left empty. */
    static String pick(Map<String, String> texts, String language) {
        String lang = "am".equals(language) ? "am" : "en";
        String own = texts.get(lang);
        if (own != null && !own.isBlank()) {
            return own;
        }
        String other = texts.get(lang.equals("am") ? "en" : "am");
        return other == null ? "" : other;
    }

    /** The text with {@code {name}} filled in (or taken out when the person's name is not known). */
    static String personal(String text, String firstName) {
        String name = firstName == null ? "" : firstName.strip();
        return text.replace("{name}", name)
                .replaceAll(" {2,}", " ")
                .replace(" ,", ",")
                .strip();
    }

    /** The whole message: text, then the counted link, then how to stop. */
    static String compose(String text, String firstName, String language, String link, String stop) {
        boolean am = "am".equals(language);
        return personal(text, firstName) + " " + link + " " + (am ? "ለማቆም፦ " : "Stop: ") + stop;
    }

    /** SMS parts: 160 characters in one (153 each when longer) for plain Latin text, 70 (67) once any other script is in it. */
    static int segments(String text) {
        int n = text.codePointCount(0, text.length());
        boolean plain = text.chars().allMatch(c -> c < 128);
        if (plain) {
            return n <= 160 ? 1 : (int) Math.ceil(n / 153.0);
        }
        return n <= 70 ? 1 : (int) Math.ceil(n / 67.0);
    }
}
