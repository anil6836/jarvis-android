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
import java.util.List;

/**
 * లూడో on the screen (the rules are in LudoRules). A big die beside the board: a tap (on the die or anywhere) or her
 * words ("పాచిక వేయి") roll it; then the tokens that can move pulse and a tap on one makes it hop square by square.
 * When only one move is possible it goes by itself; when none is, the turn passes. Jarvis rolls the same fair die,
 * chooses his token by his level and talks like a person across the board ("నాకు నాలుగు పడింది. నా కాయని ముందుకు
 * జరిపాను. ఇప్పుడు మీరు పాచిక వేయండి."). With several people at the tablet he calls them by colour ("పచ్చ వారి వంతు").
 */
final class LudoGame extends Game {
    private final LudoRules g = new LudoRules();
    private static final int YARD = LudoRules.YARD, LANE = LudoRules.LANE, HOME = LudoRules.HOME;
    /** Colour words before "కాయ" (ఎర్ర కాయ…); COLOR_NAMES are for people ("పచ్చ వారు"). */
    private static final String[] ADJ = {"ఎర్ర", "పచ్చ", "పసుపు", "నీలి"};
    /** The top-left cell of each corner's yard (red bottom-left, green top-left, yellow top-right, blue bottom-right). */
    private static final int[][] YARD_AT = {{0, 9}, {0, 0}, {9, 0}, {9, 9}};

    /** 0 still, 1 the die rolling, 2 a token hopping, 3 a sent-home token going back to its yard. */
    private int mode;
    private final int[] faces = new int[9];
    // the hopping token and its path (view pixels)
    private final float[] hopX = new float[64], hopY = new float[64];
    private int hopN, mvSeat = -1, mvTok = -1;
    // tokens sent home by the move: their seat, which ones (bits), where they were hit
    private int capSeat = -1, capMask;
    private float capX, capY;
    private int hintSeat = -1, hintTok = -1;
    /** Jarvis's few words about her last move, said with his first line of the turn. */
    private String react = "";
    private int noneTold, autoTold;

    // ---- layout and drawing things made once
    private final RectF board = new RectF(), strip = new RectF(), rf = new RectF();
    private float u, dieX, dieY, dieS, listX, listY, listW, rowH;
    private int listCols = 1, lw = -1, lh = -1, bmpKey;
    private Bitmap bmp;
    private boolean noBmp;
    private final float[][] tokX = new float[4][4], tokY = new float[4][4];
    private final int[] cnt = new int[225], seen = new int[225];
    private final float[] pt = new float[2];
    private final Path pawn = new Path(), star = new Path(), tri = new Path();
    private final Paint left = new Paint(Paint.ANTI_ALIAS_FLAG);

    LudoGame(Context c) {
        super(c);
        left.setTypeface(Typeface.DEFAULT_BOLD);
        left.setTextAlign(Paint.Align.LEFT);
        makePawn(pawn);
        makeStar(star);
    }

    @Override String id() { return "ludo"; }
    @Override String title() { return "లూడో"; }
    @Override int[] players() { return new int[]{2, 4}; }
    @Override String[] options() { return new String[]{"🎲 పూర్తి ఆట (4 కాయలు)", "⚡ చిన్న ఆట (2 కాయలు)"}; }

    @Override void newGame(String[] who, int option) {
        roles = who.clone();
        g.setup(who.length, option == 1 ? 2 : 4);
        mode = 0;
        clearMarks();
        noneTold = autoTold = 0;
        start();
        if (host == null || far()) return;
        String rule = "ఆరు పడితేనే కాయ బయటికి వస్తుంది.";
        if (hasJarvis() && sole(0)) {
            int j = jarvisSeat();
            say(pick("రండి " + host.her() + ", లూడో ఆడదాం! మీవి ఎర్ర కాయలు, నావి " + ADJ[g.col[j]] + " కాయలు. " + rule + " మీరే ముందు, పాచిక మీద నొక్కండి.",
                    "సరే, లూడో! మీరు ఎరుపు, నేను " + COLOR_NAMES[g.col[j]] + ". " + rule + " పాచిక నొక్కండి " + host.her() + "."), "happy", "point");
        } else {
            say("లూడో మొదలు! " + rule + " ఎరుపు వారు ముందు, పాచిక నొక్కండి.", "happy", "point");
        }
    }

    @Override String save() { return g.save(); }

    @Override boolean load(String s) {
        mode = 0;
        clearMarks();
        return g.load(s);
    }

    @Override int turn() { return g.turn; }

    @Override String yourTurn(int seat) {
        String w = sole(seat) ? "మీ వంతు" : COLOR_NAMES[g.col[seat]] + " వారి వంతు";
        return g.rolled ? w + ": మెరుస్తున్న కాయని నొక్కండి 👇" : w + ": పాచిక నొక్కండి 🎲";
    }

    /** Also: a person here who had rolled with only one move (a saved game opened again) moves by itself. */
    @Override protected void turnNow() {
        super.turnNow();
        if (done || host == null || mode != 0) return;
        if (kind(g.turn) == HERE && g.rolled && g.choices() == 1) autoMove(g.turn);
    }

    private void clearMarks() {
        mvSeat = mvTok = capSeat = hintSeat = hintTok = -1;
        capMask = 0;
        hopN = 0;
    }

    private int jarvisSeat() {
        for (int i = 0; i < roles.length; i++) if ("jarvis".equals(roles[i])) return i;
        return -1;
    }

    // ================================================================ rolling and moving

    @Override void tap(float x, float y) {
        if (mode != 0) return;
        int seat = g.turn;
        if (!g.rolled) { roll(); return; } // (any tap rolls: there is nothing else to do before the roll)
        int t = tokenAt(seat, x, y);
        if (t < 0) { host.status(yourTurn(seat)); return; }
        keep();
        go(t);
    }

