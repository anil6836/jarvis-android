package com.anil.jarvis;

import java.util.Arrays;
import java.util.Random;

/**
 * దాడి (nine men's morris) rules and Jarvis's choice (plain Java: tested on the desk).
 *
 * The board: 24 points on three nested squares joined at the side midpoints (no diagonals). Each side has 9 pieces;
 * seat 0 (white) starts. Phase 1: a turn puts one piece from the hand on any empty point. Phase 2 (a side with none
 * left in the hand): a piece moves along a line to the next point, if it is empty. A side with exactly 3 pieces left
 * may "fly": move any of them to any empty point (the usual rule, so the end is not hopeless).
 * Three of one side in a row along a line (మూడు వరుస) → that side takes one opponent piece that is not in a row of
 * three (any piece, when all of them are). Win: the opponent is down to 2 pieces, or can't move. Draw: 50 moves in the
 * moving phase (both sides together) without a piece taken, or the same position with the same side to move for the
 * third time.
 *
 * A move is one int: from (-1: put from the hand), to, and the piece taken (-1: none) — see mv().
 */
final class DaadiRules {
    /** Grid places (0..6) of the 24 points, row by row from the top. */
    static final int[] X = {0, 3, 6, 1, 3, 5, 2, 3, 4, 0, 1, 2, 4, 5, 6, 2, 3, 4, 1, 3, 5, 0, 3, 6};
    static final int[] Y = {0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 5, 5, 5, 6, 6, 6};
    /** The points next to each point along a line. */
    static final int[][] ADJ = {
            {1, 9}, {0, 2, 4}, {1, 14},
            {4, 10}, {1, 3, 5, 7}, {4, 13},
            {7, 11}, {4, 6, 8}, {7, 12},
            {0, 10, 21}, {3, 9, 11, 18}, {6, 10, 15}, {8, 13, 17}, {5, 12, 14, 20}, {2, 13, 23},
            {11, 16}, {15, 17, 19}, {12, 16},
            {10, 19}, {16, 18, 20, 22}, {13, 19},
            {9, 22}, {19, 21, 23}, {14, 22}};
    /** The 16 rows of three (each listed end, middle, end). */
    static final int[][] MILLS = {
            {0, 1, 2}, {3, 4, 5}, {6, 7, 8}, {9, 10, 11}, {12, 13, 14}, {15, 16, 17}, {18, 19, 20}, {21, 22, 23},
            {0, 9, 21}, {3, 10, 18}, {6, 11, 15}, {1, 4, 7}, {16, 19, 22}, {8, 12, 17}, {5, 13, 20}, {2, 14, 23}};
    /** The two rows through each point. */
    static final int[][] MILLS_AT = new int[24][2];

    static {
        int[] n = new int[24];
        for (int m = 0; m < MILLS.length; m++) for (int p : MILLS[m]) MILLS_AT[p][n[p]++] = m;
    }

    static final int PIECES = 9, DRAW_QUIET = 50;
    static final int WIN = 100_000, INF = 1_000_000;
    private static final int MAXPLY = 40, BUF = 640, NOW = 80;

    /** 0 empty, 1 seat 0 (white), 2 seat 1 (dark). */
    final int[] cell = new int[24];
    /** Pieces still in the hand, and on the board, per seat. */
    final int[] hand = {PIECES, PIECES}, count = new int[2];
    int turn;
    /** Moves in the moving phase since a piece was last taken (the draw rule). */
    int quiet;
    /** The last move: from (-1 put from the hand), to, the piece taken (-1 none), and who made it (-1: no move yet). */
    int lastFrom = -1, lastTo = -1, lastRem = -1, lastSeat = -1;
    /** Positions since the last move that can't be undone (a piece put or taken): the repetition rule. */
    long[] hist = new long[80];
    int histN;

    void reset() {
        Arrays.fill(cell, 0);
        hand[0] = hand[1] = PIECES;
        count[0] = count[1] = 0;
        turn = 0;
        quiet = 0;
        lastFrom = lastTo = lastRem = lastSeat = -1;
        histN = 0;
    }

