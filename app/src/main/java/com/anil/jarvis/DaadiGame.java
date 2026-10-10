package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;

import java.util.Arrays;

/**
 * దాడి on the screen. She taps an empty point to put a piece; later she taps her piece (it glows, the points it can
 * go to get green rings) and then a ring. When she makes a row of three, the pieces she may take glow red and she taps
 * one. Jarvis says what he did ("నేను నా కాయని లోపలి చదరంలో పై మధ్యలో పెట్టాను. ఇప్పుడు మీ వంతు."); when he makes a
 * row, the piece he takes stays a moment with a red ring and then fades away.
 * Seat 0 has the white (cream) pieces and starts; seat 1 the dark (maroon) ones.
 */
final class DaadiGame extends Game {
    private final DaadiRules g = new DaadiRules();
    private final int[] buf = new int[640];
    /** The board as drawn (her row-making move shown before she picks the piece to take). */
    private final int[] show = new int[24];
    private final boolean[] target = new boolean[24], takeable = new boolean[24];
    /** Her chosen piece (moving phase), or -1. */
    private int sel = -1;
    /** Her move made a row of three: shown, waiting for her to pick the piece to take (not yet in the rules). */
    private boolean pend;
    private int pendFrom = -1, pendTo = -1;
    private boolean hintOn;
    private int hintFrom = -1, hintTo = -1, hintRem = -1;
    // The running animation: the piece now on aTo comes from aFrom (-1: put down) during the first aMoveEnd of it;
    // ghost: a piece just taken (value ghostV), shown with a red ring until aFade, then fading away.
    private int aTo = -1, aFrom = -1, ghost = -1, ghostV;
    private float aMoveEnd = 1f, aFade;
    /** Her last move stopped his row / took his piece (Jarvis says so in his reply). */
    private boolean herBlocked, herTook;
    /** "Three left: you may fly" said for this seat. */
    private final boolean[] flySaid = new boolean[2];
    // layout (onSizeChanged)
    private float step, pr;
    private final float[] px = new float[24], py = new float[24];
    private final RectF boardR = new RectF(), innerR = new RectF(), sqR = new RectF(), panelTop = new RectF(), panelBot = new RectF();

    private static final int FRAME = 0xFF8E5A2A, WOOD = 0xFFDDB46C, INK = 0xFF3E2410, LAST = 0xFF1557C9, HINT = 0xFF9C27B0,
            GO = 0xFF15964A, TAKE = 0xFFE53935, ROW = 0xFFE8590C, GLOW = 0xFFFFE14D;
    private static final String[] HAND = new String[10], LEFT = new String[10];

    static {
        for (int i = 0; i < 10; i++) { HAND[i] = "చేతిలో " + i; LEFT[i] = "బోర్డు మీద " + i; }
    }

    DaadiGame(Context c) { super(c); }

    @Override String id() { return "daadi"; }
    @Override String title() { return "దాడి"; }
    @Override String[] options() { return new String[]{"⚪ నేను ముందు", "⚫ రెండో వారు ముందు"}; }

    @Override void newGame(String[] who, int option) {
        roles = option == 1 ? new String[]{who[1], who[0]} : new String[]{who[0], who[1]};
        g.reset();
        clearUi();
        start();
        if (hasJarvis()) {
            if (kind(0) == HERE) say(pick("రండి " + host.her() + ", దాడి ఆడదాం! మీవి తెల్ల కాయలు, నావి ముదురు రంగు కాయలు. మూడు కాయలు ఒకే గీత మీద వరుసగా పెడితే, నా కాయ ఒకటి తీసేయొచ్చు. మీరే ముందు, ఏదైనా చుక్క మీద నొక్కండి.",
                    "సరే, దాడి మొదలుపెడదాం! ఒకే గీత మీద మూడు కాయలు వరుసగా వస్తే, ఎదుటివారి కాయ ఒకటి తీయొచ్చు. మీవి తెల్ల కాయలు, మీరే ముందు పెట్టండి."), "happy", "point");
            else say("సరే, ఈసారి నేను ముందు పెడతాను. మీవి ముదురు రంగు కాయలు. ఒకే గీత మీద మూడు కాయలు వరుసగా వస్తే, ఎదుటివారి కాయ ఒకటి తీయొచ్చు.", "happy", null);
        } else if (!far()) {
            say("దాడి మొదలు! తెల్ల కాయలవారు ముందు పెట్టాలి. ఒకే గీత మీద మూడు కాయలు వరుసగా వస్తే, ఎదుటివారి కాయ ఒకటి తీయొచ్చు.", "happy", "point");
        }
    }

