package com.anil.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The weekly report for her doctor: one PDF page (or two) with each day's meals, tablets, water and sugar readings,
 * a sugar chart and the medicines list, sent to Anil through his own Telegram bot on Sunday evening.
 * Only what she told Jarvis or pressed on the buttons. Runs on the caller's (background) thread; never throws.
 */
final class HomeReport {
    private HomeReport() {}

    // A4 in points
    private static final int W = 595, H = 842, M = 36, BOTTOM = H - 62;
    private static final int BLUE = 0xFF1E5AA8, BLUE_DARK = 0xFF16467F, BLUE_LIGHT = 0xFFE6EEF9, ZEBRA = 0xFFF7F9FC,
            GRID = 0xFFD3DAE3, AXIS = 0xFF9AA3AE, DAY_LINE = 0xFFECEFF3, TEXT = 0xFF222222, GRAY = 0xFF8A8F98,
            RED = 0xFFC62828, GREEN = 0xFF2E7D32, ORANGE = 0xFFE65100;
    /** Table columns: date, 4 meals, tablets, water, sugar (sum = W - 2M). */
    private static final float[] COLS = {78, 50, 50, 50, 50, 52, 40, 153};
    private static final float BODY = 12, LH = BODY * 1.5f;
    private static final long DAY = 86_400_000L;
    /** "8:10 142", "20:30 180", "8:10 pm 142". */
    private static final Pattern READING = Pattern.compile("(\\d{1,2})[:.](\\d{2})\\s*([AaPp])?\\.?[Mm]?\\.?\\D*?(\\d{2,3})(?!\\d)");

    private static final class Row {
        long start;
        JSONObject log;
        int[] doses;
        List<long[]> sugar;
    }

    // ================================================================ sending

    /** Builds the last 7 days' report and sends it to Anil's Telegram; a short Telugu result. */
    static String send(Context c) {
        try {
            if (Guard.token(c).isEmpty() || Guard.chat(c).isEmpty()) return "Telegram bot సెట్ చేయలేదు — రిపోర్ట్ పంపలేను";
            byte[] pdf = pdf(c, 7);
            if (pdf == null || pdf.length == 0) return "రిపోర్ట్ తయారు చేయలేకపోయాను";
            String name = "amma-week-" + new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(new Date()) + ".pdf";
            String cap = "📊 " + HomeCare.who(c) + " ఈ వారం రిపోర్ట్ (డాక్టర్‌కి చూపించడానికి)";
            long id = Guard.sendFile(c, "sendDocument", "document", name, "application/pdf", pdf, cap, false);
            if (id < 0) id = Guard.sendFile(c, "sendDocument", "document", name, "application/pdf", pdf, cap, false); // one more try
            return id > 0 ? "పంపాను ✓" : "పంపలేకపోయాను — ఇంటర్నెట్ లేదు లేదా Telegram పని చేయలేదు";
        } catch (Throwable e) {
            return "రిపోర్ట్ పంపలేకపోయాను";
        }
    }

    // ================================================================ data

    /** All sugar readings of the last {@code days} days (ending today): {time millis, value}, oldest first. */
    static List<long[]> sugarPoints(Context c, int days) {
        List<long[]> out = new ArrayList<>();
        try {
            for (Row r : rows(c, Math.max(1, Math.min(days, 366)), false)) out.addAll(r.sugar);
            out.sort((a, b) -> Long.compare(a[0], b[0]));
        } catch (Throwable ignored) {}
        return out;
    }

    /** The days, oldest first, each with its log, doses and sugar readings. */
    private static List<Row> rows(Context c, int days, boolean doses) {
        List<Row> out = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_MONTH, -(days - 1));
        SimpleDateFormat key = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
        long now = System.currentTimeMillis();
        for (int i = 0; i < days; i++) {
            Row r = new Row();
            r.start = cal.getTimeInMillis();
            String day = key.format(cal.getTime());
            try { r.log = HomeCare.dayLog(c, day); } catch (Throwable ignored) {}
            if (r.log == null) r.log = new JSONObject();
            if (doses) try { r.doses = Medicine.dayDoses(c, day); } catch (Throwable ignored) {}
            if (r.doses == null || r.doses.length < 2) r.doses = new int[]{0, 0};
            r.sugar = readings(r.log, r.start, now);
            out.add(r);
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return out;
    }

