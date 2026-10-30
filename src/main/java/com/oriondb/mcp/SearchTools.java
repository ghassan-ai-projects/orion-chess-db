package com.oriondb.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oriondb.analysis.Material;
import com.oriondb.analysis.Motif;
import com.oriondb.analysis.PawnFeature;
import com.oriondb.db.GameRecord;
import com.oriondb.db.Snapshot;
import com.oriondb.game.Game;
import com.oriondb.pgn.PgnWriter;
import com.oriondb.query.PiecePattern;
import com.oriondb.query.Query;
import com.oriondb.query.QueryParser;
import com.oriondb.query.SearchResult;
import com.oriondb.query.Searcher;
import com.oriondb.stats.Duplicates;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** search_games, export_pgn, find_duplicates, and query building shared with other tools. */
final class SearchTools {
    static final int DEFAULT_LIMIT = 25, MAX_LIMIT = 500;
    static final int MAX_EXPORT_TEXT = 200_000;

    private SearchTools() {}

    static List<Tool> tools(Context ctx) {
        return List.of(
            Tool.read("search_games", "Search games",
                "Find games by any combination of header, rating, opening, move, position, piece-pattern, material, "
                    + "tactical-motif, pawn-structure, label and annotation filters (all AND-combined). Use the structured "
                    + "fields, a 'query' string in the text query language, or both (structured fields override the same "
                    + "criterion in 'query'). Returns the total count and one row per game (id, players, ratings, result, "
                    + "date, event, ECO/opening, plies, labels; match_ply/match_move when fen or pattern matched). "
                    + "Game ids are stable: pass them to get_game, edit_game, etc.",
                searchSchema(), a -> search(ctx, a)),
            Tool.read("export_pgn", "Export PGN",
                "Export games as PGN: selected by 'ids', by 'query' (text query language), or all games. Writes to 'path' "
                    + "(relative paths resolve next to the database file; never the database itself) or returns the text "
                    + "(capped at ~200 KB; use offset/path for more). Optionally strip comments/NAGs or variations.",
                Schema.object()
                    .ints("ids", "Game ids to export, in this order.")
                    .str("query", "Text query selecting the games (see search_games); ignored when ids are given.")
                    .str("path", "File to write. Omit to return the PGN text instead.")
                    .bool("overwrite", "Allow replacing an existing file at 'path' (default false).")
                    .bool("comments", "Include comments and NAGs (default true).")
                    .bool("variations", "Include variations (default true).")
                    .integer("offset", "Skip this many selected games (for paging returned text).", 0, Integer.MAX_VALUE),
                a -> export(ctx, a)),
            Tool.read("find_duplicates", "Find duplicate games",
                "List groups of games with identical moves (and by default the same players). Each group names the "
                    + "suggested copy to keep (the most annotated / complete one). Use remove_duplicates to delete the others.",
                duplicateSchema(), a -> duplicates(ctx, a)));
    }

    // ------------------------------------------------------------ search

