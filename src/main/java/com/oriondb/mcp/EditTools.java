package com.oriondb.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.oriondb.chess.Position;
import com.oriondb.db.Batch;
import com.oriondb.db.GameRecord;
import com.oriondb.db.ImportException;
import com.oriondb.db.ImportOptions;
import com.oriondb.db.ImportResult;
import com.oriondb.engine.Score;
import com.oriondb.game.Game;
import com.oriondb.game.GameNode;
import com.oriondb.game.Nag;
import com.oriondb.pgn.PgnException;
import com.oriondb.pgn.PgnParser;
import com.oriondb.stats.Duplicates;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** import_pgn, add_game, edit_game, set_tags, delete_games, remove_duplicates. */
final class EditTools {
    private static final Set<String> TOOL_TAGS = Set.of("orionid", "orionlabels", "oriondbid", "oriontags");
    private static final Set<String> STRUCTURAL_TAGS = Set.of("fen", "setup", "variant");

    private EditTools() {}

    static List<Tool> tools(Context ctx) {
        return List.of(
            Tool.mutation("import_pgn", "Import PGN",
                "Import games from a PGN file ('path'; relative paths resolve next to the database) or from PGN text "
                    + "('pgn'). Duplicates (same moves and players) are skipped by default. Invalid games are reported and "
                    + "skipped, or with atomic: true the whole import is rejected. Returns counts, new game ids and errors. "
                    + "Saved to disk automatically.",
                Schema.object()
                    .str("path", "PGN file to import.")
                    .str("pgn", "PGN text to import (one or more games).")
                    .strings("labels", "Labels to put on every imported game.")
                    .bool("skip_duplicates", "Skip games already present (default true).")
                    .bool("atomic", "Import nothing if any game is invalid (default false)."),
                false, (b, a) -> importPgn(ctx, b, a)),
            Tool.mutation("add_game", "Add game",
                "Add one game, from PGN text ('pgn') or from SAN 'moves' (optionally from 'fen') plus header 'tags'. "
                    + "Result defaults to the result implied by mate/stalemate, else '*'. Returns the new game id.",
                Schema.object()
                    .str("pgn", "PGN text of exactly one game.")
                    .str("moves", "SAN moves, e.g. '1. e4 e5 2. Nf3 Nc6'.")
                    .str("fen", "Starting position for 'moves' (default: standard start).")
                    .stringMap("tags", "Header tags, e.g. {\"White\": \"Carlsen, Magnus\", \"Black\": \"Caruana, Fabiano\", "
                        + "\"Result\": \"1-0\", \"Date\": \"2024.01.15\", \"Event\": \"Casual\"}.", false)
                    .strings("labels", "Labels for the game."),
                false, (b, a) -> addGame(b, a)),
            Tool.mutation("edit_game", "Edit game",
                "Edit a game's moves and annotations with an ordered list of operations, applied together (all or "
                    + "nothing). Each operation addresses a node by 'at': the SAN moves from the start leading to it, "
                    + "following variations (e.g. 'e4 c5 Nf3'; empty = before the first move), or by 'ply' on the mainline. "
                    + "Ops: add_moves (moves: play from the node; becomes the mainline if the node has no continuation, "
                    + "otherwise a variation; existing moves are followed), promote (move a variation up one place), "
                    + "make_main (make the line through this node the main line), delete (remove the node and everything "
                    + "after it), truncate (remove everything after the node), set_comment (text; before: true for the comment "
                    + "before the move; empty text removes; [%eval]/[%clk] commands are kept), add_nag / remove_nag "
                    + "(nag: '!', '?', '!!', '??', '!?', '?!', '+-', '=', '$14' ...), set_eval (eval: '0.35', '-1.2', '#3' "
                    + "from White's view; null removes). Returns the updated mainline and PGN.",
                Schema.object()
                    .integer("id", "Game id.", 1, Integer.MAX_VALUE)
                    .prop("operations", Json.obj("type", "array", "minItems", 1,
                        "description", "Operations, applied in order.", "items", opSchema()))
                    .required("id", "operations"),
                false, (b, a) -> editGame(b, a)),
            Tool.mutation("set_tags", "Set tags",
                "Set or remove PGN header tags on one or more games: tags maps names to values (null removes). "
                    + "OrionId/OrionLabels are managed by OrionDB (use label_games); FEN/SetUp/Variant change the moves' "
                    + "meaning (add a corrected game with add_game instead).",
                Schema.object()
                    .integer("id", "Game id.", 1, Integer.MAX_VALUE)
                    .ints("ids", "Several game ids.")
                    .stringMap("tags", "Tag name -> value (null removes the tag), e.g. {\"Round\": \"3\", \"Annotator\": null}.", true)
                    .required("tags"),
                false, (b, a) -> setTags(b, a)),
            Tool.mutation("delete_games", "Delete games",
                "Permanently delete games by id (a .bak of the previous file is kept by the save). Requires confirm: true.",
                Schema.object()
                    .ints("ids", "Game ids to delete.")
                    .bool("confirm", "Must be true.")
                    .required("ids", "confirm"),
                true, (b, a) -> delete(b, a)),
            Tool.mutation("remove_duplicates", "Remove duplicates",
                "Delete redundant copies of duplicate games, keeping the suggested copy of each group (see find_duplicates). "
                    + "dry_run defaults to true: pass dry_run: false to actually delete.",
                SearchTools.duplicateSchema().bool("dry_run", "Only report what would be deleted (default true)."),
                true, (b, a) -> removeDuplicates(ctx, b, a)));
    }

