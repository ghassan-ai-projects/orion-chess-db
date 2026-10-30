package com.oriondb.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oriondb.db.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Shared fixture: a temp PGN database opened with auto-save, driven through {@link McpServer#serve}. */
abstract class McpHarness {
    static final String EXTRA = """
        [Event "Club Championship"]
        [Site "Oslo"]
        [Date "2020.03.01"]
        [Round "1"]
        [White "Carlsen, Magnus"]
        [Black "Doe, John"]
        [Result "1-0"]
        [WhiteElo "2850"]
        [BlackElo "2100"]

        1. e4 c5 2. Nf3 d6 3. d4 cxd4 4. Nxd4 Nf6 5. Nc3 a6 6. Be3 e5 7. Nb3 Be6 8. f3 Be7 9. Qd2 O-O 10. O-O-O Nbd7 1-0

        [Event "Club Championship"]
        [Site "Oslo"]
        [Date "2021.05.10"]
        [Round "2"]
        [White "Roe, Jane"]
        [Black "Carlsen, Magnus"]
        [Result "0-1"]
        [WhiteElo "2300"]
        [BlackElo "2860"]

        1. d4 Nf6 2. c4 e6 3. Nc3 Bb4 4. e3 O-O 5. Bd3 d5 6. Nf3 c5 7. O-O Nc6 8. a3 Bxc3 9. bxc3 dxc4 10. Bxc4 Qc7 0-1

        [Event "Blitz"]
        [Site "Online"]
        [Date "2022.01.02"]
        [White "Doe, John"]
        [Black "Roe, Jane"]
        [Result "1/2-1/2"]

        1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 Nf6 5. O-O Be7 6. Re1 b5 7. Bb3 d6 8. c3 O-O 1/2-1/2
        """;

    @TempDir
    Path dir;
    Path file;
    Database db;
    private int nextId = 100;

    @BeforeEach
    void openDatabase() throws IOException {
        file = dir.resolve("games.pgn");
        String famous = Files.readString(Path.of("examples/famous-games.pgn"), StandardCharsets.UTF_8);
        Files.writeString(file, famous.strip() + "\n\n" + EXTRA, StandardCharsets.UTF_8);
        db = Database.open(file, Database.Options.DEFAULT.withAutoSave(true));
    }

    @AfterEach
    void closeDatabase() {
        if (db != null) db.close();
    }

    /** Sends raw lines through one server session; returns every output line parsed. */
    List<JsonElement> session(String... lines) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        String input = String.join("\n", lines) + "\n";
        McpServer.serve(db, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out);
        List<JsonElement> msgs = new ArrayList<>();
        for (String l : bytes.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!l.isBlank()) msgs.add(JsonParser.parseString(l)); // must all be valid JSON
        }
        return msgs;
    }

    /** One request; returns its response object. */
    JsonObject request(String method, JsonObject params) {
        int id = nextId++;
        JsonObject req = Json.obj("jsonrpc", "2.0", "id", id, "method", method, "params", params);
        List<JsonElement> out = session(Json.write(req));
        assertEquals(1, out.size(), "exactly one response expected: " + out);
        JsonObject r = out.get(0).getAsJsonObject();
        assertEquals(id, r.get("id").getAsInt());
        return r;
    }

    /** Calls a tool (arguments as JSON text) and returns the CallToolResult. */
    JsonObject call(String tool, String argsJson) {
        JsonObject r = request("tools/call", Json.obj("name", tool, "arguments", JsonParser.parseString(argsJson)));
        assertTrue(r.has("result"), () -> "expected a result: " + r);
        return r.getAsJsonObject("result");
    }

    /** Calls a tool that must succeed. */
    JsonObject ok(String tool, String argsJson) {
        JsonObject res = call(tool, argsJson);
        assertFalse(isError(res), () -> tool + " failed: " + text(res));
        return res;
    }

    /** Calls a tool that must fail; returns its message. */
    String fails(String tool, String argsJson) {
        JsonObject res = call(tool, argsJson);
        assertTrue(isError(res), () -> tool + " should have failed: " + text(res));
        return text(res);
    }

    static boolean isError(JsonObject res) {
        return res.has("isError") && res.get("isError").getAsBoolean();
    }

    static String text(JsonObject res) {
        JsonArray content = res.getAsJsonArray("content");
        return content.get(0).getAsJsonObject().get("text").getAsString();
    }

    static JsonObject structured(JsonObject res) {
        return res.getAsJsonObject("structuredContent");
    }

    String fileText() throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** Id of the single game matching a query. */
    int idOf(String query) {
        JsonObject s = structured(ok("search_games", Json.write(Json.obj("query", query))));
        assertEquals(1, s.get("total").getAsInt(), "query " + query);
        return s.getAsJsonArray("games").get(0).getAsJsonObject().get("id").getAsInt();
    }
}