    static Schema searchSchema() {
        return Schema.object()
            .str("query", "Text query, e.g. 'player:carlsen as:white eco:B90-B99 elo:2700 year:2015..2020 motif:fork sort:-date'. "
                + "Keys: " + QueryParser.HELP.replaceAll("\\s+", " ").strip())
            .str("white", "White player name (tokens in any order, case/accent-insensitive).")
            .str("black", "Black player name.")
            .str("player", "Player with either color (see player_color, outcome).")
            .choice("player_color", "Restrict 'player' to one color.", "white", "black", "any")
            .choice("outcome", "Result from 'player''s point of view.", "win", "draw", "loss")
            .str("event", "Event name substring.")
            .str("site", "Site substring.")
            .str("round", "Round, e.g. '6' (also matches '6.1').")
            .str("opening", "Opening name substring, e.g. 'Najdorf'.")
            .str("text", "Free text over players, event, site and opening.")
            .str("eco", "ECO code 'B90', prefix 'B9', or range 'B90-B99'.")
            .str("date_from", "Earliest date: YYYY, YYYY.MM or YYYY.MM.DD.")
            .str("date_to", "Latest date: YYYY, YYYY.MM or YYYY.MM.DD.")
            .strings("result", "Results: '1-0', '0-1', '1/2-1/2' (or 'draw'), 'decisive', '*' (unfinished).")
            .integer("min_elo", "Minimum rating (see elo_mode).", 0, 4000)
            .integer("max_elo", "Maximum rating (see elo_mode).", 0, 4000)
            .choice("elo_mode", "Which ratings must be in range: either player (default), both, or their average.", "either", "both", "average")
            .integer("min_moves", "Minimum game length in full moves.", 0, 10000)
            .integer("max_moves", "Maximum game length in full moves.", 0, 10000)
            .str("line", "Mainline starts with these SAN moves, e.g. '1. e4 c5 2. Nf3 d6'.")
            .str("fen", "Mainline reaches this position (any move order; FEN, move counters ignored).")
            .bool("fen_in_variations", "Also match games where the fen position occurs only inside a variation "
                + "(such rows report match_move 'in a variation').")
            .str("pattern", "Piece pattern reached on the mainline, e.g. 'Kg1 kg8 Pe5 -pd5 R*7 w': uppercase white, lowercase "
                + "black, * wildcard file/rank, '-' absent, '.f7' empty square, 'w'/'b' side to move.")
            .str("material", "Material signature reached, e.g. 'KRP vs KR' (colors either way unless material_exact_colors).")
            .bool("material_exact_colors", "Material must match with White having the first side.")
            .integer("max_pieces", "Reaches a position with at most this many pieces (kings included).", 2, 32)
            .strings("motifs", "Tactical motifs that must all occur: " + String.join(", ", Motif.keys()) + ".")
            .choice("motif_by", "Side that must play the motifs.", "white", "black", "any")
            .strings("structures", "Pawn structures that must all occur (persisting a few moves): iqp, " + PawnFeature.keysList() + ".")
            .choice("structure_by", "Side that must have the structures.", "white", "black", "any")
            .strings("labels", "Game must have all these labels.")
            .strings("any_labels", "Game must have at least one of these labels.")
            .strings("exclude_labels", "Game must have none of these labels.")
            .bool("annotated", "Has comments, variations or NAGs (true) / has none (false).")
            .bool("has_evals", "Has [%eval] engine evaluations (true) / has none (false).")
            .str("comment_contains", "Some comment contains this text (case-insensitive).")
            .stringMap("tag", "PGN tags that must equal these values (case-insensitive), e.g. {\"TimeControl\": \"180+2\"}; value '*' = tag present.", false)
            .ints("ids", "Restrict to these game ids.")
            .choice("sort", "Sort order (default id).", "id", "date", "white", "black", "event", "elo", "ply", "eco")
            .bool("descending", "Sort descending.")
            .integer("limit", "Rows to return (default 25, max 500).", 1, MAX_LIMIT)
            .integer("offset", "Rows to skip (paging).", 0, Integer.MAX_VALUE);
    }

    /** Parses the text query language with a helpful error. */
    static Query parseQuery(String text) {
        try {
            return QueryParser.parse(text == null ? "" : text);
        } catch (IllegalArgumentException e) {
            throw new ToolException("bad query '" + text + "': " + Tools.message(e) + "\n" + QueryParser.HELP);
        }
    }

    /** Builds a query from the structured search fields plus the optional text 'query'. */
    static Query buildQuery(Args a) {
        String text = a.str("query", "");
        Query q = parseQuery(text);
        try {
            applyStructured(q, a);
        } catch (ToolException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new ToolException(Tools.message(e));
        }
        int limit = a.has("limit") ? a.integer("limit", DEFAULT_LIMIT, 1, MAX_LIMIT)
            : text.contains("limit:") ? Math.min(q.limit(), MAX_LIMIT) : DEFAULT_LIMIT;
        int offset = a.has("offset") ? a.integer("offset", 0, 0, Integer.MAX_VALUE) : q.offset();
        return q.page(offset, Math.max(1, limit));
    }