    DaadiRules copy() {
        DaadiRules c = new DaadiRules();
        System.arraycopy(cell, 0, c.cell, 0, 24);
        c.hand[0] = hand[0];
        c.hand[1] = hand[1];
        c.count[0] = count[0];
        c.count[1] = count[1];
        c.turn = turn;
        c.quiet = quiet;
        c.lastFrom = lastFrom;
        c.lastTo = lastTo;
        c.lastRem = lastRem;
        c.lastSeat = lastSeat;
        c.hist = hist.clone();
        c.histN = histN;
        return c;
    }

    // ================================================================ moves

    static int mv(int from, int to, int rem) { return (from + 1) | (to << 5) | ((rem + 1) << 10); }
    static int from(int m) { return (m & 31) - 1; }
    static int to(int m) { return (m >> 5) & 31; }
    static int rem(int m) { return ((m >> 10) & 31) - 1; }

    static boolean adjacent(int a, int b) {
        if (a < 0 || a >= 24) return false;
        for (int q : ADJ[a]) if (q == b) return true;
        return false;
    }

    /** This seat is still putting pieces from the hand. */
    boolean placing(int seat) { return hand[seat] > 0; }

    /** This seat has exactly 3 pieces left (none in the hand): it may move to any empty point. */
    boolean flying(int seat) { return hand[seat] == 0 && count[seat] == 3; }

    /** The piece on p is in a row of three. */
    boolean millAt(int p) {
        int v = cell[p];
        if (v == 0) return false;
        for (int m : MILLS_AT[p]) {
            int[] l = MILLS[m];
            if (cell[l[0]] == v && cell[l[1]] == v && cell[l[2]] == v) return true;
        }
        return false;
    }

    /** Would a piece of value v standing on q (and none on skip) make a row of three through q? */
    private boolean rowWith(int v, int q, int skip) {
        for (int m : MILLS_AT[q]) {
            int[] l = MILLS[m];
            boolean ok = true;
            for (int p : l) if (p != q && (p == skip || cell[p] != v)) { ok = false; break; }
            if (ok) return true;
        }
        return false;
    }

    /** Would seat's piece going from f (-1: from the hand) to t make a row of three? */
    boolean makesRow(int seat, int f, int t) { return rowWith(seat + 1, t, f); }

    /** Would a piece of seat on the empty point t stop the other side's two-in-a-row? */
    boolean blocks(int seat, int t) {
        int opp = 2 - seat;
        for (int m : MILLS_AT[t]) {
            int k = 0;
            for (int p : MILLS[m]) if (p != t && cell[p] == opp) k++;
            if (k == 2) return true;
        }
        return false;
    }

    private boolean allInRows(int v) {
        for (int q = 0; q < 24; q++) if (cell[q] == v && !millAt(q)) return false;
        return true;
    }

    /** The legal moves of the seat to play, into out (at least 640 long); returns how many. */
    int moves(int[] out) {
        int t = turn, me = t + 1, n = 0;
        if (hand[t] > 0) {
            for (int p = 0; p < 24; p++) if (cell[p] == 0) n = add(out, n, -1, p);
        } else if (count[t] == 3) {
            for (int f = 0; f < 24; f++) {
                if (cell[f] != me) continue;
                for (int p = 0; p < 24; p++) if (cell[p] == 0) n = add(out, n, f, p);
            }
        } else {
            for (int f = 0; f < 24; f++) {
                if (cell[f] != me) continue;
                for (int q : ADJ[f]) if (cell[q] == 0) n = add(out, n, f, q);
            }
        }
        return n;
    }

    /** The move f→to, once for each piece it may take when it makes a row of three. */
    private int add(int[] out, int n, int f, int to) {
        if (!rowWith(turn + 1, to, f)) { out[n++] = mv(f, to, -1); return n; }
        int opp = 2 - turn, k = 0;
        boolean all = allInRows(opp);
        for (int r = 0; r < 24; r++) if (cell[r] == opp && (all || !millAt(r))) { out[n++] = mv(f, to, r); k++; }
        if (k == 0) out[n++] = mv(f, to, -1); // (a row, but nothing of theirs on the board to take)
        return n;
    }

