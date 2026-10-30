package com.oriondb.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Protocol handling and the read-only tools. */
class McpServerTest extends McpHarness {

    static final Set<String> TOOLS = Set.of("db_stats", "search_games", "get_game", "show_position", "opening_explorer",
        "player_report", "head_to_head", "list_labels", "import_pgn", "add_game", "edit_game", "set_tags", "label_games",
        "manage_label", "delete_games", "find_duplicates", "remove_duplicates", "export_pgn", "analyze_position",
        "annotate_game", "batch");

    // ------------------------------------------------------------ protocol

    @Test
    void initializeHandshake() {
        List<JsonElement> out = session(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}");
        assertEquals(3, out.size(), "the notification gets no response: " + out);
        JsonObject init = out.get(0).getAsJsonObject().getAsJsonObject("result");
        assertEquals("2025-03-26", init.get("protocolVersion").getAsString());
        assertEquals("oriondb", init.getAsJsonObject("serverInfo").get("name").getAsString());
        assertEquals("3.0.0", init.getAsJsonObject("serverInfo").get("version").getAsString());
        assertTrue(init.getAsJsonObject("capabilities").has("tools"));
        String instructions = init.get("instructions").getAsString();
        assertTrue(instructions.contains("stable") && instructions.contains("saved"), instructions);
        assertEquals(2, out.get(1).getAsJsonObject().get("id").getAsInt());
        assertTrue(out.get(1).getAsJsonObject().getAsJsonObject("result").isEmpty());
        assertEquals("2025-06-18", out.get(2).getAsJsonObject().getAsJsonObject("result").get("protocolVersion").getAsString());
    }

    @Test
    void toolsListHasSchemas() {
        JsonArray tools = request("tools/list", new JsonObject()).getAsJsonObject("result").getAsJsonArray("tools");
        Set<String> names = new HashSet<>();
        for (JsonElement e : tools) {
            JsonObject t = e.getAsJsonObject();
            String name = t.get("name").getAsString();
            names.add(name);
            assertFalse(t.get("description").getAsString().isBlank(), name);
            JsonObject schema = t.getAsJsonObject("inputSchema");
            assertEquals("object", schema.get("type").getAsString(), name);
            assertTrue(schema.has("properties"), name);
            for (var p : schema.getAsJsonObject("properties").entrySet()) {
                assertTrue(p.getValue().getAsJsonObject().has("description"), name + "." + p.getKey() + " lacks a description");
            }
        }
        assertEquals(TOOLS, names);
    }

    @Test
    void protocolErrors() {
        List<JsonElement> out = session(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"no/such/method\"}",
            "{not json",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"no_such_tool\",\"arguments\":{}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"arguments\":{}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"get_game\",\"arguments\":[1]}}",
            "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"ping\"}");
        assertEquals(6, out.size());
        assertEquals(-32601, code(out.get(0)));
        assertEquals(-32700, code(out.get(1)));
        assertTrue(out.get(1).getAsJsonObject().get("id").isJsonNull());
        assertEquals(-32602, code(out.get(2)));
        assertEquals(-32602, code(out.get(3)));
        assertEquals(-32602, code(out.get(4)));
        assertTrue(out.get(5).getAsJsonObject().has("result"), "the loop survives errors");
    }

    private static int code(JsonElement e) {
        return e.getAsJsonObject().getAsJsonObject("error").get("code").getAsInt();
    }

    @Test
    void badToolArgumentsAreToolErrors() {
        assertTrue(fails("search_games", "{\"plyr\": \"carlsen\"}").contains("unknown argument 'plyr'"));
        assertTrue(fails("get_game", "{}").contains("missing required argument 'id'"));
        assertTrue(fails("get_game", "{\"id\": 9999}").contains("no game with id 9999"));
        assertTrue(fails("get_game", "{\"id\": \"abc\"}").contains("integer"));
        assertTrue(fails("search_games", "{\"query\": \"frobnicate:3\"}").contains("unknown query key"));
        assertTrue(fails("search_games", "{\"motifs\": [\"teleport\"]}").contains("unknown motif"));
        assertTrue(fails("show_position", "{\"moves\": \"e4 e5 Ke3\"}").contains("Ke3"));
        assertTrue(fails("show_position", "{\"fen\": \"not a fen\"}").contains("invalid FEN"));
        assertTrue(fails("search_games", "{\"limit\": 501}").contains("between 1 and 500"));
    }