    /** One day's sugar readings {time millis, value} in the order they were saved; bad entries are skipped. */
    private static List<long[]> readings(JSONObject log, long dayStart, long now) {
        List<long[]> out = new ArrayList<>();
        JSONArray a = log == null ? null : log.optJSONArray("sugar");
        int prev = -1;
        for (int i = 0; a != null && i < a.length(); i++) {
            try {
                Matcher m = READING.matcher(a.optString(i, ""));
                if (!m.find()) continue;
                int h = Integer.parseInt(m.group(1)), mi = Integer.parseInt(m.group(2)), v = Integer.parseInt(m.group(4));
                if (h > 23 || mi > 59 || v < 20 || v > 700) continue;
                int t;
                if (m.group(3) != null) {
                    if (h > 12) continue;
                    t = (h % 12 + (Character.toLowerCase(m.group(3).charAt(0)) == 'p' ? 12 : 0)) * 60 + mi;
                } else {
                    // saved as "h:mm" (no am/pm): 1-4 o'clock is afternoon, and readings are saved in order through the day
                    t = h * 60 + mi;
                    if (h < 12 && (h >= 1 && h <= 4 || t < prev)) {
                        t += 720;
                        if (dayStart + t * 60_000L > now + 600_000L) t -= 720; // not in the future
                    }
                }
                prev = Math.max(prev, t);
                out.add(new long[]{dayStart + t * 60_000L, v});
            } catch (Throwable ignored) {}
        }
        return out;
    }

    // ================================================================ the PDF

