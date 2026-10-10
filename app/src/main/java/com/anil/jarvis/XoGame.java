package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;

/**
 * సున్నా-ఇంటూ on the screen (the smallest game: also the example the other games follow). A tap on a free square
 * plays it; Jarvis answers with his ⭕ and says where ("నేను సున్నాని పైన కుడి మూలలో పెట్టాను. ఇప్పుడు మీ వంతు.").
 */
final class XoGame extends Game {
    private final XoRules g = new XoRules();
    private int hintCell = -1;

    XoGame(Context c) { super(c); }

    @Override String id() { return "xo"; }
    @Override String title() { return "సున్నా-ఇంటూ"; }
    @Override String[] options() { return new String[]{"❌ నేను ముందు", "⭕ రెండో వారు ముందు"}; }

    @Override void newGame(String[] who, int option) {
        roles = option == 1 ? new String[]{who[1], who[0]} : new String[]{who[0], who[1]};
        for (int i = 0; i < 9; i++) g.cell[i] = 0;
        g.turn = 0;
        g.last = -1;
        hintCell = -1;
        start();
        if (kind(0) == HERE && hasJarvis()) say(pick("రండి " + host.her() + ", సున్నా-ఇంటూ ఆడదాం! మీరు ఇంటూ, నేను సున్నా. మీరే ముందు, ఏదైనా గడి మీద నొక్కండి.",
                "సరే, మొదలుపెడదాం! మూడు ఒకే వరుసలో పెట్టినవాళ్లు గెలుస్తారు. మీరు ముందు పెట్టండి."), "happy", "point");
    }

    @Override String save() { return g.save(); }
    @Override boolean load(String s) { hintCell = -1; return g.load(s); }
    @Override int turn() { return g.turn; }

    @Override void tap(float x, float y) {
        int i = cellAt(x, y);
        if (i < 0 || !g.legal(i)) return;
        int seat = g.turn;
        keep();
        boolean blocked = g.winningCell(1 - seat) == i; // (she stopped the other's line)
        g.play(i);
        hintCell = -1;
        animate(260, null);
        if (!end(seat) && blocked && kind(1 - seat) == JARVIS) say(pick("అయ్యో, నా వరుస ఆపేశారు!", "అబ్బా, భలే అడ్డుకున్నారు!"), "surprised", null);
        moved();
    }

    @Override void jarvisMove() {
        final int level = host.level();
        final XoRules copy = new XoRules();
        copy.load(g.save());
        think(() -> copy.choose(level, rnd), i -> {
            if (i == null || i < 0 || !g.legal(i)) return;
            int seat = g.turn;
            boolean wins = g.winningCell(seat) == i, blocks = g.winningCell(1 - seat) == i;
            g.play(i);
            animate(320, null);
            if (!end(seat)) {
                String said = "నేను " + (seat == 0 ? "ఇంటూని " : "సున్నాని ") + XoRules.where(i) + " పెట్టాను.";
                if (blocks && !wins) said = pick("మీ వరుస పూర్తి కాకుండా ఆపాను! ", "అబ్బో, మీరు గెలవబోతున్నారు, ఆపేశాను! ") + said;
                said += " " + pick("ఇప్పుడు మీ వంతు.", "మీరు పెట్టండి.", "ఇప్పుడు మీరు.");
                say(said, blocks ? "laugh" : null, null);
            }
            moved();
        });
    }

    /** After a move by seat: the end, said. True when the game is over. */
    private boolean end(int seat) {
        int w = g.winner();
        if (w == -2) return false;
        if (w == -1) { finish(-1, pick("ఎవరూ గెలవలేదు, సమానం! మీరు బాగా ఆడారు.", "టై అయింది! మళ్లీ ఆడదామా?"), "happy", "thumb"); return true; }
        if (role(w).equals("jarvis")) finish(w, pick("ఈసారి నేను గెలిచాను! పర్వాలేదు " + host.her() + ", మళ్లీ ఆడదాం.", "నా మూడు వరుసలో వచ్చాయి! ఇంకో ఆట ఆడదామా?"), "happy", null);
        else if (hasJarvis()) finish(w, pick("మీరు గెలిచారు " + host.her() + "! చాలా బాగా ఆడారు!", "అద్భుతం! మీ మూడు వరుసలో వచ్చాయి, మీరే గెలిచారు!"), "excited", "clap");
        else finish(w, name(w) + " " + verb(w, "గెలిచారు", "గెలిచాను", "గెలిచాడు") + "! చాలా బాగా ఆడారు.", "excited", "clap");
        return true;
    }

