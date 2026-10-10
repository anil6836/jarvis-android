package com.anil.jarvis;

import java.util.Random;

/**
 * పులి-మేక (ఆడు పులి ఆట, goats and tigers) rules and Jarvis's choice (plain Java: tested on the desk).
 *
 * The board is the traditional South Indian 23-point board: a triangle (apex at the top, two more lines from the
 * apex down to the base) crossed by a rectangle with a middle line. Points: the apex (0); three rows of six — the
 * rectangle's side point, the four lines of the triangle, the other side point — (1..6, 7..12, 13..18); and the
 * base of four (19..22). Lines: the four rows, the four lines down from the apex, and the two short sides of the
 * rectangle (1-7-13, 6-12-18). Pieces move along a line to the next point; a tiger may also jump along a straight
 * line over the next point (a goat) to the empty point just beyond it, and that goat is taken. X / Y below are the
 * points' places (0..100 across and down); every line is straight in them.
 *
 * Seat 0 plays the 15 goats (all in hand at the start), seat 1 the 3 tigers, which start on the apex and the two
 * inner points just below it. The goats move first (Funskool's and indianetzone's rules). While goats are in hand,
 * a goat turn places one goat on any empty point (goats on the board don't move yet); after all 15 are placed, a
 * goat moves to a neighbouring empty point. A tiger moves to a neighbouring empty point or jumps one goat (one goat
 * per turn, never over a tiger). The tigers win when they have taken 5 goats (the number Indian Heritage (Singapore)
 * and New Venture Games give: after that the goats can't shut them in); the goats win when the tigers can't move on
 * their turn (and goats that can't move lose). After all goats are placed, 60 moves in a row (both sides counted)
 * without a capture is a draw.
 */
final class PuliRules {
    static final int N = 23, GOATS = 15, WIN = 5, QUIET = 60;
    static final int EMPTY = 0, GOAT = 1, TIGER = 2;
    /** A move is from * 32 + to; from = PLACE: a goat from the hand. */
    static final int PLACE = 31;
    static final float[] X = new float[N], Y = new float[N];
    /** The straight lines, point by point. */
    static final int[][] LINES = {
            {1, 2, 3, 4, 5, 6}, {7, 8, 9, 10, 11, 12}, {13, 14, 15, 16, 17, 18}, {19, 20, 21, 22},
            {0, 2, 8, 14, 19}, {0, 3, 9, 15, 20}, {0, 4, 10, 16, 21}, {0, 5, 11, 17, 22},
            {1, 7, 13}, {6, 12, 18}};
    static final int[][] ADJ = new int[N][];
    /** JUMPS[p] = {over, to, over, to, …}: the jumps a tiger on p can make along a line. */
    static final int[][] JUMPS = new int[N][];
    /** OVER[from][to]: the point jumped over, or -1 (not a jump). */
    static final int[][] OVER = new int[N][N];
    static final int[] START = {0, 3, 4};

    static {
        // the apex at (50, 4); the base at y 96 (x 10 … 90); the three rows at y 34, 55, 76; side points at x 2 / 98
        float[] baseX = {10, 36.6667f, 63.3333f, 90}, rowY = {34, 55, 76};
        X[0] = 50; Y[0] = 4;
        for (int r = 0; r < 3; r++) {
            int b = 1 + r * 6;
            X[b] = 2; Y[b] = rowY[r];
            X[b + 5] = 98; Y[b + 5] = rowY[r];
            for (int k = 0; k < 4; k++) { X[b + 1 + k] = 50 + (baseX[k] - 50) * (rowY[r] - 4) / 92f; Y[b + 1 + k] = rowY[r]; }
        }
        for (int k = 0; k < 4; k++) { X[19 + k] = baseX[k]; Y[19 + k] = 96; }
        int[][] adj = new int[N][N];
        int[] na = new int[N], nj = new int[N];
        int[][] jumps = new int[N][2 * N];
        for (int[] a : OVER) java.util.Arrays.fill(a, -1);
        for (int[] l : LINES)
            for (int i = 0; i < l.length; i++) {
                if (i + 1 < l.length) { adj[l[i]][na[l[i]]++] = l[i + 1]; adj[l[i + 1]][na[l[i + 1]]++] = l[i]; }
                if (i + 2 < l.length) {
                    int a = l[i], o = l[i + 1], b = l[i + 2];
                    jumps[a][nj[a]++] = o; jumps[a][nj[a]++] = b;
                    jumps[b][nj[b]++] = o; jumps[b][nj[b]++] = a;
                    OVER[a][b] = o; OVER[b][a] = o;
                }
            }
        for (int p = 0; p < N; p++) { ADJ[p] = java.util.Arrays.copyOf(adj[p], na[p]); JUMPS[p] = java.util.Arrays.copyOf(jumps[p], nj[p]); }
    }

