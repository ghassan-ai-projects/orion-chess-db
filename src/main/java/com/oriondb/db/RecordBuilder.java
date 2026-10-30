package com.oriondb.db;

import com.oriondb.analysis.Material;
import com.oriondb.analysis.MotifDetector;
import com.oriondb.analysis.PawnFeature;
import com.oriondb.chess.Move;
import com.oriondb.chess.Position;
import com.oriondb.eco.EcoBook;
import com.oriondb.game.Game;
import com.oriondb.game.GameNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Builds {@link GameRecord}s from parsed games: header fields plus mainline analysis. */
final class RecordBuilder {
    /** Tool-owned PGN tags: stable id and labels. */
    static final String ID_TAG = "OrionId", LABELS_TAG = "OrionLabels";
    /** OrionDB v2 spellings, read for migration and rewritten on save. */
    static final String LEGACY_ID_TAG = "OrionDBId", LEGACY_LABELS_TAG = "OrionTags";

    private static final Pattern ECO = Pattern.compile("[A-E]\\d\\d");
    private static final String[] NO_STRINGS = {};

    private RecordBuilder() {}

    static boolean isToolTag(String name) {
        return name.equals(ID_TAG) || name.equals(LABELS_TAG) || name.equals(LEGACY_ID_TAG) || name.equals(LEGACY_LABELS_TAG);
    }

    /** Full record for a parsed game. */
    static GameRecord build(int id, Game game, Source source) {
        Map<String, String> tagMap = game.tags().asMap();
        String[] tags = userTags(tagMap);
        String[] labels = labelsFrom(tagMap);

        List<GameNode> line = game.mainline();
        Position[] positions = new Position[line.size() + 1];
        int[] moves = new int[line.size()];
        positions[0] = game.startPosition();
        short[] packed = new short[line.size()];
        for (int i = 0; i < moves.length; i++) {
            GameNode n = line.get(i);
            moves[i] = n.move();
            packed[i] = Move.pack(n.move());
            positions[i + 1] = n.position();
        }

        TreeSet<Long> mats = new TreeSet<>();
        for (Position p : positions) mats.add(Material.signature(p));
        long[] materials = mats.stream().mapToLong(Long::longValue).toArray();

        List<MotifDetector.Hit> hits = MotifDetector.detect(positions, moves);
        int[] motifs = new int[hits.size()];
        int motifMask = 0;
        for (int i = 0; i < motifs.length; i++) {
            motifs[i] = (hits.get(i).ply() << 8) | hits.get(i).motif().ordinal();
            motifMask |= hits.get(i).motif().bit();
        }
        int pawnMask = PawnFeature.gameMask(positions);

        boolean standardStart = positions[0].equals(Position.start());
        String bookEco = null, bookOpening = null;
        if (standardStart) {
            EcoBook.Opening o = EcoBook.get().classify(positions);
            if (o != null) {
                bookEco = o.eco();
                bookOpening = o.name();
            }
        }

        int flags = 0;
        StringBuilder comments = new StringBuilder();
        for (GameNode n : game.allNodes()) {
            if (n.plainComment() != null) {
                flags |= GameRecord.HAS_COMMENTS;
                comments.append(n.plainComment()).append(' ');
            }
            if (n.startingComment() != null) {
                flags |= GameRecord.HAS_COMMENTS;
                comments.append(n.startingComment()).append(' ');
            }
            if (n.children().size() > 1) flags |= GameRecord.HAS_VARIATIONS;
            if (n.nags().length > 0) flags |= GameRecord.HAS_NAGS;
            if (n.comment() != null) {
                Map<String, String> cmds = n.commands();
                if (cmds.containsKey("eval")) flags |= GameRecord.HAS_EVALS;
                if (cmds.containsKey("clk")) flags |= GameRecord.HAS_CLOCKS;
            }
        }
        if (game.isChess960()) flags |= GameRecord.CHESS960;
        if (!standardStart) flags |= GameRecord.SETUP;
        String commentText = comments.isEmpty() ? null : comments.toString().strip().toLowerCase(Locale.ROOT);

        return assemble(id, tags, bookEco, bookOpening, flags, standardStart ? null : positions[0].fen(),
            packed, materials, motifMask, motifs, pawnMask, labels, commentText, null, source);
    }

    /** Record for a game that failed to parse: kept verbatim, excluded from search. */
    static GameRecord invalid(int id, Map<String, String> tagMap, String error, Source source) {
        return assemble(id, userTags(tagMap), null, null, GameRecord.INVALID, null, new short[0], new long[0],
            0, new int[0], 0, labelsFrom(tagMap), null, error, source);
    }

    static GameRecord withHeaders(GameRecord r, String[] tags, Source source) {
        return assemble(r.id, tags, r.bookEco, r.bookOpening, r.flags, r.startFen, r.moves, r.materials,
            r.motifMask, r.motifs, r.pawnMask, r.labels, r.comments, r.error, source);
    }