    // ------------------------------------------------------------ read tools

    @Test
    void dbStats() {
        JsonObject res = ok("db_stats", "{}");
        assertTrue(text(res).contains("Games: 6"), text(res));
        assertTrue(text(res).contains(file.toAbsolutePath().toString()));
        assertEquals(6, structured(res).get("games").getAsInt());
    }

    @Test
    void searchStructured() {
        JsonObject s = structured(ok("search_games", "{\"player\": \"carlsen\", \"outcome\": \"win\", \"sort\": \"date\", \"descending\": true}"));
        assertEquals(2, s.get("total").getAsInt());
        JsonArray games = s.getAsJsonArray("games");
        assertEquals("Roe, Jane", games.get(0).getAsJsonObject().get("white").getAsString(), "newest first");

        s = structured(ok("search_games", "{\"min_elo\": 2800, \"elo_mode\": \"either\", \"eco\": \"B90-B99\"}"));
        assertEquals(1, s.get("total").getAsInt());

        s = structured(ok("search_games", "{\"motifs\": [\"queen_sacrifice\"], \"result\": \"1-0\"}"));
        assertTrue(s.get("total").getAsInt() >= 1);

        s = structured(ok("search_games", "{\"line\": \"1. e4 e5\", \"limit\": 1}"));
        assertTrue(s.get("total").getAsInt() >= 3);
        assertEquals(1, s.getAsJsonArray("games").size());
    }

    @Test
    void searchByQueryAndCombined() {
        JsonObject res = ok("search_games", "{\"query\": \"player:carlsen as:black\"}");
        assertEquals(1, structured(res).get("total").getAsInt());
        assertTrue(text(res).contains("Roe, Jane"), text(res));

        // query + structured fields are AND-combined
        JsonObject s = structured(ok("search_games", "{\"query\": \"year:1800..1900\", \"white\": \"morphy\"}"));
        assertEquals(1, s.get("total").getAsInt());
        assertEquals(0, structured(ok("search_games", "{\"query\": \"year:1800..1900\", \"white\": \"carlsen\"}")).get("total").getAsInt());
    }

    @Test
    void searchByPositionReportsMatchPly() {
        // Najdorf position after 5... a6, reached only in the Carlsen game
        JsonObject s = structured(ok("search_games",
            "{\"fen\": \"rnbqkb1r/1p2pppp/p2p1n2/8/3NP3/2N5/PPP2PPP/R1BQKB1R w KQkq - 0 6\"}"));
        assertEquals(1, s.get("total").getAsInt());
        JsonObject row = s.getAsJsonArray("games").get(0).getAsJsonObject();
        assertEquals(10, row.get("match_ply").getAsInt());
        assertEquals("5... a6", row.get("match_move").getAsString());
    }

    @Test
    void getGame() {
        int id = idOf("white:morphy");
        JsonObject res = ok("get_game", "{\"id\": " + id + ", \"ply\": 2}");
        String t = text(res);
        assertTrue(t.contains("[White \"Morphy, Paul\"]"), t);
        assertTrue(t.contains("17. Rd8#"), t);
        assertTrue(t.contains("queen_sacrifice"), t);
        assertTrue(t.contains("PGN:"), t);
        JsonObject s = structured(res);
        assertEquals("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
            s.getAsJsonObject("position").get("fen").getAsString());
        assertTrue(s.get("final_fen").getAsString().startsWith("1n1Rkb1r"));
        assertTrue(s.getAsJsonArray("flags").toString().contains("comments"));
    }

