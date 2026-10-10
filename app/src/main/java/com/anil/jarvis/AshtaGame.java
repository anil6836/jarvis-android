package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;

import java.util.List;

/**
 * అష్టా చమ్మా on the screen: the 5×5 board on the left (safe squares with a big X), the four cowrie shells and the
 * banked throws in the strip on the right. A tap on the shells (or her words "గవ్వలు వేయి") throws them; the pawns
 * that can move pulse and a ring shows where each one would land; a tap on a pawn (or on its ring) moves it step by
 * step. With only one choice the pawn moves by itself after a moment. Jarvis throws and moves the same way and talks
 * like a person across the board ("చమ్మా! నేను మళ్లీ వేస్తాను." … "నేను కాయని మూడు గడులు జరిపాను. ఇప్పుడు మీ వంతు.").
 * Rules: AshtaRules.
 */
final class AshtaGame extends Game {
    private AshtaRules g = new AshtaRules(2, 4);
    private int hintPawn = -1;
    /** A pending automatic move (only one choice) is valid while this hasn't changed. */
    private int autoToken;
    private boolean autoSaid;

    // the throw (shells tumbling)
    private boolean tumbling;
    private int tumbleBits;
    private final float[] shellAng = {74, 101, 109, 83}, shellSpin = {400, -520, 610, -380};
    // a pawn walking (and the pawns it sent home flying back)
    private boolean walking;
    private int wSeat = -1, wPawn = -1, wN, capN, capCell;
    private final int[] wCells = new int[32], capSeat = new int[16], capPawn = new int[16];
    private float walkPart = 1f;

    // layout (set when the size is known)
    private final RectF board = new RectF(), listBox = new RectF(), shellBox = new RectF(), bankBox = new RectF(), tmp = new RectF();
    private float cs, rowH;
    private final float[][] slotX = new float[4][4], slotY = new float[4][4], slotR = new float[4][4];
    private final int[][] occ = new int[25][16];
    private final int[] occN = new int[25];
    private String[] names = new String[0];
    /** The choices now (kept while drawing; made again when the game moves on). */
    private List<Integer> ch;
    private AshtaRules chFor;
    private int chCount;
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics fm = new Paint.FontMetrics();
    private static final String[] DIG = {"0", "1", "2", "3", "4", "5", "6", "7", "8"};
    private static final float[][] SHELL_AT = {{0.28f, 0.30f}, {0.72f, 0.26f}, {0.30f, 0.72f}, {0.71f, 0.70f}};

    AshtaGame(Context c) {
        super(c);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextAlign(Paint.Align.LEFT);
    }

    @Override String id() { return "ashta"; }
    @Override String title() { return "అష్టా చమ్మా"; }
    @Override int[] players() { return new int[]{2, 4}; }
    @Override String[] options() { return new String[]{"🐚 పూర్తి ఆట (4 కాయలు)", "⚡ చిన్న ఆట (2 కాయలు)"}; }

    @Override void newGame(String[] who, int option) {
        roles = who.clone();
        g = new AshtaRules(roles.length, option == 1 ? 2 : 4);
        reset();
        start();
        String her = host != null ? host.her() : "అమ్మగారు";
        if (hasJarvis() && kind(0) == HERE)
            say(pick("రండి " + her + ", అష్టా చమ్మా ఆడదాం! మీవి " + colour(0) + " కాయలు, కింద ఉన్నాయి. గవ్వల మీద నొక్కి వేయండి.",
                    "అష్టా చమ్మా! మీరే ముందు, గవ్వలు వేయండి. గుర్తుందా, ఒక కాయని కొట్టాకే లోపలి గడులలోకి వెళ్లొచ్చు."), "happy", "point");
        else if (!hasJarvis() && !far()) say("అష్టా చమ్మా మొదలు! " + colour(0) + " కాయల వాళ్లు ముందు గవ్వలు వేయండి.", "happy", null);
    }

    private void reset() {
        hintPawn = -1;
        autoToken++;
        autoSaid = false;
        tumbling = walking = false;
        capN = 0;
        names = new String[0];
    }

    @Override String save() { return g.save(); }

    @Override boolean load(String s) {
        AshtaRules r = AshtaRules.parse(s);
        if (r == null) return false;
        g = r;
        hintPawn = -1;
        autoToken++;
        tumbling = walking = false;
        capN = 0;
        names = new String[0];
        invalidate();
        return true;
    }

    @Override int turn() { return g.turn; }

    // ================================================================ words

    private String colour(int seat) { return COLOR_NAMES[g.side(seat)]; }
    private int colourOf(int seat) { return COLORS[g.side(seat)]; }
    private String her() { return host != null ? host.her() : "అమ్మగారు"; }
    /** " అమ్మగారు" only for her at the tablet (never to Anil on his phone), else "". */
    private String herName(int seat) { return "her".equals(role(seat)) && !onPhone ? " " + her() : ""; }

    /** "మీ కాయని" / "నా కాయని" / "పచ్చ కాయని" for the seat whose pawn was sent home. */
    private String whosePawn(int seat) {
        if (role(seat).equals("jarvis")) return "నా కాయని";
        if (sole(seat)) return "మీ కాయని";
        return colour(seat) + " కాయని";
    }

    private int caughtSeat() {
        for (int s = 0; s < g.n; s++) if ((g.lastCaughtSeats & (1 << s)) != 0) return s;
        return -1;
    }