    boolean canMove(int seat) {
        if (hand[seat] > 0 || count[seat] == 3) return true;
        int v = seat + 1;
        for (int f = 0; f < 24; f++) {
            if (cell[f] != v) continue;
            for (int q : ADJ[f]) if (cell[q] == 0) return true;
        }
        return false;
    }

    boolean legal(int m) {
        if (winner() != -2) return false;
        int[] b = new int[BUF];
        int n = moves(b);
        for (int i = 0; i < n; i++) if (b[i] == m) return true;
        return false;
    }

    /** Makes the move for the seat to play (the game's own move: kept as the last move, counted for the draw rules). */
    void apply(int m) {
        int s = turn;
        make(m);
        qTop = 0;
        lastFrom = from(m);
        lastTo = to(m);
        lastRem = rem(m);
        lastSeat = s;
        if (lastFrom < 0 || lastRem >= 0) histN = 0;
        if (hand[0] == 0 && hand[1] == 0) {
            if (histN == hist.length) { System.arraycopy(hist, 1, hist, 0, histN - 1); histN--; }
            hist[histN++] = key();
        }
    }

    private final int[] qStack = new int[MAXPLY + 8];
    private int qTop;

    private void make(int m) {
        int f = from(m), t = to(m), r = rem(m);
        qStack[qTop++] = quiet;
        if (f < 0) { hand[turn]--; count[turn]++; } else cell[f] = 0;
        cell[t] = turn + 1;
        if (r >= 0) { cell[r] = 0; count[1 - turn]--; quiet = 0; }
        else if (f >= 0) quiet++;
        else quiet = 0;
        turn ^= 1;
    }

    private void unmake(int m) {
        turn ^= 1;
        int f = from(m), t = to(m), r = rem(m);
        if (r >= 0) { cell[r] = 2 - turn; count[1 - turn]++; }
        cell[t] = 0;
        if (f < 0) { hand[turn]++; count[turn]--; } else cell[f] = turn + 1;
        quiet = qStack[--qTop];
    }

    /** The position (board + side to move) as one number. */
    long key() {
        long k = 0;
        for (int i = 23; i >= 0; i--) k = k * 3 + cell[i];
        return k * 2 + turn;
    }

    private int timesSeen(long k) {
        int c = 0;
        for (int i = 0; i < histN; i++) if (hist[i] == k) c++;
        return c;
    }

    /** How many times the position now has been seen (since the last piece put or taken). */
    int repeats() { return histN == 0 ? 0 : timesSeen(hist[histN - 1]); }

    /** -2 still playing, -1 a draw, 0 / 1 the seat that won. */
    int winner() {
        for (int s = 0; s < 2; s++) if (hand[s] + count[s] < 3) return 1 - s;
        if (!canMove(turn)) return 1 - turn;
        if (quiet >= DRAW_QUIET) return -1;
        if (repeats() >= 3) return -1;
        return -2;
    }

    // ================================================================ Jarvis's choice

    private int[][] buf, keys;
    private long deadline;
    private boolean stop, mayStop;
    private int nodes;