    /** The seat to play rolls (a person here by tap / words, Jarvis by himself). */
    private void roll() {
        if (mode != 0 || done || g.rolled) return;
        final int seat = g.turn, d = 1 + rnd.nextInt(6);
        hintSeat = hintTok = -1;
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

    private void rolled(int seat, int d) {
        int r = g.roll(d);
        invalidate();
        int k = kind(seat);
        if (r == LudoRules.SIXES) {
            if (k == JARVIS) say(takeReact() + pick("అయ్యో, మూడు సార్లు ఆరు! నా వంతు పోయింది. ", "మూడో ఆరు పడింది, నియమం ప్రకారం నా వంతు పోయింది. ") + nextWords(), "laugh", null);
            else say(subj(seat) + "మూడు సార్లు ఆరు! నియమం ప్రకారం ఈ వంతు పోయింది. " + nextWords(), "surprised", null);
            moved();
            return;
        }
        if (r == LudoRules.NONE) {
            boolean waiting = allWaiting(seat);
            if (k == JARVIS) {
                say(takeReact() + (waiting ? pick("నాకు " + num(d) + " పడింది, ఆరు రాలేదు. ", num(d) + " పడింది, నా కాయలు ఇంకా బయటికి రాలేదు. ")
                        : "నాకు " + num(d) + " పడింది, నా కాయలేవీ కదలవు. ") + nextWords(), null, null);
            } else {
                String why = waiting ? (noneTold++ < 2 ? " ఆరు పడితేనే కాయ బయటికి వస్తుంది." : "") : " ఇంటికి సరిగ్గా అంకె పడాలి.";
                say(subj(seat) + num(d) + " పడింది. ఈసారి ఏమీ కదలదు." + why + " " + nextWords(), null, null);
            }
            moved();
            return;
        }
        if (k == JARVIS) { jarvisChoose(); return; }
        if (k != HERE) return;
        if (g.choices() == 1) autoMove(seat);
        else host.status(yourTurn(seat));
    }

    /** Every token of this seat that isn't home is still in the yard (only a six helps). */
    private boolean allWaiting(int seat) {
        for (int t = 0; t < g.k; t++) if (g.pos[seat][t] != YARD && g.pos[seat][t] != HOME) return false;
        return true;
    }

    /** Only one move for a person here: it is made after a moment (said the first time). */
    private void autoMove(final int seat) {
        List<Integer> m = g.movable();
        if (m.isEmpty()) return;
        final int t = m.get(0);
        host.status((sole(seat) ? "" : COLOR_NAMES[g.col[seat]] + ": ") + "ఒకే దారి, కాయ తానే కదులుతుంది…");
        if (autoTold++ == 0 && hasJarvis()) say("ఒకే కాయ కదలగలదు, అది తానే వెళ్తుంది.", null, null);
        later(800, () -> { if (mode == 0 && !done && g.rolled && g.turn == seat && kind(seat) == HERE && g.legal(t)) go(t); });
    }

    @Override void jarvisMove() {
        if (mode != 0 || done) return;
        if (g.lastSeat != g.turn) react = reaction(); // (the first roll of his turn)
        if (g.rolled) { jarvisChoose(); return; }
        host.status("Jarvis పాచిక వేస్తున్నాడు… 🎲");
        roll();
    }

    private void jarvisChoose() {
        if (g.choices() == 1) {
            final int t = g.movable().get(0);
            later(450, () -> { if (mode == 0 && !done && g.legal(t) && kind(g.turn) == JARVIS) go(t); });
            return;
        }
        final int level = host.level();
        final LudoRules copy = g.copy();
        think(() -> copy.choose(level, rnd), t -> {
            int k = t == null ? -1 : t;
            if (!g.legal(k)) { List<Integer> m = g.movable(); if (m.isEmpty()) return; k = m.get(0); }
            go(k);
        });
    }

    /** Token t of the seat to play moves (hops along), then the words and the next turn. */
    private void go(final int t) {
        if (mode != 0 || !g.legal(t)) return;
        final int seat = g.turn, from = g.pos[seat][t], d = g.die;
        layout();
        path(seat, t, from, g.target(seat, t, d));
        final int threat = from >= 0 && from < LANE ? g.threatBy(seat, g.abs(seat, from)) : -1;
        final int res = g.move(t);
        final boolean escaped = threat >= 0 && (g.lastTo >= LANE || g.danger(seat, g.abs(seat, g.lastTo)) == 0);
        final int victim = g.lastCapSeat;
        hintSeat = hintTok = -1;
        mvSeat = seat;
        mvTok = t;
        capSeat = (res & LudoRules.CAPTURE) != 0 ? victim : -1;
        capMask = g.lastCapMask;
        capX = hopX[hopN - 1];
        capY = hopY[hopN - 1];
        mode = 2;
        final int gg = gen;
        animate(from == YARD ? 380 : 110L * (hopN - 1), () -> {
            if (gg != gen) return;
            if (capSeat >= 0) {
                mode = 3;
                animate(480, () -> { if (gg == gen) landed(seat, threat, escaped, victim, from, d, res); });
            } else landed(seat, threat, escaped, victim, from, d, res);
        });
    }

    private void landed(int seat, int threat, boolean escaped, int victim, int from, int d, int res) {
        mode = 0;
        mvSeat = mvTok = capSeat = -1;
        capMask = 0;
        invalidate();
        if ((res & LudoRules.WON) != 0) { won(seat); return; }
        talk(seat, threat, escaped, victim, from, d, res);
        moved();
    }

    /** The path of a move in view pixels: where the token is drawn now, then each square to its end. */
    private void path(int seat, int t, int from, int to) {
        hopN = 0;
        hopX[0] = tokX[seat][t];
        hopY[0] = tokY[seat][t];
        if (hopX[0] == 0 && hopY[0] == 0) { spotAt(g.col[seat], t, from, pt); hopX[0] = pt[0]; hopY[0] = pt[1]; }
        hopN = 1;
        for (int p = from == YARD ? 0 : from + 1; p <= to && hopN < hopX.length; p++) {
            spotAt(g.col[seat], t, p, pt);
            hopX[hopN] = pt[0];
            hopY[hopN] = pt[1];
            hopN++;
        }
        if (hopN < 2) { hopX[1] = hopX[0]; hopY[1] = hopY[0]; hopN = 2; }
    }

    /** A far player's move arrived: shown hopping (the state is there already; the base said the words). */
    @Override void farMoved(String all) {
        int before = g.moves;
        super.farMoved(all);
        if (g.moves != before + 1 || g.lastKind != 1 || g.lastSeat < 0 || g.lastTok < 0 || getWidth() == 0 || mode != 0) return;
        layout();
        path(g.lastSeat, g.lastTok, g.lastFrom, g.lastTo);
        mvSeat = g.lastSeat;
        mvTok = g.lastTok;
        mode = 2;
        final int gg = gen;
        animate(g.lastFrom == YARD ? 380 : 110L * (hopN - 1), () -> { if (gg != gen) return; mode = 0; mvSeat = mvTok = -1; invalidate(); });
    }

    // ================================================================ words

    /** "నేను" / "మీరు" / "పచ్చ వారు" (several people here) / a name, with a space; "" for the only person here. */
    private String subj(int seat) {
        if (kind(seat) == JARVIS) return "నాకు ";
        if (sole(seat)) return "";
        if (kind(seat) == HERE) return COLOR_NAMES[g.col[seat]] + " వారికి ";
        return dat(seat) + " ";
    }

    /** To the seat: "నాకు", "మీకు", "పచ్చ వారికి", "అబ్బాయికి", "అమ్మగారికి". */
    private String dat(int seat) {
        if (kind(seat) == JARVIS) return "నాకు";
        if (sole(seat)) return "మీకు";
        if (kind(seat) == HERE) return COLOR_NAMES[g.col[seat]] + " వారికి";
        String n = name(seat);
        return n.endsWith("ు") ? n.substring(0, n.length() - 1) + "ికి" : n + "కి";
    }

    /** Whose token, before "కాయ": "నా", "మీ", "పచ్చ", "అబ్బాయి", "అమ్మగారి". */
    private String whose(int seat) {
        if (kind(seat) == JARVIS) return "నా";
        if (sole(seat)) return "మీ";
        if (kind(seat) == HERE) return ADJ[g.col[seat]];
        String n = name(seat);
        return n.endsWith("ు") ? n.substring(0, n.length() - 1) + "ి" : n;
    }

    /** Her name after a line for her (", అమ్మగారు"), only for her at the tablet. */
    private String herName(int seat) { return "her".equals(role(seat)) && !onPhone && host != null ? " " + host.her() : ""; }

    /** Whose turn it is now, as the end of a line. */
    private String nextWords() {
        int s = g.turn;
        switch (kind(s)) {
            case JARVIS: return pick("ఇప్పుడు నా వంతు.", "ఇక నేను వేస్తాను.");
            case FAR: return "ఇప్పుడు " + name(s) + " వంతు.";
            default:
                if (!sole(s)) return "ఇప్పుడు " + COLOR_NAMES[g.col[s]] + " వారి వంతు.";
                return pick("ఇప్పుడు మీరు పాచిక వేయండి.", "ఇప్పుడు మీ వంతు.", "మీరు వేయండి" + herName(s) + ".", "ఇక మీరు పాచిక వేయండి.");
        }
    }

    private String takeReact() { String r = react; react = ""; return r; }

    /** Now and then a few words about her last move, before Jarvis's own ("మీ కాయ బయటికి వచ్చింది, బాగుంది!"). */
    private String reaction() {
        int s = g.lastSeat;
        if (s < 0 || g.lastKind != 1 || kind(s) != HERE || !sole(s) || rnd.nextInt(3) != 0) return "";
        if (g.lastFrom == YARD) return pick("మీ కాయ బయటికి వచ్చింది, బాగుంది! ", "బాగుంది, మీ కాయ బయటికి వచ్చింది. ");
        if (g.lastTo >= LANE && g.lastTo < HOME) return "అబ్బో, మీ కాయ ఇంటి దారిలో ఉంది! ";
        int j = jarvisSeat();
        if (j >= 0) {
            int a = g.abs(s, g.lastTo);
            for (int t = 0; a >= 0 && t < g.k; t++) {
                int b = g.abs(j, g.pos[j][t]);
                int dd = (b - a + LudoRules.TRACK) % LudoRules.TRACK;
                if (b >= 0 && dd >= 1 && dd <= 6 && !LudoRules.safeSquare(b)) return pick("అమ్మో, మీ కాయ నా కాయ వెనకే ఉంది! ", "అబ్బా, నా కాయ వెనకే వచ్చారు! ");
            }
        }
        return pick("బాగా జరిపారు" + herName(s) + ". ", "");
    }

    /** The words after a move (Jarvis always; a person here only at special moments). */
    private void talk(int seat, int threat, boolean escaped, int victim, int from, int d, int res) {
        boolean cap = (res & LudoRules.CAPTURE) != 0, home = (res & LudoRules.HOME_IN) != 0, again = (res & LudoRules.AGAIN) != 0;
        boolean lastOne = false;
        if (g.lastOne(seat) && (g.near & (1 << seat)) == 0) { g.near |= 1 << seat; lastOne = true; }
        int k = kind(seat);
        if (k == JARVIS) {
            String rx = takeReact();
            if (escaped && rx.contains("వెనకే")) rx = ""; // (his own words say it)
            String head = rx + (d == 6 ? pick("ఆరు పడింది! ", "నాకు ఆరు! ") : "నాకు " + num(d) + " పడింది. ");
            String what, feel = null;
            if (cap) {
                feel = "laugh";
                if (sole(victim)) what = pick("మీ కాయని కొట్టేశాను, సారీ " + host.her() + "!", "అయ్యో, మీ కాయని ఇంటికి పంపేశాను! సారీ " + host.her() + ".");
                else what = whose(victim) + " కాయని కొట్టేశాను, సారీ!";
            } else if (home) {
                feel = "happy";
                what = pick("నా కాయ ఇంటికి చేరింది!", "హమ్మయ్య, నా కాయ ఇంటికి చేరింది!");
            } else if (from == YARD) {
                what = pick("కొత్త కాయని బయటికి తీశాను.", "నా ఇంకో కాయ బయటికి వచ్చింది.");
            } else if ((res & LudoRules.LANE_IN) != 0) {
                what = "నా కాయ ఇంటి దారిలోకి వెళ్లింది.";
            } else if (escaped) {
                what = (sole(threat) ? "మీ" : whose(threat)) + " కాయ నా వెనకే ఉంది, అందుకే నా కాయని తప్పించాను.";
            } else if ((res & LudoRules.SAFE) != 0) {
                what = pick("నా కాయని నక్షత్రం గడిలో పెట్టాను, అక్కడ ఎవరూ కొట్టలేరు.", "నా కాయ నక్షత్రం మీద భద్రంగా ఉంది.");
            } else {
                what = pick("నా కాయని ముందుకు జరిపాను.", "నా కాయని " + (d == 1 ? "ఒక గడి" : num(d) + " గడులు") + " ముందుకు జరిపాను.", "నా కాయ ముందుకు వెళ్లింది.");
            }
            String tail = again ? pick("ఇంకోసారి నేనే వేస్తాను.", "మళ్లీ నా వంతు!") : nextWords();
            say(head + what + (lastOne ? " ఇంకొక్క కాయే మిగిలింది నాకు!" : "") + " " + tail, feel, null);
            return;
        }
        if (k != HERE) return;
        boolean one = sole(seat);
        String by = one ? "" : COLOR_NAMES[g.col[seat]] + " వారు ";
        String more = by + "ఇంకోసారి వేయండి.";
        String s = null, feel = null, gest = null;
        if (cap) {
            if (kind(victim) == JARVIS) {
                s = pick("అయ్యో! " + by + "నా కాయని ఇంటికి పంపేశారు!", "అబ్బా! " + by + "నా కాయని కొట్టేశారు, చాలా తెలివైన ఆట!") + " " + more;
                feel = "surprised";
            } else {
                s = "భలే! " + by + whose(victim) + " కాయని ఇంటికి పంపారు! " + more;
                feel = "excited";
            }
        } else if (home) {
            s = "భలే! " + (one ? "మీ" : ADJ[g.col[seat]]) + " కాయ ఇంటికి చేరింది! " + more;
            feel = "excited";
            gest = "clap";
        } else if (again && d == 6) {
            s = "ఆరు పడింది! " + more;
            feel = "happy";
        }
        if (lastOne) s = (s == null ? "" : s + " ") + (one ? "ఇంకొక్క కాయే మిగిలింది, మీరు గెలవబోతున్నారు!" : COLOR_NAMES[g.col[seat]] + " వారికి ఇంకొక్క కాయే మిగిలింది!");
        if (s != null) say(s, feel, gest);
    }

    private void won(int seat) {
        int k = kind(seat);
        if (k == JARVIS) finish(seat, pick("నా కాయలన్నీ ఇంటికి చేరాయి, ఈసారి నేను గెలిచాను! పర్వాలేదు " + host.her() + ", మళ్లీ ఆడదాం.",
                "ఈసారి నాకు అదృష్టం కలిసొచ్చింది, నేను గెలిచాను! మీరు చాలా బాగా ఆడారు, మళ్లీ ఆడదాం."), "happy", "namaste");
        else if (k == HERE && sole(seat)) finish(seat, pick("మీరు గెలిచారు" + herName(seat) + "! మీ కాయలన్నీ ఇంటికి చేరాయి, చాలా బాగా ఆడారు!",
                "అద్భుతం! మీ కాయలన్నీ ఇంటికి చేరాయి, మీరే గెలిచారు!"), "excited", "clap");
        else if (k == HERE) finish(seat, COLOR_NAMES[g.col[seat]] + " వారు గెలిచారు! అన్ని కాయలూ ఇంటికి చేరాయి, చాలా బాగా ఆడారు!", "excited", "clap");
        else finish(seat, name(seat) + " " + verb(seat, "గెలిచారు", "గెలిచాను", "గెలిచాడు") + "! పర్వాలేదు, మళ్లీ ఆడదాం.", "happy", null);
    }

    @Override String lastWords() {
        int s = g.lastSeat;
        if (s < 0 || s >= g.n || g.lastKind == 0) return "";
        String to = dat(s) + " ";
        if (g.lastKind == 3) return to + "మూడు సార్లు ఆరు పడింది, వంతు పోయింది.";
        String d = num(g.lastDie);
        if (g.lastKind == 2) return to + d + " పడింది, ఏ కాయా కదలలేదు.";
        String w;
        if (g.lastCapSeat >= 0 && g.lastCapMask != 0) w = whose(g.lastCapSeat) + " కాయని " + verb(s, "కొట్టేశారు", "కొట్టేశాను", "కొట్టేశాడు") + "!";
        else if (g.lastTo == HOME) w = own(s) + " కాయని ఇంటికి " + verb(s, "చేర్చారు", "చేర్చాను", "చేర్చాడు") + "!";
        else if (g.lastFrom == YARD) w = "కొత్త కాయని బయటికి " + verb(s, "తీశారు", "తీశాను", "తీశాడు") + ".";
        else w = own(s) + " కాయని ముందుకు " + verb(s, "జరిపారు", "జరిపాను", "జరిపాడు") + ".";
        return to + d + " పడింది, " + w;
    }

    /** "నా" / "మీ" / "తన" (Anil far) / "తమ" (someone else, politely). */
    private String own(int seat) {
        if (kind(seat) == JARVIS) return "నా";
        if (sole(seat)) return "మీ";
        return "son".equals(role(seat)) && !onPhone ? "తన" : "తమ";
    }

    // ================================================================ hint and words

    @Override boolean hint() {
        int seat = g.turn;
        if (done || kind(seat) != HERE || mode != 0) return false;
        if (!g.rolled) {
            say(pick("ముందు పాచిక వేయండి" + herName(seat) + ", పాచిక మీద నొక్కండి.", "పాచిక నొక్కండి, ఏం పడుతుందో చూద్దాం!"), "happy", "point");
            return true;
        }
        int t = g.copy().choose(2, rnd);
        if (t < 0) return false;
        hintSeat = seat;
        hintTok = t;
        invalidate();
        LudoRules c = g.copy();
        int p = g.pos[seat][t], r = c.move(t);
        String it = "పచ్చ వలయం ఉన్న కాయ", w;
        if ((r & LudoRules.CAPTURE) != 0) w = it + "ని జరపండి, దాంతో " + (kind(c.lastCapSeat) == JARVIS ? "నా" : whose(c.lastCapSeat)) + " కాయని కొట్టొచ్చు!";
        else if ((r & LudoRules.HOME_IN) != 0) w = it + "ని జరపండి, అది నేరుగా ఇంటికి చేరుతుంది!";
        else if (p == YARD) w = it + "ని బయటికి తీయండి.";
        else if (p < LANE && g.danger(seat, g.abs(seat, p)) > 0) w = it + " వెనక వేరే కాయ ఉంది, దాన్ని తప్పించండి.";
        else if ((r & LudoRules.LANE_IN) != 0) w = it + "ని ఇంటి దారిలోకి జరపండి, అక్కడ ఎవరూ కొట్టలేరు.";
        else if ((r & LudoRules.SAFE) != 0) w = it + "ని నక్షత్రం గడికి జరపండి, అక్కడ ఎవరూ కొట్టలేరు.";
        else w = "నేనైతే " + it + "ని ముందుకు జరుపుతాను.";
        say(w, "happy", "point");
        return true;
    }

    private static final String ROLL = "(?s).*(పాచిక|వేయి|వెయ్యి|వెయ్|వేస్తాను|వేయండి|వేస్తా|దాయం|రోల్|roll|dice|గవ్వలు).*";
    private static final String YOU = "(?s).*(నువ్వే జరుపు|నువ్వే పెట్టు|నువ్వే వేయి|నువ్వే వెయ్యి|నువ్వే ఆడు|నువ్వే చెయ్యి|నువ్వే చేయి|నువ్వే కదుపు).*";

    @Override boolean heard(String t) {
        if (done || t == null) return false;
        boolean you = t.matches(YOU), rollW = !you && t.matches(ROLL);
        if (!you && !rollW) return false;
        int s = g.turn;
        if (kind(s) != HERE) {
            say(kind(s) == JARVIS ? "ఒక్క క్షణం, ఇప్పుడు నా వంతు." : "ఇప్పుడు " + name(s) + " వంతు, కొంచెం ఆగండి.", null, null);
            return true;
        }
        if (busy() || mode != 0) return true;
        if (!g.rolled) { roll(); return true; }
        if (you) {
            int k = g.copy().choose(2, rnd);
            if (k < 0) return true;
            keep();
            say(pick("సరే, ఈ కాయని నేను జరుపుతాను.", "సరే, ఇదిగో."), "happy", "point");
            go(k);
            return true;
        }
        say("పాచిక వేశారు, ఇప్పుడు మెరుస్తున్న కాయని నొక్కండి.", null, "point");
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
        } else {
            float side = Math.max(60 * dp, Math.min(w - 2 * m, h - 3 * m - 150 * dp)), sh = Math.min(230 * dp, h - 3 * m - side);
            float y0 = Math.max(0, (h - (sh + side + 3 * m)) / 2f);
            board.set((w - side) / 2f, y0 + m, (w + side) / 2f, y0 + m + side);
            strip.set(m, board.bottom + m, w - m, board.bottom + m + sh);
            dieS = Math.max(40 * dp, Math.min(sh - 76 * dp, 100 * dp));
            dieX = strip.left + 16 * dp + dieS / 2f;
            dieY = strip.top + 38 * dp + dieS / 2f;
            listX = dieX + dieS / 2f + 30 * dp;
            listY = strip.top + 4 * dp;
            listW = (strip.right - listX) / 2f;
            listCols = 2;
        }
        rowH = 56 * dp;
        u = board.width() / 15f;
        bmp = null;
    }

