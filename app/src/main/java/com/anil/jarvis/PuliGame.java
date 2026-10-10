package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;

/**
 * పులి-మేక on the screen: the 23-point board drawn across the whole view, goats still in hand in the top-left corner
 * (with a count), goats taken in the top-right corner (out of five). While goats are in hand, a tap on any empty
 * point places one; later, a tap on a piece selects it (it glows), its legal points show as big green dots (a jump
 * that takes a goat: a red ring, and the goat at risk is ringed too), and a tap on one moves it (a jump flies in an
 * arc). Jarvis plays either side and talks like a person across the board ("నేను పులిని కిందకి జరిపాను. ఇప్పుడు
 * మీ మేకని పెట్టండి."). Rules: PuliRules.
 */
final class PuliGame extends Game {
    private PuliRules g = new PuliRules();
    private int sel = -1, hintM = -1, tgtN;
    private final int[] tgt = new int[16], tgtOver = new int[16], buf = new int[128];
    /** The move on the screen just now (animated): from (PLACE: from the hand), to, the goat taken, the piece. */
    private int aFrom = -1, aTo = -1, aOver = -1, aPiece, aHandSlot;
    private boolean aJump;
    private int trapSaidAt = -100;
    private int handN = -1, capN = -1, quietN = -1;
    private String handText = "", capText = "", quietText = "";

    // layout
    private float bl, bt, bw, bh, R;
    private final RectF handBox = new RectF(), capBox = new RectF(), tmp = new RectF();
    private final Path hornL = new Path(), hornR = new Path(), beard = new Path();
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics fm = new Paint.FontMetrics();

    PuliGame(Context c) {
        super(c);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextAlign(Paint.Align.LEFT);
        hornL.moveTo(-0.3f, -0.72f);
        hornL.quadTo(-0.42f, -1.5f, -0.98f, -1.36f);
        hornR.moveTo(0.3f, -0.72f);
        hornR.quadTo(0.42f, -1.5f, 0.98f, -1.36f);
        beard.moveTo(-0.2f, 0.72f);
        beard.quadTo(-0.05f, 1.42f, 0.02f, 1.32f);
        beard.quadTo(0.1f, 1.2f, 0.2f, 0.72f);
        beard.close();
    }

    @Override String id() { return "puli"; }
    @Override String title() { return "పులి-మేక"; }
    @Override int[] players() { return new int[]{2, 2}; }
    @Override String[] options() { return new String[]{"🐐 నేను మేకలు", "🐯 నేను పులులు"}; }

    @Override void newGame(String[] who, int option) {
        roles = option == 1 ? new String[]{who[1], who[0]} : new String[]{who[0], who[1]};
        g = new PuliRules();
        clearMarks();
        trapSaidAt = -100;
        start();
        String her = her();
        if (!hasJarvis()) { if (!far()) say("పులి-మేక మొదలు! " + name(0) + " మేకలు, " + name(1) + " పులులు. మేకలు ముందు.", "happy", null); return; }
        if (kind(0) == HERE)
            say(pick("రండి " + her + ", పులి-మేక ఆడదాం! మీకు పదిహేను మేకలు, నాకు మూడు పులులు. మేకలు ముందు — మీ మేకని ఎక్కడైనా ఖాళీ చుక్క మీద పెట్టండి.",
                    "పులి-మేక! నా పులులని కదలకుండా చుట్టేస్తే మీరు గెలుస్తారు. ఐదు మేకలు నాకు దొరికితే నేను గెలుస్తాను. మీ మేకని పెట్టండి."), "happy", "point");
        else if (kind(1) == HERE)
            say(pick("సరే " + her + ", మీరు పులులు, నేను మేకలు! మేకలు ముందు, నేను మొదలుపెడతాను. ఐదు మేకలని పట్టుకుంటే మీరు గెలుస్తారు.",
                    "మీ పులులతో నా మేకలని పట్టుకోండి చూద్దాం! ముందు నేను ఒక మేకని పెడతాను."), "happy", "point");
    }

    private void clearMarks() { sel = -1; hintM = -1; tgtN = 0; aTo = -1; aOver = -1; }

    @Override String save() { return g.save(); }

    @Override boolean load(String s) {
        PuliRules r = new PuliRules();
        if (!r.load(s)) return false;
        g = r;
        clearMarks();
        invalidate();
        return true;
    }

    @Override int turn() { return g.turn; }