    /** The report for the last {@code days} days ending today (A4), or null if it could not be made. */
    static byte[] pdf(Context c, int days) {
        PdfDocument doc = null;
        Pager pg = null;
        try {
            int n = Math.max(1, Math.min(days, 62));
            String who = HomeCare.who(c);
            int low = HomeCare.sugarLow(c), high = HomeCare.sugarHigh(c);
            List<Row> rows = rows(c, n, true);
            doc = new PdfDocument();
            pg = new Pager(doc, who, range(rows.get(0).start, rows.get(rows.size() - 1).start));
            pg.open();
            table(pg, rows, low, high);
            summary(pg, rows, low, high);
            chart(pg, rows, low, high);
            medicines(pg, c);
            games(pg, c);
            pg.close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.writeTo(out);
            return out.toByteArray();
        } catch (Throwable e) {
            return null;
        } finally {
            if (doc != null) {
                try { if (pg != null && pg.page != null) doc.finishPage(pg.page); } catch (Throwable ignored) {}
                try { doc.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String range(long first, long last) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        a.setTimeInMillis(first);
        b.setTimeInMillis(last);
        SimpleDateFormat dmy = new SimpleDateFormat("d MMM yyyy", Locale.ENGLISH);
        if (first == last) return dmy.format(new Date(last));
        boolean sameYear = a.get(Calendar.YEAR) == b.get(Calendar.YEAR);
        return new SimpleDateFormat(sameYear ? "d MMM" : "d MMM yyyy", Locale.ENGLISH).format(new Date(first)) + " – " + dmy.format(new Date(last));
    }

    /** Pages: a blue header bar on top, the small gray note and page number at the bottom. */
    private static final class Pager {
        final PdfDocument doc;
        final String who, range;
        PdfDocument.Page page;
        Canvas cv;
        float y;
        int no;

        Pager(PdfDocument doc, String who, String range) { this.doc = doc; this.who = who; this.range = range; }

        void open() {
            no++;
            page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, no).create());
            cv = page.getCanvas();
            if (no == 1) {
                cv.drawRect(0, 0, W, 88, fill(BLUE));
                cv.drawText(who + " వారపు రిపోర్ట్", M, 42, paint(20, Color.WHITE, true));
                cv.drawText(range, M, 70, paint(13, 0xFFDCE8F8, false));
                y = 88 + 22;
            } else {
                cv.drawRect(0, 0, W, 40, fill(BLUE));
                cv.drawText(who + " వారపు రిపోర్ట్ · " + range, M, 26, paint(12, Color.WHITE, true));
                y = 40 + 20;
            }
        }

        /** Room for h more points on this page? If not, a new page (true). */
        boolean need(float h) {
            if (y + h <= BOTTOM) return false;
            close();
            open();
            return true;
        }

        void close() {
            if (page == null) return;
            try {
                cv.drawLine(M, H - 52, W - M, H - 52, stroke(GRID, 0.6f));
                Paint p = paint(9, GRAY, false);
                float fy = H - 38;
                String note = "ఇవి " + who + " Jarvis కి చెప్పినవి / బటన్లతో నొక్కినవి మాత్రమే. డాక్టర్ సలహాకి బదులు కాదు.";
                for (String l : wrap(note, p, W - 2 * M - 50)) { cv.drawText(l, M, fy, p); fy += 13; }
                Paint pn = paint(9, GRAY, false);
                pn.setTextAlign(Paint.Align.RIGHT);
                cv.drawText("పేజీ " + no, W - M, H - 38, pn);
            } catch (Throwable ignored) {}
            doc.finishPage(page);
            page = null;
            cv = null;
        }
    }

    // ---- the table

    private static void tableHeader(Pager pg) {
        String[] labels = {"తేదీ", HomeCare.MEALS[0][1], HomeCare.MEALS[1][1], HomeCare.MEALS[2][1], HomeCare.MEALS[3][1],
                "మాత్రలు", "నీళ్లు", "షుగర్ (mg/dL)"};
        Paint p = paint(11, BLUE_DARK, true);
        float[] sizes = new float[labels.length];
        List<List<String>> lines = new ArrayList<>();
        float hh = 30;
        for (int i = 0; i < labels.length; i++) {
            sizes[i] = fit(labels[i], p, COLS[i] - 8, 11);
            p.setTextSize(sizes[i]);
            List<String> ls = wrap(labels[i], p, COLS[i] - 8);
            lines.add(ls);
            hh = Math.max(hh, ls.size() * sizes[i] * 1.45f + 10);
        }
        pg.need(hh + 26); // the header and at least one row
        Canvas cv = pg.cv;
        float top = pg.y, mid = top + hh / 2, x = M;
        cv.drawRect(M, top, W - M, top + hh, fill(BLUE_LIGHT));
        for (int i = 0; i < labels.length; i++) {
            p.setTextSize(sizes[i]);
            List<String> ls = lines.get(i);
            float lh = sizes[i] * 1.45f, base = mid - (ls.size() - 1) * lh / 2 + sizes[i] * 0.35f;
            for (String l : ls) { center(cv, l, x + COLS[i] / 2, base, p); base += lh; }
            x += COLS[i];
        }
        grid(cv, top, hh);
        cv.drawLine(M, top + hh, W - M, top + hh, stroke(AXIS, 0.8f));
        pg.y += hh;
    }

    private static void table(Pager pg, List<Row> rows, int low, int high) {
        tableHeader(pg);
        Paint body = paint(BODY, TEXT, false), gray = paint(BODY, GRAY, false), red = paint(BODY, RED, true);
        SimpleDateFormat df = new SimpleDateFormat("EEE d MMM", Locale.ENGLISH);
        float sugarX = M;
        for (int i = 0; i < 7; i++) sugarX += COLS[i];
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int lines = r.sugar.isEmpty() ? 1 : sugarCell(null, r.sugar, sugarX + 6, 0, COLS[7] - 12, low, high);
            float rh = Math.max(26, lines * LH + 8);
            if (pg.need(rh)) tableHeader(pg);
            Canvas cv = pg.cv;
            float top = pg.y, mid = top + rh / 2, base = mid + BODY * 0.35f, x = M;
            if (i % 2 == 1) cv.drawRect(M, top, W - M, top + rh, fill(ZEBRA));
            cv.drawText(df.format(new Date(r.start)), x + 6, base, body);
            x += COLS[0];
            for (int k = 0; k < 4; k++) {
                if (r.log.has("meal_" + HomeCare.MEALS[k][0])) tick(cv, x + COLS[1 + k] / 2, mid);
                else dash(cv, x + COLS[1 + k] / 2, mid);
                x += COLS[1 + k];
            }
            int taken = r.doses[0], due = r.doses[1];
            center(cv, due == 0 ? "–" : taken + "/" + due, x + COLS[5] / 2, base, due == 0 ? gray : taken < due ? red : body);
            x += COLS[5];
            int water = r.log.optInt("water");
            center(cv, String.valueOf(water), x + COLS[6] / 2, base, water == 0 ? gray : body);
            if (r.sugar.isEmpty()) cv.drawText("–", sugarX + 6, base, gray);
            else sugarCell(cv, r.sugar, sugarX + 6, mid - (lines - 1) * LH / 2 + BODY * 0.35f, COLS[7] - 12, low, high);
            grid(cv, top, rh);
            pg.y += rh;
        }
    }

