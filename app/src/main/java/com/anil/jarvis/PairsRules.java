package com.anil.jarvis;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;

/**
 * జతల ఆట (memory cards) rules and Jarvis's memory (plain Java: tested on the desk).
 *
 * The cards lie face down in a grid (6 pairs 4×3, 8 pairs 4×4, 10 pairs 5×4). A turn turns two cards: the same face →
 * a pair, both stay face up and the same player goes again; not the same → both turn back and the turn passes (alone:
 * it only counts as a try). Jarvis remembers each card he sees with a chance that follows his level (MEMORY); a pair
 * he remembers he takes, else he turns a card he doesn't know, then its partner if he remembers where it is, else
 * another card he doesn't know.
 *
 * A pair's face is a family photo (photo[p], a file kept by the game) or a drawn symbol (sym[p], always set and
 * different for every pair: also the stand-in when a photo file is gone).
 */
final class PairsRules {
    static final int SYMBOLS = 12;
    /** How likely Jarvis keeps a card he has seen in mind: easy, medium, hard. */
    static final double[] MEMORY = {0.35, 0.65, 0.95};

    int pairs = 6, cols = 4, rows = 3, seats = 1;
    /** Names the photo files of this game. */
    long tag;
    /** Card → its pair (0..pairs-1), row by row. */
    int[] card = new int[0];
    /** Pair → its symbol (0..SYMBOLS-1), and whether it shows a photo. */
    int[] sym = new int[0];
    boolean[] photo = new boolean[0];
    /** Card → -1 face down, else the seat that found its pair. */
    int[] owner = new int[0];
    /** Card was ever turned up (everyone saw it: for the hint) / Jarvis remembers it. */
    boolean[] seen = new boolean[0], known = new boolean[0];
    int[] score = new int[1];
    /** Whose turn; the cards turned up in this turn (-1 none); turns played. */
    int turn, first = -1, second = -1, tries;
    /** The last turn: its two cards, who, a pair or not, and whether the second card had been seen before (a memory). */
    int lastA = -1, lastB = -1, lastSeat = -1;
    boolean lastMatch, lastRecalled;

    static int colsFor(int pairs) { return pairs >= 10 ? 5 : 4; }
    static int rowsFor(int pairs) { return pairs * 2 / colsFor(pairs); }

    /** A new game: n pairs (6, 8 or 10) for this many seats, all faces symbols for now. */
    void deal(int n, int seats, long tag, Random r) {
        pairs = n == 8 || n == 10 ? n : 6;
        cols = colsFor(pairs);
        rows = rowsFor(pairs);
        this.seats = Math.max(1, Math.min(4, seats));
        this.tag = tag;
        card = new int[pairs * 2];
        for (int i = 0; i < card.length; i++) card[i] = i / 2;
        shuffle(card, r);
        int[] s = new int[SYMBOLS];
        for (int i = 0; i < SYMBOLS; i++) s[i] = i;
        shuffle(s, r);
        sym = new int[pairs];
        System.arraycopy(s, 0, sym, 0, pairs);
        photo = new boolean[pairs];
        owner = new int[pairs * 2];
        for (int i = 0; i < owner.length; i++) owner[i] = -1;
        seen = new boolean[pairs * 2];
        known = new boolean[pairs * 2];
        score = new int[this.seats];
        turn = tries = 0;
        first = second = -1;
        lastA = lastB = lastSeat = -1;
        lastMatch = lastRecalled = false;
    }

    private static void shuffle(int[] a, Random r) {
        for (int i = a.length - 1; i > 0; i--) { int j = r.nextInt(i + 1), x = a[i]; a[i] = a[j]; a[j] = x; }
    }

    int size() { return card.length; }

    /** The other card of this card's pair. */
    int partner(int c) {
        for (int i = 0; i < card.length; i++) if (i != c && card[i] == card[c]) return i;
        return -1;
    }

    /** Can this card be turned up now? */
    boolean canOpen(int c) { return c >= 0 && c < card.length && owner[c] < 0 && c != first && second < 0; }

