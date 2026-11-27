package com.anil.jarvis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 📦 A cardboard cutting sheet's shapes (Anil, 28 Nov: "F చేయి"): one workshop piece unfolded flat, in millimetres. A box → the
 * cross net (lid, back, bottom, front, two sides) with glue tabs; a cylinder → its wrap with a glue strip and two round ends with
 * tabs; a thin sheet → one rectangle; a thin disc → one circle. Cut lines, fold lines, glue tabs and each panel's name and size.
 * Pure Java (tested on a PC); the PDF page is drawn from it in CardboardPdf.
 */
final class NetPlan {
    static final int CUT = 0, FOLD = 1;

    /** A line: from (x1, y1) to (x2, y2), cut or fold. */
    static final class Seg {
        final double x1, y1, x2, y2; final int kind;
        Seg(double x1, double y1, double x2, double y2, int kind) { this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.kind = kind; }
        double len() { return Math.hypot(x2 - x1, y2 - y1); }
    }

    /** A circle: cut or fold. */
    static final class Ring {
        final double cx, cy, r; final int kind;
        Ring(double cx, double cy, double r, int kind) { this.cx = cx; this.cy = cy; this.r = r; this.kind = kind; }
    }

    /** A panel's words, at its middle. */
    static final class Label {
        final double x, y, room; final String name, size;
        Label(double x, double y, double room, String name, String size) { this.x = x; this.y = y; this.room = room; this.name = name; this.size = size; }
    }

    final List<Seg> segs = new ArrayList<>();
    final List<double[]> tabs = new ArrayList<>();   // glue tabs: polygons {x0, y0, x1, y1, ...} (filled grey)
    final List<Ring> rings = new ArrayList<>();
    final List<Label> labels = new ArrayList<>();
    double w, h;                                      // the whole sheet (mm)

    /** A tab's depth for panels this small (10 mm, less on tiny pieces). */
    static double tabFor(double... sides) {
        double m = Double.MAX_VALUE;
        for (double s : sides) m = Math.min(m, s);
        return Math.max(4, Math.min(10, m * 0.3));
    }

    static String cm(double mm) {
        double c = mm / 10.0;
        return Math.abs(c - Math.round(c)) < 0.05 ? String.valueOf(Math.round(c)) : String.format(Locale.ROOT, "%.1f", c);
    }

    // ---------------------------------------------------------------- the box

    private static final class Rect {
        final double x, y, w, h; final String name;
        Rect(double x, double y, double w, double h, String name) { this.x = x; this.y = y; this.w = w; this.h = h; this.name = name; }
    }

    /**
     * A closed box W × H × D (mm), laid out as a cross: [lid / back / bottom / front] down the middle, a side panel on each side of
     * the bottom. Glue tabs on the side panels' two free edges, on the lid's two sides and on the front's free edge.
     */
    static NetPlan box(double W, double H, double D) {
        NetPlan n = new NetPlan();
        double t = tabFor(W, H, D);
        double cx = H + t;                     // (room for the left side panel and its tabs)
        double y0 = t;                         // (room above the lid for nothing; kept even)
        List<Rect> R = new ArrayList<>();
        Rect lid = new Rect(cx, y0, W, D, "మూత");
        Rect back = new Rect(cx, y0 + D, W, H, "వెనక");
        Rect bottom = new Rect(cx, y0 + D + H, W, D, "అడుగు");
        Rect front = new Rect(cx, y0 + D + H + D, W, H, "ముందు");
        Rect left = new Rect(cx - H, y0 + D + H, H, D, "ఎడమ పక్క");
        Rect right = new Rect(cx + W, y0 + D + H, H, D, "కుడి పక్క");
        R.add(lid); R.add(back); R.add(bottom); R.add(front); R.add(left); R.add(right);
        // tabs: {base x1, y1, x2, y2, outward nx, ny}
        List<double[]> T = new ArrayList<>();
        T.add(new double[]{left.x, left.y, left.x + left.w, left.y, 0, -1});                       // left side, edge to the back
        T.add(new double[]{left.x, left.y + left.h, left.x + left.w, left.y + left.h, 0, 1});      // left side, edge to the front
        T.add(new double[]{right.x, right.y, right.x + right.w, right.y, 0, -1});
        T.add(new double[]{right.x, right.y + right.h, right.x + right.w, right.y + right.h, 0, 1});
        T.add(new double[]{lid.x, lid.y, lid.x, lid.y + lid.h, -1, 0});                           // lid's left side
        T.add(new double[]{lid.x + lid.w, lid.y, lid.x + lid.w, lid.y + lid.h, 1, 0});            // lid's right side
        T.add(new double[]{front.x, front.y + front.h, front.x + front.w, front.y + front.h, 0, 1}); // front's free edge, under the lid
        n.layout(R, T, t);
        n.w = cx + W + H + t;
        n.h = y0 + D + H + D + H + t;
        return n;
    }

