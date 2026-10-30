package com.oriondb.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.oriondb.db.Database;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * The built-in MCP (Model Context Protocol) server: newline-delimited
 * JSON-RPC 2.0 over stdio, implementing the lifecycle (initialize, ping)
 * and the tools capability. Only protocol messages are written to
 * {@code out}; diagnostics go to stderr.
 */
public final class McpServer {
    static final String LATEST_PROTOCOL = "2025-06-18";
    static final Set<String> PROTOCOLS = Set.of("2024-11-05", "2025-03-26", "2025-06-18");
    static final String NAME = "oriondb", VERSION = "3.0.0";

    static final int PARSE_ERROR = -32700, INVALID_REQUEST = -32600, METHOD_NOT_FOUND = -32601,
        INVALID_PARAMS = -32602, INTERNAL_ERROR = -32603;

    private final Database db;
    private final Tools tools;
    private final PrintStream out;
    private final PrintStream log;

    private McpServer(Database db, Context ctx, PrintStream out, PrintStream log) {
        this.db = db;
        this.tools = new Tools(ctx, log);
        this.out = out;
        this.log = log;
    }

    /** Serves MCP (newline-delimited JSON-RPC) on the given streams until input ends. */
    public static void serve(Database db, InputStream in, PrintStream out) {
        PrintStream log = System.err;
        try (Context ctx = new Context(db)) {
            McpServer server = new McpServer(db, ctx, out, log);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    server.handleLine(line);
                } catch (RuntimeException | StackOverflowError e) {
                    log.println("oriondb mcp: unexpected failure handling a message: " + e);
                    server.send(error(null, INTERNAL_ERROR, "internal error: " + e));
                }
            }
        } catch (IOException e) {
            log.println("oriondb mcp: input closed: " + e.getMessage());
        }
    }

    void handleLine(String line) {
        JsonElement msg;
        try {
            msg = JsonParser.parseString(line);
        } catch (JsonParseException e) {
            send(error(null, PARSE_ERROR, "parse error: " + e.getMessage()));
            return;
        }
        if (msg.isJsonArray()) {
            // JSON-RPC batch (2025-03-26): answer all requests in one array.
            JsonArray responses = new JsonArray();
            for (JsonElement m : msg.getAsJsonArray()) {
                JsonObject r = handle(m);
                if (r != null) responses.add(r);
            }
            if (!responses.isEmpty()) send(responses);
            return;
        }
        JsonObject r = handle(msg);
        if (r != null) send(r);
    }

    /** Handles one message; returns the response, or null for notifications and client responses. */
    private JsonObject handle(JsonElement m) {
        if (!m.isJsonObject()) return error(null, INVALID_REQUEST, "invalid request: expected a JSON object");
        JsonObject msg = m.getAsJsonObject();
        JsonElement id = msg.get("id");
        boolean notification = id == null || id.isJsonNull();
        JsonElement methodEl = msg.get("method");
        if (methodEl == null || !methodEl.isJsonPrimitive()) {
            if (msg.has("result") || msg.has("error")) return null; // a response to us: we never send requests
            return error(id, INVALID_REQUEST, "invalid request: missing 'method'");
        }
        String method = methodEl.getAsString();
        JsonElement paramsEl = msg.get("params");
        if (paramsEl != null && !paramsEl.isJsonNull() && !paramsEl.isJsonObject()) {
            return notification ? null : error(id, INVALID_PARAMS, "params must be an object");
        }
        JsonObject params = paramsEl == null || paramsEl.isJsonNull() ? new JsonObject() : paramsEl.getAsJsonObject();
        if (notification) {
            if (!method.startsWith("notifications/")) log.println("oriondb mcp: ignoring notification " + method);
            return null;
        }
        try {
            return switch (method) {
                case "initialize" -> result(id, initialize(params));
                case "ping" -> result(id, new JsonObject());
                case "tools/list" -> result(id, Json.obj("tools", tools.list()));
                case "tools/call" -> callTool(id, params);
                case "resources/list" -> result(id, Json.obj("resources", new JsonArray()));
                case "resources/templates/list" -> result(id, Json.obj("resourceTemplates", new JsonArray()));
                case "prompts/list" -> result(id, Json.obj("prompts", new JsonArray()));
                default -> error(id, METHOD_NOT_FOUND, "method not found: " + method);
            };
        } catch (RuntimeException e) {
            log.println("oriondb mcp: " + method + " failed: " + e);
            return error(id, INTERNAL_ERROR, "internal error: " + Tools.message(e));
        }
    }

    private JsonObject initialize(JsonObject params) {
        JsonElement v = params.get("protocolVersion");
        String requested = v != null && v.isJsonPrimitive() ? v.getAsString() : null;
        String version = requested != null && PROTOCOLS.contains(requested) ? requested : LATEST_PROTOCOL;
        return Json.obj("protocolVersion", version,
            "capabilities", Json.obj("tools", Json.obj("listChanged", false)),
            "serverInfo", Json.obj("name", NAME, "title", "OrionDB chess database", "version", VERSION),
            "instructions", instructions());
    }

    private String instructions() {
        int games = db.size();
        String file = db.path() == null ? "an in-memory database" : db.path().toAbsolutePath().toString();
        return "OrionDB chess database with " + games + " games (" + file + "). "
            + "Read: db_stats (overview), search_games (structured filters or a text 'query': players, ratings, dates, "
            + "openings, move lines, positions by FEN, piece patterns, material, tactical motifs, pawn structures, labels), "
            + "get_game, show_position, opening_explorer (move statistics from any position), player_report, head_to_head, "
            + "list_labels, find_duplicates, export_pgn. "
            + "Change: import_pgn, add_game, edit_game (add moves/variations, comments, NAGs, evals by SAN path), set_tags, "
            + "label_games, manage_label, delete_games, remove_duplicates, and batch to apply several changes atomically. "
            + "Engine: analyze_position, annotate_game (needs Stockfish or $ORIONDB_ENGINE). "
            + "Game ids are stable across sessions (stored in the PGN), so keep and reuse them. "
            + (db.isReadOnly() ? "The database is open read-only: changes are refused."
                : "Every change is atomic and saved to disk automatically.");
    }

    private JsonObject callTool(JsonElement id, JsonObject params) {
        JsonElement nameEl = params.get("name");
        if (nameEl == null || !nameEl.isJsonPrimitive()) return error(id, INVALID_PARAMS, "tools/call needs a tool 'name'");
        Tool tool = tools.get(nameEl.getAsString());
        if (tool == null) return error(id, INVALID_PARAMS, "unknown tool: " + nameEl.getAsString());
        JsonElement args = params.get("arguments");
        if (args != null && !args.isJsonNull() && !args.isJsonObject()) return error(id, INVALID_PARAMS, "'arguments' must be an object");
        JsonObject a = args == null || args.isJsonNull() ? new JsonObject() : args.getAsJsonObject();
        return result(id, tools.call(tool, a).toJson());
    }

    // ------------------------------------------------------------ messages

    private static JsonObject result(JsonElement id, JsonObject result) {
        JsonObject o = Json.obj("jsonrpc", "2.0");
        o.add("id", id);
        o.add("result", result);
        return o;
    }

    static JsonObject error(JsonElement id, int code, String message) {
        JsonObject o = Json.obj("jsonrpc", "2.0");
        o.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        o.add("error", Json.obj("code", code, "message", message));
        return o;
    }

    private void send(JsonElement message) {
        synchronized (out) {
            out.print(Json.write(message));
            out.print('\n');
            out.flush();
        }
    }
}
