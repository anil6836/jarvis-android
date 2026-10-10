package com.anil.jarvis;

import java.util.Arrays;
import java.util.Random;

/**
 * చదరంగం (chess) rules and Jarvis's choice (plain Java: tested on the desk). Seat 0 plays white and moves first,
 * seat 1 black. The whole game: castling, en passant, promotion (always to a queen in the game), check, mate,
 * stalemate, the fifty-move rule, threefold repetition and too little material left to mate.
 *
 * Squares are 0..63: a1 = 0, h1 = 7, a8 = 56 (rank * 8 + file); the words never say them, they say "ముందుకు",
 * "కుడి వైపుకి". A piece is its type (1 బంటు … 6 రాజు) + 8 for black. A move is one int:
 * from | to << 6 | promotion type << 12 | flag << 16 (flag: a pawn's double step, en passant, castling).
 * Moves are made and taken back on the arrays (make / unmake), so the search needs no copies of the board; Jarvis
 * searches on his own copy (copy()), never on the board on the screen.
 */
final class ChessRules {
    static final int P = 1, N = 2, B = 3, R = 4, Q = 5, K = 6, BLACK = 8;
    static final int F_DOUBLE = 1, F_EP = 2, F_CASTLE = 3;
    /** state(): still playing, mate (the side to move lost), stalemate, fifty moves, three times the same, too little. */
    static final int PLAYING = 0, MATE = 1, STALEMATE = 2, FIFTY = 3, REPEAT = 4, MATERIAL = 5;
    static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    /** The pieces in Telugu (index: type): బంటు, గుర్రం, ఒంటె, ఏనుగు, మంత్రి, రాజు. */
    static final String[] NAME = {"", "బంటు", "గుర్రం", "ఒంటె", "ఏనుగు", "మంత్రి", "రాజు"};
    /** As the object ("గుర్రాన్ని జరిపాను"). */
    static final String[] OBJ = {"", "బంటుని", "గుర్రాన్ని", "ఒంటెని", "ఏనుగుని", "మంత్రిని", "రాజుని"};
    /** "With the …" ("ఒంటెతో పట్టుకున్నాను"). */
    static final String[] WITH = {"", "బంటుతో", "గుర్రంతో", "ఒంటెతో", "ఏనుగుతో", "మంత్రితో", "రాజుతో"};

    final int[] b = new int[64];
    int side, castle, ep = -1, half, full = 1;   // castle bits: 1 white short, 2 white long, 4 black short, 8 black long
    long hash;
    final int[] king = new int[2];
    /** Hashes of the positions so far (the last one is now), kept back to the last capture or pawn move. */
    long[] hist = new long[128];
    int hn;
    /** The last move of the game (0: none), the piece that made it (before a promotion), what it took, and check. */
    int lastMove, lastPiece, lastCap;
    boolean lastCheck;
    /** Also make knight / bishop / rook promotions (only the desk test's move counts want them). */
    boolean underPromote;

    // what make() changed, for unmake()
    private int[] uCap = new int[128], uCastle = new int[128], uEp = new int[128], uHalf = new int[128];
    private long[] uHash = new long[128];
    private int sp;

    // ================================================================ tables

    private static final int[] MB = new int[120], M64 = new int[64]; // the 10 x 12 board with a border (for edges)
    private static final int[][] KN = new int[64][], KG = new int[64][];
    private static final int[] ORTH = {10, -10, 1, -1}, DIAG = {11, 9, -9, -11};
    private static final int[] CMASK = new int[64];
    private static final long[][] ZP = new long[16][64];
    private static final long[] ZC = new long[16], ZE = new long[8];
    private static final long ZS;

    static {
        Arrays.fill(MB, -1);
        for (int sq = 0; sq < 64; sq++) { M64[sq] = 21 + (sq >> 3) * 10 + (sq & 7); MB[M64[sq]] = sq; }
        int[] kn = {21, 19, 12, 8, -8, -12, -19, -21}, kg = {11, 10, 9, 1, -1, -9, -10, -11};
        for (int sq = 0; sq < 64; sq++) { KN[sq] = steps(sq, kn); KG[sq] = steps(sq, kg); }
        Arrays.fill(CMASK, 15);
        CMASK[0] = 13; CMASK[4] = 12; CMASK[7] = 14; CMASK[56] = 7; CMASK[60] = 3; CMASK[63] = 11;
        long[] st = {0x2545F4914F6CDD1DL}; // (fixed: saved games keep their repetition hashes across versions)
        for (int p = 0; p < 16; p++) for (int sq = 0; sq < 64; sq++) ZP[p][sq] = mix(st);
        for (int i = 0; i < 16; i++) ZC[i] = mix(st);
        for (int i = 0; i < 8; i++) ZE[i] = mix(st);
        ZS = mix(st);
    }

    private static int[] steps(int sq, int[] d) {
        int[] t = new int[8];
        int n = 0;
        for (int x : d) { int to = MB[M64[sq] + x]; if (to >= 0) t[n++] = to; }
        return Arrays.copyOf(t, n);
    }

