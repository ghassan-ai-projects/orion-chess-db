package com.oriondb.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.oriondb.analysis.Motifs;
import com.oriondb.chess.Board;
import com.oriondb.db.Database;
import com.oriondb.db.GameData;
import com.oriondb.model.Game;
import com.oriondb.pgn.PgnWriter;
import com.oriondb.search.Duplicates;
import com.oriondb.search.OpeningExplorer;
import com.oriondb.search.PlayerStats;
import com.oriondb.search.Search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * MCP tool definitions and handlers over a {@link Database}.
 * Mutating tools save the database to disk automatically.
 */
final class DbTools {

    private static final Set<String> SEARCH_FILTER_ARGUMENTS = Set.of(
        "white", "black", "player", "event", "site", "eco", "opening",
        "result", "date_from", "date_to", "min_elo", "max_elo", "min_ply",
        "max_ply", "opening_line", "contains_fen", "material", "max_pieces",
        "motif", "pawn_feature", "comment_contains", "tags", "exclude_tags",
        "annotated");

    /** Thrown for user-visible tool errors (bad arguments, missing games...). */
    private static final class ToolError extends RuntimeException {
        ToolError(String message) {
            super(message);
        }
    }

    private record Tool(String name, String description, JsonObject inputSchema,
                        Function<JsonObject, JsonObject> handler) {}

    private final Database db;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    DbTools(Database db) {
        this.db = db;
        register();
    }

    // ==================================================================
    // Protocol surface
    // ==================================================================

    JsonObject listTools() {
        JsonArray list = new JsonArray();
        for (Tool tool : tools.values()) {
            JsonObject t = new JsonObject();
            t.addProperty("name", tool.name());
            t.addProperty("description", tool.description());
            t.add("inputSchema", tool.inputSchema());
            list.add(t);
        }
        JsonObject result = new JsonObject();
        result.add("tools", list);
        return result;
    }

    JsonObject callTool(JsonObject params) {
        String name = params != null && params.has("name")
            ? params.get("name").getAsString() : null;
        Tool tool = name != null ? tools.get(name) : null;
        if (tool == null) {
            return errorResult("Unknown tool: " + name);
        }
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
            ? params.getAsJsonObject("arguments") : new JsonObject();
        try {
            synchronized (db) {
                return successResult(tool.handler().apply(args));
            }
        } catch (ToolError e) {
            return errorResult(e.getMessage());
        } catch (IllegalArgumentException e) {
            return errorResult("Invalid argument: " + e.getMessage());
        } catch (Exception e) {
            return errorResult("Tool failed: " + e);
        }
    }

    private static JsonObject successResult(JsonObject payload) {
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", payload.toString());
        JsonArray content = new JsonArray();
        content.add(text);
        JsonObject result = new JsonObject();
        result.add("content", content);
        result.add("structuredContent", payload);
        return result;
    }

    private static JsonObject errorResult(String message) {
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", message);
        JsonArray content = new JsonArray();
        content.add(text);
        JsonObject result = new JsonObject();
        result.add("content", content);
        result.addProperty("isError", true);
        return result;
    }

    // ==================================================================
    // Registration
    // ==================================================================