    private void clearUi() {
        sel = -1;
        pend = false;
        pendFrom = pendTo = -1;
        hintOn = false;
        aTo = aFrom = ghost = -1;
        herBlocked = herTook = false;
        flySaid[0] = g.flying(0);
        flySaid[1] = g.flying(1);
    }

    @Override String save() { return g.save(); }

    @Override boolean load(String s) {
        if (!g.load(s)) return false;
        clearUi();
        return true;
    }

    @Override int turn() { return g.turn; }

    @Override String yourTurn(int seat) {
        String who = sole(seat) ? "మీ వంతు" : name(seat) + " వంతు";
        String what = g.hand[seat] > 0 ? "కాయ పెట్టండి" : g.flying(seat) ? "కాయని ఎక్కడికైనా జరపొచ్చు" : "కాయని జరపండి";
        return (seat == 0 ? "⚪ " : "⚫ ") + who + ": " + what + " 👇";
    }

    /** "తెల్ల" / "ముదురు రంగు" (two people at the tablet: the pieces by their colour). */
    private static String colour(int seat) { return seat == 0 ? "తెల్ల" : "ముదురు రంగు"; }

    /** Whose pieces: "నా", "మీ", or the name. */
    private String whose(int seat) { return role(seat).equals("jarvis") ? "నా" : sole(seat) ? "మీ" : name(seat); }

    // ================================================================ her moves

    @Override void tap(float x, float y) {
        int p = pointAt(x, y), seat = g.turn, me = seat + 1;
        if (pend) {
            if (p >= 0 && takeable[p]) { applyHere(DaadiRules.mv(pendFrom, pendTo, p), true); return; }
            if (p >= 0 && show[p] == 2 - seat) say(pick("ఆ కాయ మూడు వరుసలో ఉంది, దాన్ని తీయలేం. ఎర్రగా వెలుగుతున్న వాటిలో ఒకటి నొక్కండి.",
                    "అది మూడు వరుసలో భద్రంగా ఉంది. ఎర్ర గీత ఉన్న కాయల్లో ఒకటి నొక్కండి."), "caring", "point");
            return;
        }
        if (p < 0) { if (sel >= 0) { sel = -1; invalidate(); } return; }
        if (g.hand[seat] > 0) { if (g.cell[p] == 0) play(-1, p); return; }
        if (g.cell[p] == me) { choosePiece(p, seat); return; }
        if (sel >= 0 && target[p]) { play(sel, p); return; }
        if (sel >= 0) { sel = -1; invalidate(); }
    }

    private void choosePiece(int p, int seat) {
        if (sel == p) { sel = -1; invalidate(); return; }
        Arrays.fill(target, false);
        boolean any = false;
        int n = g.moves(buf);
        for (int i = 0; i < n; i++) if (DaadiRules.from(buf[i]) == p) { target[DaadiRules.to(buf[i])] = true; any = true; }
        if (!any) {
            sel = -1;
            host.status("ఆ కాయకి దారి లేదు, వేరే కాయ ఎంచుకోండి 👇");
        } else {
            sel = p;
            host.status(yourTurn(seat));
        }
        invalidate();
    }

