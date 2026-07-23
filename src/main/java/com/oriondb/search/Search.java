package com.oriondb.search;

import com.oriondb.analysis.Material;
import com.oriondb.analysis.Motifs;
import com.oriondb.analysis.PawnFeatures;
import com.oriondb.chess.Board;
import com.oriondb.chess.Move;
import com.oriondb.db.Database;
import com.oriondb.db.GameData;
import com.oriondb.model.Game;
import com.oriondb.model.GameTags;
import com.oriondb.model.MoveNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fluent game search. All criteria combine with AND. Text criteria are
 * case-insensitive substring matches; position criteria are transposition
 * aware (Zobrist based).
 *
 * <pre>
 * db.search().player("Carlsen").ecoRange("B90", "B99").minElo(2700, EloMode.BOTH)
 *   .sortBy(Sort.DATE, true).limit(20).find();
 * </pre>
 */
public final class Search {

    public enum EloMode { EITHER, BOTH, AVERAGE }

    public enum Sort { ID, DATE, WHITE_ELO, BLACK_ELO, AVG_ELO, PLY_COUNT }

    private final Database db;

    private String white;
    private String black;
    private String player;
    private String event;
    private String site;
    private String ecoFrom;
    private String ecoTo;
    private String opening;
    private String result;          // canonical: 1-0, 0-1, 1/2-1/2, *, decisive
    private String dateFrom;        // zero-filled sortable
    private String dateTo;
    private Integer minElo;
    private Integer maxElo;
    private EloMode eloMode = EloMode.EITHER;
    private Integer minPly;
    private Integer maxPly;
    private Long positionHash;
    private Integer materialKey;
    private boolean materialEitherSide = true;
    private Integer maxPieces;
    private int motifMask;
    private int pawnMask;
    private String commentText;
    private Boolean annotated;
    private final Set<String> requiredTags = new LinkedHashSet<>();
    private final Set<String> anyTags = new LinkedHashSet<>();
    private final Set<String> excludedTags = new LinkedHashSet<>();
    private Sort sort = Sort.ID;
    private boolean descending;
    private int limit = Integer.MAX_VALUE;
    private int offset;

    public Search(Database db) {
        this.db = db;
    }

    // ------------------------------------------------------------------
    // Criteria
    // ------------------------------------------------------------------

    /** Substring match on the White player's name. */
    public Search white(String name) {
        this.white = lower(name);
        return this;
    }

    /** Substring match on the Black player's name. */
    public Search black(String name) {
        this.black = lower(name);
        return this;
    }

    /** Substring match on either player's name. */
    public Search player(String name) {
        this.player = lower(name);
        return this;
    }

    public Search event(String name) {
        this.event = lower(name);
        return this;
    }

    public Search site(String name) {
        this.site = lower(name);
        return this;
    }

    /**
     * ECO filter: "B90" (exact), "B9" or "B" (prefix), or "B90-B99" (range).
     * Uses the game's ECO tag, falling back to the book classification.
     */
    public Search eco(String eco) {
        if (eco == null || eco.isBlank()) return this;
        String s = eco.trim().toUpperCase(Locale.ROOT);
        int dash = s.indexOf('-');
        if (dash > 0) {
            return ecoRange(s.substring(0, dash), s.substring(dash + 1));
        }
        this.ecoFrom = s;
        this.ecoTo = s + "￿"; // prefix semantics
        return this;
    }

    /** Inclusive ECO range, e.g. ("B90", "B99"). */
    public Search ecoRange(String from, String to) {
        this.ecoFrom = from.trim().toUpperCase(Locale.ROOT);
        this.ecoTo = to.trim().toUpperCase(Locale.ROOT) + "￿";
        return this;
    }

    /** Substring match on the opening name (tag or book classification). */
    public Search opening(String name) {
        this.opening = lower(name);
        return this;
    }

    /**
     * Result filter. Accepts "1-0"/"white", "0-1"/"black", "1/2-1/2"/"draw",
     * "*"/"unfinished", or "decisive".
     */
    public Search result(String result) {
        if (result == null || result.isBlank()) return this;
        switch (result.trim().toLowerCase(Locale.ROOT)) {
            case "1-0": case "white": case "white-win": case "white_win":
                this.result = "1-0"; break;
            case "0-1": case "black": case "black-win": case "black_win":
                this.result = "0-1"; break;
            case "1/2-1/2": case "1/2": case "draw": case "drawn":
                this.result = "1/2-1/2"; break;
            case "*": case "unfinished": case "ongoing":
                this.result = "*"; break;
            case "decisive":
                this.result = "decisive"; break;
            default:
                throw new IllegalArgumentException("Unknown result filter: " + result);
        }
        return this;
    }