    /** Face up now (found, or turned up in this turn). */
    boolean up(int c) { return owner[c] >= 0 || c == first || c == second; }

    /**
     * Turns card c up. 0: the first card of the turn; 1: a pair (both stay up, the same seat goes on); 2: not a pair
     * (both stay up until close()).
     */
    int open(int c) {
        boolean was = seen[c];
        seen[c] = true;
        if (first < 0) { first = c; return 0; }
        tries++;
        lastA = first;
        lastB = c;
        lastSeat = turn;
        lastRecalled = was;
        if (card[c] == card[first]) {
            owner[c] = owner[first] = turn;
            score[turn]++;
            first = second = -1;
            lastMatch = true;
            return 1;
        }
        second = c;
        lastMatch = false;
        return 2;
    }

    /** Not a pair: both cards turn back and the next seat plays. */
    void close() {
        first = second = -1;
        turn = (turn + 1) % seats;
    }

    /** Jarvis saw card c: he keeps it in mind with chance p. */
    void see(int c, double p, Random r) { if (c >= 0 && c < known.length && !known[c] && r.nextDouble() < p) known[c] = true; }

    int found() { int n = 0; for (int s : score) n += s; return n; }

    boolean over() { return found() >= pairs; }

    /** Alone: 0; else the seat with the most pairs, -1 when the top is shared. */
    int winner() {
        if (seats == 1) return 0;
        int best = -1, top = -1;
        for (int s = 0; s < seats; s++) {
            if (score[s] > top) { top = score[s]; best = s; }
            else if (score[s] == top) best = -1;
        }
        return best;
    }

    private int pickWhere(IntPredicate ok, Random r) {
        List<Integer> l = new ArrayList<>();
        for (int c = 0; c < card.length; c++) if (ok.test(c)) l.add(c);
        return l.isEmpty() ? -1 : l.get(r.nextInt(l.size()));
    }

    /**
     * Jarvis's two cards {a, b, 1 when he went by memory (he knew where b was) else 0}, or null when the game is over.
     * When a card of this turn is already up (a game taken up again), a is that card.
     */
    int[] jarvisPick(Random r) {
        if (over() || second >= 0) return null;
        int a;
        if (first >= 0) {
            a = first;
        } else {
            final int f = first;
            int k = pickWhere(c -> owner[c] < 0 && known[c] && c < partner(c) && known[partner(c)] && owner[partner(c)] < 0, r);
            if (k >= 0) return new int[]{k, partner(k), 1};
            a = pickWhere(c -> owner[c] < 0 && !known[c], r);
            if (a < 0) a = pickWhere(c -> owner[c] < 0 && c != f, r);
        }
        if (a < 0) return null;
        final int aa = a;
        int p = partner(a);
        if (p >= 0 && known[p] && owner[p] < 0) return new int[]{a, p, 1};
        int b = pickWhere(c -> owner[c] < 0 && c != aa && !known[c], r);
        if (b < 0) b = pickWhere(c -> owner[c] < 0 && c != aa, r);
        return b < 0 ? null : new int[]{a, b, 0};
    }

    /**
     * A good card for the person to play (from what everyone has seen): a pair seen before (two cards), the partner of
     * the card just turned up, or a card nobody has seen yet. null when nothing can be turned.
     */
    int[] hint(Random r) {
        if (over() || second >= 0) return null;
        if (first >= 0) {
            final int f = first;
            int p = partner(f);
            if (p >= 0 && seen[p]) return new int[]{p};
            int u = pickWhere(c -> owner[c] < 0 && c != f && !seen[c], r);
            if (u < 0) u = pickWhere(c -> owner[c] < 0 && c != f, r);
            return u < 0 ? null : new int[]{u};
        }
        for (int c = 0; c < card.length; c++) {
            if (owner[c] >= 0 || !seen[c]) continue;
            int p = partner(c);
            if (p > c && seen[p] && owner[p] < 0) return new int[]{c, p};
        }
        int u = pickWhere(c -> owner[c] < 0 && !seen[c], r);
        if (u < 0) u = pickWhere(c -> owner[c] < 0, r);
        return u < 0 ? null : new int[]{u};
    }

