package com.oriondb.query;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Forgiving name matching: case/accent-insensitive, order-insensitive tokens ("magnus carlsen" ~ "Carlsen, Magnus"). */
public final class Names {
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private Names() {}

    /** Lowercase, accents stripped, punctuation turned into single spaces. */
    public static String normalize(String s) {
        if (s == null) return "";
        String n = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
        return NON_WORD.matcher(n.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    /** Compiled query: every token must occur in the name. */
    public static final class Matcher {
        private final String[] tokens;

        public Matcher(String query) {
            String n = normalize(query);
            tokens = n.isEmpty() ? new String[0] : n.split(" ");
        }

        public boolean matches(String name) {
            if (tokens.length == 0) return true;
            String n = normalize(name);
            for (String t : tokens) if (!n.contains(t)) return false;
            return true;
        }
    }
}
