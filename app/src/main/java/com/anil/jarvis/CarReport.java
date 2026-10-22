package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/** 🚗 The car's month on A4: the totals, each fill (a table), the mileage between fills, the services, the next service. */
final class CarReport {
    private CarReport() {}

    private static final int W = 595, H = 842, M = 40;

    /** One running page set (a long month goes on to the next page). */
    private static final class Pages {
        final PdfDocument doc = new PdfDocument();
        PdfDocument.Page pg;
        Canvas cv;
        int y, n;

        void next() {
            if (pg != null) doc.finishPage(pg);
            pg = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, ++n).create());
            cv = pg.getCanvas();
            y = M;
        }

        /** Room for h more points, else a new page. */
        void room(int h) { if (y + h > H - M - 24) next(); }
    }

    static Coder.Made pdf(Context c, YearMonth ym) throws Exception {
        JSONObject st = Car.load(c);
        if (!CarBook.isSet(st)) return null;
        ZoneId z = ZoneId.systemDefault();
        long now = System.currentTimeMillis();
        LocalDate today = LocalDate.now(z);
        List<double[]> trips = Car.trips(c);
        JSONObject m = CarBook.month(st, trips, ym, today, now, z);
        String fuel = CarBook.fuel(st), fw = CarBook.fuelWord(fuel);
        Pages p = new Pages();
        try {
            p.next();
            TextPaint head = paint(20, Color.BLACK, true), body = paint(12.5f, Color.rgb(40, 40, 40), false), big = paint(13.5f, Color.BLACK, true),
                    sub = paint(14, Color.rgb(20, 70, 140), true), cell = paint(11.5f, Color.rgb(30, 30, 30), false), cellB = paint(11.5f, Color.rgb(30, 30, 30), true),
                    note = paint(10, Color.rgb(90, 90, 90), false);
            p.y = draw(p.cv, CarBook.name(st) + " లెక్క · " + CarBook.MONTHS[ym.getMonthValue() - 1] + " " + ym.getYear() + (m.optBoolean("so_far") ? " (ఈరోజు వరకు)" : ""),
                    head, M, p.y, W - 2 * M, Layout.Alignment.ALIGN_CENTER) + 6;
            p.y = draw(p.cv, fw + " · సర్వీస్ ప్రతి " + CarBook.km(CarBook.everyKm(st)) + " కి.మీ లేదా " + CarBook.everyMonths(st) + " నెలలకి", body, M, p.y, W - 2 * M,
                    Layout.Alignment.ALIGN_CENTER) + 14;

            // the totals
            StringBuilder s = new StringBuilder();
            int n = m.optInt("n");
            s.append(fw).append(": ").append(n == 0 ? "ఈ నెల కొట్టించలేదు" : n + (n == 1 ? " సారి" : " సార్లు") + " · " + CarBook.litres(m.optDouble("litres")) + " లీటర్లు · "
                    + HouseBook.rs(m.optLong("rupees")));
            if (m.optLong("km", 0) > 0)
                s.append("\nతిరిగింది: ").append(m.optBoolean("km_approx") ? "సుమారు " : "").append(CarBook.km(m.optLong("km"))).append(" కి.మీ").append(m.optBoolean("km_gps") ? " (GPS ట్రిప్పులు)" : m.optBoolean("km_mixed") ? " (మీటర్ + GPS ట్రిప్పులు)" : " (మీటర్)");
            if (m.has("kmpl"))
                s.append("\nమైలేజ్: లీటర్‌కి ").append(m.optDouble("kmpl")).append(" కి.మీ").append("gps".equals(m.optString("kmpl_src")) ? " (GPS: సుమారు)" : "")
                        .append(" · కి.మీ.కి ").append(HouseBook.rate(m.optDouble("rs_per_km")));
            JSONArray sv = m.optJSONArray("services");
            if (sv != null && sv.length() > 0) s.append("\nసర్వీస్: ").append(m.optLong("service_rs") > 0 ? HouseBook.rs(m.optLong("service_rs")) : "అయింది");
            s.append("\n").append(CarBook.serviceLine(st, CarBook.service(st, trips, today, now, z)));
            p.y = draw(p.cv, s.toString(), big, M + 8, p.y, W - 2 * M - 16, Layout.Alignment.ALIGN_NORMAL) + 16;

            // each fill
            JSONArray fl = m.optJSONArray("fills");
            if (fl != null && fl.length() > 0) {
                String[] hd = {"తేదీ", "రూపాయలు", "లీటర్లు", "లీటర్ రేటు", "మీటర్", "ఫుల్"};
                int[] cw = {95, 90, 80, 90, 95, 65};
                String title = fw + " కొట్టించినవి";
                p.room(height(title, sub, W - 2 * M) + 6 + 2 * rowHeight(hd, cw, cellB)); // (the heading never alone at a page's foot)
                p.y = draw(p.cv, title, sub, M, p.y, W - 2 * M, Layout.Alignment.ALIGN_NORMAL) + 6;
                p.y = row(p.cv, hd, cw, cellB, p.y, true);
                for (int i = 0; i < fl.length(); i++) {
                    JSONObject f = fl.getJSONObject(i);
                    String[] r = {CarBook.dayName(f.optString("d")), HouseBook.rs(Math.round(f.optDouble("rs"))),
                            (f.optBoolean("le") ? "~" : "") + CarBook.litres(f.optDouble("l")),
                            f.optBoolean("le") ? "—" : HouseBook.rate(CarBook.round2(f.optDouble("rs") / f.optDouble("l"))),
                            f.has("odo") ? CarBook.km(f.optDouble("odo")) : "—", f.optBoolean("full") ? "అవును" : ""};
                    int rh = rowHeight(r, cw, cell);
                    if (p.y + rh > H - M - 24) { p.next(); p.y = row(p.cv, hd, cw, cellB, p.y, true); } // (a new page starts with the heading row)
                    p.y = row(p.cv, r, cw, cell, p.y, false);
                }
                p.y += 14;
            }

            // the mileage between fills that ended this month
            String from = ym.atDay(1).toString(), to = ym.atEndOfMonth().toString();
            StringBuilder ms = new StringBuilder();
            for (JSONObject g : CarBook.segments(st, trips)) {
                String d = g.optString("to");
                if (d.compareTo(from) < 0 || d.compareTo(to) > 0) continue;
                ms.append(CarBook.dayName(g.optString("from"))).append(" → ").append(CarBook.dayName(d)).append(": ").append(CarBook.km(g.optDouble("km"))).append(" కి.మీ · ")
                        .append(CarBook.litres(g.optDouble("l"))).append(" లీటర్లు · ");
                if (g.optBoolean("valid")) ms.append(g.optBoolean("exact") ? "" : "సుమారు ").append(g.optDouble("kmpl")).append(" కి.మీ/లీటర్")
                        .append("gps".equals(g.optString("src")) ? " (GPS)" : g.optBoolean("exact") ? " (ఫుల్ టు ఫుల్)" : "");
                else ms.append("లెక్కలోకి తీసుకోలేదు (చాలా తక్కువ దూరం / నమ్మలేని అంకె)");
                ms.append('\n');
            }
            if (ms.length() > 0) {
                String[] lines = ms.toString().trim().split("\n");
                String title = "మైలేజ్ (రెండు " + fw + " మధ్య)";
                p.room(height(title, sub, W - 2 * M) + 6 + height(lines[0], body, W - 2 * M - 16) + 3);
                p.y = draw(p.cv, title, sub, M, p.y, W - 2 * M, Layout.Alignment.ALIGN_NORMAL) + 6;
                for (String line : lines) {
                    p.room(height(line, body, W - 2 * M - 16) + 3);
                    p.y = draw(p.cv, line, body, M + 8, p.y, W - 2 * M - 16, Layout.Alignment.ALIGN_NORMAL) + 3;
                }
                p.y += 12;
            }

            // services
            if (sv != null && sv.length() > 0) {
                p.room(height("సర్వీస్", sub, W - 2 * M) + 6 + 24);
                p.y = draw(p.cv, "సర్వీస్", sub, M, p.y, W - 2 * M, Layout.Alignment.ALIGN_NORMAL) + 6;
                for (int i = 0; i < sv.length(); i++) {
                    JSONObject x = sv.getJSONObject(i);
                    String line = CarBook.dayName(x.optString("d")) + (x.has("rs") ? " · " + HouseBook.rs(Math.round(x.optDouble("rs"))) : "")
                            + (x.has("odo") ? " · మీటర్ " + CarBook.km(x.optDouble("odo")) : "") + (x.optString("what").isEmpty() ? "" : " · " + x.optString("what"));
                    p.room(height(line, body, W - 2 * M - 16) + 3);
                    p.y = draw(p.cv, line, body, M + 8, p.y, W - 2 * M - 16, Layout.Alignment.ALIGN_NORMAL) + 3;
                }
                p.y += 12;
            }
            String foot = "సుమారు / ~ = ట్యాంక్ ఫుల్ చేయించని సార్లు, లీటర్లు చెప్పని సార్లు, మీటర్ రీడింగ్‌ల మధ్య రోజులు, లేదా GPS ట్రిప్పుల కి.మీ (డ్రైవ్ అలర్ట్స్ ఆన్‌లో ఉన్నవే). "
                    + "మీటర్ రీడింగ్ (మొత్తం ODO) + ఫుల్ ట్యాంక్ చెబితే కచ్చితమైన లెక్క.";
            p.room(height(foot, note, W - 2 * M));
            draw(p.cv, foot, note, M, p.y, W - 2 * M, Layout.Alignment.ALIGN_NORMAL);
            draw(p.cv, "Jarvis తయారు చేసింది · " + today, paint(9.5f, Color.rgb(130, 130, 130), false), M, H - M - 14, W - 2 * M, Layout.Alignment.ALIGN_CENTER);
            p.doc.finishPage(p.pg);
            p.pg = null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            p.doc.writeTo(out);
            return Coder.save(c, "Jarvis/reports", "Car_" + ym + "_" + System.currentTimeMillis() % 100000 + ".pdf", "application/pdf", out.toByteArray());
        } finally { // (close() refuses while a page is open: a failure half way finishes it first)
            if (p.pg != null) try { p.doc.finishPage(p.pg); } catch (Exception ignored) {}
            try { p.doc.close(); } catch (Exception ignored) {}
        }
    }

    /** How tall that text is when wrapped to w. */
    private static int height(String text, TextPaint p, int w) { return layout(text, p, w, Layout.Alignment.ALIGN_NORMAL).getHeight(); }

    /** A table row: its tallest cell and some room. */
    private static int rowHeight(String[] cells, int[] cw, TextPaint tp) {
        int h = 0;
        for (int i = 0; i < cells.length; i++) h = Math.max(h, height(cells[i], tp, cw[i] - 8));
        return h + 8;
    }

    /** One table row with its cell lines; returns the y under it. */
    private static int row(Canvas cv, String[] cells, int[] cw, TextPaint tp, int y, boolean head) {
        int h = rowHeight(cells, cw, tp), x = M;
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setColor(Color.rgb(170, 170, 170));
        line.setStrokeWidth(0.8f);
        if (head) {
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(Color.rgb(232, 240, 250));
            int total = 0;
            for (int w : cw) total += w;
            cv.drawRect(M, y, M + total, y + h, fill);
        }
        for (int i = 0; i < cells.length; i++) {
            cv.drawRect(x, y, x + cw[i], y + h, line);
            draw(cv, cells[i], tp, x + 4, y + 4, cw[i] - 8, Layout.Alignment.ALIGN_CENTER);
            x += cw[i];
        }
        return y + h;
    }

    private static TextPaint paint(float size, int color, boolean bold) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(color);
        if (bold) p.setTypeface(Typeface.DEFAULT_BOLD);
        return p;
    }

    /** Draws wrapped text at (x, y); returns the y under it. */
    private static StaticLayout layout(String text, TextPaint p, int w, Layout.Alignment al) {
        StaticLayout.Builder lb = StaticLayout.Builder.obtain(text, 0, text.length(), p, Math.max(10, w)).setAlignment(al).setLineSpacing(1, 1.1f);
        if (android.os.Build.VERSION.SDK_INT >= 28) // (Telugu marks above / below the line keep their room; API 28, so by name)
            try { lb.getClass().getMethod("setUseLineSpacingFromFallbacks", boolean.class).invoke(lb, true); } catch (Exception ignored) {}
        return lb.build();
    }

    private static int draw(Canvas cv, String text, TextPaint p, int x, int y, int w, Layout.Alignment al) {
        StaticLayout l = layout(text, p, w, al);
        cv.save();
        cv.translate(x, y);
        l.draw(cv);
        cv.restore();
        return y + l.getHeight();
    }
}
