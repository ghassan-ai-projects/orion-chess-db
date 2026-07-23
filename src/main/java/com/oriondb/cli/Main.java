package com.oriondb.cli;

import com.oriondb.db.Database;
import com.oriondb.mcp.McpServer;
import com.oriondb.model.Game;
import com.oriondb.pgn.PgnWriter;
import com.oriondb.analysis.Motifs;
import com.oriondb.search.Duplicates;
import com.oriondb.search.OpeningExplorer;
import com.oriondb.search.PlayerStats;
import com.oriondb.search.Search;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OrionDB command line.
 *
 * <pre>
 * oriondb mcp       games.pgn                 # MCP server over stdio
 * oriondb stats     games.pgn
 * oriondb import    games.pgn new-games.pgn
 * oriondb search    games.pgn --player Carlsen --eco B90 --limit 20
 * oriondb explorer  games.pgn "1. e4 c5 2. Nf3"
 * oriondb game      games.pgn 17
 * oriondb player    games.pgn "Carlsen"
 * oriondb h2h       games.pgn "Kasparov" "Karpov"
 * oriondb tag       games.pgn --ids 1,2 --add tactics,study
 * oriondb export    games.pgn --ids 1,2,3 -o out.pgn
 * oriondb dedupe    games.pgn
 * </pre>
 */
public final class Main {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (UsageException e) {
            System.err.println(e.getMessage());
            System.err.println();
            printUsage();
            System.exit(2);
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            printUsage();
            return;
        }
        String command = args[0];
        if (args.length < 2) {
            throw new UsageException("Missing database file for command '" + command + "'");
        }
        Path dbPath = Path.of(args[1]);
        String[] rest = new String[args.length - 2];
        System.arraycopy(args, 2, rest, 0, rest.length);

