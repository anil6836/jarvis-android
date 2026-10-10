package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;

import java.util.Arrays;

/**
 * వైకుంఠపాళి on the screen (the rules are in SnakesRules). A big die beside the board: a tap (on the die or anywhere)
 * or her words ("పాచిక వేయి") roll it; the token hops square by square, then climbs a ladder or slides down a snake.
 * It is a game of luck, so Jarvis just rolls (his level changes nothing) and keeps it cheerful: "నిచ్చెన ఎక్కారు!
 * అరవై నాలుగుకి వెళ్లారు!", "అబ్బా! నన్ను పాము మింగేసింది!". With several people at the tablet he calls them by colour.
 */
final class SnakesGame extends Game {
    private final SnakesRules g = new SnakesRules();
    private int[] colour = SnakesRules.colours(2);
    private static final int SAMPLES = 30;
    private static final int[] SNAKE_COLORS = {0xFF66BB6A, 0xFFFF9800, 0xFFAB47BC, 0xFFEC407A, 0xFF26A69A, 0xFF5C6BC0, 0xFFC0CA33,
            0xFF29B6F6, 0xFFFF7043};
    /** Each snake's body from head to tail, in board squares (x, y from the top left). */
    private static final float[][] SX = new float[SnakesRules.SNAKES.length][SAMPLES], SY = new float[SnakesRules.SNAKES.length][SAMPLES];

    static {
        for (int i = 0; i < SnakesRules.SNAKES.length; i++) {
            float hx = SnakesRules.col(SnakesRules.SNAKES[i][0]) + 0.5f, hy = SnakesRules.row(SnakesRules.SNAKES[i][0]) + 0.5f;
            float tx = SnakesRules.col(SnakesRules.SNAKES[i][1]) + 0.5f, ty = SnakesRules.row(SnakesRules.SNAKES[i][1]) + 0.5f;
            float vx = tx - hx, vy = ty - hy, len = (float) Math.hypot(vx, vy), px = -vy / len, py = vx / len;
            float amp = Math.min(0.9f, len * 0.35f) * (i % 2 == 0 ? -1 : 1); // (an S-curve, bending one way or the other)
            float ax = hx + vx * 0.33f + px * amp, ay = hy + vy * 0.33f + py * amp, bx = hx + vx * 0.66f - px * amp, by = hy + vy * 0.66f - py * amp;
            for (int j = 0; j < SAMPLES; j++) {
                float t = j / (SAMPLES - 1f), m = 1 - t;
                SX[i][j] = m * m * m * hx + 3 * m * m * t * ax + 3 * m * t * t * bx + t * t * t * tx;
                SY[i][j] = m * m * m * hy + 3 * m * m * t * ay + 3 * m * t * t * by + t * t * t * ty;
            }
        }
    }

    /** 0 still, 1 the die rolling, 2 a token hopping, 3 climbing a ladder / sliding down a snake. */
    private int mode;
    private final int[] faces = new int[9];
    private final float[] hopX = new float[8], hopY = new float[8];
    private int hopN, mvSeat = -1;
    /** The ladder (≥ 0) or snake (-1 - i) being used in mode 3. */
    private int slide;
    private String react = "";

    // ---- layout and drawing things made once
    private final RectF board = new RectF(), strip = new RectF(), startBox = new RectF(), rf = new RectF();
    private float u, dieX, dieY, dieS, listX, listY, listW, rowH;
    private int listCols = 1, lw = -1, lh = -1, bmpKey;
    private Bitmap bmp;
    private boolean noBmp;
    private final float[] tokX = new float[4], tokY = new float[4], pt = new float[2];
    private final int[] cnt = new int[101], seen = new int[101];
    private final Path pawn = new Path(), star = new Path();
    private final Paint left = new Paint(Paint.ANTI_ALIAS_FLAG);