    private void register() {
        add("db_stats",
            "Database overview: game count, players, date range, most common openings, "
            + "and storage location.",
            schema(props()),
            this::dbStats);

        add("search_games",
            "Search games with any combination of filters (all AND-ed). Text filters are "
            + "case-insensitive substrings. Returns game summaries plus total_count; "
            + "fetch full games with get_game.",
            schema(props(
                str("white", "White player name contains this"),
                str("black", "Black player name contains this"),
                str("player", "Either player's name contains this"),
                str("event", "Event name contains this"),
                str("site", "Site contains this"),
                str("eco", "ECO code: exact 'B90', prefix 'B9' or 'B', or range 'B90-B99'"),
                str("opening", "Opening name contains this (e.g. 'Najdorf')"),
                str("result", "'1-0'/'white', '0-1'/'black', '1/2-1/2'/'draw', '*', or 'decisive'"),
                str("date_from", "Inclusive lower date bound: '1990', '1990.06', or '1990.06.15'"),
                str("date_to", "Inclusive upper date bound"),
                integer("min_elo", "Minimum Elo (see elo_mode)"),
                integer("max_elo", "Maximum Elo (see elo_mode)"),
                str("elo_mode", "How Elo bounds apply: 'either' (default), 'both', or 'average'"),
                integer("min_ply", "Minimum number of half-moves"),
                integer("max_ply", "Maximum number of half-moves"),
                str("opening_line", "Moves from the start position, e.g. '1. e4 c5 2. Nf3' "
                    + "or 'e4 c5 Nf3'; matches transpositions too"),
                str("contains_fen", "Games whose mainline reaches this FEN position "
                    + "(any move order)"),
                str("material", "Material balance reached at any point, e.g. 'KRP vs KR' "
                    + "or 'KQ-KR' (either side may be the strong one)"),
                integer("max_pieces", "Games reaching a position with at most this many "
                    + "pieces, kings included (endgame filter)"),
                str("motif", "Tactical motif featured in the game: fork, pin, skewer, "
                    + "discovered-check, double-check, promotion, underpromotion, "
                    + "en-passant, checkmate, castling"),
                str("pawn_feature", "Pawn structure feature: iqp, doubled, isolated, passed, "
                    + "advanced-passer (prefix with 'white-'/'black-' for one side)"),
                str("comment_contains", "Game commentary contains this text"),
                stringArray("tags", "Categorical game tags to match"),
                str("tag_mode", "How tags match: 'all' (default) or 'any'"),
                stringArray("exclude_tags", "Exclude games carrying any of these tags"),
                bool("annotated", "true: only games with comments/variations/NAGs; "
                    + "false: only bare games"),
                str("sort_by", "'date', 'white_elo', 'black_elo', 'avg_elo', 'ply_count', "
                    + "or 'id' (default)"),
                bool("descending", "Sort descending (default false)"),
                integer("limit", "Max games to return (default 25, max 500)"),
                integer("offset", "Skip this many matches (pagination)"))),
            this::searchGames);

        add("get_game",
            "Fetch one game in full: tags, mainline moves, PGN (with variations and "
            + "comments), final position FEN, opening classification, and its tactical "
            + "moments (ply + motifs).",
            schema(props(
                integer("game_id", "The game id from search results")),
                "game_id"),
            this::getGame);

        add("opening_explorer",
            "ChessBase-style opening tree for any position: every continuation played in "
            + "the database with game counts, W/D/L, score% for the side to move, average "
            + "Elo of the movers, last-played date, and sample games. Give either 'moves' "
            + "(from the start position) or 'fen'. Omit both for the start position.",
            schema(props(
                str("moves", "Moves from the start, e.g. '1. e4 c5 2. Nf3 d6' or 'e4 c5'"),
                str("fen", "Explore this FEN position instead"))),
            this::openingExplorer);

        add("position_search",
            "Find every game whose mainline reaches a position (transposition aware). "
            + "Returns the game and the half-move number where it occurs.",
            schema(props(
                str("fen", "The position as FEN"),
                integer("limit", "Max games to return (default 50)")),
                "fen"),
            this::positionSearch);

        add("player_report",
            "Player dossier: W/D/L as White and Black, score percentages, Elo performance "
            + "rating, highest rating, active period, favorite openings with scores, and "
            + "best wins. Name matching is a case-insensitive substring.",
            schema(props(
                str("player", "Player name or part of it, e.g. 'Carlsen'")),
                "player"),
            this::playerReport);

        add("head_to_head",
            "Lifetime score between two players, overall and split by color, with the "
            + "list of their game ids.",
            schema(props(
                str("player_a", "First player (substring match)"),
                str("player_b", "Second player (substring match)")),
                "player_a", "player_b"),
            this::headToHead);

        add("import_pgn",
            "Import games from PGN into the database. Every move is validated; duplicates "
            + "of existing games are skipped; missing ECO/Opening tags are filled from the "
            + "opening book. Provide pgn_text or file_path. Saves automatically.",
            schema(props(
                str("pgn_text", "PGN text with one or more games"),
                str("file_path", "Path to a .pgn file to import"))),
            this::importPgn);

        add("add_game",
            "Add a single game from PGN text and return its id. Fails if the text does "
            + "not contain exactly one valid game. Saves automatically.",
            schema(props(
                str("pgn_text", "PGN of exactly one game")),
                "pgn_text"),
            this::addGame);

        add("delete_game",
            "Delete a game by id. Saves automatically.",
            schema(props(
                integer("game_id", "The game to delete")),
                "game_id"),
            this::deleteGame);

        add("set_tag",
            "Set or remove a PGN tag on a game (e.g. fix a player name, add an ECO code). "
            + "Omit 'value' to remove the tag. Saves automatically.",
            schema(props(
                integer("game_id", "The game to edit"),
                str("key", "Tag name, e.g. 'White', 'ECO', 'Annotator'"),
                str("value", "New value; omit to remove the tag")),
                "game_id", "key"),
            this::setTag);

        add("update_game_tags",
            "Add, remove, or replace categorical tags on one or more games. Tags are "
            + "case-insensitive, normalized, and stored in the portable OrionTags PGN "
            + "header. Use set to replace all tags, or add/remove for incremental updates.",
            schema(props(
                intArray("game_ids", "One or more game ids to update"),
                stringArray("add", "Tags to add"),
                stringArray("remove", "Tags to remove"),
                stringArray("set", "Exact replacement tags; an empty array clears all")),
                "game_ids"),
            this::updateGameTags);

        add("tag_games_by_search",
            "Add, remove, or replace categorical tags on every game matching search "
            + "filters. At least one filter is required unless match_all is true.",
            schema(props(
                str("white", "White player name contains this"),
                str("black", "Black player name contains this"),
                str("player", "Either player's name contains this"),
                str("event", "Event name contains this"),
                str("site", "Site contains this"),
                str("eco", "ECO exact value, prefix, or range"),
                str("opening", "Opening name contains this"),
                str("result", "Game result"),
                str("date_from", "Inclusive lower date bound"),
                str("date_to", "Inclusive upper date bound"),
                integer("min_elo", "Minimum Elo"),
                integer("max_elo", "Maximum Elo"),
                str("elo_mode", "either, both, or average"),
                integer("min_ply", "Minimum half-moves"),
                integer("max_ply", "Maximum half-moves"),
                str("opening_line", "Position reached after these moves"),
                str("contains_fen", "Position reached as FEN"),
                str("material", "Material balance reached"),
                integer("max_pieces", "Maximum pieces reached"),
                str("motif", "Tactical motif"),
                str("pawn_feature", "Pawn feature"),
                str("comment_contains", "Comment text"),
                stringArray("tags", "Required categorical tags"),
                str("tag_mode", "all or any"),
                stringArray("exclude_tags", "Excluded categorical tags"),
                bool("annotated", "Whether games have annotations"),
                bool("match_all", "Allow an empty filter to select the whole database"),
                stringArray("add", "Tags to add"),
                stringArray("remove", "Tags to remove"),
                stringArray("set", "Exact replacement tags"))),
            this::tagGamesBySearch);

        add("manage_game_tag",
            "Rename, merge, or delete a categorical tag across the database. Rename "
            + "rejects an existing destination; merge combines into it.",
            schema(props(
                str("action", "rename, merge, or delete"),
                str("source", "Tag to rename, merge, or delete"),
                str("destination", "Required for rename and merge")),
                "action", "source"),
            this::manageGameTag);

        add("batch_mutate",
            "Apply multiple mutation operations as one transaction. Any invalid "
            + "operation rolls back the entire batch; a successful batch saves once.",
            schema(props(batchOperations()), "operations"),
            this::batchMutate);

        add("export_pgn",
            "Export games as PGN text, or write them to a file when file_path is given. "
            + "Omit game_ids to export the whole database.",
            schema(props(
                intArray("game_ids", "Ids of the games to export (default: all)"),
                str("file_path", "Write the PGN here instead of returning it")),
                new String[0]),
            this::exportPgn);

        add("find_duplicates",
            "Find groups of games with identical moves. Groups where the player names "
            + "also match are true duplicates; others are the same moves under different "
            + "names.",
            schema(props()),
            this::findDuplicates);
    }

