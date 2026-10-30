# OrionDB v3 architecture

OrionDB is a chess database engine for personal and club collections (up to
roughly 100k–200k games on a laptop), with search depth comparable to
commercial databases, UCI engine analysis, a CLI, and a built-in MCP server.

```
 cli/  (humans)                    mcp/  (AI agents, JSON-RPC over stdio)
   └───────────────┬──────────────────┘
                   │
 query/   Query · QueryParser (text language) · Searcher · PiecePattern
 stats/   OpeningExplorer · PlayerReport · HeadToHead · DatabaseStats · Duplicates
 engine/  UciEngine · GameAnnotator (accuracy, ACPL, ?!/?/?? NAGs, best lines)
                   │
 db/      Database · Snapshot (immutable view) · Batch (atomic changes)
          GameRecord (compact per-game summary) · Storage (lock, atomic save)
          IndexCache (.oidx sidecar) · Positions (index + overlay)
 index/   PositionIndex (sorted hash → game/ply arrays)
 analysis/ Material signatures · MotifDetector · PawnFeature   eco/ EcoBook
                   │
 pgn/     PgnSplitter (byte offsets) · PgnParser (lenient) · PgnWriter · PgnText
 game/    Game · GameNode (variations, comments, NAGs, [%cmd]) · Tags · Nag
 chess/   Position (bitboards, legal moves, Chess960) · San · Uci · Zobrist
```

Each layer depends only on the layers below it.

## Storage: PGN is the truth, the index is a cache

- The database **is** a `.pgn` file. Any chess program can read it; OrionDB
  never stores anything that exists only in its own format.
- `mygames.pgn.oidx` caches every parsed `GameRecord` (header fields, packed
  mainline, analysis). It is trusted only when the PGN's size and mtime match,
  otherwise it is rebuilt. Deleting it is always safe.
- Each game's record knows its byte span in the PGN file. Full games
  (variations, comments) are read and parsed on demand.
- Saving writes a temp file, fsyncs, keeps the previous generation as
  `mygames.pgn.bak` (a hard link when possible), and atomically renames.
  Unchanged games are copied byte-for-byte, so formatting you did not touch is
  preserved. If another program changed the file since it was read, the save
  is refused.
- `mygames.pgn.lock` holds an OS file lock while a process can write; a second
  writer fails fast. Read-only opens take no lock.
- Tool-owned tags: `[OrionId "17"]` (stable id) and `[OrionLabels "a,b"]`. They
  are written lazily: opening a file never rewrites it.
- Games that fail to parse are kept verbatim, flagged invalid, excluded from
  search, and reported (`check`, `db_stats`). Nothing is ever silently dropped.

## In memory

- `GameRecord` per game (~200–400 bytes plus tags): interned header strings,
  mainline packed at 2 bytes/move, sorted material signatures reached, motif
  hits, pawn-structure bitmask, labels, lowercase comment text.
- `PositionIndex`: three parallel arrays sorted by Zobrist hash (14 bytes per
  position), built in parallel and radix-sorted. Lookups are binary searches.
  Transposition-aware by construction.
- Changes after the index was built go to a small overlay that is scanned
  directly; once it passes ~5% of the database the index is rebuilt.
- Reads use immutable `Snapshot`s (lock-free). Writes go through `Batch`, which
  works on a copy and swaps it in atomically. If the batch body throws,
  nothing changes.

## Chess core

- Bitboards with classical ray attacks; legal move generation validated by
  perft (standard suites plus Chess960 positions).
- Castling is encoded "king takes own rook", which handles standard chess and
  Chess960 uniformly. FEN reads X-FEN/Shredder castling fields.
- The en passant square is stored only when a capture is actually possible,
  so transpositions are equal and hash equal.
- Zobrist keys come from a fixed seed, so they are deterministic and safe to
  persist.

## PGN

- `PgnSplitter` splits bytes (not chars), tracking offsets. It recovers from
  unclosed comments at the next `[Event` line and handles BOMs, `%` escape
  lines and tags-only games.
- `PgnParser` is lenient about syntax (glued move numbers, `0-0`, suffix
  annotations, unclosed tag quotes, `;` comments) but strict about chess. An
  illegal mainline move rejects the game. An illegal move inside a variation
  drops only the rest of that variation, with a warning.
- Per-game encoding detection: UTF-8, falling back to Windows-1252.

## Scale (Apple Silicon laptop, synthetic random games, `DatabasePerfTest`)

| games | positions | first open | reopen (cache) | typical query | pattern scan |
|---:|---:|---:|---:|---:|---:|
| 20,000 | 1.8 M | 1.4 s | 0.26 s | 4–40 ms | ~100 ms |
| 100,000 | 8.9 M | 4.9 s | 0.67 s | 7–130 ms | ~115 ms |

Run it yourself:

```bash
mvn -q test -Dtest=DatabasePerfTest -Doriondb.slow=true -Doriondb.perf.games=50000
```