    /** Whose turn it is now, in words (after Jarvis's turn ends). */
    private String nextWords() {
        int s = g.turn;
        if (kind(s) == JARVIS) return "";
        if (kind(s) == FAR) return "ఇప్పుడు " + name(s) + " వంతు.";
        if (sole(s)) return pick("ఇప్పుడు మీరు గవ్వలు వేయండి.", "మీ వంతు " + her() + ", గవ్వలు వేయండి.", "ఇక మీరు వేయండి.", "ఇప్పుడు మీ వంతు.");
        return "ఇప్పుడు " + colour(s) + " కాయల వంతు, " + name(s) + " వేయండి.";
    }

    @Override String yourTurn(int seat) {
        String who = sole(seat) ? "" : name(seat) + " (" + colour(seat) + "): ";
        if (g.phase == AshtaRules.THROW) return who + "గవ్వలు వేయండి 👉 గవ్వల మీద నొక్కండి";
        List<Integer> ch = g.choices();
        if (ch.size() == 1) { autoMove(seat, ch.get(0)); return who + g.now() + " వచ్చింది — కాయ తనంతట తానే కదులుతుంది…"; }
        return who + g.now() + " వచ్చింది — కాయ మీద నొక్కండి 👆";
    }

    @Override String lastWords() {
        int s = g.lastSeat;
        if (s < 0) return "";
        int v = g.lastValue;
        if (g.lastAct == 1) {
            String got = v == 4 ? "చమ్మా వచ్చింది!" : v == 8 ? "అష్టా వచ్చింది!" : num(v) + " వచ్చింది.";
            return who(s) + " గవ్వలు " + verb(s, "వేశారు", "వేశాను", "వేశాడు") + ", " + got;
        }
        if (g.lastAct != 2) return "";
        String w = who(s) + " కాయని " + AshtaRules.steps(v) + " " + verb(s, "జరిపారు", "జరిపాను", "జరిపాడు") + ".";
        int c = caughtSeat();
        if (g.lastCaught > 0 && c >= 0) w += " " + whosePawn(c) + " ఇంటికి " + verb(s, "పంపారు", "పంపాను", "పంపాడు") + "!";
        if (g.lastTo == AshtaRules.HOME) w += " ఆ కాయ పండింది!";
        return w;
    }

    // ================================================================ playing

    @Override void tap(float x, float y) {
        int seat = g.turn;
        if (g.phase == AshtaRules.THROW) { throwNow(); return; } // (anywhere: the only thing to do now is throw)
        int c = cellAt(x, y);
        if (c < 0) return;
        List<Integer> ch = g.choices();
        int k = -1;
        for (int j : ch) if (g.cell(seat, g.pos[seat][j]) == c) k = j;
        if (k < 0) for (int j : ch) if (g.cell(seat, g.target(seat, j, g.now())) == c) k = j;
        if (k < 0) {
            for (int j = 0; j < g.per; j++)
                if (g.pos[seat][j] < AshtaRules.HOME && g.cell(seat, g.pos[seat][j]) == c) { host.status("ఈ కాయ " + g.now() + " కి కదలదు — మెరుస్తున్న కాయని నొక్కండి"); return; }
            return;
        }
        autoToken++;
        keep();
        doMove(k);
    }

    /** Only one choice: it moves by itself after a moment (said once a game). */
    private void autoMove(int seat, int k) {
        final int token = ++autoToken, cnt = g.count;
        if (!autoSaid && hasJarvis() && sole(seat)) { autoSaid = true; say("ఒకటే దారి ఉంది, కాయ దానంతట అదే కదులుతుంది.", null, null); }
        later(800, new Runnable() {
            @Override public void run() {
                if (token != autoToken || done || g.turn != seat || g.count != cnt || g.phase != AshtaRules.MOVE || kind(seat) != HERE || !g.canMove(k)) return;
                if (busy()) { later(300, this); return; }
                doMove(k); // (no keep(): undo goes back to her last real choice, not to this forced move that would replay itself)
            }
        });
    }

    /** The shells are thrown (her tap / words, or Jarvis): they tumble, land, and the throw is banked. */
    private void throwNow() {
        if (tumbling || walking || done || g.phase != AshtaRules.THROW) return;
        final int seat = g.turn;
        tumbleBits = AshtaRules.throwShells(rnd);
        for (int i = 0; i < 4; i++) {
            shellAng[i] = 68 + rnd.nextInt(44); // (lying mostly up and down: bigger in the narrow strip)
            shellSpin[i] = (rnd.nextBoolean() ? 1 : -1) * (360 + rnd.nextInt(400));
        }
        tumbling = true;
        hintPawn = -1;
        autoToken++;
        final int gg = gen;
        animate(800, () -> {
            if (gg != gen) return;
            tumbling = false;
            if (!g.applyThrow(tumbleBits)) { invalidate(); return; }
            talkThrow(seat);
            afterStep();
        });
    }