    private static long mix(long[] st) { // splitmix64
        long z = (st[0] += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static int from(int m) { return m & 63; }
    static int to(int m) { return (m >>> 6) & 63; }
    static int promo(int m) { return (m >>> 12) & 7; }
    static int flag(int m) { return (m >>> 16) & 3; }
    static int move(int from, int to, int promo, int flag) { return from | to << 6 | promo << 12 | flag << 16; }

    // ================================================================ the position

    ChessRules() { setFen(START); }

    void reset() { setFen(START); }

    ChessRules copy() {
        ChessRules c = new ChessRules();
        c.copyFrom(this);
        return c;
    }

    void copyFrom(ChessRules o) {
        System.arraycopy(o.b, 0, b, 0, 64);
        side = o.side; castle = o.castle; ep = o.ep; half = o.half; full = o.full; hash = o.hash;
        king[0] = o.king[0]; king[1] = o.king[1];
        hist = Arrays.copyOf(o.hist, Math.max(128, o.hist.length));
        hn = o.hn;
        lastMove = o.lastMove; lastPiece = o.lastPiece; lastCap = o.lastCap; lastCheck = o.lastCheck;
        underPromote = o.underPromote;
        sp = 0;
    }

    /** A position from FEN text; false (and this object half-set: use a fresh one) when it can't be read. */
    boolean setFen(String fen) {
        try {
            String[] f = fen.trim().split("\\s+");
            if (f.length < 4) return false;
            String[] rows = f[0].split("/");
            if (rows.length != 8) return false;
            int[] nb = new int[64];
            int wk = -1, bk = -1;
            for (int i = 0; i < 8; i++) {
                int rank = 7 - i, file = 0;
                for (int k = 0; k < rows[i].length(); k++) {
                    char ch = rows[i].charAt(k);
                    if (ch >= '1' && ch <= '8') { file += ch - '0'; continue; }
                    int t = "pnbrqk".indexOf(Character.toLowerCase(ch)) + 1;
                    if (t == 0 || file > 7) return false;
                    int sq = rank * 8 + file, p = t | (Character.isLowerCase(ch) ? BLACK : 0);
                    if (t == P && (rank == 0 || rank == 7)) return false;
                    if (p == K) { if (wk >= 0) return false; wk = sq; }
                    if (p == (K | BLACK)) { if (bk >= 0) return false; bk = sq; }
                    nb[sq] = p;
                    file++;
                }
                if (file != 8) return false;
            }
            if (wk < 0 || bk < 0) return false;
            int s = f[1].equals("w") ? 0 : f[1].equals("b") ? 1 : -1;
            if (s < 0) return false;
            int c = 0;
            if (!f[2].equals("-")) for (int k = 0; k < f[2].length(); k++) {
                int x = "KQkq".indexOf(f[2].charAt(k));
                if (x < 0) return false;
                c |= 1 << x;
            }
            if (nb[4] != K) c &= ~3;
            if (nb[7] != R) c &= ~1;
            if (nb[0] != R) c &= ~2;
            if (nb[60] != (K | BLACK)) c &= ~12;
            if (nb[63] != (R | BLACK)) c &= ~4;
            if (nb[56] != (R | BLACK)) c &= ~8;
            int e = f[3].equals("-") ? -1 : square(f[3]);
            if (!f[3].equals("-") && e < 0) return false;
            int hf = f.length > 4 ? Integer.parseInt(f[4]) : 0, fm = f.length > 5 ? Integer.parseInt(f[5]) : 1;
            if (hf < 0 || hf > 1000 || fm < 1) return false;
            System.arraycopy(nb, 0, b, 0, 64);
            side = s; castle = c; half = hf; full = fm;
            king[0] = wk; king[1] = bk;
            ep = e >= 0 && epUseful(e) ? e : -1;
            if (attacked(king[side ^ 1], side)) return false; // (the side that just moved can't be in check)
            sp = 0;
            hash = computeHash();
            hn = 0;
            hist[hn++] = hash;
            lastMove = lastPiece = lastCap = 0;
            lastCheck = inCheck();
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /** An en-passant square counts only when a pawn of the side to move could take there. */
    private boolean epUseful(int e) {
        int f = e & 7;
        if (side == 0) {
            if ((e >> 3) != 5 || b[e] != 0 || b[e - 8] != (P | BLACK)) return false;
            return (f > 0 && b[e - 9] == P) || (f < 7 && b[e - 7] == P);
        }
        if ((e >> 3) != 2 || b[e] != 0 || b[e + 8] != P) return false;
        return (f > 0 && b[e + 7] == (P | BLACK)) || (f < 7 && b[e + 9] == (P | BLACK));
    }

    String fen() {
        StringBuilder s = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                int p = b[rank * 8 + file];
                if (p == 0) { empty++; continue; }
                if (empty > 0) { s.append(empty); empty = 0; }
                char ch = " pnbrqk".charAt(p & 7);
                s.append(p < BLACK ? Character.toUpperCase(ch) : ch);
            }
            if (empty > 0) s.append(empty);
            if (rank > 0) s.append('/');
        }
        s.append(side == 0 ? " w " : " b ");
        if (castle == 0) s.append('-');
        for (int i = 0; i < 4; i++) if ((castle & (1 << i)) != 0) s.append("KQkq".charAt(i));
        s.append(' ').append(ep < 0 ? "-" : name(ep)).append(' ').append(half).append(' ').append(full);
        return s.toString();
    }

    /** "e4" → 28 (for the desk and FEN only: Jarvis never says squares), -1 when not a square. */
    static int square(String s) {
        if (s == null || s.length() != 2) return -1;
        int f = s.charAt(0) - 'a', r = s.charAt(1) - '1';
        return f < 0 || f > 7 || r < 0 || r > 7 ? -1 : r * 8 + f;
    }

    static String name(int sq) { return "" + (char) ('a' + (sq & 7)) + (char) ('1' + (sq >> 3)); }

    long computeHash() {
        long h = 0;
        for (int sq = 0; sq < 64; sq++) if (b[sq] != 0) h ^= ZP[b[sq]][sq];
        h ^= ZC[castle];
        if (ep >= 0) h ^= ZE[ep & 7];
        if (side == 1) h ^= ZS;
        return h;
    }

    // ================================================================ attacks and moves

    /** Is this square attacked by that colour (0 white, 1 black)? */
    boolean attacked(int sq, int by) {
        int s = M64[sq], pawn = P | by << 3;
        if (by == 0) { if (at(s - 9) == pawn || at(s - 11) == pawn) return true; }
        else if (at(s + 9) == pawn || at(s + 11) == pawn) return true;
        int kn = N | by << 3, kg = K | by << 3;
        for (int t : KN[sq]) if (b[t] == kn) return true;
        for (int t : KG[sq]) if (b[t] == kg) return true;
        int rk = R | by << 3, qn = Q | by << 3, bs = B | by << 3;
        for (int d : ORTH) {
            for (int x = s + d; ; x += d) {
                int t = MB[x];
                if (t < 0) break;
                int q = b[t];
                if (q != 0) { if (q == rk || q == qn) return true; break; }
            }
        }
        for (int d : DIAG) {
            for (int x = s + d; ; x += d) {
                int t = MB[x];
                if (t < 0) break;
                int q = b[t];
                if (q != 0) { if (q == bs || q == qn) return true; break; }
            }
        }
        return false;
    }

    private int at(int s120) { int t = MB[s120]; return t < 0 ? -1 : b[t]; }

    /** Is the side to move in check? */
    boolean inCheck() { return attacked(king[side], side ^ 1); }

    /**
     * The moves of the side to move that obey how pieces move (a few may leave the own king in check: the caller
     * tries them), written into l from index n; returns the new end. noisy: only captures and queen promotions.
     */
    int gen(int[] l, int n, boolean noisy) {
        int us = side;
        for (int sq = 0; sq < 64; sq++) {
            int p = b[sq];
            if (p == 0 || (p >> 3) != us) continue;
            int t = p & 7;
            if (t == P) { n = pawn(l, n, sq, us, noisy); continue; }
            if (t == N || t == K) {
                for (int to : t == N ? KN[sq] : KG[sq]) {
                    int q = b[to];
                    if (q == 0) { if (!noisy) l[n++] = sq | to << 6; }
                    else if ((q >> 3) != us) l[n++] = sq | to << 6;
                }
                continue;
            }
            if (t != R) n = slide(l, n, sq, us, DIAG, noisy);
            if (t != B) n = slide(l, n, sq, us, ORTH, noisy);
        }
        if (!noisy) n = castles(l, n, us);
        return n;
    }

    private int slide(int[] l, int n, int sq, int us, int[] dirs, boolean noisy) {
        for (int d : dirs) {
            for (int x = M64[sq] + d; ; x += d) {
                int to = MB[x];
                if (to < 0) break;
                int q = b[to];
                if (q == 0) { if (!noisy) l[n++] = sq | to << 6; continue; }
                if ((q >> 3) != us) l[n++] = sq | to << 6;
                break;
            }
        }
        return n;
    }

    private int pawn(int[] l, int n, int sq, int us, boolean noisy) {
        int dir = us == 0 ? 8 : -8, f = sq & 7, to = sq + dir;
        boolean last = (to >> 3) == (us == 0 ? 7 : 0);
        if (b[to] == 0) {
            if (last) n = promos(l, n, sq, to, noisy);
            else if (!noisy) {
                l[n++] = sq | to << 6;
                if ((sq >> 3) == (us == 0 ? 1 : 6) && b[to + dir] == 0) l[n++] = move(sq, to + dir, 0, F_DOUBLE);
            }
        }
        for (int df = -1; df <= 1; df += 2) {
            if (f + df < 0 || f + df > 7) continue;
            int c = to + df, q = b[c];
            if (q != 0) {
                if ((q >> 3) != us) { if (last) n = promos(l, n, sq, c, noisy); else l[n++] = sq | c << 6; }
            } else if (c == ep) l[n++] = move(sq, c, 0, F_EP);
        }
        return n;
    }

    private int promos(int[] l, int n, int from, int to, boolean noisy) {
        l[n++] = move(from, to, Q, 0);
        if (underPromote && !noisy) { l[n++] = move(from, to, N, 0); l[n++] = move(from, to, R, 0); l[n++] = move(from, to, B, 0); }
        return n;
    }

    private int castles(int[] l, int n, int us) {
        if (us == 0) {
            if (b[4] != K || (castle & 3) == 0 || attacked(4, 1)) return n;
            if ((castle & 1) != 0 && b[7] == R && b[5] == 0 && b[6] == 0 && !attacked(5, 1) && !attacked(6, 1)) l[n++] = move(4, 6, 0, F_CASTLE);
            if ((castle & 2) != 0 && b[0] == R && b[1] == 0 && b[2] == 0 && b[3] == 0 && !attacked(3, 1) && !attacked(2, 1)) l[n++] = move(4, 2, 0, F_CASTLE);
        } else {
            if (b[60] != (K | BLACK) || (castle & 12) == 0 || attacked(60, 0)) return n;
            if ((castle & 4) != 0 && b[63] == (R | BLACK) && b[61] == 0 && b[62] == 0 && !attacked(61, 0) && !attacked(62, 0)) l[n++] = move(60, 62, 0, F_CASTLE);
            if ((castle & 8) != 0 && b[56] == (R | BLACK) && b[57] == 0 && b[58] == 0 && b[59] == 0 && !attacked(59, 0) && !attacked(58, 0)) l[n++] = move(60, 58, 0, F_CASTLE);
        }
        return n;
    }

    private void grow() {
        int n = uCap.length * 2;
        uCap = Arrays.copyOf(uCap, n); uCastle = Arrays.copyOf(uCastle, n); uEp = Arrays.copyOf(uEp, n);
        uHalf = Arrays.copyOf(uHalf, n); uHash = Arrays.copyOf(uHash, n);
    }

    /** Makes a move (from gen: it may leave the own king in check, see legal()). unmake(m) takes it back. */
    void make(int m) {
        if (sp == uCap.length) grow();
        if (hn == hist.length) hist = Arrays.copyOf(hist, hn * 2);
        int from = m & 63, to = (m >>> 6) & 63, pr = (m >>> 12) & 7, fl = (m >>> 16) & 3;
        int us = side, p = b[from], cap = b[to];
        uCastle[sp] = castle; uEp[sp] = ep; uHalf[sp] = half; uHash[sp] = hash;
        long h = hash ^ ZC[castle];
        if (ep >= 0) h ^= ZE[ep & 7];
        if (fl == F_EP) { int cs = to + (us == 0 ? -8 : 8); cap = b[cs]; b[cs] = 0; h ^= ZP[cap][cs]; }
        else if (cap != 0) h ^= ZP[cap][to];
        uCap[sp] = cap;
        b[from] = 0;
        h ^= ZP[p][from];
        int np = pr != 0 ? pr | us << 3 : p;
        b[to] = np;
        h ^= ZP[np][to];
        if (fl == F_CASTLE) {
            int rf = rookFrom(to), rt = rookTo(to), rk = b[rf];
            b[rf] = 0; b[rt] = rk;
            h ^= ZP[rk][rf] ^ ZP[rk][rt];
        }
        if ((p & 7) == K) king[us] = to;
        castle &= CMASK[from] & CMASK[to];
        h ^= ZC[castle];
        ep = -1;
        if (fl == F_DOUBLE) {
            int ef = to & 7, enemy = P | (us ^ 1) << 3;
            if ((ef > 0 && b[to - 1] == enemy) || (ef < 7 && b[to + 1] == enemy)) { ep = (from + to) >> 1; h ^= ZE[ep & 7]; }
        }
        half = (p & 7) == P || cap != 0 ? 0 : half + 1;
        if (us == 1) full++;
        side = us ^ 1;
        h ^= ZS;
        hash = h;
        hist[hn++] = h;
        sp++;
    }

    void unmake(int m) {
        sp--;
        hn--;
        int from = m & 63, to = (m >>> 6) & 63, pr = (m >>> 12) & 7, fl = (m >>> 16) & 3;
        int us = side ^ 1;
        side = us;
        if (us == 1) full--;
        int p = pr != 0 ? P | us << 3 : b[to];
        b[from] = p;
        int cap = uCap[sp];
        if (fl == F_EP) { b[to] = 0; b[to + (us == 0 ? -8 : 8)] = cap; }
        else b[to] = cap;
        if (fl == F_CASTLE) { int rf = rookFrom(to), rt = rookTo(to); b[rf] = b[rt]; b[rt] = 0; }
        if ((p & 7) == K) king[us] = from;
        castle = uCastle[sp]; ep = uEp[sp]; half = uHalf[sp]; hash = uHash[sp];
    }

    static int rookFrom(int kingTo) { return kingTo == 6 ? 7 : kingTo == 2 ? 0 : kingTo == 62 ? 63 : 56; }
    static int rookTo(int kingTo) { return kingTo == 6 ? 5 : kingTo == 2 ? 3 : kingTo == 62 ? 61 : 59; }

    private void makeNull() {
        if (sp == uCap.length) grow();
        if (hn == hist.length) hist = Arrays.copyOf(hist, hn * 2);
        uCastle[sp] = castle; uEp[sp] = ep; uHalf[sp] = half; uHash[sp] = hash; uCap[sp] = 0;
        long h = hash;
        if (ep >= 0) h ^= ZE[ep & 7];
        ep = -1;
        side ^= 1;
        hash = h ^ ZS;
        half = 0; // (no repetition across a pass)
        hist[hn++] = hash;
        sp++;
    }

    private void unmakeNull() {
        sp--;
        hn--;
        side ^= 1;
        castle = uCastle[sp]; ep = uEp[sp]; half = uHalf[sp]; hash = uHash[sp];
    }

    /** All legal moves of the side to move (promotions: a queen). */
    int[] legal() {
        int[] l = new int[320];
        int n = gen(l, 0, false), k = 0;
        for (int i = 0; i < n; i++) {
            int m = l[i];
            make(m);
            if (!attacked(king[side ^ 1], side)) l[k++] = m;
            unmake(m);
        }
        return Arrays.copyOf(l, k);
    }

    boolean isLegal(int m) {
        if (m == 0) return false;
        for (int x : legal()) if (x == m) return true;
        return false;
    }

    /** The legal move from → to (a promotion: to a queen), or 0. */
    int find(int from, int to) {
        int got = 0;
        for (int m : legal()) if ((m & 63) == from && ((m >>> 6) & 63) == to && (got == 0 || promo(m) == Q)) got = m;
        return got;
    }

    boolean hasLegal() {
        int[] l = new int[320];
        int n = gen(l, 0, false);
        for (int i = 0; i < n; i++) {
            make(l[i]);
            boolean ok = !attacked(king[side ^ 1], side);
            unmake(l[i]);
            if (ok) return true;
        }
        return false;
    }

    /** A move of the game (legal): made, remembered as the last move. */
    void play(int m) {
        int from = m & 63, to = (m >>> 6) & 63, p = b[from];
        int cap = flag(m) == F_EP ? P | (side ^ 1) << 3 : b[to];
        make(m);
        sp = 0;
        if (half == 0) { hist[0] = hash; hn = 1; } // (nothing before a capture / pawn move can come again)
        lastMove = m; lastPiece = p; lastCap = cap;
        lastCheck = inCheck();
    }

    /** How many times the position now has been on the board (same side to move, castling, en passant). */
    int repeats() {
        int c = 1;
        for (int i = hn - 3; i >= 0 && i >= hn - 1 - half; i -= 2) if (hist[i] == hash) c++;
        return c;
    }

    private boolean repeated() {
        for (int i = hn - 3; i >= 0 && i >= hn - 1 - half; i -= 2) if (hist[i] == hash) return true;
        return false;
    }

    /** Nobody can ever mate: kings alone, one minor piece, or only bishops all on one colour of squares. */
    boolean insufficient() {
        int knights = 0, light = 0, dark = 0;
        for (int sq = 0; sq < 64; sq++) {
            int t = b[sq] & 7;
            if (t == P || t == R || t == Q) return false;
            if (t == N) knights++;
            else if (t == B) { if (((sq >> 3) + (sq & 7)) % 2 == 0) dark++; else light++; }
        }
        if (knights + light + dark <= 1) return true;
        return knights == 0 && (light == 0 || dark == 0);
    }

    /** PLAYING, MATE (the side to move has lost), STALEMATE, FIFTY, REPEAT or MATERIAL (all three: a draw). */
    int state() {
        if (!hasLegal()) return inCheck() ? MATE : STALEMATE;
        if (half >= 100) return FIFTY;
        if (repeats() >= 3) return REPEAT;
        if (insufficient()) return MATERIAL;
        return PLAYING;
    }

    /** How many of this colour's pieces were taken, by type, into out[0..6] (promoted pawns are not "taken"). */
    void taken(int color, int[] out) {
        for (int i = 0; i < 7; i++) out[i] = 0;
        for (int p : b) if (p != 0 && (p >> 3) == color) out[p & 7]--;
        int promoted = 0;
        int[] start = START_COUNT;
        for (int t = 2; t <= 5; t++) { int have = -out[t]; out[t] = Math.max(0, start[t] - have); promoted += Math.max(0, have - start[t]); }
        out[P] = Math.max(0, 8 + out[P] - promoted);
        out[K] = 0;
    }

    private static final int[] START_COUNT = {0, 8, 2, 2, 2, 1, 1};

    /** Leaf count (the desk test checks the move generator with it). */
    long perft(int depth) {
        if (depth == 0) return 1;
        int[] l = new int[320];
        int n = gen(l, 0, false);
        long c = 0;
        for (int i = 0; i < n; i++) {
            make(l[i]);
            if (!attacked(king[side ^ 1], side)) c += depth == 1 ? 1 : perft(depth - 1);
            unmake(l[i]);
        }
        return c;
    }

    // ================================================================ words for a move (no squares, as seen on the screen)

    /**
     * Which way the piece went: forward / back for the one who moved it ("ముందుకు" = toward the other side), left /
     * right as this screen shows the board (whiteBottom: white at the bottom). "రెండు గడులు ముందుకు" for a pawn's
     * double step.
     */
    static String way(int m, int piece, boolean whiteBottom) {
        int from = m & 63, to = (m >>> 6) & 63;
        int dr = ((to >> 3) - (from >> 3)) * ((piece >> 3) == 0 ? 1 : -1);
        int df = ((to & 7) - (from & 7)) * (whiteBottom ? 1 : -1);
        if ((piece & 7) == P && Math.abs(dr) == 2) return "రెండు గడులు ముందుకు";
        String lr = df > 0 ? "కుడి వైపు" : "ఎడమ వైపు";
        if (df == 0) return dr > 0 ? "ముందుకు" : "వెనక్కి";
        if (dr == 0) return lr + "కి";
        return lr + "గా " + (dr > 0 ? "ముందుకు" : "వెనక్కి");
    }

    // ================================================================ save / load

    /** "v1;" + FEN + ";" + last move, piece, taken piece, check + ";" + the position hashes since the last capture / pawn move. */
    String save() {
        StringBuilder s = new StringBuilder("v1;").append(fen()).append(';')
                .append(lastMove).append(',').append(lastPiece).append(',').append(lastCap).append(',').append(lastCheck ? 1 : 0).append(';');
        int from = Math.max(0, hn - 1 - half);
        for (int i = from; i < hn; i++) { if (i > from) s.append(','); s.append(Long.toString(hist[i], 36)); }
        return s.toString();
    }

    boolean load(String s) {
        try {
            if (s == null || !s.startsWith("v1;")) return false;
            String[] p = s.split(";", -1);
            if (p.length < 4) return false;
            ChessRules t = new ChessRules();
            if (!t.setFen(p[1])) return false;
            String[] l = p[2].split(",");
            if (l.length != 4) return false;
            int lm = Integer.parseInt(l[0]), lp = Integer.parseInt(l[1]), lc = Integer.parseInt(l[2]);
            if (lm < 0 || lm >= 1 << 18 || !pieceOrNone(lp) || !pieceOrNone(lc) || lm != 0 && lp == 0) return false;
            long[] h = new long[0];
            if (!p[3].isEmpty()) {
                String[] hs = p[3].split(",");
                if (hs.length > 1000) return false;
                h = new long[hs.length];
                for (int i = 0; i < hs.length; i++) h[i] = Long.parseLong(hs[i], 36);
            }
            boolean endsNow = h.length > 0 && h[h.length - 1] == t.hash;
            int n = h.length + (endsNow ? 0 : 1);
            t.hist = Arrays.copyOf(h, Math.max(128, n * 2));
            if (!endsNow) t.hist[h.length] = t.hash;
            t.hn = n;
            t.lastMove = lm; t.lastPiece = lp; t.lastCap = lc; t.lastCheck = l[3].equals("1");
            t.underPromote = underPromote;
            copyFrom(t);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean pieceOrNone(int p) { return p == 0 || p > 0 && p < 15 && (p & 7) >= 1 && (p & 7) <= 6; }

    // ================================================================ how good a position is (for the side to move)

    private static final int[] VAL = {0, 100, 320, 330, 500, 900, 0};
    private static final int[] PHASE = {0, 0, 1, 1, 2, 4, 0};
    private static final int[] PASSED = {0, 5, 10, 18, 32, 55, 85, 0};
    private static final int[][] MGT = new int[16][64], EGT = new int[16][64];

    // piece-square tables as seen by white, the 8th rank first (the well-known "simplified evaluation" values)
    private static final int[] T_P = {
            0, 0, 0, 0, 0, 0, 0, 0,
            50, 50, 50, 50, 50, 50, 50, 50,
            10, 10, 20, 30, 30, 20, 10, 10,
            5, 5, 10, 25, 25, 10, 5, 5,
            0, 0, 0, 20, 20, 0, 0, 0,
            5, -5, -10, 0, 0, -10, -5, 5,
            5, 10, 10, -20, -20, 10, 10, 5,
            0, 0, 0, 0, 0, 0, 0, 0};
    private static final int[] T_PE = {
            0, 0, 0, 0, 0, 0, 0, 0,
            70, 70, 70, 70, 70, 70, 70, 70,
            40, 40, 40, 40, 40, 40, 40, 40,
            22, 22, 22, 22, 22, 22, 22, 22,
            10, 10, 10, 10, 10, 10, 10, 10,
            3, 3, 3, 3, 3, 3, 3, 3,
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0};
    private static final int[] T_N = {
            -50, -40, -30, -30, -30, -30, -40, -50,
            -40, -20, 0, 0, 0, 0, -20, -40,
            -30, 0, 10, 15, 15, 10, 0, -30,
            -30, 5, 15, 20, 20, 15, 5, -30,
            -30, 0, 15, 20, 20, 15, 0, -30,
            -30, 5, 10, 15, 15, 10, 5, -30,
            -40, -20, 0, 5, 5, 0, -20, -40,
            -50, -40, -30, -30, -30, -30, -40, -50};
    private static final int[] T_B = {
            -20, -10, -10, -10, -10, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 10, 10, 5, 0, -10,
            -10, 5, 5, 10, 10, 5, 5, -10,
            -10, 0, 10, 10, 10, 10, 0, -10,
            -10, 10, 10, 10, 10, 10, 10, -10,
            -10, 5, 0, 0, 0, 0, 5, -10,
            -20, -10, -10, -10, -10, -10, -10, -20};
    private static final int[] T_R = {
            0, 0, 0, 0, 0, 0, 0, 0,
            5, 10, 10, 10, 10, 10, 10, 5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            0, 0, 0, 5, 5, 0, 0, 0};
    private static final int[] T_Q = {
            -20, -10, -10, -5, -5, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 5, 5, 5, 0, -10,
            -5, 0, 5, 5, 5, 5, 0, -5,
            0, 0, 5, 5, 5, 5, 0, -5,
            -10, 5, 5, 5, 5, 5, 0, -10,
            -10, 0, 5, 0, 0, 0, 0, -10,
            -20, -10, -10, -5, -5, -10, -10, -20};
    private static final int[] T_K = {
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -20, -30, -30, -40, -40, -30, -30, -20,
            -10, -20, -20, -20, -20, -20, -20, -10,
            20, 20, 0, 0, 0, 0, 20, 20,
            20, 30, 10, 0, 0, 10, 30, 20};
    private static final int[] T_KE = {
            -50, -40, -30, -20, -20, -30, -40, -50,
            -30, -20, -10, 0, 0, -10, -20, -30,
            -30, -10, 20, 30, 30, 20, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 20, 30, 30, 20, -10, -30,
            -30, -30, 0, 0, 0, 0, -30, -30,
            -50, -30, -30, -30, -30, -30, -30, -50};

    static { initEval(); } // (here: after the tables above exist)

    private static void initEval() {
        int[][] mg = {null, T_P, T_N, T_B, T_R, T_Q, T_K}, eg = {null, T_PE, T_N, T_B, T_R, T_Q, T_KE};
        for (int t = 1; t <= 6; t++) {
            for (int sq = 0; sq < 64; sq++) {
                int ev = t == P ? 10 : 0; // (a pawn is worth a little more when the board empties)
                MGT[t][sq] = VAL[t] + mg[t][sq ^ 56];
                EGT[t][sq] = VAL[t] + ev + eg[t][sq ^ 56];
                MGT[t | BLACK][sq] = VAL[t] + mg[t][sq];
                EGT[t | BLACK][sq] = VAL[t] + ev + eg[t][sq];
            }
        }
    }

    private final int[] wMin = new int[8], bMax = new int[8], pawnSq = new int[16];

    /** The position's value for the side to move (centipawns): material, piece squares, passed pawns, mating help. */
    int evaluate() {
        int mg = 0, eg = 0, phase = 0, np = 0, wb = 0, bb = 0, wMat = 0, bMat = 0, wPawns = 0, bPawns = 0;
        for (int f = 0; f < 8; f++) { wMin[f] = 8; bMax[f] = -1; }
        for (int sq = 0; sq < 64; sq++) {
            int p = b[sq];
            if (p == 0) continue;
            int t = p & 7;
            if (p < BLACK) { mg += MGT[p][sq]; eg += EGT[p][sq]; }
            else { mg -= MGT[p][sq]; eg -= EGT[p][sq]; }
            phase += PHASE[t];
            if (t == P) {
                if (np < 16) pawnSq[np++] = sq;
                int f = sq & 7, r = sq >> 3;
                if (p == P) { wPawns++; if (r < wMin[f]) wMin[f] = r; }
                else { bPawns++; if (r > bMax[f]) bMax[f] = r; }
            } else if (t != K) {
                if (p < BLACK) { wMat += VAL[t]; if (t == B) wb++; } else { bMat += VAL[t]; if (t == B) bb++; }
            }
        }
        for (int i = 0; i < np; i++) {
            int sq = pawnSq[i], f = sq & 7, r = sq >> 3;
            int lo = Math.max(0, f - 1), hi = Math.min(7, f + 1);
            if (b[sq] == P) {
                boolean passed = true;
                for (int x = lo; x <= hi; x++) if (bMax[x] > r) { passed = false; break; }
                if (passed) { mg += PASSED[r] / 2; eg += PASSED[r]; }
            } else {
                boolean passed = true;
                for (int x = lo; x <= hi; x++) if (wMin[x] < r) { passed = false; break; }
                if (passed) { mg -= PASSED[7 - r] / 2; eg -= PASSED[7 - r]; }
            }
        }
        if (wb >= 2) { mg += 30; eg += 40; }
        if (bb >= 2) { mg -= 30; eg -= 40; }
        int ph = Math.min(24, phase);
        int score = (mg * ph + eg * (24 - ph)) / 24;
        // the other side has only its king: drive it to the edge and bring the own king close (so mates get finished)
        if (bMat == 0 && bPawns == 0 && wMat >= 500) score += mopUp(king[1], king[0]);
        else if (wMat == 0 && wPawns == 0 && bMat >= 500) score -= mopUp(king[0], king[1]);
        return side == 0 ? score : -score;
    }

    private static int mopUp(int lone, int strong) {
        int f = lone & 7, r = lone >> 3;
        int edge = Math.max(3 - f, f - 4) + Math.max(3 - r, r - 4);
        int dist = Math.abs(f - (strong & 7)) + Math.abs(r - (strong >> 3));
        return 200 + 12 * edge + 5 * (14 - dist);
    }

    // ================================================================ Jarvis's search

    private static final int INF = 32000, MATE_V = 30000, MAXPLY = 64, SLICE = 320;
    private static final int TT_SIZE = 1 << 16, EXACT = 1, LOWER = 2, UPPER = 3;
    /**
     * Work limits. Nodes give the same strength on the slow tablet (hard ≈ 0.2 s here, ≈ 0.8 s there); the ms cap
     * stops him sitting too long on a slower device. (Not final: the desk test lowers them for its many games.)
     */
    static long MEDIUM_NODES = 60_000, HARD_NODES = 250_000;
    static long MEDIUM_MS = 1500, HARD_MS = 2600;

    private int[] mv, ms, histT;
    private int[][] killer;
    private long[] ttKey;
    private int[] ttMove, ttData;
    private long nodes, nodeLimit, deadline;
    private boolean stop, canStop, qOn, nullOn, ttOn, extOn;
    /** The score of the last think() for the side that was to move (centipawns; ± near 30000: a mate). */
    int lastScore;
    long lastNodes;
    int lastDepth;

    private void prepare(boolean tt) {
        if (mv == null) { mv = new int[MAXPLY * SLICE]; ms = new int[MAXPLY * SLICE]; killer = new int[MAXPLY][2]; histT = new int[2 * 4096]; }
        if (tt && ttKey == null) { ttKey = new long[TT_SIZE]; ttMove = new int[TT_SIZE]; ttData = new int[TT_SIZE]; }
        for (int[] k : killer) { k[0] = 0; k[1] = 0; }
        Arrays.fill(histT, 0);
        sp = 0;
        stop = false;
        nodes = 0;
    }

    /** Jarvis's move at this level (0 easy, 1 medium, 2 hard), or 0 when there is none. Searches this object: a copy. */
    int choose(int level, Random rnd) {
        int[] l = legal();
        if (l.length == 0) return 0;
        if (l.length == 1) return l[0];
        if (level <= 0) return easy(l, rnd);
        if (level == 1) return think(3, MEDIUM_NODES, MEDIUM_MS, 12, rnd);
        return think(MAXPLY - 10, HARD_NODES, HARD_MS, full <= 3 ? 8 : 0, rnd);
    }

    /** The 💡 move for whoever is to move: medium strength, his best (no playfulness). 0 when there is none. */
    int hint(Random rnd) {
        int[] l = legal();
        if (l.length == 0) return 0;
        if (l.length == 1) return l[0];
        return think(3, MEDIUM_NODES, MEDIUM_MS, 0, rnd);
    }

    /**
     * Easy: looks one move ahead (sometimes two), picks at random among the moves near the best, now and then
     * doesn't notice what he could take, and sometimes just slips. She can win against this.
     */
    private int easy(int[] l, Random rnd) {
        double x = rnd.nextDouble();
        if (x < 0.15) return l[rnd.nextInt(l.length)];
        boolean blind = x < 0.37; // (he doesn't see what he could take this time)
        int depth = rnd.nextDouble() < 0.35 ? 2 : 1;
        prepare(false);
        qOn = false; nullOn = false; ttOn = false; extOn = false; canStop = false;
        int[] sc = new int[l.length];
        int best = -INF;
        for (int i = 0; i < l.length; i++) {
            int m = l[i];
            boolean take = b[(m >>> 6) & 63] != 0 || flag(m) == F_EP;
            make(m);
            int s = -search(depth - 1, -INF, INF, 1, false);
            unmake(m);
            if (blind && take) s = -INF + 1;
            sc[i] = s;
            if (s > best) best = s;
        }
        int[] ok = new int[l.length];
        int n = 0;
        for (int i = 0; i < l.length; i++) if (sc[i] >= best - 170) ok[n++] = l[i];
        lastScore = best;
        return ok[rnd.nextInt(n)];
    }

    /** Iterative deepening up to maxDepth within the limits; noise: ± a few centipawns per root move (variety). */
    int think(int maxDepth, long maxNodes, long maxMs, int noise, Random rnd) {
        int[] root = legal();
        if (root.length == 0) return 0;
        prepare(true);
        qOn = true; nullOn = true; ttOn = true; extOn = true;
        nodeLimit = maxNodes;
        deadline = System.nanoTime() + maxMs * 1_000_000L;
        int n = root.length;
        int[] nz = new int[n], key = new int[n];
        for (int i = 0; i < n; i++) { nz[i] = noise > 0 ? rnd.nextInt(2 * noise + 1) - noise : 0; key[i] = order(root[i], 0, 0); }
        for (int i = 1; i < n; i++) { // captures first
            for (int j = i; j > 0 && key[j] > key[j - 1]; j--) { swap(root, j, j - 1); swap(nz, j, j - 1); swap(key, j, j - 1); }
        }
        int best = root[0], bestScore = 0;
        lastDepth = 0;
        for (int depth = 1; depth <= maxDepth; depth++) {
            canStop = depth > 1;
            int alpha = -INF, beta = INF, iBest = -1, iScore = -INF;
            for (int i = 0; i < n; i++) {
                int m = root[i], a = alpha - nz[i], bt = beta - nz[i];
                make(m);
                int s;
                if (i == 0) s = -search(depth - 1, -bt, -a, 1, true);
                else {
                    s = -search(depth - 1, -a - 1, -a, 1, true);
                    if (!stop && s > a) s = -search(depth - 1, -bt, -a, 1, true);
                }
                unmake(m);
                if (stop) break;
                s += nz[i];
                if (s > iScore) { iScore = s; iBest = i; }
                if (s > alpha) alpha = s;
            }
            if (iBest >= 0) {
                best = root[iBest];
                bestScore = iScore;
                for (int j = iBest; j > 0; j--) { swap(root, j, j - 1); swap(nz, j, j - 1); }
            }
            if (stop) break;
            lastDepth = depth;
            if (Math.abs(bestScore) >= MATE_V - 200) break;
            if (nodes > nodeLimit / 2) break; // (the next depth would not finish)
        }
        lastScore = bestScore;
        lastNodes = nodes;
        return best;
    }

    private static void swap(int[] a, int i, int j) { int t = a[i]; a[i] = a[j]; a[j] = t; }

    private void tick() {
        if (canStop && (nodes >= nodeLimit || System.nanoTime() >= deadline)) stop = true;
    }

    /** Move order: the remembered best, captures (most valuable victim, cheapest attacker), killers, history. */
    private int order(int m, int ttm, int ply) {
        if (m == ttm) return 3_000_000;
        int to = (m >>> 6) & 63, pr = (m >>> 12) & 7;
        int victim = flag(m) == F_EP ? P : b[to] & 7;
        if (victim != 0 || pr != 0) return 2_000_000 + VAL[victim] * 16 + (pr == Q ? 8000 : 0) - (b[m & 63] & 7);
        if (killer != null && ply < MAXPLY) {
            if (m == killer[ply][0]) return 1_900_000;
            if (m == killer[ply][1]) return 1_800_000;
        }
        return histT == null ? 0 : histT[side << 12 | (m & 4095)];
    }

    private int search(int depth, int alpha, int beta, int ply, boolean nullOk) {
        if (ply > 0 && (half >= 100 || repeated())) return 0;
        boolean check = attacked(king[side], side ^ 1);
        if (check && extOn && ply < MAXPLY - 12) depth++;
        if (depth <= 0) return qOn ? quiesce(alpha, beta, ply) : evaluate();
        if ((++nodes & 1023) == 0) tick();
        if (stop) return 0;
        if (ply >= MAXPLY - 4) return evaluate();
        int ttm = 0;
        int ti = (int) hash & (TT_SIZE - 1);
        if (ttOn && ttKey[ti] == hash) {
            ttm = ttMove[ti];
            int d = ttData[ti], td = (d >>> 16) & 0xFF, tf = d >>> 24, ts = (short) (d & 0xFFFF);
            if (ts > MATE_V - 200) ts -= ply; else if (ts < -MATE_V + 200) ts += ply;
            if (td >= depth && beta - alpha == 1
                    && (tf == EXACT || (tf == LOWER && ts >= beta) || (tf == UPPER && ts <= alpha))) return ts;
        }
        if (nullOn && nullOk && !check && depth >= 3 && beta - alpha == 1 && hasPieces(side) && evaluate() >= beta) {
            makeNull();
            int s = -search(depth - 1 - (depth > 6 ? 3 : 2), -beta, -beta + 1, ply + 1, false);
            unmakeNull();
            if (stop) return 0;
            if (s >= beta) return s >= MATE_V - 200 ? beta : s;
        }
        int base = ply * SLICE, end = gen(mv, base, false);
        for (int i = base; i < end; i++) ms[i] = order(mv[i], ttm, ply);
        int best = -INF, bestMove = 0, legal = 0, a0 = alpha;
        for (int i = base; i < end; i++) {
            int bi = i;
            for (int j = i + 1; j < end; j++) if (ms[j] > ms[bi]) bi = j;
            int m = mv[bi];
            mv[bi] = mv[i]; mv[i] = m;
            int o = ms[bi]; ms[bi] = ms[i]; ms[i] = o;
            boolean quiet = o < 2_000_000 || m == ttm && b[(m >>> 6) & 63] == 0 && flag(m) != F_EP && promo(m) == 0;
            make(m);
            if (attacked(king[side ^ 1], side)) { unmake(m); continue; }
            legal++;
            int s;
            if (legal == 1) s = -search(depth - 1, -beta, -alpha, ply + 1, true);
            else {
                int red = depth >= 3 && legal > 4 && quiet && !check && o < 1_800_000 && !attacked(king[side], side ^ 1) ? 1 : 0;
                s = -search(depth - 1 - red, -alpha - 1, -alpha, ply + 1, true);
                if (!stop && s > alpha && (red > 0 || s < beta)) s = -search(depth - 1, -beta, -alpha, ply + 1, true);
            }
            unmake(m);
            if (stop) return 0;
            if (s > best) { best = s; bestMove = m; }
            if (s > alpha) {
                alpha = s;
                if (s >= beta) {
                    if (quiet) {
                        if (killer[ply][0] != m) { killer[ply][1] = killer[ply][0]; killer[ply][0] = m; }
                        int hi = side << 12 | (m & 4095);
                        histT[hi] += depth * depth;
                        if (histT[hi] > 1_000_000) for (int k = 0; k < histT.length; k++) histT[k] >>= 1;
                    }
                    break;
                }
            }
        }
        if (legal == 0) return check ? -MATE_V + ply : 0;
        if (ttOn) {
            int ts = best > MATE_V - 200 ? best + ply : best < -MATE_V + 200 ? best - ply : best;
            int tf = best >= beta ? LOWER : best > a0 ? EXACT : UPPER;
            ttKey[ti] = hash;
            ttMove[ti] = bestMove;
            ttData[ti] = (ts & 0xFFFF) | Math.min(255, depth) << 16 | tf << 24;
        }
        return best;
    }

    private int quiesce(int alpha, int beta, int ply) {
        if ((++nodes & 1023) == 0) tick();
        if (stop) return 0;
        int stand = evaluate();
        if (stand >= beta || ply >= MAXPLY - 2) return stand;
        if (stand > alpha) alpha = stand;
        int base = ply * SLICE, end = gen(mv, base, true);
        for (int i = base; i < end; i++) ms[i] = order(mv[i], 0, ply);
        for (int i = base; i < end; i++) {
            int bi = i;
            for (int j = i + 1; j < end; j++) if (ms[j] > ms[bi]) bi = j;
            int m = mv[bi];
            mv[bi] = mv[i]; mv[i] = m;
            int o = ms[bi]; ms[bi] = ms[i]; ms[i] = o;
            int victim = flag(m) == F_EP ? P : b[(m >>> 6) & 63] & 7;
            if (promo(m) == 0 && stand + VAL[victim] + 200 < alpha) continue; // (even taking it can't help)
            make(m);
            if (attacked(king[side ^ 1], side)) { unmake(m); continue; }
            int s = -quiesce(-beta, -alpha, ply + 1);
            unmake(m);
            if (stop) return 0;
            if (s >= beta) return s;
            if (s > alpha) alpha = s;
        }
        return alpha;
    }

    private boolean hasPieces(int color) {
        for (int sq = 0; sq < 64; sq++) {
            int p = b[sq];
            if (p != 0 && (p >> 3) == color && (p & 7) != P && (p & 7) != K) return true;
        }
        return false;
    }
}