    private String her() { return host != null ? host.her() : "అమ్మగారు"; }

    // ================================================================ words

    @Override String yourTurn(int seat) {
        String who = sole(seat) ? "" : name(seat) + ": ";
        if (seat == 0 && g.placing()) return who + "మేకని ఖాళీ చుక్క మీద పెట్టండి 👇 (చేతిలో " + g.inHand + ")";
        if (sel < 0) return who + (seat == 0 ? "మేకని ఎంచుకోండి 👆" : "పులిని ఎంచుకోండి 👆");
        return who + "పచ్చ చుక్క మీద నొక్కండి 👆" + (seat == 1 ? " (ఎరుపు: మేకని పట్టుకోవడం)" : "");
    }

    /** Whose turn now, in words (after Jarvis's move). */
    private String nextWords() {
        int s = g.turn;
        if (kind(s) == FAR) return "ఇప్పుడు " + name(s) + " వంతు.";
        if (kind(s) != HERE) return "";
        if (!sole(s)) return "ఇప్పుడు " + name(s) + " వంతు.";
        if (s == 0 && g.placing()) return pick("మీ మేకని ఎక్కడైనా ఖాళీ చుక్క మీద పెట్టండి.", "ఇప్పుడు మీరు ఒక మేకని పెట్టండి.", "మీ వంతు " + her() + ", మేకని పెట్టండి.");
        if (s == 0) return pick("ఇప్పుడు మీ మేకని జరపండి.", "మీ వంతు " + her() + ".", "ఇక మీరు ఒక మేకని జరపండి.");
        return pick("ఇప్పుడు మీ పులిని జరపండి.", "మీ పులి వంతు.", "ఇక మీరు ఆడండి " + her() + ".");
    }

    @Override String lastWords() {
        int s = g.lastSeat;
        if (s < 0 || g.lastTo < 0) return "";
        if (g.lastFrom == PuliRules.PLACE) return who(s) + " ఒక మేకని " + PuliRules.where(g.lastTo) + " " + verb(s, "పెట్టారు", "పెట్టాను", "పెట్టాడు") + ".";
        if (g.lastOver >= 0) return who(s) + " పులితో ఒక మేకని " + verb(s, "పట్టుకున్నారు", "పట్టుకున్నాను", "పట్టుకున్నాడు") + "!";
        return who(s) + " " + (s == 1 ? "పులిని" : "మేకని") + " " + PuliRules.way(g.lastFrom, g.lastTo) + " " + verb(s, "జరిపారు", "జరిపాను", "జరిపాడు") + ".";
    }

    // ================================================================ playing

    @Override void tap(float x, float y) {
        int seat = g.turn, p = pointAt(x, y);
        if (seat == 0 && g.placing()) {
            if (p >= 0 && g.cell[p] == PuliRules.EMPTY) play(PuliRules.move(PuliRules.PLACE, p));
            else if (p >= 0) host.status("అక్కడ ఖాళీ లేదు — ఖాళీ చుక్క మీద నొక్కండి 👇");
            return;
        }
        if (p >= 0 && sel >= 0) for (int i = 0; i < tgtN; i++) if (tgt[i] == p) { play(PuliRules.move(sel, p)); return; }
        int mine = seat == 0 ? PuliRules.GOAT : PuliRules.TIGER;
        if (p >= 0 && g.cell[p] == mine) {
            sel = p;
            hintM = -1;
            tgtN = 0;
            int n = g.movesFrom(p, buf);
            for (int i = 0; i < n && tgtN < tgt.length; i++) { tgt[tgtN] = PuliRules.to(buf[i]); tgtOver[tgtN] = PuliRules.OVER[p][tgt[tgtN]]; tgtN++; }
            if (tgtN == 0) host.status(seat == 0 ? "ఈ మేక కదలలేదు — ఇంకో మేకని ఎంచుకోండి" : "ఈ పులి కదలలేదు — ఇంకో పులిని ఎంచుకోండి");
            else host.status(yourTurn(seat));
            invalidate();
            return;
        }
        if (sel >= 0) { sel = -1; tgtN = 0; host.status(yourTurn(seat)); invalidate(); }
    }