    /** Where a token of this colour rests at position p (view pixels) into out. */
    private void spotAt(int colour, int t, int p, float[] out) {
        if (p == YARD) {
            int i = g.k == 2 ? (t == 0 ? 0 : 3) : t;
            out[0] = board.left + (YARD_AT[colour][0] + (i % 2 == 0 ? 2 : 4)) * u;
            out[1] = board.top + (YARD_AT[colour][1] + (i < 2 ? 2 : 4)) * u;
        } else if (p == HOME) {
            float x = 7.5f + (t - (g.k - 1) / 2f) * 0.42f, y = 8.45f; // (red's triangle, then turned)
            for (int i = 0; i < colour; i++) { float nx = 15 - y; y = x; x = nx; }
            out[0] = board.left + x * u;
            out[1] = board.top + y * u;
        } else {
            int[] c = LudoRules.cellOf(colour, p);
            out[0] = board.left + (c[0] + 0.5f) * u;
            out[1] = board.top + (c[1] + 0.5f) * u;
        }
    }

    /** The movable token of the seat nearest the tap (or any in its yard when the yard is tapped), or -1. */
    private int tokenAt(int seat, float x, float y) {
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (int t = 0; t < g.k; t++) {
            if (!g.legal(t)) continue;
            float d = (float) Math.hypot(x - tokX[seat][t], y - tokY[seat][t]);
            if (d < bd) { bd = d; best = t; }
        }
        if (best >= 0 && bd <= u * 1.15f) return best;
        int[] o = YARD_AT[g.col[seat]];
        float yx = board.left + o[0] * u, yy = board.top + o[1] * u;
        if (x >= yx && x <= yx + 6 * u && y >= yy && y <= yy + 6 * u)
            for (int t = 0; t < g.k; t++) if (g.pos[seat][t] == YARD && g.legal(t)) return t;
        return -1;
    }

