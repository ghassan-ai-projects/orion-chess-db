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
 * Search criteria, all AND-combined. Built fluently ({@code new Query().player("Carlsen").minElo(2700)})
 * or from text with {@link QueryParser}. Unset criteria match everything.
 */
public final class Query {
    public enum EloMode { EITHER, BOTH, AVERAGE }
    public enum Color { ANY, WHITE, BLACK }
    public enum Outcome { WIN, DRAW, LOSS }
    public enum Sort { ID, DATE, WHITE, BLACK, EVENT, ELO, PLY, ECO }

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
