package com.oriondb.query;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Forgiving name matching: case- and accent-insensitive, any token order,
 * each query token matching the start of a name token.
 *
 * <p>Player names get one more rule, because databases abbreviate given names
 * ("Carlsen,M", "Nakamura,Hi"): an abbreviated given name matches any query
 * token it is a prefix of, so "magnus carlsen" finds "Carlsen,M" but not
 * "Carlsen,H".
 */
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

    private static String[] tokens(String s) {
        String n = normalize(s);
        return n.isEmpty() ? new String[0] : n.split(" ");
    }

    /**
     * A person's name split into surname and given-name tokens. Understands
     * "Surname, Given", "Surname,G", "Given Surname" and "Surname G".
     */
    public record PersonName(List<String> surname, List<String> given) {
        public static PersonName parse(String raw) {
            if (raw == null) return new PersonName(List.of(), List.of());
            int comma = raw.indexOf(',');
            if (comma >= 0) {
                return new PersonName(List.of(tokens(raw.substring(0, comma))), List.of(tokens(raw.substring(comma + 1))));
            }
            String[] t = tokens(raw);
            if (t.length <= 1) return new PersonName(List.of(t), List.of());
            if (t[t.length - 1].length() <= 2) {
                // "Carlsen M": surname first, initials last
                List<String> sur = new ArrayList<>(), giv = new ArrayList<>();
                for (String x : t) (x.length() <= 2 && !sur.isEmpty() ? giv : sur).add(x);
                return new PersonName(sur, giv);
            }
            return new PersonName(List.of(t[t.length - 1]), List.of(t).subList(0, t.length - 1));
        }

        /** True if the given name is only initials or a short abbreviation. */
        public boolean abbreviated() {
            return given.isEmpty() || given.get(0).length() <= 2;
        }

        public String firstGiven() {
            return given.isEmpty() ? "" : given.get(0);
        }

        public String surnameText() {
            return String.join(" ", surname);
        }
    }

    /** Compiled query: every token must match the start of some name token. */
    public static final class Matcher {
        private final String[] query;
        private final boolean person;

        /** General text (events, sites, openings). */
        public Matcher(String query) {
            this(query, false);
        }

        private Matcher(String query, boolean person) {
            this.query = tokens(query);
            this.person = person;
        }

        /** Player names: abbreviated given names also match full ones. */
        public static Matcher person(String query) {
            return new Matcher(query, true);
        }

        public boolean matches(String name) {
            if (query.length == 0) return true;
            if (!person) {
                String[] n = tokens(name);
                for (String q : query) if (!anyPrefix(n, q)) return false;
                return true;
            }
            PersonName p = PersonName.parse(name);
            for (String q : query) {
                boolean ok = false;
                for (String s : p.surname()) if (s.startsWith(q)) { ok = true; break; }
                if (!ok) {
                    for (String g : p.given()) {
                        if (g.startsWith(q) || (g.length() <= 2 && q.startsWith(g))) { ok = true; break; }
                    }
                }
                if (!ok) return false;
            }
            return true;
        }

        private static boolean anyPrefix(String[] name, String q) {
            for (String n : name) if (n.startsWith(q)) return true;
            return false;
        }
    }
}