    /**
     * Jarvis's move for the seat to play, or -1 when there is none. Level 0: often a plain move, otherwise one move
     * ahead with a wobbly judgement (she can win); 1: two moves ahead, sometimes careless; 2: searches as deep as the
     * time allows. ms ≤ 0: the level's own time. onlyTo ≥ 0: only moves onlyFrom → onlyTo (a move already shown on the
     * screen: then it only chooses the piece to take).
     */
    int choose(int level, Random r, int onlyFrom, int onlyTo, long ms) {
        if (buf == null) { buf = new int[MAXPLY][BUF]; keys = new int[MAXPLY][BUF]; }
        if (winner() != -2) return -1;
        int[] root = new int[BUF];
        int n = moves(root);
        if (onlyTo >= 0) {
            int k = 0;
            for (int i = 0; i < n; i++) if (to(root[i]) == onlyTo && from(root[i]) == onlyFrom) root[k++] = root[i];
            n = k;
        }
        if (n == 0) return -1;
        if (n == 1) return root[0];
        for (int i = n - 1; i > 0; i--) { int j = r.nextInt(i + 1), x = root[i]; root[i] = root[j]; root[j] = x; } // (equal moves vary)
        double careless = level <= 0 ? 0.45 : level == 1 ? 0.12 : 0;
        if (r.nextDouble() < careless) {
            int rows = 0;
            for (int i = 0; i < n; i++) if (rem(root[i]) >= 0) rows++;
            if (rows > 0 && r.nextDouble() < (level <= 0 ? 0.6 : 0.85)) { // (even careless, he often sees his own row)
                int k = r.nextInt(rows);
                for (int i = 0; i < n; i++) if (rem(root[i]) >= 0 && k-- == 0) return root[i];
            }
            return root[r.nextInt(n)];
        }
        int maxDepth = level <= 0 ? 1 : level == 1 ? 2 : MAXPLY - 4;
        if (ms <= 0) ms = level <= 0 ? 80 : level == 1 ? 200 : 350;
        int noise = level <= 0 ? 70 : level == 1 ? 20 : 0;
        int[] nz = new int[n], sc = new int[n];
        if (noise > 0) for (int i = 0; i < n; i++) nz[i] = r.nextInt(2 * noise + 1) - noise;
        deadline = System.nanoTime() + ms * 1_000_000L;
        stop = false;
        nodes = 0;
        qTop = 0;
        int best = root[0];
        for (int d = 1; d <= maxDepth; d++) {
            mayStop = d > 1; // (the first depth always finishes)
            int alpha = -INF, bi = -1, bs = -INF;
            for (int i = 0; i < n; i++) {
                int m = root[i];
                // a piece taken now is worth a little more than the same gain later (and a hint says the plain thing)
                int bonus = rem(m) >= 0 ? NOW : 0;
                make(m);
                int s;
                if (from(m) >= 0 && rem(m) < 0 && hand[0] == 0 && hand[1] == 0 && timesSeen(key()) >= 2) s = 0; // (a third time: a draw)
                else s = -negamax(d - 1, -INF, noise > 0 ? INF : -(alpha - bonus), 1) + bonus;
                unmake(m);
                if (stop) break;
                s += nz[i];
                sc[i] = s;
                if (s > bs) { bs = s; bi = i; }
                if (s > alpha) alpha = s;
            }
            if (stop || bi < 0) break;
            best = root[bi];
            // the best first for the next depth (then by this depth's scores)
            for (int i = 1; i < n; i++) {
                int m = root[i], z = nz[i], s = sc[i], j = i - 1;
                while (j >= 0 && sc[j] < s) { root[j + 1] = root[j]; nz[j + 1] = nz[j]; sc[j + 1] = sc[j]; j--; }
                root[j + 1] = m; nz[j + 1] = z; sc[j + 1] = s;
            }
            if (bs >= WIN - 200 || bs <= -WIN + 200) break; // (a sure result: deeper won't change it)
        }
        mayStop = false;
        return best;
    }

    private int negamax(int depth, int alpha, int beta, int ply) {
        if (mayStop && (++nodes & 511) == 0 && System.nanoTime() > deadline) stop = true;
        if (stop) return 0;
        int t = turn;
        if (hand[t] + count[t] < 3) return -WIN + ply;
        if (quiet >= DRAW_QUIET) return 0;
        if (depth <= 0 || ply >= MAXPLY - 1) return eval(ply);
        int[] ms = buf[ply];
        int n = moves(ms);
        if (n == 0) return -WIN + ply;
        order(ms, n, ply);
        int best = -INF;
        for (int i = 0; i < n; i++) {
            make(ms[i]);
            int s = -negamax(depth - 1, -beta, -alpha, ply + 1);
            unmake(ms[i]);
            if (stop) return 0;
            if (s > best) {
                best = s;
                if (s > alpha) { alpha = s; if (alpha >= beta) break; }
            }
        }
        return best;
    }

