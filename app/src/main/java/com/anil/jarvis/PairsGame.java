package com.anil.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * జతల ఆట on the screen: memory cards with the family's photos (from the photo frame, HomeFrame.thumbs), and drawn
 * symbols (mango, banana, flower…) when there are too few photos. She taps a card, it flips; a second card: a pair
 * stays up and she goes again, else both turn back after a moment. Alone it counts the tries; with Jarvis he takes
 * turns, slowly enough for her to watch (and remember) his cards too. The photos of a game are kept as JPEG files in
 * the cache (pairs/), so a game taken up later shows the same faces.
 */
final class PairsGame extends Game {
    private PairsRules g = new PairsRules();
    /** Pair → its photo (null: the symbol). */
    private Bitmap[] face = new Bitmap[0];
    /** The photos are being made / read (taps wait); a miss is being shown (both cards up). */
    private boolean loading, waiting, stopped;
    /** Changes on every new game / load: photo work for an older one is dropped. */
    private int deal;
    /** Cards turning over now (up: face up at the end). */
    private int flipA = -1, flipB = -1;
    private boolean flipUp;
    private int hintA = -1, hintB = -1;
    /** Jarvis's seat, or -1. */
    private int jarvis = -1;
    // layout (lay())
    private float cell, cardS, gx, gy, gap;
    private final RectF r = new RectF(), r2 = new RectF(), chip = new RectF();
    private final RectF unitIn = new RectF(7, 7, 93, 93), houseWall = new RectF(24, 48, 76, 86), houseDoor = new RectF(44, 64, 56, 86),
            houseWin = new RectF(60, 56, 71, 67), chimney = new RectF(63, 22, 71, 40), fishBody = new RectF(14, 30, 72, 70),
            birdBody = new RectF(18, 42, 72, 80);
    private final Paint pic = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path backPat = new Path(), tick = new Path(), mango = new Path(), mangoLeaf = new Path(), banana = new Path(),
            star = new Path(), moon = new Path(), heart = new Path(), leaf = new Path(), fishTail = new Path(), fishFin = new Path(),
            beak = new Path(), wing = new Path(), tail = new Path(), roof = new Path(), bell = new Path(), stemLeaf = new Path();

    /** The card colour behind each symbol. */
    private static final int[] SYM_BG = {0xFFE8F5E9, 0xFFE3F2FD, 0xFFFFF8E1, 0xFF283593, 0xFFB3E5FC, 0xFF1A237E,
            0xFFFCE4EC, 0xFFFFF3E0, 0xFFE0F7FA, 0xFFFFFDE7, 0xFFE8EAF6, 0xFFF3E5F5};

    PairsGame(Context c) {
        super(c);
        buildPaths();
    }

    @Override String id() { return "pairs"; }
    @Override String title() { return "జతల ఆట"; }
    @Override int[] players() { return new int[]{1, 2}; }
    @Override boolean farOk() { return false; }
    @Override String[] options() { return new String[]{"6 జతలు (సులభం)", "8 జతలు", "10 జతలు"}; }
    @Override boolean canUndo() { return false; }

    @Override void newGame(String[] who, int option) {
        roles = who.clone();
        findJarvis();
        int n = option == 2 ? 10 : option == 1 ? 8 : 6;
        long tag = System.currentTimeMillis();
        PairsRules d = new PairsRules();
        d.deal(n, roles.length, tag, rnd);
        g = d;
        face = new Bitmap[g.pairs];
        clearUi();
        deal++;
        loading = true; // (the status says so until the photos are ready)
        start();
        prepare(n, tag);
    }

    private void findJarvis() {
        jarvis = -1;
        for (int i = 0; i < roles.length; i++) if ("jarvis".equals(roles[i])) jarvis = i;
    }

    private void clearUi() {
        flipA = flipB = hintA = hintB = -1;
        waiting = false;
    }

    private static File dir(Context c) { return new File(c.getCacheDir(), "pairs"); }

    private static String file(long tag, int k) { return Long.toString(tag, 36) + "_" + k + ".jpg"; }

