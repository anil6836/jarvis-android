package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.Random;

/**
 * చదరంగం on the screen. The person here sits at the bottom (the board turns round when she plays black); a tap on
 * her piece lights it and puts green dots where it may go, a tap on a dot moves it (a short slide). Jarvis sits
 * across the board and says his moves in plain words, never squares ("నేను గుర్రాన్ని ముందుకు తెచ్చాను. ఇప్పుడు మీ
 * వంతు."); when she takes his piece he praises her, and check, mate and the draws get their own words. 💡 shows a
 * good move in purple and says it; "నువ్వే జరుపు" makes it for her.
 */
final class ChessGame extends Game {
    private final ChessRules r = new ChessRules();
    private int sel = -1, hintMove, checkSq = -1;
    private final boolean[] target = new boolean[64];
    /** A word about her last move, said at the start of Jarvis's answer ("బాగుంది."). */
    private String pending = "";
    // the move sliding now (and a rook when castling, a taken piece fading)
    private int aFrom = -1, aTo, aPiece, aCap, aCapSq, aRookFrom = -1, aRookTo, aRook;
    private final int[] takenW = new int[7], takenB = new int[7];
    // drawing (made once)
    private final RectF bd = new RectF(), strip = new RectF(), tmp = new RectF();
    private final Path[] shape = new Path[7], lines = new Path[7], dots = new Path[7];
    private final Path arrow = new Path();
    private final Paint pf = new Paint(Paint.ANTI_ALIAS_FLAG), ps = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final float OUTLINE = 6.6f; // (in the 100-box: about a third of it shows outside the body)
    private static final int LIGHT = 0xFFEBD2A8, DARK = 0xFFA87447, FRAME = 0xFF4A2E17;
    private static final int W_BODY = 0xFFFDF3DE, W_EDGE = 0xFF24170A, B_BODY = 0xFF2A2930, B_EDGE = 0xFFF1E8D4;
    private static final int LAST = 0x99FFD54F, SELECT = 0x8842A5F5, SELECT_EDGE = 0xFF1E88E5, DOT = 0xD82E9E44,
            HINT = 0x99A855E0, HINT_EDGE = 0xFF8E24AA, CHECK = 0xFFFF1744, GOLD = 0xFFFFC107;

    /**
     * The pieces in a 100 x 100 box, as simple outlines: M x y / L x y / Q cx cy x y / C … (as in SVG), Z closes,
     * O x y r: a circle, R left top right bottom radius: a rounded box. Index: piece type (1 బంటు … 6 రాజు).
     * Every outline goes clockwise, like the circles and boxes, so parts that overlap stay filled.
     */
    static final String[] SHAPE = {"",
            // బంటు: a round head on a collar and a flared body
            "O 50 27 13 R 37 41 63 48 3 M 41 48 L 59 48 Q 59 64 71 75 L 29 75 Q 41 64 41 48 Z R 22 75 78 88 5",
            // గుర్రం: a horse's head looking left
            "M 28 78 C 30 67 37 59 44 54 C 37 57 27 59 21 56 C 14 52 13 45 18 40 C 25 31 32 23 40 18 L 41 7 L 50 15 "
                    + "C 65 17 78 31 78 50 C 78 61 75 70 73 78 Z R 20 77 80 89 5",
            // ఒంటె: a ball on a mitre (with its slit), a collar and a flared body
            "O 50 11 6 M 50 17 C 66 27 69 45 60 56 L 40 56 C 31 45 34 27 50 17 Z R 35 55 65 62 3 "
                    + "M 41 62 L 59 62 Q 59 70 70 77 L 30 77 Q 41 70 41 62 Z R 20 77 80 89 5",
            // ఏనుగు: a tower with battlements
            "M 26 13 L 37 13 L 37 21 L 45 21 L 45 13 L 55 13 L 55 21 L 63 21 L 63 13 L 74 13 L 74 32 L 26 32 Z "
                    + "M 33 32 L 67 32 L 69 69 L 31 69 Z R 26 68 74 77 3 R 20 77 80 89 5",
            // మంత్రి: a crown of five points with balls
            "M 31 62 L 16 25 L 35 42 L 33 16 L 46 40 L 50 11 L 54 40 L 67 16 L 65 42 L 84 25 L 69 62 Z "
                    + "O 16 24 5 O 33 15 5 O 50 10 5 O 67 15 5 O 84 24 5 R 29 61 71 68 3 "
                    + "M 34 68 L 66 68 Q 67 73 74 77 L 26 77 Q 33 73 34 68 Z R 20 77 80 89 5",
            // రాజు: a cross on a two-lobed crown
            "R 45 2 55 36 2 R 37 9 63 18 2 M 31 60 C 15 45 24 24 50 33 C 76 24 85 45 69 60 Z R 29 57 71 65 3 "
                    + "M 34 65 L 66 65 Q 67 72 74 77 L 26 77 Q 33 72 34 65 Z R 20 77 80 89 5"};
    /** Lines drawn in the outline colour: where the parts meet, the bishop's slit, the horse's mane. */
    static final String[] LINES = {"",
            "M 39 48 L 61 48 M 31 75 L 69 75",
            "M 55 21 C 66 27 72 39 72 54 M 30 77.5 L 72 77.5",
            "M 44 43 L 56 30 M 37 55 L 63 55 M 37 62 L 63 62 M 31 77 L 69 77",
            "M 27 32 L 73 32 M 28 68 L 72 68 M 27 77 L 73 77",
            "M 31 61 L 69 61 M 31 68 L 69 68 M 27 77 L 73 77",
            "M 31 57 L 69 57 M 31 65 L 69 65 M 27 77 L 73 77"};
    /** Spots filled in the outline colour (the horse's eye and nostril). */
    static final String[] DOTS = {"", "", "O 37 30 3.4 O 21 47 2", "", "", "", ""};

