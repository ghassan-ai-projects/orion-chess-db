package com.oriondb.pgn;

import com.oriondb.chess.ChessFormatException;
import com.oriondb.chess.Position;
import com.oriondb.chess.San;
import com.oriondb.game.Game;
import com.oriondb.game.GameNode;
import com.oriondb.game.Nag;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses the text of one PGN game into a {@link Game}.
 *
 * <p>Lenient where real-world files are sloppy (glued move numbers, "0-0",
 * annotation suffixes, unclosed quotes and comments, stray tokens) but strict
 * about chess: an illegal mainline move rejects the game. An illegal move
 * inside a variation drops the rest of that variation with a warning.
 */
public final class PgnParser {
    private static final Pattern MOVE_LIKE = Pattern.compile(".*([a-h][1-8]|[O0o]-[O0o]|^--$|^Z0$).*");
    private static final Pattern MOVE_NUMBER = Pattern.compile("^\\d+\\.*");
    private static final Pattern CASTLING = Pattern.compile("^[O0o]-[O0o](-[O0o])?.*");

    /** A parsed game plus non-fatal problems found while parsing it. */
    public record Result(Game game, List<String> warnings) {}

    private final String text;
    private int pos;
    private int line = 1;
    private final List<String> warnings = new ArrayList<>();

    private PgnParser(String text) {
        this.text = text;
    }

    public static Game parseGame(String text) throws PgnException {
        return parse(text).game();
    }

    public static Result parse(String text) throws PgnException {
        return new PgnParser(text).run();
    }

