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

/** 🥛 The milk account's month on one A4 page (a calendar: what came each day, the days none came in red, the totals). */
final class HouseReport {
    private HouseReport() {}

    // ================================================================ the month's page (PDF)

    private static final String[] WEEK = {"ఆది", "సోమ", "మంగళ", "బుధ", "గురు", "శుక్ర", "శని"};

    /** One A4 page: the month as a calendar (what came each day, the days none came in red), the totals, given / left. */
    static Coder.Made pdf(Context c, String key, YearMonth ym) throws Exception {
        JSONObject st = House.load(c);
        JSONObject it = HouseBook.item(st, key);
        if (it == null) return null;
        LocalDate today = LocalDate.now();
        JSONObject b = HouseBook.bill(st, key, ym, today);
        LocalDate since = LocalDate.parse(it.optString("since"));
        String unit = it.optString("unit");
        PdfDocument doc = new PdfDocument();
        int W = 595, H = 842, M = 40;
        PdfDocument.Page pg = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, 1).create());
        Canvas cv = pg.getCanvas();
        TextPaint head = paint(20, Color.BLACK, true), body = paint(12.5f, Color.rgb(40, 40, 40), false);
        int y = M;
        y = draw(cv, HouseBook.of(it.optString("name")) + " లెక్క · " + HouseBook.MONTHS[ym.getMonthValue() - 1] + " " + ym.getYear()
                + (b.optBoolean("so_far") ? " (ఈరోజు వరకు)" : ""), head, M, y, W - 2 * M, Layout.Alignment.ALIGN_CENTER) + 10;
        y = draw(cv, HouseBook.setupLine(it, today), body, M, y, W - 2 * M, Layout.Alignment.ALIGN_CENTER) + 14;
        StringBuilder sum = new StringBuilder("మొత్తం: ").append(HouseBook.qtyText(unit, b.optDouble("qty"))).append(" · ").append(HouseBook.rs(b.optLong("amount")));
        JSONArray missed = b.optJSONArray("missed");
        sum.append("\nరాని రోజులు: ").append(missed == null || missed.length() == 0 ? "లేవు" : missed.length() + " (" + missed.join(", ") + " తేదీల్లో)");
        long paid = b.optLong("paid"), bal = b.optLong("balance");
        sum.append("\nఇచ్చింది: ").append(HouseBook.rs(paid)).append(" · ").append(bal == 0 && paid > 0 ? "మొత్తం ఇచ్చేశారు" : "ఇవ్వాల్సింది: " + HouseBook.rs(bal));
        TextPaint big = paint(14, Color.BLACK, true);
        y = draw(cv, sum.toString(), big, M + 10, y, W - 2 * M - 20, Layout.Alignment.ALIGN_NORMAL) + 16;

        int cw = (W - 2 * M) / 7, ch = 74;
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setColor(Color.rgb(170, 170, 170));
        line.setStrokeWidth(0.8f);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        TextPaint wk = paint(11.5f, Color.rgb(60, 60, 60), true);
        for (int i = 0; i < 7; i++) draw(cv, WEEK[i], wk, M + i * cw, y, cw, Layout.Alignment.ALIGN_CENTER);
        y += 20;
        int col = ym.atDay(1).getDayOfWeek().getValue() % 7; // (Sunday first)
        int row = 0;
        TextPaint dayN = paint(11, Color.rgb(80, 80, 80), true), qty = paint(12, Color.rgb(20, 90, 40), true), red = paint(12, Color.rgb(200, 30, 30), true),
                blue = paint(12, Color.rgb(25, 80, 200), true), grey = paint(11, Color.rgb(160, 160, 160), false), small = paint(9.5f, Color.rgb(200, 110, 0), true);
        double lastPrice = -1;
        for (int dnum = 1; dnum <= ym.lengthOfMonth(); dnum++) {
            LocalDate d = ym.atDay(dnum);
            int x0 = M + col * cw, y0 = y + row * ch;
            boolean counted = !d.isBefore(since) && !d.isAfter(today);
            double q = HouseBook.qtyOn(st, it, d), usual = HouseBook.usualOn(it, d), price = HouseBook.priceOn(it, d);
            if (counted && q == 0 && usual > 0) { fill.setColor(Color.rgb(253, 228, 228)); cv.drawRect(x0, y0, x0 + cw, y0 + ch, fill); }
            cv.drawRect(x0, y0, x0 + cw, y0 + ch, line);
            draw(cv, String.valueOf(dnum), dayN, x0 + 5, y0 + 4, cw - 10, Layout.Alignment.ALIGN_NORMAL);
            String txt;
            TextPaint tp;
            if (!counted) { txt = d.isBefore(since) ? "—" : ""; tp = grey; }
            else if (q == 0 && usual > 0) { txt = "రాలేదు"; tp = red; }
            else if (Math.abs(q - usual) > 1e-9) { txt = HouseBook.qtyText(unit, q); tp = blue; }
            else { txt = HouseBook.qtyText(unit, q); tp = qty; }
            if (!txt.isEmpty()) draw(cv, txt, tp, x0 + 3, y0 + 28, cw - 6, Layout.Alignment.ALIGN_CENTER);
            if (counted && price != lastPrice) {
                if (lastPrice >= 0) draw(cv, HouseBook.rate(price) + " నుంచి", small, x0 + 3, y0 + ch - 16, cw - 6, Layout.Alignment.ALIGN_CENTER);
                lastPrice = price;
            }
            if (++col == 7) { col = 0; row++; }
        }
        y += (row + (col > 0 ? 1 : 0)) * ch + 14;
        draw(cv, "ఆకుపచ్చ = మామూలుగా వచ్చాయి · నీలం = ఆ రోజు వేరేగా · ఎరుపు = రాలేదు · — = లెక్క మొదలవక ముందు", paint(10, Color.rgb(90, 90, 90), false),
                M, y, W - 2 * M, Layout.Alignment.ALIGN_CENTER);
        draw(cv, "Jarvis తయారు చేసింది · " + today, paint(9.5f, Color.rgb(130, 130, 130), false), M, H - M - 14, W - 2 * M, Layout.Alignment.ALIGN_CENTER);
        doc.finishPage(pg);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.writeTo(out);
        doc.close();
        return Coder.save(c, "Jarvis/reports", "Milk_" + ym + "_" + System.currentTimeMillis() % 100000 + ".pdf", "application/pdf", out.toByteArray());
    }

    private static TextPaint paint(float size, int color, boolean bold) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(color);
        if (bold) p.setTypeface(Typeface.DEFAULT_BOLD);
        return p;
    }

    /** Draws wrapped text at (x, y); returns the y under it. */
    private static int draw(Canvas cv, String text, TextPaint p, int x, int y, int w, Layout.Alignment al) {
        StaticLayout.Builder lb = StaticLayout.Builder.obtain(text, 0, text.length(), p, Math.max(10, w)).setAlignment(al).setLineSpacing(1, 1.1f);
        if (android.os.Build.VERSION.SDK_INT >= 28) // (Telugu marks above / below the line keep their room; API 28, so by name)
            try { lb.getClass().getMethod("setUseLineSpacingFromFallbacks", boolean.class).invoke(lb, true); } catch (Exception ignored) {}
        StaticLayout l = lb.build();
        cv.save();
        cv.translate(x, y);
        l.draw(cv);
        cv.restore();
        return y + l.getHeight();
    }

}
