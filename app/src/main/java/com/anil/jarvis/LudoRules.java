package com.anil.jarvis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * లూడో rules and Jarvis's choice (plain Java: tested on the desk).
 *
 * The board is the usual 15 × 15 cross. The colours sit in the corners: 0 red bottom-left, 1 green top-left, 2 yellow
 * top-right, 3 blue bottom-right, and the play goes clockwise in that order. Seats get colours by how many play: two →
 * red and yellow (opposite corners), three → red, green, yellow, four → all four. Seat 0 (the person at the tablet) is
 * always red.
 *
 * The rules, kept simple the way it is played at home:
 * - A token leaves its yard only with a 6, onto its own start square.
 * - A 6 gives another roll; the third 6 in a row is lost and the turn passes.
 * - Tokens go clockwise once round the 52 squares of the track, then up their own home column (5 squares) into the
 *   home triangle in the middle. Home needs the exact number: a token that can't use the whole roll can't move.
 * - Landing on another colour's token sends it back to its yard and gives another roll, except on a safe square: the
 *   four start squares and the four star squares (8 squares after each start), where tokens simply share. Own tokens
 *   may share any square (no blocks).
 * - A token reaching home gives another roll.
 * - When nothing can move, the turn passes.
 * - The first player with all tokens home wins and the game ends (with three or four players too).
 *
 * Positions are counted from the owner's start square: -1 in the yard, 0..50 on the track, 51..55 the home column,
 * 56 home.
 */
final class LudoRules {
    static final int TRACK = 52, LANE = 51, HOME = 56, YARD = -1;
    /** What a roll did: a token must be chosen; nothing could move (the turn passed); the third six (the turn passed). */
    static final int CHOOSE = 0, NONE = 1, SIXES = 2;
    /** What a move did (bits): left the yard, sent tokens home, entered the home column, landed on a safe square,
     *  reached home, rolls again, won; MOVED is in every move made. */
    static final int OUT = 1, CAPTURE = 2, LANE_IN = 4, SAFE = 8, HOME_IN = 16, AGAIN = 32, WON = 64, MOVED = 128;

    int n = 2, k = 4;
    int[] col = {0, 2};
    int[][] pos = new int[4][4];
    int turn, die, sixes, winner = -1;
    /** The die is rolled and a token must be chosen. */
    boolean rolled;
    /** The last thing that happened: kind 0 nothing yet, 1 a move, 2 a roll with nothing to move, 3 the third six. */
    int lastKind, lastSeat = -1, lastTok = -1, lastFrom, lastTo, lastDie, lastCapSeat = -1, lastCapMask;
    /** Seats already told "one token left" (bits), and moves made so far. */
    int near, moves;

    LudoRules() { setup(2, 4); }

    void setup(int players, int tokens) {
        n = Math.max(2, Math.min(4, players));
        k = tokens == 2 ? 2 : 4;
        col = colours(n);
        pos = new int[4][4];
        for (int[] p : pos) Arrays.fill(p, YARD);
        turn = die = sixes = 0;
        winner = -1;
        rolled = false;
        lastKind = 0;
        lastSeat = lastTok = lastCapSeat = -1;
        lastFrom = lastTo = lastDie = lastCapMask = 0;
        near = moves = 0;
    }

    /** The colour (corner) of each seat. */
    static int[] colours(int n) { return n <= 2 ? new int[]{0, 2} : n == 3 ? new int[]{0, 1, 2} : new int[]{0, 1, 2, 3}; }

    // ================================================================ the board

    /** The track square (0..51, red's start is 0) of a position of this seat, or -1 off the track. */
    int abs(int seat, int p) { return p >= 0 && p < LANE ? (13 * col[seat] + p) % TRACK : -1; }

    /** A safe square: a start square or a star square. */
    static boolean safeSquare(int a) { return a >= 0 && (a % 13 == 0 || a % 13 == 8); }

    /** Where this token would go with d, or -2 when it can't move. */
    int target(int seat, int t, int d) {
        int p = pos[seat][t];
        if (p == HOME) return -2;
        if (p == YARD) return d == 6 ? 0 : -2;
        return p + d <= HOME ? p + d : -2;
    }

    boolean legal(int t) { return rolled && winner < 0 && t >= 0 && t < k && target(turn, t, die) >= 0; }