    /** Inclusive lower date bound: "1990", "1990.06", or "1990.06.15". */
    public Search dateFrom(String date) {
        this.dateFrom = normalizeDate(date, false);
        return this;
    }

    /** Inclusive upper date bound. */
    public Search dateTo(String date) {
        this.dateTo = normalizeDate(date, true);
        return this;
    }

    private static String normalizeDate(String date, boolean upper) {
        if (date == null || date.isBlank()) return null;
        String s = date.trim().replace('-', '.').replace('/', '.');
        String[] parts = s.split("\\.");
        String year = parts[0];
        String month = parts.length > 1 ? pad2(parts[1]) : (upper ? "99" : "00");
        String day = parts.length > 2 ? pad2(parts[2]) : (upper ? "99" : "00");
        return year + "." + month + "." + day;
    }

    private static String pad2(String s) {
        return s.length() == 1 ? "0" + s : s;
    }

    public Search minElo(int elo) {
        this.minElo = elo;
        return this;
    }

    public Search minElo(int elo, EloMode mode) {
        this.minElo = elo;
        this.eloMode = mode;
        return this;
    }

    public Search maxElo(int elo) {
        this.maxElo = elo;
        return this;
    }

    public Search eloMode(EloMode mode) {
        this.eloMode = mode;
        return this;
    }

    public Search minPly(int plies) {
        this.minPly = plies;
        return this;
    }

    public Search maxPly(int plies) {
        this.maxPly = plies;
        return this;
    }

    /** Games whose mainline reaches this FEN position (any move order). */
    public Search containsPosition(String fen) {
        this.positionHash = Board.fromFen(fen).zobrist();
        return this;
    }

    public Search containsPosition(Board board) {
        this.positionHash = board.zobrist();
        return this;
    }

    public Search containsHash(long zobrist) {
        this.positionHash = zobrist;
        return this;
    }

    /**
     * Games passing through the position after these moves from the start,
     * e.g. "e4 c5 Nf3" or "1. e4 c5 2. Nf3". Transpositions match too.
     */
    public Search openingLine(String sanSequence) {
        this.positionHash = positionAfter(sanSequence).zobrist();
        return this;
    }

    /** Play a SAN sequence from the start position ("1. e4 c5" or "e4 c5"). */
    public static Board positionAfter(String sanSequence) {
        Board board = Board.startingPosition();
        for (String raw : sanSequence.trim().split("\\s+")) {
            String token = raw;
            // Strip move numbers, including glued forms like "1.e4",
            // but leave castling written with zeros intact.
            if (!token.isEmpty() && Character.isDigit(token.charAt(0)) && !token.startsWith("0-0")) {
                token = token.replaceFirst("^\\d+\\.*", "");
            }
            if (token.isEmpty()) continue;
            int move = board.parseSan(token);
            if (move == Move.NONE) {
                throw new IllegalArgumentException("Illegal move in opening line: '" + raw + "'");
            }
            board = board.play(move);
        }
        return board;
    }

    /**
     * Games that reach this material balance at any point,
     * e.g. "KRP vs KR". By default either side may have the stronger side.
     */
    public Search material(String signature) {
        this.materialKey = Material.parse(signature);
        return this;
    }

    public Search material(String signature, boolean eitherSide) {
        this.materialKey = Material.parse(signature);
        this.materialEitherSide = eitherSide;
        return this;
    }

    /** Games reaching a position with at most this many pieces (kings included). */
    public Search maxPieces(int pieces) {
        this.maxPieces = pieces;
        return this;
    }

    /** Games featuring a tactical motif: fork, pin, skewer, discovered-check, ... */
    public Search motif(String name) {
        int bit = Motifs.fromName(name);
        if (bit == 0) {
            throw new IllegalArgumentException("Unknown motif: " + name);
        }
        this.motifMask |= bit;
        return this;
    }

    /** Games featuring a pawn structure feature: iqp, doubled, passed, ... */
    public Search pawnFeature(String name) {
        int bit = PawnFeatures.fromName(name);
        if (bit == 0) {
            throw new IllegalArgumentException("Unknown pawn feature: " + name);
        }
        this.pawnMask |= bit;
        return this;
    }

    /** Case-insensitive substring search over all comments in the game. */
    public Search commentContains(String text) {
        this.commentText = lower(text);
        return this;
    }

    /** Only games with (true) or without (false) any annotations. */
    public Search annotated(boolean annotated) {
        this.annotated = annotated;
        return this;
    }

    /** Require one categorical game tag. Repeated calls combine with AND. */
    public Search tag(String tag) {
        requiredTags.add(GameTags.normalize(tag));
        return this;
    }

    /** Require every categorical game tag. */
    public Search tags(String... tags) {
        return tags(Arrays.asList(tags));
    }