    /** Rows of three first, then moves that stop the other side's row. */
    private void order(int[] ms, int n, int ply) {
        int[] k = keys[ply];
        int opp = 2 - turn;
        for (int i = 0; i < n; i++) {
            int m = ms[i], t = to(m), s = 0;
            if (rem(m) >= 0) s += 1000;
            for (int mi : MILLS_AT[t]) {
                int o = 0;
                for (int p : MILLS[mi]) if (p != t && cell[p] == opp) o++;
                if (o == 2) s += 300;
            }
            k[i] = s;
        }
        for (int i = 1; i < n; i++) {
            int m = ms[i], s = k[i], j = i - 1;
            while (j >= 0 && k[j] < s) { ms[j + 1] = ms[j]; k[j + 1] = k[j]; j--; }
            ms[j + 1] = m;
            k[j + 1] = s;
        }
    }

    /**
     * How good the position is for the side to move: pieces, rows of three, two-in-a-row with the third point empty
     * (more when a piece can get there next move), pieces shut in, room to move, and "double rows" (a piece that can
     * step out of a row into another one, and back).
     */
    private int eval(int ply) {
        int t = turn, o = 1 - t, me = t + 1, op = o + 1;
        int s = 120 * (hand[t] + count[t] - hand[o] - count[o]);
        int rowsT = 0, rowsO = 0, twoT = 0, twoO = 0, openT = 0, openO = 0;
        for (int[] l : MILLS) {
            int a = 0, b = 0, e = -1;
            for (int p : l) {
                int v = cell[p];
                if (v == me) a++;
                else if (v == op) b++;
                else e = p;
            }
            if (a == 3) rowsT++;
            else if (b == 3) rowsO++;
            else if (a == 2 && b == 0) { if (reach(t, e, l)) openT++; else twoT++; }
            else if (b == 2 && a == 0) { if (reach(o, e, l)) openO++; else twoO++; }
        }
        s += 25 * (rowsT - rowsO) + 18 * (openT - openO) + 5 * (twoT - twoO);
        if (openT > 0) s += 45; // (the side to move can make a row now)
        if (openO > 1) s -= 30; // (two threats: only one can be stopped)
        int mobT = 0, mobO = 0, blkT = 0, blkO = 0;
        for (int p = 0; p < 24; p++) {
            int v = cell[p];
            if (v == 0) continue;
            int f = 0;
            for (int q : ADJ[p]) if (cell[q] == 0) f++;
            if (v == me) { mobT += f; if (f == 0) blkT++; }
            else { mobO += f; if (f == 0) blkO++; }
        }
        boolean movT = hand[t] == 0, movO = hand[o] == 0;
        if (movT && count[t] > 3 && mobT == 0) return -WIN + ply; // (shut in: the side to move has lost)
        int wm = movT && movO ? 4 : 1, wb = movT && movO ? 8 : 3;
        if (!flying(t)) s += wm * mobT - wb * blkT;
        if (!flying(o)) s -= wm * mobO - wb * blkO;
        if (movT && count[t] > 3) s += 30 * doubles(me);
        if (movO && count[o] > 3) s -= 30 * doubles(op);
        return s;
    }

    /** Can seat get a piece onto the empty point e of this row next move (from outside the row)? */
    private boolean reach(int seat, int e, int[] line) {
        if (hand[seat] > 0 || count[seat] == 3) return true;
        int v = seat + 1;
        for (int q : ADJ[e]) if (cell[q] == v && q != line[0] && q != line[1] && q != line[2]) return true;
        return false;
    }

    private int doubles(int v) {
        int n = 0;
        for (int p = 0; p < 24; p++) {
            if (cell[p] != v || !millAt(p)) continue;
            for (int q : ADJ[p]) if (cell[q] == 0 && rowWith(v, q, p)) { n++; break; }
        }
        return n;
    }

    // ================================================================ words

    static final String[] RING = {"బయటి చదరం", "మధ్య చదరం", "లోపలి చదరం"};

    /** 0 the outer square, 1 the middle, 2 the inner. */
    static int ring(int p) { return Math.min(Math.min(X[p], 6 - X[p]), Math.min(Y[p], 6 - Y[p])); }