    /** Her move f → t (f -1: from the hand). A row of three waits for her to pick the piece to take. */
    private void play(int f, int t) {
        int seat = g.turn, o = 1 - seat;
        int plain = DaadiRules.mv(f, t, -1);
        if (g.legal(plain)) { applyHere(plain, false); return; }
        Arrays.fill(takeable, false);
        boolean any = false;
        int n = g.moves(buf);
        for (int i = 0; i < n; i++) {
            int m = buf[i];
            if (DaadiRules.from(m) == f && DaadiRules.to(m) == t && DaadiRules.rem(m) >= 0) { takeable[DaadiRules.rem(m)] = true; any = true; }
        }
        if (!any) return;
        // she followed the hint: its "take this one" ring stays while she chooses
        boolean keepHint = hintOn && hintRem >= 0 && hintFrom == f && hintTo == t;
        pend = true;
        pendFrom = f;
        pendTo = t;
        sel = -1;
        hintOn = keepHint;
        hintFrom = hintTo = -1;
        aTo = t;
        aFrom = f;
        ghost = -1;
        aMoveEnd = 1f;
        animate(f < 0 ? 300 : 420, null);
        host.status(sole(seat) ? "ఒక కాయని తీసేయండి 👇" : name(seat) + ": ఒక " + colour(o) + " కాయని తీసేయండి 👇");
        if (kind(o) == JARVIS) say(pick("అబ్బా! మూడు వరుసలో పెట్టేశారు! నా కాయ ఒకటి తీసేయండి.",
                "భలే, దాడి కట్టేశారు! ఎర్రగా వెలుగుతున్న నా కాయల్లో ఒకటి నొక్కండి."), "surprised", "point");
        else say(who(seat) + " మూడు వరుస " + verb(seat, "కట్టారు", "కట్టాను", "కట్టాడు") + "! ఇప్పుడు ఒక " + colour(o) + " కాయని తీసేయండి.", "excited", "point");
        invalidate();
    }

    /** A whole move by a person here (shown: the piece is already on the screen, only the taking is left). */
    private void applyHere(int m, boolean shown) {
        int seat = g.turn, f = DaadiRules.from(m), t = DaadiRules.to(m), r = DaadiRules.rem(m);
        keep();
        if (kind(1 - seat) == JARVIS) { herBlocked = r < 0 && g.blocks(seat, t); herTook = r >= 0; }
        boolean placing = g.hand[0] + g.hand[1] > 0;
        ghostV = r >= 0 ? g.cell[r] : 0;
        g.apply(m);
        hintOn = false;
        sel = -1;
        pend = false;
        aTo = shown ? -1 : t;
        aFrom = f;
        ghost = r;
        if (r >= 0) {
            aMoveEnd = shown ? 0f : 0.35f;
            aFade = shown ? 0.15f : 0.45f;
            animate(shown ? 600 : 1000, () -> ghost = -1);
        } else {
            aMoveEnd = 1f;
            animate(f < 0 ? 300 : 420, null);
        }
        if (end(seat)) return;
        if (!hasJarvis()) peopleTalk(seat, placing);
        moved();
    }

    /** Two people here (or Anil far): only the special moments. */
    private void peopleTalk(int seat, boolean wasPlacing) {
        int o = 1 - seat;
        if (wasPlacing && g.hand[0] + g.hand[1] == 0) {
            say("అన్ని కాయలూ పెట్టేశారు! ఇక కాయని గీత మీదుగా పక్కనున్న ఖాళీ చుక్కకి జరపాలి.", "happy", "point");
        } else if (g.flying(o) && !flySaid[o]) {
            flySaid[o] = true;
            say("ఇక " + colour(o) + " కాయలు మూడే మిగిలాయి. అవి ఏ ఖాళీ చుక్కకైనా ఎగరొచ్చు!", "surprised", null);
        }
    }

    // ================================================================ Jarvis

    @Override void jarvisMove() {
        final int level = host.level();
        final DaadiRules copy = g.copy();
        think(() -> copy.choose(level, rnd, -1, -1, 0), m -> {
            if (done) return;
            if (m == null || m < 0 || !g.legal(m)) { // (never stuck: any legal move)
                int n = g.moves(buf);
                if (n == 0) return;
                m = buf[rnd.nextInt(n)];
            }
            int seat = g.turn, f = DaadiRules.from(m), t = DaadiRules.to(m), r = DaadiRules.rem(m);
            boolean blocks = r < 0 && g.blocks(seat, t);
            boolean wasPlacing = g.hand[0] + g.hand[1] > 0;
            boolean fly = f >= 0 && g.flying(seat) && !DaadiRules.adjacent(f, t);
            boolean flyNews = g.flying(seat) && !flySaid[seat];
            if (flyNews) flySaid[seat] = true;
            ghostV = r >= 0 ? g.cell[r] : 0;
            g.apply(m);
            hintOn = false;
            sel = -1;
            aTo = t;
            aFrom = f;
            ghost = r;
            if (r >= 0) {
                aMoveEnd = 0.25f;
                aFade = 0.6f;
                animate(1700, () -> ghost = -1);
            } else {
                aMoveEnd = 1f;
                animate(f < 0 ? 350 : 480, null);
            }
            if (!end(seat)) say(jarvisLine(seat, f, t, r, blocks, fly, flyNews, wasPlacing && g.hand[0] + g.hand[1] == 0),
                    r >= 0 ? "laugh" : blocks ? "happy" : null, null);
            moved();
        });
    }