    // ================================================================ words

    private static final String[] SMALL = {"సున్నా", "ఒకటి", "రెండు", "మూడు", "నాలుగు", "ఐదు", "ఆరు", "ఏడు", "ఎనిమిది",
            "తొమ్మిది", "పది", "పదకొండు", "పన్నెండు", "పదమూడు", "పద్నాలుగు", "పదిహేను", "పదహారు", "పదిహేడు",
            "పద్దెనిమిది", "పందొమ్మిది"};
    private static final String[] TENS = {"", "", "ఇరవై", "ముప్పై", "నలభై", "యాభై", "అరవై", "డెబ్బై", "ఎనభై", "తొంభై"};

    /** A number in Telugu words (0..999; "ఇరవై మూడు", "నూట ఐదు"): Jarvis says numbers, never digits. */
    static String words(int n) {
        if (n < 0 || n > 999) return String.valueOf(n);
        if (n >= 100) { // (a forgetful game of ten pairs can take more than a hundred tries)
            int h = n / 100, r = n % 100;
            String hs = h == 1 ? (r == 0 ? "వంద" : "నూట") : SMALL[h] + (r == 0 ? " వందలు" : " వందల");
            return r == 0 ? hs : hs + " " + words(r);
        }
        if (n < 20) return SMALL[n];
        return TENS[n / 10] + (n % 10 == 0 ? "" : " " + SMALL[n % 10]);
    }

    /** "ఐదు జతలు", "ఒక జత", "ఒక్క జత కూడా లేదు". */
    static String pairsWord(int n) { return n == 0 ? "ఒక్క జత కూడా లేదు" : n == 1 ? "ఒక జత" : words(n) + " జతలు"; }

    private static final String[] ORD = {"మొదటి", "రెండో", "మూడో", "నాలుగో", "ఐదో"};

    /** Where a card is: "పై వరుసలో ఎడమ నుంచి రెండో కార్డు". */
    String where(int c) {
        if (c < 0 || c >= card.length) return "";
        int r = c / cols, k = c % cols;
        String row = r == 0 ? "పై వరుసలో" : r == rows - 1 ? "కింది వరుసలో" : ORD[Math.min(4, r)] + " వరుసలో";
        return row + " ఎడమ నుంచి " + ORD[Math.min(4, k)] + " కార్డు";
    }

    /** The symbol as a word before "బొమ్మ" ("రెండూ మామిడిపండు బొమ్మలే!"). */
    static final String[] SYM_WORD = {"మామిడిపండు", "అరటిపండు", "పువ్వు", "నక్షత్రం", "సూర్యుడి", "చంద్రుడి", "గుండె",
            "ఆకు", "చేప", "పక్షి", "ఇంటి", "గంట"};

    // ================================================================ saving

    String save() {
        StringBuilder b = new StringBuilder("v1;");
        b.append(pairs).append(';').append(seats).append(';').append(Long.toString(tag, 36)).append(';').append(turn).append(';')
                .append(first).append(';').append(second).append(';').append(tries).append(';');
        for (int v : card) b.append((char) ('0' + v));
        b.append(';');
        for (int v : sym) b.append((char) ('a' + v));
        b.append(';');
        bits(b, photo);
        b.append(';');
        for (int v : owner) b.append(v < 0 ? '.' : (char) ('0' + v));
        b.append(';');
        bits(b, seen);
        b.append(';');
        bits(b, known);
        b.append(';');
        for (int i = 0; i < score.length; i++) b.append(i == 0 ? "" : ",").append(score[i]);
        b.append(';').append(lastA).append(',').append(lastB).append(',').append(lastSeat).append(',')
                .append(lastMatch ? 1 : 0).append(',').append(lastRecalled ? 1 : 0);
        return b.toString();
    }

    private static void bits(StringBuilder b, boolean[] a) { for (boolean v : a) b.append(v ? '1' : '0'); }