    SnakesGame(Context c) {
        super(c);
        left.setTypeface(Typeface.DEFAULT_BOLD);
        left.setTextAlign(Paint.Align.LEFT);
        // a pawn about one unit tall round (0, 0): round head, flared body, wide base
        pawn.addCircle(0, -0.26f, 0.2f, Path.Direction.CW);
        pawn.moveTo(-0.12f, -0.1f);
        pawn.lineTo(0.12f, -0.1f);
        pawn.quadTo(0.15f, 0.12f, 0.3f, 0.26f);
        pawn.lineTo(-0.3f, 0.26f);
        pawn.quadTo(-0.15f, 0.12f, -0.12f, -0.1f);
        pawn.close();
        pawn.addOval(new RectF(-0.36f, 0.18f, 0.36f, 0.42f), Path.Direction.CW);
        // a five-pointed star of radius 1
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float r = i % 2 == 0 ? 1f : 0.45f, x = (float) (Math.cos(a) * r), y = (float) (Math.sin(a) * r);
            if (i == 0) star.moveTo(x, y); else star.lineTo(x, y);
        }
        star.close();
    }

    private static int mix(int a, int b, float f) {
        int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f), gg = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f),
                bb = (int) ((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (gg << 8) | bb;
    }

    @Override String id() { return "snakes"; }
    @Override String title() { return "వైకుంఠపాళి"; }
    @Override int[] players() { return new int[]{2, 4}; }

    @Override void newGame(String[] who, int option) {
        roles = who.clone();
        g.setup(who.length);
        colour = SnakesRules.colours(g.n);
        mode = 0;
        mvSeat = -1;
        bmp = null;
        start();
        if (host == null || far()) return;
        if (hasJarvis() && sole(0))
            say(pick("రండి " + host.her() + ", వైకుంఠపాళి ఆడదాం! మీది ఎర్ర కాయ, నాది " + adj(jarvisSeat()) + " కాయ. నిచ్చెన ఎక్కితే పైకి, పాము నోట్లో పడితే కిందికి! పాచిక నొక్కండి.",
                    "వైకుంఠపాళి! ముందు వందకి ఎవరు చేరితే వాళ్లే గెలుపు. మీరే ముందు " + host.her() + ", పాచిక నొక్కండి."), "happy", "point");
        else say("వైకుంఠపాళి మొదలు! నిచ్చెన ఎక్కితే పైకి, పాము నోట్లో పడితే కిందికి. ఎరుపు వారు ముందు, పాచిక నొక్కండి.", "happy", "point");
    }

    @Override String save() { return g.save(); }

    @Override boolean load(String s) {
        mode = 0;
        mvSeat = -1;
        if (!g.load(s)) return false;
        colour = SnakesRules.colours(g.n);
        return true;
    }

    @Override int turn() { return g.turn; }

    /** No take-backs in a game of luck (it would only be a second roll). */
    @Override boolean canUndo() { return false; }

    @Override String yourTurn(int seat) { return (sole(seat) ? "మీ వంతు" : COLOR_NAMES[colour[seat]] + " వారి వంతు") + ": పాచిక నొక్కండి 🎲"; }

    private int jarvisSeat() {
        for (int i = 0; i < roles.length; i++) if ("jarvis".equals(roles[i])) return i;
        return -1;
    }

    // ================================================================ rolling and moving

    @Override void tap(float x, float y) { roll(); } // (a tap anywhere rolls: there is nothing else to do)

    @Override void jarvisMove() {
        if (mode != 0 || done) return;
        if (g.lastSeat != g.turn) react = reaction();
        host.status("Jarvis పాచిక వేస్తున్నాడు… 🎲");
        roll();
    }

    private void roll() {
        if (mode != 0 || done) return;
        final int seat = g.turn, d = 1 + rnd.nextInt(6);
        for (int i = 0; i < faces.length - 1; i++) faces[i] = 1 + rnd.nextInt(6);
        faces[faces.length - 1] = d;
        mode = 1;
        final int gg = gen;
        animate(700, () -> {
            if (gg != gen) return;
            mode = 0;
            rolled(seat, d);
        });
    }

    private void rolled(final int seat, final int d) {
        layout();
        float sx = tokX[seat], sy = tokY[seat];
        final int from = g.pos[seat], res = g.roll(d);
        invalidate();
        if ((res & (SnakesRules.SIXES | SnakesRules.STAY)) != 0) { done(seat, from, d, res); return; }
        hop(seat, sx, sy, from, g.lastLand, g.lastTo, () -> done(seat, from, d, res));
    }

    /** The token hops from (sx, sy) square by square to land, then climbs / slides to `to`; then. */
    private void hop(int seat, float sx, float sy, int from, int land, int to, Runnable then) {
        hopX[0] = sx;
        hopY[0] = sy;
        if (sx == 0 && sy == 0) { spot(seat, from, pt); hopX[0] = pt[0]; hopY[0] = pt[1]; }
        hopN = 1;
        for (int p = from + 1; p <= land && hopN < hopX.length; p++) {
            spot(seat, p, pt);
            hopX[hopN] = pt[0];
            hopY[hopN] = pt[1];
            hopN++;
        }
        if (hopN < 2) { hopX[1] = hopX[0]; hopY[1] = hopY[0]; hopN = 2; }
        mvSeat = seat;
        mode = 2;
        slide = 0;
        if (to > land) for (int i = 0; i < SnakesRules.LADDERS.length; i++) if (SnakesRules.LADDERS[i][0] == land) slide = i;
        if (to < land) for (int i = 0; i < SnakesRules.SNAKES.length; i++) if (SnakesRules.SNAKES[i][0] == land) slide = -1 - i;
        final int gg = gen;
        animate(130L * (hopN - 1), () -> {
            if (gg != gen) return;
            if (to == land) { mode = 0; mvSeat = -1; then.run(); return; }
            mode = 3;
            animate(to > land ? 750 : 900, () -> { if (gg != gen) return; mode = 0; mvSeat = -1; then.run(); });
        });
    }

    private void done(int seat, int from, int d, int res) {
        invalidate();
        if ((res & SnakesRules.WON) != 0) { won(seat); return; }
        talk(seat, from, d, res);
        moved();
    }

    /** A far player's roll arrived: shown hopping and climbing / sliding (the base said the words already). */
    @Override void farMoved(String all) {
        int before = g.moves;
        super.farMoved(all);
        if (g.moves != before + 1 || g.lastKind != 1 || g.lastSeat < 0 || getWidth() == 0 || mode != 0) return;
        layout();
        spot(g.lastSeat, g.lastFrom, pt);
        hop(g.lastSeat, pt[0], pt[1], g.lastFrom, g.lastLand, g.lastTo, this::invalidate);
    }

    // ================================================================ words

    /** Numbers as words: num() up to 12, SnakesRules.words() above. */
    private static String tel(int n) { return n <= 12 ? num(n) : SnakesRules.words(n); }

    private String adj(int seat) { return seat < 0 ? "" : new String[]{"ఎర్ర", "పచ్చ", "పసుపు", "నీలి"}[colour[seat]]; }

    /** To the seat: "నాకు", "మీకు", "పచ్చ వారికి", "అబ్బాయికి", "అమ్మగారికి". */
    private String dat(int seat) {
        if (kind(seat) == JARVIS) return "నాకు";
        if (sole(seat)) return "మీకు";
        if (kind(seat) == HERE) return COLOR_NAMES[colour[seat]] + " వారికి";
        String n = name(seat);
        return n.endsWith("ు") ? n.substring(0, n.length() - 1) + "ికి" : n + "కి";
    }

    /** Her name, only for her at the tablet (" అమ్మగారు"), else "". */
    private String herName(int seat) { return "her".equals(role(seat)) && !onPhone && host != null ? " " + host.her() : ""; }

    private String nextWords() {
        int s = g.turn;
        switch (kind(s)) {
            case JARVIS: return pick("ఇప్పుడు నా వంతు.", "ఇక నేను వేస్తాను.");
            case FAR: return "ఇప్పుడు " + name(s) + " వంతు.";
            default:
                if (!sole(s)) return "ఇప్పుడు " + COLOR_NAMES[colour[s]] + " వారి వంతు.";
                return pick("ఇప్పుడు మీరు పాచిక వేయండి.", "ఇప్పుడు మీ వంతు.", "మీరు వేయండి" + herName(s) + ".", "ఇక మీరు పాచిక వేయండి.");
        }
    }

    private String takeReact() { String r = react; react = ""; return r; }

    /** Now and then a word about how the race stands, before Jarvis's own line. */
    private String reaction() {
        int s = g.lastSeat, j = jarvisSeat();
        if (s < 0 || j < 0 || g.lastKind != 1 || kind(s) != HERE || !sole(s) || rnd.nextInt(3) != 0) return "";
        if (g.lastTo != g.lastLand || g.lastTo >= 90) return ""; // (said already)
        if (g.pos[s] >= g.pos[j] + 20) return pick("మీరు నాకంటే చాలా ముందున్నారు! ", "అబ్బో, మీరు దూసుకుపోతున్నారు! ");
        if (g.pos[j] >= g.pos[s] + 20) return "పర్వాలేదు, ఒక్క నిచ్చెనతో మీరు ముందుకొచ్చేస్తారు. ";
        return "";
    }

    private void talk(int seat, int from, int d, int res) {
        boolean ladder = (res & SnakesRules.LADDER) != 0, snake = (res & SnakesRules.SNAKE) != 0, again = (res & SnakesRules.AGAIN) != 0,
                near = (res & SnakesRules.NEAR) != 0;
        int to = g.pos[seat], k = kind(seat);
        if (k == JARVIS) {
            String rx = takeReact(), head = rx + (d == 6 ? pick("ఆరు పడింది! ", "నాకు ఆరు! ") : "నాకు " + num(d) + " పడింది. ");
            String what, feel = null;
            if ((res & SnakesRules.SIXES) != 0) { what = pick("అయ్యో, మూడు సార్లు ఆరు! నా వంతు పోయింది.", "మూడో ఆరు, నియమం ప్రకారం నా వంతు పోయింది."); feel = "laugh"; head = rx; }
            else if ((res & SnakesRules.STAY) != 0) what = "కానీ నూరుకి సరిగ్గా " + tel(100 - from) + " కావాలి, ఇక్కడే ఉంటాను.";
            else if (ladder) { what = pick("ఆహా, నిచ్చెన! ", "భలే, నిచ్చెన దొరికింది! ") + tel(to) + "కి ఎక్కేశాను!"; feel = "happy"; }
            else if (snake) {
                what = pick("అబ్బా! నన్ను పాము మింగేసింది! ", "అయ్యో, పాము నోట్లో పడ్డాను! ", "ఈ పాముకి నేనంటే చాలా ఇష్టం లాగుంది! ") + tel(to) + "కి జారిపోయాను.";
                feel = "laugh";
            } else what = pick(tel(to) + "కి వెళ్లాను.", "నేను " + tel(to) + "కి వచ్చాను.");
            if (near) what += pick(" వందకి దగ్గరకి వచ్చేశాను!", " ఇంకొంచెమే, వంద దగ్గర్లో ఉన్నాను!");
            String tail = again ? pick("ఇంకోసారి నేనే వేస్తాను.", "మళ్లీ నా వంతు!") : nextWords();
            say(head + what + " " + tail, feel, null);
            return;
        }
        if (k != HERE) return;
        boolean one = sole(seat);
        String by = one ? "" : COLOR_NAMES[colour[seat]] + " వారు ";
        String more = by + "ఇంకోసారి వేయండి.";
        String s = null, feel = null, gest = null;
        if ((res & SnakesRules.SIXES) != 0) {
            s = (one ? "" : dat(seat) + " ") + "మూడు సార్లు ఆరు పడింది! నియమం ప్రకారం ఈ వంతు పోయింది. " + nextWords();
            feel = "surprised";
        } else if ((res & SnakesRules.STAY) != 0) {
            String need = tel(100 - from);
            s = (one ? "" : dat(seat) + " ") + pick("నూరుకి సరిగ్గా " + need + " కావాలి" + (one ? herName(seat) : "") + ".", "ఇంకా సరిగ్గా " + need + " పడాలి.",
                    need + " పడితే వైకుంఠమే!") + " " + (again ? more : nextWords());
        } else if (ladder) {
            s = pick("నిచ్చెన ఎక్కారు! ", "భలే, నిచ్చెన! ") + by + tel(to) + "కి వెళ్లారు!" + (again ? " " + more : "");
            feel = "excited";
            gest = "clap";
        } else if (snake) {
            s = "అయ్యో, పాము మింగింది! " + by + tel(to) + "కి దిగారు." + (hasJarvis() && one ? pick(" పర్వాలేదు, మళ్లీ పైకి వెళ్తారు.", " ఏం కాదు, నిచ్చెనలు ఇంకా ఉన్నాయి.", "") : "")
                    + (again ? " " + more : "");
            feel = "caring";
        } else if (again) {
            s = "ఆరు పడింది! " + more;
            feel = "happy";
        }
        if (near) s = (s == null ? "" : s + " ") + (one ? "ఇంకొంచెమే! వందకి దగ్గరలో ఉన్నారు!" : COLOR_NAMES[colour[seat]] + " వారు వందకి దగ్గరలో ఉన్నారు!");
        if (s != null) say(s, feel, gest);
    }

    private void won(int seat) {
        int k = kind(seat);
        if (k == JARVIS) finish(seat, pick("నేను వందకి చేరాను, ఈసారి నేను గెలిచాను! పర్వాలేదు " + host.her() + ", మళ్లీ ఆడదాం.",
                "వైకుంఠం చేరాను! ఇది అదృష్టం ఆట, తర్వాతి ఆట మీదే అవుతుంది. మళ్లీ ఆడదాం!"), "happy", "namaste");
        else if (k == HERE && sole(seat)) finish(seat, pick("మీరు గెలిచారు" + herName(seat) + "! సరిగ్గా వందకి చేరారు, వైకుంఠం మీదే!",
                "అద్భుతం! వైకుంఠం చేరారు, మీరే గెలిచారు!"), "excited", "clap");
        else if (k == HERE) finish(seat, COLOR_NAMES[colour[seat]] + " వారు వందకి చేరి గెలిచారు! చాలా బాగా ఆడారు!", "excited", "clap");
        else finish(seat, name(seat) + " " + verb(seat, "గెలిచారు", "గెలిచాను", "గెలిచాడు") + "! పర్వాలేదు, మళ్లీ ఆడదాం.", "happy", null);
    }

    @Override String lastWords() {
        int s = g.lastSeat;
        if (s < 0 || s >= g.n || g.lastKind == 0) return "";
        String to = dat(s) + " ";
        if (g.lastKind == 3) return to + "మూడు సార్లు ఆరు పడింది, వంతు పోయింది.";
        String d = num(g.lastDie);
        if (g.lastKind == 2) return to + d + " పడింది, నూరుకి సరిగ్గా " + tel(100 - g.lastFrom) + " కావాలి, అక్కడే " + verb(s, "ఉన్నారు", "ఉన్నాను", "ఉన్నాడు") + ".";
        if (g.lastTo > g.lastLand) return to + d + " పడింది, నిచ్చెన ఎక్కి " + tel(g.lastTo) + "కి " + verb(s, "వెళ్లారు", "వెళ్లాను", "వెళ్లాడు") + "!";
        if (g.lastTo < g.lastLand) return to + d + " పడింది, పాము మింగి " + tel(g.lastTo) + "కి " + verb(s, "దిగారు", "దిగాను", "దిగాడు") + ".";
        return to + d + " పడింది, " + tel(g.lastTo) + "కి " + verb(s, "వెళ్లారు", "వెళ్లాను", "వెళ్లాడు") + ".";
    }

    /** A game of luck: nothing to advise but to roll. */
    @Override boolean hint() {
        say("ఇది అదృష్టం ఆట" + herName(g.turn) + ", పాచిక వేయండి!", "happy", "point");
        return true;
    }

    private static final String ROLL = "(?s).*(పాచిక|వేయి|వెయ్యి|వెయ్|వేస్తాను|వేయండి|వేస్తా|దాయం|రోల్|roll|dice|గవ్వలు|నువ్వే జరుపు|నువ్వే పెట్టు|నువ్వే ఆడు|నువ్వే చెయ్యి|నువ్వే చేయి).*";

    @Override boolean heard(String t) {
        if (done || t == null || !t.matches(ROLL)) return false;
        int s = g.turn;
        if (kind(s) != HERE) {
            say(kind(s) == JARVIS ? "ఒక్క క్షణం, ఇప్పుడు నా వంతు." : "ఇప్పుడు " + name(s) + " వంతు, కొంచెం ఆగండి.", null, null);
            return true;
        }
        if (!busy()) roll();
        return true;
    }

    // ================================================================ where things are

    private void layout() {
        int w = getWidth(), h = getHeight();
        if (w == lw && h == lh) return;
        lw = w;
        lh = h;
        float m = 10 * dp;
        if (w >= h) {
            float side = Math.max(60 * dp, Math.min(h - 2 * m, w - 3 * m - 135 * dp)), sw = Math.min(210 * dp, w - 3 * m - side);
            float x0 = Math.max(0, (w - (sw + side + 3 * m)) / 2f);
            strip.set(x0 + m, m, x0 + m + sw, h - m);
            board.set(strip.right + m, (h - side) / 2f, strip.right + m + side, (h + side) / 2f);
            dieS = Math.min(sw * 0.7f, 112 * dp);
            dieX = strip.centerX();
            dieY = strip.top + 46 * dp + dieS / 2f;
            listX = strip.left;
            listY = dieY + dieS / 2f + 50 * dp;
            listW = strip.width();
            listCols = 1;
            startBox.set(strip.left, Math.max(listY, board.bottom - 84 * dp), strip.right, board.bottom);
        } else {
            float side = Math.max(60 * dp, Math.min(w - 2 * m, h - 3 * m - 150 * dp)), sh = Math.min(230 * dp, h - 3 * m - side);
            float y0 = Math.max(0, (h - (sh + side + 3 * m)) / 2f);
            board.set((w - side) / 2f, y0 + m, (w + side) / 2f, y0 + m + side);
            strip.set(m, board.bottom + m, w - m, board.bottom + m + sh);
            startBox.set(strip.left, strip.top, strip.left + 96 * dp, strip.bottom);
            dieS = Math.max(40 * dp, Math.min(sh - 76 * dp, 100 * dp));
            dieX = startBox.right + 16 * dp + dieS / 2f;
            dieY = strip.top + 38 * dp + dieS / 2f;
            listX = dieX + dieS / 2f + 24 * dp;
            listY = strip.top + 4 * dp;
            listW = (strip.right - listX) / 2f;
            listCols = 2;
        }
        rowH = 56 * dp;
        u = board.width() / 10f;
        bmp = null;
    }

    /** The centre of square p (view pixels). */
    private void center(int p, float[] out) {
        out[0] = board.left + (SnakesRules.col(p) + 0.5f) * u;
        out[1] = board.top + (SnakesRules.row(p) + 0.5f) * u;
    }

    /** Where a seat's token rests at position p: its place in the start box (0), or the square's centre. */
    private void spot(int seat, int p, float[] out) {
        if (p <= 0) {
            if (listCols == 1) {
                out[0] = startBox.left + (seat + 0.5f) * startBox.width() / 4f;
                out[1] = startBox.bottom - 28 * dp;
            } else {
                out[0] = startBox.left + (seat % 2 + 0.5f) * startBox.width() / 2f;
                out[1] = startBox.top + 40 * dp + (seat / 2) * 40 * dp;
            }
            return;
        }
        center(p, out);
        out[1] += 0.08f * u;
    }

    // ================================================================ drawing

    @Override protected void draw2(Canvas c) {
        layout();
        if (u <= 0) return;
        boardImage(c);
        marks(c);
        tokens(c);
        side(c);
        if (!done && mode == 0 && host != null && kind(g.turn) == HERE) postInvalidateDelayed(80); // (the die's pulse)
    }

    private void boardImage(Canvas c) {
        int s = Math.round(board.width()), key = s;
        if (!noBmp && (bmp == null || bmpKey != key)) {
            bmp = null;
            try {
                Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
                paintBoard(new Canvas(b), 0, 0);
                bmp = b;
                bmpKey = key;
            } catch (Throwable e) {
                noBmp = true;
            }
        }
        if (bmp != null) c.drawBitmap(bmp, board.left, board.top, null);
        else paintBoard(c, board.left, board.top);
    }

    /** The board itself (drawn once into a picture): squares, ladders, snakes, then the numbers on top. */
    private void paintBoard(Canvas c, float ox, float oy) {
        for (int p = 1; p <= 100; p++) {
            float x = ox + SnakesRules.col(p) * u, y = oy + SnakesRules.row(p) * u;
            fill.setColor(p == 100 ? 0xFFFFD54F : (SnakesRules.col(p) + SnakesRules.row(p)) % 2 == 0 ? 0xFFFFF4D8 : 0xFFFFDDA6);
            c.drawRect(x, y, x + u, y + u, fill);
        }
        line.setColor(0x55704A20);
        line.setStrokeWidth(1.2f * dp);
        for (int i = 1; i < 10; i++) {
            c.drawLine(ox + i * u, oy, ox + i * u, oy + 10 * u, line);
            c.drawLine(ox, oy + i * u, ox + 10 * u, oy + i * u, line);
        }
        // 100: a big star (వైకుంఠం)
        c.save();
        c.translate(ox + 0.55f * u, oy + 0.58f * u);
        c.scale(0.34f * u, 0.34f * u);
        fill.setColor(0xFFFFFFFF);
        c.drawPath(star, fill);
        line.setColor(0xFFB8860B);
        line.setStrokeWidth(2 * dp / (0.34f * u));
        c.drawPath(star, line);
        c.restore();
        for (int[] l : SnakesRules.LADDERS) ladder(c, ox, oy, l[0], l[1]);
        for (int i = 0; i < SnakesRules.SNAKES.length; i++) snake(c, ox, oy, i);
        // the numbers, small, top-left of each square, with a light edge so they show over a snake or a ladder
        left.setTextSize(Math.max(11 * dp, 0.22f * u));
        for (int p = 1; p <= 100; p++) {
            float x = ox + SnakesRules.col(p) * u + 0.07f * u, y = oy + SnakesRules.row(p) * u + 0.06f * u - left.ascent();
            String s = String.valueOf(p);
            left.setStyle(Paint.Style.STROKE);
            left.setStrokeWidth(3 * dp);
            left.setColor(0xDDFFFFFF);
            c.drawText(s, x, y, left);
            left.setStyle(Paint.Style.FILL);
            left.setColor(0xFF3B2A16);
            c.drawText(s, x, y, left);
        }
        line.setColor(0xFF5D3A16);
        line.setStrokeWidth(3 * dp);
        c.drawRect(ox + 1.5f * dp, oy + 1.5f * dp, ox + 10 * u - 1.5f * dp, oy + 10 * u - 1.5f * dp, line);
    }

    /** Two wooden rails with rungs, from the foot square to the top square. */
    private void ladder(Canvas c, float ox, float oy, int foot, int top) {
        float x1 = ox + (SnakesRules.col(foot) + 0.5f) * u, y1 = oy + (SnakesRules.row(foot) + 0.5f) * u;
        float x2 = ox + (SnakesRules.col(top) + 0.5f) * u, y2 = oy + (SnakesRules.row(top) + 0.5f) * u;
        float dx = x2 - x1, dy = y2 - y1, len = (float) Math.hypot(dx, dy), ux = dx / len, uy = dy / len, px = -uy * 0.21f * u, py = ux * 0.21f * u;
        float e = 0.18f * u;
        x1 -= ux * e; y1 -= uy * e; x2 += ux * e; y2 += uy * e;
        len += 2 * e;
        int rungs = Math.max(2, (int) (len / (0.4f * u)));
        for (int pass = 0; pass < 2; pass++) {
            line.setColor(pass == 0 ? 0xFF4A2A0E : 0xFFB9783A);
            float wRung = (pass == 0 ? 0.1f : 0.06f) * u;
            line.setStrokeWidth(wRung);
            for (int i = 1; i < rungs; i++) {
                float t = i / (float) rungs, cx = x1 + (x2 - x1) * t, cy = y1 + (y2 - y1) * t;
                c.drawLine(cx + px, cy + py, cx - px, cy - py, line);
            }
            line.setColor(pass == 0 ? 0xFF4A2A0E : 0xFF9A5B22);
            line.setStrokeWidth((pass == 0 ? 0.13f : 0.08f) * u);
            c.drawLine(x1 + px, y1 + py, x2 + px, y2 + py, line);
            c.drawLine(x1 - px, y1 - py, x2 - px, y2 - py, line);
        }
    }

    /** A cheerful snake: a thick curvy body getting thinner to the tail, spots, a round head with big eyes. */
    private void snake(Canvas c, float ox, float oy, int i) {
        int col = SNAKE_COLORS[i % SNAKE_COLORS.length], dark = mix(col, 0xFF000000, 0.45f), light = mix(col, 0xFFFFFFFF, 0.5f);
        for (int pass = 0; pass < 2; pass++) {
            line.setColor(pass == 0 ? dark : col);
            for (int j = 0; j < SAMPLES - 1; j++) {
                float w = u * (0.3f - 0.21f * j / (SAMPLES - 1f));
                line.setStrokeWidth(pass == 0 ? w + 3.5f * dp : w);
                c.drawLine(ox + SX[i][j] * u, oy + SY[i][j] * u, ox + SX[i][j + 1] * u, oy + SY[i][j + 1] * u, line);
            }
        }
        fill.setColor(light);
        for (int j = 4; j < SAMPLES - 3; j += 3) c.drawCircle(ox + SX[i][j] * u, oy + SY[i][j] * u, u * (0.3f - 0.21f * j / (SAMPLES - 1f)) * 0.22f, fill);
        // the head, looking away from its body
        float hx = ox + SX[i][0] * u, hy = oy + SY[i][0] * u;
        float ang = (float) Math.toDegrees(Math.atan2(SY[i][0] - SY[i][2], SX[i][0] - SX[i][2]));
        c.save();
        c.translate(hx, hy);
        c.rotate(ang);
        rf.set(-0.2f * u, -0.21f * u, 0.3f * u, 0.21f * u);
        fill.setColor(dark);
        c.drawOval(rf.left - 1.8f * dp, rf.top - 1.8f * dp, rf.right + 1.8f * dp, rf.bottom + 1.8f * dp, fill);
        fill.setColor(col);
        c.drawOval(rf, fill);
        line.setColor(0xFFE53935);
        line.setStrokeWidth(Math.max(1.5f * dp, 0.03f * u));
        c.drawLine(0.3f * u, 0, 0.42f * u, 0, line);
        c.drawLine(0.42f * u, 0, 0.48f * u, -0.05f * u, line);
        c.drawLine(0.42f * u, 0, 0.48f * u, 0.05f * u, line);
        for (int e = -1; e <= 1; e += 2) {
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(0.1f * u, e * 0.1f * u, 0.08f * u, fill);
            line.setColor(dark);
            line.setStrokeWidth(1.2f * dp);
            c.drawCircle(0.1f * u, e * 0.1f * u, 0.08f * u, line);
            fill.setColor(0xFF111111);
            c.drawCircle(0.13f * u, e * 0.1f * u, 0.042f * u, fill);
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(0.145f * u, e * 0.1f * u - 0.015f * u, 0.013f * u, fill);
        }
        fill.setColor(0x66FF8A80); // (rosy cheeks)
        c.drawCircle(-0.02f * u, -0.15f * u, 0.04f * u, fill);
        c.drawCircle(-0.02f * u, 0.15f * u, 0.04f * u, fill);
        c.restore();
    }

    /** The last roll's squares (where it started and where it ended). */
    private void marks(Canvas c) {
        if (g.lastKind != 1 || mode != 0) return;
        fill.setColor(0x55FFC107);
        line.setColor(0xFFFFA000);
        line.setStrokeWidth(2.5f * dp);
        for (int i = 0; i < 2; i++) {
            int p = i == 0 ? g.lastFrom : g.lastTo;
            if (p < 1 || p > 100) continue;
            float x = board.left + SnakesRules.col(p) * u, y = board.top + SnakesRules.row(p) * u;
            rf.set(x + 2 * dp, y + 2 * dp, x + u - 2 * dp, y + u - 2 * dp);
            c.drawRect(rf, fill);
            c.drawRect(rf, line);
        }
    }

    private void tokens(Canvas c) {
        Arrays.fill(cnt, 0);
        Arrays.fill(seen, 0);
        for (int s = 0; s < g.n; s++) if (s != mvSeat && g.pos[s] > 0) cnt[g.pos[s]]++;
        // the start box (tokens still off the board)
        if (startBox.height() > 0) {
            fill.setColor(0x1FFFFFFF);
            c.drawRoundRect(startBox, 12 * dp, 12 * dp, fill);
            fitText(c, "మొదలు", startBox.centerX(), startBox.top + 14 * dp, 14 * dp, startBox.width(), 0xFFB4C5D8);
        }
        for (int i = 1; i <= g.n; i++) { // (the seat to play last: on top)
            int s = (g.turn + i) % g.n;
            if (s == mvSeat) continue;
            int p = g.pos[s];
            spot(s, p, pt);
            float x = pt[0], y = pt[1], size = p <= 0 ? Math.min(34 * dp, 0.6f * u) : 0.62f * u;
            if (p > 0 && cnt[p] > 1) {
                int idx = seen[p]++;
                if (cnt[p] == 2) { x += (idx == 0 ? -0.2f : 0.2f) * u; size = 0.5f * u; }
                else { x += (idx % 2 == 0 ? -0.2f : 0.2f) * u; y += (idx / 2 == 0 ? -0.12f : 0.2f) * u; size = 0.42f * u; }
            }
            tokX[s] = x;
            tokY[s] = y;
            drawPawn(c, x, y, size, COLORS[colour[s]], 0);
        }
        if (mvSeat < 0 || mvSeat >= g.n) return;
        int cl = COLORS[colour[mvSeat]];
        if (mode == 2 && hopN >= 2) {
            int segs = hopN - 1;
            float raw = animRaw() * segs;
            int i = Math.min(segs - 1, (int) raw);
            float f = Math.min(1f, raw - i), e = f * f * (3 - 2 * f);
            float x = hopX[i] + (hopX[i + 1] - hopX[i]) * e, y = hopY[i] + (hopY[i + 1] - hopY[i]) * e;
            drawPawn(c, x, y, 0.62f * u, cl, (float) Math.sin(Math.PI * f) * 0.35f * u);
            tokX[mvSeat] = hopX[hopN - 1];
            tokY[mvSeat] = hopY[hopN - 1];
        } else if (mode == 3) {
            float a = anim(), x, y;
            if (slide >= 0) {
                int[] l = SnakesRules.LADDERS[slide];
                center(l[0], pt);
                float x1 = pt[0], y1 = pt[1];
                center(l[1], pt);
                x = x1 + (pt[0] - x1) * a;
                y = y1 + (pt[1] - y1) * a;
            } else {
                int k = -1 - slide;
                float raw = a * (SAMPLES - 1);
                int j = Math.min(SAMPLES - 2, (int) raw);
                float f = raw - j;
                x = board.left + (SX[k][j] + (SX[k][j + 1] - SX[k][j]) * f) * u;
                y = board.top + (SY[k][j] + (SY[k][j + 1] - SY[k][j]) * f) * u;
            }
            drawPawn(c, x, y + 0.08f * u, 0.62f * u, cl, 0.1f * u);
        }
    }

    /** A game pawn with a dark outline and a shadow; lift: up from its shadow. */
    private void drawPawn(Canvas c, float x, float y, float size, int color, float lift) {
        fill.setColor(0x44000000);
        rf.set(x - 0.32f * size, y + 0.3f * size, x + 0.32f * size, y + 0.46f * size);
        c.drawOval(rf, fill);
        c.save();
        c.translate(x, y - lift);
        c.scale(size, size);
        float o = Math.max(2 * dp, size * 0.06f);
        line.setColor(0xFF15202B);
        line.setStrokeWidth(2 * o / size);
        c.drawPath(pawn, line);
        fill.setColor(color);
        c.drawPath(pawn, fill);
        fill.setColor(0x99FFFFFF);
        c.drawCircle(-0.07f, -0.32f, 0.06f, fill);
        c.restore();
    }

    /** Beside the board: whose turn, the die, the players and their squares. */
    private void side(Canvas c) {
        int s = mvSeat >= 0 && mvSeat < g.n ? mvSeat : g.turn; // (while a token moves, it is still that player's go)
        boolean hereRoll = !done && mode == 0 && kind(s) == HERE;
        int cur = COLORS[colour[s]];
        String who = done ? (winner < 0 || winner >= g.n ? "" : kind(winner) == JARVIS ? "Jarvis గెలిచాడు" : label(winner) + " గెలిచారు!")
                : kind(s) == JARVIS ? "Jarvis వంతు" : sole(s) ? "మీ వంతు" : kind(s) == HERE ? COLOR_NAMES[colour[s]] + " వారి వంతు" : name(s) + " వంతు";
        fitText(c, who, dieX, dieY - dieS / 2f - 26 * dp, 20 * dp, Math.max(dieS * 1.3f, (listCols == 1 ? strip.width() : dieS * 2f) - 8 * dp), 0xFFFFFFFF);
        if (hereRoll) {
            float p = (float) (0.5 + 0.5 * Math.sin(SystemClock.uptimeMillis() / 220.0));
            fill.setColor((cur & 0x00FFFFFF) | ((int) (60 + 90 * p) << 24));
            c.drawCircle(dieX, dieY, dieS * (0.78f + 0.06f * p), fill);
        }
        if (mode == 1) {
            float a = animRaw();
            int face = faces[Math.min(faces.length - 1, (int) (a * faces.length))];
            c.save();
            c.translate(0, -(float) Math.abs(Math.sin(a * Math.PI * 3)) * (1 - a) * 18 * dp);
            c.rotate((1 - anim()) * 540f, dieX, dieY);
            drawDie(c, dieX, dieY, dieS, face, cur);
            c.restore();
        } else {
            drawDie(c, dieX, dieY, dieS, g.die == 0 ? 6 : g.die, done ? 0 : cur);
            if (!done && mvSeat < 0 && (g.lastSeat != s || g.die == 0)) { // (the old roll, waiting for the next)
                fill.setColor(0x88FFFFFF);
                rf.set(dieX - dieS / 2f, dieY - dieS / 2f, dieX + dieS / 2f, dieY + dieS / 2f);
                c.drawRoundRect(rf, dieS * 0.18f, dieS * 0.18f, fill);
            }
        }
        if (hereRoll) fitText(c, "నొక్కండి", dieX, dieY + dieS / 2f + 24 * dp, 18 * dp, dieS * 1.6f, 0xFFFFD166);
        for (int i = 0; i < g.n; i++) {
            float x = listX + (i % listCols) * listW, y = listY + (i / listCols) * rowH;
            if (listCols == 1 && y + rowH > startBox.top + 2 * dp) break;
            if (i == s && !done) {
                rf.set(x + 2 * dp, y + 3 * dp, x + listW - 2 * dp, y + rowH - 3 * dp);
                fill.setColor(0x26FFFFFF);
                c.drawRoundRect(rf, 12 * dp, 12 * dp, fill);
                line.setColor(COLORS[colour[i]]);
                line.setStrokeWidth(2.5f * dp);
                c.drawRoundRect(rf, 12 * dp, 12 * dp, line);
            }
            drawPawn(c, x + 22 * dp, y + rowH / 2f, 34 * dp, COLORS[colour[i]], 0);
            String lb = label(i);
            left.setTextSize(17 * dp);
            float tw = left.measureText(lb), max = listW - 50 * dp;
            if (tw > max && max > 0) left.setTextSize(17 * dp * max / tw);
            left.setColor(0xFFFFFFFF);
            c.drawText(lb, x + 44 * dp, y + rowH * 0.42f, left);
            left.setTextSize(16 * dp);
            left.setColor(0xFFFFD166);
            c.drawText(g.pos[i] <= 0 ? "-" : String.valueOf(g.pos[i]), x + 44 * dp, y + rowH * 0.8f, left);
        }
    }

    private String label(int s) {
        if (kind(s) == JARVIS) return "Jarvis";
        if (sole(s)) return "మీరు";
        if (kind(s) == HERE) return COLOR_NAMES[colour[s]];
        return name(s);
    }

    private void fitText(Canvas c, String s, float x, float y, float size, float maxW, int color) {
        text.setTextSize(size);
        float w = text.measureText(s);
        if (w > maxW && maxW > 0) text.setTextSize(size * maxW / w);
        text.setColor(color);
        c.drawText(s, x, y - (text.ascent() + text.descent()) / 2f, text);
    }
}