    ChessGame(Context c) {
        super(c);
        for (int t = 1; t <= 6; t++) { shape[t] = path(SHAPE[t]); lines[t] = path(LINES[t]); dots[t] = path(DOTS[t]); }
        shape[0] = lines[0] = dots[0] = new Path();
        pf.setStyle(Paint.Style.FILL);
        pf.setStrokeJoin(Paint.Join.ROUND);
        ps.setStyle(Paint.Style.STROKE);
        ps.setStrokeJoin(Paint.Join.ROUND);
        ps.setStrokeCap(Paint.Cap.ROUND);
    }

    private Path path(String s) {
        Path p = new Path();
        String[] t = s.trim().isEmpty() ? new String[0] : s.trim().split("\\s+");
        int i = 0;
        try {
            while (i < t.length) {
                switch (t[i++]) {
                    case "M": p.moveTo(f(t[i]), f(t[i + 1])); i += 2; break;
                    case "L": p.lineTo(f(t[i]), f(t[i + 1])); i += 2; break;
                    case "Q": p.quadTo(f(t[i]), f(t[i + 1]), f(t[i + 2]), f(t[i + 3])); i += 4; break;
                    case "C": p.cubicTo(f(t[i]), f(t[i + 1]), f(t[i + 2]), f(t[i + 3]), f(t[i + 4]), f(t[i + 5])); i += 6; break;
                    case "Z": p.close(); break;
                    case "O": p.addCircle(f(t[i]), f(t[i + 1]), f(t[i + 2]), Path.Direction.CW); i += 3; break;
                    case "R":
                        tmp.set(f(t[i]), f(t[i + 1]), f(t[i + 2]), f(t[i + 3]));
                        p.addRoundRect(tmp, f(t[i + 4]), f(t[i + 4]), Path.Direction.CW);
                        i += 5;
                        break;
                    default: return p;
                }
            }
        } catch (RuntimeException ignored) {}
        return p;
    }

    private static float f(String s) { return Float.parseFloat(s); }

    @Override String id() { return "chess"; }
    @Override String title() { return "చదరంగం"; }
    @Override int[] players() { return new int[]{2, 2}; }
    @Override String[] options() { return new String[]{"⚪ నేను తెల్లవి (ముందు నేనే)", "⚫ నేను నల్లవి"}; }

    @Override void newGame(String[] who, int option) {
        String a = who != null && who.length > 0 ? who[0] : "her", b = who != null && who.length > 1 ? who[1] : "jarvis";
        roles = option == 1 ? new String[]{b, a} : new String[]{a, b};
        r.reset();
        clear();
        refresh();
        start();
        String h = her();
        if (hasJarvis()) {
            if (kind(0) == HERE) say(pick("రండి " + h + ", చదరంగం ఆడదాం! మీరు తెల్లవి, మీరే ముందు.",
                    "చదరంగం మొదలు! మీవి తెల్ల కాయలు, మీరే ముందు. ఏ కాయ మీద నొక్కినా, అది ఎక్కడికి వెళ్లొచ్చో పచ్చ చుక్కలతో చూపిస్తాను."), "happy", "point");
            else if (kind(1) == HERE) say(pick("రండి " + h + ", చదరంగం ఆడదాం! ఈసారి మీవి నల్ల కాయలు, తెల్లవి నావి. నేను ముందు పెడతాను.",
                    "సరే, మీరు నల్లవి! తెల్లవి ముందు కదులుతాయి కాబట్టి నేను మొదలుపెడతాను."), "happy", null);
        } else if (!far()) {
            say("సరే, ఇద్దరూ ఆడండి! " + name(0) + " తెల్లవి, " + name(1) + " నల్లవి. తెల్లవి ముందు. నేను చూస్తుంటాను.", "happy", null);
        }
    }

    @Override String save() { return r.save(); }

    @Override boolean load(String s) {
        if (!r.load(s)) return false;
        clear();
        refresh();
        return true;
    }

    @Override int turn() { return r.side; }

    private void clear() {
        sel = -1;
        hintMove = 0;
        pending = "";
        aFrom = -1;
        aRookFrom = -1;
        for (int i = 0; i < 64; i++) target[i] = false;
    }

    /** After the board changed: the king in check, the taken pieces. */
    private void refresh() {
        checkSq = r.inCheck() ? r.king[r.side] : -1;
        r.taken(0, takenW);
        r.taken(1, takenB);
        invalidate();
    }

    private String her() { return host != null ? host.her() : "అమ్మగారు"; }

    // ================================================================ touches

