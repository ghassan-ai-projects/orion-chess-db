package com.oriondb.model;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * First-class categorical tags for games.
 *
 * Tags are stored in the portable {@value #PGN_TAG} PGN header as a
 * comma-separated list. Identity is case-insensitive: values are normalized
 * to lowercase with collapsed whitespace and written in sorted order.
 */
public final class GameTags {
    public static final String PGN_TAG = "OrionTags";
    private static final int MAX_LENGTH = 64;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private GameTags() {}

    /** Parse a stored {@value #PGN_TAG} value. */
    public static Set<String> parse(String value) {
        if (value == null || value.isBlank()) return Set.of();
        TreeSet<String> tags = new TreeSet<>();
        for (String part : value.split(",", -1)) {
            if (!part.isBlank()) {
                tags.add(normalize(part));
            }
        }
        return immutableOrdered(tags);
    }

    /** Normalize and deduplicate tags supplied through an API. */
    public static Set<String> normalizeAll(Collection<String> values) {
        Objects.requireNonNull(values, "tags");
        TreeSet<String> tags = new TreeSet<>();
        for (String value : values) {
            tags.add(normalize(value));
        }
        return immutableOrdered(tags);
    }

    /** Canonical persisted representation, or null for an empty collection. */
    public static String format(Collection<String> values) {
        Set<String> tags = normalizeAll(values);
        return tags.isEmpty() ? null : String.join(", ", tags);
    }

    /** Canonical form used for storage and matching. */
    public static String normalize(String value) {
        Objects.requireNonNull(value, "tag");
        String normalized = WHITESPACE.matcher(value.trim()).replaceAll(" ")
            .toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Game tag must not be blank");
        }
        if (normalized.indexOf(',') >= 0) {
            throw new IllegalArgumentException(
                "Game tag must not contain a comma: '" + value + "'");
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Game tag must not contain control characters");
        }
        if (normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                "Game tag must be at most " + MAX_LENGTH + " characters");
        }
        return normalized;
    }

    private static Set<String> immutableOrdered(Collection<String> tags) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(tags));
    }
}