    /** Parses every game in a PGN string; throws on the first invalid game. */
    public static List<Game> parseAll(String pgn) throws PgnException {
        List<Game> games = new ArrayList<>();
        try (PgnSplitter splitter = new PgnSplitter(new ByteArrayInputStream(pgn.getBytes(StandardCharsets.UTF_8)))) {
            while (splitter.hasNext()) {
                RawGame raw = splitter.next();
                try {
                    games.add(parseGame(raw.text()));
                } catch (PgnException e) {
                    throw new PgnException("game " + (raw.index() + 1) + ": " + e.getMessage(), 0);
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return games;
    }

    // ------------------------------------------------------------ headers

    private Result run() throws PgnException {
        List<String[]> tags = readTags();
        Position start = Position.start();
        String fen = null, variant = null;
        for (String[] kv : tags) {
            if (kv[0].equals("FEN")) fen = kv[1];
            if (kv[0].equals("Variant")) variant = kv[1];
        }
        if (variant != null && !isSupportedVariant(variant)) {
            throw new PgnException("unsupported variant '" + variant + "'", 0);
        }
        if (fen != null && !fen.isBlank()) {
            try {
                start = Position.fromFen(fen);
            } catch (ChessFormatException e) {
                throw new PgnException("invalid FEN tag: " + e.getMessage(), 0);
            }
        }
        Game game = new Game(start);
        game.tags().remove("SetUp");
        game.tags().remove("FEN");
        for (String[] kv : tags) {
            if (!com.oriondb.game.Tags.isValidName(kv[0])) {
                warnings.add("ignored invalid tag name '" + kv[0] + "'");
                continue;
            }
            if (game.tags().has(kv[0])) warnings.add("duplicate tag " + kv[0]);
            else game.tags().set(kv[0], kv[1]);
        }
        String resultToken = parseMovetext(game);
        String tagResult = game.tags().get("Result");
        if (resultToken != null) {
            if (tagResult == null || tagResult.equals("*") || tagResult.equals("?")) game.tags().set("Result", resultToken);
            else if (!tagResult.equals(resultToken)) warnings.add("Result tag " + tagResult + " differs from movetext result " + resultToken);
        }
        return new Result(game, List.copyOf(warnings));
    }

    private static boolean isSupportedVariant(String v) {
        String s = v.toLowerCase().strip();
        return s.isEmpty() || s.equals("standard") || s.contains("960") || s.contains("fischer")
            || s.equals("from position") || s.equals("normal");
    }

    private List<String[]> readTags() {
        List<String[]> tags = new ArrayList<>();
        while (true) {
            skipWhitespace();
            if (pos >= text.length()) break;
            char c = text.charAt(pos);
            if (c == '%' && atLineStart()) {
                skipToEol();
                continue;
            }
            if (c != '[') break;
            int eol = text.indexOf('\n', pos);
            if (eol < 0) eol = text.length();
            String lineText = text.substring(pos, eol).strip();
            String[] kv = PgnText.parseTagLine(lineText);
            if (kv == null) warnings.add("unreadable tag line: " + lineText);
            else tags.add(kv);
            pos = eol;
        }
        return tags;
    }

    // ------------------------------------------------------------ movetext

    /** Parses movetext into the game's tree; returns the result token, if any. */
    private String parseMovetext(Game game) throws PgnException {
        GameNode cur = game.root();
        Deque<GameNode> stack = new ArrayDeque<>();
        String pendingComment = null;   // comment before the first move of a variation
        boolean lineStart = true;       // nothing played yet in the current (sub)line

        while (true) {
            skipWhitespace();
            if (pos >= text.length()) break;
            char c = text.charAt(pos);
            if (c == '%' && atLineStart()) {
                skipToEol();
            } else if (c == '{') {
                int close = text.indexOf('}', pos + 1);
                String body;
                if (close < 0) {
                    warnings.add("unclosed comment at line " + line);
                    body = text.substring(pos + 1);
                    countLines(text.length());
                    pos = text.length();
                } else {
                    body = text.substring(pos + 1, close);
                    countLines(close + 1);
                    pos = close + 1;
                }
                String comment = normalizeComment(body);
                if (comment.isEmpty()) continue;
                if (lineStart && !stack.isEmpty()) {
                    pendingComment = pendingComment == null ? comment : pendingComment + " " + comment;
                } else {
                    cur.appendComment(comment);
                }
            } else if (c == ';') {
                int start = pos + 1;
                skipToEol();
                String comment = text.substring(start, pos).strip();
                if (comment.isEmpty()) continue;
                if (lineStart && !stack.isEmpty()) {
                    pendingComment = pendingComment == null ? comment : pendingComment + " " + comment;
                } else {
                    cur.appendComment(comment);
                }
            } else if (c == '(') {
                pos++;
                if (cur.isRoot()) {
                    warnings.add("variation before the first move at line " + line + " ignored");
                    skipVariation();
                    continue;
                }
                stack.push(cur);
                cur = cur.parent();
                lineStart = true;
                pendingComment = null;
            } else if (c == ')') {
                pos++;
                if (stack.isEmpty()) {
                    warnings.add("unbalanced ')' at line " + line);
                    continue;
                }
                cur = stack.pop();
                lineStart = false;
                pendingComment = null;
            } else if (c == '[') {
                // A stray tag inside movetext; skip it.
                int eol = text.indexOf(']', pos);
                warnings.add("stray '[' in movetext at line " + line);
                int stop = eol < 0 ? text.length() : eol + 1;
                countLines(stop);
                pos = stop;
            } else {
                String token = readToken();
                if (token.isEmpty()) {
                    pos++;
                    continue;
                }
                String result = resultToken(token);
                if (result != null) {
                    if (stack.isEmpty()) return result;
                    warnings.add("result token inside a variation ignored");
                    continue;
                }
                int nag = Nag.parse(token);
                if (nag >= 0) {
                    if (cur.isRoot()) warnings.add("annotation before the first move ignored");
                    else cur.appendNag(nag);
                    continue;
                }
                if (!CASTLING.matcher(token).matches()) {
                    token = MOVE_NUMBER.matcher(token).replaceFirst("");
                    if (token.isEmpty() || token.matches("\\.+")) continue;
                }
                if (token.equals("e.p.") || token.equals("ep")) continue;
                GameNode next = playToken(cur, token, stack.isEmpty());
                if (next == null) {
                    // Illegal move in a variation: drop the remainder of this variation.
                    skipVariation();
                    cur = stack.pop();
                    lineStart = false;
                    pendingComment = null;
                    continue;
                }
                cur = next;
                if (pendingComment != null) {
                    cur.setStartingComment(pendingComment);
                    pendingComment = null;
                }
                lineStart = false;
            }
        }
        if (!stack.isEmpty()) warnings.add("unterminated variation");
        return null;
    }

    /** Plays one move token; returns null for an illegal variation move. */
    private GameNode playToken(GameNode cur, String token, boolean mainline) throws PgnException {
        String move = token;
        int suffixNag = -1;
        int cut = move.length();
        while (cut > 0 && (move.charAt(cut - 1) == '!' || move.charAt(cut - 1) == '?')) cut--;
        if (cut < move.length()) {
            suffixNag = Nag.parse(move.substring(cut));
            move = move.substring(0, cut);
        }
        if (!MOVE_LIKE.matcher(move).matches()) {
            warnings.add("ignored unknown token '" + token + "' at line " + line);
            return cur;
        }
        int m;
        try {
            m = San.parse(cur.position(), move);
        } catch (ChessFormatException e) {
            String where = "'" + token + "' after " + describe(cur);
            if (mainline) throw new PgnException("illegal or unparseable move " + where, line);
            warnings.add("illegal move " + where + " in a variation; rest of the variation dropped");
            return null;
        }
        GameNode next = cur.appendChild(m);
        if (suffixNag >= 0) next.appendNag(suffixNag);
        return next;
    }

    private static String describe(GameNode n) {
        return n.isRoot() ? "the start" : n.toString();
    }

    private String readToken() {
        int start = pos;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (Character.isWhitespace(c) || "{}();[".indexOf(c) >= 0) break;
            if (c == '$' && pos > start) break;
            pos++;
        }
        return text.substring(start, pos);
    }

    private static String resultToken(String token) {
        return switch (token) {
            case "1-0" -> "1-0";
            case "0-1" -> "0-1";
            case "1/2-1/2", "½-½", "1/2", "0.5-0.5" -> "1/2-1/2";
            case "*" -> "*";
            default -> null;
        };
    }

    private void skipVariation() {
        int depth = 1;
        while (pos < text.length() && depth > 0) {
            char c = text.charAt(pos);
            if (c == '{') {
                int close = text.indexOf('}', pos + 1);
                int stop = close < 0 ? text.length() : close + 1;
                countLines(stop);
                pos = stop;
                continue;
            }
            if (c == ';') {
                skipToEol();
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == '\n') line++;
            pos++;
        }
    }

    private static String normalizeComment(String body) {
        return body.replace('\r', ' ').replace('\n', ' ').replaceAll("[ \t]{2,}", " ").strip();
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            if (text.charAt(pos) == '\n') line++;
            pos++;
        }
    }

    private void skipToEol() {
        while (pos < text.length() && text.charAt(pos) != '\n') pos++;
    }

    private boolean atLineStart() {
        return pos == 0 || text.charAt(pos - 1) == '\n';
    }

    private void countLines(int until) {
        for (int i = pos; i < until && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
    }

    /** Tags of a game text without parsing the moves. */
    public static Map<String, String> tagsOf(String text) {
        return PgnText.readTags(text);
    }
}
