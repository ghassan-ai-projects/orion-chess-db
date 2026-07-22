package com.oriondb.pgn;

import com.oriondb.chess.Board;
import com.oriondb.chess.Move;
import com.oriondb.model.Game;
import com.oriondb.model.MoveNode;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Spec-compliant, error-tolerant PGN reader.
 *
 * Preserves everything: variations (RAV), comments ({...} and ;), NAGs
 * ($n and !/? suffixes), game comments, and non-standard tags. Handles
 * escape lines (%), glued move numbers ("1.e4"), castling with zeros,
 * tag-value escapes, and games with FEN/SetUp start positions.
 *
 * With validation enabled (the default), every move is checked against the
 * legal move generator and rewritten in canonical SAN. Illegal mainline
 * moves truncate the game at that point with a warning; illegal variation
 * moves truncate that variation. Games that fail entirely are skipped, and
 * parsing always continues with the next game.
 */
public final class PgnReader {

    /** Result of a parse: the games plus any per-game warnings. */
    public static final class ParseResult {
        private final List<Game> games;
        private final List<String> warnings;

        ParseResult(List<Game> games, List<String> warnings) {
            this.games = games;
            this.warnings = warnings;
        }

        public List<Game> games() {
            return games;
        }

        public List<String> warnings() {
            return warnings;
        }
    }

    private final boolean validate;

    public PgnReader() {
        this(true);
    }

    public PgnReader(boolean validate) {
        this.validate = validate;
    }

    public ParseResult readFile(Path path) throws IOException {
        return readString(readFileText(path));
    }