    final int[] cell = new int[N];
    int inHand = GOATS, captured, turn, quiet, moves;
    /** The last move: its seat, from (PLACE for a placed goat), to, the goat taken (-1). */
    int lastSeat = -1, lastFrom = -1, lastTo = -1, lastOver = -1;

    PuliRules() { for (int p : START) cell[p] = TIGER; }

    PuliRules copy() {
        PuliRules c = new PuliRules();
        System.arraycopy(cell, 0, c.cell, 0, N);
        c.inHand = inHand; c.captured = captured; c.turn = turn; c.quiet = quiet; c.moves = moves;
        c.lastSeat = lastSeat; c.lastFrom = lastFrom; c.lastTo = lastTo; c.lastOver = lastOver;
        return c;
    }

    static int from(int m) { return m >> 5; }
    static int to(int m) { return m & 31; }
    static int move(int from, int to) { return from * 32 + to; }

    /** Goats are still being placed. */
    boolean placing() { return inHand > 0; }

    /** All moves for the seat to play into buf (room for 128); returns how many. */
    int moves(int[] buf) {
        int n = 0;
        if (turn == 0) {
            if (inHand > 0) { for (int p = 0; p < N; p++) if (cell[p] == EMPTY) buf[n++] = move(PLACE, p); return n; }
            for (int p = 0; p < N; p++) if (cell[p] == GOAT) for (int q : ADJ[p]) if (cell[q] == EMPTY) buf[n++] = move(p, q);
            return n;
        }
        for (int p = 0; p < N; p++) {
            if (cell[p] != TIGER) continue;
            int[] j = JUMPS[p];
            for (int i = 0; i < j.length; i += 2) if (cell[j[i]] == GOAT && cell[j[i + 1]] == EMPTY) buf[n++] = move(p, j[i + 1]);
            for (int q : ADJ[p]) if (cell[q] == EMPTY) buf[n++] = move(p, q);
        }
        return n;
    }

    /** The moves of the piece on p (the seat to play), into buf; returns how many. */
    int movesFrom(int p, int[] buf) {
        int n = 0, all = moves(scratch);
        for (int i = 0; i < all; i++) if (from(scratch[i]) == p) buf[n++] = scratch[i];
        return n;
    }

    private final int[] scratch = new int[128];

    boolean legal(int m) {
        if (result() != -2) return false;
        int n = moves(scratch);
        for (int i = 0; i < n; i++) if (scratch[i] == m) return true;
        return false;
    }

    /** Makes a move (legal: see legal()). */
    void apply(int m) {
        int f = from(m), t = to(m), over = -1;
        boolean moving = inHand == 0;
        if (f == PLACE) { cell[t] = GOAT; inHand--; }
        else {
            int piece = cell[f];
            cell[f] = EMPTY;
            cell[t] = piece;
            if (piece == TIGER && OVER[f][t] >= 0 && cell[OVER[f][t]] == GOAT) { over = OVER[f][t]; cell[over] = EMPTY; captured++; }
        }
        if (over >= 0) quiet = 0;
        else if (moving) quiet++;
        lastSeat = turn; lastFrom = f; lastTo = t; lastOver = over;
        turn = 1 - turn;
        moves++;
    }

    /** -2 still playing, -1 a draw, 0 the goats won, 1 the tigers won. */
    int result() {
        if (captured >= WIN) return 1;
        if (quiet >= QUIET) return -1;
        if (moves(scratch) == 0) return turn == 1 ? 0 : 1;
        return -2;
    }

