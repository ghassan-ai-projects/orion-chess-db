package com.oriondb.chess;

import java.util.Arrays;

/**
 * An immutable chess position with full legal move generation (standard
 * chess and Chess960 castling). {@link #play(int)} returns a new position.
 *
 * <p>The en passant square is stored only when a pawn of the side to move
 * can actually capture there, so positions reached by different move orders
 * compare (and hash) equal.
 */
public final class Position {
    public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    /** Castling slots: white O-O, white O-O-O, black O-O, black O-O-O (also the Zobrist mask bits). */
    public static final int WHITE_OO = 0, WHITE_OOO = 1, BLACK_OO = 2, BLACK_OOO = 3;

    private static final Position START = fromFen(START_FEN);

    private final long[] bb;        // per piece code 0..11
    private final long[] byColor;   // per color
    private final byte[] board;     // piece code or -1
    private int side;
    /** Four 8-bit slots, each rook square + 1 (0 = right lost). */
    private int castling;
    private int ep = Square.NONE;
    private int halfmove;
    private int fullmove = 1;
    private long key;
    private int[] legalCache;

    private Position() {
        bb = new long[12];
        byColor = new long[2];
        board = new byte[64];
        Arrays.fill(board, (byte) -1);
    }

    private Position(Position o) {
        bb = o.bb.clone();
        byColor = o.byColor.clone();
        board = o.board.clone();
        side = o.side;
        castling = o.castling;
        ep = o.ep;
        halfmove = o.halfmove;
        fullmove = o.fullmove;
        key = o.key;
    }

    public static Position start() {
        return START;
    }

    // ---------------------------------------------------------------- FEN

    /** Parses FEN (also X-FEN / Shredder-FEN castling). Missing trailing fields get defaults. */
    public static Position fromFen(String fen) {
        if (fen == null || fen.isBlank()) throw new ChessFormatException("empty FEN");
        String[] parts = fen.trim().split("\\s+");
        Position p = new Position();
        String[] ranks = parts[0].split("/");
        if (ranks.length != 8) throw new ChessFormatException("FEN must have 8 ranks: " + fen);
        for (int i = 0; i < 8; i++) {
            int rank = 7 - i, file = 0;
            for (char c : ranks[i].toCharArray()) {
                if (c >= '1' && c <= '8') {
                    file += c - '0';
                } else {
                    int piece = Piece.fromFenChar(c);
                    if (piece == Piece.NONE || file > 7) throw new ChessFormatException("bad FEN rank '" + ranks[i] + "'");
                    p.put(piece, Square.of(file++, rank));
                }
                if (file > 8) throw new ChessFormatException("bad FEN rank '" + ranks[i] + "'");
            }
            if (file != 8) throw new ChessFormatException("FEN rank '" + ranks[i] + "' does not have 8 squares");
        }
        String stm = parts.length > 1 ? parts[1] : "w";
        if (stm.equals("w")) p.side = Piece.WHITE;
        else if (stm.equals("b")) p.side = Piece.BLACK;
        else throw new ChessFormatException("bad side to move '" + stm + "'");

        for (int c = 0; c < 2; c++) {
            if (Long.bitCount(p.bb[Piece.make(c, Piece.KING)]) != 1) {
                throw new ChessFormatException(Piece.colorName(c) + " must have exactly one king");
            }
        }
        if (((p.bb[Piece.W_PAWN] | p.bb[Piece.B_PAWN]) & (Bitboards.RANK_1 | Bitboards.RANK_8)) != 0) {
            throw new ChessFormatException("pawns on the first or last rank");
        }
        if (p.isAttacked(p.kingSquare(p.side ^ 1), p.side, p.occupied())) {
            throw new ChessFormatException("side not to move is in check");
        }
        if (parts.length > 2) p.parseCastling(parts[2]);
        if (parts.length > 3 && !parts[3].equals("-")) {
            int sq = Square.parse(parts[3]);
            if (sq == Square.NONE) throw new ChessFormatException("bad en passant square '" + parts[3] + "'");
            p.ep = p.capturableEp(sq, p.side) ? sq : Square.NONE;
        }
        try {
            if (parts.length > 4) p.halfmove = Math.max(0, Integer.parseInt(parts[4]));
            if (parts.length > 5) p.fullmove = Math.max(1, Integer.parseInt(parts[5]));
        } catch (NumberFormatException e) {
            throw new ChessFormatException("bad move counters in FEN: " + fen);
        }
        p.key = p.computeKey();
        return p;
    }

