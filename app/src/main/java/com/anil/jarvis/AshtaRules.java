package com.anil.jarvis;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * అష్టా చమ్మా rules and Jarvis's choice (plain Java: tested on the desk).
 *
 * The board is 5×5 (cell = row * 5 + col, row 0 at the top). Safe squares, drawn with a big X: the middle square of
 * each outer side (a player's start / house) and the centre (home, where pawns "పండుతాయి"). Seats sit on the sides:
 * 2 players → bottom and top; 3 → bottom, right, top; 4 → bottom, right, top, left. Seats play in that order.
 *
 * A throw is 4 cowrie shells, each mouth-up with probability 1/2: the value is how many are mouth-up (1, 2, 3); all
 * four up = 4 ("చమ్మా"), none up = 8 ("అష్టా"). A 4 or an 8 gives another throw. The throws of a turn are banked in
 * order and each one moves one pawn (a throw no pawn can use is skipped).
 *
 * Every pawn starts on its own start square. Its path (positions 0..24 from the start square): round the outer ring
 * anticlockwise (0..15), then the inner ring of 8 squares clockwise (16..23), then the centre (24). A pawn may turn
 * into the inner ring only after its player has captured at least one opponent pawn; until then it keeps going round
 * the outer ring (position 15 wraps to 0). The centre needs an exact throw. Landing on opponent pawn(s) on a square
 * that is not safe sends them all back to their start square, gives one extra throw (after the banked throws) and
 * opens the inner ring for the capturer for the rest of the game. Any number of pawns may share a safe square, and
 * a player's own pawns may share any square. The first player with all pawns home wins (the game ends there).
 */
final class AshtaRules {
    static final int THROW = 0, MOVE = 1;
    static final int OUTER = 16, HOME = 24;
    static final int MAXB = 24; // (banked throws; a longer chain of 4s and 8s just stops throwing)
    /** PATH[side][position] = the cell of that position for a seat on that side (0 bottom, 1 right, 2 top, 3 left). */
    static final int[][] PATH = new int[4][HOME + 1];
    static final boolean[] SAFE = new boolean[25];
    /** REACH[d]: chance that one turn's chain of throws has a running total of exactly d (a pawn could land d ahead). */
    static final double[] REACH = new double[48];

    static {
        int[][] p0 = {{4, 2}, {4, 3}, {4, 4}, {3, 4}, {2, 4}, {1, 4}, {0, 4}, {0, 3}, {0, 2}, {0, 1}, {0, 0}, {1, 0}, {2, 0},
                {3, 0}, {4, 0}, {4, 1}, // outer ring, anticlockwise on the screen
                {3, 1}, {2, 1}, {1, 1}, {1, 2}, {1, 3}, {2, 3}, {3, 3}, {3, 2}, // inner ring, clockwise
                {2, 2}}; // the centre
        for (int s = 0; s < 4; s++)
            for (int i = 0; i <= HOME; i++) {
                int x = p0[i][1] - 2, y = p0[i][0] - 2;
                for (int k = 0; k < s; k++) { int t = x; x = y; y = -t; } // (a quarter turn: bottom → right → top → left)
                PATH[s][i] = (y + 2) * 5 + (x + 2);
            }
        SAFE[22] = SAFE[14] = SAFE[2] = SAFE[10] = SAFE[12] = true;
        double[] pv = new double[9];
        pv[1] = 4 / 16.0; pv[2] = 6 / 16.0; pv[3] = 4 / 16.0; pv[4] = 1 / 16.0; pv[8] = 1 / 16.0;
        for (int d = 1; d < REACH.length; d++) {
            double s = 0;
            for (int x = 1; x <= 8; x++) {
                if (pv[x] == 0) continue;
                if (x == d) s += pv[x];
                else if ((x == 4 || x == 8) && x < d) s += pv[x] * REACH[d - x];
            }
            REACH[d] = s;
        }
    }

    final int n, per;
    final int[][] pos;
    final boolean[] open;
    int turn, phase = THROW;
    final int[] bank = new int[MAXB];
    int banks, extra;
    /** The last throw: bit i set = shell i mouth-up; -1 before the first throw. */
    int shells = -1;
    /** The last action: 0 none, 1 a throw, 2 a pawn moved. */
    int lastAct, lastSeat = -1, lastPawn = -1, lastFrom = -1, lastTo = -1, lastValue, lastCaught, lastCaughtSeats;
    /** The last move opened the inner ring (its player's first capture). */
    boolean lastOpened;
    /** Throws skipped by the last settle (nothing could move), and the first such value. */
    int skipped, skippedValue;
    int winner = -1, count;

    AshtaRules(int players, int pawns) {
        n = Math.max(2, Math.min(4, players));
        per = Math.max(1, Math.min(4, pawns));
        pos = new int[n][per];
        open = new boolean[n];
    }

    AshtaRules copy() {
        AshtaRules c = new AshtaRules(n, per);
        for (int s = 0; s < n; s++) { System.arraycopy(pos[s], 0, c.pos[s], 0, per); c.open[s] = open[s]; }
        c.turn = turn; c.phase = phase; System.arraycopy(bank, 0, c.bank, 0, MAXB); c.banks = banks; c.extra = extra;
        c.shells = shells; c.lastAct = lastAct; c.lastSeat = lastSeat; c.lastPawn = lastPawn; c.lastFrom = lastFrom;
        c.lastTo = lastTo; c.lastValue = lastValue; c.lastCaught = lastCaught; c.lastCaughtSeats = lastCaughtSeats;
        c.lastOpened = lastOpened; c.skipped = skipped; c.skippedValue = skippedValue; c.winner = winner; c.count = count;
        return c;
    }

    // ================================================================ the board

    /** The side of the board a seat sits on (0 bottom, 1 right, 2 top, 3 left). */
    int side(int seat) { return n == 2 ? seat * 2 : seat; }

    /** The cell of a seat's path position. */
    int cell(int seat, int p) { return PATH[side(seat)][Math.max(0, Math.min(HOME, p))]; }

    /** The start square of a seat. */
    int startCell(int seat) { return cell(seat, 0); }

    int home(int seat) { int h = 0; for (int p : pos[seat]) if (p == HOME) h++; return h; }

    // ================================================================ throwing

    /** Four fair shells: bit i set = shell i landed mouth-up. */
    static int throwShells(Random r) { int b = 0; for (int i = 0; i < 4; i++) if (r.nextBoolean()) b |= 1 << i; return b; }

    /** The throw's value: the mouth-up count, all four up = 4 (చమ్మా), none up = 8 (అష్టా). */
    static int value(int bits) { int up = Integer.bitCount(bits & 15); return up == 0 ? 8 : up; }

    /** A 4 or an 8 throws again. */
    static boolean again(int v) { return v == 4 || v == 8; }

    /** The throw is banked; a 4 / 8 throws again, else the banked throws are used. False when it isn't time to throw. */
    boolean applyThrow(int bits) {
        if (phase != THROW || winner >= 0) return false;
        shells = bits & 15;
        int v = value(shells);
        if (banks < MAXB) bank[banks++] = v;
        lastAct = 1; lastSeat = turn; lastValue = v; lastCaught = 0; lastCaughtSeats = 0; lastOpened = false;
        count++;
        if (!again(v) || banks >= MAXB) phase = MOVE;
        settle();
        return true;
    }

    // ================================================================ moving

    /** Where pawn k of a seat lands with throw t (a path position), or -1 when it can't move. */
    int target(int seat, int k, int t) {
        int p = pos[seat][k];
        if (p >= HOME || t <= 0) return -1;
        if (p < OUTER && !open[seat]) return (p + t) % OUTER;
        int q = p + t;
        return q <= HOME ? q : -1;
    }

    /** The throw now to be used (0 when none). */
    int now() { return phase == MOVE && banks > 0 ? bank[0] : 0; }

    boolean canMove(int k) { return winner < 0 && phase == MOVE && banks > 0 && k >= 0 && k < per && target(turn, k, bank[0]) >= 0; }

    private boolean anyMove(int seat, int t) { for (int k = 0; k < per; k++) if (target(seat, k, t) >= 0) return true; return false; }

    /** The pawns that can use the throw now, one per square (pawns on one square are the same choice). */
    List<Integer> choices() {
        List<Integer> l = new ArrayList<>();
        if (phase != MOVE || banks == 0 || winner >= 0) return l;
        for (int k = 0; k < per; k++) {
            if (target(turn, k, bank[0]) < 0) continue;
            boolean same = false;
            for (int j : l) if (pos[turn][j] == pos[turn][k]) same = true;
            if (!same) l.add(k);
        }
        return l;
    }

    /** The opponent pawns pawn k would send back with the throw now (count). */
    int wouldCatch(int k) {
        int to = canMove(k) ? target(turn, k, bank[0]) : -1;
        return to < 0 ? 0 : caughtAt(turn, cell(turn, to));
    }

    private int caughtAt(int seat, int c) {
        if (SAFE[c]) return 0;
        int m = 0;
        for (int o = 0; o < n; o++) if (o != seat) for (int j = 0; j < per; j++) if (pos[o][j] < HOME && cell(o, pos[o][j]) == c) m++;
        return m;
    }

    /** Pawn k of the seat to play uses the throw now. False when it can't. */
    boolean move(int k) {
        if (!canMove(k)) return false;
        int seat = turn, t = bank[0], from = pos[seat][k], to = target(seat, k, t);
        pos[seat][k] = to;
        System.arraycopy(bank, 1, bank, 0, banks - 1);
        banks--;
        int c = cell(seat, to), caught = 0, seats = 0;
        if (!SAFE[c])
            for (int o = 0; o < n; o++) {
                if (o == seat) continue;
                for (int j = 0; j < per; j++) if (pos[o][j] < HOME && cell(o, pos[o][j]) == c) { pos[o][j] = 0; caught++; seats |= 1 << o; }
            }
        lastOpened = false;
        if (caught > 0) {
            extra++;
            if (!open[seat]) { open[seat] = true; lastOpened = true; }
        }
        lastAct = 2; lastSeat = seat; lastPawn = k; lastFrom = from; lastTo = to; lastValue = t; lastCaught = caught; lastCaughtSeats = seats;
        count++;
        if (home(seat) == per) { winner = seat; skipped = 0; return true; }
        settle();
        return true;
    }

    /** Skips throws no pawn can use; with none left, an extra throw (a capture) or the next seat. */
    void settle() {
        skipped = 0;
        if (winner >= 0 || phase != MOVE) return;
        while (banks > 0 && !anyMove(turn, bank[0])) {
            if (skipped == 0) skippedValue = bank[0];
            skipped++;
            System.arraycopy(bank, 1, bank, 0, banks - 1);
            banks--;
        }
        if (banks > 0) return;
        if (extra > 0) { extra--; phase = THROW; return; }
        turn = (turn + 1) % n;
        phase = THROW;
    }

    // ================================================================ Jarvis's choice

    /**
     * The pawn Jarvis moves with the throw now (-1 when none): level 0 mostly any pawn; level 1 a capture or a safe /
     * less dangerous square; level 2 the full weighing: home > capture (more while the inner ring is still shut) >
     * not being caught > progress.
     */
    int choose(int level, Random r) {
        List<Integer> ch = choices();
        if (ch.isEmpty()) return -1;
        if (ch.size() == 1) return ch.get(0);
        if (r.nextDouble() < (level <= 0 ? 0.85 : level == 1 ? 0.2 : 0)) return ch.get(r.nextInt(ch.size())); // (a careless move)
        int best = ch.get(0);
        double bestS = -1e18;
        for (int k : ch) {
            double s = level >= 2 ? score(k) : simpleScore(k) + r.nextDouble() * 25;
            if (s > bestS) { bestS = s; best = k; }
        }
        return best;
    }

    /** Level 0/1: home, then a capture, then a safe or quiet square. */
    private double simpleScore(int k) {
        int me = turn, t = bank[0], to = target(me, k, t), c = cell(me, to);
        if (to == HOME) return 2000;
        int caught = caughtAt(me, c);
        if (caught > 0) return 1000 + caught * 50 + to;
        double s = SAFE[c] ? 120 : -danger(me, c) * 200;
        if (open[me]) s += (to - pos[me][k]) * 3;
        return s;
    }

    /** Level 2: the full weighing of moving pawn k with the throw now. */
    double score(int k) {
        int me = turn, t = bank[0], from = pos[me][k], to = target(me, k, t);
        int cf = cell(me, from), ct = cell(me, to);
        double s = 0;
        if (to == HOME) s += 1000;
        double caughtWorth = 0;
        int caught = 0;
        if (!SAFE[ct])
            for (int o = 0; o < n; o++) if (o != me) for (int j = 0; j < per; j++)
                if (pos[o][j] < HOME && cell(o, pos[o][j]) == ct) { caught++; caughtWorth += 40 + pos[o][j] * 6; }
        if (caught > 0) { s += 300 + caughtWorth; if (!open[me]) s += 350; }
        // progress (round and round the outer ring is worth little while the inner ring is shut)
        s += open[me] ? (to - from) * 8 : t * 3;
        // being caught: the danger left behind and the danger where it lands (after the move)
        double before = SAFE[cf] ? 0 : danger(me, cf);
        s += before * (30 + from * 5) * 1.6;
        if (to != HOME && !SAFE[ct]) {
            AshtaRules a = copy();
            a.move(k);
            s -= a.danger(me, ct) * (30 + to * 5) * 2.2;
        }
        if (SAFE[ct] && to != HOME) s += 25;
        // a chance to catch someone next turn (worth more while the inner ring is still shut)
        if (to != HOME) s += threat(me, to) * (open[me] ? 60 : 140);
        return s;
    }

    /** Where a seat's pawn at position p is after d steps (its own path), or -1. */
    private int ahead(int seat, int p, int d) {
        if (p >= HOME) return -1;
        if (p < OUTER && !open[seat]) return (p + d) % OUTER;
        return p + d <= HOME ? p + d : -1;
    }

    /** The chance that a pawn of seat on cell c (not safe) is caught before seat plays again (roughly). */
    double danger(int seat, int c) {
        if (SAFE[c]) return 0;
        double safe = 1;
        for (int o = 0; o < n; o++) {
            if (o == seat) continue;
            for (int j = 0; j < per; j++) {
                int q = pos[o][j];
                if (q >= HOME) continue;
                boolean seen = false;
                for (int i = 0; i < j; i++) if (pos[o][i] == q) seen = true;
                if (seen) continue;
                double hit = 0;
                for (int d = 1; d < 20; d++) {
                    int a = ahead(o, q, d);
                    if (a < 0) break;
                    if (cell(o, a) == c) hit += REACH[d];
                }
                safe *= 1 - Math.min(1, hit);
            }
        }
        return 1 - safe;
    }

    /** The chance that seat's pawn at position p can catch someone next turn (roughly). */
    private double threat(int seat, int p) {
        double miss = 1;
        for (int d = 1; d < 20; d++) {
            int a = ahead(seat, p, d);
            if (a < 0) break;
            int c = cell(seat, a);
            if (!SAFE[c] && caughtAt(seat, c) > 0) miss *= 1 - REACH[d];
        }
        return 1 - miss;
    }

    // ================================================================ saving

    /** "v1;n;per;turn;phase;extra;shells;winner;count;open;bank;pawns;last" (plain text, no JSON). */
    String save() {
        StringBuilder b = new StringBuilder("v1;");
        b.append(n).append(';').append(per).append(';').append(turn).append(';').append(phase).append(';').append(extra)
                .append(';').append(shells).append(';').append(winner).append(';').append(count).append(';');
        for (int s = 0; s < n; s++) b.append(open[s] ? '1' : '0');
        b.append(';');
        if (banks == 0) b.append('-');
        for (int i = 0; i < banks; i++) b.append(i == 0 ? "" : ",").append(bank[i]);
        b.append(';');
        for (int s = 0; s < n; s++) {
            if (s > 0) b.append('/');
            for (int k = 0; k < per; k++) b.append(k == 0 ? "" : ",").append(pos[s][k]);
        }
        b.append(';').append(lastAct).append(',').append(lastSeat).append(',').append(lastPawn).append(',').append(lastFrom)
                .append(',').append(lastTo).append(',').append(lastValue).append(',').append(lastCaught).append(',')
                .append(lastCaughtSeats).append(',').append(lastOpened ? 1 : 0).append(',').append(skipped).append(',').append(skippedValue);
        return b.toString();
    }

    /** The game from save(), or null when it can't be read. */
    static AshtaRules parse(String s) {
        try {
            if (s == null || !s.startsWith("v1;")) return null;
            String[] p = s.split(";", -1);
            if (p.length < 13) return null;
            int n = Integer.parseInt(p[1]), per = Integer.parseInt(p[2]);
            if (n < 2 || n > 4 || per < 1 || per > 4) return null;
            AshtaRules g = new AshtaRules(n, per);
            g.turn = Integer.parseInt(p[3]);
            g.phase = Integer.parseInt(p[4]);
            g.extra = Integer.parseInt(p[5]);
            g.shells = Integer.parseInt(p[6]);
            g.winner = Integer.parseInt(p[7]);
            g.count = Integer.parseInt(p[8]);
            if (g.turn < 0 || g.turn >= n || (g.phase != THROW && g.phase != MOVE) || g.extra < 0 || g.extra > 99
                    || g.shells < -1 || g.shells > 15 || g.winner < -1 || g.winner >= n || g.count < 0) return null;
            if (p[9].length() != n) return null;
            for (int i = 0; i < n; i++) { char ch = p[9].charAt(i); if (ch != '0' && ch != '1') return null; g.open[i] = ch == '1'; }
            if (!p[10].equals("-")) {
                String[] q = p[10].split(",");
                if (q.length > MAXB) return null;
                for (String v : q) {
                    int x = Integer.parseInt(v);
                    if (x != 1 && x != 2 && x != 3 && x != 4 && x != 8) return null;
                    g.bank[g.banks++] = x;
                }
            }
            if (g.phase == MOVE && g.banks == 0 && g.winner < 0) return null;
            String[] seats = p[11].split("/");
            if (seats.length != n) return null;
            for (int i = 0; i < n; i++) {
                String[] q = seats[i].split(",");
                if (q.length != per) return null;
                for (int k = 0; k < per; k++) {
                    int x = Integer.parseInt(q[k]);
                    if (x < 0 || x > HOME || (x >= OUTER && x < HOME && !g.open[i])) return null;
                    g.pos[i][k] = x;
                }
            }
            String[] l = p[12].split(",");
            if (l.length != 11) return null;
            g.lastAct = Integer.parseInt(l[0]); g.lastSeat = Integer.parseInt(l[1]); g.lastPawn = Integer.parseInt(l[2]);
            g.lastFrom = Integer.parseInt(l[3]); g.lastTo = Integer.parseInt(l[4]); g.lastValue = Integer.parseInt(l[5]);
            g.lastCaught = Integer.parseInt(l[6]); g.lastCaughtSeats = Integer.parseInt(l[7]); g.lastOpened = l[8].equals("1");
            g.skipped = Integer.parseInt(l[9]); g.skippedValue = Integer.parseInt(l[10]);
            if (g.lastAct < 0 || g.lastAct > 2 || g.lastSeat < -1 || g.lastSeat >= n || g.lastPawn < -1 || g.lastPawn >= per
                    || g.lastFrom < -1 || g.lastFrom > HOME || g.lastTo < -1 || g.lastTo > HOME
                    || g.lastValue < 0 || g.lastValue > 8) return null; // (lastValue: the drawn digit of the last throw)
            if (g.lastAct != 0 && g.lastSeat < 0) return null;
            if (g.lastAct == 2 && (g.lastPawn < 0 || g.lastFrom < 0 || g.lastTo < 0)) return null;
            return g;
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================ words

    /** Telugu number words (the same as Game.num, kept here so the rules need nothing from Android). */
    static String num(int n) {
        String[] w = {"సున్నా", "ఒకటి", "రెండు", "మూడు", "నాలుగు", "ఐదు", "ఆరు", "ఏడు", "ఎనిమిది", "తొమ్మిది", "పది",
                "పదకొండు", "పన్నెండు", "పదమూడు", "పద్నాలుగు", "పదిహేను", "పదహారు"};
        return n >= 0 && n < w.length ? w[n] : String.valueOf(n);
    }

    /** The throw's name, as Jarvis says it ("చమ్మా", "అష్టా", or the number). */
    static String throwWord(int v) { return v == 4 ? "చమ్మా" : v == 8 ? "అష్టా" : num(v); }

    /** "ఒక గడి" / "మూడు గడులు". */
    static String steps(int v) { return v == 1 ? "ఒక గడి" : num(v) + " గడులు"; }
}