    // ================================================================ drawing

    @Override protected void draw2(Canvas c) {
        layout();
        if (u <= 0) return;
        boardImage(c);
        marks(c);
        tokens(c);
        side(c);
        if (!done && mode == 0 && host != null && kind(g.turn) == HERE) postInvalidateDelayed(70); // (the pulse)
    }

    private void boardImage(Canvas c) {
        int s = Math.round(board.width()), key = s * 8 + g.n;
        if (!noBmp && (bmp == null || bmpKey != key)) {
            bmp = null;
            try {
                Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
                paintBoard(new Canvas(b), 0, 0);
                bmp = b;
                bmpKey = key;
            } catch (Throwable e) {
                noBmp = true; // (no memory for it: drawn every time)
            }
        }
        if (bmp != null) c.drawBitmap(bmp, board.left, board.top, null);
        else paintBoard(c, board.left, board.top);
    }

    /** The board itself (drawn once into a picture): yards, track, home columns, stars, the middle. */
    private void paintBoard(Canvas c, float ox, float oy) {
        boolean[] on = new boolean[4];
        for (int s = 0; s < g.n; s++) on[g.col[s]] = true;
        float S = 15 * u;
        fill.setColor(0xFFFFFFFF);
        c.drawRect(ox, oy, ox + S, oy + S, fill);
        for (int k = 0; k < 4; k++) {
            int cc = on[k] ? COLORS[k] : mix(COLORS[k], 0xFFFFFFFF, 0.72f);
            float x0 = ox + YARD_AT[k][0] * u, y0 = oy + YARD_AT[k][1] * u;
            fill.setColor(cc);
            c.drawRect(x0, y0, x0 + 6 * u, y0 + 6 * u, fill);
            fill.setColor(0xFFFFFFFF);
            rf.set(x0 + 0.8f * u, y0 + 0.8f * u, x0 + 5.2f * u, y0 + 5.2f * u);
            c.drawRoundRect(rf, 0.6f * u, 0.6f * u, fill);
            line.setStrokeWidth(1.6f * dp);
            for (int i = 0; i < 4; i++) {
                float cx = x0 + (i % 2 == 0 ? 2 : 4) * u, cy = y0 + (i < 2 ? 2 : 4) * u;
                fill.setColor(on[k] ? mix(COLORS[k], 0xFFFFFFFF, 0.55f) : 0xFFEFEFEF);
                c.drawCircle(cx, cy, 0.66f * u, fill);
                line.setColor(on[k] ? mix(COLORS[k], 0xFF000000, 0.3f) : 0xFFD0D0D0);
                c.drawCircle(cx, cy, 0.66f * u, line);
            }
        }
        line.setStrokeWidth(1.3f * dp);
        for (int i = 0; i < LudoRules.TRACK; i++) {
            int k = i / 13;
            boolean st = i % 13 == 0;
            cellRect(ox, oy, LudoRules.CELLS[i]);
            fill.setColor(st ? (on[k] ? COLORS[k] : mix(COLORS[k], 0xFFFFFFFF, 0.72f)) : 0xFFFFFFFF);
            c.drawRect(rf, fill);
            line.setColor(0xFF6E7A86);
            c.drawRect(rf, line);
            if (st || i % 13 == 8) drawStar(c, rf.centerX(), rf.centerY(), 0.36f * u, st ? 0xFFFFFFFF : 0xFFFFC21A);
        }
        for (int k = 0; k < 4; k++) {
            int cc = on[k] ? COLORS[k] : mix(COLORS[k], 0xFFFFFFFF, 0.72f);
            for (int p = LANE; p < HOME; p++) {
                cellRect(ox, oy, LudoRules.cellOf(k, p));
                fill.setColor(cc);
                c.drawRect(rf, fill);
                line.setColor(0xFF6E7A86);
                c.drawRect(rf, line);
            }
            // an arrow on the last track square, into the home column
            int[] e = LudoRules.CELLS[(13 * k + 50) % LudoRules.TRACK], f = LudoRules.cellOf(k, LANE);
            float ex = ox + (e[0] + 0.5f) * u, ey = oy + (e[1] + 0.5f) * u, dx = f[0] - e[0], dy = f[1] - e[1];
            tri.reset();
            tri.moveTo(ex + dx * 0.32f * u, ey + dy * 0.32f * u);
            tri.lineTo(ex - dx * 0.2f * u - dy * 0.26f * u, ey - dy * 0.2f * u + dx * 0.26f * u);
            tri.lineTo(ex - dx * 0.2f * u + dy * 0.26f * u, ey - dy * 0.2f * u - dx * 0.26f * u);
            tri.close();
            fill.setColor(cc);
            c.drawPath(tri, fill);
        }
        // the middle: each colour's home triangle
        float mx = ox + 7.5f * u, my = oy + 7.5f * u;
        float[][] corners = {{6, 9, 9, 9}, {6, 6, 6, 9}, {6, 6, 9, 6}, {9, 6, 9, 9}};
        for (int k = 0; k < 4; k++) {
            tri.reset();
            tri.moveTo(ox + corners[k][0] * u, oy + corners[k][1] * u);
            tri.lineTo(ox + corners[k][2] * u, oy + corners[k][3] * u);
            tri.lineTo(mx, my);
            tri.close();
            fill.setColor(on[k] ? COLORS[k] : mix(COLORS[k], 0xFFFFFFFF, 0.72f));
            c.drawPath(tri, fill);
            line.setColor(0xFF33404D);
            line.setStrokeWidth(1.6f * dp);
            c.drawPath(tri, line);
        }
        line.setColor(0xFF22303D);
        line.setStrokeWidth(3 * dp);
        c.drawRect(ox + 1.5f * dp, oy + 1.5f * dp, ox + S - 1.5f * dp, oy + S - 1.5f * dp, line);
    }