    /** Draws (or, with cv null, only measures) a day's readings "8:10 142, 20:30 180"; the number of lines. */
    private static int sugarCell(Canvas cv, List<long[]> rs, float x0, float base, float w, int low, int high) {
        Paint ok = paint(BODY, TEXT, false), bad = paint(BODY, RED, true);
        SimpleDateFormat tf = new SimpleDateFormat("H:mm", Locale.ENGLISH);
        float x = x0, space = ok.measureText(" "), comma = ok.measureText(",");
        int lines = 1;
        for (int i = 0; i < rs.size(); i++) {
            long[] r = rs.get(i);
            Paint p = r[1] < low || r[1] > high ? bad : ok;
            String s = tf.format(new Date(r[0])) + " " + r[1];
            boolean more = i < rs.size() - 1;
            float sw = p.measureText(s) + (more ? comma : 0);
            if (x > x0 && x + sw > x0 + w) { lines++; x = x0; base += LH; }
            if (cv != null) {
                cv.drawText(s, x, base, p);
                if (more) cv.drawText(",", x + p.measureText(s), base, ok);
            }
            x += sw + space;
        }
        return lines;
    }

    /** Totals under the table and what the marks mean. */
    private static void summary(Pager pg, List<Row> rows, int low, int high) {
        int taken = 0, due = 0, n = 0, sum = 0, mn = Integer.MAX_VALUE, mx = Integer.MIN_VALUE;
        for (Row r : rows) {
            taken += r.doses[0];
            due += r.doses[1];
            for (long[] s : r.sugar) { n++; sum += (int) s[1]; mn = Math.min(mn, (int) s[1]); mx = Math.max(mx, (int) s[1]); }
        }
        StringBuilder b = new StringBuilder();
        if (due > 0) b.append("మాత్రలు: ").append(taken).append("/").append(due).append(" వేసుకున్నారు");
        if (n > 0) {
            if (b.length() > 0) b.append("   ·   ");
            b.append("షుగర్: ").append(n).append(" రీడింగ్స్, సగటు ").append(sum / n).append(" (").append(mn).append("–").append(mx).append(")");
        }
        Paint p = paint(BODY, TEXT, false), g = paint(10, GRAY, false);
        List<String> ls = wrap(b.toString(), p, W - 2 * M);
        pg.need(8 + ls.size() * LH + 34); // the totals and up to two legend lines
        Canvas cv = pg.cv;
        pg.y += 8;
        if (b.length() > 0) for (String l : ls) { pg.y += LH; cv.drawText(l, M, pg.y - 5, p); }
        // legend: drawn tick / dash, then the red note
        pg.y += 15;
        float x = M + 6, mid = pg.y - 3.5f;
        tick(cv, x, mid);
        x += 10;
        String t1 = "తిన్నట్టు చెప్పారు", t2 = "చెప్పలేదు", t3 = "ఎరుపు: మాత్రలు తప్పాయి / షుగర్ " + low + "–" + high + " బయట";
        cv.drawText(t1, x, pg.y, g);
        x += g.measureText(t1) + 18;
        dash(cv, x, mid);
        x += 9;
        cv.drawText(t2, x, pg.y, g);
        x += g.measureText(t2) + 18;
        if (x + g.measureText(t3) > W - M) { pg.y += 14; x = M; }
        cv.drawText(t3, x, pg.y, paint(10, RED, false));
        pg.y += 4;
    }

    // ---- the sugar chart

