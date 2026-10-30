package com.oriondb.query;

import com.oriondb.analysis.Motif;
import com.oriondb.analysis.PawnFeature;
import com.oriondb.chess.Move;
import com.oriondb.chess.Position;
import com.oriondb.chess.San;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Search criteria. A query's own criteria are AND-combined; sub-queries add
 * boolean structure: every {@link #and} sub-query must match, at least one
 * {@link #or} sub-query must match (when there are any), and no {@link #not}
 * sub-query may match. Built fluently
 * ({@code new Query().player("Carlsen").minElo(2700).or(new Query().eco("B90-B99"), new Query().eco("C00-C19"))})
 * or from text with {@link QueryParser}. Unset criteria match everything.
 * Sorting and paging are taken from the top-level query only.
 */
public final class Query {
    public enum EloMode { EITHER, BOTH, AVERAGE }
    public enum Color { ANY, WHITE, BLACK }
    public enum Outcome { WIN, DRAW, LOSS }
    public enum Sort { ID, DATE, WHITE, BLACK, EVENT, ELO, WHITE_ELO, BLACK_ELO, PLY, ECO }

    String white, black, player, event, site, round, opening, text, comment;
    Color playerColor = Color.ANY;
    Outcome outcome;
    int dateFrom, dateTo;
    Set<Integer> results;
    String ecoFrom, ecoTo;
    int minElo, maxElo;
    EloMode eloMode = EloMode.EITHER;
    int minPly, maxPly;
    short[] line;
    Position position;
    boolean positionInVariations;
    PiecePattern pattern;
    long material = -1;
    boolean materialExactColors;
    int maxPieces;
    Set<Motif> motifs = EnumSet.noneOf(Motif.class);
    Color motifBy = Color.ANY;
    Set<PawnFeature> features = EnumSet.noneOf(PawnFeature.class);
    Color featureColor = Color.ANY;
    Set<String> labelsAll = new LinkedHashSet<>(), labelsAny = new LinkedHashSet<>(), labelsNone = new LinkedHashSet<>();
    Boolean annotated, hasComments, hasVariations, hasEvals, chess960;
    Map<String, String> tagEquals = new LinkedHashMap<>();
    Set<Integer> ids;
    List<MovePattern> moves = new ArrayList<>();
    List<MovePattern> sequence;
    Color moveBy = Color.ANY;
    int moveFrom, moveTo;          // full-move number range for move:/sequence:, 0 = open
    Integer balanceMin, balanceMax;
    final List<Query> allOf = new ArrayList<>(), anyOf = new ArrayList<>(), noneOf = new ArrayList<>();
    String after;
    Sort sort = Sort.ID;
    boolean descending;
    int offset, limit = 50;

    public Query white(String v) { white = blankToNull(v); return this; }
    public Query black(String v) { black = blankToNull(v); return this; }
    /** Games of a player (either color unless {@link #playerColor} is set). */
    public Query player(String v) { player = blankToNull(v); return this; }
    public Query playerColor(Color c) { playerColor = c; return this; }
    /** Result from {@link #player}'s point of view. */
    public Query outcome(Outcome o) { outcome = o; return this; }
    public Query event(String v) { event = blankToNull(v); return this; }
    public Query site(String v) { site = blankToNull(v); return this; }
    public Query round(String v) { round = blankToNull(v); return this; }
    public Query opening(String v) { opening = blankToNull(v); return this; }
    /** Free text over players, event, site and opening name. */
    public Query text(String v) { text = blankToNull(v); return this; }
    /** Substring of the game's comments. */
    public Query comment(String v) { comment = v == null ? null : v.toLowerCase(Locale.ROOT); return this; }

    /** Inclusive date range; partial dates like "2020" or "2020.05" are widened appropriately. */
    public Query dateRange(String from, String to) {
        dateFrom = from == null ? 0 : parseDate(from, false);
        dateTo = to == null ? 0 : parseDate(to, true);
        return this;
    }

    /** Result codes from {@code GameRecord} (WHITE_WINS, BLACK_WINS, DRAW, UNKNOWN). */
    public Query results(Set<Integer> codes) { results = codes == null || codes.isEmpty() ? null : Set.copyOf(codes); return this; }

    /** "B90", prefix "B9", or range "B90-B99". */
    public Query eco(String spec) {
        if (spec == null || spec.isBlank()) { ecoFrom = ecoTo = null; return this; }
        String s = spec.strip().toUpperCase(Locale.ROOT);
        if (s.contains("-")) {
            String[] p = s.split("-", 2);
            ecoFrom = p[0].strip();
            ecoTo = p[1].strip();
        } else {
            ecoFrom = s;
            ecoTo = s.length() >= 3 ? s : s + "~"; // prefix: everything starting with it
        }
        return this;
    }

    public Query elo(int min, int max, EloMode mode) { minElo = min; maxElo = max; eloMode = mode; return this; }
    public Query minElo(int min) { minElo = min; return this; }
    public Query eloMode(EloMode m) { eloMode = m; return this; }
    public Query plies(int min, int max) { minPly = min; maxPly = max; return this; }

    /** Mainline starts with these moves (SAN, move numbers optional). */
    public Query line(String moves) {
        if (moves == null || moves.isBlank()) { line = null; return this; }
        List<Short> packed = new ArrayList<>();
        Position p = Position.start();
        for (String tok : moves.trim().split("\\s+")) {
            String t = tok.replaceFirst("^\\d+\\.+", "");
            if (t.isEmpty()) continue;
            int m = San.parse(p, t);
            packed.add(Move.pack(m));
            p = p.play(m);
        }
        line = new short[packed.size()];
        for (int i = 0; i < line.length; i++) line[i] = packed.get(i);
        return this;
    }

    /** Games whose mainline reaches this position, in any move order. */
    public Query position(Position p) { position = p; return this; }
    /** Also match {@link #position} when it occurs only inside a game's variations (reported ply -1). */
    public Query positionInVariations(boolean b) { positionInVariations = b; return this; }
    /** Piece pattern reached on the mainline. */
    public Query pattern(PiecePattern p) { pattern = p; return this; }

    /** Material signature reached (either color assignment unless {@code exactColors}). */
    public Query material(long signature, boolean exactColors) { material = signature; materialExactColors = exactColors; return this; }
    /** Positions with at most this many pieces (kings included) are reached. */
    public Query maxPieces(int n) { maxPieces = n; return this; }
    public Query motif(Motif m) { motifs.add(m); return this; }
    public Query motifBy(Color c) { motifBy = c; return this; }
    public Query feature(PawnFeature f) { features.add(f); return this; }
    public Query featureColor(Color c) { featureColor = c; return this; }
    public Query withLabel(String l) { labelsAll.add(normalizeLabel(l)); return this; }
    public Query withAnyLabel(String l) { labelsAny.add(normalizeLabel(l)); return this; }
    public Query withoutLabel(String l) { labelsNone.add(normalizeLabel(l)); return this; }
    public Query annotated(Boolean b) { annotated = b; return this; }
    public Query hasComments(Boolean b) { hasComments = b; return this; }
    public Query hasVariations(Boolean b) { hasVariations = b; return this; }
    public Query hasEvals(Boolean b) { hasEvals = b; return this; }
    public Query chess960(Boolean b) { chess960 = b; return this; }
    /** Arbitrary tag equals value (case-insensitive); value "*" means the tag is present. */
    public Query tag(String name, String value) { tagEquals.put(name, value); return this; }
    public Query ids(Set<Integer> v) { ids = v == null ? null : Set.copyOf(v); return this; }
    /** A mainline move fitting this pattern was played (repeatable: all must occur). */
    public Query move(MovePattern m) { moves.add(m); return this; }
    /** These moves were played consecutively somewhere on the mainline. */
    public Query sequence(List<MovePattern> s) { sequence = s == null || s.isEmpty() ? null : List.copyOf(s); return this; }
    /** Restrict {@link #move}/{@link #sequence} to moves by one side. */
    public Query moveBy(Color c) { moveBy = c; return this; }
    /** Restrict {@link #move}/{@link #sequence} to full-move numbers in [from, to] (0 = open end). */
    public Query moveNumbers(int from, int to) { moveFrom = from; moveTo = to; return this; }
    /** A position with White's material advantage (pawns; negative = Black ahead) in [min, max] is reached. */
    public Query balance(Integer min, Integer max) { balanceMin = min; balanceMax = max; return this; }

    /** Every one of these must also match. */
    public Query and(Query... qs) { allOf.addAll(List.of(qs)); return this; }
    /** At least one of these must match (each call adds one OR group, ANDed with the rest). */
    public Query or(Query... qs) {
        if (qs.length == 1) allOf.add(qs[0]);
        else allOf.add(new Query().anyOfInternal(qs));
        return this;
    }
    private Query anyOfInternal(Query... qs) { anyOf.addAll(List.of(qs)); return this; }
    /** None of these may match. */
    public Query not(Query... qs) { noneOf.addAll(List.of(qs)); return this; }
    /** A query matching if any of {@code qs} matches. */
    public static Query anyOf(Query... qs) { return new Query().anyOfInternal(qs); }

    /**
     * Keyset paging: return results strictly after this cursor (from a previous
     * {@link SearchResult#nextCursor()}). Unlike offsets, pages stay stable when
     * games are added or removed between requests. Overrides the offset.
     */
    public Query after(String cursor) { after = cursor == null || cursor.isBlank() ? null : cursor; return this; }

    public Query sort(Sort s, boolean desc) { sort = s; descending = desc; return this; }
    public Query page(int offset, int limit) { this.offset = Math.max(0, offset); this.limit = limit; return this; }
    /** No pagination: return every match. */
    public Query all() { offset = 0; limit = Integer.MAX_VALUE; return this; }

    public int limit() { return limit; }
    public int offset() { return offset; }

    static String normalizeLabel(String l) {
        return l.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** "2020" -> 20200000 (from) / 20201231 (to); "2020.05" and "2020.05.17" likewise. */
    static int parseDate(String s, boolean upper) {
        String[] p = s.strip().split("[.\\-/]");
        try {
            int y = Integer.parseInt(p[0]);
            int m = p.length > 1 && !p[1].startsWith("?") ? Integer.parseInt(p[1]) : (upper ? 12 : 0);
            int d = p.length > 2 && !p[2].startsWith("?") ? Integer.parseInt(p[2]) : (upper ? 31 : 0);
            return y * 10000 + m * 100 + d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad date '" + s + "' (use YYYY, YYYY.MM or YYYY.MM.DD)");
        }
    }
}
