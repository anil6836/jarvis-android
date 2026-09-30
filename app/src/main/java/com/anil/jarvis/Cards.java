package com.anil.jarvis;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * Made on the phone, Telugu drawn properly: a greeting card picture (birthday, anniversary, festivals...) and a letter
 * as a PDF (leave letter, complaint, request). Saved under Pictures / Documents → Jarvis; shared only when he says so.
 */
final class Cards {
    private Cards() {}

    static volatile Coder.Made lastCard, lastLetter;

    // ================================================================ greeting card

    /** {title, emoji, colour from, colour to} for the occasion. */
    static String[] style(String occasion, String festival) {
        String o = (occasion == null ? "" : occasion).toLowerCase(Locale.ROOT) + " " + (festival == null ? "" : festival).toLowerCase(Locale.ROOT);
        if (o.contains("anniv") || o.contains("పెళ్లి")) return new String[]{"పెళ్లిరోజు శుభాకాంక్షలు", "💍❤️", "#7F1D1D", "#DB2777"};
        if (o.contains("christmas") || o.contains("క్రిస్మస్")) return new String[]{"క్రిస్మస్ శుభాకాంక్షలు", "🎄⭐", "#064E3B", "#B91C1C"};
        if (o.contains("new year") || o.contains("నూతన")) return new String[]{"నూతన సంవత్సర శుభాకాంక్షలు", "🎆✨", "#1E1B4B", "#7C3AED"};
        if (o.contains("diwali") || o.contains("దీపావళి")) return new String[]{"దీపావళి శుభాకాంక్షలు", "🪔✨", "#431407", "#EA580C"};
        if (o.contains("sankranti") || o.contains("సంక్రాంతి")) return new String[]{"సంక్రాంతి శుభాకాంక్షలు", "🪁🌾", "#78350F", "#F59E0B"};
        if (o.contains("ugadi") || o.contains("ఉగాది")) return new String[]{"ఉగాది శుభాకాంక్షలు", "🌿🥭", "#14532D", "#65A30D"};
        if (o.contains("dussehra") || o.contains("దసరా") || o.contains("దశమి")) return new String[]{"విజయదశమి శుభాకాంక్షలు", "🏹🌼", "#7C2D12", "#DC2626"};
        if (o.contains("easter") || o.contains("ఈస్టర్")) return new String[]{"ఈస్టర్ శుభాకాంక్షలు", "✝️🌷", "#1E3A8A", "#0EA5E9"};
        if (o.contains("congrat") || o.contains("అభినందన")) return new String[]{"హృదయపూర్వక అభినందనలు", "🎊🏆", "#1E3A8A", "#9333EA"};
        if (o.contains("get well") || o.contains("కోలుకో")) return new String[]{"త్వరగా కోలుకోండి", "💐🙏", "#134E4A", "#0891B2"};
        if (festival != null && !festival.trim().isEmpty()) return new String[]{festival.trim() + " శుభాకాంక్షలు", "🎉✨", "#312E81", "#DB2777"};
        return new String[]{"పుట్టినరోజు శుభాకాంక్షలు", "🎂🎉", "#4C1D95", "#DB2777"};
    }

    private static StaticLayout layout(String text, TextPaint p, int width) { return layout(text, p, width, 0); }