    private static void applyStructured(Query q, Args a) {
        if (a.has("white")) q.white(a.str("white"));
        if (a.has("black")) q.black(a.str("black"));
        if (a.has("player")) q.player(a.str("player"));
        if (a.has("player_color")) q.playerColor(color(a.str("player_color")));
        if (a.has("outcome")) q.outcome(Query.Outcome.valueOf(a.str("outcome").strip().toUpperCase(Locale.ROOT)));
        if (a.has("event")) q.event(a.str("event"));
        if (a.has("site")) q.site(a.str("site"));
        if (a.has("round")) q.round(a.str("round"));
        if (a.has("opening")) q.opening(a.str("opening"));
        if (a.has("text")) q.text(a.str("text"));
        if (a.has("eco")) q.eco(a.str("eco"));
        if (a.has("date_from") || a.has("date_to")) q.dateRange(a.str("date_from"), a.str("date_to"));
        if (a.has("result")) q.results(results(a.strings("result")));
        Query.EloMode mode = a.has("elo_mode") ? Query.EloMode.valueOf(a.str("elo_mode").strip().toUpperCase(Locale.ROOT)) : null;
        if (a.has("min_elo") || a.has("max_elo")) {
            q.elo(a.integer("min_elo", 0, 0, 4000), a.integer("max_elo", 0, 0, 4000), mode == null ? Query.EloMode.EITHER : mode);
        } else if (mode != null) {
            q.eloMode(mode);
        }
        if (a.has("min_moves") || a.has("max_moves")) {
            int min = a.integer("min_moves", 0, 0, 10000), max = a.integer("max_moves", 0, 0, 10000);
            q.plies(min > 0 ? min * 2 - 1 : 0, max * 2);
        }
        if (a.has("line")) q.line(a.str("line"));
        if (a.has("fen")) q.position(Inputs.fen(a.str("fen")));
        if (a.has("fen_in_variations")) q.positionInVariations(a.bool("fen_in_variations", false));
        if (a.has("pattern")) q.pattern(PiecePattern.parse(a.str("pattern")));
        if (a.has("material")) q.material(Material.parse(a.str("material")), a.bool("material_exact_colors", false));
        if (a.has("max_pieces")) q.maxPieces(a.integer("max_pieces", 32, 2, 32));
        for (String m : a.strings("motifs")) q.motif(Motif.parse(m));
        if (a.has("motif_by")) q.motifBy(color(a.str("motif_by")));
        for (String s : a.strings("structures")) q.feature(PawnFeature.parse(s));
        if (a.has("structure_by")) q.featureColor(color(a.str("structure_by")));
        for (String l : a.strings("labels")) q.withLabel(l);
        for (String l : a.strings("any_labels")) q.withAnyLabel(l);
        for (String l : a.strings("exclude_labels")) q.withoutLabel(l);
        if (a.has("annotated")) q.annotated(a.bool("annotated", true));
        if (a.has("has_evals")) q.hasEvals(a.bool("has_evals", true));
        if (a.has("comment_contains")) q.comment(a.str("comment_contains"));
        for (Map.Entry<String, String> e : a.stringMap("tag").entrySet()) {
            if (e.getValue() == null) throw new ToolException("tag." + e.getKey() + ": value must be a string ('*' = present)");
            q.tag(e.getKey(), e.getValue());
        }
        if (a.has("ids")) q.ids(new HashSet<>(a.ints("ids")));
        if (a.has("sort") || a.has("descending")) {
            q.sort(Query.Sort.valueOf(a.str("sort", "id").strip().toUpperCase(Locale.ROOT)), a.bool("descending", false));
        }
    }

