package com.oriondb.pgn;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Text-level PGN helpers: decoding and editing tag pairs without re-rendering movetext. */
public final class PgnText {
    private static final Charset LEGACY = Charset.forName("windows-1252");

    private PgnText() {}

    /** Decodes UTF-8, falling back to Windows-1252 (a Latin-1 superset) for invalid UTF-8. */
    public static String decode(byte[] bytes) {
        try {
            CharBuffer cb = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
            return cb.toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, LEGACY);
        }
    }

    public static String escapeTagValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace('\n', ' ').replace('\r', ' ');
    }

    /** Reads the tag pairs at the start of a game's text (no movetext parsing). */
    public static Map<String, String> readTags(String gameText) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (String line : gameText.split("\n")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("%")) continue;
            if (!t.startsWith("[")) break;
            String[] kv = parseTagLine(t);
            if (kv != null) tags.putIfAbsent(kv[0], kv[1]);
        }
        return tags;
    }

    /** Parses a single {@code [Name "value"]} line leniently; null if it is not a tag. */
    static String[] parseTagLine(String t) {
        int i = 1, n = t.length();
        while (i < n && Character.isWhitespace(t.charAt(i))) i++;
        int ns = i;
        while (i < n && !Character.isWhitespace(t.charAt(i)) && t.charAt(i) != '"' && t.charAt(i) != ']') i++;
        String name = t.substring(ns, i);
        if (name.isEmpty() || name.startsWith("%")) return null;
        while (i < n && Character.isWhitespace(t.charAt(i))) i++;
        StringBuilder value = new StringBuilder();
        if (i < n && t.charAt(i) == '"') {
            i++;
            boolean closed = false;
            while (i < n) {
                char c = t.charAt(i++);
                if (c == '\\' && i < n && (t.charAt(i) == '"' || t.charAt(i) == '\\')) {
                    value.append(t.charAt(i++));
                } else if (c == '"') {
                    // A quote counts as closing only if followed by optional spaces and ']' (or end).
                    String rest = t.substring(i).strip();
                    if (rest.isEmpty() || rest.startsWith("]")) { closed = true; break; }
                    value.append(c);
                } else {
                    value.append(c);
                }
            }
            if (!closed) {
                String v = value.toString();
                if (v.endsWith("]")) v = v.substring(0, v.length() - 1);
                return new String[] {name, v.strip()};
            }
        } else {
            int close = t.lastIndexOf(']');
            value.append(t, i, close > i ? close : n);
        }
        return new String[] {name, value.toString()};
    }

    /** Sets (or with a null value removes) a tag in raw game text, keeping everything else byte-identical. */
    public static String setTag(String gameText, String name, String value) {
        String[] lines = gameText.split("\n", -1);
        StringBuilder out = new StringBuilder(gameText.length() + 32);
        boolean done = false;
        int lastTagLine = -1;
        int i = 0;
        for (; i < lines.length; i++) {
            String t = lines[i].strip();
            if (t.isEmpty() || t.startsWith("%")) continue;
            if (!t.startsWith("[")) break;
            lastTagLine = i;
        }
        for (int k = 0; k < lines.length; k++) {
            String line = lines[k];
            if (!done && k <= lastTagLine) {
                String t = line.strip();
                if (t.startsWith("[")) {
                    String[] kv = parseTagLine(t);
                    if (kv != null && kv[0].equals(name)) {
                        done = true;
                        if (value != null) appendLine(out, tagLine(name, value), k, lines.length);
                        continue;
                    }
                }
            }
            appendLine(out, line, k, lines.length);
            if (!done && value != null && k == lastTagLine) {
                if (k == lines.length - 1) out.append('\n').append(tagLine(name, value));
                else out.append(tagLine(name, value)).append('\n');
                done = true;
            }
        }
        if (!done && value != null) return tagLine(name, value) + "\n" + gameText;
        return out.toString();
    }

    private static void appendLine(StringBuilder out, String line, int k, int total) {
        out.append(line);
        if (k < total - 1) out.append('\n');
    }

    public static String tagLine(String name, String value) {
        return "[" + name + " \"" + escapeTagValue(value) + "\"]";
    }
}