    @Override void tap(float x, float y) {
        int sq = squareAt(x, y), me = r.side;
        if (sq < 0) { unselect(me); return; }
        int p = r.b[sq];
        if (sel >= 0 && target[sq]) {
            int m = r.find(sel, sq);
            if (m != 0) { herMove(m, false); return; }
        }
        if (sel < 0 && hintMove != 0 && sq == ChessRules.to(hintMove) && (p == 0 || (p >> 3) != me) && r.isLegal(hintMove)) {
            herMove(hintMove, false); // (she tapped the purple square of the 💡)
            return;
        }
        if (p != 0 && (p >> 3) == me && sq != sel) { select(sq); return; }
        unselect(me);
        if (p != 0 && (p >> 3) != me && host != null) host.status("మీవి " + (me == 0 ? "తెల్ల" : "నల్ల") + " కాయలు, వాటి మీద నొక్కండి 👇");
    }

    private void select(int sq) {
        sel = sq;
        for (int i = 0; i < 64; i++) target[i] = false;
        int n = 0;
        for (int m : r.legal()) if (ChessRules.from(m) == sq) { target[ChessRules.to(m)] = true; n++; }
        String nm = ChessRules.NAME[r.b[sq] & 7];
        if (host != null) host.status(n > 0 ? nm + ": పచ్చ చుక్కల దగ్గరికి వెళ్లొచ్చు 👇"
                : nm + ": ఈ కాయ ఇప్పుడు కదలదు" + (r.inCheck() ? ". మీ రాజుకి చెక్! కాపాడండి" : ""));
        invalidate();
    }

    private void unselect(int me) {
        sel = -1;
        for (int i = 0; i < 64; i++) target[i] = false;
        if (host != null) host.status(yourTurn(me));
        invalidate();
    }

    @Override String yourTurn(int seat) {
        if (r.side == seat && r.inCheck()) return (sole(seat) ? "మీ" : owner(seat)) + " రాజుకి చెక్! కాపాడండి 👑";
        return (sole(seat) ? "మీ వంతు " : name(seat) + " వంతు ") + (seat == 0 ? "⚪" : "⚫") + " 👇";
    }

    /** A move of the person here (or the 💡 move Jarvis makes for her when she asks: byJarvis). */
    private void herMove(int m, boolean byJarvis) {
        int seat = r.side, piece = r.b[ChessRules.from(m)];
        int cap = ChessRules.flag(m) == ChessRules.F_EP ? ChessRules.P | (seat ^ 1) << 3 : r.b[ChessRules.to(m)];
        keep();
        r.play(m);
        sel = -1;
        hintMove = 0;
        for (int i = 0; i < 64; i++) target[i] = false;
        slide(m, piece, cap);
        refresh();
        String forHer = byJarvis ? forHerWords(seat, m, piece, cap) : "";
        if (end(seat, forHer)) return;
        if (byJarvis) { pending = ""; say(forHer, "happy", null); }
        else afterHer(seat, m, cap);
        moved();
    }

    /** "సరే, మీ గుర్రాన్ని ముందుకు జరిపాను." (the move he made for her). */
    private String forHerWords(int seat, int m, int piece, int cap) {
        int t = piece & 7, fl = ChessRules.flag(m);
        String s;
        if (fl == ChessRules.F_CASTLE) s = "సరే, మీ తరఫున కోట కట్టాను, మీ రాజు ఇప్పుడు భద్రం.";
        else if (cap != 0) s = "సరే, మీ " + ChessRules.WITH[t] + " " + owner(1 - seat) + " " + ChessRules.OBJ[cap & 7] + " పట్టుకున్నాను.";
        else s = "సరే, మీ " + ChessRules.OBJ[t] + " " + ChessRules.way(m, piece, whiteBottom()) + " జరిపాను.";
        if (ChessRules.promo(m) != 0) s += " బంటు చివరికి చేరింది, మంత్రి అయింది!";
        if (r.lastCheck) s += " " + owner(1 - seat) + " రాజుకి చెక్ కూడా!";
        return s;
    }

    /** What Jarvis says after her move: praise for a capture, a check, a promotion; else a word kept for his answer. */
    private void afterHer(int seat, int m, int cap) {
        int other = 1 - seat, ct = cap & 7;
        boolean promo = ChessRules.promo(m) != 0, chk = r.lastCheck;
        if (kind(other) == JARVIS) {
            String s = cap != 0 ? praise(ct) : "";
            if (promo) s += (s.isEmpty() ? "" : " ") + "అద్భుతం! మీ బంటు చివరికి చేరింది, మంత్రి అయింది!";
            if (chk) s += s.isEmpty() ? "అబ్బో, నా రాజుకి చెక్ పెట్టారు!" : " పైగా నా రాజుకి చెక్ కూడా!";
            if (!s.isEmpty()) {
                pending = "";
                say(s, cap != 0 && ct <= ChessRules.P ? "happy" : "surprised", ct >= ChessRules.R || promo ? "clap" : null);
            } else if (ChessRules.flag(m) == ChessRules.F_CASTLE) {
                pending = pick("మీరు కోట కట్టారు, మంచి పని!", "కోట కట్టారు, ఇప్పుడు మీ రాజు భద్రం.");
            } else {
                pending = rnd.nextInt(4) == 0 ? pick("బాగుంది.", "మంచి ఎత్తు.", "హ్మ్, ఆలోచించి పెట్టారు.", "సరే " + her() + ".") : "";
            }
            return;
        }
        // no Jarvis at the board (two people here, or Anil far): only the moments that matter
        if (ct >= ChessRules.N || promo || chk) say((cap != 0 && sole(seat) ? "భలే! " : "") + words(seat, m, r.lastPiece, cap, chk), cap != 0 || promo ? "surprised" : null, null);
    }