    /** Require every categorical game tag. */
    public Search tags(Collection<String> tags) {
        requiredTags.addAll(GameTags.normalizeAll(tags));
        return this;
    }

    /** Require at least one of the supplied categorical game tags. */
    public Search anyTag(String tag) {
        anyTags.add(GameTags.normalize(tag));
        return this;
    }

    /** Require at least one of the supplied categorical game tags. */
    public Search anyTags(String... tags) {
        return anyTags(Arrays.asList(tags));
    }

    /** Require at least one of the supplied categorical game tags. */
    public Search anyTags(Collection<String> tags) {
        anyTags.addAll(GameTags.normalizeAll(tags));
        return this;
    }

    /** Exclude games carrying this categorical tag. */
    public Search withoutTag(String tag) {
        excludedTags.add(GameTags.normalize(tag));
        return this;
    }

    /** Exclude games carrying any of the supplied categorical tags. */
    public Search withoutTags(String... tags) {
        return withoutTags(Arrays.asList(tags));
    }

    /** Exclude games carrying any of the supplied categorical tags. */
    public Search withoutTags(Collection<String> tags) {
        excludedTags.addAll(GameTags.normalizeAll(tags));
        return this;
    }

    public Search sortBy(Sort sort, boolean descending) {
        this.sort = sort;
        this.descending = descending;
        return this;
    }

    public Search limit(int limit) {
        this.limit = Math.max(0, limit);
        return this;
    }

    public Search offset(int offset) {
        this.offset = Math.max(0, offset);
        return this;
    }

    /**
     * Add and remove categorical tags on every game returned by this search.
     * Sorting, limit, and offset are honored.
     */
    public Database.TagUpdateReport updateGameTags(
            Collection<String> add, Collection<String> remove) {
        synchronized (db) {
            List<Integer> ids = find().stream().map(Game::id).toList();
            return db.updateGameTags(ids, add, remove);
        }
    }

    /** Add categorical tags to every game returned by this search. */
    public Database.TagUpdateReport addGameTags(String... tags) {
        return updateGameTags(Arrays.asList(tags), List.of());
    }

    /** Remove categorical tags from every game returned by this search. */
    public Database.TagUpdateReport removeGameTags(String... tags) {
        return updateGameTags(List.of(), Arrays.asList(tags));
    }