    private static void chart(Pager pg, List<Row> rows, int low, int high) {
        List<long[]> pts = new ArrayList<>();
        for (Row r : rows) pts.addAll(r.sugar);
        pts.sort((a, b) -> Long.compare(a[0], b[0]));
        Paint title = paint(13, TEXT, true);
        if (pts.isEmpty()) {
            pg.need(56);
            pg.y += 30;
            pg.cv.drawText("షుగర్ రీడింగ్స్", M, pg.y, title);
            pg.y += 22;
            pg.cv.drawText("ఈ వారం షుగర్ రీడింగ్స్ చెప్పలేదు", M, pg.y, paint(BODY, GRAY, false));
            pg.y += 6;
            return;
        }
        float plotH = 170;
        pg.need(30 + 14 + plotH + 30);
        Canvas cv = pg.cv;
        pg.y += 30;
        cv.drawText("షుగర్ రీడింగ్స్ (mg/dL)", M, pg.y, title);
        float left = M + 32, right = W - M - 6, top = pg.y + 14, bottom = top + plotH;

        int lo = Math.min(low, high), hi = Math.max(low, high);
        for (long[] p : pts) { lo = Math.min(lo, (int) p[1]); hi = Math.max(hi, (int) p[1]); }
        int step = 25, yMin, yMax;
        while (true) {
            yMin = Math.max(0, (lo - 10) / step * step);
            yMax = ((hi + 10) / step + 1) * step;
            if ((yMax - yMin) / step <= 8) break;
            step *= 2;
        }
        long t0 = rows.get(0).start, t1 = rows.get(rows.size() - 1).start + DAY;

        // grid and y labels
        Paint lab = paint(9.5f, GRAY, false), labR = paint(9.5f, GRAY, false);
        labR.setTextAlign(Paint.Align.RIGHT);
        for (int v = yMin; v <= yMax; v += step) {
            float y = yOf(v, yMin, yMax, top, bottom);
            cv.drawLine(left, y, right, y, stroke(GRID, 0.5f));
            cv.drawText(String.valueOf(v), left - 4, y + 3.5f, labR);
        }
        // day lines and x labels
        SimpleDateFormat df = new SimpleDateFormat("EEE d", Locale.ENGLISH);
        int every = Math.max(1, (rows.size() + 9) / 10);
        for (int i = 0; i < rows.size(); i++) {
            long s = rows.get(i).start;
            float x = xOf(s, t0, t1, left, right);
            if (i > 0) cv.drawLine(x, top, x, bottom, stroke(DAY_LINE, 0.6f));
            if (i % every == 0) center(cv, df.format(new Date(s)), xOf(s + DAY / 2, t0, t1, left, right), bottom + 14, lab);
        }
        cv.drawLine(left, top, left, bottom, stroke(AXIS, 0.8f));
        cv.drawLine(left, bottom, right, bottom, stroke(AXIS, 0.8f));

        // the limits as dashed lines
        Paint lr = paint(9, RED, false), lo2 = paint(9, ORANGE, false);
        lr.setTextAlign(Paint.Align.RIGHT);
        lo2.setTextAlign(Paint.Align.RIGHT);
        float yh = yOf(high, yMin, yMax, top, bottom), yl = yOf(low, yMin, yMax, top, bottom);
        dashed(cv, left, right, yh, stroke(RED, 0.9f));
        dashed(cv, left, right, yl, stroke(ORANGE, 0.9f));
        cv.drawText("ఎక్కువ " + high, right - 2, yh - 3, lr);
        cv.drawText("తక్కువ " + low, right - 2, yl - 3, lo2);

        // the line, then the dots
        Paint line = stroke(BLUE, 1.4f);
        for (int i = 1; i < pts.size(); i++) {
            long[] a = pts.get(i - 1), b = pts.get(i);
            cv.drawLine(xOf(a[0], t0, t1, left, right), yOf(a[1], yMin, yMax, top, bottom),
                    xOf(b[0], t0, t1, left, right), yOf(b[1], yMin, yMax, top, bottom), line);
        }
        boolean labels = pts.size() <= 21;
        Paint white = fill(Color.WHITE), vOk = paint(8.5f, TEXT, false), vBad = paint(8.5f, RED, true);
        for (long[] p : pts) {
            float x = xOf(p[0], t0, t1, left, right), y = yOf(p[1], yMin, yMax, top, bottom);
            boolean out = p[1] < low || p[1] > high;
            cv.drawCircle(x, y, 3.8f, white);
            cv.drawCircle(x, y, 2.8f, fill(out ? RED : BLUE));
            if (labels) center(cv, String.valueOf(p[1]), x, y - 6, out ? vBad : vOk);
        }
        pg.y = bottom + 22;
    }

    private static float xOf(long t, long t0, long t1, float left, float right) {
        float f = t1 > t0 ? (t - t0) / (float) (t1 - t0) : 0;
        return left + Math.max(0, Math.min(1, f)) * (right - left);
    }

    private static float yOf(long v, int yMin, int yMax, float top, float bottom) {
        float f = yMax > yMin ? (v - yMin) / (float) (yMax - yMin) : 0;
        return bottom - Math.max(0, Math.min(1, f)) * (bottom - top);
    }

    private static void dashed(Canvas cv, float x0, float x1, float y, Paint p) {
        for (float x = x0; x < x1; x += 9) cv.drawLine(x, y, Math.min(x + 5, x1), y, p);
    }

    // ---- the medicines

