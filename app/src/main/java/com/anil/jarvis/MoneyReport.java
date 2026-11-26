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
import java.time.YearMonth;

/** 💰 His month's money on A4 (Telugu): in / out / saved, by kind, the biggest payees, the waste found, subscriptions, the safety net, goals, net worth, next steps. */
final class MoneyReport {
    private MoneyReport() {}

    private static final int W = 595, H = 842, M = 40;

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

        void room(int h) { if (y + h > H - M - 20) next(); }
    }

    static Coder.Made pdf(Context c, YearMonth ym) throws Exception {
        JSONObject b = new JSONObject(Finance.bundle(c));
        JSONArray months = b.optJSONArray("months");
        JSONObject m = null;
        for (int i = 0; months != null && i < months.length(); i++) if (ym.toString().equals(months.getJSONObject(i).optString("month"))) m = months.getJSONObject(i);
        if (m == null || m.optInt("count") == 0) return null;
        Pages p = new Pages();
        try {
            p.next();
            TextPaint head = paint(20, Color.BLACK, true), sub = paint(14, Color.rgb(20, 90, 60), true), body = paint(11.5f, Color.rgb(35, 35, 35), false),
                    big = paint(13, Color.BLACK, true), note = paint(9.5f, Color.rgb(100, 100, 100), false);
            p.y = draw(p.cv, "💰 " + FinCoach.monthTe(ym) + " డబ్బు రిపోర్ట్" + (m.optBoolean("so_far") ? " (ఈరోజు వరకు)" : ""), head, M, p.y, W - 2 * M,
                    Layout.Alignment.ALIGN_CENTER) + 14;
            StringBuilder t = new StringBuilder();
            t.append("వచ్చింది: ").append(FinMath.rs(m.optLong("came_in"))).append("   ·   ఖర్చు: ").append(FinMath.rs(m.optLong("spent")))
                    .append("   ·   పెట్టుబడి: ").append(FinMath.rs(m.optLong("invested")));
            if (m.has("save_rate")) t.append("\nమిగిలింది (జీతం − ఖర్చు): ").append(m.optLong("save_rate")).append("%");
            t.append("\nస్థిర ఖర్చులు (EMI, అద్దె, బిల్లులు…): ").append(FinMath.rs(m.optLong("fixed"))).append("   ·   మార్చగలిగేవి: ").append(FinMath.rs(m.optLong("flexible")));
            p.y = draw(p.cv, t.toString(), big, M, p.y, W - 2 * M, Layout.Alignment.ALIGN_NORMAL) + 14;

            p.y = section(p, "రకాల వారీగా", sub);
            JSONArray cats = m.optJSONArray("cats");
            long spent = Math.max(1, m.optLong("spent"));
            int[] cw = {260, 110, 70, 75};
            p.room(24);
            p.y = row(p.cv, new String[]{"రకం", "మొత్తం", "సార్లు", "%"}, cw, body, p.y, true);
            for (int i = 0; cats != null && i < cats.length(); i++) {
                JSONObject x = cats.getJSONObject(i);
                p.room(22);
                p.y = row(p.cv, new String[]{x.optString("te"), FinMath.rs(x.optLong("amount")), String.valueOf(x.optInt("n")), Math.round(x.optLong("amount") * 100.0 / spent) + "%"},
                        cw, body, p.y, false);
            }
            p.y += 12;
            JSONArray pay = m.optJSONArray("payees");
            if (pay != null && pay.length() > 0) {
                p.y = section(p, "ఎక్కువ డబ్బు వెళ్ళిన చోట్లు", sub);
                StringBuilder s = new StringBuilder();
                for (int i = 0; i < pay.length(); i++) {
                    JSONObject x = pay.getJSONObject(i);
                    s.append("• ").append(x.optString("name")).append(": ").append(FinMath.rs(x.optLong("amount"))).append(" (").append(x.optInt("n")).append(" సార్లు)\n");
                }
                p.room(height(s.toString(), body, W - 2 * M));
                p.y = draw(p.cv, s.toString().trim(), body, M, p.y, W - 2 * M, Layout.Alignment.ALIGN_NORMAL) + 12;
            }
            boolean thisMonth = YearMonth.now().equals(ym);
            if (thisMonth || YearMonth.now().minusMonths(1).equals(ym)) {
                list(p, "🕳️ వృథా అవుతున్న డబ్బు", b.optJSONArray("leaks"), "title", "tip", sub, body);
                JSONArray subs = b.optJSONArray("subs");
                if (subs != null && subs.length() > 0) {
                    p.y = section(p, "🔁 ప్రతి నెల కట్ అయ్యేవి", sub);
                    StringBuilder s = new StringBuilder();
                    for (int i = 0; i < subs.length(); i++) {
                        JSONObject x = subs.getJSONObject(i);
                        s.append("• ").append(x.optString("name")).append(": ").append(FinMath.rs(x.optLong("amount"))).append(" (ఏడాదికి ").append(FinMath.rs(x.optLong("yearly")))
                                .append(")").append("cancel".equals(x.optString("mark")) ? " — ఆపాలనుకున్నారు" : "").append("\n");
                    }
                    p.room(height(s.toString(), body, W - 2 * M));
                    p.y = draw(p.cv, s.toString().trim(), body, M, p.y, W - 2 * M, Layout.Alignment.ALIGN_NORMAL) + 12;
                }
            }
            list(p, "🛡️ రక్షణ", b.optJSONArray("protection"), "title", "now", sub, body);
            JSONArray goals = b.optJSONArray("goals");
            if (goals != null && goals.length() > 0) list(p, "🎯 లక్ష్యాలు", goals, "", "te", sub, body);
            JSONObject net = b.optJSONObject("net");
            if (net != null) {
                p.y = section(p, "📊 నికర ఆస్తి (అంచనా)", sub);
                String s = "బ్యాంక్ (SMS లో చివరి బ్యాలెన్స్): " + FinMath.rs(net.optLong("bank")) + "\nపెట్టుబడులు: " + FinMath.rs(net.optLong("invested"))
                        + "\nఇతరులు ఇవ్వాల్సింది: " + FinMath.rs(net.optLong("lent")) + "\nఅప్పులు: " + FinMath.rs(net.optLong("owe"))
                        + "\nమొత్తం: " + FinMath.rs(net.optLong("total"));
                p.room(height(s, body, W - 2 * M));
                p.y = draw(p.cv, s, body, M, p.y, W - 2 * M, Layout.Alignment.ALIGN_NORMAL) + 12;
            }
            list(p, "👣 తర్వాత చేయాల్సినవి", b.optJSONArray("priorities"), "", "te", sub, body);
            p.room(50);
            draw(p.cv, "బ్యాంక్ / UPI / కార్డ్ SMS + మీరు రాసిన ఖర్చుల నుంచి ఫోన్‌లోనే లెక్క (SMS లేని చెల్లింపులు లెక్కలో ఉండవు). ఇది మీ నిర్ణయానికి సమాచారం మాత్రమే; "
                    + "Jarvis SEBI రిజిస్టర్డ్ సలహాదారు కాదు.", note, M, p.y + 6, W - 2 * M, Layout.Alignment.ALIGN_NORMAL);
            p.doc.finishPage(p.pg);
            p.pg = null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            p.doc.writeTo(out);
            return Coder.save(c, "Jarvis/reports", "Money_" + ym + "_" + System.currentTimeMillis() % 100000 + ".pdf", "application/pdf", out.toByteArray());
        } finally {
            if (p.pg != null) try { p.doc.finishPage(p.pg); } catch (Exception ignored) {}
            try { p.doc.close(); } catch (Exception ignored) {}
        }
    }

    private static int section(Pages p, String title, TextPaint sub) {
        p.room(40);
        return draw(p.cv, title, sub, M, p.y, W - 2 * M, Layout.Alignment.ALIGN_NORMAL) + 6;
    }

    private static void list(Pages p, String title, JSONArray a, String headKey, String bodyKey, TextPaint sub, TextPaint body) throws Exception {
        if (a == null || a.length() == 0) return;
        p.y = section(p, title, sub);
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.getJSONObject(i);
            String s = "• " + (headKey.isEmpty() ? "" : x.optString(headKey) + ": ") + x.optString(bodyKey);
            int h = height(s, body, W - 2 * M);
            p.room(h + 4);
            p.y = draw(p.cv, s, body, M, p.y, W - 2 * M, Layout.Alignment.ALIGN_NORMAL) + 4;
        }
        p.y += 8;
    }

    private static int height(String text, TextPaint p, int w) { return layout(text, p, w, Layout.Alignment.ALIGN_NORMAL).getHeight(); }

    private static int row(Canvas cv, String[] cells, int[] cw, TextPaint tp, int y, boolean head) {
        int h = 0;
        for (int i = 0; i < cells.length; i++) h = Math.max(h, height(cells[i], tp, cw[i] - 8));
        h += 8;
        int x = M;
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setColor(Color.rgb(170, 170, 170));
        line.setStrokeWidth(0.8f);
        if (head) {
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(Color.rgb(228, 244, 234));
            int total = 0;
            for (int w : cw) total += w;
            cv.drawRect(M, y, M + total, y + h, fill);
        }
        for (int i = 0; i < cells.length; i++) {
            cv.drawRect(x, y, x + cw[i], y + h, line);
            draw(cv, cells[i], tp, x + 4, y + 4, cw[i] - 8, i == 0 ? Layout.Alignment.ALIGN_NORMAL : Layout.Alignment.ALIGN_CENTER);
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

    private static StaticLayout layout(String text, TextPaint p, int w, Layout.Alignment al) {
        StaticLayout.Builder lb = StaticLayout.Builder.obtain(text, 0, text.length(), p, Math.max(10, w)).setAlignment(al).setLineSpacing(1, 1.1f);
        if (android.os.Build.VERSION.SDK_INT >= 28)
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
