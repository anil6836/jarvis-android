package com.anil.jarvis;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Telugu PDF of a detailed scan: the photo with every part numbered, what it is and how it works, the block diagram
 * and the connection diagram (drawn by Jarvis from what was found, marked as an estimate), the parts table with values
 * and prices, how to build one step by step, how to test and repair it, tools and safety. Saved in Downloads/Jarvis/scans.
 * The diagram alone can be shared as a picture.
 */
final class ScanPdf {
    private ScanPdf() {}

    private static final int PW = 595, PH = 842, M = 40; // A4 in points, margin

    /** Makes the PDF (a worker thread: it asks the AI for the guide's words). */
    static Coder.Made make(Context c, Prefs p, String id) throws Exception {
        JSONObject data = ScanStore.load(c, id);
        if (data == null) throw new IllegalStateException("no scan");
        Bitmap photo = ScanStore.photo(c, id, photoName(data), 1600);
        // the guide's words, from what the scan found
        StringBuilder facts = new StringBuilder();
        facts.append("Title: ").append(data.optString("title")).append("\nKind: ").append(data.optString("kind"))
                .append("\nWhat Jarvis said: ").append(data.optString("say")).append("\nParts:\n");
        for (ScanBrain.Item it : items(data)) {
            facts.append(it.n).append(". ").append(it.name).append(it.value.isEmpty() ? "" : " (" + it.value + ")")
                    .append(it.section.isEmpty() ? "" : " [" + it.section + "]").append(": ").append(it.info.isEmpty() ? it.note : it.info)
                    .append(it.fault ? " FAULT: " + it.faultWhy : "").append('\n');
        }
        if (data.has("sections")) facts.append("Sections: ").append(data.optJSONArray("sections")).append('\n');
        if (data.has("block")) facts.append("Blocks: ").append(data.optJSONArray("block")).append('\n');
        if (data.has("build")) facts.append("Build steps found: ").append(data.optJSONArray("build")).append('\n');
        if (data.has("tests")) facts.append("Tests: ").append(data.optJSONArray("tests")).append('\n');
        String reply = Brain.oneShot(p, ScanBrain.pdfSystem(p), facts.toString(), photo == null ? null : JarvisCamera.jpeg(photo, 1280, 82), false, 6000);
        JSONObject g = ScanBrain.json(reply);
        if (g == null) g = new JSONObject().put("about", ScanBrain.clean(reply));

        PdfDocument doc = new PdfDocument();
        Writer w = new Writer(doc);
        w.title("JARVIS · స్కాన్ రిపోర్ట్", data.optString("title"),
                new java.text.SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH).format(new java.util.Date(data.optLong("t", System.currentTimeMillis()))));
        List<ScanBrain.Item> list = items(data);
        if (photo != null) w.image(ScanHud.withBoxes(photo, list, 1f), 330);
        w.note("⚠️ ఈ రిపోర్ట్ ఫోటో నుంచి Jarvis వేసిన అంచనా. parts విలువలు, కనెక్షన్లు multimeter తో చెక్ చేసుకోండి. కరెంట్‌తో పనిచేసేటప్పుడు జాగ్రత్త.");
        w.heading("ఇది ఏంటి");
        w.para(g.optString("about", data.optString("say")));
        if (!g.optString("how").isEmpty()) { w.heading("ఎలా పనిచేస్తుంది"); w.para(g.optString("how")); }
        Bitmap block = blockDiagram(data, 1000);
        if (block != null) { w.heading("బ్లాక్ డయాగ్రమ్ (అంచనా)"); w.image(block, 300); }
        Bitmap conn = connectionDiagram(data, 1000);
        if (conn != null) { w.heading("కనెక్షన్ డయాగ్రమ్ (అంచనా)"); w.image(conn, 420); }
        if (!list.isEmpty()) {
            w.heading("Parts లిస్ట్" + (data.optString("bom_total").isEmpty() ? "" : " · మొత్తం దాదాపు " + data.optString("bom_total")));
            for (ScanBrain.Item it : list) {
                w.bullet(it.n + ". " + it.name + (it.value.isEmpty() ? "" : " · " + it.value) + (it.price.isEmpty() ? "" : " · " + it.price)
                        + "\n   " + (it.info.isEmpty() ? it.note : it.info) + (it.sub.isEmpty() ? "" : "\n   బదులు: " + it.sub)
                        + (it.fault ? "\n   ⚠️ " + it.faultWhy : ""));
            }
        }
        list("తయారు చేసే విధానం", g.optJSONArray("build"), w, true);
        list("ఎలా టెస్ట్ చేయాలి", g.optJSONArray("tests"), w, true);
        JSONArray tp = data.optJSONArray("tests");
        if (tp != null && tp.length() > 0) {
            w.heading("టెస్ట్ పాయింట్లు (multimeter)");
            for (int i = 0; i < tp.length(); i++) {
                JSONObject o = tp.optJSONObject(i);
                if (o != null) w.bullet("TP" + (i + 1) + ": " + o.optString("where") + " → " + o.optString("expect"));
            }
        }
        list("సాధారణ సమస్యలు, రిపేర్", g.optJSONArray("repair"), w, false);
        list("కావాల్సిన టూల్స్", g.optJSONArray("tools"), w, false);
        list("జాగ్రత్తలు", g.optJSONArray("safety"), w, false);
        w.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.writeTo(out);
        doc.close();
        String name = "Jarvis_" + data.optString("title", "scan").replaceAll("[^\\p{L}\\p{M}\\p{N}]+", "_") + "_"
                + new java.text.SimpleDateFormat("yyyyMMdd_HHmm", Locale.ENGLISH).format(new java.util.Date()) + ".pdf";
        if (name.length() > 90) name = name.substring(0, 70) + ".pdf";
        return Coder.save(c, "Jarvis/scans", name, "application/pdf", out.toByteArray());
    }

    private static void list(String head, JSONArray a, Writer w, boolean numbered) {
        if (a == null || a.length() == 0) return;
        w.heading(head);
        for (int i = 0; i < a.length(); i++) {
            String s = ScanBrain.clean(a.optString(i));
            if (!s.isEmpty()) w.bullet((numbered ? (i + 1) + ". " : "• ") + s);
        }
    }

    private static String photoName(JSONObject data) {
        JSONArray l = data.optJSONArray("layers");
        if (l != null && l.length() > 0 && l.optJSONObject(0) != null) return l.optJSONObject(0).optString("photo", "photo.jpg");
        return "photo.jpg";
    }

    static List<ScanBrain.Item> items(JSONObject data) {
        List<ScanBrain.Item> out = new ArrayList<>();
        JSONArray a = data.optJSONArray("items");
        if (a == null) {
            JSONArray l = data.optJSONArray("layers");
            if (l != null && l.length() > 0 && l.optJSONObject(0) != null) a = l.optJSONObject(0).optJSONArray("items");
        }
        for (int i = 0; a != null && i < a.length(); i++) {
            ScanBrain.Item it = ScanBrain.item(a.optJSONObject(i), i + 1);
            if (it != null) out.add(it);
        }
        return out;
    }

    /** The diagrams as one picture, shared (he picks the person). */
    static void shareDiagram(Activity a, String id) {
        JSONObject data = ScanStore.load(a, id);
        if (data == null) return;
        Bitmap b1 = blockDiagram(data, 1000), b2 = connectionDiagram(data, 1000);
        if (b1 == null && b2 == null) { Toast.makeText(a, "డయాగ్రమ్ చేయడానికి సరిపడా వివరాలు లేవు", Toast.LENGTH_SHORT).show(); return; }
        int h = (b1 == null ? 0 : b1.getHeight()) + (b2 == null ? 0 : b2.getHeight()) + 120;
        Bitmap out = Bitmap.createBitmap(1000, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(0xFFFFFFFF);
        TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        t.setTextSize(30);
        t.setColor(0xFF0B2A3A);
        t.setFakeBoldText(true);
        c.drawText("JARVIS · " + data.optString("title") + " (అంచనా)", 24, 52, t);
        int y = 80;
        if (b1 != null) { c.drawBitmap(b1, 0, y, null); y += b1.getHeight(); }
        if (b2 != null) c.drawBitmap(b2, 0, y + 20, null);
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(PhotoProvider.shareFile(a))) {
            out.compress(Bitmap.CompressFormat.JPEG, 92, o);
            Uri u = PhotoProvider.shareUri();
            Intent s = new Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            s.setClipData(android.content.ClipData.newRawUri("Jarvis", u));
            a.startActivity(Intent.createChooser(s, "ఎవరికి పంపాలి?").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception e) {
            Toast.makeText(a, "షేర్ చేయలేకపోయాను", Toast.LENGTH_SHORT).show();
        }
    }

    // ================================================================ the diagrams

    private static final int[] PAL = {0xFF0891B2, 0xFFD97706, 0xFF059669, 0xFF7C3AED, 0xFFDB2777, 0xFF2563EB, 0xFFCA8A04};

    /** The sections as boxes with arrows between them (from the scan's blocks, else from its sections). */
    static Bitmap blockDiagram(JSONObject data, int width) {
        List<String> names = new ArrayList<>();
        Map<String, String> does = new HashMap<>();
        Map<String, Integer> col = new HashMap<>();
        JSONArray secs = data.optJSONArray("sections");
        for (int i = 0; secs != null && i < secs.length(); i++) {
            JSONObject o = secs.optJSONObject(i);
            if (o == null || o.optString("name").isEmpty()) continue;
            names.add(o.optString("name"));
            does.put(o.optString("name"), o.optString("does"));
            int cc = PAL[i % PAL.length];
            try { cc = Color.parseColor(o.optString("color").trim()); } catch (Exception ignored) {}
            col.put(o.optString("name"), cc | 0xFF000000);
        }
        List<String[]> arrows = new ArrayList<>();
        JSONArray blocks = data.optJSONArray("block");
        for (int i = 0; blocks != null && i < blocks.length(); i++) {
            JSONObject o = blocks.optJSONObject(i);
            if (o == null) continue;
            String from = o.optString("name");
            if (!from.isEmpty() && !names.contains(from)) names.add(from);
            JSONArray to = o.optJSONArray("to");
            for (int k = 0; to != null && k < to.length(); k++) {
                String t = to.optString(k);
                if (t.isEmpty()) continue;
                if (!names.contains(t)) names.add(t);
                arrows.add(new String[]{from, t, o.optString("signal")});
            }
        }
        if (names.size() < 2) return null;
        int cols = 2, rows = (names.size() + 1) / 2;
        int bw = (width - 3 * 60) / 2, bh = 150, gap = 70;
        int h = rows * (bh + gap) + 40;
        Bitmap b = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFFFFFFFF);
        Paint box = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        Map<String, RectF> at = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            int r = i / cols, k = i % cols;
            float x = 60 + k * (bw + 60), y = 30 + r * (bh + gap);
            RectF rr = new RectF(x, y, x + bw, y + bh);
            at.put(names.get(i), rr);
            int cc = col.containsKey(names.get(i)) ? col.get(names.get(i)) : PAL[i % PAL.length];
            box.setStyle(Paint.Style.FILL);
            box.setColor(Color.argb(40, Color.red(cc), Color.green(cc), Color.blue(cc)));
            c.drawRoundRect(rr, 18, 18, box);
            box.setStyle(Paint.Style.STROKE);
            box.setStrokeWidth(4);
            box.setColor(cc);
            c.drawRoundRect(rr, 18, 18, box);
            tp.setColor(0xFF0B2A3A);
            tp.setTextSize(28);
            tp.setFakeBoldText(true);
            String d = does.get(names.get(i));
            text(c, names.get(i) + (d == null || d.isEmpty() ? "" : "\n" + d), tp, rr, 22);
        }
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(4);
        line.setColor(0xFF334155);
        Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
        head.setColor(0xFF334155);
        tp.setTextSize(22);
        tp.setFakeBoldText(false);
        for (String[] a : arrows) {
            RectF f = at.get(a[0]), t = at.get(a[1]);
            if (f == null || t == null || f == t) continue;
            float[] p = edge(f, t.centerX(), t.centerY()), q = edge(t, f.centerX(), f.centerY());
            c.drawLine(p[0], p[1], q[0], q[1], line);
            arrowHead(c, head, p[0], p[1], q[0], q[1]);
            if (!a[2].isEmpty()) c.drawText(a[2], (p[0] + q[0]) / 2 + 8, (p[1] + q[1]) / 2 - 8, tp);
        }
        return b;
    }

    /** The parts as symbols in their sections (columns), with lines for the connections found. */
    static Bitmap connectionDiagram(JSONObject data, int width) {
        List<ScanBrain.Item> list = items(data);
        if (list.size() < 2) return null;
        List<String> secs = new ArrayList<>();
        for (ScanBrain.Item it : list) { String s = it.section.isEmpty() ? "ఇతరాలు" : it.section; if (!secs.contains(s)) secs.add(s); }
        int cols = Math.max(1, Math.min(4, secs.size()));
        Map<String, Integer> colOf = new HashMap<>();
        for (int i = 0; i < secs.size(); i++) colOf.put(secs.get(i), i % cols);
        int cw = width / cols, rowH = 96;
        int[] filled = new int[cols];
        Map<Integer, RectF> at = new HashMap<>();
        for (ScanBrain.Item it : list) {
            int k = colOf.get(it.section.isEmpty() ? "ఇతరాలు" : it.section);
            float x = k * cw + 30, y = 70 + filled[k] * rowH;
            filled[k]++;
            at.put(it.n, new RectF(x, y, x + cw - 60, y + 66));
        }
        int maxRows = 0;
        for (int f : filled) maxRows = Math.max(maxRows, f);
        int h = 90 + maxRows * rowH;
        Bitmap b = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFFFFFFFF);
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(24);
        tp.setFakeBoldText(true);
        for (int i = 0; i < cols && i < secs.size(); i++) {
            tp.setColor(PAL[i % PAL.length]);
            StringBuilder nm = new StringBuilder();
            for (int s = 0; s < secs.size(); s++) if (s % cols == i) nm.append(nm.length() == 0 ? "" : " / ").append(secs.get(s));
            c.drawText(ellipsize(nm.toString(), tp, cw - 40), i * cw + 30, 44, tp);
        }
        // the connections under the symbols
        Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
        ln.setStyle(Paint.Style.STROKE);
        ln.setStrokeWidth(3);
        ln.setColor(0xFF64748B);
        JSONArray links = data.optJSONArray("links");
        for (int i = 0; links != null && i < links.length(); i++) {
            JSONArray lk = links.optJSONArray(i);
            if (lk == null || lk.length() < 2) continue;
            RectF a = at.get(lk.optInt(0)), z = at.get(lk.optInt(1));
            if (a == null || z == null) continue;
            Path p = new Path();
            float ax = a.right, ay = a.centerY(), zx = z.left, zy = z.centerY();
            if (Math.abs(a.centerX() - z.centerX()) < 5) { ax = a.left; zx = z.left; float mx = a.left - 18 - (i % 4) * 6; p.moveTo(ax, ay); p.lineTo(mx, ay); p.lineTo(mx, zy); p.lineTo(zx, zy); }
            else { if (z.centerX() < a.centerX()) { ax = a.left; zx = z.right; } float mx = (ax + zx) / 2 + (i % 5) * 4; p.moveTo(ax, ay); p.lineTo(mx, ay); p.lineTo(mx, zy); p.lineTo(zx, zy); }
            c.drawPath(p, ln);
        }
        Paint sym = new Paint(Paint.ANTI_ALIAS_FLAG);
        sym.setStyle(Paint.Style.STROKE);
        sym.setStrokeWidth(3.5f);
        Paint bg = new Paint();
        bg.setColor(0xFFFFFFFF);
        tp.setTextSize(21);
        tp.setFakeBoldText(false);
        for (ScanBrain.Item it : list) {
            RectF r = at.get(it.n);
            if (r == null) continue;
            int k = colOf.get(it.section.isEmpty() ? "ఇతరాలు" : it.section);
            sym.setColor(it.fault ? 0xFFDC2626 : PAL[k % PAL.length]);
            RectF s = new RectF(r.left, r.top + 8, r.left + 92, r.bottom - 8);
            c.drawRect(s, bg);
            symbol(c, sym, it.shape, s);
            tp.setColor(0xFF0B2A3A);
            c.drawText(ellipsize(it.n + ". " + it.name, tp, r.width() - 100), r.left + 100, r.top + 28, tp);
            if (!it.value.isEmpty()) { tp.setColor(0xFF475569); c.drawText(ellipsize(it.value, tp, r.width() - 100), r.left + 100, r.top + 56, tp); }
        }
        return b;
    }

    /** A schematic-like symbol for each kind of part. */
    private static void symbol(Canvas c, Paint p, String shape, RectF r) {
        float cx = r.centerX(), cy = r.centerY(), w = r.width(), h = r.height();
        Path path = new Path();
        switch (shape == null ? "" : shape) {
            case "resistor":
                path.moveTo(r.left, cy);
                path.lineTo(r.left + w * 0.2f, cy);
                for (int i = 0; i < 6; i++) path.lineTo(r.left + w * (0.25f + i * 0.1f), cy + (i % 2 == 0 ? -h * 0.3f : h * 0.3f));
                path.lineTo(r.left + w * 0.8f, cy);
                path.lineTo(r.right, cy);
                c.drawPath(path, p);
                break;
            case "capacitor": case "cap_e":
                c.drawLine(r.left, cy, cx - 6, cy, p);
                c.drawLine(cx - 6, cy - h * 0.38f, cx - 6, cy + h * 0.38f, p);
                c.drawLine(cx + 6, cy - h * 0.38f, cx + 6, cy + h * 0.38f, p);
                c.drawLine(cx + 6, cy, r.right, cy, p);
                if ("cap_e".equals(shape)) c.drawText("+", cx - 26, cy - h * 0.2f, p);
                break;
            case "diode": case "led":
                path.moveTo(cx - 14, cy - h * 0.32f);
                path.lineTo(cx + 12, cy);
                path.lineTo(cx - 14, cy + h * 0.32f);
                path.close();
                c.drawPath(path, p);
                c.drawLine(cx + 12, cy - h * 0.32f, cx + 12, cy + h * 0.32f, p);
                c.drawLine(r.left, cy, cx - 14, cy, p);
                c.drawLine(cx + 12, cy, r.right, cy, p);
                if ("led".equals(shape)) { c.drawLine(cx, cy - h * 0.35f, cx + 12, cy - h * 0.55f, p); c.drawLine(cx + 8, cy - h * 0.3f, cx + 20, cy - h * 0.5f, p); }
                break;
            case "transistor":
                c.drawCircle(cx, cy, Math.min(w, h) * 0.42f, p);
                c.drawLine(cx - 8, cy - h * 0.25f, cx - 8, cy + h * 0.25f, p);
                c.drawLine(r.left, cy, cx - 8, cy, p);
                c.drawLine(cx - 8, cy - 6, cx + 14, cy - h * 0.3f, p);
                c.drawLine(cx - 8, cy + 6, cx + 14, cy + h * 0.3f, p);
                break;
            case "inductor": case "coil":
                for (int i = 0; i < 4; i++) c.drawArc(new RectF(r.left + w * (0.1f + i * 0.2f), cy - h * 0.22f, r.left + w * (0.3f + i * 0.2f), cy + h * 0.22f), 180, 180, false, p);
                break;
            case "battery":
                c.drawLine(cx - 6, cy - h * 0.38f, cx - 6, cy + h * 0.38f, p);
                c.drawLine(cx + 6, cy - h * 0.2f, cx + 6, cy + h * 0.2f, p);
                c.drawLine(r.left, cy, cx - 6, cy, p);
                c.drawLine(cx + 6, cy, r.right, cy, p);
                break;
            default: { // a chip / module / connector: a box with pins
                RectF b = new RectF(r.left + w * 0.18f, r.top + 4, r.right - w * 0.18f, r.bottom - 4);
                c.drawRect(b, p);
                for (int i = 0; i < 3; i++) {
                    float y = b.top + (i + 0.5f) * b.height() / 3;
                    c.drawLine(r.left, y, b.left, y, p);
                    c.drawLine(b.right, y, r.right, y, p);
                }
                if ("ic".equals(shape) || "chip".equals(shape)) c.drawArc(new RectF(cx - 8, b.top - 8, cx + 8, b.top + 8), 0, 180, false, p);
            }
        }
    }

    private static float[] edge(RectF r, float tx, float ty) {
        float cx = r.centerX(), cy = r.centerY(), dx = tx - cx, dy = ty - cy;
        if (dx == 0 && dy == 0) return new float[]{cx, cy};
        float sx = dx == 0 ? Float.MAX_VALUE : (r.width() / 2) / Math.abs(dx), sy = dy == 0 ? Float.MAX_VALUE : (r.height() / 2) / Math.abs(dy);
        float s = Math.min(sx, sy);
        return new float[]{cx + dx * s, cy + dy * s};
    }

    private static void arrowHead(Canvas c, Paint p, float x0, float y0, float x1, float y1) {
        double a = Math.atan2(y1 - y0, x1 - x0);
        Path h = new Path();
        h.moveTo(x1, y1);
        h.lineTo((float) (x1 - 22 * Math.cos(a - 0.4)), (float) (y1 - 22 * Math.sin(a - 0.4)));
        h.lineTo((float) (x1 - 22 * Math.cos(a + 0.4)), (float) (y1 - 22 * Math.sin(a + 0.4)));
        h.close();
        c.drawPath(h, p);
    }

    private static void text(Canvas c, String s, TextPaint tp, RectF r, float pad) {
        StaticLayout l = StaticLayout.Builder.obtain(s, 0, s.length(), tp, Math.max(10, (int) (r.width() - 2 * pad)))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(4).setEllipsize(android.text.TextUtils.TruncateAt.END).build();
        c.save();
        c.translate(r.left + pad, r.top + Math.max(pad / 2, (r.height() - l.getHeight()) / 2));
        l.draw(c);
        c.restore();
    }

    private static String ellipsize(String s, TextPaint p, float w) {
        return android.text.TextUtils.ellipsize(s, p, w, android.text.TextUtils.TruncateAt.END).toString();
    }

    // ================================================================ the pages

    /** Writes headings, paragraphs, bullets and pictures down the pages, starting a new page when one is full. */
    private static final class Writer {
        private final PdfDocument doc;
        private PdfDocument.Page page;
        private Canvas c;
        private float y;
        private int num;
        private final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG), head = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG);

        Writer(PdfDocument doc) {
            this.doc = doc;
            body.setTextSize(11.5f);
            body.setColor(0xFF1E293B);
            head.setTextSize(15f);
            head.setColor(0xFF0E7490);
            head.setFakeBoldText(true);
            small.setTextSize(9.5f);
            small.setColor(0xFF64748B);
            newPage();
        }

        private void newPage() {
            if (page != null) doc.finishPage(page);
            num++;
            page = doc.startPage(new PdfDocument.PageInfo.Builder(PW, PH, num).create());
            c = page.getCanvas();
            y = M;
            c.drawText("Jarvis · " + num, PW - M - 50, PH - 18, small);
        }

        private void room(float h) { if (y + h > PH - M) newPage(); }

        void title(String top, String title, String when) {
            TextPaint t = new TextPaint(head);
            t.setTextSize(11);
            t.setLetterSpacing(0.2f);
            c.drawText(top, M, y + 10, t);
            y += 22;
            TextPaint big = new TextPaint(head);
            big.setTextSize(22);
            big.setColor(0xFF0B2A3A);
            block(title, big, 6);
            c.drawText(when, M, y + 10, small);
            y += 22;
        }

        void heading(String s) { room(60); y += 8; block(s, head, 6); }

        void para(String s) { if (s != null && !s.trim().isEmpty()) block(s.trim(), body, 8); }

        void bullet(String s) { block(s, body, 5); }

        void note(String s) {
            TextPaint n = new TextPaint(body);
            n.setColor(0xFFB45309);
            n.setTextSize(10.5f);
            block(s, n, 8);
        }

        /** Text that flows on to the next page line by line when it does not fit. */
        private void block(String s, TextPaint tp, float after) {
            int w = PW - 2 * M;
            StaticLayout l = StaticLayout.Builder.obtain(s, 0, s.length(), tp, w).setLineSpacing(2f, 1.1f).build();
            int line = 0;
            while (line < l.getLineCount()) {
                float room = PH - M - y;
                int last = line;
                while (last < l.getLineCount() && l.getLineBottom(last) - l.getLineTop(line) <= room) last++;
                if (last == line) { newPage(); continue; }
                c.save();
                c.translate(M, y - l.getLineTop(line));
                c.clipRect(0, l.getLineTop(line), w, l.getLineBottom(last - 1));
                l.draw(c);
                c.restore();
                y += l.getLineBottom(last - 1) - l.getLineTop(line);
                line = last;
                if (line < l.getLineCount()) newPage();
            }
            y += after;
        }

        void image(Bitmap b, float maxH) {
            float w = PW - 2 * M, s = Math.min(w / b.getWidth(), maxH / b.getHeight());
            float iw = b.getWidth() * s, ih = b.getHeight() * s;
            room(ih + 10);
            Paint f = new Paint(Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(b, null, new RectF(M + (w - iw) / 2, y, M + (w + iw) / 2, y + ih), f);
            y += ih + 10;
        }

        void finish() { if (page != null) doc.finishPage(page); page = null; }
    }
}