        switch (command) {
            case "mcp": mcp(dbPath); break;
            case "stats": stats(dbPath); break;
            case "import": importCmd(dbPath, rest); break;
            case "search": search(dbPath, rest); break;
            case "explorer": explorer(dbPath, rest); break;
            case "game": game(dbPath, rest); break;
            case "player": player(dbPath, rest); break;
            case "h2h": h2h(dbPath, rest); break;
            case "tag": tag(dbPath, rest); break;
            case "export": export(dbPath, rest); break;
            case "dedupe": dedupe(dbPath); break;
            default:
                throw new UsageException("Unknown command: " + command);
        }
    }

    private static void printUsage() {
        System.err.println("""
            OrionDB — chess database with built-in MCP server

            Usage: oriondb <command> <database.pgn> [options]

            Commands:
              mcp       Serve the database over MCP (stdio) for AI agents
              stats     Database overview
              import    Import games from a PGN file: import db.pgn new.pgn
              search    Find games: --white --black --player --event --eco --result
                        --date-from --date-to --min-elo --max-elo --elo-mode
                        --opening-line "1. e4 c5" --fen FEN --material "KRP vs KR"
                        --max-pieces N --motif fork --pawn iqp --comment TEXT
                        --tag TAG --any-tag TAG --without-tag TAG
                        --annotated --sort date|avg_elo|ply_count --desc
                        --limit N --offset N
              explorer  Opening tree: explorer db.pgn "1. e4 c5 2. Nf3"
              game      Show one game with tactics: game db.pgn <id>
              player    Player dossier: player db.pgn "Carlsen"
              h2h       Head to head: h2h db.pgn "Kasparov" "Karpov"
              tag       Update game tags: tag db.pgn --ids 1,2 --add tactics,study
                        Select with --player/--event/--eco/--tagged/--without-tag
                        Use --all explicitly to select every game
                        Manage tags with --rename OLD --to NEW, --merge OLD --into NEW,
                        or --delete-tag TAG
              export    Write PGN: export db.pgn [--ids 1,2,3] [-o out.pgn]
              dedupe    List duplicate games

            The database file is plain PGN — usable by any chess program.""");
    }

    // ------------------------------------------------------------------

    private static void mcp(Path dbPath) throws Exception {
        // stdout carries only protocol messages; announce on stderr.
        try (Database db = Database.open(dbPath, Database.OpenOptions.locked())) {
            System.err.println(
                "oriondb mcp: serving " + db.gameCount() + " games from " + dbPath);
            new McpServer(db).run();
        }
    }

    private static void stats(Path dbPath) throws Exception {
        try (Database db = open(dbPath)) {
            System.out.println("Database: " + dbPath);
            System.out.println("Games:    " + db.gameCount());
            Map<String, Integer> ecoCounts = new HashMap<>();
            int annotated = 0;
            String first = null;
            String last = null;
            for (Game game : db.games()) {
                String eco = game.tag("ECO");
                if (eco != null) ecoCounts.merge(eco, 1, Integer::sum);
                if (game.hasAnnotations()) annotated++;
                String date = game.sortableDate();
                if (date != null) {
                    if (first == null || date.compareTo(first) < 0) first = date;
                    if (last == null || date.compareTo(last) > 0) last = date;
                }
            }
            if (first != null) {
                System.out.println("Dates:    " + first + " to " + last);
            }
            System.out.println("Annotated: " + annotated);
            System.out.println("Top openings:");
            ecoCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> System.out.printf("  %-4s %d games%n", e.getKey(), e.getValue()));
            Map<String, Integer> tagCounts = db.gameTagCounts();
            if (!tagCounts.isEmpty()) {
                System.out.println("Top tags:");
                tagCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .forEach(e -> System.out.printf(
                        "  %-24s %d games%n", e.getKey(), e.getValue()));
            }
        }
    }

    private static void importCmd(Path dbPath, String[] args) throws Exception {
        if (args.length == 0) {
            throw new UsageException("import needs a PGN file to import");
        }
        try (Database db = Database.open(dbPath, Database.OpenOptions.locked())) {
            for (String file : args) {
                long start = System.currentTimeMillis();
                Database.ImportReport report = db.importFile(Path.of(file));
                long ms = System.currentTimeMillis() - start;
                System.out.printf(
                    "%s: %d imported, %d duplicates skipped, %d rejected (%d ms)%n",
                    file, report.gamesAdded(), report.duplicatesSkipped(),
                    report.gamesRejected(), ms);
                for (String warning : report.warnings()) {
                    System.out.println("  warning: " + warning);
                }
            }
            db.save();
            System.out.println("Database now holds " + db.gameCount() + " games.");
        }
    }

    private static void search(Path dbPath, String[] args) throws Exception {
        try (Database db = open(dbPath)) {
            Search search = db.search();
            int limit = 25;
            int offset = 0;
            String sort = null;
            boolean desc = false;

            for (int i = 0; i < args.length; i++) {
                String flag = args[i];
                switch (flag) {
                    case "--annotated": search.annotated(true); break;
                    case "--desc": desc = true; break;
                    default: {
                        String value = i + 1 < args.length ? args[++i] : null;
                        if (value == null) {
                            throw new UsageException("Missing value for " + flag);
                        }
                        switch (flag) {
                            case "--white": search.white(value); break;
                            case "--black": search.black(value); break;
                            case "--player": search.player(value); break;
                            case "--event": search.event(value); break;
                            case "--site": search.site(value); break;
                            case "--eco": search.eco(value); break;
                            case "--opening": search.opening(value); break;
                            case "--result": search.result(value); break;
                            case "--date-from": search.dateFrom(value); break;
                            case "--date-to": search.dateTo(value); break;
                            case "--min-elo": search.minElo(Integer.parseInt(value)); break;
                            case "--max-elo": search.maxElo(Integer.parseInt(value)); break;
                            case "--elo-mode": search.eloMode(
                                Search.EloMode.valueOf(value.toUpperCase(Locale.ROOT))); break;
                            case "--min-ply": search.minPly(Integer.parseInt(value)); break;
                            case "--max-ply": search.maxPly(Integer.parseInt(value)); break;
                            case "--opening-line": search.openingLine(value); break;
                            case "--fen": search.containsPosition(value); break;
                            case "--material": search.material(value); break;
                            case "--max-pieces": search.maxPieces(Integer.parseInt(value)); break;
                            case "--motif": search.motif(value); break;
                            case "--pawn": search.pawnFeature(value); break;
                            case "--comment": search.commentContains(value); break;
                            case "--tag": search.tag(value); break;
                            case "--any-tag": search.anyTag(value); break;
                            case "--without-tag": search.withoutTag(value); break;
                            case "--sort": sort = value; break;
                            case "--limit": limit = Integer.parseInt(value); break;
                            case "--offset": offset = Integer.parseInt(value); break;
                            default:
                                throw new UsageException("Unknown search option: " + flag);
                        }
                    }
                }
            }
            if (sort != null) {
                search.sortBy(Search.Sort.valueOf(sort.toUpperCase(Locale.ROOT)), desc);
            } else if (desc) {
                search.sortBy(Search.Sort.ID, true);
            }

            int total = search.count();
            List<Game> games = search.limit(limit).offset(offset).find();
            for (Game game : games) {
                String tags = game.gameTags().isEmpty()
                    ? "" : " [" + String.join(", ", game.gameTags()) + "]";
                System.out.printf("#%-5d %-24s - %-24s %-7s %-10s %s %s%s%n",
                    game.id(),
                    clip(game.white(), 24), clip(game.black(), 24),
                    game.result(), game.date(),
                    game.tag("ECO", ""),
                    clip(game.tag("Opening", ""), 30),
                    tags);
            }
            System.out.println(games.size() + " of " + total + " matching games shown.");
        }
    }

    private static void explorer(Path dbPath, String[] args) throws Exception {
        try (Database db = open(dbPath)) {
            String line = args.length > 0 ? String.join(" ", args) : "";
            OpeningExplorer.Result result = line.startsWith("--fen")
                ? db.explorer().exploreFen(line.substring(5).trim())
                : db.explorer().explore(line);

            if (result.openingName() != null) {
                System.out.println(result.openingEco() + " " + result.openingName());
            }
            System.out.println("Position: " + result.fen());
            System.out.printf("Games: %d  (+%d =%d -%d from White's side)%n%n",
                result.totalGames(), result.whiteWins(), result.draws(), result.blackWins());
            System.out.printf("%-8s %6s %7s %6s %6s %6s %8s %10s%n",
                "move", "games", "score%", "+", "=", "-", "avgElo", "last");
            for (OpeningExplorer.MoveStat stat : result.moves()) {
                System.out.printf("%-8s %6d %6.1f%% %6d %6d %6d %8s %10s%n",
                    stat.san(), stat.games(), stat.scorePercent(),
                    stat.whiteWins(), stat.draws(), stat.blackWins(),
                    stat.averageElo() != null ? stat.averageElo().toString() : "-",
                    stat.lastPlayed() != null ? stat.lastPlayed() : "-");
            }
        }
    }

    private static void game(Path dbPath, String[] args) throws Exception {
        if (args.length == 0) {
            throw new UsageException("game needs a game id");
        }
        try (Database db = open(dbPath)) {
            Game game = db.game(Integer.parseInt(args[0]));
            if (game == null) {
                throw new IllegalArgumentException("No game with id " + args[0]);
            }
            System.out.print(new PgnWriter().write(game));
            List<Motifs.Moment> moments = Motifs.moments(game);
            if (!moments.isEmpty()) {
                System.out.println("\nTactical moments:");
                for (Motifs.Moment moment : moments) {
                    System.out.printf("  move %d%s %-8s %s%n",
                        moment.ply() / 2 + 1,
                        moment.ply() % 2 == 0 ? "." : "...",
                        moment.san(),
                        String.join(", ", moment.motifNames()));
                }
            }
        }
    }

    private static void player(Path dbPath, String[] args) throws Exception {
        if (args.length == 0) {
            throw new UsageException("player needs a name");
        }
        try (Database db = open(dbPath)) {
            PlayerStats.Report report = db.players().report(String.join(" ", args));
            if (report == null) {
                System.out.println("No games found.");
                return;
            }
            System.out.println(report.name());
            System.out.printf("Games: %d   Performance: %s   Peak Elo: %s%n",
                report.totalGames(),
                report.performance() != null ? report.performance() : "-",
                report.highestElo() != null ? report.highestElo() : "-");
            if (report.firstGameDate() != null) {
                System.out.println("Active: " + report.firstGameDate()
                    + " to " + report.lastGameDate());
            }
            printScore("As White", report.asWhite());
            printScore("As Black", report.asBlack());
            printOpenings("Openings as White", report.openingsAsWhite());
            printOpenings("Openings as Black", report.openingsAsBlack());
            if (!report.bestWins().isEmpty()) {
                System.out.println("Best wins:");
                for (PlayerStats.BestWin win : report.bestWins()) {
                    System.out.printf("  #%d vs %s (%d) %s%n",
                        win.gameId(), win.opponent(), win.opponentElo(),
                        win.date() != null ? win.date() : "");
                }
            }
        }
    }

    private static void printScore(String label, PlayerStats.Score score) {
        System.out.printf("%-9s %4d games  +%d =%d -%d  (%.1f%%)%n",
            label + ":", score.games(), score.wins(), score.draws(), score.losses(),
            score.percent());
    }

    private static void printOpenings(String label, List<PlayerStats.OpeningStat> openings) {
        if (openings.isEmpty()) return;
        System.out.println(label + ":");
        for (PlayerStats.OpeningStat stat : openings) {
            System.out.printf("  %-4s %-40s %3d games  %.1f%%%n",
                stat.eco(), stat.name() != null ? clip(stat.name(), 40) : "",
                stat.games(), stat.scorePercent());
        }
    }

    private static void h2h(Path dbPath, String[] args) throws Exception {
        if (args.length < 2) {
            throw new UsageException("h2h needs two player names");
        }
        try (Database db = open(dbPath)) {
            PlayerStats.HeadToHead h2h = db.players().headToHead(args[0], args[1]);
            if (h2h == null) {
                System.out.println("No games between these players.");
                return;
            }
            System.out.println(h2h.playerA() + " vs " + h2h.playerB());
            printScore("Overall", h2h.overall());
            printScore("As White", h2h.aAsWhite());
            printScore("As Black", h2h.aAsBlack());
            System.out.println("Games: " + h2h.gameIds());
        }
    }

    private static void tag(Path dbPath, String[] args) throws Exception {
        List<Integer> ids = new ArrayList<>();
        List<String> add = new ArrayList<>();
        List<String> remove = new ArrayList<>();
        List<String> replacement = null;
        List<String> requiredTags = new ArrayList<>();
        List<String> anyTags = new ArrayList<>();
        List<String> excludedTags = new ArrayList<>();
        String player = null;
        String event = null;
        String eco = null;
        boolean selectAll = false;
        String managementAction = null;
        String managementSource = null;
        String managementDestination = null;

        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if ("--all".equals(flag)) {
                selectAll = true;
                continue;
            }
            if (i + 1 >= args.length) {
                throw new UsageException("Missing value for " + flag);
            }
            String value = args[++i];
            switch (flag) {
                case "--ids":
                    for (String id : value.split(",")) {
                        ids.add(Integer.parseInt(id.trim()));
                    }
                    break;
                case "--add":
                    add.addAll(tagList(value));
                    break;
                case "--remove":
                    remove.addAll(tagList(value));
                    break;
                case "--set":
                    replacement = tagList(value);
                    break;
                case "--player":
                    player = value;
                    break;
                case "--event":
                    event = value;
                    break;
                case "--eco":
                    eco = value;
                    break;
                case "--tagged":
                    requiredTags.addAll(tagList(value));
                    break;
                case "--any-tagged":
                    anyTags.addAll(tagList(value));
                    break;
                case "--without-tag":
                    excludedTags.addAll(tagList(value));
                    break;
                case "--rename":
                case "--merge":
                case "--delete-tag":
                    if (managementAction != null) {
                        throw new UsageException("Only one tag management action is allowed");
                    }
                    managementAction = flag;
                    managementSource = value;
                    break;
                case "--to":
                case "--into":
                    managementDestination = value;
                    break;
                default:
                    throw new UsageException("Unknown tag option: " + flag);
            }
        }

        boolean hasSearchSelection = selectAll || player != null || event != null
            || eco != null || !requiredTags.isEmpty() || !anyTags.isEmpty()
            || !excludedTags.isEmpty();
        if (managementAction == null && managementDestination != null) {
            throw new UsageException("--to/--into requires --rename or --merge");
        }
        if (managementAction != null) {
            if (!ids.isEmpty() || hasSearchSelection || replacement != null
                    || !add.isEmpty() || !remove.isEmpty()) {
                throw new UsageException(
                    "Tag management cannot be combined with game selection or updates");
            }
            if (!"--delete-tag".equals(managementAction)
                    && managementDestination == null) {
                throw new UsageException(managementAction + " needs --to or --into");
            }
            if ("--delete-tag".equals(managementAction)
                    && managementDestination != null) {
                throw new UsageException("--delete-tag cannot use --to or --into");
            }
            manageTag(dbPath, managementAction, managementSource, managementDestination);
            return;
        }
        if (ids.isEmpty() && !hasSearchSelection) {
            throw new UsageException(
                "tag needs --ids, search filters, or an explicit --all");
        }
        if (!ids.isEmpty() && hasSearchSelection) {
            throw new UsageException("--ids cannot be combined with search selection");
        }
        if (selectAll && (player != null || event != null || eco != null
                || !requiredTags.isEmpty() || !anyTags.isEmpty()
                || !excludedTags.isEmpty())) {
            throw new UsageException("--all cannot be combined with search filters");
        }
        if (replacement != null && (!add.isEmpty() || !remove.isEmpty())) {
            throw new UsageException("--set cannot be combined with --add or --remove");
        }
        if (replacement == null && add.isEmpty() && remove.isEmpty()) {
            throw new UsageException("tag needs --add, --remove, or --set");
        }

        try (Database db = Database.open(dbPath, Database.OpenOptions.locked())) {
            db.setSaveOnClose(false);
            if (ids.isEmpty()) {
                Search selection = db.search();
                if (player != null) selection.player(player);
                if (event != null) selection.event(event);
                if (eco != null) selection.eco(eco);
                if (!requiredTags.isEmpty()) selection.tags(requiredTags);
                if (!anyTags.isEmpty()) selection.anyTags(anyTags);
                if (!excludedTags.isEmpty()) selection.withoutTags(excludedTags);
                ids.addAll(selection.find().stream().map(Game::id).toList());
            }
            Database.TagUpdateReport report = replacement != null
                ? db.setGameTags(ids, replacement)
                : db.updateGameTags(ids, add, remove);
            if (report.gamesUpdated() > 0) {
                db.save();
            }
            System.out.printf("%d games updated, %d unchanged.%n",
                report.gamesUpdated(), report.gamesUnchanged());
            if (!report.missingGameIds().isEmpty()) {
                System.out.println("Missing game ids: " + report.missingGameIds());
            }
        }
    }

    private static void manageTag(
            Path dbPath, String action, String source, String destination)
            throws Exception {
        try (Database db = Database.open(dbPath, Database.OpenOptions.locked())) {
            db.setSaveOnClose(false);
            Database.TagUpdateReport report = switch (action) {
                case "--rename" -> db.renameGameTag(source, destination);
                case "--merge" -> db.mergeGameTag(source, destination);
                case "--delete-tag" -> db.deleteGameTag(source);
                default -> throw new AssertionError("Unknown tag management action");
            };
            if (report.gamesUpdated() > 0) db.save();
            System.out.printf("%d games updated, %d unchanged.%n",
                report.gamesUpdated(), report.gamesUnchanged());
        }
    }

    private static List<String> tagList(String value) {
        List<String> tags = new ArrayList<>();
        if (value == null || value.isBlank()) return tags;
        for (String tag : value.split(",")) {
            if (!tag.isBlank()) tags.add(tag);
        }
        return tags;
    }

    private static void export(Path dbPath, String[] args) throws Exception {
        try (Database db = open(dbPath)) {
            List<Game> games = new ArrayList<>();
            Path out = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--ids": {
                        for (String id : args[++i].split(",")) {
                            Game game = db.game(Integer.parseInt(id.trim()));
                            if (game == null) {
                                throw new IllegalArgumentException("No game with id " + id);
                            }
                            games.add(game);
                        }
                        break;
                    }
                    case "-o":
                    case "--out":
                        out = Path.of(args[++i]);
                        break;
                    default:
                        throw new UsageException("Unknown export option: " + args[i]);
                }
            }
            if (games.isEmpty()) {
                games.addAll(db.games());
            }
            if (out != null) {
                try (var writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                    new PgnWriter().write(games, writer);
                }
                System.out.println("Wrote " + games.size() + " games to " + out);
            } else {
                System.out.print(new PgnWriter().write(games));
            }
        }
    }

    private static void dedupe(Path dbPath) throws Exception {
        try (Database db = open(dbPath)) {
            List<Duplicates.Group> groups = Duplicates.find(db);
            if (groups.isEmpty()) {
                System.out.println("No duplicate games found.");
                return;
            }
            for (Duplicates.Group group : groups) {
                Game first = db.game(group.gameIds().get(0));
                System.out.printf("%s ids=%s %s%n",
                    group.samePlayers() ? "duplicate " : "same-moves",
                    group.gameIds(), first);
            }
        }
    }

    // ------------------------------------------------------------------

    /** Open for reading: never creates and never writes back. */
    private static Database open(Path dbPath) throws Exception {
        if (!Files.exists(dbPath)) {
            throw new IllegalArgumentException("Database file not found: " + dbPath);
        }
        Database db = Database.open(dbPath);
        db.setSaveOnClose(false);
        return db;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
