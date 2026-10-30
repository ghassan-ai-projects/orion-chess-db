package com.oriondb.mcp;

import com.google.gson.JsonObject;
import com.oriondb.engine.EngineConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Engine tools; skipped when no UCI engine is installed. */
class McpEngineTest extends McpHarness {

    @BeforeEach
    void requireEngine() {
        Assumptions.assumeTrue(EngineConfig.detect().isPresent(), "no UCI engine installed");
    }

    @Test
    void analyzeMateInOne() {
        JsonObject res = ok("analyze_position", "{\"fen\": \"6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1\", \"depth\": 10, \"multipv\": 2}");
        JsonObject best = structured(res).getAsJsonArray("lines").get(0).getAsJsonObject();
        assertEquals("Rd8#", best.get("move").getAsString(), text(res));
        assertEquals("#1", best.get("score").getAsString());
        assertTrue(text(res).contains("1. Rd8#"), text(res));
    }

    @Test
    void analyzeFromGameAndTerminalPositions() {
        int id = idOf("white:morphy");
        JsonObject res = ok("analyze_position", "{\"game_id\": " + id + ", \"ply\": 31, \"movetime_ms\": 300}");
        assertTrue(text(res).contains("Rd8#"), "mate in 1 after 16... Nxb8: " + text(res));
        res = ok("analyze_position", "{\"game_id\": " + id + "}");
        assertTrue(text(res).contains("checkmate"), text(res));
    }

    @Test
    void annotatePreviewAndSave() throws Exception {
        int id = idOf("white:morphy");
        String before = fileText();
        JsonObject res = ok("annotate_game", "{\"id\": " + id + ", \"depth\": 6, \"movetime_ms\": 100}");
        JsonObject s = structured(res);
        assertFalse(s.get("saved").getAsBoolean());
        assertTrue(s.getAsJsonObject("white").get("moves").getAsInt() > 10);
        assertTrue(s.get("pgn").getAsString().contains("[%eval"), "preview includes the annotated PGN");
        assertEquals(before, fileText(), "preview does not write");

        ok("annotate_game", "{\"id\": " + id + ", \"depth\": 6, \"movetime_ms\": 100, \"save\": true, \"black\": false}");
        assertTrue(fileText().contains("[%eval"));
    }
}
