package com.oriondb.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oriondb.db.Database;
import com.oriondb.engine.EngineConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CliTest {
    static final Path FAMOUS = Path.of("examples/famous-games.pgn");

    /** Ids after setup: 1 Morphy, 2 Anderssen, 3 Fischer (famous-games.pgn), then 4..6 below. */
    static final String EXTRA = """
        [Event "Club"]
        [Site "Here"]
        [Date "2020.03.01"]
        [Round "1"]
        [White "Carlsen, Magnus"]
        [Black "Doe, John"]
        [Result "1-0"]
        [WhiteElo "2850"]
        [BlackElo "2100"]

        1. e4 c5 2. Nf3 d6 3. d4 cxd4 4. Nxd4 Nf6 5. Nc3 a6 {Najdorf} 6. Be3 e5 1-0

        [Event "Club"]
        [Date "2021.05.01"]
        [White "Doe, John"]
        [Black "Carlsen, Magnus"]
        [Result "0-1"]
        [WhiteElo "2150"]
        [BlackElo "2860"]

        1. Nf3 Nf6 2. d4 d6 3. e4 c5 (3... e5) 4. Nc3 cxd4 5. Nxd4 a6 0-1

        [Event "Legal"]
        [White "Legal"]
        [Black "Saint Brie"]
        [Result "1-0"]
        [Date "1750.??.??"]

        1. e4 e5 2. Nf3 d6 3. Bc4 Bg4 4. Nc3 g6 5. Nxe5 Bxd1 6. Bxf7+ Ke7 7. Nd5# 1-0
        """;

    static final String MATE_IN_ONE = "6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1";

    @TempDir
    Path dir;
    Path db;
    String dbs;

    record Result(int code, String out, String err) {
        Result ok() {
            assertEquals(0, code, () -> "exit " + code + "\nstdout:\n" + out + "\nstderr:\n" + err);
            return this;
        }
    }

    static Result run(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        int code = Main.run(args, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Result(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void setUp() throws Exception {
        db = dir.resolve("db.pgn");
        dbs = db.toString();
        Path extra = dir.resolve("extra.pgn");
        Files.writeString(extra, EXTRA);
        Path famous = dir.resolve("famous.pgn");
        Files.copy(FAMOUS, famous);
        Result r = run("import", dbs, famous.toString(), extra.toString()).ok();
        assertTrue(r.out.contains("famous.pgn: read 3, added 3, duplicates 0, invalid 0"), r.out);
        assertTrue(r.out.contains("extra.pgn: read 3, added 3"), r.out);
        assertTrue(r.out.contains("created"), r.out);
    }

    // ------------------------------------------------------------ import / stats

    @Test
    void importSkipsDuplicatesAndReportsInvalid() throws Exception {
        Path bad = dir.resolve("bad.pgn");
        Files.writeString(bad, EXTRA + "\n[White \"X\"]\n[Black \"Y\"]\n[Result \"*\"]\n\n1. e4 e5 2. Ke3 *\n");
        Result r = run("import", dbs, bad.toString(), "--label", "Club").ok();
        assertTrue(r.out.contains("read 4, added 0, duplicates 3, invalid 1"), r.out);
        assertTrue(r.out.contains("rejected game 4"), r.out);
        assertTrue(r.out.contains("Ke3"), r.out);

        Result atomic = run("import", dbs, bad.toString(), "--keep-duplicates", "--atomic");
        assertEquals(1, atomic.code);
        assertTrue(atomic.err.contains("nothing was imported"), atomic.err);
        assertTrue(run("stats", dbs).ok().out.contains("Games:       6"));

        run("import", dbs, bad.toString(), "--keep-duplicates", "--label", "copy").ok();
        Result stats = run("stats", dbs).ok();
        assertTrue(stats.out.contains("Games:       9"), stats.out);
        assertTrue(stats.out.contains("copy"), stats.out);
    }

    @Test
    void stats() {
        Result r = run("stats", dbs).ok();
        assertTrue(r.out.contains("Games:       6"), r.out);
        assertTrue(r.out.contains("Years:       1750–2021"), r.out);
        assertTrue(r.out.contains("Carlsen, Magnus"), r.out);
        JsonObject json = JsonParser.parseString(run("stats", dbs, "--format", "json").ok().out).getAsJsonObject();
        assertEquals(6, json.get("games").getAsInt());
        assertEquals(0, json.get("invalid").getAsInt());
    }

    // ------------------------------------------------------------ search

    @Test
    void searchTable() {
        Result r = run("search", dbs, "player:fischer").ok();
        assertTrue(r.out.contains("Fischer, Robert James (2785)"), r.out);
        assertTrue(r.out.contains("Spassky, Boris V (2660)"), r.out);
        assertTrue(r.out.contains("1972.07.23"), r.out);
        assertTrue(r.out.contains("1 game"), r.out);
        assertFalse(r.out.contains("Morphy"), r.out);

        Result all = run("search", dbs, "--limit", "2").ok();
        assertTrue(all.out.contains("showing 1–2 of 6 games"), all.out);
    }

    @Test
    void searchIdsAndQueryLanguage() {
        assertEquals(List.of("1", "2"), lines(run("search", dbs, "year:1850..1860", "--format", "ids").ok().out));
        assertEquals(List.of("5", "4"), lines(run("search", dbs, "player:carlsen", "sort:-date", "--format=ids").ok().out));
        assertEquals(List.of("4"), lines(run("search", dbs, "player:carlsen", "as:white", "elo:2800", "--format", "ids").ok().out));
        assertEquals(List.of("3"), lines(run("search", dbs, "eco:D50-D69", "--format", "ids").ok().out));
        assertEquals(List.of("1", "2", "6"), lines(run("search", dbs, "motif:checkmate", "--format", "ids").ok().out));
        assertTrue(run("search", dbs, "nobody-here").ok().out.contains("no games match"));
    }

    @Test
    void searchPositionShowsMatchMove() {
        // Najdorf position, reached by both Carlsen games in different move orders
        String fen = "rnbqkb1r/1p2pppp/p2p1n2/8/3NP3/2N5/PPP2PPP/R1BQKB1R w KQkq - 0 6";
        Result r = run("search", dbs, "fen:\"" + fen + "\"").ok();
        assertTrue(r.out.contains("match"), r.out);
        assertTrue(r.out.contains("5... a6"), r.out);
        assertTrue(r.out.contains("2 games"), r.out);

        JsonObject json = JsonParser.parseString(run("search", dbs, "fen:\"" + fen + "\"", "--format", "json").ok().out).getAsJsonObject();
        assertEquals(2, json.get("total").getAsInt());
        assertEquals(10, json.getAsJsonArray("games").get(0).getAsJsonObject().get("matchPly").getAsInt());
    }

    @Test
    void searchPgnFormat() {
        Result r = run("search", dbs, "white:legal", "--format", "pgn").ok();
        assertTrue(r.out.contains("[White \"Legal\"]"), r.out);
        assertTrue(r.out.contains("7. Nd5#"), r.out);
    }

    // ------------------------------------------------------------ game / board

    @Test
    void game() {
        Result r = run("game", dbs, "1").ok();
        assertTrue(r.out.contains("Game #1"), r.out);
        assertTrue(r.out.contains("Morphy, Paul"), r.out);
        assertTrue(r.out.contains("17. Rd8# 1-0"), r.out);
        assertTrue(r.out.contains("Motifs:"), r.out);
        assertTrue(r.out.contains("checkmate"), r.out);
        assertTrue(r.out.contains("has comments"), r.out);

        Result ply = run("game", dbs, "1", "--ply", "2").ok();
        assertTrue(ply.out.contains("After 1... e5"), ply.out);
        assertTrue(ply.out.contains("FEN: rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"), ply.out);

        Result pgn = run("game", dbs, "1", "--pgn").ok();
        assertTrue(pgn.out.contains("{A brilliant"), pgn.out);
        assertTrue(pgn.out.contains("[OrionId \"1\"]"), pgn.out);

        assertEquals(1, run("game", dbs, "99").code);
        assertEquals(2, run("game", dbs, "1", "--ply", "500").code);
    }

    @Test
    void board() {
        Result r = run("board", "1. e4 c5").ok();
        assertTrue(r.out.contains("Sicilian Defense"), r.out);
        assertTrue(r.out.contains("FEN:     rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"), r.out);
        assertTrue(r.out.contains("To move: White"), r.out);

        Result mate = run("board", MATE_IN_ONE, "Rd8").ok();
        assertTrue(mate.out.contains("checkmate"), mate.out);
        assertTrue(mate.out.contains("Legal:   none"), mate.out);

        Result split = run("board", "8/8/4k3/8/3PK3/8/8/7r", "w", "-", "-", "0", "60", "--flip").ok();
        assertTrue(split.out.contains("h g f e d c b a"), split.out);

        Result bad = run("board", "1. e4 e4");
        assertEquals(2, bad.code);
        assertTrue(bad.err.contains("e4"), bad.err);
    }

    // ------------------------------------------------------------ reports

    @Test
    void explore() {
        Result r = run("explore", dbs, "e4").ok();
        assertTrue(r.out.contains("Games:    4"), r.out);
        assertTrue(r.out.contains("e5"), r.out);
        assertTrue(r.out.contains("c5"), r.out);
        assertTrue(r.out.contains("King's Pawn"), r.out);

        Result filtered = run("explore", dbs, "e4", "--filter", "year:<=1900").ok();
        assertTrue(filtered.out.contains("Games:    3"), filtered.out);

        JsonObject json = JsonParser.parseString(run("explore", dbs, "--format", "json").ok().out).getAsJsonObject();
        assertEquals(6, json.get("games").getAsInt());

        assertEquals(2, run("explore", dbs, "e5").code); // illegal for White
    }

    @Test
    void playerAndHeadToHead() {
        Result p = run("player", dbs, "carlsen").ok();
        assertTrue(p.out.contains("Carlsen, Magnus ×2"), p.out);
        assertTrue(p.out.contains("+2 =0 -0"), p.out);
        assertTrue(p.out.contains("Best wins"), p.out);
        assertEquals(1, run("player", dbs, "nobody").code);

        Result h = run("h2h", dbs, "carlsen", "doe").ok();
        assertTrue(h.out.contains("Overall:   2 games  +2 =0 -0  100.0%"), h.out);
    }

    // ------------------------------------------------------------ labels / tags

    @Test
    void labelsRoundTrip() {
        assertTrue(run("labels", dbs).ok().out.contains("no labels"));
        run("label", dbs, "--ids", "1,2", "--add", "Classic,prep").ok();
        assertTrue(run("label", dbs, "--query", "player:carlsen", "--add", "prep").ok().out.contains("changed labels on 2 of 2"));

        Result labels = run("labels", dbs, "--format", "tsv").ok();
        assertTrue(labels.out.contains("classic\t2"), labels.out);
        assertTrue(labels.out.contains("prep\t4"), labels.out);
        assertEquals(List.of("1", "2", "4", "5"), lines(run("search", dbs, "label:prep", "--format", "ids").ok().out));

        run("label-rename", dbs, "prep", "repertoire").ok();
        run("label-delete", dbs, "classic").ok();
        run("label", dbs, "--ids", "4", "--remove", "repertoire").ok();
        run("label", dbs, "--ids", "5", "--set", "a,b").ok();
        Result after = run("labels", dbs, "--format", "tsv").ok();
        assertEquals(List.of("label\tgames", "a\t1", "b\t1", "repertoire\t2"), lines(after.out));
        assertTrue(run("game", dbs, "5", "--pgn").ok().out.contains("[OrionLabels \"a,b\"]"));

        assertEquals(2, run("label", dbs, "--add", "x").code);                    // no selection
        assertEquals(2, run("label", dbs, "--ids", "1").code);                    // nothing to do
        assertEquals(1, run("label", dbs, "--ids", "77", "--add", "x").code);     // unknown id
    }

    @Test
    void tagEdit() {
        run("tag", dbs, "3", "Annotator", "Robert", "Byrne").ok();
        assertTrue(run("game", dbs, "3", "--pgn").ok().out.contains("[Annotator \"Robert Byrne\"]"));
        assertEquals(List.of("3"), lines(run("search", dbs, "tag:Annotator=*", "--format", "ids").ok().out));
        run("tag", dbs, "3", "Annotator", "--remove").ok();
        assertFalse(run("game", dbs, "3", "--pgn").ok().out.contains("Annotator"));
        assertEquals(1, run("tag", dbs, "3", "OrionId", "7").code);
    }

    @Test
    void delete() {
        Result r = run("delete", dbs, "1,6").ok();
        assertTrue(r.out.contains("deleted 2 games; 4 left"), r.out);
        assertEquals(List.of("2", "3", "4", "5"), lines(run("search", dbs, "--format", "ids").ok().out));
        assertEquals(1, run("delete", dbs, "2,42").code);
        assertEquals(4, lines(run("search", dbs, "--format", "ids").ok().out).size());
    }

    // ------------------------------------------------------------ export / dedupe / check

    @Test
    void export() throws Exception {
        Path out = dir.resolve("out.pgn");
        Result r = run("export", dbs, "player:fischer", "-o", out.toString()).ok();
        assertTrue(r.err.contains("exported 1 game"), r.err);
        String text = Files.readString(out);
        assertTrue(text.contains("Fischer, Robert James"), text);
        assertFalse(text.contains("Morphy"), text);

        Result stdout = run("export", dbs, "white:morphy", "--no-comments", "-o", "-").ok();
        assertTrue(stdout.out.contains("Rd8#"), stdout.out);
        assertFalse(stdout.out.contains("{"), stdout.out);

        Result noVar = run("export", dbs, "black:carlsen", "--no-variations", "--output=-").ok();
        assertFalse(noVar.out.contains("(3... e5)"), noVar.out);
        assertTrue(run("export", dbs, "black:carlsen", "-o", "-").ok().out.contains("(3... e5)"));

        Path all = dir.resolve("all.pgn");
        run("export", dbs, "-o", all.toString()).ok();
        Result reimport = run("import", dir.resolve("copy.pgn").toString(), all.toString()).ok();
        assertTrue(reimport.out.contains("read 6, added 6"), reimport.out);

        assertEquals(2, run("export", dbs, "-o", dbs).code);
        assertEquals(2, run("export", dbs).code);
    }

    @Test
    void dedupe() throws Exception {
        Path famous = dir.resolve("famous.pgn");
        run("import", dbs, famous.toString(), "--keep-duplicates").ok();
        Result dry = run("dedupe", dbs).ok();
        assertTrue(dry.out.contains("3 groups, 3 redundant copies"), dry.out);
        assertTrue(dry.out.contains("dry run"), dry.out);
        assertEquals(9, lines(run("search", dbs, "--format", "ids").ok().out).size());

        Result apply = run("dedupe", dbs, "--apply").ok();
        assertTrue(apply.out.contains("deleted 3; 6 games left"), apply.out);
        assertTrue(run("dedupe", dbs).ok().out.contains("no duplicates"));
    }

    @Test
    void checkListsInvalidGames() throws Exception {
        assertTrue(run("check", dbs).ok().out.contains("all 6 games parse"));
        Files.writeString(db, "\n[Event \"Broken\"]\n[White \"X\"]\n[Black \"Y\"]\n[Result \"*\"]\n\n1. e4 e5 2. Ke3 *\n",
            StandardOpenOption.APPEND);
        Result r = run("check", dbs);
        assertEquals(1, r.code, r.out + r.err);
        assertTrue(r.out.contains("Ke3"), r.out);
        assertTrue(r.err.contains("1 of 7 games could not be parsed"), r.err);
        int line = (int) Files.readAllLines(db).indexOf("[Event \"Broken\"]") + 1;
        assertTrue(r.out.matches("(?s).*\\b7\\s+" + line + "\\s+X\\s+Y.*"), r.out);

        Result stats = run("stats", dbs).ok();
        assertTrue(stats.out.contains("1 invalid"), stats.out);
        Result search = run("search", dbs).ok();
        assertTrue(search.err.contains("could not be parsed"), search.err);

        run("delete", dbs, "7").ok();
        assertEquals(0, run("check", dbs).code);
    }

    @Test
    void reindex() {
        Result r = run("reindex", dbs).ok();
        assertTrue(r.out.contains("reindexed"), r.out);
        assertTrue(r.out.contains("6 games (0 invalid)"), r.out);
        assertTrue(Files.exists(dir.resolve("db.pgn.oidx")));
    }

    @Test
    void readCommandsWorkWhileAnotherWriterHoldsTheLock() {
        try (Database writer = Database.open(db)) {
            assertEquals(6, writer.size());
            assertEquals(List.of("3"), lines(run("search", dbs, "player:fischer", "--format", "ids").ok().out));
            run("stats", dbs).ok();
            Result write = run("tag", dbs, "3", "Annotator", "x");
            assertEquals(1, write.code);
            assertTrue(write.err.contains("another process"), write.err);
        }
    }

    @Test
    void mcpServesOnlyJsonOnStdout() {
        String requests = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}
            {"jsonrpc":"2.0","id":2,"method":"tools/list"}
            """;
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        int code = Main.run(new String[] {"mcp", dbs, "--read-only"},
            new java.io.ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8));
        String out = o.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, out + e);
        List<String> responses = lines(out);
        assertEquals(2, responses.size(), out);
        for (String line : responses) assertTrue(JsonParser.parseString(line).isJsonObject(), line);
        assertTrue(out.contains("\"serverInfo\""), out);
        assertTrue(e.toString(StandardCharsets.UTF_8).contains("read-only"));
    }

    // ------------------------------------------------------------ usage

    @Test
    void usageErrors() {
        assertEquals(2, run().code);
        Result unknown = run("serch", dbs);
        assertEquals(2, unknown.code);
        assertTrue(unknown.err.contains("did you mean 'search'"), unknown.err);

        Result flag = run("search", dbs, "--bogus");
        assertEquals(2, flag.code);
        assertTrue(flag.err.contains("--format <value>"), flag.err);
        assertTrue(flag.err.contains("usage: oriondb search"), flag.err);
        assertTrue(run("search", dbs, "--frmat", "ids").err.contains("did you mean --format?"));

        assertEquals(2, run("search", dbs, "badkey:x").code);
        assertEquals(2, run("search", dbs, "--format", "xml").code);
        assertEquals(2, run("search", dbs, "--limit", "ten").code);
        assertEquals(2, run("search").code);
        assertEquals(2, run("game", dbs).code);
        assertEquals(2, run("game", dbs, "abc").code);
        assertEquals(2, run("delete", dbs).code);
        assertEquals(2, run("stats", dbs, "extra").code);
        assertEquals(2, run("analyze", MATE_IN_ONE, "--depth", "3", "--time", "100").code);

        Result missing = run("stats", dir.resolve("nope.pgn").toString());
        assertEquals(1, missing.code);
        assertTrue(missing.err.contains("no such database"), missing.err);
        assertFalse(Files.exists(dir.resolve("nope.pgn")), "read commands must not create files");
    }

    @Test
    void helpAndVersion() {
        Result top = run("help").ok();
        for (String c : Main.commandNames()) assertTrue(top.out.contains("  " + c + " "), c);
        assertTrue(run("help", "search").ok().out.contains("Query terms"));
        assertTrue(run("search", "--help").ok().out.contains("usage: oriondb search"));
        assertEquals("oriondb 3.0.0", run("--version").ok().out.strip());
        assertEquals(2, run("help", "nonsense").code);
    }

    // ------------------------------------------------------------ engine

    @Test
    void analyzeMateInOne() {
        Assumptions.assumeTrue(EngineConfig.detect().isPresent(), "no UCI engine installed");
        Result r = run("analyze", MATE_IN_ONE, "--depth", "8").ok();
        assertTrue(r.out.contains("Rd8#"), r.out);
        assertTrue(r.out.contains("#1"), r.out);

        Result multi = run("analyze", "1. e4 e5", "--depth", "6", "--multipv", "2").ok();
        assertTrue(multi.out.contains("1. ") && multi.out.contains("2. "), multi.out);
        assertTrue(multi.out.contains("2. "), multi.out);
    }

    @Test
    void annotate() {
        Assumptions.assumeTrue(EngineConfig.detect().isPresent(), "no UCI engine installed");
        Result dry = run("annotate", dbs, "6", "--depth", "6", "--dry-run").ok();
        assertTrue(dry.out.contains("#6  Legal - Saint Brie"), dry.out);
        assertTrue(dry.out.contains("accuracy"), dry.out);
        assertFalse(run("game", dbs, "6", "--pgn").ok().out.contains("%eval"));

        Result real = run("annotate", dbs, "6", "--depth", "6", "--black-only").ok();
        assertTrue(real.out.contains("Black"), real.out);
        assertFalse(real.out.contains("White  accuracy"), real.out);
        String pgn = run("game", dbs, "6", "--pgn").ok().out;
        assertTrue(pgn.contains("%eval"), pgn);
        assertTrue(pgn.contains("[OrionId \"6\"]"), pgn);
    }

    static List<String> lines(String s) {
        return s.lines().filter(l -> !l.isBlank()).toList();
    }
}