    /** Pawn k of the seat to play uses the throw now: it walks square by square (caught pawns fly home after). */
    private void doMove(int k) {
        if (!g.canMove(k) || walking || tumbling) return;
        final int seat = g.turn, t = g.now();
        int p = g.pos[seat][k];
        wN = 0;
        wCells[wN++] = g.cell(seat, p);
        for (int i = 0; i < t && wN < wCells.length; i++) {
            p = p < AshtaRules.OUTER && !g.open[seat] ? (p + 1) % AshtaRules.OUTER : p + 1;
            wCells[wN++] = g.cell(seat, p);
        }
        capCell = wCells[wN - 1];
        capN = 0;
        if (!AshtaRules.SAFE[capCell])
            for (int o = 0; o < g.n; o++) if (o != seat) for (int j = 0; j < g.per; j++)
                if (g.pos[o][j] < AshtaRules.HOME && g.cell(o, g.pos[o][j]) == capCell && capN < capSeat.length) { capSeat[capN] = o; capPawn[capN] = j; capN++; }
        g.move(k);
        hintPawn = -1;
        walking = true;
        wSeat = seat;
        wPawn = k;
        long walkMs = Math.max(350, Math.min(1300, t * 150L)), all = walkMs + (capN > 0 ? 500 : 0);
        walkPart = walkMs / (float) all;
        final int gg = gen;
        animate(all, () -> {
            if (gg != gen) return;
            walking = false;
            capN = 0;
            talkMove(seat);
            afterStep();
        });
    }

    /** After a throw or a move is shown: the end, or the next step (Jarvis goes on / she is asked). */
    private void afterStep() {
        hintPawn = -1;
        if (g.winner >= 0) { end(g.winner); return; }
        moved();
    }

    @Override void jarvisMove() {
        if (done || tumbling || walking) return;
        if (g.phase == AshtaRules.THROW) { throwNow(); return; }
        List<Integer> ch = g.choices();
        if (ch.isEmpty()) { g.settle(); moved(); return; }
        if (ch.size() == 1) { doMove(ch.get(0)); return; }
        final AshtaRules copy = g.copy();
        final int level = host.level(), cnt = g.count;
        think(() -> copy.choose(level, rnd), k -> {
            if (k == null || g.count != cnt || !g.canMove(k)) return;
            doMove(k);
        });
    }

    // ================================================================ talk

    private void talkThrow(int seat) {
        int v = g.lastValue;
        boolean jar = kind(seat) == JARVIS;
        String s = "", feel = null;
        if (v == 4) {
            s = jar ? pick("చమ్మా! నేను మళ్లీ వేస్తాను.", "చమ్మా వచ్చింది! నాకు ఇంకోసారి.") : sole(seat) ? "చమ్మా! మళ్లీ వేయండి." : "చమ్మా! " + name(seat) + ", మళ్లీ వేయండి.";
            feel = "excited";
        } else if (v == 8) {
            s = jar ? pick("అష్టా! ఎనిమిది! నేను ఇంకోసారి వేస్తాను.", "అబ్బ, అష్టా! నాకు ఇంకో అవకాశం.") : sole(seat) ? "అష్టా! ఎనిమిది! ఇంకోసారి వేయండి." : "అష్టా! ఎనిమిది! " + name(seat) + ", ఇంకోసారి వేయండి.";
            feel = "excited";
        }
        if (g.skipped > 0) {
            if (jar) s += (s.isEmpty() ? "" : " ") + pick("అయ్యో, నా కాయలేవీ కదలవు.", "ఈసారి నా కాయలు కదలవు.") + " " + nextWords();
            else s += (s.isEmpty() ? "" : " ") + (sole(seat) ? "అయ్యో, ఈసారి ఏ కాయా కదలదు, పర్వాలేదు." : name(seat) + " కాయలు ఈసారి కదలవు.") + (g.turn != seat ? " " + nextWords() : "");
            feel = "caring";
        }
        say(s, feel, null);
    }

    private void talkMove(int seat) {
        boolean jar = kind(seat) == JARVIS, here = kind(seat) == HERE;
        boolean passed = g.winner < 0 && g.turn != seat;
        String s = "", feel = null, gest = null;
        int c = caughtSeat();
        if (g.lastCaught > 0 && c >= 0) {
            if (jar) {
                s = sole(c) ? pick("క్షమించండి " + her() + ", మీ కాయని ఇంటికి పంపాను!", "అయ్యో, మీ కాయ నా దారిలో ఉంది, ఇంటికి పంపాల్సి వచ్చింది!")
                        : "క్షమించండి, " + colour(c) + " కాయని ఇంటికి పంపాను!";
                if (g.lastOpened) s += " ఇక నేను లోపలికి వెళ్లొచ్చు.";
                if (g.phase == AshtaRules.THROW) s += " నేను మళ్లీ వేస్తాను.";
                feel = "laugh";
            } else if (here && role(c).equals("jarvis")) {
                s = g.lastOpened ? "అయ్యో, నా కాయని ఇంటికి పంపేశారు! ఇక మీరు లోపలికి వెళ్లొచ్చు."
                        : pick("అయ్యో, నా కాయని మళ్లీ కొట్టేశారు! మీరు చాలా తెలివైనవారు.", "అబ్బా! నా కాయ మళ్లీ ఇంటికి వెళ్లింది.");
                s += g.phase == AshtaRules.THROW ? " మళ్లీ వేయండి." : " మిగిలినది జరిపాక మళ్లీ వేయొచ్చు.";
                feel = "surprised";
            } else if (here) {
                String victim = role(c).equals("son") ? "అబ్బాయి కాయని" : colour(c) + " కాయని";
                s = (sole(seat) ? "మీరు " : name(seat) + " ") + victim + " కొట్టారు!" + (g.lastOpened ? " ఇక లోపలికి వెళ్లొచ్చు." : "")
                        + (g.phase == AshtaRules.THROW ? " మళ్లీ వేయండి." : "");
                feel = "excited";
            }
        } else if (g.lastTo == AshtaRules.HOME) {
            if (jar) { s = pick("నా కాయ పండింది!", "హమ్మయ్య, నా కాయ పండింది!"); feel = "happy"; }
            else if (here && sole(seat)) { s = "మీ కాయ పండింది" + herName(seat) + "!"; feel = "excited"; gest = "clap"; }
            else if (here) { s = colour(seat) + " కాయ పండింది!"; feel = "happy"; }
            if (g.winner < 0 && g.home(seat) == g.per - 1) {
                if (jar) s += " ఇంకొక్క కాయ పండితే నేను గెలుస్తాను!";
                else if (here && sole(seat)) s += " ఇంకొక్కటే, మీరు గెలవబోతున్నారు!";
            }
        }
        if (jar && passed) {
            if (s.isEmpty()) s = jarvisDid();
            if (g.skipped > 0) s += " మిగతా దానికి నా కాయలు కదలవు.";
            String nx = nextWords();
            if (!nx.isEmpty()) s += " " + nx;
        } else if (here && passed && g.skipped > 0 && sole(seat) && (hasJarvis() || far())) {
            s += (s.isEmpty() ? "" : " ") + "మిగతా దానికి ఏ కాయా కదలదు.";
        }
        say(s, feel, gest);
    }