    private String praise(int t) {
        String h = her();
        switch (t) {
            case ChessRules.Q: return pick("అయ్యో, నా మంత్రిని పట్టేశారు! మీరు చాలా తెలివైనవారు!", "అబ్బా! నా మంత్రి పోయింది. భలే పట్టారు " + h + "!");
            case ChessRules.R: return pick("అయ్యో, నా ఏనుగుని పట్టేశారు! చాలా బాగా చూశారు.", "అరెరే, నా ఏనుగు పోయింది! మీరు భలే ఆడుతున్నారు " + h + ".");
            case ChessRules.B:
            case ChessRules.N: return pick("అయ్యో, నా " + ChessRules.OBJ[t] + " పట్టేశారు! బాగా గమనించారు.", "అబ్బా, నా " + ChessRules.NAME[t] + " పోయింది! బాగుంది " + h + ".");
            default: return pick("నా బంటుని పట్టుకున్నారు, బాగుంది!", "ఓహో, నా బంటు పోయింది!", "మంచిది, నా బంటుని పట్టేశారు.");
        }
    }

    // ================================================================ Jarvis

    @Override void jarvisMove() {
        if (done) return;
        final int level = host != null ? host.level() : 0, seat = r.side;
        final ChessRules copy = r.copy();
        final long seed = rnd.nextLong();
        think(() -> copy.choose(level, new Random(seed)), m -> {
            if (done || r.side != seat || kind(seat) != JARVIS) return;
            int mv = m == null ? 0 : m;
            if (!r.isLegal(mv)) { // (never stuck: any legal move)
                int[] l = r.legal();
                if (l.length == 0) return;
                mv = l[rnd.nextInt(l.length)];
            }
            int piece = r.b[ChessRules.from(mv)];
            int cap = ChessRules.flag(mv) == ChessRules.F_EP ? ChessRules.P | (seat ^ 1) << 3 : r.b[ChessRules.to(mv)];
            r.play(mv);
            sel = -1;
            hintMove = 0;
            for (int i = 0; i < 64; i++) target[i] = false;
            slide(mv, piece, cap);
            refresh();
            if (end(seat, words(seat, mv, piece, cap, false))) return;
            jarvisSays(seat, mv, piece, cap);
            moved();
        });
    }

    /** His move in one line: a word about hers, what he did (a capture with a little sorry), check, whose turn. */
    private void jarvisSays(int seat, int m, int piece, int cap) {
        String h = her();
        StringBuilder s = new StringBuilder();
        if (!pending.isEmpty() && cap == 0) s.append(pending).append(' '); // ("good move" doesn't go with taking her piece)
        pending = "";
        if (cap != 0) s.append((cap & 7) == ChessRules.Q ? pick("అయ్యో క్షమించండి " + h + ", ", "ఏమనుకోకండి, ") : pick("క్షమించండి " + h + ", ", "ఏమనుకోకండి, ", "హహ, ", ""));
        s.append(words(seat, m, piece, cap, false));
        if (r.lastCheck) s.append(' ').append(pick("జాగ్రత్త " + h + ", మీ రాజుకి చెక్!", "జాగ్రత్త, మీ రాజుకి చెక్!"))
                .append(' ').append(pick("రాజుని కాపాడండి.", "ఇప్పుడు రాజుని కాపాడుకోండి."));
        else s.append(' ').append(pick("ఇప్పుడు మీ వంతు.", "ఇక మీరు పెట్టండి.", "మీ వంతు " + h + ".", "ఇప్పుడు మీరు ఆలోచించండి."));
        boolean promo = ChessRules.promo(m) != 0;
        say(s.toString(), r.lastCheck ? "excited" : cap != 0 ? "laugh" : promo ? "proud" : null, r.lastCheck ? "point" : null);
    }

