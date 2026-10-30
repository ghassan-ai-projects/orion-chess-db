package com.oriondb.db;

import com.oriondb.analysis.Motif;
import com.oriondb.chess.Move;
import com.oriondb.chess.Position;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The in-memory summary of one game: header fields, the packed mainline and
 * precomputed analysis used by search. Immutable; the full game (variations,
 * comments) is parsed from its PGN text on demand.
 */
public final class GameRecord {
    public static final int WHITE_WINS = 0, BLACK_WINS = 1, DRAW = 2, UNKNOWN = 3;

    public static final int HAS_COMMENTS = 1, HAS_VARIATIONS = 2, HAS_NAGS = 4, CHESS960 = 8,
        SETUP = 16, INVALID = 32, HAS_EVALS = 64, HAS_CLOCKS = 128;

    final int id;
    final String[] tags;          // name/value pairs, file order, without tool tags
    final String white, black, event, site, round;
    final int date;               // yyyymmdd, unknown parts 0
    final byte result;
    final short whiteElo, blackElo;
    final String eco, opening;    // from tags, else the book's
    final String bookEco, bookOpening;
    final int flags;
    final String startFen;        // null for the standard start
    final short[] moves;          // packed mainline
    final long[] materials;       // distinct material signatures reached, sorted
    final int motifMask;
    final int[] motifs;           // (ply << 8) | motif ordinal
    final int pawnMask;
    final String[] labels;        // sorted, lowercase
    final String comments;        // lowercase comment text, or null
    final String error;           // why an INVALID game failed to parse
    final Source source;

    GameRecord(int id, String[] tags, int date, byte result, short whiteElo, short blackElo,
               String eco, String opening, String bookEco, String bookOpening, int flags, String startFen,
               short[] moves, long[] materials, int motifMask, int[] motifs, int pawnMask,
               String[] labels, String comments, String error, Source source) {
        this.id = id;
        this.tags = tags;
        this.white = tagIn(tags, "White");
        this.black = tagIn(tags, "Black");
        this.event = tagIn(tags, "Event");
        this.site = tagIn(tags, "Site");
        this.round = tagIn(tags, "Round");
        this.date = date;
        this.result = result;
        this.whiteElo = whiteElo;
        this.blackElo = blackElo;
        this.eco = eco;
        this.opening = opening;
        this.bookEco = bookEco;
        this.bookOpening = bookOpening;
        this.flags = flags;
        this.startFen = startFen;
        this.moves = moves;
        this.materials = materials;
        this.motifMask = motifMask;
        this.motifs = motifs;
        this.pawnMask = pawnMask;
        this.labels = labels;
        this.comments = comments;
        this.error = error;
        this.source = source;
    }

    private static String tagIn(String[] tags, String name) {
        for (int i = 0; i < tags.length; i += 2) if (tags[i].equals(name)) return tags[i + 1];
        return "";
    }

    // ------------------------------------------------------------ accessors

    public int id() { return id; }
    public String white() { return white; }
    public String black() { return black; }
    public String event() { return event; }
    public String site() { return site; }
    public String round() { return round; }
    /** Date as yyyymmdd with unknown parts 0 (0 = unknown). */
    public int date() { return date; }
    public int year() { return date / 10000; }
    public int resultCode() { return result; }
    public int whiteElo() { return whiteElo; }
    public int blackElo() { return blackElo; }
    public String eco() { return eco; }
    public String opening() { return opening; }
    public int flags() { return flags; }
    public boolean has(int flag) { return (flags & flag) != 0; }
    public boolean isValid() { return (flags & INVALID) == 0; }
    public int plyCount() { return moves.length; }
    public List<String> labels() { return List.of(labels); }
    public boolean hasLabel(String label) { return Arrays.binarySearch(labels, label) >= 0; }
    public String comments() { return comments; }
    public String error() { return error; }
    public Source source() { return source; }
    public int motifMask() { return motifMask; }
    public int pawnMask() { return pawnMask; }
    public long[] materials() { return materials.clone(); }

