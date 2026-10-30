package com.oriondb.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Parsed command-line arguments: positionals plus {@code --flag value},
 * {@code --flag=value} and boolean {@code --flag}s. Only flags declared in the
 * command's {@link Spec} are accepted; anything else is a usage error that
 * lists the valid flags. A token starting with a single dash is positional
 * unless it is a declared short alias (so {@code -} and query terms such as
 * {@code -label:x} pass through), and {@code --} ends flag parsing.
 */
final class Args {

    /** Declared flags of a command: value-taking flags, boolean flags and short aliases. */
    record Spec(Set<String> valueFlags, Set<String> booleanFlags, Map<String, String> aliases) {
        static Spec of(String... flags) {
            Set<String> values = new LinkedHashSet<>(), bools = new LinkedHashSet<>();
            Map<String, String> aliases = new LinkedHashMap<>();
            for (String f : flags) {
                // "name=" takes a value; "name" is boolean; "o|output=" declares a short alias
                String name = f;
                int bar = name.indexOf('|');
                if (bar > 0) {
                    String alias = name.substring(0, bar);
                    name = name.substring(bar + 1);
                    aliases.put(alias, name.endsWith("=") ? name.substring(0, name.length() - 1) : name);
                }
                if (name.endsWith("=")) values.add(name.substring(0, name.length() - 1));
                else bools.add(name);
            }
            return new Spec(values, bools, aliases);
        }

        /** The declared flag nearest to a misspelling, or null. */
        String closest(String name) {
            String best = null;
            int bestD = 3;
            for (String f : valueFlags) if (Main.distance(name, f) < bestD) { bestD = Main.distance(name, f); best = f; }
            for (String f : booleanFlags) if (Main.distance(name, f) < bestD) { bestD = Main.distance(name, f); best = f; }
            return best;
        }

        String describe() {
            List<String> out = new ArrayList<>();
            for (String v : new TreeSet<>(valueFlags)) out.add("--" + v + " <value>");
            for (String b : new TreeSet<>(booleanFlags)) out.add("--" + b);
            return out.isEmpty() ? "(none)" : String.join(", ", out);
        }
    }

    private final List<String> positionals;
    private final Map<String, String> values;
    private final Set<String> flags;

    private Args(List<String> positionals, Map<String, String> values, Set<String> flags) {
        this.positionals = positionals;
        this.values = values;
        this.flags = flags;
    }

    static Args parse(String[] argv, Spec spec) {
        return parse(Arrays.asList(argv), spec);
    }

    static Args parse(List<String> argv, Spec spec) {
        List<String> pos = new ArrayList<>();
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> flags = new LinkedHashSet<>();
        boolean flagsDone = false;
        for (int i = 0; i < argv.size(); i++) {
            String a = argv.get(i);
            if (flagsDone) {
                pos.add(a);
                continue;
            }
            if (a.equals("--")) {
                flagsDone = true;
                continue;
            }
            String name, inline = null;
            if (a.startsWith("--") && a.length() > 2) {
                name = a.substring(2);
                int eq = name.indexOf('=');
                if (eq >= 0) {
                    inline = name.substring(eq + 1);
                    name = name.substring(0, eq);
                }
            } else if (a.startsWith("-") && a.length() > 1 && spec.aliases().containsKey(a.substring(1))) {
                name = spec.aliases().get(a.substring(1));
            } else {
                pos.add(a);
                continue;
            }
            if (spec.valueFlags().contains(name)) {
                String v = inline;
                if (v == null) {
                    if (i + 1 >= argv.size()) throw new UsageException("--" + name + " needs a value");
                    v = argv.get(++i);
                }
                if (values.containsKey(name)) throw new UsageException("--" + name + " given twice");
                values.put(name, v);
            } else if (spec.booleanFlags().contains(name)) {
                if (inline != null) throw new UsageException("--" + name + " does not take a value");
                flags.add(name);
            } else {
                String hint = spec.closest(name);
                throw new UsageException("unknown option '" + a + "'" + (hint == null ? "" : " (did you mean --" + hint + "?)")
                    + "; valid options: " + spec.describe());
            }
        }
        return new Args(List.copyOf(pos), values, flags);
    }

    // ------------------------------------------------------------ positionals

    List<String> positionals() {
        return positionals;
    }

    int count() {
        return positionals.size();
    }

    /** Positional {@code i}; a usage error naming {@code what} if missing. */
    String require(int i, String what) {
        if (i >= positionals.size()) throw new UsageException("missing " + what);
        return positionals.get(i);
    }

    /** Positionals from {@code i} on. */
    List<String> rest(int i) {
        return i >= positionals.size() ? List.of() : positionals.subList(i, positionals.size());
    }

    /** Fails if there are more than {@code max} positionals. */
    void maxPositionals(int max) {
        if (positionals.size() > max) {
            throw new UsageException("unexpected argument '" + positionals.get(max) + "'");
        }
    }

    // ------------------------------------------------------------ flags

    boolean has(String flag) {
        return flags.contains(flag) || values.containsKey(flag);
    }

    String get(String flag) {
        return values.get(flag);
    }

    String get(String flag, String fallback) {
        return values.getOrDefault(flag, fallback);
    }

    int intValue(String flag, int fallback) {
        String v = values.get(flag);
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            throw new UsageException("--" + flag + " expects a whole number, got '" + v + "'");
        }
    }

    int positiveInt(String flag, int fallback) {
        int n = intValue(flag, fallback);
        if (has(flag) && n < 1) throw new UsageException("--" + flag + " must be at least 1");
        return n;
    }

    /** One of {@code allowed} (first is the default). */
    String choice(String flag, String... allowed) {
        String v = values.get(flag);
        if (v == null) return allowed[0];
        for (String a : allowed) if (a.equalsIgnoreCase(v.strip())) return a;
        throw new UsageException("--" + flag + " must be one of " + String.join("|", allowed) + ", got '" + v + "'");
    }

    /** Comma-separated list (empty if absent). */
    List<String> list(String flag) {
        String v = values.get(flag);
        if (v == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : v.split(",")) if (!s.isBlank()) out.add(s.strip());
        return out;
    }

    /** Fails if more than one of these flags is present. */
    void exclusive(String... names) {
        String seen = null;
        for (String n : names) {
            if (!has(n)) continue;
            if (seen != null) throw new UsageException("--" + seen + " and --" + n + " cannot be combined");
            seen = n;
        }
    }
}
