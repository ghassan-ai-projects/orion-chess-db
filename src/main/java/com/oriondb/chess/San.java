package com.oriondb.chess;

/** Standard Algebraic Notation: formatting and tolerant parsing. */
public final class San {
    private San() {}

    /** SAN of a legal move, including check/mate suffix. */
    public static String format(Position pos, int move) {
        String s = formatWithoutSuffix(pos, move);
        if (Move.isNull(move)) return s;
        Position after = pos.play(move);
        if (after.isCheck()) s += after.legalMoves().length == 0 ? "#" : "+";
        return s;
    }

    public static String formatWithoutSuffix(Position pos, int move) {
        if (Move.isNull(move)) return "--";
        if (Move.isCastle(move)) return Move.isKingsideCastle(move) ? "O-O" : "O-O-O";
        int from = Move.from(move), to = Move.to(move);
        int type = Piece.type(pos.pieceAt(from));
        boolean capture = pos.pieceAt(to) >= 0 || Move.isEnPassant(move);
        StringBuilder sb = new StringBuilder(7);
        if (type == Piece.PAWN) {
            if (capture) sb.append(Square.fileChar(from)).append('x');
            sb.append(Square.name(to));
            if (Move.isPromotion(move)) sb.append('=').append(Piece.typeChar(Move.promotionType(move)));
            return sb.toString();
        }
        sb.append(Piece.typeChar(type));
        boolean ambiguous = false, sameFile = false, sameRank = false;
        for (int other : pos.legalMoves()) {
            int of = Move.from(other);
            if (other == move || Move.isCastle(other) || Move.to(other) != to || of == from) continue;
            if (Piece.type(pos.pieceAt(of)) != type) continue;
            ambiguous = true;
            if (Square.file(of) == Square.file(from)) sameFile = true;
            if (Square.rank(of) == Square.rank(from)) sameRank = true;
        }
        if (ambiguous) {
            if (!sameFile) sb.append(Square.fileChar(from));
            else if (!sameRank) sb.append(Square.rankChar(from));
            else sb.append(Square.name(from));
        }
        if (capture) sb.append('x');
        sb.append(Square.name(to));
        return sb.toString();
    }

    /**
     * Parses SAN leniently: accepts check/annotation suffixes, "0-0", missing
     * or extra "=" in promotions, "e.p.", piece letter "P", long algebraic
     * ("Ng1-f3", "e2e4"), and null moves ("--", "Z0", "0000").
     *
     * @throws ChessFormatException if the text is not a legal move here
     */
    public static int parse(Position pos, String text) {
        String s = text.trim();
        // Strip annotation and check suffixes.
        int end = s.length();
        while (end > 0 && "+#!?".indexOf(s.charAt(end - 1)) >= 0) end--;
        s = s.substring(0, end);
        if (s.endsWith("e.p.")) s = s.substring(0, s.length() - 4);
        else if (s.endsWith("ep")) s = s.substring(0, s.length() - 2);
        if (s.isEmpty()) throw new ChessFormatException("empty move");

        switch (s) {
            case "--", "Z0", "0000", "@@@@", "null" -> {
                return Move.NULL;
            }
            case "O-O", "0-0", "o-o", "OO" -> {
                return castle(pos, true, text);
            }
            case "O-O-O", "0-0-0", "o-o-o", "OOO" -> {
                return castle(pos, false, text);
            }
            default -> { }
        }

        int promo = 0;
        // Trailing promotion piece: "e8=Q", "e8Q", "e8(Q)", "e8/Q", "e7e8q"
        String body = s.replace("(", "").replace(")", "").replace("/", "");
        char last = body.charAt(body.length() - 1);
        if (body.length() >= 3 && "QRBNqrbn".indexOf(last) >= 0 && Character.isDigit(body.charAt(body.length() - 2))
                || body.length() >= 4 && "QRBNqrbn".indexOf(last) >= 0 && body.charAt(body.length() - 2) == '=') {
            promo = Piece.typeFromChar(last);
            body = body.substring(0, body.length() - 1);
            if (body.endsWith("=")) body = body.substring(0, body.length() - 1);
        }

        int type = Piece.PAWN;
        int i = 0;
        char c0 = body.charAt(0);
        if ("KQRBN".indexOf(c0) >= 0 || c0 == 'P') {
            type = Piece.typeFromChar(c0);
            i = 1;
        }
        String rest = body.substring(i).replace("x", "").replace(":", "").replace("-", "");
        if (rest.length() < 2) throw new ChessFormatException("cannot parse move '" + text + "'");
        int to = Square.parse(rest.substring(rest.length() - 2));
        if (to == Square.NONE) throw new ChessFormatException("cannot parse move '" + text + "'");
        String disamb = rest.substring(0, rest.length() - 2);
        int fromFile = -1, fromRank = -1;
        for (char c : disamb.toCharArray()) {
            if (c >= 'a' && c <= 'h') fromFile = c - 'a';
            else if (c >= '1' && c <= '8') fromRank = c - '1';
            else throw new ChessFormatException("cannot parse move '" + text + "'");
        }
        boolean explicitFrom = fromFile >= 0 && fromRank >= 0;

        int found = Move.NONE;
        for (int m : pos.legalMoves()) {
            int from = Move.from(m);
            int mType = Piece.type(pos.pieceAt(from));
            int dest = Move.isCastle(m) ? Move.kingTarget(m) : Move.to(m);
            boolean matches;
            if (Move.isCastle(m)) {
                // "Kg1"/"e1g1" style castling, or king-takes-rook "e1h1"
                matches = (type == Piece.KING || explicitFrom) && (dest == to || (explicitFrom && Move.to(m) == to));
            } else {
                matches = mType == type || (explicitFrom && i == 0);
                matches &= Move.to(m) == to;
            }
            if (!matches) continue;
            if (fromFile >= 0 && Square.file(from) != fromFile) continue;
            if (fromRank >= 0 && Square.rank(from) != fromRank) continue;
            int mPromo = Move.promotionType(m);
            if (mPromo != 0 && mPromo != (promo == 0 ? Piece.QUEEN : promo)) continue;
            if (mPromo == 0 && promo != 0) continue;
            if (found != Move.NONE && found != m) {
                // A pawn move and a castling both matching "e1g1"-like input cannot happen; anything else is ambiguous.
                throw new ChessFormatException("ambiguous move '" + text + "'");
            }
            found = m;
        }
        if (found == Move.NONE) throw new ChessFormatException("illegal move '" + text + "' in " + pos.fen());
        return found;
    }

    private static int castle(Position pos, boolean kingside, String text) {
        for (int m : pos.legalMoves()) {
            if (Move.isCastle(m) && Move.isKingsideCastle(m) == kingside) return m;
        }
        throw new ChessFormatException("illegal castling '" + text + "' in " + pos.fen());
    }
}
