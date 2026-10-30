package com.oriondb.mcp;

import com.google.gson.JsonObject;
import com.oriondb.db.Database;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The mutating tools, batch atomicity, and persistence. */
class McpEditTest extends McpHarness {

    @Test
    void editGameVariationCommentNagRoundTrip() throws Exception {
        int id = idOf("white:morphy");
        JsonObject res = ok("edit_game", """
            {"id": %d, "operations": [
              {"op": "add_moves", "at": "e4 e5 Nf3", "moves": "Nc6 Bb5"},
              {"op": "set_comment", "at": "e4 e5 Nf3 Nc6", "text": "The usual move"},
              {"op": "add_nag", "at": "e4 e5 Nf3 d6", "nag": "?!"},
              {"op": "set_eval", "ply": 1, "eval": "0.30"},
              {"op": "set_comment", "at": "", "text": "Played at the opera."}
            ]}""".formatted(id));
        String pgn = structured(res).get("pgn").getAsString().replaceAll("\\s+", " ");
        assertTrue(pgn.contains("(2... Nc6 {The usual move} 3. Bb5)"), pgn);
        assertTrue(pgn.contains("d6 $6"), pgn);
        assertTrue(pgn.contains("[%eval 0.30]"), pgn);
        assertTrue(pgn.startsWith("[") && pgn.contains("{Played at the opera.} 1. e4"), pgn);
        assertTrue(structured(res).get("mainline").getAsString().startsWith("1. e4 e5 2. Nf3 d6"), "mainline unchanged");

        // make the variation the main line, then remove it again
        res = ok("edit_game", "{\"id\": " + id + ", \"operations\": [{\"op\": \"make_main\", \"at\": \"e4 e5 Nf3 Nc6\"}]}");
        assertTrue(structured(res).get("mainline").getAsString().startsWith("1. e4 e5 2. Nf3 Nc6 3. Bb5"));
        ok("edit_game", "{\"id\": " + id + ", \"operations\": [{\"op\": \"delete\", \"at\": \"e4 e5 Nf3 Nc6\"}]}");

        // On disk, and survives reopening
        db.close();
        db = Database.open(file, Database.Options.DEFAULT.withAutoSave(true));
        String stored = db.pgn(id);
        assertTrue(stored.contains("$6") && stored.contains("[%eval 0.30]") && stored.contains("Played at the opera."), stored);
        assertFalse(stored.contains("Bb5"), stored);
        assertTrue(db.game(id).mainlineSan().contains("17. Rd8#"));
    }

    @Test
    void editGameFailureChangesNothing() throws Exception {
        int id = idOf("white:morphy");
        String before = fileText();
        String msg = fails("edit_game", """
            {"id": %d, "operations": [
              {"op": "set_comment", "at": "e4", "text": "kept?"},
              {"op": "add_moves", "at": "e4 e5 Nf3", "moves": "Ke7 Qxf7"}
            ]}""".formatted(id));
        assertTrue(msg.contains("operation 2"), msg);
        assertTrue(msg.contains("Qxf7"), msg);
        assertTrue(fails("edit_game", "{\"id\": " + id + ", \"operations\": [{\"op\": \"promote\", \"at\": \"d4\"}]}")
            .contains("no node at 'd4'"));
        assertEquals(before, fileText());
        assertFalse(db.pgn(id).contains("kept?"));
    }

    @Test
    void addGameAndImportArePersisted() throws Exception {
        JsonObject res = ok("add_game", """
            {"moves": "1. f3 e5 2. g4 Qh4#", "tags": {"White": "Fool, Ann", "Black": "Smart, Bob", "Event": "Lesson"},
             "labels": ["Traps"]}""");
        int id = structured(res).get("id").getAsInt();
        assertTrue(fileText().contains("[White \"Fool, Ann\"]"));
        assertTrue(fileText().contains("[OrionLabels \"traps\"]"));
        assertEquals("0-1", db.record(id).resultText(), "result from the mate");

        res = ok("import_pgn", Json.write(Json.obj("pgn", """
            [White "A"]
            [Black "B"]
            [Result "*"]

            1. d4 d5 *

            [White "C"]
            [Black "D"]
            [Result "*"]

            1. e4 Ke7 2. Kxe8 *
            """, "labels", java.util.List.of("imported"))));
        JsonObject s = structured(res);
        assertEquals(2, s.get("read").getAsInt());
        assertEquals(1, s.get("added").getAsInt());
        assertEquals(1, s.get("invalid").getAsInt());
        assertTrue(text(res).contains("Errors:"), text(res));
        assertTrue(fileText().contains("[White \"A\"]"));

        String msg = fails("import_pgn", Json.write(Json.obj("pgn", "[White \"X\"]\n\n1. e4 Ke7 2. Kxe8 *\n", "atomic", true)));
        assertTrue(msg.contains("nothing imported"), msg);
        assertTrue(fails("add_game", "{\"pgn\": \"1. e4 e5 *\\n\\n[White \\\"x\\\"]\\n\\n1. d4 *\"}").contains("import_pgn"));
    }

