# OrionDB — a chess database with a built-in MCP server

OrionDB is a chess database engine for personal collections: a few hundred to
a few tens of thousands of games, with the search depth of a professional
database and a **built-in [MCP](https://modelcontextprotocol.io) server** so AI
agents (Claude Code, Claude Desktop, or any MCP client) can query and edit your
games directly.

**Your database file is plain PGN.** Every chess program can open it; OrionDB
adds validation, indexing, and search on top. No proprietary format, no lock-in,
nothing to migrate.

```
┌─────────────────────────────────────────────────────────┐
│                      oriondb.jar                        │
│                                                         │
│  CLI (humans)                    MCP server (agents)    │
│  search / explorer / player      13 tools over stdio    │
│        └──────────────┬──────────────┘                  │
│                  Search engine                          │
│   metadata · positions · material · tactics · text      │
│                       │                                 │
│               In-memory database                        │
│   Zobrist position index · ECO book · player stats      │
│                       │                                 │
│              Legal-move chess core                      │
│   perft-validated movegen · SAN · FEN · lossless PGN    │
└─────────────────────────────────────────────────────────┘
                          │
                    your-games.pgn
```

## Highlights

- **Correct chess.** Full legal move generation (pins, checks, castling
  legality, en passant), validated by perft against reference positions.
  Every imported move is replayed and normalized; illegal games are flagged,
  never silently corrupted.
- **Lossless PGN.** Variations, comments, NAGs, `%` escape lines, glued move
  numbers, `0-0` castling, Latin-1 legacy files, FEN set-up games — read,
  preserved, and written back in clean export format.
- **Transposition-aware position search.** Zobrist hashing means "find games
  reaching this position" works regardless of move order.
- **Opening explorer.** For any position: every continuation with game counts,
  W/D/L, score for the side to move, average Elo of the movers, last-played
  date, and sample games — like the big commercial databases.
- **ECO auto-classification.** The lichess opening book (3,800+ named lines,
  CC0) is embedded. Games missing ECO/Opening tags get them filled in on
  import, transposition-aware.
- **Tactics search.** Forks, pins, skewers, discovered/double checks,
  promotions, underpromotions, en passant captures, checkmates — detected on
  import, searchable instantly, listed per game.
- **Endgame search.** By material signature ("KRP vs KR") or piece count
  ("positions with ≤ 6 pieces").
- **Player intelligence.** Dossiers (W/D/L by color, performance rating,
  repertoire with scores, best wins) and head-to-head reports.
- **Duplicate detection**, comment full-text search, pawn-structure filters
  (IQP, passed pawns, ...), date/Elo/event/result filters, sorting, pagination.
- **Zero-infrastructure.** One 500 KB jar, one PGN file. Atomic saves.
  12,500 real master games import in ~7 s and reopen in ~6 s on a laptop;
  a 3,000-game personal collection feels instant.

## Quick start

Requires Java 17+ (build with Maven).

```bash
mvn -q package
```

Everything runs through the launcher (or `java -jar target/oriondb.jar ...`):

```bash
# Create a database by importing PGN into it
./bin/oriondb import mygames.pgn some-collection.pgn

# Overview
./bin/oriondb stats mygames.pgn

# Search
./bin/oriondb search mygames.pgn --player Carlsen --eco B90 --result white --limit 10
./bin/oriondb search mygames.pgn --material "KRP vs KR" --max-pieces 6
./bin/oriondb search mygames.pgn --motif underpromotion
./bin/oriondb search mygames.pgn --opening-line "1. e4 c5 2. Nf3 d6" --min-elo 2700

# Opening explorer from any position
./bin/oriondb explorer mygames.pgn "1. e4 c5 2. Nf3 d6 3. d4 cxd4 4. Nxd4 Nf6 5. Nc3 a6"

# One game with its tactical moments
./bin/oriondb game mygames.pgn 17

# Players
./bin/oriondb player mygames.pgn "Kasparov"
./bin/oriondb h2h mygames.pgn "Kasparov" "Karpov"

# Maintenance
./bin/oriondb dedupe mygames.pgn
./bin/oriondb export mygames.pgn --ids 1,5,9 -o best.pgn
```

## The MCP server (for AI agents)

The MCP server is built into the same jar — not a wrapper, not a separate
process to manage. It speaks MCP over stdio:

```bash
./bin/oriondb mcp mygames.pgn
```

### Claude Code

This repository ships a [.mcp.json](.mcp.json) pointing at the sample
database; edit the path to your own PGN. Or register it anywhere with:

```bash
claude mcp add oriondb -- /path/to/orion-chess-db/bin/oriondb mcp /path/to/mygames.pgn
```

### Claude Desktop

```json
{
  "mcpServers": {
    "oriondb": {
      "command": "/path/to/orion-chess-db/bin/oriondb",
      "args": ["mcp", "/path/to/mygames.pgn"]
    }
  }
}
```

### Tools

| Tool | What it does |
|---|---|
| `db_stats` | Game count, players, date range, top openings |
| `search_games` | Every filter below, AND-combined, with sort + pagination |
| `get_game` | Full game: tags, moves, PGN, final FEN, tactical moments |
| `opening_explorer` | Move statistics tree for any position |
| `position_search` | Games reaching a FEN position (+ where and what came next) |
| `player_report` | Dossier: W/D/L, performance, repertoire, best wins |
| `head_to_head` | Two players' lifetime score, split by color |
| `import_pgn` | Import text or a file; validates, dedupes, classifies |
| `add_game` | Add a single game, returns its id |
| `delete_game` | Remove a game |
| `set_tag` | Edit or remove a PGN tag |
| `export_pgn` | Export by ids or everything, to text or file |
| `find_duplicates` | Groups of games with identical moves |

Mutations save to disk automatically (atomic temp-file + rename).

`search_games` filters: `white`, `black`, `player`, `event`, `site`, `eco`
(exact / prefix / range), `opening`, `result`, `date_from`, `date_to`,
`min_elo`, `max_elo`, `elo_mode` (either/both/average), `min_ply`, `max_ply`,
`opening_line`, `contains_fen`, `material`, `max_pieces`, `motif`,
`pawn_feature`, `comment_contains`, `annotated`, `sort_by`, `descending`,
`limit`, `offset`.

## Library use

```java
try (Database db = Database.open(Path.of("mygames.pgn"))) {
    // Fluent search
    List<Game> games = db.search()
        .player("Carlsen").eco("B90-B99")
        .minElo(2700, Search.EloMode.BOTH)
        .sortBy(Search.Sort.DATE, true).limit(20)
        .find();

    // Opening explorer
    OpeningExplorer.Result tree = db.explorer().explore("1. e4 c5 2. Nf3");
    // Player dossier
    PlayerStats.Report report = db.players().report("Kasparov");
    // Tactics of one game
    List<Motifs.Moment> moments = Motifs.moments(games.get(0));
}
```

See [examples/ApiExample.java](examples/ApiExample.java) for a complete
program, and [examples/famous-games.pgn](examples/famous-games.pgn) for a tiny
sample database.

## Design notes

- **PGN is the storage format.** At this scale (thousands of games, not
  millions) everything fits comfortably in memory: opening a database
  re-validates and re-indexes it (parallelized across cores), which takes a
  couple of seconds and guarantees indexes are never stale. Saves are atomic.
- **The chess core is the foundation.** SAN disambiguation legally requires
  pin-aware move generation (`Re2` when one rook is pinned), so the engine
  implements full legality and is perft-validated. Zobrist hashes use the
  polyglot en-passant convention so transpositions that differ only in an
  irrelevant e.p. square identify.
- **Variations are first-class.** The move tree preserves RAVs, comments, and
  NAGs through import/export round trips. Mainline positions are indexed;
  variations are kept and searchable by text.
- **Dependencies:** Gson (for the MCP JSON layer). That's it.

## Project layout

```
src/main/java/com/oriondb/
  chess/      Board, legal movegen, SAN, FEN, Zobrist
  model/      Game, MoveNode (move tree), replay helpers
  pgn/        PgnReader (tolerant tokenizer), PgnWriter
  eco/        EcoClassifier + embedded openings book (CC0)
  analysis/   Material signatures, pawn features, tactical motifs
  db/         Database (PGN persistence, indexes), GameData
  search/     Search, OpeningExplorer, PlayerStats, Duplicates
  mcp/        McpServer (stdio JSON-RPC), DbTools (13 tools)
  cli/        Main
```

## Acknowledgements

- Opening classifications from
  [lichess-org/chess-openings](https://github.com/lichess-org/chess-openings)
  (CC0), embedded in `src/main/resources/com/oriondb/eco/openings.tsv`.

## License

MIT — see [LICENSE](LICENSE).