    private void cellRect(float ox, float oy, int[] cell) { rf.set(ox + cell[0] * u, oy + cell[1] * u, ox + (cell[0] + 1) * u, oy + (cell[1] + 1) * u); }

    private void drawStar(Canvas c, float x, float y, float r, int color) {
        c.save();
        c.translate(x, y);
        c.scale(r, r);
        fill.setColor(color);
        c.drawPath(star, fill);
        line.setColor(0xFF4A3B00);
        line.setStrokeWidth(1.4f * dp / r);
        c.drawPath(star, line);
        c.restore();
    }

    /** The last move's squares, the hint, and where the tokens that can move would go. */
    private void marks(Canvas c) {
        if (g.lastKind == 1 && g.lastSeat >= 0 && g.lastSeat < g.n && mode == 0) {
            int colour = g.col[g.lastSeat];
            fill.setColor(0x55FFC107);
            line.setColor(0xFFFFA000);
            line.setStrokeWidth(2.5f * dp);
            for (int i = 0; i < 2; i++) {
                int[] cell = LudoRules.cellOf(colour, i == 0 ? g.lastFrom : g.lastTo);
                if (cell == null) continue;
                cellRect(board.left, board.top, cell);
                c.drawRect(rf, fill);
                c.drawRect(rf, line);
            }
        }
        if (mode != 0 || done || !g.rolled || kind(g.turn) != HERE) return;
        int s = g.turn;
        for (int t = 0; t < g.k; t++) {
            if (!g.legal(t)) continue;
            spotAt(g.col[s], t, g.target(s, t, g.die), pt);
            boolean h = s == hintSeat && t == hintTok;
            line.setColor(h ? 0xFF00C853 : 0xCC2B3A4A);
            line.setStrokeWidth((h ? 4 : 2.5f) * dp);
            c.drawCircle(pt[0], pt[1], 0.3f * u, line);
            fill.setColor(h ? 0xFF00C853 : COLORS[g.col[s]]);
            c.drawCircle(pt[0], pt[1], 0.12f * u, fill);
        }
    }