    /** Tigers that can't move now (shut in). */
    int trapped() {
        int n = 0;
        for (int p = 0; p < N; p++) if (cell[p] == TIGER && !canGo(p)) n++;
        return n;
    }

    /** The tiger on p can step or jump. */
    boolean canGo(int p) {
        for (int q : ADJ[p]) if (cell[q] == EMPTY) return true;
        int[] j = JUMPS[p];
        for (int i = 0; i < j.length; i += 2) if (cell[j[i]] == GOAT && cell[j[i + 1]] == EMPTY) return true;
        return false;
    }

    /** Goats a tiger could take right now (each goat once). */
    int threats() {
        int n = 0;
        long seen = 0;
        for (int p = 0; p < N; p++) {
            if (cell[p] != TIGER) continue;
            int[] j = JUMPS[p];
            for (int i = 0; i < j.length; i += 2)
                if (cell[j[i]] == GOAT && cell[j[i + 1]] == EMPTY && (seen & (1L << j[i])) == 0) { seen |= 1L << j[i]; n++; }
        }
        return n;
    }

    // ================================================================ Jarvis's choice

    private static final int INF = 1_000_000, WON = 100_000;
    private long nodes, nodeCap, deadline;
    private boolean stopped;

    /**
     * Jarvis's move for the seat to play (-1 when none): level 0 often any move and otherwise a one-move look; level 1 a
     * three-move look with a little carelessness; level 2 a deeper alpha-beta search (deepening until ms or the node
     * budget run out).
     */
    int choose(int level, Random r, long ms) {
        int[] all = new int[128];
        int n = moves(all);
        if (n == 0) return -1;
        if (n == 1) return all[0];
        if (level <= 1 && turn == 1) {
            // a kind tiger often doesn't see the goat it could take (the goats are the harder side for a beginner)
            int k = 0;
            for (int i = 0; i < n; i++) if (OVER[from(all[i])][to(all[i])] < 0) all[k++] = all[i];
            boolean sees = r.nextDouble() < (level <= 0 ? 0.2 : 0.35);
            if (k > 0 && k < n && !sees) n = k; // (only its plain moves)
            else n = moves(all);
            if (level <= 0) return r.nextDouble() < 0.5 ? all[r.nextInt(n)] : search(all, n, 1, 250, r, 50_000, ms);
            return search(all, n, 2, 120, r, 200_000, ms);
        }
        if (level <= 0 && r.nextDouble() < 0.35) return all[r.nextInt(n)];
        if (level == 1 && r.nextDouble() < 0.1) return all[r.nextInt(n)];
        if (level <= 0) return search(all, n, 1, 250, r, 50_000, ms);
        if (level == 1) return search(all, n, 3, 60, r, 200_000, ms);
        return search(all, n, 14, 0, r, 260_000, ms);
    }

    /** A good move for a hint (quick: a four-move look). */
    int best() {
        int[] all = new int[128];
        int n = moves(all);
        if (n == 0) return -1;
        return search(all, n, 4, 0, new Random(1), 40_000, 400);
    }

