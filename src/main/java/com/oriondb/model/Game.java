package com.oriondb.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A chess game: PGN tags plus a move tree.
 *
 * The id is assigned by the database (-1 for games not yet in one). Tags keep
 * insertion order. The move tree preserves variations, comments, and NAGs.
 */
public final class Game {
    public static final String UNKNOWN_DATE = "????.??.??";
    private static final Pattern TAG_NAME = Pattern.compile("[A-Za-z0-9_]+");

    private int id;
    private final LinkedHashMap<String, String> tags;
    private MoveNode root;              // first mainline move, or null
    private String gameComment;         // comment before the first move
    private boolean readOnly;

    public Game() {
        this.id = -1;
        this.tags = new LinkedHashMap<>();
    }

    public Game(Map<String, String> tags, MoveNode root) {
        this.id = -1;
        this.tags = new LinkedHashMap<>();
        Objects.requireNonNull(tags, "tags").forEach(this::setTag);
        this.root = root;
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    public int id() {
        return id;
    }

    /** Assigned by the database layer. */
    public void setId(int id) {
        requireMutable();
        this.id = id;
    }

    // ------------------------------------------------------------------
    // Tags
    // ------------------------------------------------------------------

    public Map<String, String> tags() {
        return Collections.unmodifiableMap(tags);
    }

    public String tag(String key) {
        return tags.get(key);
    }

    public String tag(String key, String defaultValue) {
        return tags.getOrDefault(key, defaultValue);
    }

    public void setTag(String key, String value) {
        requireMutable();
        validateTag(key, value);
        if (value == null) {
            tags.remove(key);
        } else {
            tags.put(key, value);
        }
    }

    public String white() {
        return tag("White", "?");
    }

    public String black() {
        return tag("Black", "?");
    }

    public String event() {
        return tag("Event", "?");
    }

    public String site() {
        return tag("Site", "?");
    }

    public String date() {
        return tag("Date", UNKNOWN_DATE);
    }

    public String round() {
        return tag("Round", "?");
    }

    public String result() {
        return tag("Result", "*");
    }

    public String eco() {
        return tag("ECO");
    }

    public String opening() {
        return tag("Opening");
    }

    /** Normalized categorical tags stored in the portable OrionTags PGN header. */
    public Set<String> gameTags() {
        return GameTags.parse(tag(GameTags.PGN_TAG));
    }

    public boolean hasGameTag(String gameTag) {
        return gameTags().contains(GameTags.normalize(gameTag));
    }

    public Integer whiteElo() {
        return parseElo(tag("WhiteElo"));
    }

    public Integer blackElo() {
        return parseElo(tag("BlackElo"));
    }

    /** Average of the Elos present; null when neither player has one. */
    public Integer averageElo() {
        Integer w = whiteElo();
        Integer b = blackElo();
        if (w == null) return b;
        if (b == null) return w;
        return (w + b) / 2;
    }

    private static Integer parseElo(String elo) {
        if (elo == null || elo.isEmpty() || elo.equals("?") || elo.equals("-")) return null;
        try {
            int value = Integer.parseInt(elo.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Date in a form that compares correctly as a string: unknown components
     * are zero-filled ("1985.??.??" -&gt; "1985.00.00"). Returns null when the
     * year is unknown.
     */
    public String sortableDate() {
        String date = date();
        if (date.length() < 4) return null;
        String year = date.substring(0, 4);
        if (!year.chars().allMatch(Character::isDigit)) return null;
        String month = date.length() >= 7 ? date.substring(5, 7) : "00";
        String day = date.length() >= 10 ? date.substring(8, 10) : "00";
        if (!month.chars().allMatch(Character::isDigit)) month = "00";
        if (!day.chars().allMatch(Character::isDigit)) day = "00";
        return year + "." + month + "." + day;
    }

    // ------------------------------------------------------------------
    // Moves
    // ------------------------------------------------------------------

    /** First mainline move, or null for a game without moves. */
    public MoveNode root() {
        return root;
    }

    public void setRoot(MoveNode root) {
        requireMutable();
        this.root = root;
    }

    /** Comment before the first move, or null. */
    public String gameComment() {
        return gameComment;
    }

    public void setGameComment(String comment) {
        requireMutable();
        this.gameComment = comment;
    }

    /** Deep, mutable copy suitable for editing and {@code Database.replace}. */
    public Game copy() {
        Game copy = new Game(tags, root != null ? root.copy() : null);
        copy.id = id;
        copy.gameComment = gameComment;
        return copy;
    }

    /** Make this game and its move tree read-only. Used by the database. */
    public void makeReadOnly() {
        if (readOnly) return;
        if (root != null) root.makeReadOnly();
        readOnly = true;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    private void requireMutable() {
        if (readOnly) {
            throw new UnsupportedOperationException(
                "Database games are read-only; copy the game and call Database.replace");
        }
    }

    private static void validateTag(String key, String value) {
        Objects.requireNonNull(key, "tag key");
        if (!TAG_NAME.matcher(key).matches()) {
            throw new IllegalArgumentException(
                "Invalid PGN tag name '" + key + "'; use letters, digits, and underscores");
        }
        if (value != null && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
            throw new IllegalArgumentException("PGN tag values must not contain line breaks");
        }
    }

    /** Mainline moves as SAN strings. */
    public List<String> mainlineSans() {
        List<String> sans = new ArrayList<>();
        for (MoveNode node = root; node != null; node = node.next()) {
            sans.add(node.san());
        }
        return sans;
    }

    /** Number of mainline plies. */
    public int plyCount() {
        int count = 0;
        for (MoveNode node = root; node != null; node = node.next()) {
            count++;
        }
        return count;
    }

    /** True if any node in the tree has a comment, NAG, or variation. */
    public boolean hasAnnotations() {
        if (gameComment != null) return true;
        return hasAnnotations(root);
    }

    private static boolean hasAnnotations(MoveNode node) {
        for (; node != null; node = node.next()) {
            if (node.hasAnnotations()) return true;
            for (MoveNode variation : node.variations()) {
                if (hasAnnotations(variation)) return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return String.format("%s vs %s, %s (%s) %s",
            white(), black(), event(), date(), result());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Game)) return false;
        Game other = (Game) o;
        return id != -1 && id == other.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