    private int cellKey(int s, int t) {
        int[] cell = LudoRules.cellOf(g.col[s], g.pos[s][t]);
        return cell == null ? -1 : cell[1] * 15 + cell[0];
    }

    private boolean hidden(int s, int t) {
        if (mode == 2 && s == mvSeat && t == mvTok) return true;
        return (mode == 2 || mode == 3) && s == capSeat && (capMask & (1 << t)) != 0;
    }

    private void tokens(Canvas c) {
        Arrays.fill(cnt, 0);
        Arrays.fill(seen, 0);
        for (int s = 0; s < g.n; s++) for (int t = 0; t < g.k; t++) {
            if (hidden(s, t)) continue;
            int key = cellKey(s, t);
            if (key >= 0) cnt[key]++;
        }
        long now = SystemClock.uptimeMillis();
        float pulse = (float) (0.5 + 0.5 * Math.sin(now / 150.0));
        boolean choosing = mode == 0 && !done && g.rolled && kind(g.turn) == HERE;
        for (int i = 1; i <= g.n; i++) { // (the seat to play last: its tokens on top)
            int s = (g.turn + i) % g.n, colour = g.col[s];
            for (int t = 0; t < g.k; t++) {
                if (hidden(s, t)) continue;
                int p = g.pos[s][t];
                spotAt(colour, t, p, pt);
                float x = pt[0], y = pt[1], size = p == YARD ? 1.05f * u : p == HOME ? 0.55f * u : u;
                int key = cellKey(s, t);
                if (key >= 0 && cnt[key] > 1) {
                    int idx = seen[key]++;
                    if (cnt[key] == 2) { x += (idx == 0 ? -0.2f : 0.2f) * u; size = 0.78f * u; }
                    else { x += (idx % 2 == 0 ? -0.22f : 0.22f) * u; y += (idx / 2 % 2 == 0 ? -0.2f : 0.2f) * u; size = 0.62f * u; }
                }
                tokX[s][t] = x;
                tokY[s][t] = y;
                boolean can = choosing && s == g.turn && g.legal(t), hinted = s == hintSeat && t == hintTok && g.rolled;
                if (can || hinted) {
                    line.setColor(0xFF22303D);
                    line.setStrokeWidth(5.5f * dp);
                    float rr = size * (0.5f + 0.08f * pulse);
                    c.drawCircle(x, y, rr, line);
                    line.setColor(hinted ? 0xFF00E676 : 0xFFFFC400);
                    line.setStrokeWidth(3.5f * dp);
                    c.drawCircle(x, y, rr, line);
                }
                drawPawn(c, x, y, size, COLORS[colour], can ? pulse * 0.07f * u : 0);
            }
        }
        // the hopping token, and tokens sent home
        if (mode == 2 && mvSeat >= 0 && hopN >= 2) {
            int segs = hopN - 1;
            float raw = animRaw() * segs;
            int i = Math.min(segs - 1, (int) raw);
            float f = Math.min(1f, raw - i), e = f * f * (3 - 2 * f);
            float x = hopX[i] + (hopX[i + 1] - hopX[i]) * e, y = hopY[i] + (hopY[i + 1] - hopY[i]) * e;
            float lift = (float) Math.sin(Math.PI * f) * (segs == 1 ? 1.1f : 0.45f) * u;
            if (capSeat >= 0) for (int t = 0; t < g.k; t++) if ((capMask & (1 << t)) != 0) drawPawn(c, capX + (t - 1.5f) * 0.08f * u, capY, 0.9f * u, COLORS[g.col[capSeat]], 0);
            drawPawn(c, x, y, u, COLORS[g.col[mvSeat]], lift);
            tokX[mvSeat][mvTok] = hopX[hopN - 1];
            tokY[mvSeat][mvTok] = hopY[hopN - 1];
        } else if (mode == 3 && capSeat >= 0) {
            float a = anim();
            for (int t = 0; t < g.k; t++) {
                if ((capMask & (1 << t)) == 0) continue;
                spotAt(g.col[capSeat], t, YARD, pt);
                float x = capX + (pt[0] - capX) * a, y = capY + (pt[1] - capY) * a;
                drawPawn(c, x, y, u, COLORS[g.col[capSeat]], (float) Math.sin(Math.PI * a) * 1.4f * u);
            }
        }
    }

