# Performance profile

OrionDB targets personal collections: hundreds to tens of thousands of games.
The performance goal is predictable interactive use without turning the PGN
file into a second database format.

Run the repeatable synthetic profile with:

```bash
mvn -q -Dtest=DatabasePerfTest \
  -Doriondb.perf.games=5000 \
  -Doriondb.perf.queries=1000 test
```

An Apple Silicon development run with OpenJDK 26 produced:

```text
Opened 5000 games in 2409 ms (2076 games/s)
1000 indexed tag queries in 242 ms
1000 full-scan tag queries in 4237 ms
```

These numbers are directional, not a release guarantee. The generated games
contain 40–119 legal half-moves, so opening includes PGN parsing, legal replay,
ECO classification, tactical/material analysis, and index construction.

## Decisions from the profile

- Exact categorical tags use an inverted in-memory index. This is a common
  filter for chess-tool workflows and was roughly an order of magnitude faster
  than the same-result-count full-scan control at 5,000 games.
- Player, event, site, and opening filters remain scans. Their public semantics
  are case-insensitive substring matching, so simple exact-value indexes would
  add write cost and memory without serving the actual queries. At the target
  scale, these scans remain small compared with legal replay during open.
- File parsing decodes incrementally and retains only one source-game text
  chunk at a time. Saves and file exports write directly to a buffered
  character stream. Parsed game objects still live in memory by design.
- Position queries retain their existing Zobrist inverted index because replay
  and transposition-aware search already demonstrate a clear benefit.

Profile before adding another index. For larger collections, representative
real PGNs and real query distributions are more useful than a synthetic
microbenchmark.