    // ------------------------------------------------------------ import / add

    private static ToolResult importPgn(Context ctx, Batch b, Args a) {
        if (a.has("path") == a.has("pgn")) throw new ToolException("give exactly one of 'path' or 'pgn'");
        ImportOptions opts = new ImportOptions(a.bool("skip_duplicates", true), a.strings("labels"), a.bool("atomic", false));
        ImportResult r;
        String source;
        try {
            if (a.has("path")) {
                Path p = Path.of(a.str("path").strip());
                Path db = ctx.db().path();
                if (!p.isAbsolute() && db != null && db.toAbsolutePath().getParent() != null) p = db.toAbsolutePath().getParent().resolve(p);
                if (!Files.isRegularFile(p)) throw new ToolException("file not found: " + p.toAbsolutePath());
                source = p.toAbsolutePath().toString();
                r = b.importFile(p, opts);
            } else {
                source = "text";
                r = b.importText(a.str("pgn"), opts);
            }
        } catch (ImportException e) {
            throw new ToolException(e.getMessage() + errorList(e.result()));
        }
        StringBuilder sb = new StringBuilder("Imported ").append(r.added()).append(" of ").append(r.read()).append(" games from ").append(source);
        if (r.duplicates() > 0) sb.append(", ").append(r.duplicates()).append(" duplicates skipped");
        if (r.invalid() > 0) sb.append(", ").append(r.invalid()).append(" invalid games skipped");
        sb.append('.');
        if (!r.ids().isEmpty()) sb.append("\nNew ids: ").append(ranges(r.ids(), 40));
        sb.append(errorList(r));
        List<String> errors = r.errors().stream().limit(20).map(ImportResult.Error::toString).toList();
        return ToolResult.of(sb.toString(), Json.obj("read", r.read(), "added", r.added(), "duplicates", r.duplicates(),
            "invalid", r.invalid(), "ids", r.ids().size() > 1000 ? r.ids().subList(0, 1000) : r.ids(),
            "ids_truncated", r.ids().size() > 1000, "errors", errors));
    }