    /** A move by a person here: kept for undo, shown, talked about, then the next turn. */
    private void play(int m) {
        if (!g.legal(m)) return;
        int seat = g.turn;
        keep();
        int trappedBefore = g.trapped();
        boolean wasPlacing = g.placing();
        show(m);
        g.apply(m);
        clearSel();
        if (end()) return;
        boolean vsJarvis = hasJarvis();
        if (g.lastOver >= 0) {
            if (vsJarvis) say("అయ్యో, నా మేకని తినేసింది మీ పులి!" + (g.captured == PuliRules.WIN - 1 ? " ఇంకొక్కటి పడితే మీరే గెలుస్తారు!" : ""), "surprised", null);
            else say((sole(seat) ? "మీ పులి" : "పులి") + " ఒక మేకని పట్టుకుంది!" + (g.captured == PuliRules.WIN - 1 ? " ఇంకొక్కటి పడితే పులులు గెలుస్తాయి!" : ""), "surprised", null);
        } else if (seat == 0 && g.trapped() > trappedBefore && g.moves - trapSaidAt >= 6) {
            trapSaidAt = g.moves;
            say(vsJarvis ? pick("అబ్బా, నా పులిని అడ్డుకున్నారు!", "అయ్యో, నా పులి ఇరుక్కుపోయింది! భలే అడ్డుకున్నారు.") : (sole(seat) ? "మీ మేకలు" : "మేకలు") + " ఒక పులిని అడ్డుకున్నాయి!", "surprised", null);
        } else if (seat == 0 && wasPlacing && !g.placing()) {
            say(sole(seat) ? "మీ మేకలన్నీ పెట్టేశారు! ఇక మేకలని పక్కనే ఉన్న ఖాళీ చుక్కకి జరపొచ్చు." : "మేకలన్నీ పెట్టేశారు! ఇక మేకలని జరపొచ్చు.", "happy", null);
        }
        moved();
    }

    private void clearSel() { sel = -1; tgtN = 0; hintM = -1; }

    @Override void jarvisMove() {
        if (done) return;
        final PuliRules copy = g.copy();
        final int level = host.level(), mv = g.moves;
        think(() -> copy.choose(level, rnd, 1200), m -> {
            if (m == null || g.moves != mv || !g.legal(m)) return;
            int seat = g.turn;
            int herTrapped = g.trapped();
            boolean wasPlacing = g.placing();
            show(m);
            g.apply(m);
            clearSel();
            if (end()) return;
            String s, feel = null;
            if (g.lastOver >= 0) {
                s = pick("నా పులి మీ మేకని పట్టుకుంది!", "క్షమించండి " + her() + ", నా పులి మీ మేకని పట్టుకుంది!");
                if (g.captured == PuliRules.WIN - 1) s += " ఇంకొక్క మేక దొరికితే నా పులులు గెలుస్తాయి, జాగ్రత్త!";
                feel = "laugh";
            } else if (g.lastFrom == PuliRules.PLACE) {
                s = pick("నేను ఒక మేకని " + PuliRules.where(g.lastTo) + " పెట్టాను.", "నా మేకని " + PuliRules.where(g.lastTo) + " పెట్టాను.");
                if (wasPlacing && !g.placing()) s += " నా మేకలన్నీ పెట్టేశాను, ఇక జరుపుతాను.";
            } else {
                String way = PuliRules.way(g.lastFrom, g.lastTo);
                s = seat == 1 ? pick("నేను పులిని " + way + " జరిపాను.", "నా పులి " + way + " వెళ్లింది.") : pick("నేను మేకని " + way + " జరిపాను.", "నా మేక " + way + " వెళ్లింది.");
            }
            if (seat == 0 && g.trapped() > herTrapped && g.moves - trapSaidAt >= 6) {
                trapSaidAt = g.moves;
                s = pick("మీ పులిని నా మేకలు చుట్టేశాయి!", "అబ్బో, మీ పులి ఇక కదలలేదు!") + " " + s;
                feel = "laugh";
            }
            if (seat == 1 && level == 0 && g.threats() > 0 && rnd.nextBoolean()) s += " జాగ్రత్త " + her() + ", మీ మేక ఒకటి ప్రమాదంలో ఉంది!";
            String nx = nextWords();
            say(nx.isEmpty() ? s : s + " " + nx, feel, null);
            moved();
        });
    }