    /** Panels + tabs → cut / fold lines: an edge another panel or a tab shares is a fold, every other edge is cut. */
    private void layout(List<Rect> R, List<double[]> T, double t) {
        for (Rect r : R) {
            double[][] edges = {{r.x, r.y, r.x + r.w, r.y}, {r.x + r.w, r.y, r.x + r.w, r.y + r.h}, {r.x, r.y + r.h, r.x + r.w, r.y + r.h}, {r.x, r.y, r.x, r.y + r.h}};
            for (double[] e : edges) {
                boolean fold = false;
                for (Rect o : R) if (o != r && shares(o, e)) { fold = true; break; }
                if (!fold) for (double[] b : T) if (same(e, b)) { fold = true; break; }
                if (fold) { if (!hasFold(e)) segs.add(new Seg(e[0], e[1], e[2], e[3], FOLD)); }
                else segs.add(new Seg(e[0], e[1], e[2], e[3], CUT));
            }
            labels.add(new Label(r.x + r.w / 2, r.y + r.h / 2, Math.min(r.w, r.h), r.name, cm(r.w) + " × " + cm(r.h)));
        }
        for (double[] b : T) tab(b[0], b[1], b[2], b[3], b[4], b[5], t);
    }

    /** A trapezoid tab on the base line, pointing out (its three outer edges are cut; the base is the fold drawn by its panel). */
    private void tab(double x1, double y1, double x2, double y2, double nx, double ny, double t) {
        double len = Math.hypot(x2 - x1, y2 - y1);
        if (len < 2) return;
        double ux = (x2 - x1) / len, uy = (y2 - y1) / len;
        double in = Math.min(t, len / 3);
        double ax = x1 + ux * in + nx * t, ay = y1 + uy * in + ny * t, bx = x2 - ux * in + nx * t, by = y2 - uy * in + ny * t;
        tabs.add(new double[]{x1, y1, ax, ay, bx, by, x2, y2});
        segs.add(new Seg(x1, y1, ax, ay, CUT));
        segs.add(new Seg(ax, ay, bx, by, CUT));
        segs.add(new Seg(bx, by, x2, y2, CUT));
    }

    private boolean hasFold(double[] e) {
        for (Seg s : segs) if (s.kind == FOLD && same(e, new double[]{s.x1, s.y1, s.x2, s.y2})) return true;
        return false;
    }

    private static boolean near(double a, double b) { return Math.abs(a - b) < 0.01; }

    /** The same line either way round. */
    static boolean same(double[] a, double[] b) {
        return (near(a[0], b[0]) && near(a[1], b[1]) && near(a[2], b[2]) && near(a[3], b[3]))
                || (near(a[0], b[2]) && near(a[1], b[3]) && near(a[2], b[0]) && near(a[3], b[1]));
    }

    /** Another panel has this whole edge on one of its own edges. */
    private static boolean shares(Rect o, double[] e) {
        double[][] edges = {{o.x, o.y, o.x + o.w, o.y}, {o.x + o.w, o.y, o.x + o.w, o.y + o.h}, {o.x, o.y + o.h, o.x + o.w, o.y + o.h}, {o.x, o.y, o.x, o.y + o.h}};
        for (double[] f : edges) if (same(e, f)) return true;
        return false;
    }

    // ---------------------------------------------------------------- the cylinder, a sheet, a disc

    /** A cylinder of radius r, height hh (mm): the wrap (2πr × hh) with a glue strip, and two ends with tabs round them. */
    static NetPlan cylinder(double r, double hh) {
        NetPlan n = new NetPlan();
        double t = tabFor(r * 2, hh);
        double wrap = 2 * Math.PI * r;
        double x0 = t, y0 = t;
        // the wrap and its glue strip on the right
        n.segs.add(new Seg(x0, y0, x0 + wrap, y0, CUT));
        n.segs.add(new Seg(x0, y0 + hh, x0 + wrap, y0 + hh, CUT));
        n.segs.add(new Seg(x0, y0, x0, y0 + hh, CUT));
        n.segs.add(new Seg(x0 + wrap, y0, x0 + wrap, y0 + hh, FOLD));
        n.tab(x0 + wrap, y0, x0 + wrap, y0 + hh, 1, 0, t);
        n.labels.add(new Label(x0 + wrap / 2, y0 + hh / 2, Math.min(wrap, hh), "చుట్టు", cm(wrap) + " × " + cm(hh)));
        // the two ends below it
        double cy = y0 + hh + t + r + t;
        for (int k = 0; k < 2; k++) {
            double cx = x0 + t + r + k * (2 * r + 3 * t);
            n.end(cx, cy, r, t, k == 0 ? "పై మూత" : "కింది మూత");
        }
        n.w = Math.max(x0 + wrap + t + t, x0 + 2 * (2 * r + 3 * t));
        n.h = cy + r + t + t;
        return n;
    }