    /** Replace categorical tags on every game returned by this search. */
    public Database.TagUpdateReport setGameTags(Collection<String> tags) {
        synchronized (db) {
            List<Integer> ids = find().stream().map(Game::id).toList();
            return db.setGameTags(ids, tags);
        }
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------

    /** Matching games, sorted and paginated. */
    public List<Game> find() {
        synchronized (db) {
            List<Game> matches = collect();
            matches.sort(comparator());
            int from = Math.min(offset, matches.size());
            int to = (int) Math.min((long) from + limit, matches.size());
            return new ArrayList<>(matches.subList(from, to));
        }
    }

    /** Total match count, ignoring limit/offset. */
    public int count() {
        synchronized (db) {
            return collect().size();
        }
    }

    private List<Game> collect() {
        List<Game> matches = new ArrayList<>();
        if (positionHash != null) {
            // Fast path: candidates from the position index.
            for (long entry : db.positionEntries(positionHash)) {
                Game game = db.game(Database.entryGameId(entry));
                if (game != null && matches(game)) {
                    matches.add(game);
                }
            }
        } else if (!requiredTags.isEmpty() || !anyTags.isEmpty()) {
            // Fast path: candidates from the categorical-tag inverted index.
            for (int gameId : db.gameIdsMatchingTags(requiredTags, anyTags)) {
                Game game = db.game(gameId);
                if (game != null && matches(game)) {
                    matches.add(game);
                }
            }
        } else {
            for (Game game : db.games()) {
                if (matches(game)) {
                    matches.add(game);
                }
            }
        }
        return matches;
    }

    private boolean matches(Game game) {
        GameData data = db.data(game.id());

        if (white != null && !contains(game.white(), white)) return false;
        if (black != null && !contains(game.black(), black)) return false;
        if (player != null && !contains(game.white(), player) && !contains(game.black(), player)) {
            return false;
        }
        if (event != null && !contains(game.event(), event)) return false;
        if (site != null && !contains(game.site(), site)) return false;

        if (ecoFrom != null) {
            String eco = effectiveEco(game, data);
            if (eco == null || eco.compareTo(ecoFrom) < 0 || eco.compareTo(ecoTo) > 0) {
                return false;
            }
        }
        if (opening != null) {
            String name = game.tag("Opening");
            if (name == null && data != null) name = data.openingName();
            if (name == null || !name.toLowerCase(Locale.ROOT).contains(opening)) {
                return false;
            }
        }

        if (result != null) {
            String r = game.result();
            if (result.equals("decisive")) {
                if (!r.equals("1-0") && !r.equals("0-1")) return false;
            } else if (!r.equals(result)) {
                return false;
            }
        }

        if (dateFrom != null || dateTo != null) {
            String date = game.sortableDate();
            if (date == null) return false;
            if (dateFrom != null && date.compareTo(dateFrom) < 0) return false;
            if (dateTo != null && date.compareTo(dateTo) > 0) return false;
        }

        if (minElo != null || maxElo != null) {
            if (!eloMatches(game)) return false;
        }

        int plyCount = game.plyCount();
        if (minPly != null && plyCount < minPly) return false;
        if (maxPly != null && plyCount > maxPly) return false;

        if (data != null) {
            if (motifMask != 0 && (data.motifMask() & motifMask) != motifMask) return false;
            if (pawnMask != 0 && (data.pawnMask() & pawnMask) != pawnMask) return false;
            if (materialKey != null && !reachesMaterial(data)) return false;
            if (maxPieces != null && !reachesPieceCount(data)) return false;
        } else if (motifMask != 0 || pawnMask != 0 || materialKey != null || maxPieces != null) {
            return false;
        }

        if (annotated != null && game.hasAnnotations() != annotated) return false;

        if (commentText != null && !commentsContain(game, commentText)) return false;

        if (!requiredTags.isEmpty() || !anyTags.isEmpty() || !excludedTags.isEmpty()) {
            Set<String> gameTags = game.gameTags();
            if (!gameTags.containsAll(requiredTags)) return false;
            if (!anyTags.isEmpty() && Collections.disjoint(gameTags, anyTags)) return false;
            if (!excludedTags.isEmpty() && !Collections.disjoint(gameTags, excludedTags)) {
                return false;
            }
        }

        return true;
    }

    private String effectiveEco(Game game, GameData data) {
        String eco = game.tag("ECO");
        if (eco == null && data != null) eco = data.eco();
        return eco == null ? null : eco.toUpperCase(Locale.ROOT);
    }

    private boolean eloMatches(Game game) {
        Integer w = game.whiteElo();
        Integer b = game.blackElo();
        int lo = minElo != null ? minElo : 0;
        int hi = maxElo != null ? maxElo : Integer.MAX_VALUE;
        switch (eloMode) {
            case BOTH:
                return w != null && b != null
                    && w >= lo && w <= hi && b >= lo && b <= hi;
            case AVERAGE: {
                Integer avg = game.averageElo();
                return avg != null && avg >= lo && avg <= hi;
            }
            case EITHER:
            default:
                return (w != null && w >= lo && w <= hi)
                    || (b != null && b >= lo && b <= hi);
        }
    }

    private boolean reachesMaterial(GameData data) {
        int target = materialKey;
        int flipped = Material.flip(target);
        for (int key : data.materialKeys()) {
            if (key == target || (materialEitherSide && key == flipped)) return true;
        }
        return false;
    }

    private boolean reachesPieceCount(GameData data) {
        int[] keys = data.materialKeys();
        // Piece count never increases, so the final position has the minimum.
        return Material.pieceCount(keys[keys.length - 1]) <= maxPieces;
    }

    private static boolean contains(String haystack, String needleLower) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    private static boolean commentsContain(Game game, String needleLower) {
        if (game.gameComment() != null
                && game.gameComment().toLowerCase(Locale.ROOT).contains(needleLower)) {
            return true;
        }
        return commentsContain(game.root(), needleLower);
    }

    private static boolean commentsContain(MoveNode node, String needleLower) {
        for (; node != null; node = node.next()) {
            if (node.comment() != null
                    && node.comment().toLowerCase(Locale.ROOT).contains(needleLower)) {
                return true;
            }
            if (node.commentBefore() != null
                    && node.commentBefore().toLowerCase(Locale.ROOT).contains(needleLower)) {
                return true;
            }
            for (MoveNode variation : node.variations()) {
                if (commentsContain(variation, needleLower)) return true;
            }
        }
        return false;
    }

    private Comparator<Game> comparator() {
        Comparator<Game> cmp;
        switch (sort) {
            case DATE:
                cmp = Comparator.comparing(Game::sortableDate,
                    Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            case WHITE_ELO:
                cmp = Comparator.comparing(Game::whiteElo,
                    Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            case BLACK_ELO:
                cmp = Comparator.comparing(Game::blackElo,
                    Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            case AVG_ELO:
                cmp = Comparator.comparing(Game::averageElo,
                    Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            case PLY_COUNT:
                cmp = Comparator.comparingInt(Game::plyCount);
                break;
            case ID:
            default:
                cmp = Comparator.comparingInt(Game::id);
                break;
        }
        if (descending) {
            cmp = cmp.reversed();
        }
        return cmp.thenComparingInt(Game::id);
    }
}
