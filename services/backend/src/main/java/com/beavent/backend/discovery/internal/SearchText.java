package com.beavent.backend.discovery.internal;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How text is prepared for search, for the indexed document and for the query alike (docs/06 §5.5):
 *
 * <ul>
 *   <li>lower case, no accents, punctuation as spaces;
 *   <li>Ge'ez letters that sound the same are folded to one (ሐ ኀ → ሀ, ሠ → ሰ, ዐ → አ, ፀ → ጸ, in every vowel order),
 *       so "ፀጉር" finds "ጸጉር";
 *   <li>a Latin transliteration of Ge'ez words ("ቦሌ" → "bole"), added next to the original, so a Latin query finds a
 *       Ge'ez name and a Ge'ez query finds a Latin name that has the same sound.
 * </ul>
 *
 * The transliteration is deliberately plain (no diacritics, one spelling): people type many spellings, and the trigram
 * match covers the distance between them.
 */
public final class SearchText {

    private SearchText() {}

    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private static final int ETHIOPIC_FIRST = 0x1200;
    private static final int ETHIOPIC_LAST = 0x1357;

    /** First consonant of each row of eight letters from U+1200: the letter in each of the seven vowel orders. */
    private static final String[] ROWS = {
        "h", "l", "h", "m", "s", "r", "s", "sh", "q", "qw", "q", "qw", "b", "v", "t", "ch", "h", "hw", "n", "ny", "",
        "k", "kw", "h", "hw", "w", "", "z", "zh", "y", "d", "d", "j", "g", "gw", "ng", "t", "ch", "p", "ts", "ts", "f",
        "p"
    };
    // ä u i a e ə o ʷa. The seventh order (ə) has no vowel at the end of a word and "i" inside it.
    private static final String[] VOWELS = {"e", "u", "i", "a", "e", "", "o", "wa"};
    private static final String[] GLOTTAL_VOWELS = {"a", "u", "i", "a", "e", "e", "o", "wa"};

    /** Search form of a text: lower case, accents removed, Ge'ez variants folded, words separated by one space. */
    public static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        s = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
        s = Normalizer.normalize(s, Normalizer.Form.NFC);
        s = foldGeez(s);
        return NOT_WORD.matcher(s).replaceAll(" ").trim();
    }

    /** True when the text contains any Ge'ez syllable. */
    public static boolean hasGeez(String text) {
        return text != null && text.codePoints().anyMatch(SearchText::isSyllable);
    }

    private static boolean isSyllable(int cp) {
        return cp >= ETHIOPIC_FIRST && cp <= ETHIOPIC_LAST;
    }

    static String foldGeez(String s) {
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char folded = foldChar(c);
            if (folded != c && out == null) {
                out = new StringBuilder(s.length()).append(s, 0, i);
            }
            if (out != null) {
                out.append(folded);
            }
        }
        return out == null ? s : out.toString();
    }

    private static char foldChar(char c) {
        int offset;
        if ((offset = c - 0x1210) >= 0 && offset < 8) { // ሐ → ሀ
            return (char) (0x1200 + offset);
        }
        if ((offset = c - 0x1280) >= 0 && offset < 8) { // ኀ → ሀ
            return (char) (0x1200 + offset);
        }
        if ((offset = c - 0x1220) >= 0 && offset < 8) { // ሠ → ሰ
            return (char) (0x1230 + offset);
        }
        if ((offset = c - 0x12D0) >= 0 && offset < 8) { // ዐ → አ
            return (char) (0x12A0 + offset);
        }
        if ((offset = c - 0x1340) >= 0 && offset < 8) { // ፀ → ጸ
            return (char) (0x1338 + offset);
        }
        return c;
    }

    /**
     * Latin spelling of the Ge'ez words in an already {@linkplain #normalize normalised} text; other words are left
     * out. "ቦሌ ሳሎን" gives "bole salon".
     */
    public static String romanize(String normalized) {
        if (!hasGeez(normalized)) {
            return "";
        }
        List<String> words = new ArrayList<>();
        for (String word : normalized.split(" ")) {
            if (hasGeez(word)) {
                words.add(romanizeWord(word));
            }
        }
        return String.join(" ", words);
    }

    private static String romanizeWord(String word) {
        StringBuilder out = new StringBuilder();
        int[] points = word.codePoints().toArray();
        for (int i = 0; i < points.length; i++) {
            int cp = points[i];
            if (!isSyllable(cp)) {
                out.appendCodePoint(cp);
                continue;
            }
            int row = (cp - ETHIOPIC_FIRST) / 8;
            int order = (cp - ETHIOPIC_FIRST) % 8;
            if (row >= ROWS.length) {
                continue;
            }
            boolean glottal = ROWS[row].isEmpty();
            String vowel = (glottal ? GLOTTAL_VOWELS : VOWELS)[order];
            if (!glottal && order == 5) {
                boolean last = i == points.length - 1 || !isSyllable(points[i + 1]);
                vowel = last ? "" : "i";
            }
            out.append(ROWS[row]).append(vowel);
        }
        return out.toString();
    }

    /** The words of a normalised text, without repeats. */
    public static Set<String> words(String normalized) {
        Set<String> out = new LinkedHashSet<>();
        if (!normalized.isEmpty()) {
            for (String word : normalized.split(" ")) {
                out.add(word);
            }
        }
        return out;
    }

    /**
     * The searchable words of several texts: each text normalised, then followed by the Latin spelling of its Ge'ez
     * words.
     */
    public static String document(List<String> texts) {
        Set<String> words = new LinkedHashSet<>();
        for (String text : texts) {
            String n = normalize(text);
            words.addAll(words(n));
            words.addAll(words(romanize(n)));
        }
        return String.join(" ", words);
    }

    /**
     * A {@code to_tsquery('simple', …)} string for what the person typed: every word must match, as a prefix, in its
     * own spelling or in its Latin spelling. Empty when nothing searchable was typed.
     */
    public static String tsQuery(String typed) {
        List<String> terms = new ArrayList<>();
        for (String word : words(normalize(typed))) {
            String latin = romanize(word);
            terms.add(
                    latin.isEmpty() || latin.equals(word)
                            ? prefix(word)
                            : "(" + prefix(word) + " | " + prefix(latin) + ")");
        }
        return String.join(" & ", terms);
    }

    /**
     * One typed word: its full-text term (prefix match, in its own spelling or the Latin one) and the spellings the
     * trigram match tries.
     */
    public record Term(String tsQuery, List<String> fuzzy) {}

    /** The typed words one by one; each must match (the full-text term or a close spelling). */
    public static List<Term> terms(String typed) {
        List<Term> terms = new ArrayList<>();
        for (String word : words(normalize(typed))) {
            String latin = romanize(word);
            if (latin.isEmpty() || latin.equals(word)) {
                terms.add(new Term(prefix(word), List.of(word)));
            } else {
                terms.add(new Term("(" + prefix(word) + " | " + prefix(latin) + ")", List.of(word, latin)));
            }
        }
        return terms;
    }

    /** The typed text with each Ge'ez word followed by its Latin spelling, for the trigram match. */
    public static String fuzzy(String typed) {
        String n = normalize(typed);
        String latin = romanize(n);
        return latin.isEmpty() ? n : n + " " + latin;
    }

    private static String prefix(String word) {
        // Words contain only letters and digits after normalisation, so quoting needs no escaping.
        return "'" + word + "':*";
    }
}