    static Query.Color color(String s) {
        return switch (s.strip().toLowerCase(Locale.ROOT)) {
            case "white", "w" -> Query.Color.WHITE;
            case "black", "b" -> Query.Color.BLACK;
            case "any", "either", "" -> Query.Color.ANY;
            default -> throw new ToolException("bad color '" + s + "' (white, black or any)");
        };
    }

    private static Set<Integer> results(List<String> values) {
        Set<Integer> out = new HashSet<>();
        for (String v : values) {
            switch (v.strip().toLowerCase(Locale.ROOT)) {
                case "1-0", "white" -> out.add(GameRecord.WHITE_WINS);
                case "0-1", "black" -> out.add(GameRecord.BLACK_WINS);
                case "1/2-1/2", "1/2", "draw", "=" -> out.add(GameRecord.DRAW);
                case "decisive" -> {
                    out.add(GameRecord.WHITE_WINS);
                    out.add(GameRecord.BLACK_WINS);
                }
                case "*", "unknown", "unfinished" -> out.add(GameRecord.UNKNOWN);
                default -> throw new ToolException("bad result '" + v + "' (use 1-0, 0-1, 1/2-1/2, draw, decisive or *)");
            }
        }
        return out;
    }

    private static ToolResult search(Context ctx, Args a) {
        Query q = buildQuery(a);
        SearchResult res = Searcher.search(ctx.snap(), q);
        JsonArray rows = new JsonArray();
        StringBuilder sb = new StringBuilder();
        int from = q.offset() + 1, to = q.offset() + res.matches().size();
        sb.append(res.total()).append(res.total() == 1 ? " game matches" : " games match");
        if (res.matches().isEmpty()) {
            sb.append(res.total() > 0 ? " (offset beyond the last match)." : ".");
        } else {
            sb.append(res.total() > res.matches().size() ? " (showing " + from + "-" + to + ")" : "").append(":\n");
        }
        for (SearchResult.Match m : res.matches()) {
            rows.add(Format.row(m.record(), m.ply()));
            sb.append(Format.rowText(m.record(), m.ply())).append('\n');
        }
        if (to < res.total()) sb.append("More results: offset=").append(to).append('\n');
        return ToolResult.of(sb.toString().strip(),
            Json.obj("total", res.total(), "offset", q.offset(), "count", res.matches().size(), "games", rows));
    }

    // ------------------------------------------------------------ export

    /** Ids selected by 'ids', 'query', or all games. */
    static List<Integer> select(Snapshot snap, Args a, boolean allowAll) {
        if (a.has("ids")) {
            List<Integer> ids = a.ints("ids");
            for (int id : ids) if (snap.find(id) == null) throw new ToolException("no game with id " + id);
            return ids;
        }
        if (a.has("query")) return Searcher.ids(snap, parseQuery(a.str("query")));
        if (!allowAll) throw new ToolException("give 'ids' or 'query' to select games");
        return snap.records().stream().map(GameRecord::id).toList();
    }

    private static ToolResult export(Context ctx, Args a) throws IOException {
        Snapshot snap = ctx.snap();
        List<Integer> ids = select(snap, a, true);
        int offset = a.integer("offset", 0, 0, Integer.MAX_VALUE);
        ids = ids.subList(Math.min(offset, ids.size()), ids.size());
        boolean comments = a.bool("comments", true), variations = a.bool("variations", true);
        PgnWriter.Options opts = new PgnWriter.Options(80, comments, variations, comments, false);

        if (a.has("path")) {
            Path target = target(ctx, a.str("path"));
            if (Files.exists(target) && !a.bool("overwrite", false)) {
                throw new ToolException("file " + target + " exists; pass overwrite: true to replace it");
            }
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            long bytes = 0;
            try (Writer w = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                for (int id : ids) {
                    String text = gameText(snap, id, comments && variations, opts) + "\n\n";
                    w.write(text);
                    bytes += text.length();
                }
            }
            return ToolResult.of("Wrote " + ids.size() + " games (" + Math.max(1, bytes / 1024) + " KB) to " + target,
                Json.obj("path", target.toString(), "games", ids.size()));
        }

        StringBuilder sb = new StringBuilder();
        int written = 0;
        for (int id : ids) {
            String text = gameText(snap, id, comments && variations, opts);
            if (written > 0 && sb.length() + text.length() > MAX_EXPORT_TEXT) break;
            sb.append(text).append("\n\n");
            written++;
        }
        String out = sb.toString().strip();
        if (written < ids.size()) {
            out += "\n\n; [truncated: returned " + written + " of " + ids.size() + " games; continue with offset="
                + (offset + written) + " or pass 'path' to write a file]";
        }
        if (ids.isEmpty()) out = "No games selected.";
        return ToolResult.of(out);
    }

