package com.oriondb.chess;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An immutable chess position with full legal move generation.
 *
 * All rules are implemented: checks, pins, castling legality (rights, empty
 * squares, no castling through check), en passant (including the discovered
 * check corner case, handled by the make-and-test legality filter),
 * promotions, and the fifty-move counters.
 *
 * {@link #play(int)} returns a new Board; instances are safe to share.
 */
public final class Board {
    public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    // Castling right bits
    public static final int WHITE_KINGSIDE = 1;
    public static final int WHITE_QUEENSIDE = 2;
    public static final int BLACK_KINGSIDE = 4;
    public static final int BLACK_QUEENSIDE = 8;

    private static final int[][] KNIGHT_DELTAS = {
        {1, 2}, {2, 1}, {2, -1}, {1, -2}, {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2}
    };
    private static final int[][] KING_DELTAS = {
        {0, 1}, {1, 1}, {1, 0}, {1, -1}, {0, -1}, {-1, -1}, {-1, 0}, {-1, 1}
    };
    private static final int[][] BISHOP_DIRS = {{1, 1}, {1, -1}, {-1, -1}, {-1, 1}};
    private static final int[][] ROOK_DIRS = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};

    /** rights &amp;= MASK[from] &amp; MASK[to] after every move. */
    private static final int[] CASTLING_MASK = new int[64];
    static {
        Arrays.fill(CASTLING_MASK, 0xF);
        CASTLING_MASK[Square.A1] = 0xF & ~WHITE_QUEENSIDE;
        CASTLING_MASK[Square.E1] = 0xF & ~(WHITE_KINGSIDE | WHITE_QUEENSIDE);
        CASTLING_MASK[Square.H1] = 0xF & ~WHITE_KINGSIDE;
        CASTLING_MASK[Square.A8] = 0xF & ~BLACK_QUEENSIDE;
        CASTLING_MASK[Square.E8] = 0xF & ~(BLACK_KINGSIDE | BLACK_QUEENSIDE);
        CASTLING_MASK[Square.H8] = 0xF & ~BLACK_KINGSIDE;
    }

    private static final Pattern SAN_PATTERN = Pattern.compile(
        "^([NBRQK])?([a-h])?([1-8])?(x)?([a-h][1-8])(?:=?([NBRQ]))?$");
    private static final Pattern LAN_PATTERN = Pattern.compile(
        "^([a-h][1-8])[-x]?([a-h][1-8])=?([NBRQ])?$", Pattern.CASE_INSENSITIVE);

    private final int[] squares;      // 64 entries, Piece codes or Piece.NONE
    private final int sideToMove;     // Piece.WHITE or Piece.BLACK
    private final int castling;       // bitmask of the four rights
    private final int epSquare;       // Square.NONE or the square behind a double push
    private final int halfMoveClock;
    private final int fullMoveNumber;

    // Lazy caches (Board is immutable, so these are safe).
    private int[] legalMoves;
    private long zobrist;
    private boolean zobristComputed;

    private Board(int[] squares, int sideToMove, int castling, int epSquare,
                  int halfMoveClock, int fullMoveNumber) {
        this.squares = squares;
        this.sideToMove = sideToMove;
        this.castling = castling;
        this.epSquare = epSquare;
        this.halfMoveClock = halfMoveClock;
        this.fullMoveNumber = fullMoveNumber;
    }

    public static Board startingPosition() {
        return fromFen(START_FEN);
    }

    /**
     * Parse a FEN string. The move counters are optional (default 0 and 1).
     *
     * @throws IllegalArgumentException on malformed FEN
     */
    public static Board fromFen(String fen) {
        if (fen == null) throw new IllegalArgumentException("FEN is null");
        String[] parts = fen.trim().split("\\s+");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid FEN: " + fen);
        }

        int[] squares = new int[64];
        Arrays.fill(squares, Piece.NONE);

        String[] ranks = parts[0].split("/");
        if (ranks.length != 8) {
            throw new IllegalArgumentException("Invalid FEN board: " + parts[0]);
        }
        for (int r = 0; r < 8; r++) {
            String rankStr = ranks[7 - r];
            int file = 0;
            for (char c : rankStr.toCharArray()) {
                if (c >= '1' && c <= '8') {
                    file += c - '0';
                } else {
                    int piece = Piece.fromFenChar(c);
                    if (piece == Piece.NONE || file > 7) {
                        throw new IllegalArgumentException("Invalid FEN board: " + parts[0]);
                    }
                    squares[Square.of(file, r)] = piece;
                    file++;
                }
            }
            if (file != 8) {
                throw new IllegalArgumentException("Invalid FEN rank: " + rankStr);
            }
        }

        int side;
        if (parts[1].equals("w")) side = Piece.WHITE;
        else if (parts[1].equals("b")) side = Piece.BLACK;
        else throw new IllegalArgumentException("Invalid FEN side: " + parts[1]);

        int castling = 0;
        if (parts.length > 2 && !parts[2].equals("-")) {
            for (char c : parts[2].toCharArray()) {
                switch (c) {
                    case 'K': castling |= WHITE_KINGSIDE; break;
                    case 'Q': castling |= WHITE_QUEENSIDE; break;
                    case 'k': castling |= BLACK_KINGSIDE; break;
                    case 'q': castling |= BLACK_QUEENSIDE; break;
                    default: throw new IllegalArgumentException("Invalid FEN castling: " + parts[2]);
                }
            }
        }

        int ep = Square.NONE;
        if (parts.length > 3 && !parts[3].equals("-")) {
            ep = Square.fromAlgebraic(parts[3]);
            if (ep == Square.NONE) {
                throw new IllegalArgumentException("Invalid FEN e.p. square: " + parts[3]);
            }
        }

        int halfMove = 0;
        int fullMove = 1;
        try {
            if (parts.length > 4) halfMove = Integer.parseInt(parts[4]);
            if (parts.length > 5) fullMove = Integer.parseInt(parts[5]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid FEN counters: " + fen);
        }

        return new Board(squares, side, castling, ep, halfMove, fullMove);
    }

    public String toFen() {
        StringBuilder fen = new StringBuilder();
        for (int r = 7; r >= 0; r--) {
            int empty = 0;
            for (int f = 0; f < 8; f++) {
                int piece = squares[Square.of(f, r)];
                if (piece == Piece.NONE) {
                    empty++;
                } else {
                    if (empty > 0) {
                        fen.append(empty);
                        empty = 0;
                    }
                    fen.append(Piece.toFenChar(piece));
                }
            }
            if (empty > 0) fen.append(empty);
            if (r > 0) fen.append('/');
        }
        fen.append(' ').append(sideToMove == Piece.WHITE ? 'w' : 'b').append(' ');
        if (castling == 0) {
            fen.append('-');
        } else {
            if ((castling & WHITE_KINGSIDE) != 0) fen.append('K');
            if ((castling & WHITE_QUEENSIDE) != 0) fen.append('Q');
            if ((castling & BLACK_KINGSIDE) != 0) fen.append('k');
            if ((castling & BLACK_QUEENSIDE) != 0) fen.append('q');
        }
        fen.append(' ').append(epSquare == Square.NONE ? "-" : Square.toAlgebraic(epSquare));
        fen.append(' ').append(halfMoveClock).append(' ').append(fullMoveNumber);
        return fen.toString();
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public int pieceAt(int square) {
        return Square.isValid(square) ? squares[square] : Piece.NONE;
    }

    /** Defensive copy of the 64-square array. */
    public int[] boardArray() {
        return Arrays.copyOf(squares, 64);
    }

    public int sideToMove() {
        return sideToMove;
    }

    public boolean whiteToMove() {
        return sideToMove == Piece.WHITE;
    }

    public int castlingRights() {
        return castling;
    }

    public int enPassantSquare() {
        return epSquare;
    }

    public int halfMoveClock() {
        return halfMoveClock;
    }

    public int fullMoveNumber() {
        return fullMoveNumber;
    }

    /** Zobrist hash identifying the position (pieces, side, castling, relevant e.p.). */
    public long zobrist() {
        if (!zobristComputed) {
            zobrist = Zobrist.hash(squares, sideToMove, castling, epSquare);
            zobristComputed = true;
        }
        return zobrist;
    }

    /** Counts per piece code (index with Piece.WHITE_PAWN .. Piece.BLACK_KING). */
    public int[] pieceCounts() {
        int[] counts = new int[16];
        for (int sq = 0; sq < 64; sq++) {
            if (squares[sq] != Piece.NONE) counts[squares[sq]]++;
        }
        return counts;
    }

    public int kingSquare(int color) {
        int king = Piece.KING | color;
        for (int sq = 0; sq < 64; sq++) {
            if (squares[sq] == king) return sq;
        }
        return Square.NONE;
    }

    // ------------------------------------------------------------------
    // Attacks and game state
    // ------------------------------------------------------------------

    /** Is {@code square} attacked by any piece of color {@code by}? */
    public boolean isSquareAttacked(int square, int by) {
        return attacked(squares, square, by);
    }

    private static boolean attacked(int[] a, int target, int by) {
        int tf = Square.file(target);
        int tr = Square.rank(target);

        // Pawns: a white pawn on (f, r) attacks (f±1, r+1).
        int pawnRank = tr - (by == Piece.WHITE ? 1 : -1);
        if (pawnRank >= 0 && pawnRank < 8) {
            int pawn = Piece.PAWN | by;
            if (tf > 0 && a[Square.of(tf - 1, pawnRank)] == pawn) return true;
            if (tf < 7 && a[Square.of(tf + 1, pawnRank)] == pawn) return true;
        }

        // Knights
        int knight = Piece.KNIGHT | by;
        for (int[] d : KNIGHT_DELTAS) {
            int f = tf + d[0], r = tr + d[1];
            if (Square.isValid(f, r) && a[Square.of(f, r)] == knight) return true;
        }

        // King
        int king = Piece.KING | by;
        for (int[] d : KING_DELTAS) {
            int f = tf + d[0], r = tr + d[1];
            if (Square.isValid(f, r) && a[Square.of(f, r)] == king) return true;
        }

        // Diagonal sliders
        int bishop = Piece.BISHOP | by;
        int queen = Piece.QUEEN | by;
        for (int[] d : BISHOP_DIRS) {
            int f = tf + d[0], r = tr + d[1];
            while (Square.isValid(f, r)) {
                int piece = a[Square.of(f, r)];
                if (piece != Piece.NONE) {
                    if (piece == bishop || piece == queen) return true;
                    break;
                }
                f += d[0];
                r += d[1];
            }
        }

        // Orthogonal sliders
        int rook = Piece.ROOK | by;
        for (int[] d : ROOK_DIRS) {
            int f = tf + d[0], r = tr + d[1];
            while (Square.isValid(f, r)) {
                int piece = a[Square.of(f, r)];
                if (piece != Piece.NONE) {
                    if (piece == rook || piece == queen) return true;
                    break;
                }
                f += d[0];
                r += d[1];
            }
        }

        return false;
    }

    /** Is the side to move in check? */
    public boolean inCheck() {
        int kingSq = kingSquare(sideToMove);
        return kingSq != Square.NONE && attacked(squares, kingSq, Piece.opposite(sideToMove));
    }

    public boolean isCheckmate() {
        return inCheck() && legalMovesArray().length == 0;
    }

    public boolean isStalemate() {
        return !inCheck() && legalMovesArray().length == 0;
    }

    // ------------------------------------------------------------------
    // Move generation
    // ------------------------------------------------------------------

    /** All strictly legal moves in this position. */
    public List<Integer> legalMoves() {
        int[] moves = legalMovesArray();
        List<Integer> list = new ArrayList<>(moves.length);
        for (int m : moves) list.add(m);
        return list;
    }

    private int[] legalMovesArray() {
        if (legalMoves == null) {
            List<Integer> pseudo = generatePseudoLegal();
            int us = sideToMove;
            int them = Piece.opposite(us);
            int[] result = new int[pseudo.size()];
            int n = 0;
            int[] scratch = new int[64];
            for (int move : pseudo) {
                System.arraycopy(squares, 0, scratch, 0, 64);
                applyMechanics(scratch, move, us);
                int kingSq = findKing(scratch, us);
                if (kingSq != Square.NONE && !attacked(scratch, kingSq, them)) {
                    result[n++] = move;
                }
            }
            legalMoves = Arrays.copyOf(result, n);
        }
        return legalMoves;
    }

    private static int findKing(int[] a, int color) {
        int king = Piece.KING | color;
        for (int sq = 0; sq < 64; sq++) {
            if (a[sq] == king) return sq;
        }
        return Square.NONE;
    }

    private List<Integer> generatePseudoLegal() {
        List<Integer> moves = new ArrayList<>(48);
        int us = sideToMove;
        for (int sq = 0; sq < 64; sq++) {
            int piece = squares[sq];
            if (piece == Piece.NONE || Piece.color(piece) != us) continue;
            switch (Piece.type(piece)) {
                case Piece.PAWN: generatePawnMoves(sq, moves); break;
                case Piece.KNIGHT: generateLeaperMoves(sq, KNIGHT_DELTAS, moves); break;
                case Piece.BISHOP: generateSliderMoves(sq, BISHOP_DIRS, moves); break;
                case Piece.ROOK: generateSliderMoves(sq, ROOK_DIRS, moves); break;
                case Piece.QUEEN:
                    generateSliderMoves(sq, BISHOP_DIRS, moves);
                    generateSliderMoves(sq, ROOK_DIRS, moves);
                    break;
                case Piece.KING:
                    generateLeaperMoves(sq, KING_DELTAS, moves);
                    break;
                default: break;
            }
        }
        generateCastlingMoves(moves);
        return moves;
    }

    private void generatePawnMoves(int from, List<Integer> moves) {
        int us = sideToMove;
        int dir = us == Piece.WHITE ? 1 : -1;
        int startRank = us == Piece.WHITE ? 1 : 6;
        int promoRank = us == Piece.WHITE ? 7 : 0;
        int file = Square.file(from);
        int rank = Square.rank(from);

        // Pushes
        int oneRank = rank + dir;
        if (oneRank >= 0 && oneRank < 8) {
            int one = Square.of(file, oneRank);
            if (squares[one] == Piece.NONE) {
                addPawnMove(moves, from, one, oneRank == promoRank, Move.NORMAL);
                if (rank == startRank) {
                    int two = Square.of(file, rank + 2 * dir);
                    if (squares[two] == Piece.NONE) {
                        moves.add(Move.make(from, two, Move.DOUBLE_PUSH));
                    }
                }
            }
            // Captures
            for (int df = -1; df <= 1; df += 2) {
                int tf = file + df;
                if (tf < 0 || tf > 7) continue;
                int to = Square.of(tf, oneRank);
                int targetPiece = squares[to];
                if (targetPiece != Piece.NONE && Piece.color(targetPiece) != us) {
                    addPawnMove(moves, from, to, oneRank == promoRank, Move.NORMAL);
                } else if (to == epSquare && targetPiece == Piece.NONE) {
                    moves.add(Move.make(from, to, Move.EN_PASSANT));
                }
            }
        }
    }

    private static void addPawnMove(List<Integer> moves, int from, int to, boolean promotion, int special) {
        if (promotion) {
            moves.add(Move.makePromotion(from, to, Piece.QUEEN));
            moves.add(Move.makePromotion(from, to, Piece.ROOK));
            moves.add(Move.makePromotion(from, to, Piece.BISHOP));
            moves.add(Move.makePromotion(from, to, Piece.KNIGHT));
        } else {
            moves.add(Move.make(from, to, special));
        }
    }

    private void generateLeaperMoves(int from, int[][] deltas, List<Integer> moves) {
        int us = sideToMove;
        int file = Square.file(from);
        int rank = Square.rank(from);
        for (int[] d : deltas) {
            int f = file + d[0], r = rank + d[1];
            if (!Square.isValid(f, r)) continue;
            int to = Square.of(f, r);
            int piece = squares[to];
            if (piece == Piece.NONE || Piece.color(piece) != us) {
                moves.add(Move.make(from, to));
            }
        }
    }

    private void generateSliderMoves(int from, int[][] dirs, List<Integer> moves) {
        int us = sideToMove;
        int file = Square.file(from);
        int rank = Square.rank(from);
        for (int[] d : dirs) {
            int f = file + d[0], r = rank + d[1];
            while (Square.isValid(f, r)) {
                int to = Square.of(f, r);
                int piece = squares[to];
                if (piece == Piece.NONE) {
                    moves.add(Move.make(from, to));
                } else {
                    if (Piece.color(piece) != us) {
                        moves.add(Move.make(from, to));
                    }
                    break;
                }
                f += d[0];
                r += d[1];
            }
        }
    }

    private void generateCastlingMoves(List<Integer> moves) {
        int them = Piece.opposite(sideToMove);
        if (sideToMove == Piece.WHITE) {
            if ((castling & WHITE_KINGSIDE) != 0
                    && squares[Square.E1] == Piece.WHITE_KING
                    && squares[Square.H1] == Piece.WHITE_ROOK
                    && squares[Square.F1] == Piece.NONE
                    && squares[Square.G1] == Piece.NONE
                    && !attacked(squares, Square.E1, them)
                    && !attacked(squares, Square.F1, them)
                    && !attacked(squares, Square.G1, them)) {
                moves.add(Move.make(Square.E1, Square.G1, Move.CASTLE_KINGSIDE));
            }
            if ((castling & WHITE_QUEENSIDE) != 0
                    && squares[Square.E1] == Piece.WHITE_KING
                    && squares[Square.A1] == Piece.WHITE_ROOK
                    && squares[Square.B1] == Piece.NONE
                    && squares[Square.C1] == Piece.NONE
                    && squares[Square.D1] == Piece.NONE
                    && !attacked(squares, Square.E1, them)
                    && !attacked(squares, Square.D1, them)
                    && !attacked(squares, Square.C1, them)) {
                moves.add(Move.make(Square.E1, Square.C1, Move.CASTLE_QUEENSIDE));
            }
        } else {
            if ((castling & BLACK_KINGSIDE) != 0
                    && squares[Square.E8] == Piece.BLACK_KING
                    && squares[Square.H8] == Piece.BLACK_ROOK
                    && squares[Square.F8] == Piece.NONE
                    && squares[Square.G8] == Piece.NONE
                    && !attacked(squares, Square.E8, them)
                    && !attacked(squares, Square.F8, them)
                    && !attacked(squares, Square.G8, them)) {
                moves.add(Move.make(Square.E8, Square.G8, Move.CASTLE_KINGSIDE));
            }
            if ((castling & BLACK_QUEENSIDE) != 0
                    && squares[Square.E8] == Piece.BLACK_KING
                    && squares[Square.A8] == Piece.BLACK_ROOK
                    && squares[Square.B8] == Piece.NONE
                    && squares[Square.C8] == Piece.NONE
                    && squares[Square.D8] == Piece.NONE
                    && !attacked(squares, Square.E8, them)
                    && !attacked(squares, Square.D8, them)
                    && !attacked(squares, Square.C8, them)) {
                moves.add(Move.make(Square.E8, Square.C8, Move.CASTLE_QUEENSIDE));
            }
        }
    }

    // ------------------------------------------------------------------
    // Making moves
    // ------------------------------------------------------------------

    /**
     * Board-array mechanics of a move (piece movement only, no state update).
     * Shared by the legality filter and {@link #play(int)}.
     */
    private static void applyMechanics(int[] a, int move, int us) {
        int special = Move.special(move);
        if (special == Move.NULL_MOVE) return;

        int from = Move.from(move);
        int to = Move.to(move);
        int piece = a[from];

        switch (special) {
            case Move.CASTLE_KINGSIDE: {
                int rank = us == Piece.WHITE ? 0 : 7;
                a[Square.of(4, rank)] = Piece.NONE;
                a[Square.of(7, rank)] = Piece.NONE;
                a[Square.of(6, rank)] = Piece.KING | us;
                a[Square.of(5, rank)] = Piece.ROOK | us;
                return;
            }
            case Move.CASTLE_QUEENSIDE: {
                int rank = us == Piece.WHITE ? 0 : 7;
                a[Square.of(4, rank)] = Piece.NONE;
                a[Square.of(0, rank)] = Piece.NONE;
                a[Square.of(2, rank)] = Piece.KING | us;
                a[Square.of(3, rank)] = Piece.ROOK | us;
                return;
            }
            case Move.EN_PASSANT: {
                a[from] = Piece.NONE;
                a[to] = piece;
                int capturedSq = to + (us == Piece.WHITE ? -8 : 8);
                a[capturedSq] = Piece.NONE;
                return;
            }
            default: {
                a[from] = Piece.NONE;
                int promo = Move.promotion(move);
                a[to] = promo != 0 ? (promo | us) : piece;
            }
        }
    }

    /**
     * Play a move and return the resulting position.
     * The move must come from {@link #legalMoves()} / {@link #parseSan} /
     * {@link #parseUci}; this method does not re-validate it.
     */
    public Board play(int move) {
        int us = sideToMove;
        int special = Move.special(move);

        int[] newSquares = Arrays.copyOf(squares, 64);
        applyMechanics(newSquares, move, us);

        int newCastling = castling;
        int newEp = Square.NONE;
        int newHalfMove;

        if (special == Move.NULL_MOVE) {
            newHalfMove = halfMoveClock + 1;
        } else {
            int from = Move.from(move);
            int to = Move.to(move);
            newCastling = castling & CASTLING_MASK[from] & CASTLING_MASK[to];
            boolean isPawn = Piece.type(squares[from]) == Piece.PAWN;
            boolean isCapture = squares[to] != Piece.NONE || special == Move.EN_PASSANT;
            newHalfMove = (isPawn || isCapture) ? 0 : halfMoveClock + 1;
            if (special == Move.DOUBLE_PUSH) {
                newEp = (from + to) / 2;
            }
        }

        int newFullMove = fullMoveNumber + (us == Piece.BLACK ? 1 : 0);
        return new Board(newSquares, Piece.opposite(us), newCastling, newEp, newHalfMove, newFullMove);
    }

    /** Convenience: parse SAN and play it. Throws on illegal/unparseable SAN. */
    public Board playSan(String san) {
        int move = parseSan(san);
        if (move == Move.NONE) {
            throw new IllegalArgumentException("Illegal or ambiguous move '" + san + "' in " + toFen());
        }
        return play(move);
    }

    // ------------------------------------------------------------------
    // SAN
    // ------------------------------------------------------------------

    /**
     * Parse a move in Standard Algebraic Notation against this position.
     * Tolerates check/mate/annotation suffixes ("Nf3!?", "Qxf7#"), castling
     * with zeros ("0-0"), promotions without '=' ("e8Q"), UCI ("e2e4") and
     * long algebraic ("e2-e4") forms, and null moves ("--", "Z0").
     *
     * @return the encoded move, or {@link Move#NONE} if the token does not
     *         resolve to exactly one legal move
     */
    public int parseSan(String san) {
        if (san == null) return Move.NONE;
        String s = san.trim();
        if (s.isEmpty()) return Move.NONE;

        // Null moves used by annotation tools
        if (s.equals("--") || s.equals("Z0") || s.equals("@@@@") || s.equals("0000")) {
            return Move.nullMove();
        }

        // Strip suffix annotations: +, #, !, ?, and "e.p."
        s = s.replace("e.p.", "");
        s = s.replaceAll("[+#!?]+$", "");
        if (s.isEmpty()) return Move.NONE;

        // Castling (letter O or digit 0)
        String castleNorm = s.replace('0', 'O');
        if (castleNorm.equals("O-O")) {
            return findCastlingMove(Move.CASTLE_KINGSIDE);
        }
        if (castleNorm.equals("O-O-O")) {
            return findCastlingMove(Move.CASTLE_QUEENSIDE);
        }

        Matcher m = SAN_PATTERN.matcher(s);
        if (m.matches()) {
            int pieceType = m.group(1) != null ? Piece.typeFromSanLetter(m.group(1).charAt(0)) : Piece.PAWN;
            int fromFile = m.group(2) != null ? m.group(2).charAt(0) - 'a' : -1;
            int fromRank = m.group(3) != null ? m.group(3).charAt(0) - '1' : -1;
            int target = Square.fromAlgebraic(m.group(5));
            int promo = m.group(6) != null ? Piece.typeFromSanLetter(m.group(6).charAt(0)) : 0;

            int found = Move.NONE;
            for (int move : legalMovesArray()) {
                int special = Move.special(move);
                if (special == Move.CASTLE_KINGSIDE || special == Move.CASTLE_QUEENSIDE) continue;
                if (Move.to(move) != target) continue;
                int from = Move.from(move);
                if (Piece.type(squares[from]) != pieceType) continue;
                if (fromFile != -1 && Square.file(from) != fromFile) continue;
                if (fromRank != -1 && Square.rank(from) != fromRank) continue;
                if (Move.promotion(move) != promo) continue;
                if (found != Move.NONE) return Move.NONE; // ambiguous
                found = move;
            }
            if (found != Move.NONE) return found;
        }

        // Fallback: UCI / long algebraic
        Matcher lan = LAN_PATTERN.matcher(s);
        if (lan.matches()) {
            int from = Square.fromAlgebraic(lan.group(1).toLowerCase());
            int to = Square.fromAlgebraic(lan.group(2).toLowerCase());
            int promo = lan.group(3) != null
                ? Piece.typeFromSanLetter(Character.toUpperCase(lan.group(3).charAt(0))) : 0;
            for (int move : legalMovesArray()) {
                if (Move.from(move) == from && Move.to(move) == to && Move.promotion(move) == promo) {
                    return move;
                }
            }
        }

        return Move.NONE;
    }

    /** Parse a UCI move ("e2e4", "e7e8q") against this position. */
    public int parseUci(String uci) {
        if (uci == null) return Move.NONE;
        Matcher lan = LAN_PATTERN.matcher(uci.trim());
        if (!lan.matches()) return Move.NONE;
        int from = Square.fromAlgebraic(lan.group(1).toLowerCase());
        int to = Square.fromAlgebraic(lan.group(2).toLowerCase());
        int promo = lan.group(3) != null
            ? Piece.typeFromSanLetter(Character.toUpperCase(lan.group(3).charAt(0))) : 0;
        for (int move : legalMovesArray()) {
            if (Move.from(move) == from && Move.to(move) == to && Move.promotion(move) == promo) {
                return move;
            }
        }
        return Move.NONE;
    }

    private int findCastlingMove(int special) {
        for (int move : legalMovesArray()) {
            if (Move.special(move) == special) return move;
        }
        return Move.NONE;
    }

    /**
     * Standard Algebraic Notation for a legal move of this position,
     * including minimal disambiguation and check/mate suffix.
     */
    public String toSan(int move) {
        if (Move.isNull(move)) return "--";
        int special = Move.special(move);

        String base;
        if (special == Move.CASTLE_KINGSIDE) {
            base = "O-O";
        } else if (special == Move.CASTLE_QUEENSIDE) {
            base = "O-O-O";
        } else {
            int from = Move.from(move);
            int to = Move.to(move);
            int pieceType = Piece.type(squares[from]);
            boolean isCapture = squares[to] != Piece.NONE || special == Move.EN_PASSANT;

            StringBuilder sb = new StringBuilder();
            if (pieceType == Piece.PAWN) {
                if (isCapture) {
                    sb.append((char) ('a' + Square.file(from))).append('x');
                }
                sb.append(Square.toAlgebraic(to));
                int promo = Move.promotion(move);
                if (promo != 0) {
                    sb.append('=').append(Piece.sanLetter(promo));
                }
            } else {
                sb.append(Piece.sanLetter(pieceType));
                sb.append(disambiguation(move, pieceType));
                if (isCapture) sb.append('x');
                sb.append(Square.toAlgebraic(to));
            }
            base = sb.toString();
        }

        // Check / checkmate suffix
        Board after = play(move);
        if (after.inCheck()) {
            base += after.legalMovesArray().length == 0 ? "#" : "+";
        }
        return base;
    }

    private String disambiguation(int move, int pieceType) {
        int from = Move.from(move);
        int to = Move.to(move);
        boolean needsDisambig = false;
        boolean fileUnique = true;
        boolean rankUnique = true;

        for (int other : legalMovesArray()) {
            if (other == move) continue;
            int otherFrom = Move.from(other);
            if (otherFrom == from) continue; // same piece (e.g. promotion variants)
            if (Move.to(other) != to) continue;
            if (Piece.type(squares[otherFrom]) != pieceType) continue;
            needsDisambig = true;
            if (Square.file(otherFrom) == Square.file(from)) fileUnique = false;
            if (Square.rank(otherFrom) == Square.rank(from)) rankUnique = false;
        }

        if (!needsDisambig) return "";
        if (fileUnique) return String.valueOf((char) ('a' + Square.file(from)));
        if (rankUnique) return String.valueOf((char) ('1' + Square.rank(from)));
        return Square.toAlgebraic(from);
    }

    // ------------------------------------------------------------------
    // Object contract
    // ------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Board)) return false;
        Board other = (Board) o;
        return sideToMove == other.sideToMove
            && castling == other.castling
            && epSquare == other.epSquare
            && halfMoveClock == other.halfMoveClock
            && fullMoveNumber == other.fullMoveNumber
            && Arrays.equals(squares, other.squares);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(zobrist());
    }

    /** ASCII diagram, rank 8 at top. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int r = 7; r >= 0; r--) {
            sb.append(r + 1).append("  ");
            for (int f = 0; f < 8; f++) {
                sb.append(Piece.toFenChar(squares[Square.of(f, r)])).append(' ');
            }
            sb.append('\n');
        }
        sb.append("   a b c d e f g h\n");
        sb.append(toFen());
        return sb.toString();
    }
}