    private void parseCastling(String s) {
        if (s.equals("-")) return;
        for (char c : s.toCharArray()) {
            int color = Character.isUpperCase(c) ? Piece.WHITE : Piece.BLACK;
            int king = kingSquare(color);
            int backRank = color == Piece.WHITE ? 0 : 7;
            if (Square.rank(king) != backRank) continue;
            long rooks = bb[Piece.make(color, Piece.ROOK)] & (Bitboards.RANK_1 << (8 * backRank));
            char u = Character.toUpperCase(c);
            int rook = Square.NONE;
            if (u == 'K') {
                for (int f = 7; f > Square.file(king); f--) {
                    if ((rooks & Square.bit(Square.of(f, backRank))) != 0) { rook = Square.of(f, backRank); break; }
                }
            } else if (u == 'Q') {
                for (int f = 0; f < Square.file(king); f++) {
                    if ((rooks & Square.bit(Square.of(f, backRank))) != 0) { rook = Square.of(f, backRank); break; }
                }
            } else if (u >= 'A' && u <= 'H') {
                int sq = Square.of(u - 'A', backRank);
                if ((rooks & Square.bit(sq)) != 0) rook = sq;
            } else {
                throw new ChessFormatException("bad castling field '" + s + "'");
            }
            if (rook == Square.NONE) continue; // tolerate rights without a rook
            int slot = color * 2 + (Square.file(rook) > Square.file(king) ? 0 : 1);
            setCastleRook(slot, rook);
        }
    }