    private void add(String name, String description, JsonObject schema,
                     Function<JsonObject, JsonObject> handler) {
        tools.put(name, new Tool(name, description, schema, handler));
    }

    // ==================================================================
    // Schema helpers
    // ==================================================================

    private static JsonObject schema(JsonObject properties, String... required) {
        JsonObject s = new JsonObject();
        s.addProperty("type", "object");
        s.add("properties", properties);
        if (required.length > 0) {
            JsonArray req = new JsonArray();
            for (String r : required) req.add(r);
            s.add("required", req);
        }
        return s;
    }

    private static JsonObject props(JsonObject... properties) {
        JsonObject all = new JsonObject();
        for (JsonObject p : properties) {
            all.add(p.get("__name").getAsString(), strip(p));
        }
        return all;
    }

    private static JsonObject strip(JsonObject p) {
        JsonObject clean = p.deepCopy();
        clean.remove("__name");
        return clean;
    }

    private static JsonObject typed(String name, String type, String description) {
        JsonObject p = new JsonObject();
        p.addProperty("__name", name);
        p.addProperty("type", type);
        p.addProperty("description", description);
        return p;
    }

    private static JsonObject str(String name, String description) {
        return typed(name, "string", description);
    }

    private static JsonObject integer(String name, String description) {
        return typed(name, "integer", description);
    }

    private static JsonObject bool(String name, String description) {
        return typed(name, "boolean", description);
    }

    private static JsonObject intArray(String name, String description) {
        JsonObject p = typed(name, "array", description);
        JsonObject items = new JsonObject();
        items.addProperty("type", "integer");
        p.add("items", items);
        return p;
    }

    private static JsonObject stringArray(String name, String description) {
        JsonObject p = typed(name, "array", description);
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        p.add("items", items);
        return p;
    }

    private static JsonObject batchOperations() {
        JsonObject p = typed("operations", "array",
            "Ordered mutation objects. Each needs an action plus that action's normal "
            + "arguments.");
        JsonObject items = new JsonObject();
        items.addProperty("type", "object");
        items.add("properties", props(
            str("action", "add_game, import_pgn, delete_game, set_tag, "
                + "or update_game_tags"),
            str("pgn_text", "PGN text for add_game or import_pgn"),
            str("file_path", "PGN file for import_pgn"),
            integer("game_id", "Game id for delete_game or set_tag"),
            str("key", "PGN tag key for set_tag"),
            str("value", "PGN tag value for set_tag; omit to remove"),
            intArray("game_ids", "Game ids for update_game_tags"),
            stringArray("add", "Categorical tags to add"),
            stringArray("remove", "Categorical tags to remove"),
            stringArray("set", "Replacement categorical tags")));
        JsonArray required = new JsonArray();
        required.add("action");
        items.add("required", required);
        p.add("items", items);
        return p;
    }

    // ==================================================================
    // Argument helpers
    // ==================================================================

    private static String optString(JsonObject args, String key) {
        JsonElement e = args.get(key);
        if (e == null || e.isJsonNull()) return null;
        String s = e.getAsString();
        return s.isBlank() ? null : s;
    }

    private static Integer optInt(JsonObject args, String key) {
        JsonElement e = args.get(key);
        if (e == null || e.isJsonNull()) return null;
        return e.getAsInt();
    }

    private static Boolean optBool(JsonObject args, String key) {
        JsonElement e = args.get(key);
        if (e == null || e.isJsonNull()) return null;
        return e.getAsBoolean();
    }

    private static String reqString(JsonObject args, String key) {
        String s = optString(args, key);
        if (s == null) throw new ToolError("Missing required argument: " + key);
        return s;
    }

    private static int reqInt(JsonObject args, String key) {
        Integer i = optInt(args, key);
        if (i == null) throw new ToolError("Missing required argument: " + key);
        return i;
    }