    /** After a move: the end, said (true when the game is over). */
    private boolean end() {
        int r = g.result();
        if (r == -2) return false;
        if (r == -1) {
            finish(-1, pick("అరవై ఎత్తులుగా ఒక్క మేక కూడా పట్టుబడలేదు — ఆట సమానం! బాగా ఆడాం.", "ఆట సమానంగా ముగిసింది! మళ్లీ ఆడదామా?"), "happy", "thumb");
            return true;
        }
        String how = r == 0 ? "మేకలు పులులన్నిటినీ కదలకుండా చుట్టేశాయి." : g.captured >= PuliRules.WIN ? "పులులు ఐదు మేకలని పట్టుకున్నాయి." : "మేకలకి కదలడానికి చోటు లేదు.";
        if (role(r).equals("jarvis")) {
            String w = r == 0 ? "నా మేకలు మీ పులులన్నిటినీ కట్టేశాయి! " : g.captured >= PuliRules.WIN ? "నా పులులు ఐదు మేకలని పట్టుకున్నాయి. " : "మీ మేకలకి కదలడానికి చోటు లేదు. ";
            finish(r, w + pick("ఈసారి నేను గెలిచాను. పర్వాలేదు " + her() + ", మళ్లీ ఆడదాం!", "ఈసారి నాది. మీరు బాగా ఆడారు, మళ్లీ ఆడదాం!"), "happy", "thumb");
        } else if (sole(r) && hasJarvis()) {
            String w = r == 0 ? "మీ మేకలు నా పులులన్నిటినీ కట్టేశాయి! " : g.captured >= PuliRules.WIN ? "మీ పులులు ఐదు మేకలని పట్టేశాయి! " : "నా మేకలకి కదలడానికి చోటు లేదు! ";
            finish(r, w + pick("మీరు గెలిచారు " + her() + "!", "మీరే గెలిచారు, చాలా బాగా ఆడారు!"), "excited", "clap");
        } else {
            finish(r, name(r) + " " + verb(r, "గెలిచారు", "గెలిచాను", "గెలిచాడు") + "! " + how,
                    kind(r) == HERE ? "excited" : "happy", kind(r) == HERE ? "clap" : null);
        }
        return true;
    }

    @Override boolean hint() {
        if (done) return false;
        int m = g.copy().best();
        if (m < 0) return false;
        hintM = m;
        sel = -1;
        tgtN = 0;
        invalidate();
        int f = PuliRules.from(m), t = PuliRules.to(m);
        if (f == PuliRules.PLACE) say(pick("నేనైతే మేకని " + PuliRules.where(t) + " పెడతాను.", PuliRules.where(t) + " పెట్టి చూడండి."), "happy", "point");
        else if (PuliRules.OVER[f][t] >= 0) say("ఆ పులితో మేకని పట్టుకోండి! నీలం వలయం చూడండి.", "laugh", "point");
        else say(pick("ఆ " + (g.turn == 1 ? "పులిని " : "మేకని ") + PuliRules.way(f, t) + " జరపండి.", "నేనైతే నీలం వలయంలో ఉన్న " + (g.turn == 1 ? "పులిని " : "మేకని ") + PuliRules.way(f, t) + " జరుపుతాను."), "happy", "point");
        return true;
    }

    @Override boolean heard(String t) {
        if (done || t == null) return false;
        if (!t.matches("(?s).*(నువ్వే జరుపు|నువ్వే పెట్టు|నువ్వే ఆడు|నువ్వే చెయ్యి|నువ్వే చేయి).*")) return false;
        int s = g.turn;
        if (kind(s) != HERE) { if (kind(s) == JARVIS) host.status("ఒక్క క్షణం, Jarvis వంతు…"); return true; }
        if (busy()) return true;
        int m = g.copy().best();
        if (m >= 0) play(m);
        return true;
    }

    // ================================================================ animation

    /** Starts showing move m (before it is applied: where the pieces were). */
    private void show(int m) {
        aFrom = PuliRules.from(m);
        aTo = PuliRules.to(m);
        aOver = aFrom == PuliRules.PLACE ? -1 : PuliRules.OVER[aFrom][aTo] >= 0 && g.cell[PuliRules.OVER[aFrom][aTo]] == PuliRules.GOAT && g.cell[aFrom] == PuliRules.TIGER ? PuliRules.OVER[aFrom][aTo] : -1;
        aJump = aOver >= 0;
        aPiece = aFrom == PuliRules.PLACE ? PuliRules.GOAT : g.cell[aFrom];
        aHandSlot = g.inHand - 1;
        final int gg = gen;
        animate(aJump ? 600 : aFrom == PuliRules.PLACE ? 420 : 360, () -> { if (gg == gen) { aTo = -1; aOver = -1; invalidate(); } });
    }