    /** After a move by seat: the end, said (before: the move in words, for a move she didn't make herself). */
    private boolean end(int seat, String before) {
        int st = r.state();
        if (st == ChessRules.PLAYING) return false;
        String h = her(), pre = before == null || before.isEmpty() ? "" : before + " ";
        if (st == ChessRules.MATE) {
            int w = seat;
            if (role(w).equals("jarvis")) finish(w, pre + pick("చెక్‌మేట్! ఈసారి నేను గెలిచాను. పర్వాలేదు " + h + ", మీరు బాగా ఆడారు. మళ్లీ ఆడదాం!",
                    "చెక్‌మేట్, మీ రాజుకి ఇక దారి లేదు. ఈ ఆట నాది, కానీ మీరు బాగా ఆడారు! మళ్లీ ఆడదాం."), "happy", null);
            else if (hasJarvis()) finish(w, pre + pick("చెక్‌మేట్! నా రాజుకి ఇక దారి లేదు. మీరు గెలిచారు " + h + "! చాలా బాగా ఆడారు!",
                    "అద్భుతం, చెక్‌మేట్! మీరే గెలిచారు, మీరు చాలా తెలివైనవారు!"), "excited", "clap");
            else if (sole(w)) finish(w, pre + "చెక్‌మేట్! మీరు గెలిచారు! చాలా బాగా ఆడారు!", "excited", "clap");
            else finish(w, pre + "చెక్‌మేట్! " + name(w) + " " + verb(w, "గెలిచారు", "గెలిచాను", "గెలిచాడు") + "! "
                    + (far() ? "మళ్లీ ఆడదాం." : "ఇద్దరూ చాలా బాగా ఆడారు."), "excited", "clap");
            return true;
        }
        String why;
        if (st == ChessRules.STALEMATE) {
            why = "రాజుకి చెక్ లేదు, కానీ కదలడానికి ఒక్క ఎత్తు కూడా లేదు. అందుకే ఆట సమానం!";
            if (role(1 - seat).equals("jarvis")) why += " ఇంకోసారి నా రాజుకి ఒక్క దారైనా వదిలి, తర్వాత చెక్‌మేట్ చేయండి, అప్పుడు మీరే గెలుస్తారు.";
        } else if (st == ChessRules.FIFTY) why = "యాభై ఎత్తులుగా ఏ కాయా పట్టుబడలేదు, ఏ బంటూ కదలలేదు. అందుకే ఆట సమానం!";
        else if (st == ChessRules.REPEAT) why = "ఒకే స్థితి మూడు సార్లు వచ్చింది. అందుకే ఆట సమానం!";
        else why = "ఇక ఎవరి దగ్గరా చెక్‌మేట్ చేసేంత కాయలు లేవు. ఆట సమానం!";
        finish(-1, pre + why + (hasJarvis() ? " మీరు బాగా ఆడారు " + h + "." : " ఇద్దరూ బాగా ఆడారు."), "happy", "thumb");
        return true;
    }

    // ================================================================ words

    /** The move in words for the seat that made it: "నేను గుర్రాన్ని ముందుకు తెచ్చాను." / "అబ్బాయి ఒంటెతో మీ బంటుని పట్టుకున్నాడు." */
    private String words(int seat, int m, int piece, int cap, boolean check) {
        int t = piece & 7, fl = ChessRules.flag(m);
        String s;
        if (fl == ChessRules.F_CASTLE) {
            s = who(seat) + " " + verb(seat, "కోట కట్టారు", "కోట కట్టాను", "కోట కట్టాడు") + ", " + own(seat) + " రాజు ఇప్పుడు భద్రం.";
        } else if (cap != 0) {
            s = who(seat) + " " + ChessRules.WITH[t] + " " + owner(1 - seat) + " " + ChessRules.OBJ[cap & 7]
                    + (fl == ChessRules.F_EP ? " దారిలోనే " : " ") + verb(seat, "పట్టుకున్నారు", "పట్టుకున్నాను", "పట్టుకున్నాడు") + ".";
        } else {
            String way = ChessRules.way(m, piece, whiteBottom());
            boolean bring = way.endsWith("ముందుకు") && (t == ChessRules.N || t == ChessRules.B || t == ChessRules.Q);
            s = who(seat) + " " + ChessRules.OBJ[t] + " " + way + " "
                    + (bring ? verb(seat, "తెచ్చారు", "తెచ్చాను", "తెచ్చాడు") : verb(seat, "జరిపారు", "జరిపాను", "జరిపాడు")) + ".";
        }
        if (ChessRules.promo(m) != 0) s += " బంటు చివరికి చేరింది, మంత్రి అయింది!";
        if (check) s += " " + owner(1 - seat) + " రాజుకి చెక్!";
        return s;
    }

    /** Whose pieces, as Jarvis says it: "నా", "మీ" (the only person here), "అబ్బాయి", "అమ్మగారి", "రెండో ఆటగాడి". */
    private String owner(int seat) {
        if (role(seat).equals("jarvis")) return "నా";
        if (sole(seat)) return "మీ";
        String n = name(seat);
        return n.endsWith("ు") ? n.substring(0, n.length() - 1) + "ి" : n;
    }

    /** One's own: "నా" (Jarvis), "మీ" (the person spoken to), "తన" (someone else). */
    private String own(int seat) {
        if (role(seat).equals("jarvis")) return "నా";
        return sole(seat) || (onPhone && role(seat).equals("son")) ? "మీ" : "తన";
    }

    @Override String lastWords() {
        if (r.lastMove == 0 || r.lastPiece == 0) return "";
        return words(r.lastPiece >> 3, r.lastMove, r.lastPiece, r.lastCap, r.lastCheck);
    }

    // ================================================================ 💡 and her words

    @Override boolean hint() {
        if (done) return false;
        if (busy()) return true;
        if (!r.hasLegal()) return false;
        final int seat = r.side;
        final ChessRules copy = r.copy();
        final long seed = rnd.nextLong();
        if (host != null) host.status("💡 చూస్తున్నాను…");
        think(() -> copy.hint(new Random(seed)), m -> {
            if (done || r.side != seat || m == null || !r.isLegal(m)) { turnNow(); return; }
            hintMove = m;
            sel = -1;
            for (int i = 0; i < 64; i++) target[i] = false;
            invalidate();
            say(hintWords(m), "happy", "point");
            if (host != null) host.status("💡 ఊదా గడి మీద నొక్కితే ఆ ఎత్తు పడుతుంది 👇");
        });
        return true;
    }