    /** The tokens of the seat to play that can move with the die. */
    List<Integer> movable() {
        List<Integer> m = new ArrayList<>();
        if (!rolled || winner >= 0) return m;
        for (int t = 0; t < k; t++) if (target(turn, t, die) >= 0) m.add(t);
        return m;
    }

    /** How many different moves there are (tokens on the same square make the same move). */
    int choices() {
        int c = 0;
        for (int t = 0; t < k; t++) {
            if (!legal(t)) continue;
            boolean same = false;
            for (int u = 0; u < t; u++) if (legal(u) && pos[turn][u] == pos[turn][t]) same = true;
            if (!same) c++;
        }
        return c;
    }

    boolean allHome(int seat) {
        for (int t = 0; t < k; t++) if (pos[seat][t] != HOME) return false;
        return true;
    }

    int homeCount(int seat) {
        int c = 0;
        for (int t = 0; t < k; t++) if (pos[seat][t] == HOME) c++;
        return c;
    }

    /** Other players' tokens 1..6 squares behind track square a (they could land on it with their next roll). */
    int danger(int seat, int a) {
        if (a < 0 || safeSquare(a)) return 0;
        int c = 0;
        for (int s = 0; s < n; s++) {
            if (s == seat) continue;
            for (int u = 0; u < k; u++) {
                int p = pos[s][u];
                if (p < 0 || p >= LANE) continue;
                int d = (a - abs(s, p) + TRACK) % TRACK;
                if (d >= 1 && d <= 6 && p + d < LANE) c++;
            }
        }
        return c;
    }

    /** The seat of a token 1..6 behind square a (the first found), or -1. */
    int threatBy(int seat, int a) {
        if (a < 0 || safeSquare(a)) return -1;
        for (int s = 0; s < n; s++) {
            if (s == seat) continue;
            for (int u = 0; u < k; u++) {
                int p = pos[s][u];
                if (p < 0 || p >= LANE) continue;
                int d = (a - abs(s, p) + TRACK) % TRACK;
                if (d >= 1 && d <= 6 && p + d < LANE) return s;
            }
        }
        return -1;
    }

    // ================================================================ playing

    /** The seat to play rolled d (1..6): CHOOSE, NONE (passed) or SIXES (passed). */
    int roll(int d) {
        die = Math.max(1, Math.min(6, d));
        if (die == 6) sixes++; else sixes = 0;
        if (die == 6 && sixes >= 3) { passed(3); return SIXES; }
        rolled = true;
        if (movable().isEmpty()) { passed(2); return NONE; }
        return CHOOSE;
    }

    private void passed(int kind) {
        lastKind = kind;
        lastSeat = turn;
        lastTok = lastCapSeat = -1;
        lastCapMask = 0;
        lastDie = die;
        lastFrom = lastTo = 0;
        rolled = false;
        sixes = 0;
        turn = (turn + 1) % n;
    }

    /** Moves token t of the seat to play with the die; returns what happened (bits), 0 when it can't. */
    int move(int t) {
        if (!legal(t)) return 0;
        int seat = turn, from = pos[seat][t], to = target(seat, t, die), r = MOVED;
        pos[seat][t] = to;
        if (from == YARD) r |= OUT;
        lastCapSeat = -1;
        lastCapMask = 0;
        int a = abs(seat, to);
        if (a >= 0) {
            if (safeSquare(a)) r |= SAFE;
            else for (int s = 0; s < n; s++) {
                if (s == seat) continue;
                for (int u = 0; u < k; u++) {
                    if (abs(s, pos[s][u]) != a) continue;
                    pos[s][u] = YARD;
                    lastCapSeat = s;
                    lastCapMask |= 1 << u;
                    r |= CAPTURE;
                }
            }
        }
        if (to >= LANE && to < HOME && from < LANE) r |= LANE_IN;
        if (to == HOME) r |= HOME_IN;
        lastKind = 1;
        lastSeat = seat;
        lastTok = t;
        lastFrom = from;
        lastTo = to;
        lastDie = die;
        moves++;
        rolled = false;
        if (allHome(seat)) { winner = seat; return r | WON; }
        if (die == 6 || (r & (CAPTURE | HOME_IN)) != 0) r |= AGAIN; // (the same seat rolls again)
        else { sixes = 0; turn = (turn + 1) % n; }
        return r;
    }

