package com.anil.jarvis;

/**
 * వైకుంఠపాళి (snakes and ladders) rules (plain Java: tested on the desk). There is nothing to choose: the die decides
 * everything, so Jarvis just rolls.
 *
 * The board is 10 × 10, numbered 1..100: row 1 at the bottom left to right, the next row right to left, and so on.
 * A fixed, fair layout of 8 ladders and 9 snakes (no ladder from 1, no snake on 100, no square with two of them).
 *
 * The rules, kept simple:
 * - Tokens start off the board (0); any roll moves (no six needed to start).
 * - A 6 gives another roll; the third 6 in a row is lost and the turn passes.
 * - 100 needs the exact number: a bigger roll leaves the token where it is.
 * - The foot of a ladder climbs to its top; a snake's head slides down to its tail.
 * - Several tokens may share a square. The first to reach 100 wins.
 */
final class SnakesRules {
    /** {foot, top} of each ladder. */
    static final int[][] LADDERS = {{3, 23}, {8, 28}, {10, 30}, {16, 35}, {37, 64}, {56, 85}, {58, 83}, {60, 81}};
    /** {head, tail} of each snake. */
    static final int[][] SNAKES = {{36, 17}, {42, 21}, {49, 12}, {51, 31}, {53, 34}, {66, 46}, {73, 54}, {94, 71}, {97, 77}};
    private static final int[] JUMP = new int[101];

    static {
        for (int i = 0; i <= 100; i++) JUMP[i] = i;
        for (int[] l : LADDERS) JUMP[l[0]] = l[1];
        for (int[] s : SNAKES) JUMP[s[0]] = s[1];
    }

    /** What a roll did (bits): moved, climbed a ladder, slid down a snake, rolls again, won, stayed (too big for 100),
     *  the third six (the turn passed), came near 100 for the first time (90 or more). */
    static final int MOVED = 1, LADDER = 2, SNAKE = 4, AGAIN = 8, WON = 16, STAY = 32, SIXES = 64, NEAR = 128;

    int n = 2;
    int[] pos = new int[4];
    int turn, die, sixes, winner = -1;
    /** The last roll: kind 0 nothing yet, 1 a move, 2 too big for 100 (stayed), 3 the third six; its seat, die,
     *  where it started, where the die took it, where it ended (after a ladder / snake). */
    int lastKind, lastSeat = -1, lastDie, lastFrom, lastLand, lastTo;
    /** Seats already told "near 100" (bits), and rolls so far. */
    int near, moves;

    SnakesRules() { setup(2); }

    void setup(int players) {
        n = Math.max(2, Math.min(4, players));
        pos = new int[4];
        turn = die = sixes = 0;
        winner = -1;
        lastKind = 0;
        lastSeat = -1;
        lastDie = lastFrom = lastLand = lastTo = 0;
        near = moves = 0;
    }

    /** The colour of each seat (Game.COLORS): two → red and blue, three → red, green, blue, four → all. */
    static int[] colours(int n) { return n <= 2 ? new int[]{0, 3} : n == 3 ? new int[]{0, 1, 3} : new int[]{0, 1, 2, 3}; }

    /** Where a ladder / snake starting on this square ends (the square itself when none). */
    static int jump(int cell) { return cell >= 0 && cell <= 100 ? JUMP[cell] : cell; }

    /** The seat to play rolled d (1..6): what happened (bits). */
    int roll(int d) {
        if (winner >= 0) return 0;
        d = Math.max(1, Math.min(6, d));
        int seat = turn, from = pos[seat], r = 0;
        die = d;
        lastSeat = seat;
        lastDie = d;
        lastFrom = from;
        moves++;
        if (d == 6) sixes++; else sixes = 0;
        if (d == 6 && sixes >= 3) {
            lastKind = 3;
            lastLand = lastTo = from;
            next();
            return SIXES;
        }
        int land = from + d;
        if (land > 100) {
            lastKind = 2;
            lastLand = lastTo = from;
            r = STAY;
        } else {
            int to = JUMP[land];
            pos[seat] = to;
            lastKind = 1;
            lastLand = land;
            lastTo = to;
            r = MOVED | (to > land ? LADDER : to < land ? SNAKE : 0);
            if (to == 100) { winner = seat; return r | WON; }
            if (to >= 90 && (near & (1 << seat)) == 0) { near |= 1 << seat; r |= NEAR; }
        }
        if (d == 6) r |= AGAIN; else next();
        return r;
    }

