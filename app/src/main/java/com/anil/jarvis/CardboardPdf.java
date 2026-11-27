package com.anil.jarvis;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 📦 The workshop model's cardboard cutting sheet (Anil, 28 Nov: "F చేయి"): A4 pages, a cover with how to print, cut, fold and glue
 * and the list of pieces, then one page a piece with its net (NetPlan) — at its real size when it fits the page (with a 5 cm
 * check line), else smaller with every size written on it. Our own drawing, ₹0, no AI; the sizes are the AI's design.
 */
final class CardboardPdf {
    private CardboardPdf() {}

    static final float PT = 72f / 25.4f;              // points in a millimetre
    private static final int PW = 595, PH = 842;      // A4 portrait
    private static final float M = 28;                // page margin (pt)
    private static final int MAX_PAGES = 40;

    /** One kind of piece from the stage, with how many and its sheet. */
    static final class Piece {
        final String name, kind, size; final int n; final NetPlan plan;
        Piece(String name, String kind, String size, int n, NetPlan plan) { this.name = name; this.kind = kind; this.size = size; this.n = n; this.plan = plan; }
    }

    /** The stage's pieces → the ones a sheet makes (and how many of the rest it left). Pure: tested on a PC. */
    static List<Piece> pieces(JSONObject data) {
        List<Piece> out = new ArrayList<>();
        JSONArray ps = data == null ? null : data.optJSONArray("pieces");
        for (int i = 0; ps != null && i < ps.length() && out.size() < MAX_PAGES; i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p == null) continue;
            String k = p.optString("k");
            NetPlan plan;
            String size, kind;
            if ("box".equals(k)) {
                double w = p.optDouble("w", 0), h = p.optDouble("h", 0), d = p.optDouble("d", 0);
                if (!(w > 0 && h > 0 && d > 0) || Math.max(w, Math.max(h, d)) > 400) continue;
                plan = NetPlan.of("box", w, h, d);
                size = NetPlan.cm(w * 10) + " × " + NetPlan.cm(h * 10) + " × " + NetPlan.cm(d * 10);
                kind = plan != null && plan.tabs.isEmpty() ? "పలక" : "డబ్బా";
            } else if ("cyl".equals(k)) {
                double r = p.optDouble("r", 0), h = p.optDouble("h", 0);
                if (!(r > 0 && h > 0) || Math.max(r, h) > 400) continue;
                plan = NetPlan.of("cyl", r, h, 0);
                size = "⌀ " + NetPlan.cm(r * 20) + " × " + NetPlan.cm(h * 10);
                kind = plan != null && plan.labels.size() == 1 ? "గుండ్రం" : "గొట్టం";
            } else continue;
            if (plan == null) continue;
            String name = p.optString("name", "").trim();
            out.add(new Piece(name.isEmpty() ? "భాగం " + (out.size() + 1) : Workshop.cap(name, 40), kind, size, Math.max(1, Math.min(999, p.optInt("n", 1))), plan));
        }
        return out;
    }

    /** The scale a sheet is drawn at on a page (1 = its real size). */
    static float fit(NetPlan n) { return (float) NetPlan.pageScale(Math.max(1, n.w), Math.max(1, n.h)); }

    static byte[] make(String title, String json) throws Exception {
        JSONObject data = new JSONObject(json);
        List<Piece> ps = pieces(data);
        if (ps.isEmpty()) return null;
        PdfDocument doc = new PdfDocument();
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        String date = new java.text.SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(new java.util.Date());
        int pageNo = 1;
        PdfDocument.Page pg = doc.startPage(new PdfDocument.PageInfo.Builder(PW, PH, pageNo).create());
        Canvas c = pg.getCanvas();
        head(c, ink, "📦 " + title + " · కార్డ్‌బోర్డ్ కటింగ్ షీట్", "Jarvis 3D వర్క్‌షాప్ · " + date + " · కొలతలు సెంటీమీటర్లలో (AI డిజైన్ చేసిన కొలతలు)");
        float y = 92;
        y = legend(c, ink, y);
        String[] how = {
                "1. ప్రింట్: \"Actual size / 100%\" ఎంచుకోండి (Fit to page వద్దు). ప్రతి పేజీ కింద 5 సెం.మీ గీతని స్కేల్‌తో చూడండి.",
                "2. పేజీని కార్డ్‌బోర్డ్ మీద అంటించి (లేదా గీతలు దించి) నల్ల గీతల దగ్గర కత్తిరించండి. బూడిద పట్టీలు కూడా కత్తిరించాలి.",
                "3. నీలం చుక్కల గీతల దగ్గర స్కేల్ పెట్టి, పెన్ను వెనక భాగంతో గీత గీసి మడవండి: సులువుగా మడతపడుతుంది.",
                "4. బూడిద పట్టీలకి గమ్ / ఫెవికాల్ రాసి లోపలి వైపు అతికించండి. ఆరేదాకా క్లిప్ / టేప్‌తో పట్టుకోండి.",
                "⚠️ కట్టర్ వాడేటప్పుడు కింద చెక్క / మందపాటి అట్ట పెట్టండి; పిల్లలు పెద్దవాళ్ళ దగ్గర ఉండి మాత్రమే."};
        ink.setTypeface(Typeface.DEFAULT);
        ink.setTextSize(9.5f);
        ink.setColor(0xFF15202B);
        for (String s : how) { y = wrap(c, ink, s, M, y, PW - 2 * M, 13) + 4; }
        y += 8;
        ink.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        ink.setTextSize(10f);
        float[] cx = {M, M + 22, M + 250, M + 290, M + 350, M + 470};
        String[] hd = {"#", "భాగం", "ఎన్ని", "ఆకారం", "సైజు (సెం.మీ)", "పేజీ · స్కేల్"};
        for (int k = 0; k < hd.length; k++) c.drawText(hd[k], cx[k], y, ink);
        ink.setColor(0xFFC9D3DE);
        c.drawRect(M, y + 5, PW - M, y + 5.8f, ink);
        y += 20;
        ink.setTypeface(Typeface.DEFAULT);
        ink.setTextSize(9.5f);
        int rowsOnCover = 0;
        for (int i = 0; i < ps.size() && y < PH - 70; i++, rowsOnCover++) {
            Piece p = ps.get(i);
            float s = fit(p.plan);
            ink.setColor(0xFF15202B);
            c.drawText(String.valueOf(i + 1), cx[0], y, ink);
            c.drawText(ellipsis(ink, p.name, 220), cx[1], y, ink);
            c.drawText("× " + p.n, cx[2], y, ink);
            c.drawText(p.kind, cx[3], y, ink);
            c.drawText(p.size, cx[4], y, ink);
            c.drawText((i + 2) + " · " + (s >= 0.999f ? "అసలు సైజు" : "1:" + ratio(s)), cx[5], y, ink);
            y += 16;
        }
        if (rowsOnCover < ps.size()) { ink.setColor(0xFF4A5868); c.drawText("… ఇంకా " + (ps.size() - rowsOnCover) + " (తర్వాతి పేజీల్లో)", cx[1], y, ink); y += 16; }
        int other = data.optInt("other", 0);
        if (other > 0) {
            JSONArray on = data.optJSONArray("otherNames");
            StringBuilder b = new StringBuilder();
            for (int i = 0; on != null && i < on.length() && i < 8; i++) { if (b.length() > 0) b.append(", "); b.append(on.optString(i)); }
            ink.setColor(0xFF4A5868);
            y = wrap(c, ink, "ℹ️ డబ్బా / గొట్టం ఆకారం కాని " + other + " ముక్కలు (గుండ్రటి, వంపులు, చిన్న వివరాలు) ఈ షీట్‌లో లేవు" + (b.length() > 0 ? ": " + b : "")
                    + ". వాటిని బంకమట్టి, బాటిల్ మూతలు, స్ట్రాలతో చేయొచ్చు.", M, y + 6, PW - 2 * M, 13);
        }
        foot(c, ink, pageNo);
        doc.finishPage(pg);
        // one page a piece
        for (int i = 0; i < ps.size(); i++) {
            Piece p = ps.get(i);
            pageNo++;
            pg = doc.startPage(new PdfDocument.PageInfo.Builder(PW, PH, pageNo).create());
            c = pg.getCanvas();
            float s = fit(p.plan);
            head(c, ink, "#" + (i + 1) + " " + p.name + "  × " + p.n + " కత్తిరించండి", p.kind + " · " + p.size + " సెం.మీ · "
                    + (s >= 0.999f ? "అసలు సైజు (100% ప్రింట్ చేస్తే నేరుగా కత్తిరించవచ్చు)" : "1:" + ratio(s) + " చిన్నగా: పేజీకి పెద్దది, కొలతలు చూసి అట్ట మీద గీయండి"));
            float k = PT * s;
            boolean turn = NetPlan.turned(p.plan.w, p.plan.h);
            float sw = (float) (turn ? p.plan.h : p.plan.w) * k, sh = (float) (turn ? p.plan.w : p.plan.h) * k;
            float left = (PW - sw) / 2f, top = 80 + Math.max(0, ((float) NetPlan.PAGE_H * PT - sh) / 2f);
            if (turn) { // (a quarter turn: the sheet's top edge runs down the page's right side)
                c.save();
                c.translate(left + sw, top);
                c.rotate(90);
                sheet(c, ink, p.plan, 0, 0, k);
                c.restore();
            } else sheet(c, ink, p.plan, left, top, k);
            ruler(c, ink, k, s >= 0.999f);
            foot(c, ink, pageNo);
            doc.finishPage(pg);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.writeTo(out);
        doc.close();
        return out.toByteArray();
    }

    static String ratio(float s) {
        double r = 1 / Math.max(0.001, s);
        return Math.abs(r - Math.round(r)) < 0.05 ? String.valueOf(Math.round(r)) : String.format(Locale.ROOT, "%.1f", r);
    }

    private static void sheet(Canvas c, Paint ink, NetPlan n, float ox, float oy, float k) {
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFDDE3EA);
        for (double[] t : n.tabs) {
            Path pa = new Path();
            pa.moveTo(ox + (float) t[0] * k, oy + (float) t[1] * k);
            for (int i = 2; i < t.length; i += 2) pa.lineTo(ox + (float) t[i] * k, oy + (float) t[i + 1] * k);
            pa.close();
            c.drawPath(pa, fill);
        }
        Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG), fold = new Paint(Paint.ANTI_ALIAS_FLAG);
        cut.setStyle(Paint.Style.STROKE); cut.setColor(0xFF111111); cut.setStrokeWidth(0.9f);
        fold.setStyle(Paint.Style.STROKE); fold.setColor(0xFF1E6FD9); fold.setStrokeWidth(0.8f);
        fold.setPathEffect(new DashPathEffect(new float[]{5, 3.5f}, 0));
        for (NetPlan.Seg sg : n.segs) {
            Paint p = sg.kind == NetPlan.CUT ? cut : fold;
            c.drawLine(ox + (float) sg.x1 * k, oy + (float) sg.y1 * k, ox + (float) sg.x2 * k, oy + (float) sg.y2 * k, p);
        }
        for (NetPlan.Ring r : n.rings) c.drawCircle(ox + (float) r.cx * k, oy + (float) r.cy * k, (float) r.r * k, r.kind == NetPlan.CUT ? cut : fold);
        ink.setStyle(Paint.Style.FILL);
        ink.setTextAlign(Paint.Align.CENTER);
        for (NetPlan.Label l : n.labels) {
            float room = (float) l.room * k;
            float ts = Math.max(5.5f, Math.min(13f, room / 4.5f));
            ink.setTextSize(ts);
            ink.setColor(0xFF15202B);
            ink.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            float x = ox + (float) l.x * k, y = oy + (float) l.y * k;
            c.drawText(l.name, x, y - ts * 0.15f, ink);
            ink.setTypeface(Typeface.DEFAULT);
            ink.setColor(0xFF2A6FD6);
            c.drawText(l.size + " సెం.మీ", x, y + ts * 1.05f, ink);
        }
        ink.setTextAlign(Paint.Align.LEFT);
    }

    /** A 5 cm line to check the printer kept the real size (at 1:1), or what 5 cm is in this smaller drawing. */
    private static void ruler(Canvas c, Paint ink, float k, boolean real) {
        float x0 = M, y0 = PH - 46, len = 50 * k;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF15202B);
        p.setStrokeWidth(1.2f);
        c.drawLine(x0, y0, x0 + len, y0, p);
        for (int i = 0; i <= 5; i++) c.drawLine(x0 + i * 10 * k, y0 - (i % 5 == 0 ? 6 : 3), x0 + i * 10 * k, y0, p);
        ink.setTextSize(8.5f);
        ink.setColor(0xFF4A5868);
        ink.setTypeface(Typeface.DEFAULT);
        c.drawText(real ? "← ఈ గీత సరిగ్గా 5 సెం.మీ ఉండాలి (లేకపోతే 100% తో మళ్ళీ ప్రింట్ చేయండి)" : "← ఈ బొమ్మలో 5 సెం.మీ", x0 + len + 8, y0, ink);
    }

    private static float legend(Canvas c, Paint ink, float y) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStrokeWidth(1.2f);
        float x = M;
        ink.setTextSize(9.5f);
        ink.setTypeface(Typeface.DEFAULT);
        ink.setColor(0xFF15202B);
        p.setColor(0xFF111111);
        c.drawLine(x, y - 3, x + 30, y - 3, p);
        c.drawText("కత్తిరించండి", x + 36, y, ink);
        x += 130;
        p.setColor(0xFF1E6FD9);
        p.setPathEffect(new DashPathEffect(new float[]{5, 3.5f}, 0));
        c.drawLine(x, y - 3, x + 30, y - 3, p);
        p.setPathEffect(null);
        c.drawText("మడవండి", x + 36, y, ink);
        x += 110;
        p.setColor(0xFFDDE3EA);
        p.setStyle(Paint.Style.FILL);
        c.drawRect(x, y - 9, x + 30, y + 2, p);
        c.drawText("గమ్ రాసే పట్టీ", x + 36, y, ink);
        return y + 22;
    }

    private static void head(Canvas c, Paint ink, String t1, String t2) {
        ink.setStyle(Paint.Style.FILL);
        ink.setTextAlign(Paint.Align.LEFT);
        ink.setColor(0xFF15202B);
        ink.setTextSize(15f);
        ink.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.drawText(ellipsis(ink, t1, PW - 2 * M), M, 40, ink);
        ink.setTypeface(Typeface.DEFAULT);
        ink.setTextSize(9f);
        ink.setColor(0xFF4A5868);
        c.drawText(ellipsis(ink, t2, PW - 2 * M), M, 56, ink);
        ink.setColor(0xFFC9D3DE);
        c.drawRect(M, 64, PW - M, 64.8f, ink);
    }

    private static void foot(Canvas c, Paint ink, int page) {
        ink.setTextAlign(Paint.Align.LEFT);
        ink.setTypeface(Typeface.DEFAULT);
        ink.setTextSize(8f);
        ink.setColor(0xFF8A96A3);
        c.drawText("Jarvis 3D వర్క్‌షాప్ · కార్డ్‌బోర్డ్ షీట్ · పేజీ " + page, M, PH - 18, ink);
    }

    private static String ellipsis(Paint ink, String s, float max) {
        if (s == null) return "";
        if (ink.measureText(s) <= max) return s;
        String t = s;
        while (t.length() > 1 && ink.measureText(t + "…") > max) t = t.substring(0, t.length() - 1);
        return t + "…";
    }

    /** Words over lines no wider than w; the y under the last line. */
    private static float wrap(Canvas c, Paint ink, String s, float x, float y, float w, float lh) {
        String[] words = s.split(" ");
        StringBuilder line = new StringBuilder();
        for (String wd : words) {
            String tryLine = line.length() == 0 ? wd : line + " " + wd;
            if (ink.measureText(tryLine) > w && line.length() > 0) { c.drawText(line.toString(), x, y, ink); y += lh; line = new StringBuilder(wd); }
            else line = new StringBuilder(tryLine);
        }
        if (line.length() > 0) { c.drawText(line.toString(), x, y, ink); y += lh; }
        return y;
    }
}