    /** Only one token of this seat is still away from home (said once: "ఇంకొక్క కాయే"). */
    boolean lastOne(int seat) { return k > 1 && homeCount(seat) == k - 1; }

    // ================================================================ Jarvis's choice

    /**
     * Jarvis's token for the rolled die. Level 0 mostly any token (now and then the furthest); level 1 likes a capture,
     * getting out and safe squares; level 2 weighs everything: home > capture > escaping a token behind > a safe
     * square > leaving the yard > advancing the most advanced token without leaving it open. The die is never his.
     */
    int choose(int level, Random r) {
        List<Integer> m = movable();
        if (m.isEmpty()) return -1;
        if (m.size() == 1) return m.get(0);
        if (level <= 0 && r.nextDouble() < 0.8) return m.get(r.nextInt(m.size()));
        if (level == 1 && r.nextDouble() < 0.15) return m.get(r.nextInt(m.size()));
        int best = m.get(0);
        double bs = -1e18;
        for (int t : m) {
            double s = level <= 0 ? score0(t) : level == 1 ? score1(t) : score2(t);
            s += r.nextDouble() * (level <= 0 ? 30 : level == 1 ? 25 : 0.01); // (ties broken at random)
            if (s > bs) { bs = s; best = t; }
        }
        return best;
    }

    private double score0(int t) { return target(turn, t, die); }

    private double score1(int t) {
        int seat = turn, to = target(seat, t, die);
        LudoRules c = copy();
        int res = c.move(t);
        if ((res & WON) != 0) return 1e6;
        double s = to * 0.5;
        if ((res & CAPTURE) != 0) s += 500;
        if ((res & HOME_IN) != 0) s += 400;
        if ((res & OUT) != 0) s += 300;
        else if ((res & SAFE) != 0 || (to >= LANE && to < HOME)) s += 200;
        return s;
    }

    private double score2(int t) {
        int seat = turn, p = pos[seat][t], q = target(seat, t, die);
        LudoRules c = copy();
        int res = c.move(t);
        if ((res & WON) != 0) return 1e6;
        double s = 0;
        if ((res & HOME_IN) != 0) s += 1000;
        if ((res & CAPTURE) != 0) {
            s += 800;
            for (int u = 0; u < k; u++) if ((c.lastCapMask & (1 << u)) != 0) s += pos[c.lastCapSeat][u] * 4; // (the further it had come, the better)
        }
        int before = p >= 0 && p < LANE ? danger(seat, abs(seat, p)) : 0;
        int after = q < LANE ? c.danger(seat, abs(seat, q)) : 0;
        if (before > 0 && after == 0) s += 400 + p * 4;
        if (q >= LANE && q < HOME) s += 250;
        else if ((res & SAFE) != 0 && p != YARD) s += 220;
        if (p == YARD) s += 180;
        s -= after * (90 + q * 5);
        s += Math.max(p, 0) * 1.5 + q * 0.2;
        return s;
    }

    // ================================================================ copy, save, load

    LudoRules copy() {
        LudoRules c = new LudoRules();
        c.n = n;
        c.k = k;
        c.col = col.clone();
        for (int i = 0; i < 4; i++) c.pos[i] = pos[i].clone();
        c.turn = turn;
        c.die = die;
        c.sixes = sixes;
        c.winner = winner;
        c.rolled = rolled;
        c.lastKind = lastKind;
        c.lastSeat = lastSeat;
        c.lastTok = lastTok;
        c.lastFrom = lastFrom;
        c.lastTo = lastTo;
        c.lastDie = lastDie;
        c.lastCapSeat = lastCapSeat;
        c.lastCapMask = lastCapMask;
        c.near = near;
        c.moves = moves;
        return c;
    }

    /** "v1;players;tokens;turn;die;rolled;sixes;winner;positions;last move;near;moves". */
    String save() {
        StringBuilder b = new StringBuilder("v1;");
        b.append(n).append(';').append(k).append(';').append(turn).append(';').append(die).append(';').append(rolled ? 1 : 0)
                .append(';').append(sixes).append(';').append(winner).append(';');
        for (int s = 0; s < n; s++) for (int t = 0; t < k; t++) b.append(s + t == 0 ? "" : ",").append(pos[s][t]);
        b.append(';').append(lastKind).append(',').append(lastSeat).append(',').append(lastTok).append(',').append(lastFrom)
                .append(',').append(lastTo).append(',').append(lastDie).append(',').append(lastCapSeat).append(',').append(lastCapMask);
        return b.append(';').append(near).append(';').append(moves).toString();
    }

