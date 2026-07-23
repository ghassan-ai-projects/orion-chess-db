package com.oriondb.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.oriondb.db.Database;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * The built-in MCP (Model Context Protocol) server.
 *
 * Speaks JSON-RPC 2.0 over stdio (one JSON object per line), implementing
 * the MCP lifecycle (initialize / initialized / ping) and the tools
 * capability. All database features — search, opening explorer, player
 * reports, position search, import/export, editing — are exposed as tools
 * defined in {@link DbTools}.
 *
 * Run with: {@code java -jar oriondb.jar mcp <database.pgn>}
 */
public final class McpServer {
    static final String PROTOCOL_VERSION = "2025-06-18";
    static final String SERVER_NAME = "oriondb";
    static final String SERVER_VERSION = "2.0.0";

    private final Database db;
    private final DbTools tools;
    private final BufferedReader in;
    private final PrintStream out;
    private final Gson gson = new Gson();

    public McpServer(Database db) {
        this(db,
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
            new PrintStream(System.out, true, StandardCharsets.UTF_8));
    }

    McpServer(Database db, BufferedReader in, PrintStream out) {
        this.db = db;
        this.tools = new DbTools(db);
        this.in = in;
        this.out = out;
    }

    /** Serve until stdin closes. */
    public void run() throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            handleLine(line);
        }
    }

    /** Handle one raw JSON-RPC message line (package-visible for tests). */
    void handleLine(String line) {
        JsonObject message;
        try {
            JsonElement parsed = JsonParser.parseString(line);
            if (!parsed.isJsonObject()) {
                sendError(null, -32600, "Invalid request: not an object");
                return;
            }
            message = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            sendError(null, -32700, "Parse error: " + e.getMessage());
            return;
        }

        JsonElement id = message.get("id");
        String method = message.has("method") && message.get("method").isJsonPrimitive()
            ? message.get("method").getAsString() : null;

        if (method == null) {
            // A response from the client (we never send requests) — ignore.
            return;
        }

        boolean isNotification = id == null || id.isJsonNull();
        try {
            JsonElement result = dispatch(method, message.getAsJsonObject("params"), isNotification);
            if (!isNotification && result != null) {
                sendResult(id, result);
            }
        } catch (McpMethodNotFound e) {
            if (!isNotification) {
                sendError(id, -32601, "Method not found: " + method);
            }
        } catch (Exception e) {
            if (!isNotification) {
                sendError(id, -32603, "Internal error: " + e.getMessage());
            }
        }
    }

    private static final class McpMethodNotFound extends RuntimeException {}

    /** @return the result, or null for handled notifications. */
    private JsonElement dispatch(String method, JsonObject params, boolean isNotification) {
        switch (method) {
            case "initialize":
                return initializeResult(params);
            case "ping":
                return new JsonObject();
            case "tools/list":
                return tools.listTools();
            case "tools/call":
                return tools.callTool(params);
            case "resources/list": {
                // Not a declared capability, but answer probes gracefully.
                JsonObject result = new JsonObject();
                result.add("resources", new com.google.gson.JsonArray());
                return result;
            }
            case "prompts/list": {
                JsonObject result = new JsonObject();
                result.add("prompts", new com.google.gson.JsonArray());
                return result;
            }
            case "notifications/initialized":
            case "notifications/cancelled":
            case "notifications/roots/list_changed":
                return null;
            default:
                if (isNotification && method.startsWith("notifications/")) {
                    return null;
                }
                throw new McpMethodNotFound();
        }
    }

    private JsonElement initializeResult(JsonObject params) {
        // Echo a protocol version we support; otherwise offer our latest.
        String requested = params != null && params.has("protocolVersion")
            ? params.get("protocolVersion").getAsString() : PROTOCOL_VERSION;
        String version = switch (requested) {
            case "2024-11-05", "2025-03-26", "2025-06-18" -> requested;
            default -> PROTOCOL_VERSION;
        };

        JsonObject capabilities = new JsonObject();
        JsonObject toolsCap = new JsonObject();
        toolsCap.addProperty("listChanged", false);
        capabilities.add("tools", toolsCap);

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", SERVER_NAME);
        serverInfo.addProperty("version", SERVER_VERSION);

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", version);
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);
        result.addProperty("instructions",
            "OrionDB chess database with " + db.gameCount() + " games"
            + (db.path() != null ? " (" + db.path() + ")" : " (in-memory)")
            + ". Use search_games for metadata/position/tactic filters, "
            + "opening_explorer for move statistics from any position, "
            + "player_report and head_to_head for player analysis, and "
            + "import_pgn / add_game / delete_game / set_tag to edit, or "
            + "tag_games_by_search for bulk categorization and batch_mutate "
            + "for all-or-nothing edits. "
            + "Mutations save to disk automatically.");
        return result;
    }

    private void sendResult(JsonElement id, JsonElement result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        send(response);
    }

    private void sendError(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        response.add("error", error);
        send(response);
    }

    private void send(JsonObject message) {
        out.println(gson.toJson(message));
    }
}