    /** Rebuilds a record from cached fields (header-derived fields are recomputed). */
    static GameRecord assemble(int id, String[] tags, String bookEco, String bookOpening, int flags,
                                       String startFen, short[] moves, long[] materials, int motifMask, int[] motifs,
                                       int pawnMask, String[] labels, String comments, String error, Source source) {
        String ecoTag = tagIn(tags, "ECO");
        String eco = ecoTag != null && ECO.matcher(ecoTag.strip()).matches() ? ecoTag.strip() : bookEco;
        String openingTag = tagIn(tags, "Opening");
        String variation = tagIn(tags, "Variation");
        String opening = openingTag != null && !openingTag.isBlank()
            ? (variation != null && !variation.isBlank() ? openingTag + ": " + variation : openingTag)
            : bookOpening;
        return new GameRecord(id, tags, parseDate(tagIn(tags, "Date")), parseResult(tagIn(tags, "Result")),
            parseElo(tagIn(tags, "WhiteElo")), parseElo(tagIn(tags, "BlackElo")),
            intern(eco), intern(opening), intern(bookEco), intern(bookOpening), flags, startFen, moves, materials,
            motifMask, motifs, pawnMask, labels, comments, error, source);
    }

    // ------------------------------------------------------------ tags

    static String[] userTags(Map<String, String> tagMap) {
        List<String> out = new ArrayList<>(tagMap.size() * 2);
        for (Map.Entry<String, String> e : tagMap.entrySet()) {
            if (isToolTag(e.getKey())) continue;
            out.add(intern(e.getKey()));
            out.add(intern(e.getValue()));
        }
        return out.toArray(NO_STRINGS);
    }

    /** Id from the tool tag (or its legacy spelling), or -1. */
    static int idFrom(Map<String, String> tagMap) {
        String v = tagMap.getOrDefault(ID_TAG, tagMap.get(LEGACY_ID_TAG));
        if (v == null) return -1;
        try {
            int id = Integer.parseInt(v.strip());
            return id > 0 ? id : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String[] labelsFrom(Map<String, String> tagMap) {
        String v = tagMap.getOrDefault(LABELS_TAG, tagMap.get(LEGACY_LABELS_TAG));
        return v == null ? NO_STRINGS : normalizeLabels(Arrays.asList(v.split(",")));
    }

    /** Lowercased, trimmed, de-duplicated, sorted; commas are not allowed inside a label. */
    static String[] normalizeLabels(Iterable<String> labels) {
        TreeSet<String> set = new TreeSet<>();
        for (String l : labels) {
            String n = normalizeLabel(l);
            if (!n.isEmpty()) set.add(n);
        }
        return set.toArray(NO_STRINGS);
    }

    static String normalizeLabel(String label) {
        return label == null ? "" : label.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT).replace(",", "");
    }

    /** True if the text's tool tags already say exactly this id and these labels (and no legacy spellings). */
    static boolean toolTagsCurrent(Map<String, String> tagMap, int id, String[] labels) {
        if (tagMap.containsKey(LEGACY_ID_TAG) || tagMap.containsKey(LEGACY_LABELS_TAG)) return false;
        if (!String.valueOf(id).equals(tagMap.get(ID_TAG))) return false;
        String expected = labels.length == 0 ? null : String.join(",", labels);
        return java.util.Objects.equals(expected, tagMap.get(LABELS_TAG));
    }

    private static String tagIn(String[] tags, String name) {
        for (int i = 0; i < tags.length; i += 2) if (tags[i].equals(name)) return tags[i + 1];
        return null;
    }

    static int parseDate(String s) {
        if (s == null) return 0;
        String[] p = s.strip().split("[.\\-/]");
        int y = num(p, 0, 1000, 2999), m = num(p, 1, 1, 12), d = num(p, 2, 1, 31);
        return y == 0 ? 0 : y * 10000 + m * 100 + d;
    }

    private static int num(String[] p, int i, int lo, int hi) {
        if (i >= p.length) return 0;
        try {
            int v = Integer.parseInt(p[i]);
            return v >= lo && v <= hi ? v : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static byte parseResult(String s) {
        if (s == null) return GameRecord.UNKNOWN;
        return switch (s.strip()) {
            case "1-0" -> GameRecord.WHITE_WINS;
            case "0-1" -> GameRecord.BLACK_WINS;
            case "1/2-1/2", "½-½" -> GameRecord.DRAW;
            default -> GameRecord.UNKNOWN;
        };
    }

    static short parseElo(String s) {
        if (s == null) return 0;
        try {
            int v = Integer.parseInt(s.strip());
            return (short) (v > 0 && v < 4000 ? v : 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String intern(String s) {
        return s == null ? null : s.intern();
    }
}