    @Test
    void showPosition() {
        JsonObject res = ok("show_position", "{\"moves\": \"1. e4 c5\"}");
        JsonObject s = structured(res);
        assertEquals("white", s.get("side_to_move").getAsString());
        assertEquals(30, s.getAsJsonArray("legal_moves").size());
        assertTrue(s.getAsJsonObject("opening").get("name").getAsString().contains("Sicilian"));
        assertEquals(1, s.get("games_in_db").getAsInt());
        assertTrue(text(res).contains("r n b q k b n r"), text(res));

        s = structured(ok("show_position", "{\"fen\": \"6k1/5ppp/8/8/8/8/5PPP/3R2K1 b - - 0 1\", \"moves\": \"Kf8\"}"));
        assertEquals("white", s.get("side_to_move").getAsString());

        int id = idOf("white:morphy");
        s = structured(ok("show_position", "{\"game_id\": " + id + "}"));
        assertEquals("checkmate", s.get("status").getAsString());
        assertEquals(0, s.getAsJsonArray("legal_moves").size());
    }

    @Test
    void openingExplorer() {
        JsonObject s = structured(ok("opening_explorer", "{}"));
        assertEquals(6, s.get("games").getAsInt());
        JsonObject first = s.getAsJsonArray("moves").get(0).getAsJsonObject();
        assertEquals("e4", first.get("san").getAsString());
        assertEquals(4, first.get("games").getAsInt());

        s = structured(ok("opening_explorer", "{\"moves\": \"e4\", \"filter\": \"player:carlsen\"}"));
        assertEquals(1, s.get("games").getAsInt());
        assertEquals("c5", s.getAsJsonArray("moves").get(0).getAsJsonObject().get("san").getAsString());
    }

    @Test
    void playerReportAndHeadToHead() {
        JsonObject res = ok("player_report", "{\"name\": \"magnus carlsen\"}");
        assertEquals(2, structured(res).getAsJsonObject("total").get("wins").getAsInt());
        assertTrue(text(res).contains("Openings as White"), text(res));

        res = ok("head_to_head", "{\"player_a\": \"carlsen\", \"player_b\": \"doe\"}");
        assertEquals(1, structured(res).getAsJsonObject("total").get("games").getAsInt());
        assertEquals(1, structured(res).getAsJsonArray("game_ids").size());
        assertTrue(text(ok("player_report", "{\"name\": \"nobody here\"}")).contains("No games"));
    }

    @Test
    void exportPgn() throws Exception {
        String t = text(ok("export_pgn", "{\"query\": \"player:carlsen\", \"comments\": false}"));
        assertEquals(2, t.split("\\[Event ").length - 1, t);
        assertTrue(fails("export_pgn", "{\"path\": \"" + file.toAbsolutePath() + "\"}").contains("refusing"));
        assertTrue(fails("export_pgn", "{\"path\": \"games.pgn\"}").contains("refusing"));
        JsonObject res = ok("export_pgn", "{\"ids\": [1, 2], \"path\": \"out/two.pgn\"}");
        String written = java.nio.file.Files.readString(dir.resolve("out/two.pgn"));
        assertEquals(2, written.split("\\[Event ").length - 1);
        assertTrue(text(res).contains("Wrote 2 games"));
        assertTrue(fails("export_pgn", "{\"ids\": [1], \"path\": \"out/two.pgn\"}").contains("overwrite"));
    }

    @Test
    void findDuplicates() {
        int id = idOf("white:anderssen");
        String pgn = structured(ok("get_game", "{\"id\": " + id + "}")).get("pgn").getAsString();
        ok("import_pgn", Json.write(Json.obj("pgn", pgn, "skip_duplicates", false)));
        JsonObject s = structured(ok("find_duplicates", "{}"));
        assertEquals(1, s.get("groups").getAsInt());
        JsonObject dry = structured(ok("remove_duplicates", "{}"));
        assertTrue(dry.get("dry_run").getAsBoolean());
        assertEquals(7, db.size(), "dry run changes nothing");
        ok("remove_duplicates", "{\"dry_run\": false}");
        assertEquals(6, db.size());
    }
}