    /** "నేనైతే గుర్రాన్ని ముందుకు జరుపుతాను. ఊదా రంగులో చూపించాను." */
    private String hintWords(int m) {
        int from = ChessRules.from(m), p = r.b[from], t = p & 7, fl = ChessRules.flag(m);
        int cap = fl == ChessRules.F_EP ? ChessRules.P : r.b[ChessRules.to(m)] & 7;
        String s;
        if (fl == ChessRules.F_CASTLE) s = "నేనైతే కోట కడతాను, రాజు భద్రంగా ఉంటాడు.";
        else if (cap != 0) s = "నేనైతే " + ChessRules.WITH[t] + " ఆ " + ChessRules.OBJ[cap] + " పట్టుకుంటాను.";
        else s = "నేనైతే " + ChessRules.OBJ[t] + " " + ChessRules.way(m, p, whiteBottom()) + " జరుపుతాను.";
        if (ChessRules.promo(m) != 0) s += " బంటు మంత్రి అవుతుంది!";
        ChessRules c = r.copy();
        c.play(m);
        if (c.state() == ChessRules.MATE) s += " అలా చేస్తే చెక్‌మేట్, మీరే గెలుస్తారు!";
        else if (c.lastCheck) s += " అప్పుడు చెక్ కూడా అవుతుంది.";
        return s + " ఊదా రంగులో చూపించాను.";
    }

    @Override boolean heard(String t) {
        if (t == null || done || kind(r.side) != HERE) return false;
        if (!t.matches("(?s).*(నువ్వే జరుపు|నువ్వే పెట్టు|నువ్వే ఆడు|నువ్వే కదుపు|నువ్వే వేయి|నువ్వే చేయి).*")) return false;
        if (busy()) return true;
        final int seat = r.side;
        final ChessRules copy = r.copy();
        final long seed = rnd.nextLong();
        think(() -> copy.hint(new Random(seed)), m -> {
            if (done || r.side != seat || kind(seat) != HERE || m == null || !r.isLegal(m)) return;
            herMove(m, true);
        });
        return true;
    }

    // ================================================================ the slide

    private void slide(int m, int piece, int cap) {
        aFrom = ChessRules.from(m);
        aTo = ChessRules.to(m);
        aPiece = piece;
        aCap = cap;
        aCapSq = ChessRules.flag(m) == ChessRules.F_EP ? aTo + ((piece >> 3) == 0 ? -8 : 8) : aTo;
        if (ChessRules.flag(m) == ChessRules.F_CASTLE) {
            aRookFrom = ChessRules.rookFrom(aTo);
            aRookTo = ChessRules.rookTo(aTo);
            aRook = ChessRules.R | (piece & ChessRules.BLACK);
        } else aRookFrom = -1;
        animate(350, null);
    }

    // ================================================================ drawing

    /** The seat at the bottom: the only person at this screen, else white. */
    private int bottom() {
        int here = 0, n = 0;
        for (int i = 0; i < 2; i++) if (kind(i) == HERE) { here = i; n++; }
        return n == 1 ? here : 0;
    }

    private boolean whiteBottom() { return bottom() == 0; }

    private void layout() {
        float w = getWidth(), h = getHeight(), m = 10 * dp, gap = 14 * dp;
        if (w >= h) {
            float s = Math.max(8, Math.min(h - 2 * m, w - 2 * m - gap - 70 * dp));
            float sw = Math.max(0, Math.min(150 * dp, w - 2 * m - gap - s));
            float x0 = (w - (s + gap + sw)) / 2f, y0 = (h - s) / 2f;
            bd.set(x0, y0, x0 + s, y0 + s);
            strip.set(bd.right + gap, y0, bd.right + gap + sw, y0 + s);
        } else {
            float s = Math.max(8, Math.min(w - 2 * m, h - 2 * m - gap - 70 * dp));
            float sh = Math.max(0, Math.min(150 * dp, h - 2 * m - gap - s));
            float x0 = (w - s) / 2f, y0 = (h - (s + gap + sh)) / 2f;
            bd.set(x0, y0, x0 + s, y0 + s);
            strip.set(x0, bd.bottom + gap, x0 + s, bd.bottom + gap + sh);
        }
    }

    private float cx(int sq, boolean wb) { return bd.left + ((wb ? sq & 7 : 7 - (sq & 7)) + 0.5f) * bd.width() / 8f; }
    private float cy(int sq, boolean wb) { return bd.top + ((wb ? 7 - (sq >> 3) : sq >> 3) + 0.5f) * bd.height() / 8f; }

    private int squareAt(float x, float y) {
        layout();
        if (!bd.contains(x, y)) return -1;
        int col = Math.min(7, (int) ((x - bd.left) * 8 / bd.width())), row = Math.min(7, (int) ((y - bd.top) * 8 / bd.height()));
        boolean wb = whiteBottom();
        return (wb ? 7 - row : row) * 8 + (wb ? col : 7 - col);
    }

