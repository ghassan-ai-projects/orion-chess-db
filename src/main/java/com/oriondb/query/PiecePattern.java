package com.oriondb.query;

import com.oriondb.chess.Piece;
import com.oriondb.chess.Position;
import com.oriondb.chess.Square;

import java.util.ArrayList;
import java.util.List;

/**
 * A partial-board pattern matched against positions, e.g.
 * {@code "Kg1 kg8 Pe5 -pd5 .f7 R*7 w"}:
 * <ul>
 *   <li>{@code Pe5} white pawn on e5; lowercase letters are black pieces</li>
 *   <li>{@code *} as file or rank is a wildcard: {@code R*7} a white rook anywhere on the 7th rank</li>
 *   <li>{@code -pd5} no black pawn on d5; {@code .f7} f7 is empty</li>
 *   <li>{@code w} / {@code b} side to move</li>
 * </ul>
 */
public final class PiecePattern {
    private record Term(int piece, long squares, boolean negated, boolean empty) {}

    private final List<Term> terms;
    private final int sideToMove; // -1 = either
    private final String text;

    private PiecePattern(List<Term> terms, int sideToMove, String text) {
        this.terms = terms;
        this.sideToMove = sideToMove;
        this.text = text;
    }

    public static PiecePattern parse(String text) {
        List<Term> terms = new ArrayList<>();
        int stm = -1;
        for (String raw : text.trim().split("[\\s,]+")) {
            if (raw.isEmpty()) continue;
            if (raw.equals("w") || raw.equals("b")) {
                stm = raw.equals("w") ? Piece.WHITE : Piece.BLACK;
                continue;
            }
            boolean neg = raw.startsWith("-") || raw.startsWith("!");
            String t = neg ? raw.substring(1) : raw;
            if (t.length() != 3) throw bad(raw);
            boolean empty = t.charAt(0) == '.';
            int piece = empty ? Piece.NONE : Piece.fromFenChar(t.charAt(0));
            if (!empty && piece == Piece.NONE) throw bad(raw);
            long squares = squares(t.charAt(1), t.charAt(2), raw);
            terms.add(new Term(piece, squares, neg, empty));
        }
        if (terms.isEmpty() && stm < 0) throw new IllegalArgumentException("empty pattern");
        return new PiecePattern(List.copyOf(terms), stm, text.trim());
    }

    private static long squares(char f, char r, String raw) {
        long mask = 0;
        for (int file = 0; file < 8; file++) {
            if (f != '*' && f - 'a' != file) continue;
            for (int rank = 0; rank < 8; rank++) {
                if (r != '*' && r - '1' != rank) continue;
                mask |= Square.bit(Square.of(file, rank));
            }
        }
        if (mask == 0) throw bad(raw);
        return mask;
    }

    private static IllegalArgumentException bad(String token) {
        return new IllegalArgumentException("bad pattern term '" + token
            + "' (use e.g. Pe5, kg8, R*7, -pd5, .f7, w)");
    }

    public boolean matches(Position p) {
        if (sideToMove >= 0 && p.sideToMove() != sideToMove) return false;
        for (Term t : terms) {
            boolean hit = t.empty ? (p.occupied() & t.squares) == 0 : (p.pieces(t.piece) & t.squares) != 0;
            if (hit == t.negated) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return text;
    }
}