    private static String errorList(ImportResult r) {
        if (r.errors().isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\nErrors:");
        r.errors().stream().limit(20).forEach(e -> sb.append("\n  ").append(e));
        if (r.invalid() > 20) sb.append("\n  ... ").append(r.invalid() - 20).append(" more");
        return sb.toString();
    }

    /** "3-7, 9, 12-14", at most {@code max} ranges. */
    static String ranges(List<Integer> ids, int max) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < ids.size()) {
            int j = i;
            while (j + 1 < ids.size() && ids.get(j + 1) == ids.get(j) + 1) j++;
            parts.add(i == j ? String.valueOf(ids.get(i)) : ids.get(i) + "-" + ids.get(j));
            i = j + 1;
        }
        return Format.limited(parts, max);
    }

    private static ToolResult addGame(Batch b, Args a) {
        Game g;
        if (a.has("pgn")) {
            if (a.has("moves") || a.has("fen") || a.has("tags")) throw new ToolException("give either 'pgn' or 'moves'/'fen'/'tags', not both");
            List<Game> games;
            try {
                games = PgnParser.parseAll(a.str("pgn"));
            } catch (PgnException e) {
                throw new ToolException("invalid PGN: " + e.getMessage());
            }
            if (games.size() != 1) throw new ToolException("'pgn' contains " + games.size() + " games; add_game takes exactly one (use import_pgn for several)");
            g = games.get(0);
        } else {
            Position start = a.has("fen") ? Inputs.fen(a.str("fen")) : Position.start();
            g = new Game(start);
            GameNode n = g.root();
            for (int m : Inputs.moves(start, a.str("moves", ""))) n = n.play(m);
            for (Map.Entry<String, String> e : a.stringMap("tags").entrySet()) {
                checkTagName(e.getKey());
                if (e.getValue() != null) g.tags().set(e.getKey(), e.getValue());
            }
            if (!g.tags().has("Result")) {
                String r = g.terminationResult();
                g.tags().set("Result", r == null ? "*" : r);
            }
        }
        int id = b.add(g, a.strings("labels"));
        GameRecord r = b.get(id);
        return ToolResult.of("Added game #" + id + ": " + Format.rowText(r, -1), Json.obj("id", id, "game", Format.row(r, -1)));
    }

    // ------------------------------------------------------------ edit_game

    private static JsonObject opSchema() {
        JsonObject items = Schema.object()
            .choice("op", "Operation.", "add_moves", "promote", "make_main", "delete", "truncate", "set_comment",
                "add_nag", "remove_nag", "set_eval")
            .str("at", "SAN moves from the start to the node (following variations); empty = the start.")
            .integer("ply", "Alternative to 'at': the mainline node after this many half-moves.", 0, 10000)
            .str("moves", "add_moves: SAN moves to play from the node.")
            .str("text", "set_comment: the comment (empty removes it).")
            .bool("before", "set_comment: set the comment shown before the move instead of after it.")
            .str("nag", "add_nag/remove_nag: '!', '?', '!!', '??', '!?', '?!', '=', '+-', '-+', '$n' ...")
            .prop("eval", Json.obj("type", Json.arr(List.of("string", "null")), "description", "set_eval: '0.35', '-1.20', '#3', '#-2' (White's view); null removes."))
            .required("op")
            .build();
        return items;
    }

    private static ToolResult editGame(Batch b, Args a) {
        int id = a.requireInt("id");
        Game g = b.game(id);
        JsonArray ops = a.array("operations");
        if (ops == null || ops.isEmpty()) throw new ToolException("'operations' must be a non-empty array");
        JsonObject schema = opSchema();
        List<String> done = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            JsonElement e = ops.get(i);
            String op = "?";
            try {
                if (!e.isJsonObject()) throw new ToolException("must be an object like {\"op\": \"add_moves\", \"at\": \"e4\", \"moves\": \"c5\"}");
                Args oa = new Args(e.getAsJsonObject());
                oa.validate(schema);
                op = oa.requireStr("op");
                done.add(apply(g, op, oa));
            } catch (ToolException | IllegalArgumentException | IllegalStateException ex) {
                throw new ToolException("operation " + (i + 1) + " (" + op + ") failed: " + Tools.message(ex)
                    + "\nNo changes were made to game " + id + ".");
            }
        }
        b.replace(id, g);
        Game updated = b.game(id);
        String pgn = b.pgn(id).strip();
        String text = "Edited game #" + id + ":\n- " + String.join("\n- ", done) + "\nMainline: " + updated.mainlineSan()
            + "\n\nPGN:\n" + pgn;
        return ToolResult.of(text, Json.obj("id", id, "operations", done, "mainline", updated.mainlineSan(), "pgn", pgn));
    }

    private static String apply(Game g, String op, Args a) {
        GameNode n = node(g, a);
        String where = n.isRoot() ? "the start" : label(n);
        switch (op) {
            case "add_moves" -> {
                String moves = a.requireStr("moves");
                boolean hadContinuation = n.next() != null;
                GameNode first = null;
                for (int m : Inputs.moves(n.position(), moves)) {
                    n = n.play(m);
                    if (first == null) first = n;
                }
                boolean variation = first != null && !first.isMainline();
                return "added " + moves.strip() + " after " + where + (variation ? " as a variation" : hadContinuation ? " (following the existing line)" : "")
                    + "; ends at '" + path(n) + "'";
            }
            case "promote" -> {
                requireMove(n, op);
                n.promote();
                return "promoted " + where;
            }
            case "make_main" -> {
                requireMove(n, op);
                for (GameNode x = n; !x.isRoot(); x = x.parent()) x.promoteToMainline();
                return "made the line through " + where + " the main line";
            }
            case "delete" -> {
                requireMove(n, op);
                n.delete();
                return "deleted " + where + " and the moves after it";
            }
            case "truncate" -> {
                n.truncate();
                return "removed all moves after " + where;
            }
            case "set_comment" -> {
                String text = a.str("text", "");
                if (a.bool("before", false) && !n.isRoot()) {
                    n.setStartingComment(text);
                } else {
                    Map<String, String> commands = n.commands();
                    n.setComment(text);
                    commands.forEach(n::setCommand);
                }
                return (text.isBlank() ? "removed the comment at " : "set the comment at ") + where;
            }
            case "add_nag", "remove_nag" -> {
                requireMove(n, op);
                int nag = nag(a.requireStr("nag"));
                if (op.equals("add_nag")) n.addNag(nag);
                else n.removeNag(nag);
                return (op.equals("add_nag") ? "added " : "removed ") + Nag.symbol(nag) + " ($" + nag + ") at " + where;
            }
            case "set_eval" -> {
                requireMove(n, op);
                String eval = a.str("eval");
                if (eval == null || eval.isBlank()) {
                    n.setCommand("eval", null);
                    return "removed the eval at " + where;
                }
                Score.parse(eval);
                n.setCommand("eval", eval.strip());
                return "set eval " + eval.strip() + " at " + where;
            }
            default -> throw new ToolException("unknown op '" + op + "'");
        }
    }

    private static GameNode node(Game g, Args a) {
        if (a.has("ply")) {
            if (a.has("at")) throw new ToolException("give 'at' or 'ply', not both");
            int ply = a.integer("ply", 0, 0, 10000);
            GameNode n = g.nodeAtPly(ply);
            if (n == null) throw new ToolException("the mainline has only " + g.plyCount() + " plies");
            return n;
        }
        String at = a.str("at", "");
        GameNode n = g.find(at);
        if (n == null) {
            throw new ToolException("no node at '" + at + "': give consecutive SAN moves from the start, following "
                + "variations (mainline: " + g.mainlineSan() + ")");
        }
        return n;
    }

    private static void requireMove(GameNode n, String op) {
        if (n.isRoot()) throw new ToolException(op + " needs a move: give 'at' with at least one move");
    }

    private static String label(GameNode n) {
        return Format.moveLabel(n.parent().position(), n.move());
    }

    /** SAN path from the start, usable as 'at'. */
    static String path(GameNode n) {
        return String.join(" ", n.line().stream().map(GameNode::san).toList());
    }

    static int nag(String s) {
        String t = s.strip();
        int n = Nag.parse(t);
        if (n < 0 && t.matches("\\d{1,3}")) n = Integer.parseInt(t);
        if (n < 0 || n > 255) throw new ToolException("unknown NAG '" + s + "' (use '!', '?', '!!', '??', '!?', '?!', '=', '+-', '-+' or '$n')");
        return n;
    }

    // ------------------------------------------------------------ tags

    private static void checkTagName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (TOOL_TAGS.contains(n)) throw new ToolException(name + " is managed by OrionDB: ids are fixed and labels are edited with label_games");
        if (STRUCTURAL_TAGS.contains(n)) {
            throw new ToolException(name + " changes what the moves mean and cannot be edited alone; "
                + "add a corrected game with add_game (fen + moves) and delete the old one");
        }
        if (!com.oriondb.game.Tags.isValidName(name)) throw new ToolException("invalid tag name '" + name + "' (letters, digits, _ - . only)");
    }

    private static ToolResult setTags(Batch b, Args a) {
        List<Integer> ids = new ArrayList<>(a.ints("ids"));
        if (a.has("id")) ids.add(0, a.requireInt("id"));
        if (ids.isEmpty()) throw new ToolException("give 'id' or 'ids'");
        Map<String, String> tags = a.stringMap("tags");
        if (tags.isEmpty()) throw new ToolException("'tags' is empty");
        for (String name : tags.keySet()) checkTagName(name);
        for (int id : ids) {
            b.get(id);
            for (Map.Entry<String, String> e : tags.entrySet()) b.setTag(id, e.getKey(), e.getValue());
        }
        List<String> changes = tags.entrySet().stream()
            .map(e -> e.getValue() == null ? "removed " + e.getKey() : e.getKey() + "=\"" + e.getValue() + "\"").toList();
        String text = "Updated " + ids.size() + (ids.size() == 1 ? " game" : " games") + " (" + ranges(ids.stream().sorted().toList(), 20)
            + "): " + String.join(", ", changes);
        return ToolResult.of(text, Json.obj("ids", ids, "changes", changes));
    }

    // ------------------------------------------------------------ delete

    private static ToolResult delete(Batch b, Args a) {
        if (!a.bool("confirm", false)) throw new ToolException("deleting games is permanent; pass confirm: true to proceed");
        List<Integer> ids = a.ints("ids");
        if (ids.isEmpty()) throw new ToolException("'ids' is empty");
        StringBuilder sb = new StringBuilder("Deleted ").append(ids.size()).append(ids.size() == 1 ? " game:" : " games:");
        for (int id : ids) {
            GameRecord r = b.get(id);
            sb.append("\n  ").append(Format.rowText(r, -1));
            b.delete(id);
        }
        return ToolResult.of(sb.toString(), Json.obj("deleted", ids));
    }

    private static ToolResult removeDuplicates(Context ctx, Batch b, Args a) {
        List<Duplicates.Group> groups = SearchTools.findGroups(ctx.snap(), a);
        boolean dry = a.bool("dry_run", true);
        List<Integer> doomed = new ArrayList<>();
        for (Duplicates.Group g : groups) for (int id : g.redundant()) if (b.exists(id)) doomed.add(id);
        if (!dry) doomed.forEach(b::delete);
        String text = (dry ? "Dry run: would delete " : "Deleted ") + doomed.size() + " redundant games from " + groups.size()
            + " duplicate groups" + (doomed.isEmpty() ? "." : ": " + Format.limited(doomed.stream().map(x -> "#" + x).toList(), 100))
            + (dry && !doomed.isEmpty() ? "\nPass dry_run: false to delete them." : "");
        return ToolResult.of(text, Json.obj("dry_run", dry, "groups", groups.size(), "deleted", dry ? List.of() : doomed,
            "would_delete", dry ? doomed : List.of()));
    }
}