    /** What Jarvis says about his move (a reaction to hers, what he did, whose turn). */
    private String jarvisLine(int seat, int f, int t, int r, boolean blocks, boolean fly, boolean flyNews, boolean phaseEnd) {
        int o = 1 - seat;
        StringBuilder b = new StringBuilder();
        if (herTook) b.append(pick("సరే, నా కాయ పోయింది! ", "అయ్యో, నా కాయ వెళ్లిపోయింది. "));
        else if (herBlocked) b.append(pick("అయ్యో, నా వరుసని అడ్డుకున్నారు! ", "అబ్బా, భలే అడ్డు పెట్టారు! "));
        herTook = herBlocked = false;
        if (flyNews) b.append("నాకు మూడే కాయలు మిగిలాయి, ఇక నేను ఎగరగలను! ");
        String w = DaadiRules.where(t);
        if (r >= 0) {
            b.append(pick("నేను మూడు వరుసలో పెట్టాను! మీ కాయ ఒకటి తీసేస్తాను, సారీ " + host.her() + ".",
                    "దాడి కట్టాను! మీ కాయ ఒకటి తీసేస్తున్నాను, ఏమీ అనుకోకండి."));
        } else {
            if (blocks) b.append(pick("మీ వరుస రాకుండా అడ్డు పెట్టాను! ", "అబ్బో, మీరు మూడు వరుస కట్టబోతున్నారు, ఆపేశాను! "));
            if (f < 0) b.append(pick("నేను నా కాయని " + w + "లో పెట్టాను.", "నా కాయ " + w + "లో పెట్టాను."));
            else if (fly) b.append("నా కాయని ఎగరేసి " + w + "లో పెట్టాను.");
            else b.append(pick("నేను నా కాయని " + w + "కి జరిపాను.", "నా కాయని " + DaadiRules.dir(f, t) + " జరిపాను, " + w + "కి."));
        }
        if (phaseEnd) b.append(" అన్ని కాయలూ పెట్టేశాం! ఇక కాయని గీత మీదుగా పక్క ఖాళీ చుక్కకి జరపాలి.");
        if (g.flying(o) && !flySaid[o]) {
            flySaid[o] = true;
            b.append(" మీకు మూడే కాయలు మిగిలాయి, ఇక ఏ ఖాళీ చుక్కకైనా ఎగరొచ్చు!");
        }
        b.append(' ').append(g.hand[o] > 0 ? pick("ఇప్పుడు మీ వంతు.", "మీరు పెట్టండి.", "ఇప్పుడు మీరు పెట్టండి.")
                : pick("ఇప్పుడు మీ వంతు.", "మీరు జరపండి.", "ఇక మీరు జరపండి."));
        return b.toString();
    }