    /** Whether the mainline reaches this material signature (or its color mirror). */
    public boolean reachedMaterial(long signature, boolean eitherColor) {
        long mirrored = com.oriondb.analysis.Material.mirror(signature);
        for (long m : materials) if (m == signature || (eitherColor && m == mirrored)) return true;
        return false;
    }

    /** Fewest pieces (kings included) on the board at any point of the mainline. */
    public int minPieces() {
        int min = 32;
        for (long m : materials) min = Math.min(min, com.oriondb.analysis.Material.pieceCount(m));
        return min;
    }

    public String tag(String name) {
        for (int i = 0; i < tags.length; i += 2) if (tags[i].equals(name)) return tags[i + 1];
        return null;
    }

    /** All user tags in file order. */
    public Map<String, String> tags() {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < tags.length; i += 2) m.put(tags[i], tags[i + 1]);
        return m;
    }

    public String resultText() {
        return switch (result) {
            case WHITE_WINS -> "1-0";
            case BLACK_WINS -> "0-1";
            case DRAW -> "1/2-1/2";
            default -> "*";
        };
    }

    /** Date in PGN form, e.g. "2024.03.??". */
    public String dateText() {
        String t = tag("Date");
        return t == null ? "????.??.??" : t;
    }

    public Position startPosition() {
        return startFen == null ? Position.start() : Position.fromFen(startFen);
    }

    /** Mainline move at index {@code i} (0-based half-move). */
    public int move(int i) {
        return Move.unpack(moves[i]);
    }

    public int[] mainline() {
        int[] out = new int[moves.length];
        for (int i = 0; i < out.length; i++) out[i] = Move.unpack(moves[i]);
        return out;
    }

    /** Positions along the mainline: index 0 is the start, index i after i half-moves. */
    public Position[] positions() {
        Position[] out = new Position[moves.length + 1];
        out[0] = startPosition();
        for (int i = 0; i < moves.length; i++) out[i + 1] = out[i].play(Move.unpack(moves[i]));
        return out;
    }

    /** Motif hits as (ply, motif). */
    public List<int[]> motifHits() {
        return Arrays.stream(motifs).mapToObj(v -> new int[] {v >>> 8, v & 0xFF}).toList();
    }

    public boolean hasMotif(Motif m) {
        return (motifMask & m.bit()) != 0;
    }

    /** True if the mainline begins with exactly these packed moves. */
    public boolean startsWith(short[] prefix) {
        if (prefix.length > moves.length) return false;
        for (int i = 0; i < prefix.length; i++) if (moves[i] != prefix[i]) return false;
        return true;
    }

    /** 64-bit fingerprint of the start position and mainline (duplicate detection). */
    public long movesFingerprint() {
        long h = startFen == null ? 0xcbf29ce484222325L : startFen.hashCode() * 0x9E3779B97F4A7C15L;
        for (short m : moves) {
            h ^= m & 0xFFFF;
            h *= 0x100000001b3L;
        }
        return h ^ moves.length;
    }

    // ------------------------------------------------------------ copies

    GameRecord withId(int newId, Source newSource) {
        return new GameRecord(newId, tags, date, result, whiteElo, blackElo, eco, opening, bookEco, bookOpening,
            flags, startFen, moves, materials, motifMask, motifs, pawnMask, labels, comments, error, newSource);
    }

    GameRecord withSource(Source newSource) {
        return withId(id, newSource);
    }

    GameRecord withLabels(String[] newLabels, Source newSource) {
        return new GameRecord(id, tags, date, result, whiteElo, blackElo, eco, opening, bookEco, bookOpening,
            flags, startFen, moves, materials, motifMask, motifs, pawnMask, newLabels, comments, error, newSource);
    }

    /** Same game with different header tags (moves and analysis unchanged). */
    GameRecord withTags(String[] newTags, Source newSource) {
        return RecordBuilder.withHeaders(this, newTags, newSource);
    }

    @Override
    public String toString() {
        return "#" + id + " " + white + " - " + black + " " + resultText() + " (" + dateText() + ")";
    }
}