    public String fen() {
        StringBuilder sb = new StringBuilder(90);
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                int piece = board[Square.of(file, rank)];
                if (piece < 0) { empty++; continue; }
                if (empty > 0) { sb.append(empty); empty = 0; }
                sb.append(Piece.fenChar(piece));
            }
            if (empty > 0) sb.append(empty);
            if (rank > 0) sb.append('/');
        }
        sb.append(side == Piece.WHITE ? " w " : " b ").append(castlingField()).append(' ')
            .append(Square.name(ep)).append(' ').append(halfmove).append(' ').append(fullmove);
        return sb.toString();
    }

    /** FEN castling field: KQkq for outermost rooks, file letters (Shredder style) otherwise. */
    public String castlingField() {
        StringBuilder sb = new StringBuilder(4);
        for (int slot = 0; slot < 4; slot++) {
            int rook = castleRook(slot);
            if (rook == Square.NONE) continue;
            int color = slot / 2;
            boolean kingside = slot % 2 == 0;
            boolean outermost = true;
            long rooks = bb[Piece.make(color, Piece.ROOK)];
            for (int f = Square.file(rook) + (kingside ? 1 : -1); f >= 0 && f < 8; f += kingside ? 1 : -1) {
                if ((rooks & Square.bit(Square.of(f, Square.rank(rook)))) != 0) outermost = false;
            }
            char c = outermost ? (kingside ? 'K' : 'Q') : (char) ('A' + Square.file(rook));
            sb.append(color == Piece.WHITE ? c : Character.toLowerCase(c));
        }
        return sb.isEmpty() ? "-" : sb.toString();
    }

    /** FEN without the move counters, useful as a position identity string. */
    public String epd() {
        String f = fen();
        int cut = f.lastIndexOf(' ', f.lastIndexOf(' ') - 1);
        return f.substring(0, cut);
    }

    // ---------------------------------------------------------------- queries

    public int pieceAt(int sq) {
        return board[sq];
    }

    public int sideToMove() {
        return side;
    }

    public long pieces(int piece) {
        return bb[piece];
    }

    public long pieces(int color, int type) {
        return bb[Piece.make(color, type)];
    }

    public long occupied(int color) {
        return byColor[color];
    }

    public long occupied() {
        return byColor[0] | byColor[1];
    }

    public int kingSquare(int color) {
        return Long.numberOfTrailingZeros(bb[Piece.make(color, Piece.KING)]);
    }

    public int epSquare() {
        return ep;
    }

    public int halfmoveClock() {
        return halfmove;
    }

    public int fullmoveNumber() {
        return fullmove;
    }

    /** Half-moves since the start of a game beginning at move 1 with white. */
    public int ply() {
        return 2 * (fullmove - 1) + side;
    }

    public long hash() {
        return key;
    }

    /** Rook square for a castling slot ({@link #WHITE_OO}...), or {@link Square#NONE}. */
    public int castleRook(int slot) {
        return ((castling >>> (slot * 8)) & 0xFF) - 1;
    }

    /** Bit i set when castling slot i is still available. */
    public int castlingRights() {
        int mask = 0;
        for (int slot = 0; slot < 4; slot++) if (castleRook(slot) != Square.NONE) mask |= 1 << slot;
        return mask;
    }

    /** True if castling rights use non-standard rook or king squares (Chess960). */
    public boolean hasChess960Castling() {
        for (int slot = 0; slot < 4; slot++) {
            int rook = castleRook(slot);
            if (rook == Square.NONE) continue;
            int color = slot / 2;
            if (Square.file(kingSquare(color)) != 4 || Square.file(rook) != (slot % 2 == 0 ? 7 : 0)) return true;
        }
        return false;
    }

    /** All pieces (both colors) attacking {@code sq} given occupancy {@code occ}. */
    public long attackersTo(int sq, long occ) {
        long rq = bb[Piece.W_ROOK] | bb[Piece.B_ROOK] | bb[Piece.W_QUEEN] | bb[Piece.B_QUEEN];
        long bq = bb[Piece.W_BISHOP] | bb[Piece.B_BISHOP] | bb[Piece.W_QUEEN] | bb[Piece.B_QUEEN];
        return (Bitboards.pawnAttacks(Piece.WHITE, sq) & bb[Piece.B_PAWN])
            | (Bitboards.pawnAttacks(Piece.BLACK, sq) & bb[Piece.W_PAWN])
            | (Bitboards.knightAttacks(sq) & (bb[Piece.W_KNIGHT] | bb[Piece.B_KNIGHT]))
            | (Bitboards.kingAttacks(sq) & (bb[Piece.W_KING] | bb[Piece.B_KING]))
            | (Bitboards.rookAttacks(sq, occ) & rq)
            | (Bitboards.bishopAttacks(sq, occ) & bq);
    }

    /** Pieces of {@code color} attacking {@code sq}. */
    public long attackers(int sq, int color) {
        return attackersTo(sq, occupied()) & byColor[color];
    }

    public boolean isAttacked(int sq, int byColorIdx) {
        return isAttacked(sq, byColorIdx, occupied());
    }

    private boolean isAttacked(int sq, int by, long occ) {
        return (attackersTo(sq, occ) & byColor[by]) != 0;
    }

    /** Squares attacked by the piece on {@code sq} (empty set for an empty square). */
    public long attacksFrom(int sq) {
        int piece = board[sq];
        if (piece < 0) return 0;
        int type = Piece.type(piece);
        return type == Piece.PAWN ? Bitboards.pawnAttacks(Piece.color(piece), sq)
            : Bitboards.attacks(type, sq, occupied());
    }

    /** Pieces giving check to the side to move. */
    public long checkers() {
        return attackers(kingSquare(side), side ^ 1);
    }

    public boolean isCheck() {
        return isAttacked(kingSquare(side), side ^ 1);
    }

    public boolean isCheckmate() {
        return isCheck() && legalMoves().length == 0;
    }

    public boolean isStalemate() {
        return !isCheck() && legalMoves().length == 0;
    }

    /** Neither side can possibly mate (K vs K, K+minor vs K, bishops all on one color). */
    public boolean isInsufficientMaterial() {
        long heavy = bb[Piece.W_PAWN] | bb[Piece.B_PAWN] | bb[Piece.W_ROOK] | bb[Piece.B_ROOK]
            | bb[Piece.W_QUEEN] | bb[Piece.B_QUEEN];
        if (heavy != 0) return false;
        long knights = bb[Piece.W_KNIGHT] | bb[Piece.B_KNIGHT];
        long bishops = bb[Piece.W_BISHOP] | bb[Piece.B_BISHOP];
        int minors = Long.bitCount(knights | bishops);
        if (minors <= 1) return true;
        if (knights != 0) return false;
        long dark = 0xAA55AA55AA55AA55L;
        return (bishops & dark) == 0 || (bishops & ~dark) == 0;
    }

    /** Number of pieces of a given type and color. */
    public int count(int color, int type) {
        return Long.bitCount(bb[Piece.make(color, type)]);
    }

    public int pieceCount() {
        return Long.bitCount(occupied());
    }

    // ---------------------------------------------------------------- move generation

    /** All legal moves. The array is shared: do not modify it. */
    public int[] legalMoves() {
        int[] cached = legalCache;
        if (cached == null) {
            int[] buf = new int[256];
            int n = generate(buf);
            cached = Arrays.copyOf(buf, n);
            legalCache = cached;
        }
        return cached;
    }

    public boolean isLegal(int move) {
        for (int m : legalMoves()) if (m == move) return true;
        return false;
    }

    private int generate(int[] buf) {
        int us = side, them = us ^ 1;
        long own = byColor[us], enemy = byColor[them], all = own | enemy;
        int n = 0;

        int fwd = us == Piece.WHITE ? 8 : -8;
        for (long pawns = bb[Piece.make(us, Piece.PAWN)]; pawns != 0; pawns &= pawns - 1) {
            int from = Long.numberOfTrailingZeros(pawns);
            int to = from + fwd;
            if (board[to] < 0) {
                n = addPawnMove(buf, n, from, to);
                if (Square.relativeRank(from, us) == 1 && board[to + fwd] < 0) n = addIfLegal(buf, n, Move.of(from, to + fwd));
            }
            long attacks = Bitboards.pawnAttacks(us, from);
            for (long caps = attacks & enemy; caps != 0; caps &= caps - 1) {
                n = addPawnMove(buf, n, from, Long.numberOfTrailingZeros(caps));
            }
            if (ep != Square.NONE && (attacks & Square.bit(ep)) != 0) n = addIfLegal(buf, n, Move.enPassant(from, ep));
        }
        for (int type = Piece.KNIGHT; type <= Piece.KING; type++) {
            for (long pcs = bb[Piece.make(us, type)]; pcs != 0; pcs &= pcs - 1) {
                int from = Long.numberOfTrailingZeros(pcs);
                for (long t = Bitboards.attacks(type, from, all) & ~own; t != 0; t &= t - 1) {
                    n = addIfLegal(buf, n, Move.of(from, Long.numberOfTrailingZeros(t)));
                }
            }
        }
        if (castling != 0) n = addCastling(buf, n, us, all);
        return n;
    }

    private int addPawnMove(int[] buf, int n, int from, int to) {
        int rank = Square.rank(to);
        if (rank == 0 || rank == 7) {
            if (!isLegalPseudo(Move.of(from, to))) return n;
            for (int type = Piece.QUEEN; type >= Piece.KNIGHT; type--) buf[n++] = Move.promotion(from, to, type);
            return n;
        }
        return addIfLegal(buf, n, Move.of(from, to));
    }

    private int addIfLegal(int[] buf, int n, int move) {
        if (isLegalPseudo(move)) buf[n++] = move;
        return n;
    }

    private int addCastling(int[] buf, int n, int us, long all) {
        int king = kingSquare(us);
        if (isAttacked(king, us ^ 1, all)) return n;
        for (int slot = us * 2; slot < us * 2 + 2; slot++) {
            int rook = castleRook(slot);
            if (rook == Square.NONE) continue;
            int move = Move.castle(king, rook);
            int kTo = Move.kingTarget(move), rTo = Move.rookTarget(move);
            long kr = Square.bit(king) | Square.bit(rook);
            long mustBeEmpty = (Bitboards.rankSpan(king, kTo) | Bitboards.rankSpan(rook, rTo)) & ~kr;
            if ((mustBeEmpty & all) != 0) continue;
            long occ = all & ~kr;
            boolean safe = true;
            for (long path = Bitboards.rankSpan(king, kTo); path != 0; path &= path - 1) {
                if (isAttacked(Long.numberOfTrailingZeros(path), us ^ 1, occ)) { safe = false; break; }
            }
            if (safe) buf[n++] = move;
        }
        return n;
    }

    /** Legality of a pseudo-legal, non-castling move: our king must not be attacked afterwards. */
    private boolean isLegalPseudo(int move) {
        int us = side, them = us ^ 1;
        int from = Move.from(move), to = Move.to(move);
        long fromB = Square.bit(from), toB = Square.bit(to);
        long occ = (occupied() ^ fromB) | toB;
        long enemies = byColor[them] & ~toB;
        int king = kingSquare(us);
        if (from == king) king = to;
        if (Move.isEnPassant(move)) {
            long cap = Square.bit(to - (us == Piece.WHITE ? 8 : -8));
            occ ^= cap;
            enemies &= ~cap;
        }
        return (attackersTo(king, occ) & enemies) == 0;
    }

    // ---------------------------------------------------------------- making moves

    /** Plays a legal move (unchecked) and returns the resulting position. */
    public Position play(int move) {
        Position p = new Position(this);
        int us = side, them = us ^ 1;
        int oldRights = castlingRights();
        int oldEp = ep;
        p.ep = Square.NONE;
        p.halfmove++;
        if (Move.isNull(move)) {
            p.finishTurn(oldRights, oldEp);
            return p;
        }
        int from = Move.from(move), to = Move.to(move);
        int piece = board[from];
        if (piece < 0 || Piece.color(piece) != us) {
            throw new IllegalArgumentException("no " + Piece.colorName(us) + " piece on " + Square.name(from));
        }
        int type = Piece.type(piece);

        if (Move.isCastle(move)) {
            int rook = board[to];
            p.remove(from);
            p.remove(to);
            p.put(piece, Move.kingTarget(move));
            p.put(rook, Move.rookTarget(move));
            p.clearCastling(us * 2);
            p.clearCastling(us * 2 + 1);
        } else {
            int capSq = Move.isEnPassant(move) ? to - (us == Piece.WHITE ? 8 : -8) : to;
            if (board[capSq] >= 0) {
                p.remove(capSq);
                p.halfmove = 0;
            }
            p.remove(from);
            p.put(Move.isPromotion(move) ? Piece.make(us, Move.promotionType(move)) : piece, to);
            if (type == Piece.PAWN) {
                p.halfmove = 0;
                if (Math.abs(to - from) == 16) {
                    int sq = (from + to) / 2;
                    if (p.capturableEp(sq, them)) p.ep = sq;
                }
            }
            if (type == Piece.KING) {
                p.clearCastling(us * 2);
                p.clearCastling(us * 2 + 1);
            }
            for (int slot = 0; slot < 4; slot++) {
                int rook = castleRook(slot);
                if (rook == from || rook == to) p.clearCastling(slot);
            }
        }
        p.finishTurn(oldRights, oldEp);
        return p;
    }

    private void finishTurn(int oldRights, int oldEp) {
        if (side == Piece.BLACK) fullmove++;
        side ^= 1;
        key ^= Zobrist.blackToMove();
        key ^= Zobrist.castling(oldRights) ^ Zobrist.castling(castlingRights());
        if (oldEp != Square.NONE) key ^= Zobrist.epFile(Square.file(oldEp));
        if (ep != Square.NONE) key ^= Zobrist.epFile(Square.file(ep));
    }

    /** Can a pawn of {@code mover} capture en passant on {@code sq}? */
    private boolean capturableEp(int sq, int mover) {
        int expectedRank = mover == Piece.WHITE ? 5 : 2;
        if (Square.rank(sq) != expectedRank || board[sq] >= 0) return false;
        int pushed = sq + (mover == Piece.WHITE ? -8 : 8);
        if (board[pushed] != Piece.make(mover ^ 1, Piece.PAWN)) return false;
        return (Bitboards.pawnAttacks(mover ^ 1, sq) & bb[Piece.make(mover, Piece.PAWN)]) != 0;
    }

    private void put(int piece, int sq) {
        bb[piece] |= Square.bit(sq);
        byColor[Piece.color(piece)] |= Square.bit(sq);
        board[sq] = (byte) piece;
        key ^= Zobrist.piece(piece, sq);
    }

    private void remove(int sq) {
        int piece = board[sq];
        bb[piece] &= ~Square.bit(sq);
        byColor[Piece.color(piece)] &= ~Square.bit(sq);
        board[sq] = -1;
        key ^= Zobrist.piece(piece, sq);
    }

    private void setCastleRook(int slot, int rook) {
        castling = (castling & ~(0xFF << (slot * 8))) | ((rook + 1) << (slot * 8));
    }

    private void clearCastling(int slot) {
        castling &= ~(0xFF << (slot * 8));
    }

    private long computeKey() {
        long k = 0;
        for (int sq = 0; sq < 64; sq++) if (board[sq] >= 0) k ^= Zobrist.piece(board[sq], sq);
        k ^= Zobrist.castling(castlingRights());
        if (ep != Square.NONE) k ^= Zobrist.epFile(Square.file(ep));
        if (side == Piece.BLACK) k ^= Zobrist.blackToMove();
        return k;
    }

    // ---------------------------------------------------------------- misc

    /** Board diagram, white at the bottom (or black when {@code flipped}). */
    public String diagram(boolean flipped) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            int rank = flipped ? i : 7 - i;
            sb.append(rank + 1).append("  ");
            for (int j = 0; j < 8; j++) {
                int file = flipped ? 7 - j : j;
                int piece = board[Square.of(file, rank)];
                sb.append(piece < 0 ? '.' : Piece.fenChar(piece));
                if (j < 7) sb.append(' ');
            }
            sb.append('\n');
        }
        sb.append("   ").append(flipped ? "h g f e d c b a" : "a b c d e f g h");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Position p && p.key == key && p.side == side && p.castling == castling
            && p.ep == ep && Arrays.equals(p.bb, bb);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(key);
    }

    @Override
    public String toString() {
        return fen();
    }
}