    /** After a move by seat: the end, said. True when the game is over. */
    private boolean end(int seat) {
        int w = g.winner();
        if (w == -2) return false;
        if (w == -1) {
            String why = g.quiet >= DaadiRules.DRAW_QUIET ? "చాలాసేపటి నుంచి ఎవరూ కాయ తీయలేదు, " : "అవే స్థానాలు మళ్లీ మళ్లీ వస్తున్నాయి, ";
            finish(-1, why + pick("ఆట సమానం! ఇద్దరూ బాగా ఆడారు.", "ఆట సమానంగా ముగిసింది. మళ్లీ ఆడదామా?"), "happy", "thumb");
            return true;
        }
        int lo = 1 - w;
        boolean shut = g.hand[lo] + g.count[lo] >= 3; // (lost by being shut in, not by pieces)
        if (role(w).equals("jarvis")) {
            finish(w, (shut ? "మీ కాయలు ఎటూ కదలలేవు. " : "మీకు రెండు కాయలే మిగిలాయి. ")
                    + pick("ఈసారి నేను గెలిచాను! పర్వాలేదు " + host.her() + ", మీరు బాగా ఆడారు. మళ్లీ ఆడదాం.", "ఈ ఆట నాది! మీరు కూడా బాగా ఆడారు, మళ్లీ ఆడదాం."), "happy", null);
        } else if (hasJarvis()) {
            finish(w, (shut ? "నా కాయలు ఎటూ కదలలేవు! " : "నాకు రెండు కాయలే మిగిలాయి! ")
                    + pick("మీరు గెలిచారు " + host.her() + "! చాలా బాగా ఆడారు!", "అద్భుతం, మీరే గెలిచారు! భలే ఆడారు!"), "excited", "clap");
        } else {
            finish(w, did(w, "గెలిచారు", "గెలిచాను", "గెలిచాడు") + "! " + (shut ? whose(lo) + " కాయలు ఎటూ కదలలేవు. " : "")
                    + "చాలా బాగా ఆడారు.", "excited", "clap");
        }
        return true;
    }

    @Override String lastWords() {
        int s = g.lastSeat, t = g.lastTo, f = g.lastFrom;
        if (s < 0 || t < 0) return "";
        String w = DaadiRules.where(t);
        String said = f < 0 ? who(s) + " ఒక కాయని " + w + "లో " + verb(s, "పెట్టారు", "పెట్టాను", "పెట్టాడు")
                : who(s) + " ఒక కాయని " + w + "కి " + verb(s, "జరిపారు", "జరిపాను", "జరిపాడు");
        if (g.lastRem >= 0) said += ", మూడు వరుస కట్టి " + whose(1 - s) + " కాయ ఒకటి " + verb(s, "తీసేశారు", "తీసేశాను", "తీసేశాడు");
        return said + ".";
    }

    // ================================================================ hint, words

    @Override boolean hint() {
        if (done || busy()) return false;
        final int seat = g.turn;
        final boolean p = pend;
        final int pf = pendFrom, pt = pendTo;
        if (g.moves(buf) == 0) return false;
        final DaadiRules copy = g.copy();
        think(() -> copy.choose(2, rnd, pf, p ? pt : -1, 250), m -> {
            if (m == null || m < 0 || done || g.turn != seat || pend != p) return;
            int f = DaadiRules.from(m), t = DaadiRules.to(m), r = DaadiRules.rem(m);
            hintFrom = p ? -1 : f;
            hintTo = p ? -1 : t;
            hintRem = r;
            hintOn = true;
            invalidate();
            String s;
            if (p) {
                s = pick("నేనైతే " + DaadiRules.where(r) + "లో ఉన్న కాయని తీసేస్తాను.", DaadiRules.where(r) + "లో ఉన్న కాయని తీసేయండి, అది మంచిది.");
            } else {
                String w = DaadiRules.where(t);
                if (f < 0) s = pick("నేనైతే " + w + "లో పెడతాను.", w + "లో పెట్టి చూడండి.");
                else if (!DaadiRules.adjacent(f, t)) s = "నేనైతే " + DaadiRules.where(f) + "లో ఉన్న కాయని " + w + "కి ఎగరేస్తాను.";
                else s = "నేనైతే " + DaadiRules.where(f) + "లో ఉన్న కాయని " + DaadiRules.dir(f, t) + " జరుపుతాను.";
                if (r >= 0) s += " అప్పుడు మూడు వరుస వస్తుంది!";
                else if (g.blocks(seat, t)) s += hasJarvis() ? " అలా చేస్తే నా వరుస ఆగిపోతుంది!" : " అలా చేస్తే ఎదుటివారి వరుస ఆగిపోతుంది.";
            }
            say(s, "happy", "point");
        });
        return true;
    }