    private static boolean[] bits(String s, int n) {
        if (s.length() != n) return null;
        boolean[] a = new boolean[n];
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            if (ch != '0' && ch != '1') return null;
            a[i] = ch == '1';
        }
        return a;
    }

    /**
     * A saved game, or null when it can't be read. Fields: v1; pairs; seats; tag; turn; first; second; tries; cards;
     * symbols; photo bits; owners; seen bits; known bits; scores; last turn. A turn left half-way after a miss is
     * finished (the turn passes).
     */
    static PairsRules parse(String s) {
        try {
            String[] p = s.split(";", -1);
            if (p.length != 16 || !p[0].equals("v1")) return null;
            PairsRules g = new PairsRules();
            int n = Integer.parseInt(p[1]);
            if (n != 6 && n != 8 && n != 10) return null;
            g.pairs = n;
            g.cols = colsFor(n);
            g.rows = rowsFor(n);
            g.seats = Integer.parseInt(p[2]);
            if (g.seats < 1 || g.seats > 4) return null;
            g.tag = Long.parseLong(p[3], 36);
            g.turn = Integer.parseInt(p[4]);
            g.first = Integer.parseInt(p[5]);
            g.second = Integer.parseInt(p[6]);
            g.tries = Integer.parseInt(p[7]);
            int size = 2 * n;
            if (g.turn < 0 || g.turn >= g.seats || g.tries < 0 || g.first < -1 || g.first >= size || g.second < -1 || g.second >= size) return null;
            if (p[8].length() != size || p[9].length() != n || p[11].length() != size) return null;
            g.card = new int[size];
            int[] times = new int[n];
            for (int i = 0; i < size; i++) {
                int v = p[8].charAt(i) - '0';
                if (v < 0 || v >= n) return null;
                g.card[i] = v;
                times[v]++;
            }
            for (int t : times) if (t != 2) return null;
            g.sym = new int[n];
            boolean[] used = new boolean[SYMBOLS];
            for (int i = 0; i < n; i++) {
                int v = p[9].charAt(i) - 'a';
                if (v < 0 || v >= SYMBOLS || used[v]) return null;
                used[v] = true;
                g.sym[i] = v;
            }
            g.photo = bits(p[10], n);
            g.seen = bits(p[12], size);
            g.known = bits(p[13], size);
            if (g.photo == null || g.seen == null || g.known == null) return null;
            g.owner = new int[size];
            for (int i = 0; i < size; i++) {
                char ch = p[11].charAt(i);
                int v = ch == '.' ? -1 : ch - '0';
                if (v < -1 || v >= g.seats) return null;
                g.owner[i] = v;
            }
            String[] sc = p[14].split(",");
            if (sc.length != g.seats) return null;
            g.score = new int[g.seats];
            int[] owned = new int[g.seats];
            for (int i = 0; i < size; i++) if (g.owner[i] >= 0) owned[g.owner[i]]++;
            for (int i = 0; i < g.seats; i++) {
                g.score[i] = Integer.parseInt(sc[i]);
                if (g.score[i] * 2 != owned[i]) return null;
            }
            for (int i = 0; i < size; i++) if (g.owner[i] >= 0 && g.owner[g.partner(i)] != g.owner[i]) return null;
            String[] l = p[15].split(",");
            if (l.length != 5) return null;
            g.lastA = Integer.parseInt(l[0]);
            g.lastB = Integer.parseInt(l[1]);
            g.lastSeat = Integer.parseInt(l[2]);
            g.lastMatch = l[3].equals("1");
            g.lastRecalled = l[4].equals("1");
            if (g.lastA < -1 || g.lastA >= size || g.lastB < -1 || g.lastB >= size || g.lastSeat < -1 || g.lastSeat >= g.seats) return null;
            if (g.first >= 0 && g.owner[g.first] >= 0) g.first = -1;
            if (g.second >= 0 && (g.first < 0 || g.owner[g.second] >= 0)) g.second = -1;
            if (g.second >= 0) g.close();
            return g;
        } catch (Exception e) {
            return null;
        }
    }

    PairsRules copy() { return parse(save()); }
}