    private static List<String> optStrings(JsonObject args, String key) {
        JsonElement element = args.get(key);
        if (element == null || element.isJsonNull()) return List.of();
        if (!element.isJsonArray()) {
            throw new ToolError(key + " must be an array");
        }
        List<String> values = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            values.add(item.getAsString());
        }
        return values;
    }

    private static List<Integer> reqInts(JsonObject args, String key) {
        JsonElement element = args.get(key);
        if (element == null || !element.isJsonArray()) {
            throw new ToolError("Missing required array: " + key);
        }
        List<Integer> values = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            values.add(item.getAsInt());
        }
        if (values.isEmpty()) {
            throw new ToolError(key + " must contain at least one id");
        }
        return values;
    }

    // ==================================================================
    // Handlers
    // ==================================================================

    private JsonObject dbStats(JsonObject args) {
        Set<String> players = new HashSet<>();
        Set<String> events = new HashSet<>();
        Map<String, Integer> ecoCounts = new HashMap<>();
        Map<String, Integer> openingCounts = new HashMap<>();
        String firstDate = null;
        String lastDate = null;
        int annotated = 0;

        for (Game game : db.games()) {
            if (!game.white().equals("?")) players.add(game.white());
            if (!game.black().equals("?")) players.add(game.black());
            if (!game.event().equals("?")) events.add(game.event());
            GameData data = db.data(game.id());
            String eco = game.tag("ECO");
            if (eco == null && data != null) eco = data.eco();
            if (eco != null) ecoCounts.merge(eco, 1, Integer::sum);
            String opening = game.tag("Opening");
            if (opening == null && data != null) opening = data.openingName();
            if (opening != null) openingCounts.merge(opening, 1, Integer::sum);
            String date = game.sortableDate();
            if (date != null) {
                if (firstDate == null || date.compareTo(firstDate) < 0) firstDate = date;
                if (lastDate == null || date.compareTo(lastDate) > 0) lastDate = date;
            }
            if (game.hasAnnotations()) annotated++;
        }

        JsonObject result = new JsonObject();
        result.addProperty("game_count", db.gameCount());
        result.addProperty("database_file",
            db.path() != null ? db.path().toString() : "(in-memory)");
        result.addProperty("unique_players", players.size());
        result.addProperty("unique_events", events.size());
        if (firstDate != null) {
            result.addProperty("first_game_date", firstDate);
            result.addProperty("last_game_date", lastDate);
        }
        result.addProperty("annotated_games", annotated);
        result.add("top_ecos", topCounts(ecoCounts, 10));
        result.add("top_openings", topCounts(openingCounts, 10));
        result.add("top_tags", topCounts(db.gameTagCounts(), 20));
        return result;
    }

    private static JsonArray topCounts(Map<String, Integer> counts, int limit) {
        JsonArray array = new JsonArray();
        counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(limit)
            .forEach(entry -> {
                JsonObject row = new JsonObject();
                row.addProperty("name", entry.getKey());
                row.addProperty("games", entry.getValue());
                array.add(row);
            });
        return array;
    }

    private JsonObject searchGames(JsonObject args) {
        Search search = applySearchFilters(db.search(), args);
        String s;
        if ((s = optString(args, "sort_by")) != null) {
            search.sortBy(Search.Sort.valueOf(s.toUpperCase(Locale.ROOT)),
                Boolean.TRUE.equals(optBool(args, "descending")));
        } else if (Boolean.TRUE.equals(optBool(args, "descending"))) {
            search.sortBy(Search.Sort.ID, true);
        }

        int limit = optInt(args, "limit") != null ? optInt(args, "limit") : 25;
        limit = Math.max(1, Math.min(limit, 500));
        int total = search.count();
        Integer offset = optInt(args, "offset");
        search.limit(limit).offset(offset != null ? offset : 0);

        JsonArray games = new JsonArray();
        for (Game game : search.find()) {
            games.add(gameSummary(game));
        }
        JsonObject result = new JsonObject();
        result.addProperty("total_count", total);
        result.addProperty("returned", games.size());
        result.add("games", games);
        return result;
    }

    private static Search applySearchFilters(Search search, JsonObject args) {
        String s;
        if ((s = optString(args, "white")) != null) search.white(s);
        if ((s = optString(args, "black")) != null) search.black(s);
        if ((s = optString(args, "player")) != null) search.player(s);
        if ((s = optString(args, "event")) != null) search.event(s);
        if ((s = optString(args, "site")) != null) search.site(s);
        if ((s = optString(args, "eco")) != null) search.eco(s);
        if ((s = optString(args, "opening")) != null) search.opening(s);
        if ((s = optString(args, "result")) != null) search.result(s);
        if ((s = optString(args, "date_from")) != null) search.dateFrom(s);
        if ((s = optString(args, "date_to")) != null) search.dateTo(s);
        Integer i;
        if ((i = optInt(args, "min_elo")) != null) search.minElo(i);
        if ((i = optInt(args, "max_elo")) != null) search.maxElo(i);
        if ((s = optString(args, "elo_mode")) != null) {
            search.eloMode(Search.EloMode.valueOf(s.toUpperCase(Locale.ROOT)));
        }
        if ((i = optInt(args, "min_ply")) != null) search.minPly(i);
        if ((i = optInt(args, "max_ply")) != null) search.maxPly(i);
        if ((s = optString(args, "opening_line")) != null) search.openingLine(s);
        if ((s = optString(args, "contains_fen")) != null) search.containsPosition(s);
        if ((s = optString(args, "material")) != null) search.material(s);
        if ((i = optInt(args, "max_pieces")) != null) search.maxPieces(i);
        if ((s = optString(args, "motif")) != null) search.motif(s);
        if ((s = optString(args, "pawn_feature")) != null) search.pawnFeature(s);
        if ((s = optString(args, "comment_contains")) != null) search.commentContains(s);
        List<String> tags = optStrings(args, "tags");
        if (!tags.isEmpty()) {
            String mode = optString(args, "tag_mode");
            if (mode == null || mode.equalsIgnoreCase("all")) {
                search.tags(tags);
            } else if (mode.equalsIgnoreCase("any")) {
                search.anyTags(tags);
            } else {
                throw new IllegalArgumentException("tag_mode must be 'all' or 'any'");
            }
        }
        List<String> excludedTags = optStrings(args, "exclude_tags");
        if (!excludedTags.isEmpty()) search.withoutTags(excludedTags);
        Boolean b;
        if ((b = optBool(args, "annotated")) != null) search.annotated(b);
        return search;
    }

    private JsonObject getGame(JsonObject args) {
        int id = reqInt(args, "game_id");
        Game game = db.game(id);
        if (game == null) throw new ToolError("No game with id " + id);
        GameData data = db.data(id);

        JsonObject result = gameSummary(game);
        JsonObject tags = new JsonObject();
        game.tags().forEach(tags::addProperty);
        result.add("tags", tags);

        JsonArray moves = new JsonArray();
        for (String san : game.mainlineSans()) moves.add(san);
        result.add("mainline", moves);

        result.addProperty("pgn", new PgnWriter().write(game));
        if (data != null) {
            result.addProperty("final_fen", data.finalFen());
        }

        JsonArray moments = new JsonArray();
        for (Motifs.Moment moment : Motifs.moments(game)) {
            JsonObject m = new JsonObject();
            m.addProperty("ply", moment.ply());
            m.addProperty("move_number", moment.ply() / 2 + 1);
            m.addProperty("san", moment.san());
            JsonArray names = new JsonArray();
            for (String name : moment.motifNames()) names.add(name);
            m.add("motifs", names);
            moments.add(m);
        }
        result.add("tactical_moments", moments);
        return result;
    }

    private JsonObject openingExplorer(JsonObject args) {
        String fen = optString(args, "fen");
        String moves = optString(args, "moves");
        OpeningExplorer.Result explored = fen != null
            ? db.explorer().exploreFen(fen)
            : db.explorer().explore(moves != null ? moves : "");

        JsonObject result = new JsonObject();
        result.addProperty("fen", explored.fen());
        result.addProperty("side_to_move", explored.whiteToMove() ? "white" : "black");
        if (explored.openingEco() != null) {
            result.addProperty("eco", explored.openingEco());
            result.addProperty("opening", explored.openingName());
        }
        result.addProperty("total_games", explored.totalGames());
        result.addProperty("white_wins", explored.whiteWins());
        result.addProperty("draws", explored.draws());
        result.addProperty("black_wins", explored.blackWins());

        JsonArray moveArray = new JsonArray();
        for (OpeningExplorer.MoveStat stat : explored.moves()) {
            JsonObject m = new JsonObject();
            m.addProperty("san", stat.san());
            m.addProperty("games", stat.games());
            m.addProperty("white_wins", stat.whiteWins());
            m.addProperty("draws", stat.draws());
            m.addProperty("black_wins", stat.blackWins());
            m.addProperty("score_percent_for_side_to_move", stat.scorePercent());
            if (stat.averageElo() != null) {
                m.addProperty("average_elo", stat.averageElo());
            }
            if (stat.lastPlayed() != null) {
                m.addProperty("last_played", stat.lastPlayed());
            }
            JsonArray samples = new JsonArray();
            for (int gameId : stat.sampleGames()) {
                Game sample = db.game(gameId);
                if (sample != null) {
                    JsonObject sj = new JsonObject();
                    sj.addProperty("game_id", gameId);
                    sj.addProperty("white", sample.white());
                    sj.addProperty("black", sample.black());
                    sj.addProperty("result", sample.result());
                    sj.addProperty("date", sample.date());
                    samples.add(sj);
                }
            }
            m.add("sample_games", samples);
            moveArray.add(m);
        }
        result.add("moves", moveArray);

        if (!explored.gamesEndingHere().isEmpty()) {
            JsonArray ending = new JsonArray();
            for (int gameId : explored.gamesEndingHere()) ending.add(gameId);
            result.add("games_ending_here", ending);
        }
        return result;
    }

    private JsonObject positionSearch(JsonObject args) {
        String fen = reqString(args, "fen");
        long hash = Board.fromFen(fen).zobrist();
        Integer limitArg = optInt(args, "limit");
        int limit = limitArg != null ? Math.max(1, limitArg) : 50;

        JsonArray games = new JsonArray();
        long[] entries = db.positionEntries(hash);
        for (long entry : entries) {
            if (games.size() >= limit) break;
            int gameId = Database.entryGameId(entry);
            int ply = Database.entryPly(entry);
            Game game = db.game(gameId);
            if (game == null) continue;
            JsonObject row = gameSummary(game);
            row.addProperty("reached_at_ply", ply);
            row.addProperty("reached_at_move", ply / 2 + 1);
            List<String> sans = game.mainlineSans();
            if (ply < sans.size()) {
                row.addProperty("next_move", sans.get(ply));
            }
            games.add(row);
        }
        JsonObject result = new JsonObject();
        result.addProperty("total_count", entries.length);
        result.add("games", games);
        return result;
    }

    private JsonObject playerReport(JsonObject args) {
        String player = reqString(args, "player");
        PlayerStats.Report report = db.players().report(player);
        if (report == null) throw new ToolError("No games found for player: " + player);

        JsonObject result = new JsonObject();
        result.addProperty("player", report.name());
        JsonArray matched = new JsonArray();
        for (String name : report.matchedNames()) matched.add(name);
        result.add("matched_names", matched);
        result.addProperty("total_games", report.totalGames());
        result.add("as_white", score(report.asWhite()));
        result.add("as_black", score(report.asBlack()));
        if (report.performance() != null) {
            result.addProperty("performance_rating", report.performance());
        }
        if (report.highestElo() != null) {
            result.addProperty("highest_elo", report.highestElo());
        }
        if (report.firstGameDate() != null) {
            result.addProperty("first_game", report.firstGameDate());
            result.addProperty("last_game", report.lastGameDate());
        }
        result.add("openings_as_white", openings(report.openingsAsWhite()));
        result.add("openings_as_black", openings(report.openingsAsBlack()));
        JsonArray bestWins = new JsonArray();
        for (PlayerStats.BestWin win : report.bestWins()) {
            JsonObject w = new JsonObject();
            w.addProperty("game_id", win.gameId());
            w.addProperty("opponent", win.opponent());
            w.addProperty("opponent_elo", win.opponentElo());
            if (win.date() != null) w.addProperty("date", win.date());
            bestWins.add(w);
        }
        result.add("best_wins", bestWins);
        return result;
    }

    private static JsonObject score(PlayerStats.Score s) {
        JsonObject o = new JsonObject();
        o.addProperty("games", s.games());
        o.addProperty("wins", s.wins());
        o.addProperty("draws", s.draws());
        o.addProperty("losses", s.losses());
        o.addProperty("score_percent", s.percent());
        return o;
    }

    private static JsonArray openings(List<PlayerStats.OpeningStat> stats) {
        JsonArray array = new JsonArray();
        for (PlayerStats.OpeningStat stat : stats) {
            JsonObject o = new JsonObject();
            o.addProperty("eco", stat.eco());
            if (stat.name() != null) o.addProperty("name", stat.name());
            o.addProperty("games", stat.games());
            o.addProperty("score_percent", stat.scorePercent());
            array.add(o);
        }
        return array;
    }

    private JsonObject headToHead(JsonObject args) {
        String a = reqString(args, "player_a");
        String b = reqString(args, "player_b");
        PlayerStats.HeadToHead h2h = db.players().headToHead(a, b);
        if (h2h == null) {
            throw new ToolError("No games between '" + a + "' and '" + b + "'");
        }
        JsonObject result = new JsonObject();
        result.addProperty("player_a", h2h.playerA());
        result.addProperty("player_b", h2h.playerB());
        result.add("overall_for_a", score(h2h.overall()));
        result.add("a_as_white", score(h2h.aAsWhite()));
        result.add("a_as_black", score(h2h.aAsBlack()));
        JsonArray ids = new JsonArray();
        for (int id : h2h.gameIds()) ids.add(id);
        result.add("game_ids", ids);
        return result;
    }

    private JsonObject importPgn(JsonObject args) {
        JsonObject result = importPgnWithoutSave(args);
        if (result.get("games_added").getAsInt() > 0) autoSave();
        return result;
    }

    private JsonObject importPgnWithoutSave(JsonObject args) {
        String text = optString(args, "pgn_text");
        String filePath = optString(args, "file_path");
        if ((text == null) == (filePath == null)) {
            throw new ToolError("Provide exactly one of pgn_text or file_path");
        }
        Database.ImportReport report;
        try {
            report = filePath != null
                ? db.importFile(Path.of(filePath))
                : db.importPgn(text);
        } catch (IOException e) {
            throw new ToolError("Cannot read " + filePath + ": " + e.getMessage());
        }
        JsonObject result = new JsonObject();
        result.addProperty("games_added", report.gamesAdded());
        result.addProperty("duplicates_skipped", report.duplicatesSkipped());
        result.addProperty("games_rejected", report.gamesRejected());
        result.addProperty("game_count", db.gameCount());
        JsonArray warnings = new JsonArray();
        for (String warning : report.warnings()) warnings.add(warning);
        result.add("warnings", warnings);
        JsonArray errors = new JsonArray();
        for (Database.ImportError error : report.errors()) {
            JsonObject item = new JsonObject();
            item.addProperty("game_number", error.gameNumber());
            item.addProperty("white", error.white());
            item.addProperty("black", error.black());
            item.addProperty("code", error.code());
            item.addProperty("message", error.message());
            errors.add(item);
        }
        result.add("errors", errors);
        return result;
    }

    private JsonObject addGame(JsonObject args) {
        JsonObject result = addGameWithoutSave(args);
        autoSave();
        return result;
    }

    private JsonObject addGameWithoutSave(JsonObject args) {
        String text = reqString(args, "pgn_text");
        var parsed = new com.oriondb.pgn.PgnReader(
            com.oriondb.pgn.PgnReader.ValidationMode.STRICT).readString(text);
        if (!parsed.rejectedGames().isEmpty()) {
            var rejection = parsed.rejectedGames().get(0);
            String reason = rejection.errors().isEmpty()
                ? "strict validation failed"
                : rejection.errors().get(0).message();
            throw new ToolError(
                "Game " + rejection.gameNumber() + " rejected: " + reason);
        }
        if (parsed.games().size() != 1) {
            throw new ToolError("Expected exactly one game, found " + parsed.games().size());
        }
        int id = db.add(parsed.games().get(0));
        JsonObject result = new JsonObject();
        result.addProperty("game_id", id);
        result.addProperty("game_count", db.gameCount());
        JsonArray warnings = new JsonArray();
        for (String warning : parsed.warnings()) warnings.add(warning);
        if (warnings.size() > 0) result.add("warnings", warnings);
        return result;
    }

    private JsonObject deleteGame(JsonObject args) {
        JsonObject result = deleteGameWithoutSave(args);
        autoSave();
        return result;
    }

    private JsonObject deleteGameWithoutSave(JsonObject args) {
        int id = reqInt(args, "game_id");
        Game game = db.game(id);
        if (game == null) throw new ToolError("No game with id " + id);
        String description = game.toString();
        db.delete(id);
        JsonObject result = new JsonObject();
        result.addProperty("deleted", description);
        result.addProperty("game_count", db.gameCount());
        return result;
    }

    private JsonObject setTag(JsonObject args) {
        JsonObject result = setTagWithoutSave(args);
        autoSave();
        return result;
    }

    private JsonObject setTagWithoutSave(JsonObject args) {
        int id = reqInt(args, "game_id");
        String key = reqString(args, "key");
        String value = optString(args, "value");
        if (!db.setTag(id, key, value)) {
            throw new ToolError("No game with id " + id);
        }
        JsonObject result = new JsonObject();
        result.addProperty("game_id", id);
        result.addProperty("tag", key);
        if (value != null) {
            result.addProperty("value", value);
        } else {
            result.addProperty("removed", true);
        }
        return result;
    }

    private JsonObject updateGameTags(JsonObject args) {
        JsonObject result = updateGameTagsWithoutSave(args);
        if (result.get("games_updated").getAsInt() > 0) autoSave();
        return result;
    }

    private JsonObject updateGameTagsWithoutSave(JsonObject args) {
        List<Integer> ids = reqInts(args, "game_ids");
        boolean hasSet = args.has("set") && !args.get("set").isJsonNull();
        List<String> replacement = optStrings(args, "set");
        List<String> add = optStrings(args, "add");
        List<String> remove = optStrings(args, "remove");
        if (hasSet && (!add.isEmpty() || !remove.isEmpty())) {
            throw new ToolError("set cannot be combined with add or remove");
        }
        if (!hasSet && add.isEmpty() && remove.isEmpty()) {
            throw new ToolError("Provide set, add, or remove");
        }

        Database.TagUpdateReport report = hasSet
            ? db.setGameTags(ids, replacement)
            : db.updateGameTags(ids, add, remove);

        JsonObject result = new JsonObject();
        result.addProperty("games_updated", report.gamesUpdated());
        result.addProperty("games_unchanged", report.gamesUnchanged());
        JsonArray missing = new JsonArray();
        for (int id : report.missingGameIds()) missing.add(id);
        result.add("missing_game_ids", missing);

        JsonArray games = new JsonArray();
        for (int id : ids) {
            Game game = db.game(id);
            if (game == null) continue;
            JsonObject item = new JsonObject();
            item.addProperty("game_id", id);
            JsonArray gameTags = new JsonArray();
            for (String tag : game.gameTags()) gameTags.add(tag);
            item.add("game_tags", gameTags);
            games.add(item);
        }
        result.add("games", games);
        return result;
    }

    private JsonObject tagGamesBySearch(JsonObject args) {
        JsonObject result = tagGamesBySearchWithoutSave(args);
        if (result.get("games_updated").getAsInt() > 0) autoSave();
        return result;
    }

    private JsonObject tagGamesBySearchWithoutSave(JsonObject args) {
        if (!hasSearchFilter(args)
                && !Boolean.TRUE.equals(optBool(args, "match_all"))) {
            throw new ToolError(
                "Provide at least one search filter, or set match_all to true");
        }
        boolean hasSet = args.has("set") && !args.get("set").isJsonNull();
        List<String> replacement = optStrings(args, "set");
        List<String> add = optStrings(args, "add");
        List<String> remove = optStrings(args, "remove");
        if (hasSet && (!add.isEmpty() || !remove.isEmpty())) {
            throw new ToolError("set cannot be combined with add or remove");
        }
        if (!hasSet && add.isEmpty() && remove.isEmpty()) {
            throw new ToolError("Provide set, add, or remove");
        }

        Search search = applySearchFilters(db.search(), args);
        Database.TagUpdateReport report = hasSet
            ? search.setGameTags(replacement)
            : search.updateGameTags(add, remove);
        JsonObject result = new JsonObject();
        result.addProperty("games_updated", report.gamesUpdated());
        result.addProperty("games_unchanged", report.gamesUnchanged());
        result.addProperty(
            "games_matched", report.gamesUpdated() + report.gamesUnchanged());
        return result;
    }

    private static boolean hasSearchFilter(JsonObject args) {
        for (String key : SEARCH_FILTER_ARGUMENTS) {
            JsonElement value = args.get(key);
            if (value == null || value.isJsonNull()) continue;
            if (value.isJsonArray() && value.getAsJsonArray().isEmpty()) continue;
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    && value.getAsString().isBlank()) {
                continue;
            }
            return true;
        }
        return false;
    }

    private JsonObject manageGameTag(JsonObject args) {
        JsonObject result = manageGameTagWithoutSave(args);
        if (result.get("games_updated").getAsInt() > 0) autoSave();
        return result;
    }

    private JsonObject manageGameTagWithoutSave(JsonObject args) {
        String action = reqString(args, "action").toLowerCase(Locale.ROOT);
        String source = reqString(args, "source");
        String destination = optString(args, "destination");
        Database.TagUpdateReport report;
        switch (action) {
            case "rename":
                if (destination == null) {
                    throw new ToolError("rename requires destination");
                }
                report = db.renameGameTag(source, destination);
                break;
            case "merge":
                if (destination == null) {
                    throw new ToolError("merge requires destination");
                }
                report = db.mergeGameTag(source, destination);
                break;
            case "delete":
                if (destination != null) {
                    throw new ToolError("delete does not accept destination");
                }
                report = db.deleteGameTag(source);
                break;
            default:
                throw new ToolError("action must be rename, merge, or delete");
        }
        JsonObject result = new JsonObject();
        result.addProperty("action", action);
        result.addProperty("source", source);
        if (destination != null) result.addProperty("destination", destination);
        result.addProperty("games_updated", report.gamesUpdated());
        result.addProperty("games_unchanged", report.gamesUnchanged());
        return result;
    }

    private JsonObject batchMutate(JsonObject args) {
        JsonElement operationsElement = args.get("operations");
        if (operationsElement == null || !operationsElement.isJsonArray()) {
            throw new ToolError("Missing required array: operations");
        }
        JsonArray operations = operationsElement.getAsJsonArray();
        if (operations.isEmpty()) {
            throw new ToolError("operations must contain at least one operation");
        }

        try {
            return db.transactionAndSave(ignored -> {
                JsonArray results = new JsonArray();
                for (int i = 0; i < operations.size(); i++) {
                    JsonElement element = operations.get(i);
                    if (!element.isJsonObject()) {
                        throw new ToolError(
                            "Batch operation " + (i + 1) + " must be an object");
                    }
                    JsonObject operation = element.getAsJsonObject();
                    String action = optString(operation, "action");
                    if (action == null) {
                        throw new ToolError(
                            "Batch operation " + (i + 1) + " is missing action");
                    }
                    JsonObject operationResult;
                    try {
                        operationResult = mutateWithoutSave(action, operation);
                        requireCompleteBatchOperation(action, operationResult);
                    } catch (ToolError | IllegalArgumentException e) {
                        throw new ToolError("Batch operation " + (i + 1) + " (" + action
                            + ") failed: " + e.getMessage());
                    }
                    JsonObject item = new JsonObject();
                    item.addProperty("action", action);
                    item.add("result", operationResult);
                    results.add(item);
                }
                JsonObject result = new JsonObject();
                result.addProperty("operations_applied", results.size());
                result.addProperty("game_count", db.gameCount());
                result.add("results", results);
                return result;
            });
        } catch (IOException e) {
            throw new ToolError("Batch rolled back because saving failed: " + e.getMessage());
        }
    }

    private JsonObject mutateWithoutSave(String action, JsonObject args) {
        return switch (action) {
            case "add_game" -> addGameWithoutSave(args);
            case "import_pgn" -> importPgnWithoutSave(args);
            case "delete_game" -> deleteGameWithoutSave(args);
            case "set_tag" -> setTagWithoutSave(args);
            case "update_game_tags" -> updateGameTagsWithoutSave(args);
            default -> throw new ToolError("Unknown mutation action: " + action);
        };
    }

    private static void requireCompleteBatchOperation(
            String action, JsonObject result) {
        if ("import_pgn".equals(action)
                && result.get("games_rejected").getAsInt() > 0) {
            throw new ToolError(
                result.get("games_rejected").getAsInt() + " imported games were rejected");
        }
        if ("update_game_tags".equals(action)
                && !result.getAsJsonArray("missing_game_ids").isEmpty()) {
            throw new ToolError("Unknown game ids: "
                + result.getAsJsonArray("missing_game_ids"));
        }
    }

    private JsonObject exportPgn(JsonObject args) {
        List<Game> games = new ArrayList<>();
        JsonElement idsElement = args.get("game_ids");
        if (idsElement != null && idsElement.isJsonArray()) {
            for (JsonElement e : idsElement.getAsJsonArray()) {
                Game game = db.game(e.getAsInt());
                if (game == null) throw new ToolError("No game with id " + e.getAsInt());
                games.add(game);
            }
        } else {
            games.addAll(db.games());
        }
        JsonObject result = new JsonObject();
        result.addProperty("games_exported", games.size());

        String filePath = optString(args, "file_path");
        if (filePath != null) {
            try (var writer =
                     Files.newBufferedWriter(Path.of(filePath), StandardCharsets.UTF_8)) {
                new PgnWriter().write(games, writer);
            } catch (IOException e) {
                throw new ToolError("Cannot write " + filePath + ": " + e.getMessage());
            }
            result.addProperty("file_path", filePath);
        } else {
            String pgn = new PgnWriter().write(games);
            if (pgn.length() > 400_000) {
                throw new ToolError("Export is " + pgn.length()
                    + " characters; pass file_path to write it to a file instead");
            }
            result.addProperty("pgn", pgn);
        }
        return result;
    }

    private JsonObject findDuplicates(JsonObject args) {
        List<Duplicates.Group> groups = Duplicates.find(db);
        JsonArray array = new JsonArray();
        for (Duplicates.Group group : groups) {
            JsonObject g = new JsonObject();
            JsonArray ids = new JsonArray();
            for (int id : group.gameIds()) ids.add(id);
            g.add("game_ids", ids);
            g.addProperty("same_players", group.samePlayers());
            Game first = db.game(group.gameIds().get(0));
            if (first != null) {
                g.addProperty("example", first.toString());
            }
            array.add(g);
        }
        JsonObject result = new JsonObject();
        result.addProperty("group_count", groups.size());
        result.add("groups", array);
        return result;
    }

    // ==================================================================

    private JsonObject gameSummary(Game game) {
        GameData data = db.data(game.id());
        JsonObject summary = new JsonObject();
        summary.addProperty("game_id", game.id());
        summary.addProperty("white", game.white());
        summary.addProperty("black", game.black());
        if (game.whiteElo() != null) summary.addProperty("white_elo", game.whiteElo());
        if (game.blackElo() != null) summary.addProperty("black_elo", game.blackElo());
        summary.addProperty("result", game.result());
        summary.addProperty("event", game.event());
        summary.addProperty("date", game.date());
        String eco = game.tag("ECO");
        if (eco == null && data != null) eco = data.eco();
        if (eco != null) summary.addProperty("eco", eco);
        String opening = game.tag("Opening");
        if (opening == null && data != null) opening = data.openingName();
        if (opening != null) summary.addProperty("opening", opening);
        JsonArray tags = new JsonArray();
        for (String tag : game.gameTags()) tags.add(tag);
        summary.add("game_tags", tags);
        summary.addProperty("ply_count", game.plyCount());
        if (game.hasAnnotations()) summary.addProperty("annotated", true);
        return summary;
    }

    private void autoSave() {
        if (db.path() == null) return;
        try {
            db.save();
        } catch (IOException e) {
            throw new ToolError("Change applied in memory but saving failed: " + e.getMessage());
        }
    }
}