    private static void medicines(Pager pg, Context c) {
        List<JSONObject> meds = null;
        try { meds = Medicine.all(c); } catch (Throwable ignored) {}
        Paint title = paint(13, TEXT, true), p = paint(BODY, TEXT, false);
        pg.need(30 + LH + 4);
        pg.y += 30;
        pg.cv.drawText("మాత్రలు (ఇప్పుడు ఉన్న జాబితా)", M, pg.y, title);
        pg.y += 4;
        if (meds == null || meds.isEmpty()) {
            pg.y += LH;
            pg.cv.drawText("మాత్రలు ఏవీ సెట్ చేయలేదు", M, pg.y, paint(BODY, GRAY, false));
            return;
        }
        for (JSONObject m : meds) {
            StringBuilder b = new StringBuilder("• ").append(m.optString("name", "?").trim());
            String dose = m.optString("dose", "").trim(), food = m.optString("food", "").trim();
            if (!dose.isEmpty()) b.append(" ").append(dose);
            JSONArray t = m.optJSONArray("times");
            StringBuilder times = new StringBuilder();
            for (int i = 0; t != null && i < t.length(); i++) {
                String s = t.optString(i, "").trim();
                if (s.isEmpty()) continue;
                if (times.length() > 0) times.append(", ");
                times.append(s);
            }
            if (times.length() > 0) b.append(" — ").append(times);
            if (!food.isEmpty()) b.append(" · ").append(food);
            boolean first = true;
            for (String l : wrap(b.toString(), p, W - 2 * M - 12)) {
                pg.need(LH);
                pg.y += LH;
                pg.cv.drawText(l, first ? M : M + 12, pg.y, p);
                first = false;
            }
        }
    }

    /** The week's games on the tablet (how many, which, won) — nothing else. */
    private static void games(Pager pg, Context c) {
        String g = Games.weekLine(c, 7);
        if (g.isEmpty()) return;
        Paint title = paint(13, TEXT, true), p = paint(BODY, TEXT, false);
        pg.need(30 + LH + 4);
        pg.y += 30;
        pg.cv.drawText("ఆటలు (మెదడుకి వ్యాయామం)", M, pg.y, title);
        pg.y += 4;
        for (String l : wrap(g.replace("🎲 ", ""), p, W - 2 * M)) {
            pg.need(LH);
            pg.y += LH;
            pg.cv.drawText(l, M, pg.y, p);
        }
    }

    // ================================================================ small drawing helpers

    private static Paint paint(float size, int color, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    private static Paint stroke(int color, float w) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(color);
        return p;
    }

    private static Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        return p;
    }

    private static void center(Canvas cv, String s, float cx, float base, Paint p) {
        cv.drawText(s, cx - p.measureText(s) / 2, base, p);
    }

    /** A green check mark (drawn, so no font is needed). */
    private static void tick(Canvas cv, float cx, float cy) {
        Paint p = stroke(GREEN, 1.8f);
        cv.drawLine(cx - 5, cy, cx - 1.5f, cy + 3.8f, p);
        cv.drawLine(cx - 1.5f, cy + 3.8f, cx + 5.5f, cy - 4.5f, p);
    }

    private static void dash(Canvas cv, float cx, float cy) {
        cv.drawLine(cx - 4, cy, cx + 4, cy, stroke(GRAY, 1.2f));
    }

    /** Light cell borders for one table row. */
    private static void grid(Canvas cv, float top, float h) {
        Paint g = stroke(GRID, 0.6f);
        float x = M;
        cv.drawLine(x, top, x, top + h, g);
        for (float w : COLS) { x += w; cv.drawLine(x, top, x, top + h, g); }
        cv.drawLine(M, top, W - M, top, g);
        cv.drawLine(M, top + h, W - M, top + h, g);
    }

    /** Word wrap to a width (a single word wider than the width stays on its own line). */
    private static List<String> wrap(String s, Paint p, float w) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String para : s.split("\n")) {
            String line = "";
            for (String word : para.split(" ")) {
                if (word.isEmpty()) continue;
                String t = line.isEmpty() ? word : line + " " + word;
                if (line.isEmpty() || p.measureText(t) <= w) line = t;
                else { out.add(line); line = word; }
            }
            if (!line.isEmpty()) out.add(line);
        }
        return out;
    }

    /** A text size (at most {@code size}) at which every word fits the width. */
    private static float fit(String s, Paint p, float w, float size) {
        float z = size;
        for (String word : s.split(" ")) {
            p.setTextSize(z);
            while (z > 7 && p.measureText(word) > w) { z -= 0.5f; p.setTextSize(z); }
        }
        p.setTextSize(z);
        return z;
    }
}