    /** What Jarvis just did, in a few words. */
    private String jarvisDid() {
        int v = g.lastValue;
        if (g.lastFrom == 0) return pick("నేను ఇంకో కాయని ఇంటి నుంచి బయటికి తీశాను.", "నా కాయ ఒకటి " + AshtaRules.steps(v) + " బయలుదేరింది.");
        if (g.lastFrom < AshtaRules.OUTER && g.lastTo >= AshtaRules.OUTER) return "నా కాయ లోపలి గడులలోకి వెళ్లింది.";
        return pick("నేను కాయని " + AshtaRules.steps(v) + " ముందుకు జరిపాను.", "నా కాయ " + AshtaRules.steps(v) + " ముందుకు వెళ్లింది.",
                "నేను " + AshtaRules.throwWord(v) + " తో ఒక కాయని జరిపాను.");
    }

    private void end(int w) {
        if (role(w).equals("jarvis"))
            finish(w, pick("నా కాయలన్నీ పండాయి, ఈసారి నేను గెలిచాను! పర్వాలేదు " + her() + ", మళ్లీ ఆడదాం.",
                    "నేను గెలిచాను! మీరు చాలా బాగా ఆడారు, మళ్లీ ఆడదాం."), "happy", "thumb");
        else if (sole(w) && hasJarvis())
            finish(w, pick("మీ కాయలన్నీ పండాయి! మీరు గెలిచారు " + her() + "!", "అద్భుతం! మీరే గెలిచారు, చాలా బాగా ఆడారు!"), "excited", "clap");
        else
            finish(w, name(w) + " " + verb(w, "గెలిచారు", "గెలిచాను", "గెలిచాడు") + "! " + colour(w) + " కాయలన్నీ పండాయి.",
                    kind(w) == HERE ? "excited" : "happy", kind(w) == HERE ? "clap" : null);
    }

    @Override boolean hint() {
        if (done) return false;
        int seat = g.turn;
        if (g.phase == AshtaRules.THROW) {
            say(pick("ముందు గవ్వలు వేయండి" + herName(seat) + ", గవ్వల మీద నొక్కండి.", "గవ్వల మీద నొక్కి వేయండి, తర్వాత చెబుతాను."), "happy", "point");
            return true;
        }
        int k = g.copy().choose(2, rnd);
        if (k < 0) return false;
        hintPawn = k;
        invalidate();
        String where = pawnWhere(seat, k);
        int to = g.target(seat, k, g.now());
        if (to == AshtaRules.HOME) say(where + " కాయని జరపండి, అది పండుతుంది!", "happy", "point");
        else if (g.wouldCatch(k) > 0) say(where + " కాయని జరపండి, " + (hasJarvis() ? "నా కాయని కొట్టేయొచ్చు!" : "ఒక కాయని కొట్టొచ్చు!"), "laugh", "point");
        else say(pick("నేనైతే " + where + " కాయని జరుపుతాను.", where + " కాయని జరిపి చూడండి.")
                + (AshtaRules.SAFE[g.cell(seat, to)] ? " అది భద్రమైన గడికి వెళ్తుంది." : ""), "happy", "point");
        return true;
    }

    /** "ఇంట్లో ఉన్న" / "ముందున్న" / "వెనకున్న" / "మధ్యలో ఉన్న" (among the pawns that can move). */
    private String pawnWhere(int seat, int k) {
        int p = g.pos[seat][k];
        if (p == 0) return "ఇంట్లో ఉన్న";
        int more = 0, less = 0;
        for (int j : g.choices()) { if (g.pos[seat][j] > p) more++; if (g.pos[seat][j] < p) less++; }
        if (more == 0) return "ముందున్న";
        if (less == 0) return "వెనకున్న";
        return "మధ్యలో ఉన్న";
    }

