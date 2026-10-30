package com.oriondb.query;

import com.oriondb.chess.Move;
import com.oriondb.chess.Piece;
import com.oriondb.chess.Position;
import com.oriondb.chess.Square;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A move described in SAN-like terms and matched by meaning, not text:
 * {@code Bxh7+} is "a bishop captures on h7 giving check". Parts:
 * <ul>
 *   <li>piece letter (none = pawn; {@code P} also pawn), optional from-file/rank</li>
 *   <li>{@code x} requires a capture (without it, captures still match)</li>
 *   <li>destination square, or {@code *} for any square ("Q*" = any queen move)</li>
 *   <li>{@code =Q} promotion piece ({@code =*} any promotion)</li>
 *   <li>{@code +} requires check, {@code #} requires mate</li>
 *   <li>{@code O-O} / {@code O-O-O} castling</li>
 * </ul>
 */
public final class MovePattern {
    private static final Pattern SYNTAX = Pattern.compile(
        "^([KQRBNP])?([a-h])?([1-8])?(x)?([a-h][1-8]|\\*)(?:=?([QRBN*]))?([+#])?$");

    private final String text;
    private final int castle;      // 0 no, 1 kingside, 2 queenside
    private final int piece;       // type, or -1 for castling
    private final int fromFile, fromRank, to; // -1 = any
    private final boolean capture;
    private final int promotion;   // 0 none required, -1 any, else type
    private final int check;       // 0 any, 1 check, 2 mate

    private MovePattern(String text, int castle, int piece, int fromFile, int fromRank, int to,
                        boolean capture, int promotion, int check) {
        this.text = text;
        this.castle = castle;
        this.piece = piece;
        this.fromFile = fromFile;
        this.fromRank = fromRank;
        this.to = to;
        this.capture = capture;
        this.promotion = promotion;
        this.check = check;
    }

    public static MovePattern parse(String text) {
        String s = text.strip().replace("0", "O");
        int check = s.endsWith("#") ? 2 : s.endsWith("+") ? 1 : 0;
        String core = check > 0 ? s.substring(0, s.length() - 1) : s;
        if (core.equals("O-O") || core.equals("O-O-O")) {
            return new MovePattern(text.strip(), core.length() == 3 ? 1 : 2, -1, -1, -1, -1, false, 0, check);
        }
        Matcher m = SYNTAX.matcher(text.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("bad move pattern '" + text
                + "' (use SAN like Bxh7+, Nd5, exd5, e8=Q, Q* for any queen move, O-O)");
        }
        int piece = m.group(1) == null ? Piece.PAWN : Piece.typeFromChar(m.group(1).charAt(0));
        int fromFile = m.group(2) == null ? -1 : m.group(2).charAt(0) - 'a';
        int fromRank = m.group(3) == null ? -1 : m.group(3).charAt(0) - '1';
        int to = m.group(5).equals("*") ? -1 : Square.parse(m.group(5));
        int promo = m.group(6) == null ? 0 : m.group(6).equals("*") ? -1 : Piece.typeFromChar(m.group(6).charAt(0));
        return new MovePattern(text.strip(), 0, piece, fromFile, fromRank, to, m.group(4) != null, promo, check);
    }

    /** A space-separated list, e.g. "Bxh7+ Kxh7 Ng5+" (move numbers ignored). */
    public static List<MovePattern> parseList(String text) {
        List<MovePattern> out = new ArrayList<>();
        for (String t : text.trim().split("\\s+")) {
            String tok = t.replaceFirst("^\\d+\\.+", "");
            if (!tok.isEmpty()) out.add(parse(tok));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("empty move list");
        return out;
    }

    /** Does {@code move}, played in {@code before} (reaching {@code after}), fit this pattern? */
    public boolean matches(Position before, int move, Position after) {
        if (Move.isNull(move)) return false;
        if (castle != 0) {
            if (!Move.isCastle(move) || Move.isKingsideCastle(move) != (castle == 1)) return false;
            return checkOk(after);
        }
        if (Move.isCastle(move)) return false;
        int from = Move.from(move);
        if (Piece.type(before.pieceAt(from)) != piece) return false;
        if (to >= 0 && Move.to(move) != to) return false;
        if (fromFile >= 0 && Square.file(from) != fromFile) return false;
        if (fromRank >= 0 && Square.rank(from) != fromRank) return false;
        if (capture && before.pieceAt(Move.to(move)) < 0 && !Move.isEnPassant(move)) return false;
        if (promotion == -1 && !Move.isPromotion(move)) return false;
        if (promotion > 0 && Move.promotionType(move) != promotion) return false;
        return checkOk(after);
    }

    private boolean checkOk(Position after) {
        if (check == 0) return true;
        if (!after.isCheck()) return false;
        return check == 1 || after.legalMoves().length == 0;
    }

    @Override
    public String toString() {
        return text;
    }
}