    private static StaticLayout layout(String text, TextPaint p, int width, int maxLines) {
        StaticLayout.Builder b = StaticLayout.Builder.obtain(text, 0, text.length(), p, width).setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0, 1.12f);
        if (android.os.Build.VERSION.SDK_INT >= 28) b.setUseLineSpacingFromFallbacks(true); // Telugu marks need the Telugu font's line height
        if (maxLines > 0) b.setMaxLines(maxLines).setEllipsize(android.text.TextUtils.TruncateAt.END);
        return b.build();
    }

    static Coder.Made card(Context c, String name, String occasion, String festival, String message, String from) throws Exception {
        String[] st = style(occasion, festival == null ? "" : festival);
        int W = 1080, H = 1350;
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, W, H, Color.parseColor(st[2]), Color.parseColor(st[3]), Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, W, H, bg);
        // soft light circles
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setColor(0x22FFFFFF);
        cv.drawCircle(W * 0.15f, H * 0.12f, 220, glow);
        cv.drawCircle(W * 0.9f, H * 0.85f, 300, glow);
        glow.setColor(0x14FFFFFF);
        cv.drawCircle(W * 0.85f, H * 0.2f, 120, glow);
        // a thin gold frame
        Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(6);
        frame.setColor(0xCCFBBF24);
        cv.drawRoundRect(40, 40, W - 40, H - 40, 40, 40, frame);

        TextPaint emoji = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        emoji.setTextSize(170);
        StaticLayout e = layout(st[1], emoji, W - 160);
        cv.save(); cv.translate(80, 150); e.draw(cv); cv.restore();

        TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        title.setColor(0xFFFDE68A);
        title.setTextSize(84);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setShadowLayer(8, 0, 4, 0x66000000);
        StaticLayout t = layout(st[0], title, W - 200);
        int y = 420;
        cv.save(); cv.translate(100, y); t.draw(cv); cv.restore();
        y += t.getHeight() + 50;

        if (name != null && !name.trim().isEmpty()) {
            TextPaint np = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            np.setColor(Color.WHITE);
            np.setTextSize(110);
            np.setTypeface(Typeface.DEFAULT_BOLD);
            np.setShadowLayer(10, 0, 5, 0x66000000);
            StaticLayout n = layout(name.trim(), np, W - 200, 2);
            cv.save(); cv.translate(100, y); n.draw(cv); cv.restore();
            y += n.getHeight() + 50;
        }
        String msg = message == null || message.trim().isEmpty() ? defaultLine(st[0]) : message.trim();
        TextPaint mp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mp.setColor(0xF2FFFFFF);
        mp.setTextSize(52);
        StaticLayout m = layout(msg, mp, W - 240, 5);
        cv.save(); cv.translate(120, y); m.draw(cv); cv.restore();

        if (from != null && !from.trim().isEmpty()) {
            TextPaint fp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            fp.setColor(0xFFFDE68A);
            fp.setTextSize(56);
            fp.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC));
            StaticLayout f = layout("— " + from.trim(), fp, W - 200);
            cv.save(); cv.translate(100, H - 110 - f.getHeight()); f.draw(cv); cv.restore();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        bmp.recycle();
        String file = "Wish_" + (name == null ? "" : name.trim().replaceAll("[^\\p{L}\\p{M}\\p{N}]+", "_")) + "_" + System.currentTimeMillis() % 100000 + ".png";
        lastCard = Coder.save(c, "Jarvis/cards", file, "image/png", out.toByteArray());
        return lastCard;
    }

    private static String defaultLine(String title) {
        if (title.startsWith("పుట్టినరోజు")) return "ఈ ఏడాది మీకు ఆరోగ్యం, ఆనందం, విజయం నిండుగా ఉండాలని కోరుకుంటున్నాను.";
        if (title.startsWith("పెళ్లిరోజు")) return "మీ ఇద్దరి ప్రేమ, అనుబంధం ఎప్పటికీ ఇలాగే పెరుగుతూ ఉండాలి.";
        if (title.startsWith("త్వరగా")) return "మీరు త్వరగా పూర్తి ఆరోగ్యంతో మళ్లీ మాతో ఉండాలని ప్రార్థిస్తున్నాను.";
        if (title.startsWith("హృదయపూర్వక")) return "మీ కష్టానికి తగిన ఫలితం. ఇంకా ఎన్నో విజయాలు సాధించాలి.";
        return "మీకు, మీ కుటుంబానికి ఆనందం, ఆరోగ్యం, శుభం కలగాలని కోరుకుంటున్నాను.";
    }

    // ================================================================ letter as PDF

    static Coder.Made letter(Context c, String title, String text) throws Exception {
        PdfDocument doc = new PdfDocument();
        int W = 595, H = 842, M = 56;
        TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        body.setColor(Color.BLACK);
        body.setTextSize(12.5f);
        StaticLayout.Builder lb = StaticLayout.Builder.obtain(text, 0, text.length(), body, W - 2 * M)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2, 1.15f);
        if (android.os.Build.VERSION.SDK_INT >= 28) lb.setUseLineSpacingFromFallbacks(true);
        StaticLayout all = lb.build();
        TextPaint head = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        head.setColor(Color.BLACK);
        head.setTextSize(16);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        int line = 0, page = 1;
        while (line < all.getLineCount()) {
            PdfDocument.Page pg = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, page).create());
            Canvas cv = pg.getCanvas();
            int top = M;
            if (page == 1 && title != null && !title.trim().isEmpty()) {
                StaticLayout h = StaticLayout.Builder.obtain(title.trim(), 0, title.trim().length(), head, W - 2 * M)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
                cv.save(); cv.translate(M, top); h.draw(cv); cv.restore();
                top += h.getHeight() + 22;
            }
            int startY = all.getLineTop(line), room = H - M - top;
            int last = line;
            while (last < all.getLineCount() && all.getLineBottom(last) - startY <= room) last++;
            if (last == line) last = line + 1; // a single very tall line: draw it anyway
            int endY = all.getLineBottom(last - 1);
            cv.save();
            cv.translate(M, top - startY);
            cv.clipRect(-4, startY - 3, W - 2 * M + 4, endY + 3); // a little room for marks above / below the line
            all.draw(cv);
            cv.restore();
            doc.finishPage(pg);
            line = last;
            page++;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.writeTo(out);
        doc.close();
        String base = title == null || title.trim().isEmpty() ? "Letter" : title.trim().replaceAll("[^\\p{L}\\p{M}\\p{N}]+", "_");
        if (base.length() > 40) base = base.substring(0, 40);
        lastLetter = Coder.save(c, "Jarvis/letters", base + "_" + System.currentTimeMillis() % 100000 + ".pdf", "application/pdf", out.toByteArray());
        return lastLetter;
    }

    // ================================================================ show / share

    static void view(Context c, Coder.Made m) {
        c.startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW).setDataAndType(m.uri, m.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "తెరవండి").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** He picks the chat and taps send himself. WhatsApp first if it is there. */
    static void share(Context c, Coder.Made m, String caption) {
        Intent s = new Intent(Intent.ACTION_SEND).setType(m.mime).putExtra(Intent.EXTRA_STREAM, m.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        if (caption != null && !caption.trim().isEmpty()) s.putExtra(Intent.EXTRA_TEXT, caption.trim());
        try {
            c.getPackageManager().getPackageInfo("com.whatsapp", 0);
            c.startActivity(new Intent(s).setPackage("com.whatsapp"));
        } catch (Exception e) {
            c.startActivity(Intent.createChooser(s, "పంపండి").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

}
