package com.oriondb.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oriondb.db.Database;
import com.oriondb.db.DatabaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class McpServerTest {

    private Database db;
    private McpServer server;
    private ByteArrayOutputStream output;

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        db.importPgn(DatabaseTest.FIXTURE);
        output = new ByteArrayOutputStream();
        server = new McpServer(db,
            new BufferedReader(new StringReader("")),
            new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    /** Send one message, return the single-line response (or null). */
    private JsonObject send(String json) {
        output.reset();
        server.handleLine(json);
        String written = output.toString(StandardCharsets.UTF_8).trim();
        if (written.isEmpty()) return null;
        assertFalse(written.contains("\n"), "one response per message");
        return JsonParser.parseString(written).getAsJsonObject();
    }

    private JsonObject callTool(String name, String argsJson) {
        JsonObject response = send("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"" + name + "\",\"arguments\":" + argsJson + "}}");
        assertNotNull(response);
        assertTrue(response.has("result"), response::toString);
        return response.getAsJsonObject("result");
    }

    private static JsonObject payload(JsonObject toolResult) {
        assertFalse(toolResult.has("isError") && toolResult.get("isError").getAsBoolean(),
            toolResult::toString);
        return toolResult.getAsJsonObject("structuredContent");
    }

    // ------------------------------------------------------------------

    @Test
    void initializeHandshake() {
        JsonObject response = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}");
        assertNotNull(response);
        JsonObject result = response.getAsJsonObject("result");
        assertEquals("2025-06-18", result.get("protocolVersion").getAsString());
        assertEquals("oriondb", result.getAsJsonObject("serverInfo").get("name").getAsString());
        assertTrue(result.getAsJsonObject("capabilities").has("tools"));

        // The initialized notification produces no response.
        assertNull(send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
    }

    @Test
    void olderProtocolVersionEchoed() {
        JsonObject response = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2024-11-05\"}}");
        assertEquals("2024-11-05",
            response.getAsJsonObject("result").get("protocolVersion").getAsString());
    }

    @Test
    void toolsList() {
        JsonObject response = send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        JsonArray tools = response.getAsJsonObject("result").getAsJsonArray("tools");
        assertEquals(15, tools.size());
        boolean foundSearch = false;
        boolean foundGameTags = false;
        boolean foundBatch = false;
        for (var tool : tools) {
            JsonObject t = tool.getAsJsonObject();
            assertTrue(t.has("name"));
            assertTrue(t.has("description"));
            assertEquals("object",
                t.getAsJsonObject("inputSchema").get("type").getAsString());
            if (t.get("name").getAsString().equals("search_games")) foundSearch = true;
            if (t.get("name").getAsString().equals("update_game_tags")) foundGameTags = true;
            if (t.get("name").getAsString().equals("batch_mutate")) foundBatch = true;
        }
        assertTrue(foundSearch);
        assertTrue(foundGameTags);
        assertTrue(foundBatch);
    }

    @Test
    void searchGamesTool() {
        JsonObject payload = payload(callTool("search_games",
            "{\"player\":\"morphy\"}"));
        assertEquals(1, payload.get("total_count").getAsInt());
        JsonObject game = payload.getAsJsonArray("games").get(0).getAsJsonObject();
        assertEquals("Morphy, Paul", game.get("white").getAsString());
        assertEquals("C41", game.get("eco").getAsString());
    }

    @Test
    void searchWithMotifAndLimit() {
        JsonObject payload = payload(callTool("search_games",
            "{\"motif\":\"checkmate\",\"limit\":1}"));
        assertEquals(2, payload.get("total_count").getAsInt());
        assertEquals(1, payload.get("returned").getAsInt());
    }

    @Test
    void getGameTool() {
        int id = db.search().player("Morphy").find().get(0).id();
        JsonObject payload = payload(callTool("get_game", "{\"game_id\":" + id + "}"));
        assertEquals("Morphy, Paul", payload.get("white").getAsString());
        assertTrue(payload.get("pgn").getAsString().contains("Rd8#"));
        assertEquals(33, payload.getAsJsonArray("mainline").size());
        assertTrue(payload.getAsJsonArray("tactical_moments").size() > 0);
        assertTrue(payload.has("final_fen"));
    }

    @Test
    void openingExplorerTool() {
        JsonObject payload = payload(callTool("opening_explorer",
            "{\"moves\":\"1. e4\"}"));
        assertEquals(4, payload.get("total_games").getAsInt());
        JsonArray moves = payload.getAsJsonArray("moves");
        assertEquals("e5", moves.get(0).getAsJsonObject().get("san").getAsString());
        assertEquals(3, moves.get(0).getAsJsonObject().get("games").getAsInt());
        assertTrue(moves.get(0).getAsJsonObject().getAsJsonArray("sample_games").size() > 0);
    }

    @Test
    void positionSearchTool() {
        JsonObject payload = payload(callTool("position_search",
            "{\"fen\":\"rnbqkb1r/1p2pppp/p2p1n2/8/3NP3/2N5/PPP2PPP/R1BQKB1R w KQkq - 0 6\"}"));
        assertEquals(1, payload.get("total_count").getAsInt());
        JsonObject game = payload.getAsJsonArray("games").get(0).getAsJsonObject();
        assertEquals("Kasparov, Garry", game.get("white").getAsString());
        assertEquals(10, game.get("reached_at_ply").getAsInt());
    }

    @Test
    void playerAndHeadToHeadTools() {
        JsonObject report = payload(callTool("player_report", "{\"player\":\"Rossi\"}"));
        assertEquals("Rossi, Mario", report.get("player").getAsString());
        assertEquals(2, report.get("total_games").getAsInt());

        JsonObject h2h = payload(callTool("head_to_head",
            "{\"player_a\":\"Rossi\",\"player_b\":\"Bianchi\"}"));
        assertEquals(1, h2h.getAsJsonObject("overall_for_a").get("games").getAsInt());
    }

    @Test
    void mutationTools() {
        JsonObject added = payload(callTool("add_game",
            "{\"pgn_text\":\"[White \\\"New, Player\\\"]\\n[Black \\\"Other, One\\\"]\\n"
            + "[Result \\\"1-0\\\"]\\n\\n1. d4 d5 2. c4 e6 1-0\"}"));
        int newId = added.get("game_id").getAsInt();
        assertEquals(7, added.get("game_count").getAsInt());

        JsonObject tagged = payload(callTool("set_tag",
            "{\"game_id\":" + newId + ",\"key\":\"Event\",\"value\":\"Test Event\"}"));
        assertEquals("Test Event", tagged.get("value").getAsString());
        assertEquals("Test Event", db.game(newId).event());

        JsonObject deleted = payload(callTool("delete_game", "{\"game_id\":" + newId + "}"));
        assertEquals(6, deleted.get("game_count").getAsInt());
        assertNull(db.game(newId));
    }

    @Test
    void bulkGameTagsAndTagSearch() {
        int morphy = db.search().player("Morphy").find().get(0).id();
        int kasparov = db.search().player("Kasparov").find().get(0).id();

        JsonObject updated = payload(callTool("update_game_tags",
            "{\"game_ids\":[" + morphy + "," + kasparov
                + "],\"add\":[\"Tactics\",\"Reviewed\"]}"));
        assertEquals(2, updated.get("games_updated").getAsInt());
        assertEquals(2, updated.getAsJsonArray("games").size());

        payload(callTool("update_game_tags",
            "{\"game_ids\":[" + morphy + "],\"add\":[\"Study\"]}"));

        JsonObject all = payload(callTool("search_games",
            "{\"tags\":[\"tactics\",\"study\"],\"tag_mode\":\"all\"}"));
        assertEquals(1, all.get("total_count").getAsInt());
        JsonObject game = all.getAsJsonArray("games").get(0).getAsJsonObject();
        assertEquals(morphy, game.get("game_id").getAsInt());
        assertTrue(game.getAsJsonArray("game_tags").toString().contains("study"));

        JsonObject excluded = payload(callTool("search_games",
            "{\"tags\":[\"tactics\"],\"exclude_tags\":[\"study\"]}"));
        assertEquals(1, excluded.get("total_count").getAsInt());
        assertEquals(kasparov, excluded.getAsJsonArray("games")
            .get(0).getAsJsonObject().get("game_id").getAsInt());
    }

    @Test
    void batchMutationsCommitTogetherOrRollBackTogether() {
        int morphy = db.search().player("Morphy").find().get(0).id();
        int kasparov = db.search().player("Kasparov").find().get(0).id();

        JsonObject committed = payload(callTool("batch_mutate", """
            {"operations":[
              {"action":"set_tag","game_id":%d,"key":"Annotator","value":"Orion"},
              {"action":"update_game_tags","game_ids":[%d],"add":["study"]}
            ]}
            """.formatted(morphy, kasparov)));
        assertEquals(2, committed.get("operations_applied").getAsInt());
        assertEquals("Orion", db.game(morphy).tag("Annotator"));
        assertTrue(db.game(kasparov).hasGameTag("study"));

        JsonObject failed = callTool("batch_mutate", """
            {"operations":[
              {"action":"set_tag","game_id":%d,"key":"Event","value":"Must Roll Back"},
              {"action":"delete_game","game_id":99999}
            ]}
            """.formatted(morphy));
        assertTrue(failed.get("isError").getAsBoolean());
        assertNotEquals("Must Roll Back", db.game(morphy).event());
    }

    @Test
    void importAndExportTools() {
        JsonObject imported = payload(callTool("import_pgn",
            "{\"pgn_text\":\"[White \\\"A\\\"]\\n[Black \\\"B\\\"]\\n[Result \\\"*\\\"]\\n\\n1. Nf3 *\"}"));
        assertEquals(1, imported.get("games_added").getAsInt());

        JsonObject exported = payload(callTool("export_pgn", "{}"));
        assertEquals(7, exported.get("games_exported").getAsInt());
        assertTrue(exported.get("pgn").getAsString().contains("Morphy"));
    }

    @Test
    void strictImportErrorsAreStructured() {
        JsonObject imported = payload(callTool("import_pgn",
            "{\"pgn_text\":\"[White \\\"Bad\\\"]\\n[Black \\\"Move\\\"]\\n"
                + "[Result \\\"*\\\"]\\n\\n1. e4 e5 2. Nf6 *\"}"));
        assertEquals(0, imported.get("games_added").getAsInt());
        assertEquals(1, imported.get("games_rejected").getAsInt());
        JsonObject error = imported.getAsJsonArray("errors").get(0).getAsJsonObject();
        assertEquals(1, error.get("game_number").getAsInt());
        assertEquals("ILLEGAL_MAINLINE_MOVE", error.get("code").getAsString());

        JsonObject add = callTool("add_game",
            "{\"pgn_text\":\"[Result \\\"*\\\"]\\n\\n1. e4 e5 2. Nf6 *\"}");
        assertTrue(add.get("isError").getAsBoolean());
        assertTrue(add.getAsJsonArray("content").get(0).getAsJsonObject()
            .get("text").getAsString().contains("rejected"));
    }

    @Test
    void dbStatsAndDuplicatesTools() {
        JsonObject stats = payload(callTool("db_stats", "{}"));
        assertEquals(6, stats.get("game_count").getAsInt());
        assertTrue(stats.get("unique_players").getAsInt() >= 10);

        JsonObject dupes = payload(callTool("find_duplicates", "{}"));
        assertEquals(0, dupes.get("group_count").getAsInt());
    }

    @Test
    void toolErrors() {
        JsonObject unknownTool = callTool("no_such_tool", "{}");
        assertTrue(unknownTool.get("isError").getAsBoolean());

        JsonObject badGame = callTool("get_game", "{\"game_id\":99999}");
        assertTrue(badGame.get("isError").getAsBoolean());
        assertTrue(badGame.getAsJsonArray("content").get(0).getAsJsonObject()
            .get("text").getAsString().contains("99999"));

        JsonObject badFen = callTool("position_search", "{\"fen\":\"not a fen\"}");
        assertTrue(badFen.get("isError").getAsBoolean());
    }

    @Test
    void protocolErrors() {
        JsonObject parseError = send("this is not json");
        assertEquals(-32700, parseError.getAsJsonObject("error").get("code").getAsInt());

        JsonObject unknownMethod = send(
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"bogus/method\"}");
        assertEquals(-32601, unknownMethod.getAsJsonObject("error").get("code").getAsInt());

        assertNull(send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/whatever\"}"),
            "unknown notifications are ignored silently");

        JsonObject ping = send("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"ping\"}");
        assertTrue(ping.has("result"));
    }
}
