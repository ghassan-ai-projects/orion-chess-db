package com.oriondb.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rows of cells printed as an aligned text table, tab-separated values or
 * JSON. Numeric cells are right-aligned; long text is truncated to a column's
 * maximum width in table form only (tsv and json keep full values).
 */
final class Table {
    static final String[] FORMATS = {"table", "tsv", "json"};
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    private final String[] headers;
    private final int[] maxWidth;
    private final List<Object[]> rows = new ArrayList<>();

    Table(String... headers) {
        this.headers = headers;
        this.maxWidth = new int[headers.length];
        java.util.Arrays.fill(maxWidth, 40);
    }

    /** Caps a column's width in table output. */
    Table maxWidth(String header, int width) {
        for (int i = 0; i < headers.length; i++) if (headers[i].equals(header)) maxWidth[i] = width;
        return this;
    }

    Table row(Object... cells) {
        if (cells.length != headers.length) throw new IllegalArgumentException("expected " + headers.length + " cells");
        rows.add(cells);
        return this;
    }

    int size() {
        return rows.size();
    }

    void print(PrintStream out, String format) {
        switch (format) {
            case "tsv" -> printTsv(out);
            case "json" -> out.println(GSON.toJson(toJson()));
            default -> printTable(out);
        }
    }

    List<Map<String, Object>> toJson() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < headers.length; i++) m.put(jsonKey(headers[i]), r[i]);
            out.add(m);
        }
        return out;
    }

    private static String jsonKey(String header) {
        return header.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    private void printTsv(PrintStream out) {
        out.println(String.join("\t", headers));
        for (Object[] r : rows) {
            List<String> cells = new ArrayList<>();
            for (Object c : r) cells.add(text(c).replace('\t', ' ').replace('\n', ' '));
            out.println(String.join("\t", cells));
        }
    }

    private void printTable(PrintStream out) {
        int n = headers.length;
        int[] width = new int[n];
        boolean[] numeric = new boolean[n];
        java.util.Arrays.fill(numeric, true);
        List<String[]> cells = new ArrayList<>();
        for (Object[] r : rows) {
            String[] s = new String[n];
            for (int i = 0; i < n; i++) {
                s[i] = truncate(text(r[i]), maxWidth[i]);
                if (r[i] != null && !(r[i] instanceof Number) && !looksNumeric(s[i])) numeric[i] = false;
            }
            cells.add(s);
        }
        for (int i = 0; i < n; i++) {
            width[i] = headers[i].length();
            for (String[] s : cells) width[i] = Math.max(width[i], s[i].length());
        }
        out.println(line(headers, width, numeric));
        StringBuilder rule = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) rule.append("  ");
            rule.append("-".repeat(width[i]));
        }
        out.println(rule);
        for (String[] s : cells) out.println(line(s, width, numeric));
    }

    private static String line(String[] cells, int[] width, boolean[] right) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append("  ");
            String c = cells[i];
            String pad = " ".repeat(width[i] - c.length());
            if (right[i]) sb.append(pad).append(c);
            else if (i == cells.length - 1) sb.append(c);
            else sb.append(c).append(pad);
        }
        return sb.toString().stripTrailing();
    }

    /** Numbers, percentages and the "-" placeholder are right-aligned like numbers. */
    private static boolean looksNumeric(String s) {
        return s.isEmpty() || s.equals("-") || s.matches("[-+]?\\d+(\\.\\d+)?%?");
    }

    static String text(Object o) {
        if (o == null) return "";
        if (o instanceof Double d) return String.format(Locale.ROOT, "%.1f", d);
        return o.toString();
    }

    static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(1, max - 1)) + "…";
    }
}