    @Override protected void draw2(Canvas c) {
        layout();
        boolean wb = whiteBottom();
        float cs = bd.width() / 8f, pc = cs * 0.9f;
        fill.setColor(FRAME);
        tmp.set(bd.left - 7 * dp, bd.top - 7 * dp, bd.right + 7 * dp, bd.bottom + 7 * dp);
        c.drawRoundRect(tmp, 10 * dp, 10 * dp, fill);
        for (int sq = 0; sq < 64; sq++) {
            float x = cx(sq, wb) - cs / 2f, y = cy(sq, wb) - cs / 2f;
            fill.setColor((((sq >> 3) + (sq & 7)) & 1) == 0 ? DARK : LIGHT);
            c.drawRect(x, y, x + cs, y + cs, fill);
        }
        if (r.lastMove != 0) { mark(c, ChessRules.from(r.lastMove), LAST, 0, wb, cs); mark(c, ChessRules.to(r.lastMove), LAST, 0, wb, cs); }
        if (hintMove != 0) { mark(c, ChessRules.from(hintMove), HINT, HINT_EDGE, wb, cs); mark(c, ChessRules.to(hintMove), HINT, HINT_EDGE, wb, cs); }
        if (checkSq >= 0) { // the king in check glows red
            float x = cx(checkSq, wb), y = cy(checkSq, wb);
            fill.setColor(0x55FF1744);
            c.drawRect(x - cs / 2f, y - cs / 2f, x + cs / 2f, y + cs / 2f, fill);
            fill.setColor(0x66FF1744);
            c.drawCircle(x, y, cs * 0.48f, fill);
            fill.setColor(0x88FF1744);
            c.drawCircle(x, y, cs * 0.36f, fill);
            line.setColor(CHECK);
            line.setStrokeWidth(3 * dp);
            c.drawRect(x - cs / 2f + 1.5f * dp, y - cs / 2f + 1.5f * dp, x + cs / 2f - 1.5f * dp, y + cs / 2f - 1.5f * dp, line);
        }
        if (sel >= 0) mark(c, sel, SELECT, SELECT_EDGE, wb, cs);

        boolean sliding = aFrom >= 0 && animating();
        float k = sliding ? anim() : 1f;
        for (int sq = 0; sq < 64; sq++) {
            int p = r.b[sq];
            if (p == 0 || sliding && (sq == aTo || aRookFrom >= 0 && sq == aRookTo)) continue;
            drawPiece(c, p, cx(sq, wb), cy(sq, wb), pc, 255);
        }
        if (sliding && aCap != 0) drawPiece(c, aCap, cx(aCapSq, wb), cy(aCapSq, wb), pc, (int) (255 * (1 - k)));

        for (int sq = 0; sq < 64; sq++) { // where the chosen piece may go: big dots, rings round what it can take
            if (!target[sq]) continue;
            float x = cx(sq, wb), y = cy(sq, wb);
            boolean takes = r.b[sq] != 0 || sel >= 0 && sq == r.ep && (r.b[sel] & 7) == ChessRules.P;
            if (takes) {
                line.setColor(DOT);
                line.setStrokeWidth(cs * 0.09f);
                c.drawCircle(x, y, cs * 0.43f, line);
            } else {
                fill.setColor(0x66000000);
                c.drawCircle(x, y + cs * 0.02f, cs * 0.19f, fill);
                fill.setColor(DOT);
                c.drawCircle(x, y, cs * 0.17f, fill);
            }
        }
        if (sliding) {
            if (aRookFrom >= 0) drawPiece(c, aRook, lerp(cx(aRookFrom, wb), cx(aRookTo, wb), k), lerp(cy(aRookFrom, wb), cy(aRookTo, wb), k), pc, 255);
            float lift = 1f + 0.1f * (float) Math.sin(Math.PI * k);
            drawPiece(c, aPiece, lerp(cx(aFrom, wb), cx(aTo, wb), k), lerp(cy(aFrom, wb), cy(aTo, wb), k), pc * lift, 255);
        }
        if (hintMove != 0) drawArrow(c, cx(ChessRules.from(hintMove), wb), cy(ChessRules.from(hintMove), wb),
                cx(ChessRules.to(hintMove), wb), cy(ChessRules.to(hintMove), wb), cs);
        drawStrip(c, wb);
    }

    private static float lerp(float a, float b, float k) { return a + (b - a) * k; }

    /** A square tinted (and edged when edge != 0). */
    private void mark(Canvas c, int sq, int color, int edge, boolean wb, float cs) {
        float x = cx(sq, wb), y = cy(sq, wb), h = cs / 2f;
        fill.setColor(color);
        c.drawRect(x - h, y - h, x + h, y + h, fill);
        if (edge != 0) {
            line.setColor(edge);
            line.setStrokeWidth(4 * dp);
            c.drawRect(x - h + 2 * dp, y - h + 2 * dp, x + h - 2 * dp, y + h - 2 * dp, line);
        }
    }