    /** A round end: its edge folds; a ring of small tabs (cut between them) glues it into the wrap. */
    private void end(double cx, double cy, double r, double t, String name) {
        rings.add(new Ring(cx, cy, r, FOLD));
        rings.add(new Ring(cx, cy, r + t, CUT));
        int cuts = Math.max(8, Math.min(24, (int) Math.round(2 * Math.PI * r / 12)));
        for (int i = 0; i < cuts; i++) {
            double a = 2 * Math.PI * i / cuts;
            segs.add(new Seg(cx + r * Math.cos(a), cy + r * Math.sin(a), cx + (r + t) * Math.cos(a), cy + (r + t) * Math.sin(a), CUT));
        }
        labels.add(new Label(cx, cy, r * 2, name, "⌀ " + cm(2 * r)));
    }

    /** A flat sheet w × hh (mm): one rectangle. */
    static NetPlan sheet(double w, double hh) {
        NetPlan n = new NetPlan();
        double m = 4;
        n.segs.add(new Seg(m, m, m + w, m, CUT));
        n.segs.add(new Seg(m + w, m, m + w, m + hh, CUT));
        n.segs.add(new Seg(m, m + hh, m + w, m + hh, CUT));
        n.segs.add(new Seg(m, m, m, m + hh, CUT));
        n.labels.add(new Label(m + w / 2, m + hh / 2, Math.min(w, hh), "పలక", cm(w) + " × " + cm(hh)));
        n.w = w + 2 * m;
        n.h = hh + 2 * m;
        return n;
    }

    /** A thin disc of radius r (mm): one circle. */
    static NetPlan disc(double r) {
        NetPlan n = new NetPlan();
        double m = 4;
        n.rings.add(new Ring(m + r, m + r, r, CUT));
        n.labels.add(new Label(m + r, m + r, 2 * r, "గుండ్రం", "⌀ " + cm(2 * r)));
        n.w = n.h = 2 * r + 2 * m;
        return n;
    }

    // ---------------------------------------------------------------- a workshop piece → its sheet

    /** An A4 page's room for the drawing (mm): across, down. */
    static final double PAGE_W = 190, PAGE_H = 246;

    /** How big a w × h sheet can be drawn on the page (1 = its real size), turned either way. */
    static double pageScale(double w, double h) { return Math.min(1.0, Math.max(Math.min(PAGE_W / w, PAGE_H / h), Math.min(PAGE_W / h, PAGE_H / w))); }

    /** Drawn turned a quarter (it fits better on the page that way). */
    static boolean turned(double w, double h) { return Math.min(PAGE_W / h, PAGE_H / w) > Math.min(PAGE_W / w, PAGE_H / h) + 1e-9; }

    /** Cardboard is about this thick: a side thinner than it is a flat sheet, not a box. */
    static final double THIN_MM = 6;

    /**
     * One piece from the stage ({k: box, w, h, d} or {k: cyl, r, h}, in cm) → its sheet, the longest side across; null when it is
     * not something a sheet makes (or too small to cut: under 5 mm).
     */
    static NetPlan of(String kind, double a, double b, double c) {
        if ("box".equals(kind)) {
            double[] s = {a * 10, b * 10, c * 10};
            java.util.Arrays.sort(s);                  // s[0] smallest
            if (s[2] < 5) return null;
            if (s[0] <= THIN_MM) return s[1] < 5 ? null : sheet(s[2], s[1]);
            // the way round that needs the least shrinking on an A4 page (either way up), then the smallest sheet
            int[][] perm = {{2, 1, 0}, {2, 0, 1}, {1, 2, 0}, {1, 0, 2}, {0, 2, 1}, {0, 1, 2}};
            NetPlan best = null;
            double bestK = 0, bestArea = 0;
            for (int[] q : perm) {
                NetPlan n = box(s[q[0]], s[q[1]], s[q[2]]);
                double k = pageScale(n.w, n.h), area = n.w * n.h;
                if (best == null || k > bestK + 1e-6 || (Math.abs(k - bestK) < 1e-6 && area < bestArea - 1e-6)) { best = n; bestK = k; bestArea = area; }
            }
            return best;
        }
        if ("cyl".equals(kind)) {
            double r = a * 10, hh = b * 10;
            if (r < 2.5) return null;
            if (hh <= THIN_MM) return disc(r);
            return cylinder(r, hh);
        }
        return null;
    }
}
