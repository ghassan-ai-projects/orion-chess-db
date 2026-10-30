package com.oriondb.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** PGN tag pairs, kept in insertion order; written Seven Tag Roster first. */
public final class Tags {
    public static final List<String> SEVEN_TAG_ROSTER = List.of("Event", "Site", "Date", "Round", "White", "Black", "Result");

    private final LinkedHashMap<String, String> map = new LinkedHashMap<>();

    public String get(String name) {
        return map.get(name);
    }

    public String get(String name, String fallback) {
        String v = map.get(name);
        return v == null ? fallback : v;
    }

    public void set(String name, String value) {
        if (!isValidName(name)) throw new IllegalArgumentException("invalid tag name '" + name + "'");
        if (value == null) map.remove(name);
        else map.put(name, value);
    }

    public String remove(String name) {
        return map.remove(name);
    }

    public boolean has(String name) {
        return map.containsKey(name);
    }

    public int size() {
        return map.size();
    }

    /** Entries in export order: Seven Tag Roster (with "?" defaults), then the rest. */
    public List<Map.Entry<String, String>> exportOrder() {
        List<Map.Entry<String, String>> out = new ArrayList<>(map.size() + 7);
        for (String name : SEVEN_TAG_ROSTER) {
            out.add(Map.entry(name, map.getOrDefault(name, name.equals("Result") ? "*" : name.equals("Date") ? "????.??.??" : "?")));
        }
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!SEVEN_TAG_ROSTER.contains(e.getKey())) out.add(Map.entry(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** Read-only view in insertion order. */
    public Map<String, String> asMap() {
        return java.util.Collections.unmodifiableMap(map);
    }

    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty()) return false;
        for (char c : name.toCharArray()) {
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.')) return false;
        }
        return true;
    }
}
