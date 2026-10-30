package com.oriondb.cli;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code oriondb} command line. Exit codes: 0 success, 1 runtime error
 * (message on stderr; stack trace with {@code ORIONDB_DEBUG=1}), 2 usage error.
 */
public final class Main {
    public static final String VERSION = "3.0.0";

    private static final Map<String, Command> COMMANDS = new LinkedHashMap<>();

    static {
        List<Command> all = new ArrayList<>();
        all.addAll(DbCommands.commands().subList(0, 2));      // import, stats
        all.addAll(SearchCommands.commands().subList(0, 1));  // search
        all.addAll(GameCommands.commands().subList(0, 2));    // game, board
        all.addAll(ReportCommands.commands());                // explore, player, h2h
        all.addAll(LabelCommands.commands());                 // labels, label, label-rename, label-delete
        all.addAll(GameCommands.commands().subList(2, 5));    // tag, delete, dedupe
        all.addAll(SearchCommands.commands().subList(1, 2));  // export
        all.addAll(EngineCommands.commands());                // analyze, annotate
        all.addAll(DbCommands.commands().subList(2, 5));      // check, reindex, mcp
        for (Command c : all) COMMANDS.put(c.name(), c);
    }

    private Main() {}

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, StandardCharsets.UTF_8);
        int code = run(args, System.in, out, err);
        out.flush();
        err.flush();
        System.exit(code);
    }

    /** Runs a command line; returns the exit code (never calls System.exit). */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, InputStream.nullInputStream(), out, err);
    }

    public static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        Context ctx = new Context(in, out, err);
        if (args.length == 0) {
            err.print(overview());
            return 2;
        }
        String name = args[0];
        switch (name) {
            case "--help", "-h", "help" -> {
                if (args.length > 1 && !name.equals("help")) return usage(err, null, "unexpected argument '" + args[1] + "'");
                if (args.length > 2) return usage(err, null, "help takes at most one command name");
                if (args.length == 2) {
                    Command c = COMMANDS.get(args[1]);
                    if (c == null) return unknown(err, args[1]);
                    out.print(help(c));
                } else {
                    out.print(overview());
                }
                return 0;
            }
            case "--version", "-V", "version" -> {
                out.println("oriondb " + VERSION);
                return 0;
            }
            default -> { }
        }
        Command cmd = COMMANDS.get(name);
        if (cmd == null) return unknown(err, name);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        for (String a : rest) {
            if (a.equals("--")) break;
            if (a.equals("--help") || a.equals("-h")) {
                out.print(help(cmd));
                return 0;
            }
        }
        try {
            Args parsed = Args.parse(rest, cmd.spec());
            return cmd.handler().run(parsed, ctx);
        } catch (UsageException e) {
            return usage(err, cmd, e.getMessage());
        } catch (Exception | StackOverflowError | OutOfMemoryError e) {
            String msg = e.getMessage();
            if (e instanceof Error || msg == null || msg.isBlank()) msg = e.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
            if (e instanceof java.io.UncheckedIOException u && u.getCause() != null) msg = u.getCause().getMessage();
            err.println("oriondb " + name + ": error: " + msg);
            if (debug()) e.printStackTrace(err);
            return 1;
        } finally {
            out.flush();
            err.flush();
        }
    }

    private static boolean debug() {
        String v = System.getenv("ORIONDB_DEBUG");
        return v != null && !v.isBlank() && !v.equals("0") && !v.equalsIgnoreCase("false");
    }

    private static int usage(PrintStream err, Command cmd, String message) {
        if (cmd == null) {
            err.println("oriondb: " + message);
            err.println("Run 'oriondb help' for the list of commands.");
        } else {
            err.println("oriondb " + cmd.name() + ": " + message);
            err.println("usage: " + cmd.synopsis());
            err.println("Run 'oriondb help " + cmd.name() + "' for details.");
        }
        return 2;
    }

    private static int unknown(PrintStream err, String name) {
        String hint = "";
        int best = Integer.MAX_VALUE;
        for (String c : COMMANDS.keySet()) {
            int d = distance(name.toLowerCase(), c);
            if (d < best && d <= 2) {
                best = d;
                hint = c;
            }
            if (hint.isEmpty() && c.startsWith(name.toLowerCase()) && !name.isEmpty()) hint = c;
        }
        err.println("oriondb: unknown command '" + name + "'" + (hint.isEmpty() ? "" : "; did you mean '" + hint + "'?"));
        err.println("Run 'oriondb help' for the list of commands.");
        return 2;
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    // ------------------------------------------------------------ help

    static String overview() {
        StringBuilder sb = new StringBuilder();
        sb.append("OrionDB ").append(VERSION).append(" - chess database engine (plain PGN files with a persistent index)\n\n");
        sb.append("Usage: oriondb <command> [arguments] [options]\n\nCommands:\n");
        int w = COMMANDS.keySet().stream().mapToInt(String::length).max().orElse(10);
        for (Command c : COMMANDS.values()) sb.append(String.format("  %-" + w + "s  %s%n", c.name(), c.summary()));
        sb.append("""

            Examples:
              oriondb import my.pgn twic1500.pgn --label twic
              oriondb search my.pgn player:carlsen eco:B90-B99 elo:2700 sort:-date
              oriondb game my.pgn 42 --ply 30
              oriondb explore my.pgn e4 c5 Nf3 --filter "elo:2600"
              oriondb player my.pgn "Carlsen, Magnus"
              oriondb label my.pgn --query "eco:B90-B99" --add najdorf
              oriondb analyze "1. e4 e5 2. Nf3 Nc6 3. Bb5" --depth 22 --multipv 3
              oriondb mcp my.pgn            (for AI assistants)

            Run 'oriondb help <command>' for a command's options; 'oriondb help search'
            also lists the query language. Commands that only read open the database
            without the write lock, so they work while an MCP server is using it.
            Exit codes: 0 ok, 1 error, 2 usage error. ORIONDB_DEBUG=1 prints stack traces.
            """);
        return sb.toString();
    }

    static String help(Command c) {
        StringBuilder sb = new StringBuilder();
        sb.append("usage: ").append(c.synopsis()).append("\n\n").append(c.summary()).append(".\n");
        if (!c.details().isBlank()) sb.append('\n').append(c.details().stripTrailing()).append('\n');
        if (c.name().equals("search") || c.name().equals("export")) {
            sb.append('\n').append(SearchCommands.queryHelp().stripTrailing()).append('\n');
        }
        return sb.toString();
    }

    /** Command names in display order (for tests and completion). */
    static List<String> commandNames() {
        return List.copyOf(COMMANDS.keySet());
    }
}