    /** A piece centred at (x, y), size across; alpha 0..255. */
    private void drawPiece(Canvas c, int p, float x, float y, float size, int alpha) {
        int t = p & 7;
        if (t < 1 || t > 6 || alpha <= 0) return;
        boolean white = p < ChessRules.BLACK;
        int edge = white ? W_EDGE : B_EDGE;
        c.save();
        c.translate(x - size / 2f, y - size / 2f);
        c.scale(size / 100f, size / 100f);
        if (alpha == 255) { // a soft shadow under it
            c.translate(2.5f, 3f);
            pf.setStyle(Paint.Style.FILL_AND_STROKE);
            pf.setStrokeWidth(OUTLINE);
            pf.setColor(0x40000000);
            c.drawPath(shape[t], pf);
            pf.setStyle(Paint.Style.FILL);
            c.translate(-2.5f, -3f);
        }
        // the outline is a wide stroke under the body: only the outer edge shows (parts that overlap make no lines)
        ps.setColor(edge);
        ps.setAlpha(alpha);
        ps.setStrokeWidth(OUTLINE);
        c.drawPath(shape[t], ps);
        pf.setColor(white ? W_BODY : B_BODY);
        pf.setAlpha(alpha);
        c.drawPath(shape[t], pf);
        if (!lines[t].isEmpty()) { ps.setStrokeWidth(3.4f); c.drawPath(lines[t], ps); }
        if (!dots[t].isEmpty()) { pf.setColor(edge); pf.setAlpha(alpha); c.drawPath(dots[t], pf); }
        c.restore();
    }

    private void drawArrow(Canvas c, float x0, float y0, float x1, float y1, float cs) {
        float dx = x1 - x0, dy = y1 - y0, len = (float) Math.hypot(dx, dy);
        if (len < 1) return;
        float ux = dx / len, uy = dy / len, head = cs * 0.38f, w = cs * 0.13f;
        float bx = x1 - ux * head, by = y1 - uy * head;
        line.setColor(0xCC8E24AA);
        line.setStrokeWidth(w);
        c.drawLine(x0 + ux * cs * 0.2f, y0 + uy * cs * 0.2f, bx, by, line);
        arrow.reset();
        arrow.moveTo(x1, y1);
        arrow.lineTo(bx - uy * head * 0.6f, by + ux * head * 0.6f);
        arrow.lineTo(bx + uy * head * 0.6f, by - ux * head * 0.6f);
        arrow.close();
        fill.setColor(0xCC8E24AA);
        c.drawPath(arrow, fill);
    }

    /** Beside the board: the pieces each side has taken (near the one who took them), and whose turn it is. */
    private void drawStrip(Canvas c, boolean wb) {
        if (strip.width() < 24 * dp || strip.height() < 24 * dp) return;
        int bottomColor = wb ? 0 : 1, topColor = 1 - bottomColor;
        int[] topTook = bottomColor == 0 ? takenW : takenB, bottomTook = topColor == 0 ? takenW : takenB;
        if (strip.height() >= strip.width()) {
            float mid = strip.centerY(), mr = Math.min(strip.width() * 0.26f, 24 * dp);
            drawTaken(c, bottomColor, topTook, strip.left, strip.top, strip.right, mid - mr - 12 * dp, false, 2);
            drawTaken(c, topColor, bottomTook, strip.left, mid + mr + 12 * dp, strip.right, strip.bottom, true, 2);
            if (!done) { // the king of the colour to move in a gold ring, an arrow toward that side
                float x = strip.centerX();
                boolean up = r.side == topColor;
                fill.setColor(0xFF1F3B5C);
                c.drawCircle(x, mid, mr, fill);
                drawPiece(c, ChessRules.K | r.side << 3, x, mid, mr * 1.6f, 255);
                line.setColor(GOLD);
                line.setStrokeWidth(3.5f * dp);
                c.drawCircle(x, mid, mr, line);
                float ty = up ? mid - mr - 5 * dp : mid + mr + 5 * dp, th = 9 * dp * (up ? -1 : 1);
                arrow.reset();
                arrow.moveTo(x, ty + th);
                arrow.lineTo(x - 9 * dp, ty);
                arrow.lineTo(x + 9 * dp, ty);
                arrow.close();
                fill.setColor(GOLD);
                c.drawPath(arrow, fill);
            }
        } else {
            float mid = strip.centerY();
            drawTaken(c, bottomColor, topTook, strip.left, strip.top, strip.right, mid, false, 8);
            drawTaken(c, topColor, bottomTook, strip.left, mid, strip.right, strip.bottom, true, 8);
        }
    }

    /** Taken pieces of one colour in a grid (queen first), from the top down, or from the bottom up. */
    private void drawTaken(Canvas c, int color, int[] n, float l, float t, float rt, float bm, boolean up, int cols) {
        int rows = (15 + cols - 1) / cols;
        float size = Math.min((rt - l) / cols, (bm - t) / rows);
        if (size < 8 * dp) return;
        float x0 = l + ((rt - l) - cols * size) / 2f;
        int i = 0;
        for (int type = ChessRules.Q; type >= ChessRules.P; type--) {
            for (int k = 0; k < n[type] && i < cols * rows; k++, i++) {
                float x = x0 + (i % cols + 0.5f) * size, y = up ? bm - (i / cols + 0.5f) * size : t + (i / cols + 0.5f) * size;
                drawPiece(c, type | color << 3, x, y, size * 0.95f, 255);
            }
        }
    }
}