    /** "నువ్వే జరుపు / నువ్వే పెట్టు": Jarvis makes the hint move for her. */
    @Override boolean heard(String t) {
        if (done || busy() || kind(g.turn) != HERE || far()) return false;
        if (!t.matches("(?s).*నువ్వే.{0,8}(జరుపు|జరిపి|పెట్టు|పెట్టేయ్|తీసేయ్|తీసేయి|తీయి|ఆడు|ఆడేయ్|చెయ్యి|చేయి).*")) return false;
        final int seat = g.turn;
        final boolean p = pend;
        final int pf = pendFrom, pt = pendTo;
        final DaadiRules copy = g.copy();
        think(() -> copy.choose(2, rnd, pf, p ? pt : -1, 250), m -> {
            if (m == null || m < 0 || done || g.turn != seat || pend != p || !g.legal(m)) return;
            say(pick("సరే, మీ బదులు నేనే ఆడాను.", "సరే, ఇదిగో, మీ కోసం ఇలా జరిపాను."), "happy", null);
            applyHere(m, p);
        });
        return true;
    }

    // ================================================================ drawing

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float m = 10 * dp, minStrip = 112 * dp;
        float bs = Math.min(h - 2 * m, w - 3 * m - minStrip);
        if (bs < 120 * dp) bs = Math.max(10, Math.min(w, h) - 2 * m); // (very narrow: the board alone)
        float stripW = Math.max(0, Math.min(190 * dp, w - bs - 3 * m));
        float x0 = (w - (bs + (stripW > 0 ? m + stripW : 0))) / 2f, y0 = (h - bs) / 2f;
        boardR.set(x0, y0, x0 + bs, y0 + bs);
        innerR.set(x0 + 7 * dp, y0 + 7 * dp, x0 + bs - 7 * dp, y0 + bs - 7 * dp);
        float pad = bs * 0.08f;
        step = (bs - 2 * pad) / 6f;
        pr = step * 0.37f;
        for (int i = 0; i < 24; i++) {
            px[i] = x0 + pad + DaadiRules.X[i] * step;
            py[i] = y0 + pad + DaadiRules.Y[i] * step;
        }
        float sx = x0 + bs + m, half = (bs - m) / 2f;
        panelTop.set(sx, y0, sx + stripW, y0 + half);
        panelBot.set(sx, y0 + half + m, sx + stripW, y0 + bs);
    }

    private int pointAt(float x, float y) {
        int best = -1;
        float bd = step * 0.55f;
        for (int i = 0; i < 24; i++) {
            float d = (float) Math.hypot(x - px[i], y - py[i]);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    private static float smooth(float t) { t = Math.max(0f, Math.min(1f, t)); return t * t * (3 - 2 * t); }

    @Override protected void draw2(Canvas c) {
        if (step <= 0) return;
        float t = animRaw();
        System.arraycopy(g.cell, 0, show, 0, 24);
        if (pend) {
            if (pendFrom >= 0) show[pendFrom] = 0;
            show[pendTo] = g.turn + 1;
        }
        // the board: a wooden square with three squares and the four joining lines
        fill.setColor(FRAME);
        c.drawRoundRect(boardR, 22 * dp, 22 * dp, fill);
        fill.setColor(WOOD);
        c.drawRoundRect(innerR, 17 * dp, 17 * dp, fill);
        line.setColor(INK);
        line.setStrokeWidth(Math.max(4 * dp, step * 0.075f));
        for (int k = 0; k < 3; k++) {
            int a = k == 0 ? 0 : k == 1 ? 3 : 6, z = k == 0 ? 23 : k == 1 ? 20 : 17;
            sqR.set(px[a], py[a], px[z], py[z]);
            c.drawRect(sqR, line);
        }
        c.drawLine(px[1], py[1], px[7], py[7], line);
        c.drawLine(px[9], py[9], px[11], py[11], line);
        c.drawLine(px[12], py[12], px[14], py[14], line);
        c.drawLine(px[16], py[16], px[22], py[22], line);
        // a row of three just made
        int rowAt = pend ? pendTo : g.lastRem >= 0 ? g.lastTo : -1;
        if (rowAt >= 0 && show[rowAt] != 0) {
            line.setColor(ROW);
            line.setStrokeWidth(step * 0.16f);
            for (int mi : DaadiRules.MILLS_AT[rowAt]) {
                int[] l = DaadiRules.MILLS[mi];
                if (show[l[0]] == show[rowAt] && show[l[1]] == show[rowAt] && show[l[2]] == show[rowAt])
                    c.drawLine(px[l[0]], py[l[0]], px[l[2]], py[l[2]], line);
            }
        }
        // the points
        fill.setColor(INK);
        for (int i = 0; i < 24; i++) if (show[i] == 0) c.drawCircle(px[i], py[i], step * 0.12f, fill);
        // the last move: a blue ring where it came from and around where it went; a red cross where a piece was taken
        if (!pend && g.lastTo >= 0) {
            line.setColor(LAST);
            line.setStrokeWidth(4 * dp);
            if (g.lastFrom >= 0 && show[g.lastFrom] == 0) c.drawCircle(px[g.lastFrom], py[g.lastFrom], pr * 0.62f, line);
            if (show[g.lastTo] != 0) c.drawCircle(px[g.lastTo], py[g.lastTo], pr + 7 * dp, line);
            int x = g.lastRem;
            if (x >= 0 && show[x] == 0 && (ghost != x || t >= 1f)) {
                line.setColor(TAKE);
                line.setStrokeWidth(5 * dp);
                float d = pr * 0.42f;
                c.drawLine(px[x] - d, py[x] - d, px[x] + d, py[x] + d, line);
                c.drawLine(px[x] - d, py[x] + d, px[x] + d, py[x] - d, line);
            }
        }
        // where her chosen piece can go
        if (sel >= 0) {
            for (int i = 0; i < 24; i++) {
                if (!target[i]) continue;
                fill.setColor(0x5515964A);
                c.drawCircle(px[i], py[i], pr * 0.66f, fill);
                line.setColor(GO);
                line.setStrokeWidth(5 * dp);
                c.drawCircle(px[i], py[i], pr * 0.66f, line);
            }
            fill.setColor(0x88FFE14D);
            c.drawCircle(px[sel], py[sel], pr + 12 * dp, fill);
        }
        // the hint (purple)
        if (hintOn) {
            line.setColor(HINT);
            line.setStrokeWidth(6 * dp);
            if (hintFrom >= 0) c.drawCircle(px[hintFrom], py[hintFrom], pr + 8 * dp, line);
            if (hintTo >= 0) {
                fill.setColor(0x449C27B0);
                c.drawCircle(px[hintTo], py[hintTo], pr * 0.8f, fill);
                c.drawCircle(px[hintTo], py[hintTo], pr * 0.8f, line);
            }
        }
        // the pieces
        for (int i = 0; i < 24; i++) if (show[i] != 0 && i != aTo) stone(c, px[i], py[i], pr, show[i], 255);
        if (ghost >= 0 && ghostV != 0 && t < 1f) {
            float a = t < aFade ? 1f : 1f - (t - aFade) / Math.max(0.01f, 1f - aFade);
            if (a > 0) {
                stone(c, px[ghost], py[ghost], pr, ghostV, (int) (255 * a));
                line.setColor(TAKE);
                line.setAlpha((int) (255 * a));
                line.setStrokeWidth(6 * dp);
                c.drawCircle(px[ghost], py[ghost], pr + 6 * dp, line);
                line.setAlpha(255);
            }
        }
        if (aTo >= 0 && show[aTo] != 0) {
            float k = smooth(aMoveEnd <= 0 ? 1f : t / aMoveEnd);
            if (aFrom >= 0) {
                stone(c, px[aFrom] + (px[aTo] - px[aFrom]) * k, py[aFrom] + (py[aTo] - py[aFrom]) * k, pr * (1 + 0.12f * (float) Math.sin(Math.PI * k)), show[aTo], 255);
            } else {
                stone(c, px[aTo], py[aTo], pr * (1 + 0.4f * (1 - k)), show[aTo], (int) (110 + 145 * k));
            }
        }
        // her chosen piece glows; the pieces she may take glow red
        if (sel >= 0) {
            line.setColor(GLOW);
            line.setStrokeWidth(6 * dp);
            c.drawCircle(px[sel], py[sel], pr + 6 * dp, line);
        }
        if (pend) {
            line.setColor(TAKE);
            line.setStrokeWidth(6 * dp);
            for (int i = 0; i < 24; i++) if (takeable[i]) c.drawCircle(px[i], py[i], pr + 7 * dp, line);
        }
        if (hintOn && hintRem >= 0) {
            line.setColor(HINT);
            line.setStrokeWidth(7 * dp);
            c.drawCircle(px[hintRem], py[hintRem], pr + 12 * dp, line);
        }
        // the two sides: pieces in the hand, whose turn
        int bottom = role(1).equals("her") && !role(0).equals("her") ? 1 : 0;
        if (Game.onPhone) bottom = role(1).equals("son") ? 1 : 0;
        panel(c, panelBot, bottom);
        panel(c, panelTop, 1 - bottom);
    }

    /** A round stone: shadow, body, a carved ring, the rim and a shine. v 1 white, 2 dark. */
    private void stone(Canvas c, float x, float y, float r, int v, int alpha) {
        fill.setColor(0x66000000);
        fill.setAlpha(Math.min(255, alpha) * 0x66 / 255);
        c.drawCircle(x + r * 0.07f, y + r * 0.13f, r, fill);
        fill.setColor(v == 1 ? 0xFFFFF4DA : 0xFF7A1A2A);
        fill.setAlpha(alpha);
        c.drawCircle(x, y, r, fill);
        line.setColor(v == 1 ? 0xFFDCC39A : 0xFF52101C);
        line.setAlpha(alpha);
        line.setStrokeWidth(r * 0.09f);
        c.drawCircle(x, y, r * 0.7f, line);
        line.setColor(v == 1 ? 0xFF5A3A1A : 0xFF24050B);
        line.setAlpha(alpha);
        line.setStrokeWidth(Math.max(2f, r * 0.1f));
        c.drawCircle(x, y, r, line);
        fill.setColor(v == 1 ? 0xFFFFFFFF : 0xFFE89AA6);
        fill.setAlpha(alpha * (v == 1 ? 220 : 120) / 255);
        c.drawCircle(x - r * 0.33f, y - r * 0.35f, r * 0.2f, fill);
        line.setAlpha(255);
        fill.setAlpha(255);
    }

    private void panel(Canvas c, RectF r, int seat) {
        if (r.width() < 60 * dp) return;
        boolean now = !done && g.turn == seat;
        fill.setColor(now ? 0xFF24456E : 0xFF172C47);
        c.drawRoundRect(r, 16 * dp, 16 * dp, fill);
        if (now) {
            line.setColor(0xFFFFD166);
            line.setStrokeWidth(4 * dp);
            c.drawRoundRect(r, 16 * dp, 16 * dp, line);
        }
        float cx = r.centerX(), y = r.top + 26 * dp;
        String label = role(seat).equals("jarvis") ? "Jarvis" : sole(seat) ? "మీరు" : name(seat);
        float size = 21 * dp;
        text.setTextSize(size);
        float tw = text.measureText(label);
        if (tw > r.width() - 14 * dp) size *= (r.width() - 14 * dp) / tw;
        centerText(c, label, cx, y, size, 0xFFFFFFFF);
        stone(c, cx, y + 42 * dp, Math.min(24 * dp, r.width() * 0.2f), seat + 1, 255);
        int n = g.hand[seat] > 0 ? g.hand[seat] : g.count[seat];
        float sr = Math.min(12 * dp, r.width() / 9.5f), gap = sr * 2.5f, gy = y + 92 * dp;
        for (int k = 0; k < n && k < 9; k++) {
            float sx = cx + (k % 3 - 1) * gap, sy = gy + (k / 3) * gap;
            if (sy + sr > r.bottom - 44 * dp) break;
            stone(c, sx, sy, sr, seat + 1, g.hand[seat] > 0 ? 255 : 150);
        }
        centerText(c, g.hand[seat] > 0 ? HAND[Math.min(9, g.hand[seat])] : LEFT[Math.min(9, g.count[seat])], cx, r.bottom - 22 * dp, 17 * dp, 0xFFDCE7F3);
    }
}