    /**
     * Read a PGN file as text: UTF-8 when valid, otherwise Latin-1
     * (the historical PGN encoding, common in older collections).
     * A UTF-8 BOM is stripped.
     */
    public static String readFileText(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException e) {
            text = new String(bytes, StandardCharsets.ISO_8859_1);
        }
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        return text;
    }

    public ParseResult read(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = reader.read(buf)) != -1) {
            sb.append(buf, 0, n);
        }
        return readString(sb.toString());
    }

    public ParseResult readString(String pgn) {
        return new Parser(pgn, validate).parseAll();
    }

    // ==================================================================
    // Tokenizer + parser
    // ==================================================================

    private enum TokenType { TAG, SAN, NUMBER, NAG, COMMENT, LPAREN, RPAREN, TERMINATOR, EOF }

    private static final class Token {
        final TokenType type;
        final String text;       // SAN text, comment text, terminator
        final String tagKey;     // TAG only
        final String tagValue;   // TAG only
        final int nag;           // NAG only

        Token(TokenType type, String text, String tagKey, String tagValue, int nag) {
            this.type = type;
            this.text = text;
            this.tagKey = tagKey;
            this.tagValue = tagValue;
            this.nag = nag;
        }

        static Token of(TokenType type, String text) {
            return new Token(type, text, null, null, 0);
        }

        static Token tag(String key, String value) {
            return new Token(TokenType.TAG, null, key, value, 0);
        }

        static Token nag(int n) {
            return new Token(TokenType.NAG, null, null, null, n);
        }
    }

    private static final class Parser {
        private final String text;
        private final boolean validate;
        private int pos;
        private final List<Game> games = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private Token pushedBack;
        private int gameNumber;          // 1-based ordinal in the file, for warnings

        Parser(String pgn, boolean validate) {
            // Strip escape lines (lines whose first character is '%').
            if (pgn.indexOf('%') != -1) {
                StringBuilder sb = new StringBuilder(pgn.length());
                for (String line : pgn.split("\n", -1)) {
                    if (!line.startsWith("%")) {
                        sb.append(line).append('\n');
                    }
                }
                pgn = sb.toString();
            }
            this.text = pgn;
            this.validate = validate;
        }

        ParseResult parseAll() {
            while (true) {
                Game game = parseGame();
                if (game == null) break;
                games.add(game);
            }
            return new ParseResult(games, warnings);
        }

        private void warn(String message) {
            warnings.add("game " + gameNumber + ": " + message);
        }

        // --------------------------------------------------------------
        // Game assembly
        // --------------------------------------------------------------

        private Game parseGame() {
            // Collect tags
            LinkedHashMap<String, String> tags = new LinkedHashMap<>();
            Token token = nextToken();
            if (token.type == TokenType.EOF) return null;
            gameNumber++;

            boolean sawAnything = false;
            while (token.type == TokenType.TAG) {
                sawAnything = true;
                tags.put(token.tagKey, token.tagValue);
                token = nextToken();
            }
            if (token.type == TokenType.EOF) {
                return sawAnything ? finishGame(tags, null, null, "*") : null;
            }
            pushBack(token);

            // Starting position (FEN games)
            Board startBoard = null;
            if (validate) {
                String fen = tags.get("FEN");
                if (fen != null && !fen.isBlank()) {
                    try {
                        startBoard = Board.fromFen(fen);
                    } catch (IllegalArgumentException e) {
                        warn("invalid FEN tag, treating moves as unverified: " + e.getMessage());
                    }
                } else {
                    startBoard = Board.startingPosition();
                }
            }

            // Movetext
            LineResult line = parseLine(startBoard, true);
            return finishGame(tags, line.first, line.leadingComment, line.terminator);
        }

        private Game finishGame(LinkedHashMap<String, String> tags, MoveNode root,
                                String gameComment, String terminator) {
            Game game = new Game(tags, root);
            game.setGameComment(gameComment);

            String tagResult = tags.get("Result");
            if (terminator == null) terminator = "*";
            if (tagResult == null || tagResult.equals("*") || tagResult.isBlank()) {
                game.setTag("Result", terminator);
            } else if (!terminator.equals("*") && !tagResult.equals(terminator)) {
                warn("Result tag '" + tagResult + "' disagrees with movetext terminator '"
                    + terminator + "'; keeping the tag");
            }
            // Guarantee the Seven Tag Roster
            if (!tags.containsKey("Event")) game.setTag("Event", "?");
            if (!tags.containsKey("Site")) game.setTag("Site", "?");
            if (!tags.containsKey("Date")) game.setTag("Date", Game.UNKNOWN_DATE);
            if (!tags.containsKey("Round")) game.setTag("Round", "?");
            if (!tags.containsKey("White")) game.setTag("White", "?");
            if (!tags.containsKey("Black")) game.setTag("Black", "?");
            return game;
        }

        // --------------------------------------------------------------
        // Movetext lines (mainline and variations)
        // --------------------------------------------------------------

        private static final class LineResult {
            MoveNode first;
            String leadingComment;   // comment before the first move
            String terminator;       // mainline only
        }

        /**
         * Parse one line of moves. For the mainline, ends at a game
         * terminator or a structural boundary (new game's '[', EOF). For a
         * variation, ends at the matching ')'.
         *
         * {@code board} is the position before the line's first move, or
         * null when validation is off.
         */
        private LineResult parseLine(Board board, boolean mainline) {
            LineResult result = new LineResult();
            MoveNode last = null;
            Board beforeLast = null;      // for variations attached to 'last'
            boolean skipping = false;     // after an illegal move: structurally consume, keep nothing

            while (true) {
                Token token = nextToken();
                switch (token.type) {
                    case EOF:
                        if (mainline) {
                            return result;
                        }
                        warn("unterminated variation at end of input");
                        return result;

                    case TAG:
                        // A tag while reading movetext means the previous game
                        // had no terminator: hand it to the next parseGame.
                        pushBack(token);
                        if (mainline) {
                            warn("game has no result terminator");
                            return result;
                        }
                        warn("unterminated variation before next game");
                        return result;

                    case TERMINATOR:
                        if (mainline) {
                            result.terminator = token.text;
                            return result;
                        }
                        // A terminator inside a variation is malformed; treat
                        // it as ending everything by pushing it back.
                        warn("game terminator inside a variation");
                        pushBack(token);
                        return result;

                    case RPAREN:
                        if (!mainline) {
                            return result;
                        }
                        warn("unmatched ')' in movetext ignored");
                        break;

                    case LPAREN: {
                        if (skipping || last == null) {
                            if (!skipping) warn("variation with no preceding move ignored");
                            consumeVariation();
                            break;
                        }
                        // A variation is an alternative to 'last', so it
                        // starts from the position before 'last'.
                        LineResult variation = parseLine(beforeLast, false);
                        if (variation.first != null) {
                            if (variation.leadingComment != null) {
                                variation.first.setCommentBefore(variation.leadingComment);
                            }
                            last.addVariation(variation.first);
                        }
                        break;
                    }

                    case COMMENT:
                        if (skipping) break;
                        if (last == null) {
                            // Before any move of this line
                            result.leadingComment = append(result.leadingComment, token.text);
                        } else {
                            // A comment right after a move belongs to that move.
                            last.appendComment(token.text);
                        }
                        break;

                    case NAG:
                        if (!skipping && last != null) {
                            last.addNag(token.nag);
                        }
                        break;

                    case NUMBER:
                        break; // move numbers carry no information

                    case SAN: {
                        if (skipping) break;
                        String san = token.text;
                        int suffixNag = suffixNag(san);
                        MoveNode node;
                        Board after = null;
                        if (board != null) {
                            int move = board.parseSan(san);
                            if (move == Move.NONE) {
                                warn("illegal or unparseable move '" + san + "' at "
                                    + describePly(board) + (mainline
                                        ? "; rest of game discarded"
                                        : "; rest of variation discarded"));
                                if (mainline) {
                                    skipping = true;
                                    break;
                                }
                                consumeRestOfVariation();
                                return result;
                            }
                            node = new MoveNode(board.toSan(move));
                            after = board.play(move);
                        } else {
                            node = new MoveNode(san);
                        }
                        if (suffixNag != 0) {
                            node.addNag(suffixNag);
                        }
                        if (last == null) {
                            result.first = node;
                        } else {
                            last.setNext(node);
                        }
                        beforeLast = board;
                        board = after;
                        last = node;
                        break;
                    }

                    default:
                        break;
                }
            }
        }

        /** Traditional !/? suffix on a SAN token as its NAG number, or 0. */
        private static int suffixNag(String san) {
            int end = san.length();
            while (end > 0 && (san.charAt(end - 1) == '+' || san.charAt(end - 1) == '#')) {
                end--; // ignore check markers after the suffix, e.g. rare "e4!+" forms
            }
            int start = end;
            while (start > 0 && (san.charAt(start - 1) == '!' || san.charAt(start - 1) == '?')) {
                start--;
            }
            switch (san.substring(start, end)) {
                case "!": return 1;
                case "?": return 2;
                case "!!": return 3;
                case "??": return 4;
                case "!?": return 5;
                case "?!": return 6;
                default: return 0;
            }
        }

        private static String append(String base, String extra) {
            if (extra == null || extra.isBlank()) return base;
            String cleaned = extra.replaceAll("\\s+", " ").trim();
            return base == null ? cleaned : base + " " + cleaned;
        }

        private String describePly(Board board) {
            return "move " + board.fullMoveNumber() + (board.whiteToMove() ? "" : "...");
        }

        /** Consume tokens up to and including the matching ')'. */
        private void consumeVariation() {
            int depth = 1;
            while (depth > 0) {
                Token token = nextToken();
                if (token.type == TokenType.EOF) return;
                if (token.type == TokenType.TAG) {
                    pushBack(token);
                    return;
                }
                if (token.type == TokenType.LPAREN) depth++;
                else if (token.type == TokenType.RPAREN) depth--;
            }
        }

        /** After an illegal variation move: consume the rest of this variation (depth 1). */
        private void consumeRestOfVariation() {
            consumeVariation();
        }

        // --------------------------------------------------------------
        // Lexer
        // --------------------------------------------------------------

        private void pushBack(Token token) {
            pushedBack = token;
        }

        private Token nextToken() {
            if (pushedBack != null) {
                Token t = pushedBack;
                pushedBack = null;
                return t;
            }
            skipWhitespace();
            if (pos >= text.length()) return Token.of(TokenType.EOF, null);

            char c = text.charAt(pos);
            switch (c) {
                case '[': return lexTag();
                case '{': return lexBraceComment();
                case ';': return lexLineComment();
                case '(': pos++; return Token.of(TokenType.LPAREN, "(");
                case ')': pos++; return Token.of(TokenType.RPAREN, ")");
                case '$': return lexNag();
                case '*': pos++; return Token.of(TokenType.TERMINATOR, "*");
                default: return lexWord();
            }
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private Token lexTag() {
            int start = pos;
            pos++; // '['
            skipWhitespace();
            // Symbol
            int symStart = pos;
            while (pos < text.length()
                    && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) {
                pos++;
            }
            String key = text.substring(symStart, pos);
            skipWhitespace();
            if (key.isEmpty() || pos >= text.length() || text.charAt(pos) != '"') {
                // Not a tag pair; skip to end of line and ignore.
                warn("malformed tag pair ignored");
                skipToLineEnd(start);
                return nextToken();
            }
            pos++; // '"'
            StringBuilder value = new StringBuilder();
            while (pos < text.length() && text.charAt(pos) != '"') {
                char ch = text.charAt(pos);
                if (ch == '\\' && pos + 1 < text.length()) {
                    pos++;
                    value.append(text.charAt(pos));
                } else {
                    value.append(ch);
                }
                pos++;
            }
            if (pos < text.length()) pos++; // closing '"'
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == ']') {
                pos++;
            }
            return Token.tag(key, value.toString());
        }

        private void skipToLineEnd(int from) {
            pos = from;
            while (pos < text.length() && text.charAt(pos) != '\n') {
                pos++;
            }
        }

        private Token lexBraceComment() {
            pos++; // '{'
            int start = pos;
            while (pos < text.length() && text.charAt(pos) != '}') {
                pos++;
            }
            String comment = text.substring(start, Math.min(pos, text.length()));
            if (pos < text.length()) pos++; // '}'
            return Token.of(TokenType.COMMENT, comment);
        }

        private Token lexLineComment() {
            pos++; // ';'
            int start = pos;
            while (pos < text.length() && text.charAt(pos) != '\n') {
                pos++;
            }
            return Token.of(TokenType.COMMENT, text.substring(start, pos));
        }

        private Token lexNag() {
            pos++; // '$'
            int start = pos;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (start == pos) return nextToken(); // stray '$'
            try {
                return Token.nag(Integer.parseInt(text.substring(start, pos)));
            } catch (NumberFormatException e) {
                return nextToken();
            }
        }

        private Token lexWord() {
            int start = pos;
            while (pos < text.length() && !isWordBoundary(text.charAt(pos))) {
                pos++;
            }
            String word = text.substring(start, pos);
            if (word.isEmpty()) {
                pos++; // safety: never stall
                return nextToken();
            }
            return classifyWord(word);
        }

        private static boolean isWordBoundary(char c) {
            return Character.isWhitespace(c) || c == '(' || c == ')' || c == '{' || c == '}'
                || c == ';' || c == '$' || c == '[' || c == ']';
        }

        private Token classifyWord(String word) {
            // Game terminators (also tolerate a bare "1/2")
            switch (word) {
                case "1-0":
                case "0-1":
                    return Token.of(TokenType.TERMINATOR, word);
                case "1/2-1/2":
                case "1/2":
                    return Token.of(TokenType.TERMINATOR, "1/2-1/2");
                default:
                    break;
            }
            // Castling written with zeros must not be treated as a number
            if (word.startsWith("0-0")) {
                return Token.of(TokenType.SAN, word);
            }
            char first = word.charAt(0);
            if (Character.isDigit(first)) {
                // Move number, possibly glued to a move: "1.e4", "12...Nf6"
                int i = 0;
                while (i < word.length() && Character.isDigit(word.charAt(i))) i++;
                while (i < word.length() && word.charAt(i) == '.') i++;
                String rest = word.substring(i);
                if (rest.isEmpty()) {
                    return Token.of(TokenType.NUMBER, word);
                }
                return classifyWord(rest); // glued SAN (or "0-0" after "12.")
            }
            return Token.of(TokenType.SAN, word);
        }
    }
}