    @Test
    void setTagsAndRejectedTags() throws Exception {
        int id = idOf("white:morphy");
        ok("set_tags", "{\"id\": " + id + ", \"tags\": {\"Round\": \"?\", \"Annotator\": \"Me\", \"Site\": null}}");
        assertTrue(fileText().contains("[Annotator \"Me\"]"));
        assertNull(db.record(id).tag("Site"));
        assertTrue(fails("set_tags", "{\"id\": " + id + ", \"tags\": {\"FEN\": \"8/8/8/8/8/8/8/8 w - - 0 1\"}}").contains("add_game"));
        assertTrue(fails("set_tags", "{\"id\": " + id + ", \"tags\": {\"OrionId\": \"5\"}}").contains("label_games"));
        assertTrue(fails("set_tags", "{\"ids\": [" + id + ", 9999], \"tags\": {\"Annotator\": \"Other\"}}").contains("9999"));
        assertEquals("Me", db.record(id).tag("Annotator"), "multi-id update is all or nothing");
    }

    @Test
    void labelGamesByQueryAndManageLabels() throws Exception {
        JsonObject s = structured(ok("label_games", "{\"query\": \"player:carlsen\", \"add\": [\"Magnus\", \"prep\"]}"));
        assertEquals(2, s.get("changed").getAsInt());
        JsonObject labels = structured(ok("list_labels", "{}")).getAsJsonObject("labels");
        assertEquals(2, labels.get("magnus").getAsInt());
        assertTrue(fileText().contains("[OrionLabels \"magnus,prep\"]"), "labels are on disk");

        assertEquals(2, structured(ok("search_games", "{\"labels\": [\"prep\"]}")).get("total").getAsInt());
        assertEquals(2, structured(ok("manage_label", "{\"action\": \"rename\", \"label\": \"prep\", \"to\": \"repertoire\"}")).get("changed").getAsInt());
        assertEquals(2, structured(ok("manage_label", "{\"action\": \"delete\", \"label\": \"magnus\"}")).get("changed").getAsInt());
        labels = structured(ok("list_labels", "{}")).getAsJsonObject("labels");
        assertEquals(1, labels.size());
        assertEquals(2, labels.get("repertoire").getAsInt());

        int id = idOf("white:morphy");
        ok("label_games", "{\"ids\": [" + id + "], \"set\": [\"classic\"]}");
        assertEquals(java.util.List.of("classic"), db.record(id).labels());
        assertTrue(fails("label_games", "{\"add\": [\"x\"]}").contains("ids"));
    }

    @Test
    void deleteRequiresConfirm() {
        int id = idOf("white:morphy");
        assertTrue(fails("delete_games", "{\"ids\": [" + id + "], \"confirm\": false}").contains("confirm"));
        assertEquals(6, db.size());
        ok("delete_games", "{\"ids\": [" + id + "], \"confirm\": true}");
        assertEquals(5, db.size());
        assertTrue(fails("get_game", "{\"id\": " + id + "}").contains("no game"));
    }

    @Test
    void batchIsAtomic() throws Exception {
        int id = idOf("white:morphy");
        String before = fileText();
        String msg = fails("batch", """
            {"operations": [
              {"tool": "add_game", "arguments": {"moves": "e4 e5", "tags": {"White": "Batch, Test"}}},
              {"tool": "label_games", "arguments": {"ids": [%d], "add": ["batched"]}},
              {"tool": "set_tags", "arguments": {"id": 9999, "tags": {"Round": "1"}}}
            ]}""".formatted(id));
        assertTrue(msg.contains("operation 3 (set_tags)") && msg.contains("nothing was changed"), msg);
        assertEquals(6, db.size());
        assertTrue(db.record(id).labels().isEmpty());
        assertEquals(before, fileText());

        assertTrue(fails("batch", "{\"operations\": [{\"tool\": \"search_games\", \"arguments\": {}}]}").contains("cannot be used in a batch"));
        assertTrue(fails("batch", "{\"operations\": [{\"tool\": \"add_game\", \"arguments\": {\"bogus\": 1}}]}").contains("unknown argument"));

        JsonObject res = ok("batch", """
            {"operations": [
              {"tool": "add_game", "arguments": {"moves": "e4 e5", "tags": {"White": "Batch, Test"}}},
              {"tool": "label_games", "arguments": {"ids": [%d], "add": ["batched"]}},
              {"tool": "edit_game", "arguments": {"id": %d, "operations": [{"op": "set_comment", "at": "e4", "text": "Batch comment"}]}}
            ]}""".formatted(id, id));
        assertEquals(3, structured(res).get("operations").getAsInt());
        assertEquals(7, db.size());
        String disk = fileText();
        assertTrue(disk.contains("Batch, Test") && disk.contains("batched") && disk.contains("Batch comment"), disk);
    }
}