    private static String gameText(Snapshot snap, int id, boolean verbatim, PgnWriter.Options opts) {
        GameRecord r = snap.get(id);
        if (verbatim || !r.isValid()) return snap.pgn(id).strip();
        Game g = snap.game(id);
        return PgnWriter.write(g, opts).strip();
    }

    /** Resolves an export path; refuses the database file and its sidecars. */
    static Path target(Context ctx, String path) throws IOException {
        Path db = ctx.db().path();
        Path p = Path.of(path.strip());
        if (!p.isAbsolute()) p = (db != null && db.toAbsolutePath().getParent() != null ? db.toAbsolutePath().getParent() : Path.of("")).resolve(p);
        p = p.toAbsolutePath().normalize();
        if (db != null) {
            Path dbAbs = db.toAbsolutePath().normalize();
            boolean same = p.equals(dbAbs) || (Files.exists(p) && Files.isSameFile(p, dbAbs));
            boolean sidecar = p.getParent() != null && p.getParent().equals(dbAbs.getParent())
                && p.getFileName().toString().startsWith(dbAbs.getFileName().toString() + ".");
            if (same || sidecar) throw new ToolException("refusing to write over the database file (or its sidecar files): " + p);
        }
        if (Files.isDirectory(p)) throw new ToolException(p + " is a directory; give a file name");
        return p;
    }

    // ------------------------------------------------------------ duplicates

    static Schema duplicateSchema() {
        return Schema.object()
            .bool("same_players", "Also require the same White and Black names (default true).")
            .integer("min_plies", "Ignore games shorter than this many plies (default 10; short draws repeat legitimately).", 0, 1000)
            .integer("limit", "Maximum groups to list (default 50).", 1, 1000);
    }

    static List<Duplicates.Group> findGroups(Snapshot snap, Args a) {
        return Duplicates.find(snap, a.bool("same_players", true), a.integer("min_plies", 10, 0, 1000));
    }

    private static ToolResult duplicates(Context ctx, Args a) {
        Snapshot snap = ctx.snap();
        List<Duplicates.Group> groups = findGroups(snap, a);
        int limit = a.integer("limit", 50, 1, 1000);
        int redundant = groups.stream().mapToInt(g -> g.redundant().size()).sum();
        StringBuilder sb = new StringBuilder();
        sb.append(groups.size()).append(" duplicate groups, ").append(redundant).append(" redundant games.");
        JsonArray out = new JsonArray();
        for (int i = 0; i < Math.min(limit, groups.size()); i++) {
            Duplicates.Group g = groups.get(i);
            sb.append("\nGroup ").append(i + 1).append(": keep #").append(g.keep()).append(", redundant ")
                .append(g.redundant().stream().map(x -> "#" + x).toList());
            JsonArray games = new JsonArray();
            for (int id : g.ids()) {
                GameRecord r = snap.get(id);
                games.add(Format.row(r, -1));
                sb.append("\n  ").append(Format.rowText(r, -1));
            }
            out.add(Json.obj("keep", g.keep(), "redundant", g.redundant(), "games", games));
        }
        if (groups.size() > limit) sb.append("\n(").append(groups.size() - limit).append(" more groups)");
        return ToolResult.of(sb.toString(), Json.obj("groups", groups.size(), "redundant", redundant, "list", out));
    }
}