    /** Iterative deepening alpha-beta at the root (noise: random points added to each root move's score). */
    private int search(int[] root, int n, int maxDepth, int noise, Random r, long cap, long ms) {
        nodes = 0;
        nodeCap = cap;
        deadline = System.currentTimeMillis() + Math.max(20, ms);
        stopped = false;
        int[] score = new int[n];
        int best = root[0];
        orderCaptures(root, n);
        for (int depth = 1; depth <= maxDepth; depth++) {
            int alpha = -INF, bestNow = root[0], bestS = -INF;
            int[] sc = new int[n];
            for (int i = 0; i < n; i++) {
                int m = root[i];
                int u = make(m);
                // (with noise every root move gets its true score; else only the best ones need it)
                int s = -negamax(depth - 1, -INF, noise > 0 ? INF : -alpha + 1, 1);
                unmake(m, u);
                if (stopped) break;
                sc[i] = s;
                if (s > bestS) { bestS = s; bestNow = m; }
                if (s > alpha) alpha = s;
            }
            if (stopped) break;
            System.arraycopy(sc, 0, score, 0, n);
            best = bestNow;
            // the best first next time
            for (int i = 0; i < n; i++) if (root[i] == best) { int t = root[0]; root[0] = root[i]; root[i] = t; int ts = score[0]; score[0] = score[i]; score[i] = ts; break; }
            if (bestS >= WON - 100 || bestS <= -WON + 100) break; // (a sure win / loss: no need to look deeper)
        }
        if (noise <= 0) {
            // among moves as good as the best, any (variety)
            int top = -INF;
            for (int i = 0; i < n; i++) top = Math.max(top, score[i]);
            if (top == -INF) return best;
            int k = 0;
            for (int i = 0; i < n; i++) if (score[i] == top) k++;
            if (k <= 1 || score[0] != top) return best;
            int pick = r.nextInt(k);
            for (int i = 0; i < n; i++) if (score[i] == top && pick-- == 0) return root[i];
            return best;
        }
        int bi = 0, bs = -INF;
        for (int i = 0; i < n; i++) { int s = score[i] + r.nextInt(2 * noise + 1) - noise; if (s > bs) { bs = s; bi = i; } }
        return root[bi];
    }

    private void orderCaptures(int[] m, int n) {
        int k = 0;
        for (int i = 0; i < n; i++) {
            int f = from(m[i]);
            if (f != PLACE && OVER[f][to(m[i])] >= 0) { int t = m[k]; m[k] = m[i]; m[i] = t; k++; }
        }
    }

    /** Score for the seat to play. */
    private int negamax(int depth, int alpha, int beta, int ply) {
        if ((++nodes & 1023) == 0 && (nodes > nodeCap || System.currentTimeMillis() > deadline)) stopped = true;
        if (stopped) return 0;
        if (captured >= WIN) return turn == 1 ? WON - ply : -(WON - ply);
        if (quiet >= QUIET) return 0;
        if (ply >= bufs.length) return turn == 1 ? eval() : -eval();
        int[] buf = bufs[ply];
        int n = moves(buf);
        if (n == 0) return -(WON - ply); // (the side to play can't move: it lost)
        if (depth <= 0) return turn == 1 ? eval() : -eval();
        orderCaptures(buf, n);
        int best = -INF;
        for (int i = 0; i < n; i++) {
            int m = buf[i];
            int u = make(m);
            int s = -negamax(depth - 1, -beta, -alpha, ply + 1);
            unmake(m, u);
            if (stopped) return 0;
            if (s > best) best = s;
            if (s > alpha) alpha = s;
            if (alpha >= beta) break;
        }
        return best;
    }

    /** The position for the tigers (+) or the goats (-). */
    int eval() {
        int mob = 0, trapped = 0;
        for (int p = 0; p < N; p++) {
            if (cell[p] != TIGER) continue;
            int m = 0;
            for (int q : ADJ[p]) if (cell[q] == EMPTY) m++;
            int[] j = JUMPS[p];
            for (int i = 0; i < j.length; i += 2) if (cell[j[i]] == GOAT && cell[j[i + 1]] == EMPTY) m += 2;
            if (m == 0) trapped++;
            mob += m;
        }
        int th = threats();
        int s = captured * 1000 + mob * 12 - trapped * 90;
        if (turn == 1) s += th > 0 ? 700 : 0; // (a tiger to move takes one)
        else s += th >= 2 ? 450 : th * 110;    // (goats can stop only one threat)
        return s;
    }

    private final int[][] bufs = new int[24][128];

    /** Makes a move in the search; returns what unmake needs (the goat taken + 1, and the quiet count). */
    private int make(int m) {
        int f = from(m), t = to(m), over = -1, q = quiet;
        boolean moving = inHand == 0;
        if (f == PLACE) { cell[t] = GOAT; inHand--; }
        else {
            int piece = cell[f];
            cell[f] = EMPTY;
            cell[t] = piece;
            if (piece == TIGER && OVER[f][t] >= 0 && cell[OVER[f][t]] == GOAT) { over = OVER[f][t]; cell[over] = EMPTY; captured++; }
        }
        if (over >= 0) quiet = 0;
        else if (moving) quiet++;
        turn = 1 - turn;
        return (over + 1) | (q << 8);
    }

