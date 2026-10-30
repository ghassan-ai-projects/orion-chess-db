# OrionDB — a chess database engine

OrionDB is a complete chess database engine for personal and club collections
(up to roughly 100k–200k games on a laptop). It has the search depth of a
commercial database, UCI engine analysis, a scriptable CLI, and a **built-in
[MCP](https://modelcontextprotocol.io) server**, so AI agents can query and
edit your games directly.

**Your database is a plain PGN file.** Every chess program can open it.
OrionDB adds a persistent index next to it (`mygames.pgn.oidx`, a disposable
cache), validation, search, statistics and editing. There is no proprietary
format and no lock-in.

## Highlights

- **Correct chess.** Bitboard move generation validated by perft (standard and
  Chess960), full legality, SAN/UCI/FEN/X-FEN.
- **Lossless, forgiving PGN.** Variations, comments, NAGs, `[%clk]`/`[%eval]`
  commands, set-up positions, Chess960, Latin-1 legacy files, sloppy real-world
  syntax. Games that fail to parse are kept verbatim and reported, never
  dropped.
- **Fast at scale.** 100,000 games reopen in under a second. Most queries take
  milliseconds (see [docs/architecture.md](docs/architecture.md)).
- **Search everything.** Combine any of these:
  - Players: accent- and order-insensitive, aware of name variants ("Magnus
    Carlsen" finds `Carlsen,M` and `Carlsen, Magnus` but not `Carlsen,H`); also
    color and outcome
  - Event, site, dates, results, ECO ranges, opening names, Elo (either/both/average) and game length
  - Opening line, exact position (transposition-aware, optionally inside variations), and **piece patterns** (`"Kg1 kg8 Pe5 -pd5 R*7"`)
  - Material signatures (`KRP vs KR`) and piece counts
  - Tactical motifs, pawn structures, labels, comments, annotations and arbitrary tags

  - Moves played (`move:Bxh7+`), move sequences anywhere in the game, and
    material balance reached

  Combine them with `OR`, `NOT` and parentheses, sort them, and page with
  offsets or stable cursors. The same filters are available as a fluent API
  and as a text query language.
- **Opening explorer** for any position: games, W/D/L, score, average rating,
  performance, last played and top games per move, with ECO names from the
  embedded lichess book.
- **Tactics and structures detected on import:**
  - Motifs: forks, pins, skewers, discovered and double checks, sacrifices, queen sacrifices
  - Mates: smothered and back-rank mates
  - Special moves: underpromotions and en passant
  - Pawn structures: IQP, hanging pawns, passed and connected passers, Carlsbad, Maroczy bind, Stonewall, opposite-coloured bishops
- **Player intelligence.**
  - Dossiers: results by colour and year, performance, peak and latest rating, repertoire with scores, opponents, best wins and worst losses
  - Head-to-head records
- **Game editing.** Add or promote/delete variations, comments, NAGs and
  `[%eval]` commands. Edit tags in place without reformatting the rest of the
  game.
- **Engine analysis.**
  - Any UCI engine (Stockfish auto-detected), with multi-PV analysis
  - Full-game annotation: `?!`/`?`/`??` NAGs, evaluations, best-line variations
  - Per-side accuracy and average centipawn loss (lichess formulas)
- **Labels** for organising games (`prep`, `to-review`, ...), stored in the PGN
  itself.
- **Safe writes.**
  - Atomic batches (all-or-nothing)
  - Incremental saves: only changed games are appended to a checksummed
    journal (about 9 ms per edit at 100k games), and the journal is folded
    into the PGN on close or when it grows
  - Crash-safe compaction: temp file, fsync, `.bak`, atomic rename
  - A process lock against concurrent writers
  - Saves are refused if another program changed the file

## Quick start

Requires Java 21+ and Maven.

```bash
mvn -q package                      # builds target/oriondb.jar
./bin/oriondb import mygames.pgn downloads/*.pgn --label inbox
./bin/oriondb stats mygames.pgn
```

### Search

```bash
./bin/oriondb search mygames.pgn player:carlsen as:white outcome:win eco:B90-B99 sort:-date
./bin/oriondb search mygames.pgn material:"KRP vs KR" elo:2600
./bin/oriondb search mygames.pgn pattern:"Ng5 Qh5 kg8" motif:sacrifice
./bin/oriondb search mygames.pgn fen:"r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
./bin/oriondb search mygames.pgn structure:iqp structureby:white year:2015..2024 --format pgn
./bin/oriondb help search          # the full query language
```

### Explore and analyze

```bash
./bin/oriondb explore mygames.pgn 1. e4 c5 2. Nf3 --filter "elo:2500"
./bin/oriondb player mygames.pgn "Magnus Carlsen"
./bin/oriondb h2h mygames.pgn Kasparov Karpov
./bin/oriondb game mygames.pgn 17 --ply 30
./bin/oriondb board "1. e4 e5 2. Nf3 Nc6 3. Bb5"
```

### Organise

```bash
./bin/oriondb label mygames.pgn --query "player:carlsen eco:B90-B99" --add prep
./bin/oriondb labels mygames.pgn
./bin/oriondb tag mygames.pgn 17 Annotator "Me"
./bin/oriondb dedupe mygames.pgn            # dry run; --apply to remove copies
./bin/oriondb export mygames.pgn label:prep -o prep.pgn
./bin/oriondb check mygames.pgn             # games that failed to parse
```

### Engine

Needs Stockfish on PATH, or set `ORIONDB_ENGINE=/path/to/engine`.

```bash
./bin/oriondb analyze "1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6" --depth 20 --multipv 3
./bin/oriondb annotate mygames.pgn 17 --depth 18
```

### Query language

Terms are `key:value` and are AND-combined. Use `OR`, `NOT` (or a leading
`-`) and parentheses for more, e.g.
`(eco:B90-B99 OR eco:C00-C19) -result:draw elo:2600`. Quote values that
contain spaces. Bare words search players, event, site and opening.

| Key | Examples |
|---|---|
| players | `white:` `black:` `player:carlsen` `as:white` `outcome:win` |
| metadata | `event:` `site:` `round:` `opening:najdorf` `tag:TimeControl=180+2` |
| dates | `year:2020` `date:2019.05..2021` `date:>=2015` |
| results | `result:1-0` `result:draw` `result:decisive` |
| openings | `eco:B90` `eco:B9` `eco:B90-B99` `line:"1. e4 c5 2. Nf3"` |
| ratings | `elo:2600` `elo:2500..2700` `elomode:both` |
| length | `moves:20..40` `plies:<=60` |
| positions | `fen:"<FEN>"` `scope:all` (also inside variations) `pattern:"Kg1 kg8 R*7 -pd5 .f7 w"` |
| endgames | `material:"KRP vs KR"` `material!:...` (exact colours) `pieces:<=6` |
| tactics | `motif:fork,sacrifice,castling` `motifby:black` |
| moves | `move:Bxh7+` `move:Q*#` `move:e8=Q` `sequence:"Bxh7+ Kxh7 Ng5+"` `moveby:white` `movenum:10..25` |
| material balance | `balance:>=3` `balance:-1..1` (White minus Black, in pawns, reached at some point) |
| structure | `structure:iqp,isolated,passed,advanced_passer,doubled,hanging_pawns,carlsbad` `structureby:white` |
| organisation | `label:prep` `anylabel:a,b` `nolabel:done` `id:1,5,9` |
| annotations | `annotated:yes` `comments:yes` `variations:yes` `evals:yes` `comment:"novelty"` |
| output | `sort:-date` (`date` `white` `black` `event` `elo` `whiteelo` `blackelo` `ply` `eco` `id`) `limit:20` `offset:40` `after:<cursor>` |

## The MCP server (for AI agents)

The MCP server is part of the same jar and speaks MCP over stdio:

```bash
./bin/oriondb mcp mygames.pgn              # read/write, auto-saves every change
./bin/oriondb mcp mygames.pgn --read-only
```

Register it with Claude Code:

```bash
claude mcp add oriondb -- /path/to/orion-chess-db/bin/oriondb mcp /path/to/mygames.pgn
```

Or with Claude Desktop (`claude_desktop_config.json`):

```json
{ "mcpServers": { "oriondb": {
    "command": "/path/to/orion-chess-db/bin/oriondb",
    "args": ["mcp", "/path/to/mygames.pgn"] } } }
```

The tools cover:

- Search (structured filters or the query language)
- Reading games and positions
- The opening explorer
- Player reports and head-to-head records
- Import
- Adding and editing games (variations, comments, NAGs, evals)
- Tags and labels
- Deletion and deduplication
- Export
- Engine analysis and game annotation
- An atomic `batch` tool

Every mutation is saved crash-safely, and game ids are stable across sessions.

## Embedding in an application

OrionDB is designed as the data layer under an application (desktop, server
or agent). The guarantees an application can rely on:

- **Threading.** `Database` is thread-safe. Reads go through immutable
  `Snapshot`s and never block or see half-applied changes. Writes are
  serialized; each `batch` is atomic.
- **Identity.** Game ids are stable across restarts, edits and saves (stored
  in the PGN as `OrionId`). Player identities come from `PlayerIndex`, which
  groups spelling variants; its `id` is stable for a given set of games.
- **Change events.** `db.addListener(...)` reports the added, updated and
  deleted ids of every commit (`Change`) and every save, so views and caches
  can refresh incrementally.
- **Caching.** `snapshot.derived(key, supplier)` memoizes any computed value
  per data version (OrionDB uses it for the player index), which is a cheap
  place to hang application-level caches.
- **Processes.** One writer per file (OS lock); any number of read-only
  openers. Read-only openers see journaled saves too. Saves refuse to
  overwrite external edits.
- **Durability.** `save()` is cheap (journal append plus fsync), so an app can
  save after every user action. `compact()` folds the journal into the PGN.
  This also happens on `close()` by default (`Options.withCompactOnClose`),
  and automatically once the journal passes a quarter of the PGN size.
- **Paging.** `SearchResult.nextCursor()` with `Query.after(cursor)` gives
  pages that stay consistent while games are added or removed.
- **Scope.** Network importers (Lichess, Chess.com), user accounts and UI
  belong to the application. Feed games in via `importText`, `importFile` or
  `add`.

## Library use

```java
try (Database db = Database.open(Path.of("mygames.pgn"))) {
    Snapshot snap = db.snapshot();
    SearchResult r = Searcher.search(snap, QueryParser.parse("player:carlsen eco:B90-B99 sort:-date"));
    OpeningExplorer.Result tree = OpeningExplorer.explore(snap, OpeningExplorer.positionAfter("e4 c5 Nf3"), null, 3);

    int id = r.matches().get(0).record().id();
    Game g = db.game(id);                                  // full tree: variations, comments, NAGs
    g.find("e4 c5 Nf3").play("d6").setComment("Main line");
    db.replace(id, g);                                     // atomic; id and labels kept
    db.update(b -> b.updateLabels(id, List.of("studied"), List.of()));
    db.save();                                             // crash-safe write of the PGN

    db.addListener(new DatabaseListener() {                // keep app views in sync
        @Override public void committed(Change c) { refresh(c.added(), c.updated(), c.deleted()); }
    });
    List<PlayerIndex.Player> hits = PlayerIndex.of(db.snapshot()).search("carlsen", 10);  // autocomplete
}
```

See [examples/ApiExample.java](examples/ApiExample.java) for a fuller tour.

## Development

```bash
mvn -q test                                              # unit + integration tests
mvn -q test -Dtest=PerftTest -Doriondb.slow=true         # deep perft
mvn -q test -Dtest=DatabasePerfTest -Doriondb.slow=true -Doriondb.perf.games=50000
```

Architecture and design decisions: [docs/architecture.md](docs/architecture.md).

The ECO book is the [lichess chess-openings](https://github.com/lichess-org/chess-openings)
dataset (CC0). OrionDB is MIT licensed.