    boolean load(String s) {
        try {
            String[] p = s.split(";");
            if (p.length < 12 || !p[0].equals("v1")) return false;
            int nn = Integer.parseInt(p[1]), kk = Integer.parseInt(p[2]), tu = Integer.parseInt(p[3]), di = Integer.parseInt(p[4]),
                    ro = Integer.parseInt(p[5]), si = Integer.parseInt(p[6]), wi = Integer.parseInt(p[7]);
            if (nn < 2 || nn > 4 || (kk != 2 && kk != 4) || tu < 0 || tu >= nn || di < 0 || di > 6 || ro < 0 || ro > 1
                    || (ro == 1 && di < 1) || si < 0 || si > 2 || wi < -1 || wi >= nn) return false;
            String[] q = p[8].split(",");
            if (q.length != nn * kk) return false;
            int[][] ps = new int[4][4];
            for (int[] r : ps) Arrays.fill(r, YARD);
            for (int i = 0; i < q.length; i++) {
                int v = Integer.parseInt(q[i].trim());
                if (v < YARD || v > HOME) return false;
                ps[i / kk][i % kk] = v;
            }
            String[] l = p[9].split(",");
            if (l.length != 8) return false;
            int[] lv = new int[8];
            for (int i = 0; i < 8; i++) lv[i] = Integer.parseInt(l[i].trim());
            if (lv[0] < 0 || lv[0] > 3 || lv[1] < -1 || lv[1] >= nn || lv[2] < -1 || lv[2] >= kk || lv[3] < YARD || lv[3] > HOME
                    || lv[4] < YARD || lv[4] > HOME || lv[5] < 0 || lv[5] > 6 || lv[6] < -1 || lv[6] >= nn || lv[7] < 0 || lv[7] > 15) return false;
            int ne = Integer.parseInt(p[10]), mo = Integer.parseInt(p[11]);
            n = nn;
            k = kk;
            col = colours(nn);
            pos = ps;
            turn = tu;
            die = di;
            rolled = ro == 1;
            sixes = si;
            winner = wi;
            lastKind = lv[0];
            lastSeat = lv[1];
            lastTok = lv[2];
            lastFrom = lv[3];
            lastTo = lv[4];
            lastDie = lv[5];
            lastCapSeat = lv[6];
            lastCapMask = lv[7];
            near = ne & 15;
            moves = Math.max(0, mo);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ================================================================ where things are on the 15 × 15 grid (x right, y down)

    /** The track's 52 squares as {x, y} cells, square 0 = red's start, going clockwise. */
    static final int[][] CELLS = new int[TRACK][];
    /** Each colour's home column (positions 51..55) as {x, y} cells. */
    static final int[][][] LANES = new int[4][5][];

    static {
        // red's quarter of the track (up from its start, out along the left arm, round its end); the others are turned
        int[][] seg = {{6, 13}, {6, 12}, {6, 11}, {6, 10}, {6, 9}, {5, 8}, {4, 8}, {3, 8}, {2, 8}, {1, 8}, {0, 8}, {0, 7}, {0, 6}};
        int[][] lane = {{7, 13}, {7, 12}, {7, 11}, {7, 10}, {7, 9}};
        for (int c = 0; c < 4; c++) {
            for (int i = 0; i < 13; i++) CELLS[13 * c + i] = rot(seg[i][0], seg[i][1], c);
            for (int i = 0; i < 5; i++) LANES[c][i] = rot(lane[i][0], lane[i][1], c);
        }
    }

    /** A cell turned c quarter turns clockwise round the middle (red's corner → green's → yellow's → blue's). */
    static int[] rot(int x, int y, int c) {
        for (int i = 0; i < (c & 3); i++) { int nx = 14 - y; y = x; x = nx; }
        return new int[]{x, y};
    }

    /** The cell {x, y} of position p of this colour, or null (in the yard, or home in the middle). */
    static int[] cellOf(int colour, int p) {
        if (p >= 0 && p < LANE) return CELLS[(13 * colour + p) % TRACK];
        if (p >= LANE && p < HOME) return LANES[colour & 3][p - LANE];
        return null;
    }
}
