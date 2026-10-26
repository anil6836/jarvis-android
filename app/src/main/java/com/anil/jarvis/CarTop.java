package com.anil.jarvis;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * 🚙 His car on the car screen's map, seen from above (⑪, 26 Oct): our own drawings — an SUV, a sedan, a hatchback, a
 * sports car, a jeep, a concept car (none copies a maker's shape or badge) — in his colour, the glass dark, the lights
 * white in front and red behind, a soft shadow, and at night the headlights' beams on the road ahead; or the navigation
 * arrow; or his own photo from above (kept only on the box). Drawn nose up around (0, 0), then turned with the way he
 * goes. All of it drawn: any size.
 */
final class CarTop {
    private CarTop() {}

    /** The drawing's own units: the car is LEN long, nose at -LEN/2. */
    static final float LEN = 100;

    private static final class Shape {
        final float w, len;
        final Path body = new Path(), glass = new Path(), roof = new Path(), extra = new Path();
        final RectF[] heads, tails;
        Shape(float w, float len, RectF[] heads, RectF[] tails) { this.w = w; this.len = len; this.heads = heads; this.tails = tails; }
    }

    private static final java.util.Map<String, Shape> shapes = new java.util.HashMap<>();

    /** A rounded body: nose and tail rounded by these, the sides a little curved. */
    private static void body(Path p, float w, float len, float nose, float tail, float waist) {
        float hw = w / 2, top = -len / 2, bot = len / 2;
        p.moveTo(-hw + nose * 0.6f, top);
        p.lineTo(hw - nose * 0.6f, top);
        p.quadTo(hw, top, hw, top + nose);
        p.quadTo(hw + waist, 0, hw, bot - tail);
        p.quadTo(hw, bot, hw - tail * 0.6f, bot);
        p.lineTo(-hw + tail * 0.6f, bot);
        p.quadTo(-hw, bot, -hw, bot - tail);
        p.quadTo(-hw - waist, 0, -hw, top + nose);
        p.quadTo(-hw, top, -hw + nose * 0.6f, top);
        p.close();
    }

    /** The glass: a windscreen from y0 to y1 (narrower at its top), the side windows, the rear glass from y2 to y3. */
    private static void glass(Path g, float w, float y0, float y1, float y2, float y3, float inset) {
        float a = w / 2 - inset, b = a - 4;
        g.moveTo(-b, y0); g.quadTo(0, y0 - 4, b, y0); g.lineTo(a, y1); g.lineTo(-a, y1); g.close(); // windscreen
        g.moveTo(-a + 0.5f, y1 + 2); g.lineTo(-a - 1.5f, y2 - 2); g.lineTo(-a + 2.5f, y2 - 2); g.lineTo(-a + 2.5f, y1 + 2); g.close(); // left windows
        g.moveTo(a - 0.5f, y1 + 2); g.lineTo(a + 1.5f, y2 - 2); g.lineTo(a - 2.5f, y2 - 2); g.lineTo(a - 2.5f, y1 + 2); g.close(); // right windows
        g.moveTo(-a, y2); g.lineTo(a, y2); g.lineTo(b, y3); g.quadTo(0, y3 + 3, -b, y3); g.close(); // rear glass
    }

    private static Shape shape(String kind) {
        Shape s = shapes.get(kind);
        if (s != null) return s;
        switch (kind) {
            case "sedan":
                s = new Shape(40, 100, lamps(40, -50, 9, 4), lamps(40, 48, 8, 3));
                body(s.body, 40, 100, 10, 9, 1.2f);
                glass(s.glass, 40, -20, -12, 16, 24, 5);
                s.roof.addRoundRect(new RectF(-14, -12, 14, 16), 5, 5, Path.Direction.CW);
                break;
            case "hatch":
                s = new Shape(40, 86, lamps(40, -43, 9, 4), lamps(40, 41, 7, 4));
                body(s.body, 40, 86, 10, 7, 1f);
                glass(s.glass, 40, -16, -8, 22, 29, 5);
                s.roof.addRoundRect(new RectF(-14, -8, 14, 22), 5, 5, Path.Direction.CW);
                break;
            case "sports":
                s = new Shape(46, 98, lamps(46, -49, 11, 3), lamps(46, 47, 12, 2.5f));
                body(s.body, 46, 98, 14, 8, 3f);
                glass(s.glass, 46, -6, 2, 18, 26, 9);
                s.roof.addRoundRect(new RectF(-12, 2, 12, 18), 6, 6, Path.Direction.CW);
                s.extra.addRoundRect(new RectF(-20, 44, 20, 48), 2, 2, Path.Direction.CW); // the wing at the back
                break;
            case "jeep":
                s = new Shape(46, 90, lamps(46, -45, 7, 6), lamps(46, 43, 5, 6));
                body(s.body, 46, 90, 4, 4, 0f);
                glass(s.glass, 46, -18, -13, 26, 30, 4);
                s.roof.addRoundRect(new RectF(-17, -13, 17, 26), 2, 2, Path.Direction.CW);
                s.extra.addCircle(0, 47, 8, Path.Direction.CW); // the spare wheel on its back
                break;
            case "concept":
                s = new Shape(44, 100, new RectF[]{new RectF(-17, -50, 17, -47)}, new RectF[]{new RectF(-18, 47, 18, 49.5f)}); // (light bars across)
                body(s.body, 44, 100, 18, 12, 2.5f);
                s.glass.addOval(new RectF(-15, -22, 15, 30), Path.Direction.CW); // one glass canopy
                break;
            default: // "suv": tall and boxy, roof rails
                s = new Shape(46, 100, lamps(46, -50, 9, 5), lamps(46, 48, 7, 5));
                body(s.body, 46, 100, 8, 7, 1.5f);
                glass(s.glass, 46, -22, -14, 26, 34, 5);
                s.roof.addRoundRect(new RectF(-16, -14, 16, 26), 4, 4, Path.Direction.CW);
                s.extra.moveTo(-14, -12); s.extra.lineTo(-14, 24); s.extra.moveTo(14, -12); s.extra.lineTo(14, 24); // roof rails
                break;
        }
        shapes.put(kind, s);
        return s;
    }

    /** Two lamps at this end (y), each w wide and h deep, set in from the sides. */
    private static RectF[] lamps(float carW, float y, float w, float h) {
        float x = carW / 2 - 3;
        float y0 = y < 0 ? y + 1 : y - h - 1;
        return new RectF[]{new RectF(-x, y0, -x + w, y0 + h), new RectF(x - w, y0, x, y0 + h)};
    }

    private static final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final android.graphics.ColorFilter DARK = new android.graphics.LightingColorFilter(0xFF9AA4B4, 0);
    private static final Path arrow = new Path();
    private static Shader beam, shadow, sheen;
    private static int sheenColour;
    private static float sheenW;

    /**
     * His car at (x, y), turned so its nose points along {@code deg} on the screen (0 = up), lenPx long. kind: one of
     * CarDeck.MAP_CARS ("" and "arrow" are not cars: the dot is the map's own, the arrow here). night: the beams on.
     */
    static void draw(Canvas c, String kind, float x, float y, float deg, float lenPx, int colour, boolean night, int accent) {
        c.save();
        c.translate(x, y);
        c.rotate(deg);
        float k = lenPx / LEN;
        c.scale(k, k);
        if ("arrow".equals(kind)) { arrow(c, accent); c.restore(); return; }
        Shape s = shape(kind);
        p.setStyle(Paint.Style.FILL);
        p.setColorFilter(null);
        if (night) { // the headlights' beams on the road ahead
            if (beam == null) beam = new RadialGradient(0, -50, 60, new int[]{0x66FFF3CF, 0x22FFF3CF, 0x00FFF3CF}, new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP);
            p.setColor(0xFFFFFFFF);
            p.setShader(beam);
            Path b = new Path();
            b.moveTo(-s.w / 2 + 4, -s.len / 2); b.lineTo(-s.w / 2 - 18, -s.len / 2 - 58); b.quadTo(0, -s.len / 2 - 70, s.w / 2 + 18, -s.len / 2 - 58); b.lineTo(s.w / 2 - 4, -s.len / 2); b.close();
            c.drawPath(b, p);
            p.setShader(null);
        }
        if (shadow == null) shadow = new RadialGradient(0, 4, 58, new int[]{0x55000000, 0x00000000}, new float[]{0.5f, 1}, Shader.TileMode.CLAMP);
        p.setColor(0xFFFFFFFF);
        p.setShader(shadow);
        c.drawOval(new RectF(-s.w / 2 - 5, -s.len / 2 - 2, s.w / 2 + 5, s.len / 2 + 7), p);
        // the body in his colour, lit along the middle (a soft sheen), a thin darker edge
        if (sheen == null || sheenColour != colour || sheenW != s.w) {
            sheenColour = colour;
            sheenW = s.w;
            sheen = new LinearGradient(-s.w / 2, 0, s.w / 2, 0, new int[]{CarTheme.shade(colour, 0.62f), lighten(colour, 0.25f), CarTheme.shade(colour, 0.62f)}, new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP);
        }
        p.setShader(sheen);
        c.drawPath(s.body, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f);
        p.setColor(CarTheme.shade(colour, 0.45f));
        c.drawPath(s.body, p);
        p.setStyle(Paint.Style.FILL);
        // the roof a shade darker, the glass dark with a streak of sky
        p.setColor(CarTheme.shade(colour, 0.8f));
        c.drawPath(s.roof, p);
        p.setColor(0xF00B141D);
        c.drawPath(s.glass, p);
        p.setColor(0x40BFE3FF);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.4f);
        c.drawPath(s.glass, p);
        p.setStyle(Paint.Style.FILL);
        // its own extras: roof rails, the spare wheel, the wing
        if (!s.extra.isEmpty()) {
            p.setColor("jeep".equals(kind) ? 0xFF1A1E23 : CarTheme.shade(colour, 0.5f));
            if ("suv".equals(kind) || kind.isEmpty()) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f); p.setColor(0xFF2A3038); }
            c.drawPath(s.extra, p);
            p.setStyle(Paint.Style.FILL);
        }
        // the mirrors
        p.setColor(CarTheme.shade(colour, 0.7f));
        c.drawRoundRect(new RectF(-s.w / 2 - 4, -s.len / 2 + s.len * 0.27f, -s.w / 2 + 1, -s.len / 2 + s.len * 0.27f + 5), 2, 2, p);
        c.drawRoundRect(new RectF(s.w / 2 - 1, -s.len / 2 + s.len * 0.27f, s.w / 2 + 4, -s.len / 2 + s.len * 0.27f + 5), 2, 2, p);
        // the lights: white in front (brighter at night), red behind
        p.setColor(night ? 0xFFFFFBEA : 0xFFE9F3FF);
        for (RectF r : s.heads) c.drawRoundRect(r, 2, 2, p);
        p.setColor(night ? 0xFFFF3B30 : 0xFFC0262D);
        for (RectF r : s.tails) c.drawRoundRect(r, 1.5f, 1.5f, p);
        c.restore();
    }

    /** The navigation arrow: a white-edged chevron in the theme's light. */
    private static void arrow(Canvas c, int accent) {
        if (arrow.isEmpty()) { arrow.moveTo(0, -46); arrow.lineTo(34, 40); arrow.lineTo(0, 22); arrow.lineTo(-34, 40); arrow.close(); }
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x66000000);
        c.save();
        c.translate(0, 5);
        c.drawPath(arrow, p);
        c.restore();
        p.setColor(accent);
        c.drawPath(arrow, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(6);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(0xFFFFFFFF);
        c.drawPath(arrow, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** His own photo from above (the car alone, nose up after his turn), lenPx long, turned with the way. */
    static void photo(Canvas c, Bitmap b, float x, float y, float deg, float lenPx, boolean night) {
        if (b == null || b.getWidth() <= 0 || b.getHeight() <= 0) return;
        float k = lenPx / Math.max(b.getWidth(), b.getHeight()); // (the longer side: the car's length)
        float w = b.getWidth() * k, h = b.getHeight() * k;
        c.save();
        c.translate(x, y);
        c.rotate(deg);
        if (shadow == null) shadow = new RadialGradient(0, 4, 58, new int[]{0x55000000, 0x00000000}, new float[]{0.5f, 1}, Shader.TileMode.CLAMP);
        c.save();
        c.scale(lenPx / LEN, lenPx / LEN);
        p.setShader(shadow);
        p.setColor(0xFFFFFFFF);
        p.setStyle(Paint.Style.FILL);
        float sw = w / (lenPx / LEN), sl = h / (lenPx / LEN);
        c.drawOval(new RectF(-sw / 2 - 8, -sl / 2 - 4, sw / 2 + 8, sl / 2 + 10), p);
        p.setShader(null);
        c.restore();
        bp.setColorFilter(night ? DARK : null); // (a daylight photo, darker at night)
        c.drawBitmap(b, null, new RectF(-w / 2, -h / 2, w / 2, h / 2), bp);
        c.restore();
    }

    private static int lighten(int c, float f) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        r = Math.round(r + (255 - r) * f); g = Math.round(g + (255 - g) * f); b = Math.round(b + (255 - b) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