    // ================================================================ drawing

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layout(w, h);
    }

    private void layout(float w, float h) {
        if (w <= 0 || h <= 0) return;
        float m = 10 * dp, pad = Math.min(34 * dp, Math.min(w, h) * 0.06f);
        bl = m + pad;
        bt = m + pad;
        bw = w - 2 * (m + pad);
        bh = h - 2 * (m + pad);
        float md = 1e9f;
        for (int p = 0; p < PuliRules.N; p++)
            for (int q : PuliRules.ADJ[p]) md = Math.min(md, (float) Math.hypot((PuliRules.X[p] - PuliRules.X[q]) * bw / 100f, (PuliRules.Y[p] - PuliRules.Y[q]) * bh / 100f));
        R = Math.min(md * 0.42f, 36 * dp);
        float rowTop = py(PuliRules.Y[1]) - R - 8 * dp;
        handBox.set(m, m, px(PuliRules.X[2]) - R * 0.6f, rowTop);
        capBox.set(px(PuliRules.X[5]) + R * 0.6f, m, w - m, rowTop);
    }

    private float px(float x) { return bl + x * bw / 100f; }
    private float py(float y) { return bt + y * bh / 100f; }

    private int pointAt(float x, float y) {
        int best = -1;
        float bd = Math.max(R * 1.3f, 40 * dp);
        for (int p = 0; p < PuliRules.N; p++) {
            float d = (float) Math.hypot(x - px(PuliRules.X[p]), y - py(PuliRules.Y[p]));
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    @Override protected void draw2(Canvas c) {
        if (R <= 0) layout(getWidth(), getHeight());
        if (R <= 0) return;
        long now = SystemClock.uptimeMillis();
        float pulse = (float) (0.5 + 0.5 * Math.sin(now / 200.0));
        boolean anim = aTo >= 0 && animating();
        boolean herPlace = !done && kind(g.turn) == HERE && g.turn == 0 && g.placing() && !anim;

        // the floor and the lines
        fill.setColor(0xFF1F3A30);
        tmp.set(bl - R - 6 * dp, bt - R - 6 * dp, bl + bw + R + 6 * dp, bt + bh + R + 6 * dp);
        c.drawRoundRect(tmp, 22 * dp, 22 * dp, fill);
        line.setColor(0xFFF3E7CF);
        line.setStrokeWidth(5 * dp);
        for (int[] l : PuliRules.LINES) c.drawLine(px(PuliRules.X[l[0]]), py(PuliRules.Y[l[0]]), px(PuliRules.X[l[l.length - 1]]), py(PuliRules.Y[l[l.length - 1]]), line);

        // the last move (both points) and the goat it took
        if (g.lastTo >= 0 && !anim) {
            fill.setColor(0x55FFD166);
            if (g.lastFrom >= 0 && g.lastFrom < PuliRules.N) c.drawCircle(px(PuliRules.X[g.lastFrom]), py(PuliRules.Y[g.lastFrom]), R * 1.3f, fill);
            c.drawCircle(px(PuliRules.X[g.lastTo]), py(PuliRules.Y[g.lastTo]), R * 1.3f, fill);
            if (g.lastOver >= 0) {
                line.setColor(0xAAFF5252);
                line.setStrokeWidth(4 * dp);
                float x = px(PuliRules.X[g.lastOver]), y = py(PuliRules.Y[g.lastOver]), k = R * 0.45f;
                c.drawLine(x - k, y - k, x + k, y + k, line);
                c.drawLine(x + k, y - k, x - k, y + k, line);
            }
        }
        // the points
        for (int p = 0; p < PuliRules.N; p++) {
            float x = px(PuliRules.X[p]), y = py(PuliRules.Y[p]);
            if (herPlace && g.cell[p] == PuliRules.EMPTY) {
                fill.setColor((((int) (90 + 110 * pulse)) << 24) | 0x0030C46C);
                c.drawCircle(x, y, R * 0.55f, fill);
            }
            fill.setColor(0xFFF3E7CF);
            c.drawCircle(x, y, 8 * dp, fill);
        }
        // the hint (blue rings)
        if (hintM >= 0 && !anim) {
            line.setColor(0xFF4DD0E1);
            line.setStrokeWidth(7 * dp);
            int f = PuliRules.from(hintM), t = PuliRules.to(hintM);
            if (f != PuliRules.PLACE) c.drawCircle(px(PuliRules.X[f]), py(PuliRules.Y[f]), R * 1.3f, line);
            c.drawCircle(px(PuliRules.X[t]), py(PuliRules.Y[t]), R * 0.9f, line);
        }
        // the pieces
        for (int p = 0; p < PuliRules.N; p++) {
            int v = g.cell[p];
            if (v == PuliRules.EMPTY || (anim && p == aTo)) continue;
            float x = px(PuliRules.X[p]), y = py(PuliRules.Y[p]);
            if (p == sel) {
                line.setColor(0xFFFFD166);
                line.setStrokeWidth(5 * dp + 3 * dp * pulse);
                c.drawCircle(x, y, R * (1.25f + 0.1f * pulse), line);
            }
            if (v == PuliRules.TIGER) tiger(c, x, y, R); else goat(c, x, y, R);
        }
        // where the selected piece can go: big green dots; a jump that takes a goat: a red ring (and the goat ringed)
        for (int i = 0; i < tgtN && sel >= 0; i++) {
            float x = px(PuliRules.X[tgt[i]]), y = py(PuliRules.Y[tgt[i]]);
            if (tgtOver[i] >= 0) {
                line.setColor(0xFFFF5252);
                line.setStrokeWidth(6 * dp);
                c.drawCircle(x, y, R * 0.8f, line);
                fill.setColor(0xCCFF5252);
                c.drawCircle(x, y, R * 0.4f, fill);
                line.setStrokeWidth(4 * dp);
                c.drawCircle(px(PuliRules.X[tgtOver[i]]), py(PuliRules.Y[tgtOver[i]]), R * 1.18f, line);
            } else {
                fill.setColor(0xDD30C46C);
                c.drawCircle(x, y, R * (0.5f + 0.08f * pulse), fill);
                line.setColor(0xFFFFFFFF);
                line.setStrokeWidth(3 * dp);
                c.drawCircle(x, y, R * (0.5f + 0.08f * pulse), line);
            }
        }
        drawHand(c, herPlace, pulse);
        drawTaken(c);
        if (anim) drawMoving(c);
        if (!done && kind(g.turn) == HERE && (herPlace || sel >= 0) && !animating()) postInvalidateDelayed(50);
    }

    /** The piece moving now: a goat flying in from the hand, a step, or a jump in an arc (the goat taken fades). */
    private void drawMoving(Canvas c) {
        float t = anim();
        float tx = px(PuliRules.X[aTo]), ty = py(PuliRules.Y[aTo]), fx, fy;
        if (aFrom == PuliRules.PLACE) { fx = handX(Math.max(0, aHandSlot)); fy = handY(Math.max(0, aHandSlot)); }
        else { fx = px(PuliRules.X[aFrom]); fy = py(PuliRules.Y[aFrom]); }
        if (aOver >= 0 && t < 0.85f) {
            float k = t < 0.5f ? 1f : 1f - (t - 0.5f) / 0.35f;
            goat(c, px(PuliRules.X[aOver]), py(PuliRules.Y[aOver]), R * Math.max(0.05f, k));
        }
        float lift = aJump ? R * 2.2f : aFrom == PuliRules.PLACE ? R * 1.2f : R * 0.3f;
        float x = fx + (tx - fx) * t, y = fy + (ty - fy) * t - (float) Math.sin(Math.PI * t) * lift;
        float r = aFrom == PuliRules.PLACE ? R * (0.7f + 0.3f * t) : R * (1f + (aJump ? 0.15f * (float) Math.sin(Math.PI * t) : 0));
        if (aPiece == PuliRules.TIGER) tiger(c, x, y, r); else goat(c, x, y, r);
    }

    private float handCell() { return Math.min(handBox.width() / 5f, (handBox.height() - handLabel()) / 3f); }
    private float handLabel() { return Math.min(34 * dp, handBox.height() * 0.22f); }
    private float handX(int i) { return handBox.left + (i % 5 + 0.5f) * handCell(); }
    private float handY(int i) { return handBox.top + handLabel() + (i / 5 + 0.5f) * handCell(); }

    /** Goats still in hand (top-left): a little herd and the count. */
    private void drawHand(Canvas c, boolean glow, float pulse) {
        if (handBox.width() <= 0 || handBox.height() <= 0) return;
        if (glow) {
            line.setColor((((int) (110 + 140 * pulse)) << 24) | 0x0030C46C);
            line.setStrokeWidth(4 * dp);
            tmp.set(handBox.left, handBox.top, handBox.left + 5 * handCell(), handBox.top + handLabel() + 3 * handCell());
            c.drawRoundRect(tmp, 16 * dp, 16 * dp, line);
        }
        label.setTextSize(handLabel() * 0.6f);
        label.setColor(0xFFF3E7CF);
        label.getFontMetrics(fm);
        if (handN != g.inHand) { handN = g.inHand; handText = "చేతిలో మేకలు: " + handN; }
        c.drawText(handText, handBox.left + 8 * dp, handBox.top + handLabel() / 2f - (fm.ascent + fm.descent) / 2f, label);
        float r = handCell() * 0.34f;
        for (int i = 0; i < g.inHand && i < 15; i++) goat(c, handX(i), handY(i), r); // (a goat being placed flies from its spot)
    }

    /** Goats taken (top-right): five places, the taken ones dimmed. */
    private void drawTaken(Canvas c) {
        if (capBox.width() <= 0 || capBox.height() <= 0) return;
        float lab = Math.min(34 * dp, capBox.height() * 0.22f), cell = Math.min(capBox.width() / 5f, (capBox.height() - lab) / 2f);
        label.setTextSize(lab * 0.6f);
        label.setColor(0xFFF3E7CF);
        label.getFontMetrics(fm);
        float left = capBox.right - 5 * cell;
        if (capN != g.captured) { capN = g.captured; capText = "పట్టిన మేకలు: " + capN + " / " + PuliRules.WIN; }
        c.drawText(capText, left + 4 * dp, capBox.top + lab / 2f - (fm.ascent + fm.descent) / 2f, label);
        float r = cell * 0.34f, y = capBox.top + lab + cell * 0.5f;
        for (int i = 0; i < PuliRules.WIN; i++) {
            float x = left + (i + 0.5f) * cell;
            if (i < g.captured) {
                goat(c, x, y, r);
                fill.setColor(0x99000000);
                c.drawCircle(x, y, r * 1.05f, fill);
                line.setColor(0xFFFF5252);
                line.setStrokeWidth(3 * dp);
                c.drawLine(x - r * 0.7f, y - r * 0.7f, x + r * 0.7f, y + r * 0.7f, line);
            } else {
                line.setColor(0x66F3E7CF);
                line.setStrokeWidth(2 * dp);
                c.drawCircle(x, y, r, line);
            }
        }
        if (!g.placing() && g.quiet > 0) {
            label.setTextSize(lab * 0.5f);
            label.setColor(g.quiet >= PuliRules.QUIET - 10 ? 0xFFFFD166 : 0xFFB4C5D8);
            if (quietN != g.quiet) { quietN = g.quiet; quietText = "ఎత్తులు " + quietN + " / " + PuliRules.QUIET + " (సమానం దాకా)"; }
            c.drawText(quietText, left + 4 * dp, y + cell * 0.5f + lab * 0.6f, label);
        }
    }

    /** A tiger's face: orange with dark stripes, yellow eyes, a dark nose (drawn in unit size, scaled to r). */
    private void tiger(Canvas c, float x, float y, float r) {
        c.save();
        c.translate(x, y);
        c.scale(r, r);
        fill.setColor(0x66000000);
        c.drawCircle(0.08f, 0.14f, 1f, fill);
        line.setColor(0xFF2B1A0E);
        line.setStrokeWidth(0.08f);
        fill.setColor(0xFFF08A24);
        c.drawCircle(-0.62f, -0.7f, 0.3f, fill);
        c.drawCircle(0.62f, -0.7f, 0.3f, fill);
        c.drawCircle(-0.62f, -0.7f, 0.3f, line);
        c.drawCircle(0.62f, -0.7f, 0.3f, line);
        fill.setColor(0xFF6B3A1E);
        c.drawCircle(-0.62f, -0.7f, 0.13f, fill);
        c.drawCircle(0.62f, -0.7f, 0.13f, fill);
        fill.setColor(0xFFF08A24);
        c.drawCircle(0, 0, 1f, fill);
        c.drawCircle(0, 0, 1f, line);
        line.setStrokeWidth(0.13f);
        c.drawLine(0, -0.93f, 0, -0.62f, line);
        c.drawLine(-0.3f, -0.9f, -0.21f, -0.64f, line);
        c.drawLine(0.3f, -0.9f, 0.21f, -0.64f, line);
        c.drawLine(-0.97f, -0.16f, -0.66f, -0.06f, line);
        c.drawLine(-0.95f, 0.24f, -0.64f, 0.2f, line);
        c.drawLine(0.97f, -0.16f, 0.66f, -0.06f, line);
        c.drawLine(0.95f, 0.24f, 0.64f, 0.2f, line);
        fill.setColor(0xFFFFF4E0);
        tmp.set(-0.44f, 0.1f, 0.44f, 0.74f);
        c.drawOval(tmp, fill);
        fill.setColor(0xFFFFE066);
        c.drawCircle(-0.36f, -0.2f, 0.16f, fill);
        c.drawCircle(0.36f, -0.2f, 0.16f, fill);
        line.setStrokeWidth(0.05f);
        c.drawCircle(-0.36f, -0.2f, 0.16f, line);
        c.drawCircle(0.36f, -0.2f, 0.16f, line);
        fill.setColor(0xFF111111);
        c.drawCircle(-0.36f, -0.2f, 0.07f, fill);
        c.drawCircle(0.36f, -0.2f, 0.07f, fill);
        fill.setColor(0xFF2B1A0E);
        tmp.set(-0.14f, 0.2f, 0.14f, 0.37f);
        c.drawOval(tmp, fill);
        line.setStrokeWidth(0.06f);
        c.drawLine(0, 0.36f, -0.15f, 0.52f, line);
        c.drawLine(0, 0.36f, 0.15f, 0.52f, line);
        c.restore();
    }

    /** A goat's face: white, with curved horns, floppy ears, dark eyes, a cream muzzle and a little beard. */
    private void goat(Canvas c, float x, float y, float r) {
        if (r <= 0.5f) return;
        c.save();
        c.translate(x, y);
        c.scale(r, r);
        fill.setColor(0x55000000);
        c.drawCircle(0.08f, 0.14f, 1f, fill);
        // horns (behind the head): a dark edge, then the horn
        line.setColor(0xFF4A3C2C);
        line.setStrokeWidth(0.28f);
        c.drawPath(hornL, line);
        c.drawPath(hornR, line);
        line.setColor(0xFFB8A27E);
        line.setStrokeWidth(0.17f);
        c.drawPath(hornL, line);
        c.drawPath(hornR, line);
        // the beard, peeping below the chin
        fill.setColor(0xFFE2DBCB);
        c.drawPath(beard, fill);
        line.setColor(0xFF6E5B45);
        line.setStrokeWidth(0.06f);
        c.drawPath(beard, line);
        // floppy ears
        fill.setColor(0xFFE3CFAE);
        line.setStrokeWidth(0.07f);
        for (int side = -1; side <= 1; side += 2) {
            c.save();
            c.translate(side * 0.8f, -0.1f);
            c.rotate(side * 32);
            tmp.set(-0.46f, -0.18f, 0.46f, 0.18f);
            c.drawOval(tmp, fill);
            c.drawOval(tmp, line);
            c.restore();
        }
        // the head
        fill.setColor(0xFFFBF7EE);
        c.drawCircle(0, 0, 0.9f, fill);
        line.setStrokeWidth(0.08f);
        c.drawCircle(0, 0, 0.9f, line);
        fill.setColor(0xFF2A211A);
        c.drawCircle(-0.34f, -0.16f, 0.12f, fill);
        c.drawCircle(0.34f, -0.16f, 0.12f, fill);
        fill.setColor(0xFFFFFFFF);
        c.drawCircle(-0.3f, -0.2f, 0.04f, fill);
        c.drawCircle(0.38f, -0.2f, 0.04f, fill);
        fill.setColor(0xFFEDE1CC);
        tmp.set(-0.34f, 0.18f, 0.34f, 0.74f);
        c.drawOval(tmp, fill);
        fill.setColor(0xFF4A3C2C);
        tmp.set(-0.19f, 0.4f, -0.07f, 0.48f);
        c.drawOval(tmp, fill);
        tmp.set(0.07f, 0.4f, 0.19f, 0.48f);
        c.drawOval(tmp, fill);
        line.setStrokeWidth(0.05f);
        c.drawLine(0, 0.5f, 0, 0.62f, line);
        c.restore();
    }
}