    /** Picks the photos off the main thread (and keeps them as files for later), then the game begins. */
    private void prepare(final int n, final long tag) {
        loading = true;
        if (host != null) host.status("కార్డులు సిద్ధం చేస్తున్నాను…");
        final Context ctx = getContext();
        final int px = (int) Math.max(200, Math.min(420, 230 * dp));
        final int my = deal;
        new Thread(() -> {
            List<Bitmap> got = null;
            try { got = HomeFrame.thumbs(ctx, n, px); } catch (Throwable ignored) {}
            final List<Bitmap> pics = got == null ? new ArrayList<>() : got;
            String pre = Long.toString(tag, 36) + "_";
            try {
                File d = dir(ctx);
                if (!d.isDirectory()) d.mkdirs();
                File[] old = d.listFiles();
                if (old != null) for (File f : old) if (!f.getName().startsWith(pre)) f.delete(); // (an older game's cards)
                for (int k = 0; k < pics.size(); k++) {
                    try (FileOutputStream o = new FileOutputStream(new File(d, file(tag, k)))) {
                        pics.get(k).compress(Bitmap.CompressFormat.JPEG, 88, o);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            main.post(() -> ready(my, pics));
        }, "pairs-cards").start();
    }

    private void ready(int my, List<Bitmap> pics) {
        if (my != deal || stopped) return;
        int k = 0;
        for (; k < pics.size() && k < g.pairs; k++) { face[k] = pics.get(k); g.photo[k] = true; }
        loading = false;
        invalidate();
        if (host == null) return;
        host.moved(); // (keeps the faces with the saved game)
        String her = host.her(), photos = k > 0 ? "ఇవి మన ఇంటి ఫోటోలు! " : "";
        if (g.seats == 1) say("రండి " + her + ", జతల ఆట! " + photos + "ఒకేలా ఉన్న రెండు కార్డులని కనుక్కోండి. ఏదైనా కార్డు మీద నొక్కండి.", "happy", "point");
        else if (jarvis >= 0) say("జతల ఆట ఆడదాం " + her + "! " + photos + "ఒక్కో వంతులో రెండు కార్డులు తిప్పాలి. ఒకేలా ఉంటే జత మీది, మళ్లీ తిప్పొచ్చు. మీరే ముందు!", "happy", "point");
        else say("జతల ఆట మొదలు! " + photos + "ఒకేలా ఉన్న రెండు కార్డులు కనుక్కున్నవారు మళ్లీ తిప్పొచ్చు. " + name(0) + " ముందు.", "happy", "point");
        turnNow();
    }

    @Override String save() { return g.save(); }

    @Override boolean load(String s) {
        PairsRules n = PairsRules.parse(s);
        if (n == null || n.seats != roles.length) return false;
        g = n;
        findJarvis();
        face = new Bitmap[g.pairs];
        clearUi();
        final int my = ++deal;
        boolean any = false;
        for (boolean p : g.photo) any |= p;
        loading = any;
        if (!any) return true;
        final Context ctx = getContext();
        final long tag = g.tag;
        final boolean[] want = g.photo.clone();
        new Thread(() -> {
            final Bitmap[] got = new Bitmap[want.length];
            for (int k = 0; k < want.length; k++) {
                if (!want[k]) continue;
                try { got[k] = BitmapFactory.decodeFile(new File(dir(ctx), file(tag, k)).getPath()); } catch (Throwable ignored) {}
            }
            main.post(() -> {
                if (my != deal || stopped) return;
                for (int k = 0; k < got.length && k < g.pairs; k++) {
                    if (!g.photo[k]) continue;
                    if (got[k] != null) face[k] = got[k];
                    else g.photo[k] = false; // (the file is gone: the pair's symbol instead)
                }
                loading = false;
                invalidate();
                if (host != null && !done && kind(g.turn) == HERE) host.status(yourTurn(g.turn));
            });
        }, "pairs-cards").start();
        return true;
    }

    @Override void stop() {
        super.stop();
        stopped = true;
        deal++;
    }

    @Override int turn() { return g.turn; }

    @Override String yourTurn(int seat) {
        if (loading) return "కార్డులు సిద్ధం చేస్తున్నాను…";
        String what = (g.first >= 0 ? "ఇంకో కార్డు తిప్పండి" : "ఒక కార్డు తిప్పండి") + " 👇";
        if (g.seats == 1) return what;
        return (sole(seat) ? "మీ వంతు" : name(seat) + " వంతు") + ": " + what;
    }

    private int level() { return host == null ? 0 : Math.max(0, Math.min(2, host.level())); }

    // ================================================================ turning cards

    @Override void tap(float x, float y) {
        if (loading || waiting) return;
        int c = cardAt(x, y);
        if (c < 0 || !g.canOpen(c)) return;
        turnUp(c, false, false);
    }

    /** Turns card c up (by her, or by Jarvis: then knew = he went by memory); a pair or a miss follows it. */
    private void turnUp(final int c, final boolean byJarvis, final boolean knew) {
        final int seat = g.turn;
        final int res = g.open(c);
        if (jarvis >= 0) g.see(c, PairsRules.MEMORY[level()], rnd);
        hintA = hintB = -1;
        flipA = c;
        flipB = -1;
        flipUp = true;
        animate(300, () -> {
            flipA = -1;
            if (res == 0) {
                if (!byJarvis && kind(seat) == HERE && host != null) host.status(yourTurn(seat));
            } else if (res == 1) {
                matched(seat, knew);
            } else {
                missed(seat);
            }
        });
        invalidate();
    }

    private void matched(int seat, boolean knew) {
        int p = g.card[g.lastB];
        boolean photo = p < face.length && face[p] != null;
        String found = photo ? pick("ఇది మన ఫోటోనే! జత దొరికింది!", "మన ఇంటి ఫోటో! జత దొరికింది!")
                : "రెండూ " + PairsRules.SYM_WORD[g.sym[p]] + " బొమ్మలే! జత దొరికింది!";
        if (g.over()) { end(); return; }
        if (seat == jarvis) {
            if (!knew) say(found + " " + pick("నేను మళ్లీ తిప్పుతాను.", "ఇంకోసారి నా వంతే."), "happy", null);
        } else if (jarvis >= 0) {
            say((g.lastRecalled ? pick("మీకు భలే గుర్తుంది " + host.her() + "! ", "అద్భుతం, ఆ కార్డు ఎక్కడుందో సరిగ్గా గుర్తుపెట్టుకున్నారు! ") : "")
                    + found + " " + pick("మీరు మళ్లీ తిప్పండి.", "ఇంకోసారి మీ వంతే."), "excited", g.lastRecalled ? "clap" : "thumb");
        } else if (g.seats == 1) {
            if (g.lastRecalled) say(pick("మీకు భలే గుర్తుంది! ", "భలే జ్ఞాపకశక్తి! ") + found, "excited", "clap");
            else if (rnd.nextInt(10) < 6) say(found, "happy", null); // (alone: not a word on every pair)
        } else {
            say(name(seat) + " జత కనుక్కున్నారు! " + (photo ? "ఇది మన ఫోటోనే! " : "") + "మళ్లీ తిప్పండి.", "happy", "thumb");
        }
        moved();
    }

    /** Not a pair: both stay up a moment (everyone sees them), turn back, and the turn passes. */
    private void missed(int seat) {
        if (seat == jarvis) say(pick("అయ్యో, జత కాలేదు. ఇప్పుడు మీ వంతు.", "అయ్యో, ఇవి వేరు వేరు! ఈ రెండూ గుర్తుపెట్టుకోండి, ఇప్పుడు మీ వంతు."), "sad", null);
        waiting = true;
        final int a = g.first, b = g.second;
        later(1300, () -> {
            flipA = a;
            flipB = b;
            flipUp = false;
            animate(300, () -> {
                flipA = flipB = -1;
                g.close();
                waiting = false;
                moved();
            });
            invalidate();
        });
    }

    private void end() {
        if (g.seats == 1) {
            finish(0, PairsRules.words(g.tries) + " ప్రయత్నాల్లో అన్నీ కనుక్కున్నారు! " + pick("చాలా బాగుంది!", "మీ జ్ఞాపకశక్తి అద్భుతం!", "భలే ఆడారు!"), "excited", "clap");
            return;
        }
        int w = g.winner();
        if (jarvis >= 0 && g.seats == 2) {
            int her = 1 - jarvis, hs = g.score[her], js = g.score[jarvis];
            if (w == her) finish(w, "మీకు " + PairsRules.pairsWord(hs) + ", నాకు " + bare(js) + "! మీరు గెలిచారు! "
                    + pick("మీ జ్ఞాపకశక్తి అద్భుతం " + host.her() + "!", "చాలా బాగా ఆడారు!"), "excited", "clap");
            else if (w == jarvis) finish(w, "నాకు " + PairsRules.pairsWord(js) + ", మీకు " + bare(hs) + ". ఈసారి నేను గెలిచాను! "
                    + pick("మీరు కూడా బాగా గుర్తుపెట్టుకున్నారు, మళ్లీ ఆడదాం.", "పర్వాలేదు " + host.her() + ", మళ్లీ ఆడదాం!"), "happy", null);
            else finish(-1, "మీకు " + PairsRules.pairsWord(hs) + ", నాకూ " + bare(js) + "! సమానం, ఇద్దరం బాగా ఆడాం.", "happy", "thumb");
            return;
        }
        StringBuilder b = new StringBuilder();
        for (int s = 0; s < g.seats; s++) b.append(name(s)).append(": ").append(PairsRules.pairsWord(g.score[s])).append(s < g.seats - 1 ? ", " : ". ");
        b.append(w >= 0 ? name(w) + " గెలిచారు! చాలా బాగా ఆడారు." : "సమానం! ఇద్దరూ బాగా ఆడారు.");
        finish(w, b.toString(), w >= 0 ? "excited" : "happy", w >= 0 ? "clap" : "thumb");
    }

    /** "మూడు" / "ఒక్కటీ లేదు" (the second number of "మీకు ఐదు జతలు, నాకు మూడు"). */
    private static String bare(int n) { return n == 0 ? "ఒక్కటీ లేదు" : PairsRules.words(n); }

    // ================================================================ Jarvis

    @Override void jarvisMove() {
        if (loading || waiting) { later(500, () -> { if (!done && kind(turn()) == JARVIS) jarvisMove(); }); return; }
        final PairsRules copy = g.copy();
        if (copy == null) return;
        think(() -> copy.jarvisPick(rnd), p -> {
            if (done || waiting) return;
            if (p == null || (g.first != p[0] && !g.canOpen(p[0]))) p = g.jarvisPick(rnd); // (never stuck)
            if (p == null) return;
            final int a = p[0], b = p[1];
            final boolean knew = p[2] == 1;
            if (host != null) host.status("Jarvis కార్డులు తిప్పుతున్నాడు… 👀");
            if (g.first == a) { later(400, () -> second(b, knew)); return; }
            turnUp(a, true, false);
            later(1200, () -> second(b, knew)); // (slowly: she sees his first card before the second)
        });
    }

    private void second(int b, boolean knew) {
        if (done || !g.canOpen(b)) return;
        if (knew) {
            int p = g.card[b];
            boolean photo = p < face.length && face[p] != null, more = g.found() + 1 < g.pairs;
            say(pick("ఆ కార్డు ఎక్కడ ఉందో నాకు గుర్తుంది!", "దీని జత ఎక్కడుందో నాకు గుర్తుంది!") + (photo ? " ఇది మన ఫోటోనే!" : "")
                    + " జత దొరికింది!" + (more ? " నేను మళ్లీ తిప్పుతాను." : ""), "happy", "point");
        }
        turnUp(b, true, knew);
    }

    @Override String lastWords() {
        int s = g.lastSeat;
        if (s < 0) return "";
        return who(s) + " రెండు కార్డులు " + verb(s, "తిప్పారు", "తిప్పాను", "తిప్పాడు") + (g.lastMatch ? ", జత దొరికింది." : ", జత కాలేదు.");
    }

    // ================================================================ hint

    @Override boolean hint() {
        if (loading || waiting || done || busy()) return false;
        int[] h = g.hint(rnd);
        if (h == null) return false;
        hintA = h[0];
        hintB = h.length > 1 ? h[1] : -1;
        invalidate();
        if (hintB >= 0) say(pick("ఈ రెండు కార్డులు ఒకే జత! ఇంతకు ముందు చూశాం. వెలుగుతున్న కార్డులు తిప్పండి.",
                "గుర్తుందా? వెలుగుతున్న ఈ రెండు కార్డులు ఒకటే!"), "happy", "point");
        else if (g.first >= 0 && g.partner(g.first) == hintA && g.seen[hintA]) say("దాని జత " + g.where(hintA) + "! అది తిప్పండి.", "happy", "point");
        else say(pick("ఇంకా ఎవరూ చూడని కార్డు తిప్పి చూడండి: " + g.where(hintA) + ".", g.where(hintA) + " తిప్పి చూడండి."), "happy", "point");
        return true;
    }

    /** "నువ్వే తిప్పు": Jarvis turns the hint card for her. */
    @Override boolean heard(String t) {
        if (done || loading || waiting || busy() || kind(g.turn) != HERE) return false;
        if (!t.matches("(?s).*నువ్వే.{0,8}(తిప్పు|తిప్పి|జరుపు|పెట్టు|ఆడు|చూపించు|చెయ్యి|చేయి).*")) return false;
        int[] h = g.hint(rnd);
        if (h == null) return false;
        say(pick("సరే, ఇదిగో, ఈ కార్డు తిప్పుతాను.", "సరే, మీ కోసం ఇది తిప్పాను."), "happy", null);
        turnUp(h[0], false, false);
        return true;
    }

    // ================================================================ drawing

    private void lay() {
        float w = getWidth(), h = getHeight(), m = 10 * dp, top = 58 * dp;
        cell = Math.max(1, Math.min((w - 2 * m) / g.cols, (h - top - m) / g.rows));
        gap = Math.max(6 * dp, cell * 0.06f);
        cardS = cell - gap;
        gx = (w - cell * g.cols) / 2f + gap / 2f;
        gy = top + (h - top - m - cell * g.rows) / 2f + gap / 2f;
    }

    private int cardAt(float x, float y) {
        lay();
        int col = (int) Math.floor((x - gx + gap / 2f) / cell), row = (int) Math.floor((y - gy + gap / 2f) / cell);
        if (col < 0 || row < 0 || col >= g.cols || row >= g.rows) return -1;
        return row * g.cols + col;
    }

    private static int seatColor(int s) { return s == 0 ? COLORS[1] : s == 1 ? COLORS[3] : COLORS[2]; }

    @Override protected void draw2(Canvas c) {
        if (getWidth() <= 0 || g.card.length == 0) return;
        lay();
        scores(c);
        float t = animRaw();
        for (int i = 0; i < g.card.length; i++) {
            float l = gx + (i % g.cols) * cell, tp = gy + (i / g.cols) * cell;
            if (i == flipA || i == flipB) {
                float sx = Math.abs((float) Math.cos(Math.PI * t));
                boolean upNow = flipUp ? t >= 0.5f : t < 0.5f;
                c.save();
                c.scale(Math.max(0.02f, sx), 1f + 0.06f * (float) Math.sin(Math.PI * t), l + cardS / 2f, tp + cardS / 2f);
                card(c, i, l, tp, upNow);
                c.restore();
            } else {
                card(c, i, l, tp, g.up(i));
            }
        }
    }

    /** The pairs found (with Jarvis / two people) or the tries (alone), above the cards. */
    private void scores(Canvas c) {
        float w = getWidth(), cy = 30 * dp;
        if (g.seats == 1) {
            centerText(c, "ప్రయత్నాలు: " + g.tries + "        జతలు: " + g.found() + " / " + g.pairs, w / 2f, cy, 22 * dp, 0xFFFFFFFF);
            return;
        }
        float cw = Math.min(300 * dp, (w - 20 * dp) / g.seats), x0 = (w - cw * g.seats) / 2f;
        for (int s = 0; s < g.seats; s++) {
            chip.set(x0 + s * cw + 8 * dp, cy - 22 * dp, x0 + (s + 1) * cw - 8 * dp, cy + 22 * dp);
            boolean now = !done && g.turn == s;
            fill.setColor(now ? 0xFF24456E : 0xFF172C47);
            c.drawRoundRect(chip, 22 * dp, 22 * dp, fill);
            if (now) {
                line.setColor(0xFFFFD166);
                line.setStrokeWidth(3 * dp);
                c.drawRoundRect(chip, 22 * dp, 22 * dp, line);
            }
            fill.setColor(seatColor(s));
            c.drawCircle(chip.left + 24 * dp, cy, 10 * dp, fill);
            String nm = s == jarvis ? "Jarvis" : sole(s) ? "మీరు" : name(s);
            centerText(c, nm + ": " + g.score[s] + " జతలు", chip.centerX() + 12 * dp, cy, 20 * dp, 0xFFFFFFFF);
        }
    }

    /** One card at (l, t): its back, or its face (photo / symbol), with the finder's colour and a tick when found. */
    private void card(Canvas c, int i, float l, float t, boolean up) {
        float s = cardS, rad = s * 0.09f;
        r.set(l, t, l + s, t + s);
        fill.setColor(0x55000000);
        r2.set(l + s * 0.02f, t + s * 0.035f, l + s * 1.02f, t + s * 1.035f);
        c.drawRoundRect(r2, rad, rad, fill);
        int p = g.card[i];
        if (!up) {
            back(c, l, t, s, rad);
        } else {
            Bitmap b = p < face.length ? face[p] : null;
            if (b != null) {
                fill.setColor(0xFFFFFFFF);
                c.drawRoundRect(r, rad, rad, fill);
                float in = s * 0.06f;
                r2.set(l + in, t + in, l + s - in, t + s - in);
                c.drawBitmap(b, null, r2, pic);
            } else if (loading && g.photo[p]) {
                fill.setColor(0xFFFFFFFF);
                c.drawRoundRect(r, rad, rad, fill);
                centerText(c, "…", l + s / 2f, t + s / 2f, s * 0.3f, 0xFF888888);
            } else {
                fill.setColor(SYM_BG[g.sym[p]]);
                c.drawRoundRect(r, rad, rad, fill);
                float in = s * 0.1f;
                symbol(c, g.sym[p], l + in, t + in, s - 2 * in);
            }
        }
        int o = g.owner[i];
        if (up && o >= 0) {
            line.setColor(seatColor(o));
            line.setStrokeWidth(s * 0.045f);
            c.drawRoundRect(r, rad, rad, line);
            float br = s * 0.11f, bx = l + s - br * 0.9f, by = t + br * 0.9f;
            fill.setColor(seatColor(o));
            c.drawCircle(bx, by, br, fill);
            c.save();
            c.translate(bx - br, by - br);
            c.scale(br / 50f, br / 50f);
            line.setColor(0xFFFFFFFF);
            line.setStrokeWidth(13f);
            c.drawPath(tick, line);
            c.restore();
        } else if (up && (i == g.first || i == g.second)) {
            line.setColor(0xFFFFD166);
            line.setStrokeWidth(s * 0.05f);
            c.drawRoundRect(r, rad, rad, line);
        } else if (!up && (i == hintA || i == hintB)) {
            line.setColor(0xFFC86BFA);
            line.setStrokeWidth(s * 0.065f);
            c.drawRoundRect(r, rad, rad, line);
        }
    }

    /** The back: calm blue, a diamond pattern, a thin frame and a small gold "J". */
    private void back(Canvas c, float l, float t, float s, float rad) {
        fill.setColor(0xFF2D5D8C);
        c.drawRoundRect(r, rad, rad, fill);
        c.save();
        c.translate(l, t);
        c.scale(s / 100f, s / 100f);
        fill.setColor(0x38FFFFFF);
        c.drawPath(backPat, fill);
        line.setColor(0x99FFFFFF);
        line.setStrokeWidth(1.8f);
        c.drawRoundRect(unitIn, 7, 7, line);
        fill.setColor(0xFFF2B544);
        c.drawCircle(50, 50, 15, fill);
        line.setColor(0xFF1B3A5C);
        line.setStrokeWidth(2.2f);
        c.drawCircle(50, 50, 15, line);
        c.restore();
        centerText(c, "J", l + s / 2f, t + s / 2f, s * 0.2f, 0xFF16304F);
    }

    // ================================================================ the symbols (drawn in a 100 × 100 box)

    private void buildPaths() {
        for (int yi = 0; yi < 7; yi++) {
            for (int xi = 0; xi < 7; xi++) {
                float x = 14 + xi * 12, y = 14 + yi * 12;
                if (Math.hypot(x - 50, y - 50) < 24) continue;
                backPat.moveTo(x, y - 4);
                backPat.lineTo(x + 4, y);
                backPat.lineTo(x, y + 4);
                backPat.lineTo(x - 4, y);
                backPat.close();
            }
        }
        tick.moveTo(27, 52);
        tick.lineTo(43, 68);
        tick.lineTo(74, 34);

        // a mango: a fat kidney, round at the top, with the little hook low on one side
        mango.moveTo(34, 26);
        mango.cubicTo(52, 12, 88, 24, 86, 56);
        mango.cubicTo(84, 82, 60, 94, 40, 88);
        mango.cubicTo(26, 84, 20, 74, 26, 66);
        mango.cubicTo(32, 58, 18, 48, 22, 38);
        mango.cubicTo(24, 32, 28, 29, 34, 26);
        mango.close();
        mangoLeaf.moveTo(32, 15);
        mangoLeaf.quadTo(46, 0, 68, 6);
        mangoLeaf.quadTo(52, 22, 32, 15);
        mangoLeaf.close();

        banana.moveTo(12, 30);
        banana.cubicTo(14, 72, 54, 94, 90, 66);
        banana.lineTo(91, 57);
        banana.cubicTo(62, 74, 34, 62, 28, 27);
        banana.close();

        for (int k = 0; k < 10; k++) {
            double a = -Math.PI / 2 + k * Math.PI / 5;
            float rr = k % 2 == 0 ? 42 : 18, x = 50 + rr * (float) Math.cos(a), y = 54 + rr * (float) Math.sin(a);
            if (k == 0) star.moveTo(x, y); else star.lineTo(x, y);
        }
        star.close();

        Path full = new Path(), bite = new Path();
        full.addCircle(46, 52, 32, Path.Direction.CW);
        bite.addCircle(64, 40, 28, Path.Direction.CW);
        moon.op(full, bite, Path.Op.DIFFERENCE);

        heart.moveTo(50, 86);
        heart.cubicTo(16, 62, 8, 36, 26, 24);
        heart.cubicTo(38, 16, 50, 24, 50, 36);
        heart.cubicTo(50, 24, 62, 16, 74, 24);
        heart.cubicTo(92, 36, 84, 62, 50, 86);
        heart.close();

        leaf.moveTo(16, 84);
        leaf.cubicTo(12, 44, 44, 12, 88, 14);
        leaf.cubicTo(88, 56, 58, 88, 16, 84);
        leaf.close();

        fishTail.moveTo(66, 50);
        fishTail.lineTo(91, 30);
        fishTail.lineTo(85, 50);
        fishTail.lineTo(91, 70);
        fishTail.close();
        fishFin.moveTo(38, 52);
        fishFin.lineTo(52, 64);
        fishFin.lineTo(34, 64);
        fishFin.close();

        beak.moveTo(80, 33);
        beak.quadTo(96, 36, 87, 52);
        beak.lineTo(80, 44);
        beak.close();
        wing.moveTo(28, 56);
        wing.quadTo(48, 44, 62, 58);
        wing.quadTo(46, 74, 28, 56);
        wing.close();
        tail.moveTo(24, 66);
        tail.lineTo(6, 86);
        tail.lineTo(14, 90);
        tail.lineTo(32, 72);
        tail.close();

        roof.moveTo(13, 51);
        roof.lineTo(50, 16);
        roof.lineTo(87, 51);
        roof.close();

        bell.moveTo(50, 18);
        bell.cubicTo(70, 18, 74, 38, 74, 54);
        bell.lineTo(82, 72);
        bell.lineTo(18, 72);
        bell.lineTo(26, 54);
        bell.cubicTo(26, 38, 30, 18, 50, 18);
        bell.close();

        stemLeaf.moveTo(50, 80);
        stemLeaf.quadTo(64, 66, 76, 72);
        stemLeaf.quadTo(64, 86, 50, 80);
        stemLeaf.close();
    }

    private void outline(Canvas c, Path p, int color, float w) {
        line.setColor(color);
        line.setStrokeWidth(w);
        c.drawPath(p, line);
    }

    /** Symbol s (0 mango … 11 bell) in the square (l, t, size). */
    private void symbol(Canvas c, int s, float l, float t, float size) {
        c.save();
        c.translate(l, t);
        c.scale(size / 100f, size / 100f);
        switch (s) {
            case 0: // mango
                fill.setColor(0xFFFFB300);
                c.drawPath(mango, fill);
                fill.setColor(0x33FF5722);
                c.drawCircle(64, 50, 21, fill);
                fill.setColor(0x2E7CB342);
                c.drawCircle(40, 38, 12, fill);
                outline(c, mango, 0xFFB26A00, 3);
                line.setColor(0xFF6D4C41);
                line.setStrokeWidth(4);
                c.drawLine(34, 26, 32, 15, line);
                fill.setColor(0xFF2E7D32);
                c.drawPath(mangoLeaf, fill);
                fill.setColor(0x88FFFFFF);
                c.drawCircle(70, 36, 4, fill);
                break;
            case 1: // banana
                fill.setColor(0xFFFFE135);
                c.drawPath(banana, fill);
                outline(c, banana, 0xFF9E7C00, 3);
                line.setColor(0xFF6D4C41);
                line.setStrokeWidth(6);
                c.drawLine(19, 29, 21, 17, line);
                fill.setColor(0xFF5D4037);
                c.drawCircle(90, 62, 4, fill);
                break;
            case 2: // flower
                line.setColor(0xFF2E7D32);
                line.setStrokeWidth(5);
                c.drawLine(50, 56, 50, 95, line);
                fill.setColor(0xFF43A047);
                c.drawPath(stemLeaf, fill);
                fill.setColor(0xFFEC407A);
                for (int k = 0; k < 6; k++) {
                    double a = k * Math.PI / 3;
                    c.drawCircle(50 + 19 * (float) Math.cos(a), 40 + 19 * (float) Math.sin(a), 14, fill);
                }
                fill.setColor(0xFFFFCA28);
                c.drawCircle(50, 40, 12, fill);
                line.setColor(0xFFF57F17);
                line.setStrokeWidth(2.5f);
                c.drawCircle(50, 40, 12, line);
                break;
            case 3: // star
                fill.setColor(0xFFFFC107);
                c.drawPath(star, fill);
                outline(c, star, 0xFFFFE082, 3);
                break;
            case 4: // sun
                line.setColor(0xFFFF8F00);
                line.setStrokeWidth(6);
                for (int k = 0; k < 12; k++) {
                    double a = k * Math.PI / 6;
                    float cx = (float) Math.cos(a), sy = (float) Math.sin(a);
                    c.drawLine(50 + 29 * cx, 50 + 29 * sy, 50 + 44 * cx, 50 + 44 * sy, line);
                }
                fill.setColor(0xFFFFB300);
                c.drawCircle(50, 50, 22, fill);
                line.setColor(0xFFE65100);
                line.setStrokeWidth(3);
                c.drawCircle(50, 50, 22, line);
                break;
            case 5: // moon
                fill.setColor(0xFFFFF59D);
                c.drawPath(moon, fill);
                outline(c, moon, 0xFFFBC02D, 2.5f);
                fill.setColor(0xFFFFFFFF);
                c.drawCircle(80, 72, 3, fill);
                c.drawCircle(84, 22, 2.5f, fill);
                c.drawCircle(70, 88, 2, fill);
                break;
            case 6: // heart
                fill.setColor(0xFFE53935);
                c.drawPath(heart, fill);
                outline(c, heart, 0xFFB71C1C, 3);
                fill.setColor(0x99FFFFFF);
                c.drawCircle(33, 36, 5, fill);
                break;
            case 7: // leaf
                fill.setColor(0xFF43A047);
                c.drawPath(leaf, fill);
                outline(c, leaf, 0xFF1B5E20, 3);
                line.setColor(0xFF1B5E20);
                line.setStrokeWidth(3);
                c.drawLine(18, 82, 78, 24, line);
                line.setStrokeWidth(2);
                c.drawLine(40, 60, 37, 44, line);
                c.drawLine(40, 60, 56, 63, line);
                c.drawLine(58, 42, 56, 29, line);
                c.drawLine(58, 42, 72, 45, line);
                break;
            case 8: // fish
                fill.setColor(0xFF1565C0);
                c.drawPath(fishTail, fill);
                fill.setColor(0xFF1E88E5);
                c.drawOval(fishBody, fill);
                line.setColor(0xFF0D47A1);
                line.setStrokeWidth(2.5f);
                c.drawOval(fishBody, line);
                fill.setColor(0xFF90CAF9);
                c.drawPath(fishFin, fill);
                fill.setColor(0xFFFFFFFF);
                c.drawCircle(28, 46, 6, fill);
                fill.setColor(0xFF000000);
                c.drawCircle(29, 46, 3, fill);
                line.setColor(0xFF64B5F6);
                line.setStrokeWidth(2);
                c.drawCircle(12, 22, 3, line);
                c.drawCircle(18, 11, 4, line);
                break;
            case 9: // bird (a green parrot)
                line.setColor(0xFF6D4C41);
                line.setStrokeWidth(3);
                c.drawLine(44, 78, 42, 92, line);
                c.drawLine(54, 78, 56, 92, line);
                fill.setColor(0xFF1B5E20);
                c.drawPath(tail, fill);
                fill.setColor(0xFF43A047);
                c.drawOval(birdBody, fill);
                c.drawCircle(68, 38, 16, fill);
                fill.setColor(0xFFE53935);
                c.drawPath(beak, fill);
                fill.setColor(0xFF2E7D32);
                c.drawPath(wing, fill);
                fill.setColor(0xFFFFFFFF);
                c.drawCircle(70, 34, 4.5f, fill);
                fill.setColor(0xFF000000);
                c.drawCircle(71, 34, 2.2f, fill);
                break;
            case 10: // house
                fill.setColor(0xFF8D6E63);
                c.drawRect(chimney, fill);
                fill.setColor(0xFFFFCC80);
                c.drawRect(houseWall, fill);
                line.setColor(0xFF8D6E63);
                line.setStrokeWidth(2.5f);
                c.drawRect(houseWall, line);
                fill.setColor(0xFFD84315);
                c.drawPath(roof, fill);
                outline(c, roof, 0xFF8D2A0E, 2.5f);
                fill.setColor(0xFF6D4C41);
                c.drawRect(houseDoor, fill);
                fill.setColor(0xFF81D4FA);
                c.drawRect(houseWin, fill);
                line.setColor(0xFF5D4037);
                line.setStrokeWidth(2);
                c.drawRect(houseWin, line);
                c.drawLine(65.5f, 56, 65.5f, 67, line);
                c.drawLine(60, 61.5f, 71, 61.5f, line);
                break;
            default: // bell
                line.setColor(0xFFB26A00);
                line.setStrokeWidth(3);
                c.drawCircle(50, 13, 5, line);
                fill.setColor(0xFF8D6E00);
                c.drawCircle(50, 79, 7, fill);
                fill.setColor(0xFFFFB300);
                c.drawPath(bell, fill);
                outline(c, bell, 0xFFB26A00, 3);
                line.setStrokeWidth(2.5f);
                c.drawLine(27, 60, 73, 60, line);
                fill.setColor(0x88FFFFFF);
                c.drawCircle(40, 36, 4, fill);
                break;
        }
        c.restore();
    }
}
