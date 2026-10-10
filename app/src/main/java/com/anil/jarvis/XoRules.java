package com.anil.jarvis;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** సున్నా-ఇంటూ (tic-tac-toe) rules and Jarvis's choice (plain Java: tested on the desk). Seat 0 plays ❌ and starts. */
final class XoRules {
    /** 0 empty, 1 ❌ (seat 0), 2 ⭕ (seat 1). */
    final int[] cell = new int[9];
    int turn;      // seat to play
    int last = -1; // the last cell played
    static final int[][] LINES = {{0, 1, 2}, {3, 4, 5}, {6, 7, 8}, {0, 3, 6}, {1, 4, 7}, {2, 5, 8}, {0, 4, 8}, {2, 4, 6}};

    boolean legal(int i) { return i >= 0 && i < 9 && cell[i] == 0 && winner() == -2; }

    void play(int i) {
        cell[i] = turn + 1;
        last = i;
        turn = 1 - turn;
    }

    /** -2 still playing, -1 a draw, 0 / 1 the seat that made a line. */
    int winner() {
        int[] l = line();
        if (l != null) return cell[l[0]] - 1;
        for (int v : cell) if (v == 0) return -2;
        return -1;
    }

    /** The winning line, or null. */
    int[] line() {
        for (int[] l : LINES) if (cell[l[0]] != 0 && cell[l[0]] == cell[l[1]] && cell[l[1]] == cell[l[2]]) return l;
        return null;
    }

    List<Integer> free() {
        List<Integer> f = new ArrayList<>();
        for (int i = 0; i < 9; i++) if (cell[i] == 0) f.add(i);
        return f;
    }

    /** A cell that wins at once for this seat, or -1. */
    int winningCell(int seat) {
        for (int[] l : LINES) {
            int mine = 0, empty = -1, n = 0;
            for (int i : l) { if (cell[i] == seat + 1) mine++; else if (cell[i] == 0) { empty = i; n++; } }
            if (mine == 2 && n == 1) return empty;
        }
        return -1;
    }

    /** Jarvis's cell: level 2 perfect; level 1 sometimes a plain move; level 0 often a plain move (she can win). */
    int choose(int level, Random r) {
        List<Integer> f = free();
        if (f.isEmpty()) return -1;
        double careless = level <= 0 ? 0.55 : level == 1 ? 0.22 : 0.0;
        if (r.nextDouble() < careless) {
            int win = winningCell(turn);
            if (win >= 0 && r.nextDouble() < 0.7) return win; // (even careless, he usually sees his own line)
            return f.get(r.nextInt(f.size()));
        }
        int best = -1, bestScore = Integer.MIN_VALUE;
        List<Integer> ties = new ArrayList<>();
        for (int i : f) {
            cell[i] = turn + 1;
            int s = -minimax(1 - turn, 1);
            cell[i] = 0;
            if (s > bestScore) { bestScore = s; ties.clear(); ties.add(i); }
            else if (s == bestScore) ties.add(i);
        }
        best = ties.get(r.nextInt(ties.size()));
        return best;
    }

    /** Score for the seat to move (+ win, - loss), quicker wins better. */
    private int minimax(int seat, int depth) {
        int[] l = line();
        if (l != null) return -(10 - depth); // the seat that just moved made a line: bad for this seat
        boolean any = false;
        int best = Integer.MIN_VALUE;
        for (int i = 0; i < 9; i++) {
            if (cell[i] != 0) continue;
            any = true;
            cell[i] = seat + 1;
            int s = -minimax(1 - seat, depth + 1);
            cell[i] = 0;
            if (s > best) best = s;
        }
        return any ? best : 0;
    }

    /** Where a cell is, in words ("పైన కుడి మూలలో"). */
    static String where(int i) {
        String[] w = {"పైన ఎడమ మూలలో", "పైన మధ్యలో", "పైన కుడి మూలలో", "ఎడమ వైపు మధ్యలో", "సరిగ్గా మధ్యలో",
                "కుడి వైపు మధ్యలో", "కింద ఎడమ మూలలో", "కింద మధ్యలో", "కింద కుడి మూలలో"};
        return i >= 0 && i < 9 ? w[i] : "";
    }

    String save() {
        StringBuilder b = new StringBuilder();
        for (int v : cell) b.append(v);
        return b + "|" + turn + "|" + last;
    }

    boolean load(String s) {
        try {
            String[] p = s.split("\\|");
            if (p.length < 3 || p[0].length() != 9) return false;
            int[] c = new int[9];
            for (int i = 0; i < 9; i++) { int v = p[0].charAt(i) - '0'; if (v < 0 || v > 2) return false; c[i] = v; }
            int t = Integer.parseInt(p[1]) & 1, l = Integer.parseInt(p[2]);
            if (l < -1 || l > 8) return false;
            System.arraycopy(c, 0, cell, 0, 9); // (only now: a bad save leaves the board as it was)
            turn = t;
            last = l;
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
