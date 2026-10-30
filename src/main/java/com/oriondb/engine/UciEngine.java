package com.oriondb.engine;

import com.oriondb.chess.Move;
import com.oriondb.chess.Position;
import com.oriondb.chess.San;
import com.oriondb.chess.Uci;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A running UCI engine process.
 *
 * <p>The constructor launches the engine and performs the {@code uci} /
 * {@code setoption} / {@code isready} handshake. {@link #analyse} runs one
 * search at a time (calls are serialized); {@link #close()} may be called
 * from any thread and aborts a running search. If the engine dies or stops
 * responding, the pending call fails with an {@link EngineException} and
 * every later call fails immediately.
 */
public final class UciEngine implements AutoCloseable {
    /** Default upper bound on one search when the limit has no move time. */
    public static final Duration DEFAULT_ANALYSIS_TIMEOUT = Duration.ofMinutes(5);

    private static final String EOF = new String("<eof>");
    private static final long HANDSHAKE_MS = 15_000, READY_MS = 30_000, STOP_GRACE_MS = 5_000, MOVETIME_SLACK_MS = 10_000;

    private final EngineConfig config;
    private final Process process;
    private final Writer out;
    private final Object writeLock = new Object();
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final Set<String> optionNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private String name = "", author = "";
    private volatile boolean dead, closed;
    private boolean chess960;
    private int multiPv = 1;
    private Duration analysisTimeout = DEFAULT_ANALYSIS_TIMEOUT;

    /**
     * Launches the engine described by {@code config} and completes the UCI handshake.
     *
     * @throws EngineException if the process cannot be started or does not speak UCI
     */
    public UciEngine(EngineConfig config) throws EngineException {
        this.config = config;
        try {
            process = new ProcessBuilder(config.executable().toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        } catch (IOException e) {
            throw new EngineException("cannot start engine " + config.executable() + ": " + e.getMessage(), e);
        }
        out = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(this::pump, "uci-reader-" + config.executable().getFileName());
        reader.setDaemon(true);
        reader.start();
        try {
            handshake();
        } catch (EngineException e) {
            kill();
            throw e;
        }
    }

    /** Launches the engine found by {@link EngineConfig#detect()}. */
    public static UciEngine start() throws EngineException {
        return new UciEngine(EngineConfig.detect()
            .orElseThrow(() -> new EngineException("no UCI engine found (set " + EngineConfig.ENV + " or install stockfish)")));
    }

    private void pump() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) lines.add(line.strip());
        } catch (IOException ignored) {
            // treated as end of stream
        } finally {
            lines.add(EOF);
        }
    }

    private void handshake() throws EngineException {
        send("uci");
        long deadline = deadline(HANDSHAKE_MS);
        while (true) {
            String line = await(deadline, "uciok");
            if (line.equals("uciok")) break;
            if (line.startsWith("id name ")) name = line.substring(8).strip();
            else if (line.startsWith("id author ")) author = line.substring(10).strip();
            else if (line.startsWith("option name ")) {
                int type = line.indexOf(" type ");
                optionNames.add((type < 0 ? line.substring(12) : line.substring(12, type)).strip());
            }
        }
        if (hasOption("Threads")) setOption("Threads", Integer.toString(config.threads()));
        if (hasOption("Hash")) setOption("Hash", Integer.toString(config.hashMb()));
        for (Map.Entry<String, String> e : config.options().entrySet()) setOption(e.getKey(), e.getValue());
        sync();
    }

    // ------------------------------------------------------------ accessors

    /** The engine's {@code id name}. */
    public String name() {
        return name;
    }

    /** The engine's {@code id author}. */
    public String author() {
        return author;
    }

    public EngineConfig config() {
        return config;
    }

    /** Names of the options the engine declared. */
    public Set<String> optionNames() {
        return Collections.unmodifiableSet(optionNames);
    }

    public boolean hasOption(String option) {
        return optionNames.contains(option);
    }

    /** True while the process runs and has not been closed. */
    public boolean isAlive() {
        return !dead && !closed && process.isAlive();
    }

    /** Upper bound on a search whose limit has no move time (default 5 minutes). */
    public synchronized void setAnalysisTimeout(Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        analysisTimeout = timeout;
    }

    // ------------------------------------------------------------ commands

    /** Sends {@code setoption} (the value may be null for button options) and waits for the engine. */
    public synchronized void configure(String option, String value) throws EngineException {
        requireAlive();
        setOption(option, value);
        sync();
    }

    /** Tells the engine the next search belongs to a different game ({@code ucinewgame}). */
    public synchronized void newGame() throws EngineException {
        requireAlive();
        send("ucinewgame");
        sync();
    }

    /** Analyses a single position (Chess960 inferred from its castling rights). */
    public Analysis analyse(Position position, Limit limit, int multiPv) throws EngineException {
        return analyse(position, List.of(), limit, multiPv, position.hasChess960Castling());
    }

    /** Analyses the position after {@code moves} from {@code start} (Chess960 inferred from the start position). */
    public Analysis analyse(Position start, List<Integer> moves, Limit limit, int multiPv) throws EngineException {
        return analyse(start, moves, limit, multiPv, start.hasChess960Castling());
    }

    /**
     * Analyses the position reached by playing {@code moves} from {@code start}.
     * The move history is sent to the engine so it can detect repetitions.
     * Checkmate and stalemate are answered without consulting the engine.
     *
     * @param multiPv  number of lines to compute (at least 1)
     * @param chess960 sets {@code UCI_Chess960} and sends castling as king-takes-rook
     * @throws IllegalArgumentException if a move is illegal
     * @throws EngineException          if the engine fails, dies or exceeds the timeout
     */
    public synchronized Analysis analyse(Position start, List<Integer> moves, Limit limit, int multiPv, boolean chess960)
            throws EngineException {
        if (multiPv < 1) throw new IllegalArgumentException("multiPv must be >= 1");
        Position base = start, pos = start;
        List<String> uciMoves = new ArrayList<>();
        for (int m : moves) {
            if (!Move.isNull(m) && !pos.isLegal(m)) {
                throw new IllegalArgumentException("illegal move " + Move.toString(m) + " in " + pos.fen());
            }
            pos = pos.play(m);
            if (Move.isNull(m)) {
                // UCI has no null move: restart the history after it.
                base = pos;
                uciMoves.clear();
            } else {
                uciMoves.add(Uci.format(m, chess960));
            }
        }
        if (pos.legalMoves().length == 0) {
            Score s = pos.isCheck() ? Score.mated() : Score.cp(0);
            return new Analysis(pos, List.of(new PvLine(1, 0, 0, s, new int[0], List.of(), 0)), Move.NONE, 0, 0);
        }
        requireAlive();

        if (chess960 != this.chess960 && hasOption("UCI_Chess960")) {
            setOption("UCI_Chess960", Boolean.toString(chess960));
            this.chess960 = chess960;
        }
        if (multiPv != this.multiPv) {
            setOption("MultiPV", Integer.toString(multiPv));
            this.multiPv = multiPv;
        }
        StringBuilder cmd = new StringBuilder("position fen ").append(base.fen());
        if (!uciMoves.isEmpty()) cmd.append(" moves ").append(String.join(" ", uciMoves));
        send(cmd.toString());
        sync();

        send(limit.goCommand());
        long budget = limit.movetimeMs() > 0
            ? Math.min(limit.movetimeMs() + MOVETIME_SLACK_MS, analysisTimeout.toMillis() + limit.movetimeMs())
            : analysisTimeout.toMillis();
        long deadline = deadline(budget);
        Map<Integer, Info> latest = new TreeMap<>();
        long nodes = 0, time = 0;
        String bestmove = null;
        boolean stopped = false;
        while (bestmove == null) {
            String line;
            try {
                line = poll(deadline);
            } catch (InterruptedException e) {
                abortSearch();
                Thread.currentThread().interrupt();
                throw new EngineException("analysis interrupted");
            }
            if (line == null) {
                if (stopped) {
                    kill();
                    throw new EngineException("engine did not stop searching; it was terminated");
                }
                send("stop");
                stopped = true;
                deadline = deadline(STOP_GRACE_MS);
                continue;
            }
            if (line.startsWith("bestmove")) {
                String[] t = line.split("\\s+");
                bestmove = t.length > 1 ? t[1] : "(none)";
            } else if (line.startsWith("info ")) {
                Info info = Info.parse(line);
                if (info == null) continue;
                if (info.nodes() > 0) nodes = info.nodes();
                if (info.timeMs() > 0) time = info.timeMs();
                if (info.score() != null && !info.bound() && info.multipv() <= multiPv) latest.put(info.multipv(), info);
            }
        }
        if (stopped) throw new EngineException("analysis exceeded the timeout of " + budget + " ms");

        List<PvLine> pvs = distinctLines(pos, latest.values());
        int best = parseMove(pos, bestmove);
        if (best == Move.NONE && !pvs.isEmpty()) best = pvs.get(0).firstMove();
        return new Analysis(pos, pvs, best, nodes, time);
    }

    /**
     * A search stopped mid-iteration can leave slots from different depths
     * holding the same move. Keep the deepest line per first move, order by
     * score (best first) and renumber.
     */
    private static List<PvLine> distinctLines(Position pos, java.util.Collection<Info> infos) {
        java.util.Map<Integer, PvLine> byMove = new java.util.LinkedHashMap<>();
        for (Info info : infos) {
            PvLine line = toPvLine(pos, info);
            if (line.moves().length == 0) continue;
            byMove.merge(line.firstMove(), line, (a, b) -> b.depth() > a.depth() ? b : a);
        }
        List<PvLine> sorted = new ArrayList<>(byMove.values());
        sorted.sort(java.util.Comparator.comparing(PvLine::score).reversed()
            .thenComparing(java.util.Comparator.comparingInt(PvLine::depth).reversed()));
        List<PvLine> out = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            PvLine l = sorted.get(i);
            out.add(new PvLine(i + 1, l.depth(), l.seldepth(), l.score(), l.moves(), l.san(), l.nodes()));
        }
        return out;
    }

    private static PvLine toPvLine(Position pos, Info info) {
        int[] moves = new int[info.pv().size()];
        List<String> san = new ArrayList<>(moves.length);
        int n = 0;
        Position p = pos;
        for (String text : info.pv()) {
            int m = parseMove(p, text);
            if (m == Move.NONE) break; // truncate at the first illegal move
            moves[n++] = m;
            san.add(San.format(p, m));
            p = p.play(m);
        }
        return new PvLine(info.multipv(), info.depth(), info.seldepth(), info.score(),
            java.util.Arrays.copyOf(moves, n), san, info.nodes());
    }

    private static int parseMove(Position pos, String text) {
        if (text == null || text.equals("(none)") || text.equals("0000")) return Move.NONE;
        try {
            int m = Uci.parse(pos, text);
            return Move.isNull(m) ? Move.NONE : m;
        } catch (IllegalArgumentException e) {
            return Move.NONE;
        }
    }

    /** Stops a search after an interrupt and resynchronizes, or kills the engine. */
    private void abortSearch() {
        boolean wasInterrupted = Thread.interrupted();
        try {
            send("stop");
            long deadline = deadline(STOP_GRACE_MS);
            while (true) {
                String line = poll(deadline);
                if (line == null) {
                    kill();
                    return;
                }
                if (line.startsWith("bestmove")) return;
            }
        } catch (EngineException | InterruptedException e) {
            kill();
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ protocol plumbing

    private void setOption(String option, String value) throws EngineException {
        send(value == null ? "setoption name " + option : "setoption name " + option + " value " + value);
    }

    /** {@code isready} round trip; discards any stray output before {@code readyok}. */
    private void sync() throws EngineException {
        send("isready");
        long deadline = deadline(READY_MS);
        while (!await(deadline, "readyok").equals("readyok")) {
            // skip
        }
    }

    private void send(String command) throws EngineException {
        if (closed) throw new EngineException("engine is closed");
        if (dead) throw deadException();
        synchronized (writeLock) {
            try {
                out.write(command);
                out.write('\n');
                out.flush();
            } catch (IOException e) {
                dead = true;
                throw deadException();
            }
        }
    }

    /** Next line before the deadline; fails on timeout or end of stream. */
    private String await(long deadline, String waitingFor) throws EngineException {
        String line;
        try {
            line = poll(deadline);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill();
            throw new EngineException("interrupted while waiting for " + waitingFor);
        }
        if (line == null) {
            kill();
            throw new EngineException("engine timed out waiting for " + waitingFor);
        }
        return line;
    }

    /** Next line, or null on timeout; throws if the engine has exited. */
    private String poll(long deadline) throws EngineException, InterruptedException {
        long wait = deadline - System.nanoTime();
        String line = wait <= 0 ? lines.poll() : lines.poll(wait, TimeUnit.NANOSECONDS);
        if (line == EOF) {
            lines.add(EOF); // keep reporting end of stream
            dead = true;
            throw deadException();
        }
        return line;
    }

    private EngineException deadException() {
        if (closed) return new EngineException("engine is closed");
        String code = "";
        try {
            if (process.waitFor(200, TimeUnit.MILLISECONDS)) code = " (exit code " + process.exitValue() + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new EngineException("engine " + (name.isEmpty() ? config.executable() : name) + " exited unexpectedly" + code);
    }

    private void requireAlive() throws EngineException {
        if (closed) throw new EngineException("engine is closed");
        if (dead || !process.isAlive()) {
            dead = true;
            throw deadException();
        }
    }

    private static long deadline(long millis) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    }

    private void kill() {
        dead = true;
        process.destroyForcibly();
    }

    /** Sends {@code quit} and terminates the process. Safe to call from any thread, repeatedly. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        synchronized (writeLock) {
            try {
                out.write("quit\n");
                out.flush();
            } catch (IOException ignored) {
                // already gone
            }
        }
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroy();
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String toString() {
        return name.isEmpty() ? config.executable().toString() : name;
    }

    // ------------------------------------------------------------ info parsing

    /** One parsed {@code info} line (fields absent from the line are 0 / null / empty). */
    record Info(int depth, int seldepth, int multipv, Score score, boolean bound, long nodes, long nps, long timeMs,
                List<String> pv) {

        /** Parses an {@code info} line; returns null for lines without search data ({@code info string ...}). */
        static Info parse(String line) {
            String[] t = line.strip().split("\\s+");
            if (t.length < 2 || !t[0].equals("info") || t[1].equals("string")) return null;
            int depth = 0, seldepth = 0, multipv = 1;
            long nodes = 0, nps = 0, time = 0;
            Score score = null;
            boolean bound = false, any = false;
            List<String> pv = List.of();
            try {
                for (int i = 1; i < t.length; i++) {
                    switch (t[i]) {
                        case "depth" -> { depth = Integer.parseInt(t[++i]); any = true; }
                        case "seldepth" -> seldepth = Integer.parseInt(t[++i]);
                        case "multipv" -> multipv = Integer.parseInt(t[++i]);
                        case "nodes" -> { nodes = Long.parseLong(t[++i]); any = true; }
                        case "nps" -> nps = Long.parseLong(t[++i]);
                        case "time" -> time = Long.parseLong(t[++i]);
                        case "score" -> {
                            String kind = t[++i];
                            int v = Integer.parseInt(t[++i]);
                            score = kind.equals("mate") ? Score.mate(v) : kind.equals("cp") ? Score.cp(v) : null;
                            any = true;
                        }
                        case "lowerbound", "upperbound" -> bound = true;
                        case "wdl" -> i += 3;
                        case "currmove", "currmovenumber", "hashfull", "tbhits", "sbhits", "cpuload" -> i++;
                        case "pv" -> {
                            pv = List.of(java.util.Arrays.copyOfRange(t, i + 1, t.length));
                            i = t.length;
                        }
                        case "string", "refutation", "currline" -> i = t.length;
                        default -> { /* unknown token: skip */ }
                    }
                }
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                return null;
            }
            return any ? new Info(depth, seldepth, multipv, score, bound, nodes, nps, time, pv) : null;
        }
    }
}