    /** Where a point is, waiting for its case ending: "బయటి చదరంలో పై ఎడమ మూల" (+ "లో" / "కి"). */
    static String where(int p) {
        if (p < 0 || p >= 24) return "";
        int dx = Integer.signum(X[p] - 3), dy = Integer.signum(Y[p] - 3);
        String spot;
        if (dy < 0) spot = dx < 0 ? "పై ఎడమ మూల" : dx == 0 ? "పై మధ్య" : "పై కుడి మూల";
        else if (dy == 0) spot = dx < 0 ? "ఎడమ పక్క మధ్య" : "కుడి పక్క మధ్య";
        else spot = dx < 0 ? "కింది ఎడమ మూల" : dx == 0 ? "కింది మధ్య" : "కింది కుడి మూల";
        return RING[ring(p)] + "లో " + spot;
    }

    /** Which way a step goes ("లోపలికి", "కుడికి"…). */
    static String dir(int f, int t) {
        if (f < 0 || t < 0 || f >= 24 || t >= 24) return "";
        int rf = ring(f), rt = ring(t);
        if (rt > rf) return "లోపలికి";
        if (rt < rf) return "బయటికి";
        if (X[t] > X[f]) return "కుడికి";
        if (X[t] < X[f]) return "ఎడమకి";
        return Y[t] > Y[f] ? "కిందకి" : "పైకి";
    }

    // ================================================================ saving

    String save() {
        StringBuilder b = new StringBuilder("v1;");
        for (int v : cell) b.append(v);
        b.append(';').append(hand[0]).append(',').append(hand[1]).append(';').append(turn).append(';').append(quiet).append(';')
                .append(lastFrom).append(',').append(lastTo).append(',').append(lastRem).append(',').append(lastSeat).append(';');
        if (histN == 0) b.append('-');
        for (int i = 0; i < histN; i++) b.append(i == 0 ? "" : ",").append(Long.toString(hist[i], 36));
        return b.toString();
    }

    boolean load(String s) {
        try {
            String[] p = s.split(";", -1);
            if (p.length != 7 || !p[0].equals("v1") || p[1].length() != 24) return false;
            int[] c = new int[24], cnt = new int[2];
            for (int i = 0; i < 24; i++) {
                int v = p[1].charAt(i) - '0';
                if (v < 0 || v > 2) return false;
                c[i] = v;
                if (v > 0) cnt[v - 1]++;
            }
            String[] h = p[2].split(",");
            if (h.length != 2) return false;
            int h0 = Integer.parseInt(h[0]), h1 = Integer.parseInt(h[1]);
            if (h0 < 0 || h1 < 0 || h0 + cnt[0] > PIECES || h1 + cnt[1] > PIECES) return false;
            int tu = Integer.parseInt(p[3]), q = Integer.parseInt(p[4]);
            if ((tu != 0 && tu != 1) || q < 0 || q > 100_000) return false;
            String[] l = p[5].split(",");
            if (l.length != 4) return false;
            int lf = Integer.parseInt(l[0]), lt = Integer.parseInt(l[1]), lr = Integer.parseInt(l[2]), ls = Integer.parseInt(l[3]);
            if (lf < -1 || lf > 23 || lt < -1 || lt > 23 || lr < -1 || lr > 23 || ls < -1 || ls > 1) return false;
            long[] hs = new long[80];
            int hn = 0;
            if (!p[6].equals("-") && !p[6].isEmpty()) {
                for (String x : p[6].split(",")) {
                    if (hn == hs.length) return false;
                    hs[hn++] = Long.parseLong(x, 36);
                }
            }
            System.arraycopy(c, 0, cell, 0, 24);
            hand[0] = h0;
            hand[1] = h1;
            count[0] = cnt[0];
            count[1] = cnt[1];
            turn = tu;
            quiet = q;
            lastFrom = lf;
            lastTo = lt;
            lastRem = lr;
            lastSeat = ls;
            hist = hs;
            histN = hn;
            qTop = 0;
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
