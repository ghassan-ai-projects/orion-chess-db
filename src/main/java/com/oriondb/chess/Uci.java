package com.oriondb.chess;

/** UCI long algebraic notation ("e2e4", "e7e8q"). */
public final class Uci {
    private Uci() {}

    /**
     * UCI text of a move. Castling is "e1g1" in standard chess and
     * king-takes-rook ("e1h1") when {@code chess960} is set.
     */
    public static String format(int move, boolean chess960) {
        if (Move.isNull(move)) return "0000";
        int to = Move.isCastle(move) && !chess960 ? Move.kingTarget(move) : Move.to(move);
        String s = Square.name(Move.from(move)) + Square.name(to);
        if (Move.isPromotion(move)) s += Character.toLowerCase(Piece.typeChar(Move.promotionType(move)));
        return s;
    }

    /** Parses UCI text (both castling conventions accepted). */
    public static int parse(Position pos, String text) {
        String s = text.trim();
        if (s.equals("0000")) return Move.NULL;
        if (s.length() < 4 || s.length() > 5) throw new ChessFormatException("bad UCI move '" + text + "'");
        int from = Square.parse(s.substring(0, 2)), to = Square.parse(s.substring(2, 4));
        int promo = s.length() == 5 ? Piece.typeFromChar(s.charAt(4)) : 0;
        if (from == Square.NONE || to == Square.NONE || promo < 0) throw new ChessFormatException("bad UCI move '" + text + "'");
        for (int m : pos.legalMoves()) {
            if (Move.from(m) != from || Move.promotionType(m) != promo) continue;
            if (Move.to(m) == to || (Move.isCastle(m) && Move.kingTarget(m) == to
                    && Piece.type(pos.pieceAt(to)) != Piece.ROOK)) {
                return m;
            }
        }
        throw new ChessFormatException("illegal move '" + text + "' in " + pos.fen());
    }
}