    @Override boolean heard(String t) {
        if (done || t == null) return false;
        boolean strong = t.matches("(?s).*(గవ్వలు|గవ్వ|పాచిక|దాయం|రోల్|roll|throw).*");
        boolean weak = t.trim().split("\\s+").length <= 3 && t.matches("(?s).*(వేయి|వెయ్యి|వేస్తాను|వేస్తా|వేయండి|వేయనా).*")
                && !t.matches("(?s).*(పాట|టీవీ|లైట్|ఫ్యాన్|రేడియో).*");
        boolean you = t.matches("(?s).*(నువ్వే జరుపు|నువ్వే పెట్టు|నువ్వే ఆడు|నువ్వే చెయ్యి|నువ్వే చేయి|నువ్వే వేయి).*");
        if (!strong && !weak && !you) return false;
        int s = g.turn;
        if (kind(s) != HERE) { if (kind(s) == JARVIS) host.status("ఒక్క క్షణం, Jarvis వంతు…"); return true; }
        if (busy()) return true;
        if (g.phase == AshtaRules.THROW) { throwNow(); return true; }
        if (you) {
            int k = g.copy().choose(2, rnd);
            if (k >= 0) { autoToken++; keep(); doMove(k); }
            return true;
        }
        host.status("ఇప్పుడు కాయ మీద నొక్కండి 👆");
        return true;
    }