    @Override String lastWords() {
        if (g.last < 0) return "";
        int seat = 1 - g.turn; // (the seat that played last)
        return did(seat, "పెట్టారు", "పెట్టాను", "పెట్టాడు").replaceFirst(" ", " " + (seat == 0 ? "ఇంటూని " : "సున్నాని ") + XoRules.where(g.last) + " ") + ".";
    }

    @Override boolean hint() {
        int seat = g.turn;
        XoRules copy = new XoRules();
        copy.load(g.save());
        int i = copy.choose(2, rnd);
        if (i < 0) return false;
        hintCell = i;
        invalidate();
        say(pick("నేనైతే " + XoRules.where(i) + " పెడతాను.", XoRules.where(i) + " పెట్టి చూడండి.") + (g.winningCell(seat) == i ? " అక్కడ పెడితే మీరే గెలుస్తారు!" : ""), "happy", "point");
        return true;
    }

    // ---- drawing
    private RectF board() { return square(18 * dp); }

    private int cellAt(float x, float y) {
        RectF b = board();
        if (!b.contains(x, y)) return -1;
        int cx = (int) ((x - b.left) / (b.width() / 3)), cy = (int) ((y - b.top) / (b.height() / 3));
        return Math.min(2, cy) * 3 + Math.min(2, cx);
    }

    @Override protected void draw2(Canvas c) {
        RectF b = board();
        float s = b.width() / 3;
        fill.setColor(0xFF16304F);
        c.drawRoundRect(b, 24 * dp, 24 * dp, fill);
        for (int i = 0; i < 9; i++) {
            float x = b.left + (i % 3) * s, y = b.top + (i / 3) * s;
            if (i == g.last) { fill.setColor(0x33FFD166); c.drawRect(x + 6, y + 6, x + s - 6, y + s - 6, fill); }
            if (i == hintCell && g.cell[i] == 0) { fill.setColor(0x5530C46C); c.drawRect(x + 6, y + 6, x + s - 6, y + s - 6, fill); }
            int v = g.cell[i];
            float k = i == g.last ? anim() : 1f, m = s * (0.22f + 0.1f * (1 - k));
            if (v == 1) {
                line.setColor(COLORS[0]);
                line.setStrokeWidth(s * 0.1f);
                c.drawLine(x + m, y + m, x + s - m, y + s - m, line);
                c.drawLine(x + s - m, y + m, x + m, y + s - m, line);
            } else if (v == 2) {
                line.setColor(COLORS[3]);
                line.setStrokeWidth(s * 0.1f);
                c.drawCircle(x + s / 2, y + s / 2, s / 2 - m, line);
            }
        }
        line.setColor(0xFFDCE7F3);
        line.setStrokeWidth(6 * dp);
        for (int k = 1; k < 3; k++) {
            c.drawLine(b.left + k * s, b.top + 14 * dp, b.left + k * s, b.bottom - 14 * dp, line);
            c.drawLine(b.left + 14 * dp, b.top + k * s, b.right - 14 * dp, b.top + k * s, line);
        }
        int[] l = g.line();
        if (l != null) {
            line.setColor(0xFFFFD166);
            line.setStrokeWidth(10 * dp);
            c.drawLine(b.left + (l[0] % 3 + 0.5f) * s, b.top + (l[0] / 3 + 0.5f) * s, b.left + (l[2] % 3 + 0.5f) * s, b.top + (l[2] / 3 + 0.5f) * s, line);
        }
    }
}