    private void unmake(int m, int u) {
        int f = from(m), t = to(m), over = (u & 255) - 1;
        turn = 1 - turn;
        quiet = u >> 8;
        if (f == PLACE) { cell[t] = EMPTY; inHand++; return; }
        cell[f] = cell[t];
        cell[t] = EMPTY;
        if (over >= 0) { cell[over] = GOAT; captured--; }
    }

    // ================================================================ saving

    /** "v1;cells;inHand;captured;turn;quiet;moves;lastSeat,lastFrom,lastTo,lastOver". */
    String save() {
        StringBuilder b = new StringBuilder("v1;");
        for (int v : cell) b.append(v);
        return b.append(';').append(inHand).append(';').append(captured).append(';').append(turn).append(';').append(quiet)
                .append(';').append(moves).append(';').append(lastSeat).append(',').append(lastFrom).append(',').append(lastTo)
                .append(',').append(lastOver).toString();
    }

    boolean load(String s) {
        try {
            if (s == null || !s.startsWith("v1;")) return false;
            String[] p = s.split(";", -1);
            if (p.length < 8 || p[1].length() != N) return false;
            int[] c = new int[N];
            int goats = 0, tigers = 0;
            for (int i = 0; i < N; i++) {
                c[i] = p[1].charAt(i) - '0';
                if (c[i] < 0 || c[i] > 2) return false;
                if (c[i] == GOAT) goats++;
                if (c[i] == TIGER) tigers++;
            }
            int hand = Integer.parseInt(p[2]), cap = Integer.parseInt(p[3]), tu = Integer.parseInt(p[4]), q = Integer.parseInt(p[5]), mv = Integer.parseInt(p[6]);
            if (tigers != 3 || hand < 0 || cap < 0 || goats + hand + cap != GOATS || (tu != 0 && tu != 1) || q < 0 || mv < 0) return false;
            String[] l = p[7].split(",");
            if (l.length != 4) return false;
            int ls = Integer.parseInt(l[0]), lf = Integer.parseInt(l[1]), lt = Integer.parseInt(l[2]), lo = Integer.parseInt(l[3]);
            if (ls < -1 || ls > 1 || lf < -1 || (lf >= N && lf != PLACE) || lt < -1 || lt >= N || lo < -1 || lo >= N) return false;
            System.arraycopy(c, 0, cell, 0, N);
            inHand = hand; captured = cap; turn = tu; quiet = q; moves = mv;
            lastSeat = ls; lastFrom = lf; lastTo = lt; lastOver = lo;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ================================================================ words

    /** Where a point is ("పై వరుసలో మధ్య ఎడమ చుక్క మీద"). */
    static String where(int p) {
        if (p == 0) return "పై కొన మీద";
        if (p >= 19 && p < N) return "అడుగు వరుసలో " + new String[]{"ఎడమ మూల", "మధ్య ఎడమ చుక్క", "మధ్య కుడి చుక్క", "కుడి మూల"}[p - 19] + " మీద";
        if (p < 1 || p >= N) return "";
        String row = new String[]{"పై వరుసలో ", "మధ్య వరుసలో ", "కింది వరుసలో "}[(p - 1) / 6];
        String col = new String[]{"ఎడమ చివర చుక్క", "ఎడమ పక్క చుక్క", "మధ్య ఎడమ చుక్క", "మధ్య కుడి చుక్క", "కుడి పక్క చుక్క", "కుడి చివర చుక్క"}[(p - 1) % 6];
        return row + col + " మీద";
    }

    /** Which way a piece went ("పైకి", "కిందకి", "ఎడమ వైపు", "కుడి వైపు"). */
    static String way(int from, int to) {
        if (from < 0 || from >= N || to < 0 || to >= N) return "";
        float dx = X[to] - X[from], dy = Y[to] - Y[from];
        if (Math.abs(dy) < 0.3f * Math.abs(dx)) return dx < 0 ? "ఎడమ వైపు" : "కుడి వైపు";
        return dy < 0 ? "పైకి" : "కిందకి";
    }
}