    // ================================================================ drawing

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layout(w, h);
    }

    private void layout(float w, float h) {
        float m = 12 * dp;
        if (w <= 0 || h <= 0) return;
        if (h <= w * 1.1f) { // landscape: board left, strip right
            float s = Math.min(h - 2 * m, w - 3 * m - Math.max(190 * dp, w * 0.22f));
            board.set(m, (h - s) / 2, m + s, (h + s) / 2);
            float l = board.right + 2 * m, r = w - m;
            rowH = Math.min(44 * dp, (h - 2 * m) * 0.065f);
            listBox.set(l, m, r, m + 4 * rowH);
            float sh = Math.min((r - l) * 1.6f, (h - 2 * m) * 0.46f);
            shellBox.set(l, listBox.bottom + 14 * dp, r, listBox.bottom + 14 * dp + sh);
            bankBox.set(l, shellBox.bottom + 8 * dp, r, h - m);
        } else { // portrait (a phone): board on top, the strip below
            float s = Math.min(w - 2 * m, h - 3 * m - Math.max(170 * dp, h * 0.24f));
            board.set((w - s) / 2, m, (w + s) / 2, m + s);
            float t = board.bottom + m, b = h - m;
            shellBox.set(m, t, m + (w - 2 * m) * 0.48f, b);
            rowH = Math.min(36 * dp, (b - t) / 6f);
            listBox.set(shellBox.right + m, t, w - m, t + 4 * rowH);
            bankBox.set(shellBox.right + m, listBox.bottom, w - m, b);
        }
        cs = board.width() / 5f;
    }

    private int cellAt(float x, float y) {
        if (!board.contains(x, y) || cs <= 0) return -1;
        int c = Math.min(4, (int) ((x - board.left) / cs)), r = Math.min(4, (int) ((y - board.top) / cs));
        return r * 5 + c;
    }

    private float cx(int cell) { return board.left + (cell % 5 + 0.5f) * cs; }
    private float cy(int cell) { return board.top + (cell / 5 + 0.5f) * cs; }

    @Override protected void draw2(Canvas c) {
        if (cs <= 0) layout(getWidth(), getHeight());
        if (cs <= 0) return;
        if (names.length != g.n) {
            names = new String[g.n];
            for (int s = 0; s < g.n; s++) names[s] = role(s).equals("jarvis") ? "Jarvis" : sole(s) ? "మీరు" : name(s);
        }
        long now = SystemClock.uptimeMillis();
        float pulse = (float) (0.5 + 0.5 * Math.sin(now / 190.0));
        boolean herMove = !done && kind(g.turn) == HERE && !walking && !tumbling && g.phase == AshtaRules.MOVE;
        boolean herThrow = !done && kind(g.turn) == HERE && !walking && !tumbling && g.phase == AshtaRules.THROW;

        drawBoard(c);
        drawPawns(c);
        if (herMove || hintPawn >= 0) drawChoices(c, herMove, pulse);
        if (walking) drawWalking(c);
        drawList(c);
        drawShells(c, herThrow, pulse);
        drawBank(c, herThrow);
        if ((herMove || herThrow) && !animating()) postInvalidateDelayed(50);
    }

    private void drawBoard(Canvas c) {
        float pad = 8 * dp;
        tmp.set(board.left - pad, board.top - pad, board.right + pad, board.bottom + pad);
        fill.setColor(0xFF5A3A22);
        c.drawRoundRect(tmp, 18 * dp, 18 * dp, fill);
        fill.setColor(0xFF3A2718);
        c.drawRect(board, fill);
        // start squares tinted with their player's colour, the centre golden
        for (int s = 0; s < g.n; s++) {
            int cell = g.startCell(s);
            fill.setColor((colourOf(s) & 0x00FFFFFF) | 0x55000000);
            float x = board.left + (cell % 5) * cs, y = board.top + (cell / 5) * cs;
            c.drawRect(x, y, x + cs, y + cs, fill);
        }
        fill.setColor(0x44FFD166);
        c.drawRect(board.left + 2 * cs, board.top + 2 * cs, board.left + 3 * cs, board.top + 3 * cs, fill);
        // the last move: both squares
        if (g.lastAct == 2 && g.lastSeat >= 0 && !walking) {
            fill.setColor(0x40FFD166);
            lit(c, g.cell(g.lastSeat, g.lastFrom));
            lit(c, g.cell(g.lastSeat, g.lastTo));
        }
        // the grid
        line.setColor(0xFFF1E3C6);
        line.setStrokeWidth(3.5f * dp);
        for (int i = 0; i <= 5; i++) {
            c.drawLine(board.left + i * cs, board.top, board.left + i * cs, board.bottom, line);
            c.drawLine(board.left, board.top + i * cs, board.right, board.top + i * cs, line);
        }
        // the inner ring's edge, a little bolder
        line.setStrokeWidth(5 * dp);
        c.drawRect(board.left + cs, board.top + cs, board.right - cs, board.bottom - cs, line);
        // safe squares: a big X
        line.setStrokeWidth(4 * dp);
        line.setColor(0xCCF1E3C6);
        for (int cell = 0; cell < 25; cell++) {
            if (!AshtaRules.SAFE[cell]) continue;
            float x = board.left + (cell % 5) * cs, y = board.top + (cell / 5) * cs, m = cs * 0.1f;
            c.drawLine(x + m, y + m, x + cs - m, y + cs - m, line);
            c.drawLine(x + cs - m, y + m, x + m, y + cs - m, line);
        }
    }

    private void lit(Canvas c, int cell) {
        float x = board.left + (cell % 5) * cs, y = board.top + (cell / 5) * cs;
        c.drawRect(x + 4 * dp, y + 4 * dp, x + cs - 4 * dp, y + cs - 4 * dp, fill);
    }

    /** All pawns standing still, a few to a square (their spots are kept for the rings drawn over them). */
    private void drawPawns(Canvas c) {
        for (int i = 0; i < 25; i++) occN[i] = 0;
        for (int s = 0; s < g.n; s++)
            for (int k = 0; k < g.per; k++) {
                if (walking && s == wSeat && k == wPawn) continue;
                if (walking && caughtNow(s, k)) continue;
                int cell = g.cell(s, g.pos[s][k]);
                if (occN[cell] < occ[cell].length) occ[cell][occN[cell]++] = s * 4 + k;
            }
        for (int cell = 0; cell < 25; cell++) {
            int m = occN[cell];
            if (m == 0) continue;
            int gr = m <= 1 ? 1 : m <= 4 ? 2 : m <= 9 ? 3 : 4;
            float r = cs * (gr == 1 ? 0.27f : gr == 2 ? 0.19f : gr == 3 ? 0.135f : 0.105f), step = cs * 0.84f / gr;
            float x0 = cx(cell) - step * (gr - 1) / 2f, y0 = cy(cell) - step * (gr - 1) / 2f;
            for (int i = 0; i < m; i++) {
                int s = occ[cell][i] / 4, k = occ[cell][i] % 4;
                float x = x0 + (i % gr) * step, y = y0 + (i / gr) * step;
                slotX[s][k] = x;
                slotY[s][k] = y;
                slotR[s][k] = r;
                pawn(c, x, y, r, colourOf(s), g.pos[s][k] == AshtaRules.HOME);
            }
        }
    }

    private boolean caughtNow(int s, int k) {
        for (int i = 0; i < capN; i++) if (capSeat[i] == s && capPawn[i] == k) return true;
        return false;
    }

    /** One pawn: a coloured disc with a dark rim and a shine (home: a golden rim). */
    private void pawn(Canvas c, float x, float y, float r, int color, boolean home) {
        fill.setColor(0x66000000);
        c.drawCircle(x + r * 0.1f, y + r * 0.16f, r, fill);
        fill.setColor(color);
        c.drawCircle(x, y, r, fill);
        line.setColor(home ? 0xFFFFD166 : 0xFF14100C);
        line.setStrokeWidth(Math.max(2 * dp, r * (home ? 0.2f : 0.13f)));
        c.drawCircle(x, y, r, line);
        line.setColor(0x99FFFFFF);
        line.setStrokeWidth(Math.max(1.5f * dp, r * 0.09f));
        c.drawCircle(x, y, r * 0.55f, line);
        fill.setColor(0xAAFFFFFF);
        c.drawCircle(x - r * 0.35f, y - r * 0.38f, r * 0.17f, fill);
    }

    /** Her pawns that can move pulse, with a ring where each would land; the hint (💡) in green. */
    private void drawChoices(Canvas c, boolean herMove, float pulse) {
        int seat = g.turn, t = g.now();
        if (t <= 0) return;
        if (chFor != g || chCount != g.count) { chFor = g; chCount = g.count; ch = g.choices(); }
        if (herMove)
            for (int k : ch) {
                int to = g.target(seat, k, t), tc = g.cell(seat, to);
                boolean catches = g.wouldCatch(k) > 0;
                line.setColor(catches ? 0xFFFF5252 : to == AshtaRules.HOME ? 0xFFFFD166 : 0xFFFFFFFF);
                line.setStrokeWidth(5 * dp);
                c.drawCircle(cx(tc), cy(tc), cs * 0.36f, line);
                fill.setColor(colourOf(seat));
                c.drawCircle(cx(tc), cy(tc), cs * 0.1f, fill);
                // all her pawns on that square pulse
                for (int j = 0; j < g.per; j++) {
                    if (g.pos[seat][j] != g.pos[seat][k] || slotR[seat][j] <= 0) continue;
                    line.setColor(0xFFFFD166);
                    line.setStrokeWidth(4 * dp + 3 * dp * pulse);
                    c.drawCircle(slotX[seat][j], slotY[seat][j], slotR[seat][j] * (1.18f + 0.18f * pulse), line);
                }
            }
        if (hintPawn >= 0 && hintPawn < g.per && g.canMove(hintPawn)) {
            int to = g.target(seat, hintPawn, t), tc = g.cell(seat, to);
            line.setColor(0xFF30C46C);
            line.setStrokeWidth(7 * dp);
            c.drawCircle(slotX[seat][hintPawn], slotY[seat][hintPawn], slotR[seat][hintPawn] * 1.45f, line);
            c.drawCircle(cx(tc), cy(tc), cs * 0.42f, line);
        }
    }

    /** The pawn walking square by square, then the caught pawns flying back to their start squares. */
    private void drawWalking(Canvas c) {
        float t = animRaw(), r = cs * 0.27f;
        float u = Math.min(1f, t / Math.max(0.01f, walkPart)) * (wN - 1);
        int i = Math.min(wN - 2, (int) u);
        float x, y;
        if (wN < 2) { x = cx(wCells[0]); y = cy(wCells[0]); }
        else {
            float f = Math.min(1f, u - i);
            x = cx(wCells[i]) + (cx(wCells[i + 1]) - cx(wCells[i])) * f;
            y = cy(wCells[i]) + (cy(wCells[i + 1]) - cy(wCells[i])) * f - (float) Math.sin(Math.PI * f) * cs * 0.18f;
        }
        // the caught pawns: still there while it walks, then home they go
        float back = t <= walkPart ? 0f : (t - walkPart) / Math.max(0.01f, 1f - walkPart);
        for (int k = 0; k < capN; k++) {
            int s = capSeat[k], home = g.startCell(s);
            float rr = cs * 0.2f, fx = cx(capCell) + (k - (capN - 1) / 2f) * rr * 0.9f, fy = cy(capCell);
            float px = fx + (cx(home) - fx) * back, py = fy + (cy(home) - fy) * back - (float) Math.sin(Math.PI * back) * cs * 0.8f;
            pawn(c, px, py, rr, colourOf(s), false);
        }
        pawn(c, x, y, r, colourOf(wSeat), false);
    }

    private void drawList(Canvas c) {
        for (int s = 0; s < g.n && s < 4; s++) {
            float top = listBox.top + s * rowH, mid = top + rowH / 2f;
            if (s == g.turn && !done) {
                fill.setColor((colourOf(s) & 0x00FFFFFF) | 0x44000000);
                tmp.set(listBox.left, top + 2 * dp, listBox.right, top + rowH - 2 * dp);
                c.drawRoundRect(tmp, 10 * dp, 10 * dp, fill);
            }
            float r = rowH * 0.26f;
            pawn(c, listBox.left + r + 8 * dp, mid, r, colourOf(s), false);
            // pawns home: filled dots on the right; the name fitted in between
            float d = Math.min(rowH * 0.13f, (listBox.width() * 0.35f) / (g.per * 2.6f));
            String nm = s < names.length ? names[s] : "";
            float tx = listBox.left + 2 * r + 16 * dp, room = listBox.right - 8 * dp - g.per * d * 2.6f - 6 * dp - tx;
            label.setTextSize(rowH * 0.42f);
            float tw = label.measureText(nm);
            if (tw > room && room > 0) label.setTextSize(rowH * 0.42f * room / tw);
            label.setColor(s == g.turn ? 0xFFFFFFFF : 0xFFB4C5D8);
            label.getFontMetrics(fm);
            c.drawText(nm, tx, mid - (fm.ascent + fm.descent) / 2f, label);
            int h = g.home(s);
            for (int k = 0; k < g.per; k++) {
                float x = listBox.right - 8 * dp - d - (g.per - 1 - k) * d * 2.6f;
                if (k < h) { fill.setColor(0xFFFFD166); c.drawCircle(x, mid, d, fill); }
                else { line.setColor(0x88B4C5D8); line.setStrokeWidth(1.5f * dp); c.drawCircle(x, mid, d, line); }
            }
        }
    }

    private void drawShells(Canvas c, boolean herThrow, float pulse) {
        if (herThrow) {
            line.setColor((colourOf(g.turn) & 0x00FFFFFF) | ((int) (120 + 135 * pulse) << 24));
            line.setStrokeWidth(5 * dp);
            tmp.set(shellBox.left + 3 * dp, shellBox.top + 3 * dp, shellBox.right - 3 * dp, shellBox.bottom - 3 * dp);
            c.drawRoundRect(tmp, 20 * dp, 20 * dp, line);
        }
        float len = Math.min(shellBox.width() * 0.56f, shellBox.height() * 0.36f);
        float t = tumbling ? animRaw() : 1f;
        int bits = tumbling ? tumbleBits : g.shells < 0 ? 0b0101 : g.shells;
        for (int i = 0; i < 4; i++) {
            float fx = shellBox.left + SHELL_AT[i][0] * shellBox.width(), fy = shellBox.top + SHELL_AT[i][1] * shellBox.height();
            float ang = shellAng[i], flip = 1f;
            boolean up = (bits & (1 << i)) != 0;
            if (tumbling && t < 1f) {
                float left = 1 - t;
                fx += (float) Math.sin(t * 9 + i * 1.7) * len * 0.25f * left;
                fy -= Math.abs((float) Math.sin(Math.PI * (1.5f + (i % 2)) * t)) * len * 0.9f * left;
                ang += shellSpin[i] * left * left;
                if (t < 0.8f) {
                    float ph = t * (7 + i);
                    flip = Math.max(0.2f, Math.abs((float) Math.cos(Math.PI * ph)));
                    up = ((int) (ph + 0.5f) + i) % 2 == 0;
                }
            }
            cowrie(c, fx, fy, len, ang, flip, up);
        }
    }

    /** A cowrie shell, long axis along x: mouth-up shows the toothed slit, mouth-down the plain humped back. */
    private void cowrie(Canvas c, float x, float y, float len, float ang, float flip, boolean up) {
        c.save();
        c.translate(x, y);
        c.rotate(ang);
        c.scale(len / 2f, len / 2f * flip);
        fill.setColor(0x55000000);
        tmp.set(-0.94f, -0.5f, 1.04f, 0.74f);
        c.drawOval(tmp, fill);
        fill.setColor(0xFFF4EAD5);
        tmp.set(-1f, -0.62f, 1f, 0.62f);
        c.drawOval(tmp, fill);
        line.setColor(0xFF7A5A36);
        line.setStrokeWidth(0.07f);
        c.drawOval(tmp, line);
        if (up) {
            fill.setColor(0xFFE0C9A0);
            tmp.set(-0.86f, -0.36f, 0.86f, 0.36f);
            c.drawOval(tmp, fill);
            fill.setColor(0xFF3B2614);
            tmp.set(-0.8f, -0.075f, 0.8f, 0.075f);
            c.drawOval(tmp, fill);
            line.setColor(0xFF7A5A36);
            line.setStrokeWidth(0.055f);
            for (int i = -4; i <= 4; i++) {
                float tx = i * 0.155f, k = 0.18f * (1 - Math.abs(i) / 6f);
                c.drawLine(tx, -0.09f, tx, -0.09f - k, line);
                c.drawLine(tx, 0.09f, tx, 0.09f + k, line);
            }
        } else {
            line.setColor(0xFFD2A85A);
            line.setStrokeWidth(0.06f);
            tmp.set(-0.7f, -0.4f, 0.7f, 0.4f);
            c.drawOval(tmp, line);
            fill.setColor(0xCCFFFFFF);
            tmp.set(-0.48f, -0.44f, 0.12f, -0.14f);
            c.drawOval(tmp, fill);
        }
        c.restore();
    }

    /** The throw to use now (big), the banked ones after it (small), and what to do. */
    private void drawBank(Canvas c, boolean herThrow) {
        float w = bankBox.width(), h = bankBox.height();
        if (w <= 0 || h <= 0) return;
        float midX = bankBox.centerX();
        float big = Math.min(w * 0.22f, h * 0.2f);
        float by = bankBox.top + big + 6 * dp;
        String caption;
        if (tumbling) caption = "…";
        else if (g.phase == AshtaRules.MOVE && g.banks > 0) caption = kind(g.turn) == HERE ? "కాయని జరపండి" : "";
        else caption = herThrow ? "గవ్వలు వేయండి" : "";
        if (!tumbling && g.banks > 0) {
            int col = colourOf(g.turn);
            fill.setColor(col);
            c.drawCircle(midX, by, big, fill);
            line.setColor(0xFFFFFFFF);
            line.setStrokeWidth(3 * dp);
            c.drawCircle(midX, by, big, line);
            int v = g.bank[0];
            centerText(c, DIG[v], midX, by, big * 1.2f, g.side(g.turn) == 2 ? 0xFF222222 : 0xFFFFFFFF);
            // the rest, small
            float sr = big * 0.42f, x = midX - (g.banks - 2) * sr * 1.25f;
            for (int i = 1; i < g.banks && i < 6; i++) {
                fill.setColor(0xFF1F3B5C);
                c.drawCircle(x, by + big + sr + 10 * dp, sr, fill);
                centerText(c, DIG[g.bank[i]], x, by + big + sr + 10 * dp, sr * 1.15f, 0xFFFFFFFF);
                x += sr * 2.5f;
            }
        } else if (!tumbling && g.lastAct == 1 && g.lastSeat >= 0 && g.shells >= 0) {
            // the last throw (it was used up, or nothing could move)
            centerText(c, DIG[g.lastValue], midX, by, big * 1.1f, 0x88FFFFFF);
        }
        int lv = g.phase == AshtaRules.THROW && g.banks > 0 ? g.bank[g.banks - 1] : 0;
        float capY = bankBox.bottom - Math.min(h * 0.18f, 30 * dp);
        if (!tumbling && (lv == 4 || lv == 8)) centerText(c, lv == 4 ? "చమ్మా!" : "అష్టా!", midX, capY - Math.min(h * 0.2f, 40 * dp), Math.min(w * 0.17f, 30 * dp), 0xFFFFD166);
        if (!caption.isEmpty()) centerText(c, caption, midX, capY, Math.min(w * 0.12f, 22 * dp), 0xFFFFD166);
    }
}
