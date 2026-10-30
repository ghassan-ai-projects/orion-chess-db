package com.oriondb.query;

import com.oriondb.db.Database;
import com.oriondb.db.Snapshot;
import com.oriondb.game.Game;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class QueryTest {
    private Database db;

    private int add(String white, String black, String result, String elo, String moves) {
        Game g = Game.fromMoves(moves);
        g.tags().set("White", white);
        g.tags().set("Black", black);
        g.tags().set("Result", result);
        g.tags().set("WhiteElo", elo);
        g.tags().set("BlackElo", "2000");
        return db.add(g);
    }

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        // 1: Greek gift
        add("Attacker, A", "Victim, V", "1-0", "2500",
            "e4 e6 d4 d5 Nc3 Nf6 e5 Nfd7 Nf3 Be7 Bd3 O-O Bxh7+ Kxh7 Ng5+ Kg8 Qh5 Re8 Qh7+ Kf8 Qh8#");
        // 2: Sicilian, queen trade
        add("Sicilian, S", "Player, P", "1/2-1/2", "2300",
            "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 a6 Be3 e5 Nb3 Be6 f3 Be7 Qd2 O-O O-O-O Nbd7");
        // 3: French, quiet
        add("French, F", "Player, P", "0-1", "2400", "e4 e6 d4 d5 Nd2 c5 exd5 Qxd5 Ngf3 cxd4 Bc4 Qd6 O-O Nf6");
        // 4: pawn ending race with promotion
        add("Endgame, E", "Player, P", "1-0", "2600",
            "e4 d5 exd5 Qxd5 Nc3 Qa5 d4 Nf6 Nf3 Bf5 Bc4 e6 Bd2 c6 Qe2 Bb4 O-O-O Nbd7 a3 Bxc3 Bxc3 Qc7");
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private Set<Integer> ids(String q) {
        return Set.copyOf(Searcher.ids(db.snapshot(), QueryParser.parse(q)));
    }

    @Test
    void booleanComposition() {
        assertEquals(Set.of(1, 2, 3, 4), ids("eco:B OR opening:french"));
        assertEquals(Set.of(1, 2, 3), ids("opening:sicilian OR opening:french"));
        assertEquals(Set.of(1, 2, 3), ids("(opening:sicilian OR opening:french) OR result:1-0 -white:endgame"));
        assertEquals(Set.of(4), ids("-opening:sicilian -opening:french -white:attacker"));
        assertEquals(Set.of(1, 4), ids("NOT (result:draw OR result:0-1)"));
        assertEquals(Set.of(3), ids("black:player (result:0-1 | result:1-0) -white:endgame"));
        assertEquals(Set.of(1, 2, 3, 4), ids("motif:castling"));
        assertEquals(Set.of(2, 4), ids("move:O-O-O"));
        assertThrows(IllegalArgumentException.class, () -> QueryParser.parse("(eco:B"));
        assertThrows(IllegalArgumentException.class, () -> QueryParser.parse("eco:B)"));

        Query fluent = new Query().or(new Query().opening("sicilian"), new Query().opening("french"))
            .not(new Query().results(Set.of(com.oriondb.db.GameRecord.DRAW)));
        assertEquals(List.of(1, 3), Searcher.ids(db.snapshot(), fluent));
    }

    @Test
    void moveQueries() {
        assertEquals(Set.of(1), ids("move:Bxh7+"));
        assertEquals(Set.of(1), ids("sequence:\"Bxh7+ Kxh7 Ng5+\""));
        assertEquals(Set.of(), ids("sequence:\"Bxh7+ Ng5+\""));
        assertEquals(Set.of(1), ids("move:Q*# moveby:white"));
        assertEquals(Set.of(1, 2, 3, 4), ids("move:Nf6"));
        assertEquals(Set.of(2, 4), ids("move:Nf6 movenum:4"));
        assertEquals(Set.of(2, 3), ids("move:exd5,O-O OR move:cxd4"));  // 4 castles long
        SearchResult r = Searcher.search(db.snapshot(), QueryParser.parse("move:Bxh7+"));
        assertEquals(13, r.matches().get(0).ply(), "reports the ply of the matching move");
        assertThrows(IllegalArgumentException.class, () -> QueryParser.parse("move:Zz9"));
    }

    @Test
    void balanceStructuresAndSorts() {
        assertEquals(Set.of(1, 4), ids("balance:<=-2"));  // 4 only in passing, mid-exchange
        assertEquals(Set.of(1), ids("balance:-2..-2"));  // after Bxh7+ Kxh7 white stays two pawns down
        assertEquals(Set.of(1, 2, 3, 4), ids("balance:0"));
        assertEquals(List.of(4, 1, 3, 2), Searcher.ids(db.snapshot(), QueryParser.parse("sort:-whiteelo")));
        assertNotNull(com.oriondb.analysis.PawnFeature.parse("advanced_passer"));
        assertNotNull(com.oriondb.analysis.PawnFeature.parse("isolated"));
    }

    @Test
    void cursorPagingIsStableAcrossInserts() {
        for (int i = 0; i < 20; i++) add("Bulk " + i, "X", "*", String.valueOf(2000 + i), "d4 d5");
        Query q = QueryParser.parse("white:bulk sort:-whiteelo limit:5");
        SearchResult page1 = Searcher.search(db.snapshot(), q);
        assertEquals(20, page1.total());
        assertNotNull(page1.nextCursor());
        // Games inserted ahead of the cursor do not shift the next page.
        add("Bulk top", "X", "*", "2900", "d4 d5");
        SearchResult page2 = Searcher.search(db.snapshot(), q.after(page1.nextCursor()));
        assertEquals("Bulk 14", page2.matches().get(0).record().white());
        List<String> seen = new ArrayList<>();
        String cursor = null;
        Query all = QueryParser.parse("white:bulk sort:-whiteelo limit:7");
        do {
            SearchResult p = Searcher.search(db.snapshot(), all.after(cursor));
            p.matches().forEach(m -> seen.add(m.record().white()));
            cursor = p.nextCursor();
        } while (cursor != null);
        assertEquals(21, seen.size());
        assertEquals(21, Set.copyOf(seen).size());
        assertThrows(IllegalArgumentException.class,
            () -> Searcher.search(db.snapshot(), QueryParser.parse("sort:date").after(page1.nextCursor())));
    }

    @Test
    void initialsNeedASurnameMatch() {
        Names.Matcher nakamura = Names.Matcher.person("nakamura");
        assertFalse(nakamura.matches("Lokesh, N."));
        assertTrue(nakamura.matches("Nakamura,Hi"));
        assertTrue(Names.Matcher.person("hikaru nakamura").matches("Nakamura,Hi"));
        assertTrue(Names.Matcher.person("magnus").matches("Carlsen, Magnus"));
        assertFalse(Names.Matcher.person("magnus").matches("Carlsen,M"));
    }

    @Test
    void predicateMatchesSearch() {
        Snapshot s = db.snapshot();
        Query q = QueryParser.parse("move:O-O (opening:sicilian OR opening:french)");
        var p = Searcher.predicate(s, q);
        assertEquals(Set.copyOf(Searcher.ids(s, q)),
            Set.copyOf(s.records().stream().filter(p).map(r -> r.id()).toList()));
    }
}