    /** A game pawn (round head, flared body, wide base) with a dark outline and a shadow; lift: up from its shadow. */
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

    /** Beside the board: whose turn, the die, the players. */
    private void side(Canvas c) {
        int s = mvSeat >= 0 && mvSeat < g.n && mode >= 2 ? mvSeat : g.turn; // (while a token moves, it is still that player's go)
        boolean hereRoll = !done && mode == 0 && !g.rolled && kind(s) == HERE;
        int cur = COLORS[g.col[s]];
        String who = done ? (winner < 0 || winner >= g.n ? "" : kind(winner) == JARVIS ? "Jarvis గెలిచాడు" : label(winner) + " గెలిచారు!") : kind(s) == JARVIS ? "Jarvis వంతు" : sole(s) ? "మీ వంతు" : kind(s) == HERE ? COLOR_NAMES[g.col[s]] + " వారి వంతు" : name(s) + " వంతు";
        fitText(c, who, dieX, dieY - dieS / 2f - 26 * dp, 20 * dp, Math.max(dieS * 1.3f, strip.width() - 8 * dp), 0xFFFFFFFF);
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
            if (!g.rolled && !done && mode < 2) { // (the old roll, waiting for the next)
                fill.setColor(0x88FFFFFF);
                rf.set(dieX - dieS / 2f, dieY - dieS / 2f, dieX + dieS / 2f, dieY + dieS / 2f);
                c.drawRoundRect(rf, dieS * 0.18f, dieS * 0.18f, fill);
            }
        }
        if (hereRoll) fitText(c, "నొక్కండి", dieX, dieY + dieS / 2f + 24 * dp, 18 * dp, strip.width(), 0xFFFFD166);
        // the players: a pawn, the name, a dot for each token home
        for (int i = 0; i < g.n; i++) {
            float x = listX + (i % listCols) * listW, y = listY + (i / listCols) * rowH;
            if (y + rowH > strip.bottom + 4 * dp) break;
            if (i == s && !done) {
                rf.set(x + 2 * dp, y + 3 * dp, x + listW - 2 * dp, y + rowH - 3 * dp);
                fill.setColor(0x26FFFFFF);
                c.drawRoundRect(rf, 12 * dp, 12 * dp, fill);
                line.setColor(COLORS[g.col[i]]);
                line.setStrokeWidth(2.5f * dp);
                c.drawRoundRect(rf, 12 * dp, 12 * dp, line);
            }
            drawPawn(c, x + 22 * dp, y + rowH / 2f, 34 * dp, COLORS[g.col[i]], 0);
            left.setTextSize(17 * dp);
            float tw = left.measureText(label(i)), max = listW - 50 * dp;
            if (tw > max && max > 0) left.setTextSize(17 * dp * max / tw);
            left.setColor(0xFFFFFFFF);
            c.drawText(label(i), x + 44 * dp, y + rowH * 0.42f, left);
            for (int t = 0; t < g.k; t++) {
                float dx = x + 50 * dp + t * 13 * dp, dy = y + rowH * 0.7f;
                if (g.pos[i][t] == HOME) { fill.setColor(COLORS[g.col[i]]); c.drawCircle(dx, dy, 4.5f * dp, fill); }
                line.setColor(0xFFB4C5D8);
                line.setStrokeWidth(1.5f * dp);
                c.drawCircle(dx, dy, 4.5f * dp, line);
            }
        }
    }

    private String label(int s) {
        if (kind(s) == JARVIS) return "Jarvis";
        if (sole(s)) return "మీరు";
        if (kind(s) == HERE) return COLOR_NAMES[g.col[s]];
        return name(s);
    }

    /** Text centred at (x, y), made smaller when wider than maxW. */
    private void fitText(Canvas c, String s, float x, float y, float size, float maxW, int color) {
        text.setTextSize(size);
        float w = text.measureText(s);
        if (w > maxW && maxW > 0) text.setTextSize(size * maxW / w);
        text.setColor(color);
        c.drawText(s, x, y - (text.ascent() + text.descent()) / 2f, text);
    }

    private static int mix(int a, int b, float f) {
        int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f), gg = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f),
                bb = (int) ((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (gg << 8) | bb;
    }

    /** A pawn about one unit tall, centred on (0, 0). */
    private static void makePawn(Path p) {
        p.reset();
        p.addCircle(0, -0.26f, 0.2f, Path.Direction.CW);
        p.moveTo(-0.12f, -0.1f);
        p.lineTo(0.12f, -0.1f);
        p.quadTo(0.15f, 0.12f, 0.3f, 0.26f);
        p.lineTo(-0.3f, 0.26f);
        p.quadTo(-0.15f, 0.12f, -0.12f, -0.1f);
        p.close();
        p.addOval(new RectF(-0.36f, 0.18f, 0.36f, 0.42f), Path.Direction.CW);
    }

    /** A five-pointed star of radius 1 round (0, 0). */
    private static void makeStar(Path p) {
        p.reset();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float r = i % 2 == 0 ? 1f : 0.45f, x = (float) (Math.cos(a) * r), y = (float) (Math.sin(a) * r);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();
    }
}