    private void next() {
        sixes = 0;
        turn = (turn + 1) % n;
    }

    // ================================================================ the board

    /** Column (0 left … 9 right) of square 1..100: row 1 goes left to right, the next right to left, and so on. */
    static int col(int cell) {
        int r = (cell - 1) / 10, c = (cell - 1) % 10;
        return r % 2 == 0 ? c : 9 - c;
    }

    /** Row from the top (0 … 9): square 1 is at the bottom. */
    static int row(int cell) { return 9 - (cell - 1) / 10; }

    /** Telugu words for 0..100 ("నలభై నాలుగు", "వంద"): Jarvis says numbers as words. */
    static String words(int n) {
        if (n <= 0) return "సున్నా";
        if (n >= 100) return "వంద";
        if (n < 10) return ONES[n];
        if (n < 20) return TEENS[n - 10];
        return n % 10 == 0 ? TENS[n / 10] : TENS[n / 10] + " " + ONES[n % 10];
    }

    private static final String[] ONES = {"", "ఒకటి", "రెండు", "మూడు", "నాలుగు", "ఐదు", "ఆరు", "ఏడు", "ఎనిమిది", "తొమ్మిది"};
    private static final String[] TEENS = {"పది", "పదకొండు", "పన్నెండు", "పదమూడు", "పద్నాలుగు", "పదిహేను", "పదహారు", "పదిహేడు",
            "పద్దెనిమిది", "పందొమ్మిది"};
    private static final String[] TENS = {"", "", "ఇరవై", "ముప్పై", "నలభై", "యాభై", "అరవై", "డెబ్బై", "ఎనభై", "తొంభై"};

    // ================================================================ save, load

    SnakesRules copy() {
        SnakesRules c = new SnakesRules();
        c.load(save());
        return c;
    }

    /** "v1;players;turn;die;sixes;winner;positions;last roll;near;moves". */
    String save() {
        StringBuilder b = new StringBuilder("v1;");
        b.append(n).append(';').append(turn).append(';').append(die).append(';').append(sixes).append(';').append(winner).append(';');
        for (int s = 0; s < n; s++) b.append(s == 0 ? "" : ",").append(pos[s]);
        b.append(';').append(lastKind).append(',').append(lastSeat).append(',').append(lastDie).append(',').append(lastFrom)
                .append(',').append(lastLand).append(',').append(lastTo);
        return b.append(';').append(near).append(';').append(moves).toString();
    }

    boolean load(String s) {
        try {
            String[] p = s.split(";");
            if (p.length < 10 || !p[0].equals("v1")) return false;
            int nn = Integer.parseInt(p[1]), tu = Integer.parseInt(p[2]), di = Integer.parseInt(p[3]), si = Integer.parseInt(p[4]),
                    wi = Integer.parseInt(p[5]);
            if (nn < 2 || nn > 4 || tu < 0 || tu >= nn || di < 0 || di > 6 || si < 0 || si > 2 || wi < -1 || wi >= nn) return false;
            String[] q = p[6].split(",");
            if (q.length != nn) return false;
            int[] ps = new int[4];
            for (int i = 0; i < nn; i++) {
                ps[i] = Integer.parseInt(q[i].trim());
                if (ps[i] < 0 || ps[i] > 100) return false;
            }
            String[] l = p[7].split(",");
            if (l.length != 6) return false;
            int[] lv = new int[6];
            for (int i = 0; i < 6; i++) lv[i] = Integer.parseInt(l[i].trim());
            if (lv[0] < 0 || lv[0] > 3 || lv[1] < -1 || lv[1] >= nn || lv[2] < 0 || lv[2] > 6) return false;
            for (int i = 3; i < 6; i++) if (lv[i] < 0 || lv[i] > 100) return false;
            int ne = Integer.parseInt(p[8]), mo = Integer.parseInt(p[9]);
            n = nn;
            pos = ps;
            turn = tu;
            die = di;
            sixes = si;
            winner = wi;
            lastKind = lv[0];
            lastSeat = lv[1];
            lastDie = lv[2];
            lastFrom = lv[3];
            lastLand = lv[4];
            lastTo = lv[5];
            near = ne & 15;
            moves = Math.max(0, mo);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
