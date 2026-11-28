package com.anil.jarvis;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * 🧱 (B, 28 Nov) The camera's photos of one thing → the 3D workshop, which builds it as a clean model part by part from
 * all the sides together. The photos become one numbered picture with each side named (from the phone's own compass /
 * position, never guessed), handed over in a short-lived file in the app's own cache that the workshop reads once and
 * deletes. Nothing is sent until his 🔨 on the workshop's cost sheet. The measured size from a real scan goes with it.
 */
final class ToWorkshop {
    private ToWorkshop() {}

    /** The numbered picture: each photo in a cell of its own shape (GridPlan), its number in the corner. */
    static Bitmap grid(List<Bitmap> pics) {
        int n = pics.size();
        Bitmap first = pics.get(0);
        int[] pl = GridPlan.plan(n, first.getWidth() / (float) Math.max(1, first.getHeight()), 1800, 1100);
        int cols = pl[0], rows = pl[1], cw = pl[2], ch = pl[3], gap = GridPlan.GAP;
        Bitmap out = Bitmap.createBitmap(cols * cw + (cols + 1) * gap, rows * ch + (rows + 1) * gap, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        c.drawColor(0xFF202020);
        Paint f = new Paint(Paint.FILTER_BITMAP_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint();
        t.setColor(0xFFFFFFFF);
        t.setTextSize(Math.max(28, cw / 9f));
        t.setFakeBoldText(true);
        bg.setColor(0xCC000000);
        for (int i = 0; i < n; i++) {
            Bitmap b = pics.get(i);
            int x = gap + (i % cols) * (cw + gap), y = gap + (i / cols) * (ch + gap);
            float s = Math.min(cw / (float) b.getWidth(), ch / (float) b.getHeight());
            float w = b.getWidth() * s, h = b.getHeight() * s;
            c.drawBitmap(b, null, new RectF(x + (cw - w) / 2, y + (ch - h) / 2, x + (cw + w) / 2, y + (ch + h) / 2), f);
            float box = t.getTextSize() * 1.5f;
            c.drawRect(x, y, x + box, y + box, bg);
            c.drawText(String.valueOf(i + 1), x + box * 0.28f, y + box * 0.74f, t);
        }
        return out;
    }

    /**
     * Opens the workshop with these photos (it shows them, the sides and the cost; nothing is sent before 🔨).
     * az / tilt: each photo's heading and tilt (null = not known); title: what it is (may be empty); sizeCm: the measured
     * size ("60 × 60 × 50 cm"), or null.
     */
    static void open(Activity a, List<Bitmap> pics, float[] az, int[] tilt, String title, String sizeCm) {
        if (pics == null || pics.isEmpty()) { Toast.makeText(a, "ఫోటోలు లేవు", Toast.LENGTH_SHORT).show(); return; }
        try {
            Bitmap g = grid(pics);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            g.compress(Bitmap.CompressFormat.JPEG, 84, o);
            g.recycle();
            File f = new File(a.getCacheDir(), "views_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream w = new FileOutputStream(f)) { w.write(o.toByteArray()); }
            String[] note = Pick3D.viewNote(az, tilt, pics.size());
            a.startActivity(new Intent(a, WorkshopActivity.class)
                    .putExtra(WorkshopActivity.EXTRA_VIEWS_FILE, f.getPath())
                    .putExtra(WorkshopActivity.EXTRA_VIEWS_NOTE, note[0])
                    .putExtra(WorkshopActivity.EXTRA_VIEWS_SAY, note[1])
                    .putExtra(WorkshopActivity.EXTRA_VIEWS_COUNT, pics.size())
                    .putExtra(WorkshopActivity.EXTRA_VIEWS_TITLE, title == null ? "" : title)
                    .putExtra(WorkshopActivity.EXTRA_VIEWS_SIZE, sizeCm == null ? "" : sizeCm)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception | OutOfMemoryError e) {
            Toast.makeText(a, "వర్క్‌షాప్‌కి పంపలేకపోయాను", Toast.LENGTH_SHORT).show();
        }
    }

    /** A title without its leading emoji and the "360°" / size tail ("🧊 డ్రమ్ 360°" → "డ్రమ్"). */
    static String plainTitle(String t) {
        if (t == null) return "";
        String s = t.replaceAll("^[^\\p{L}\\p{N}]+", "").replaceAll("\\s*360°\\s*$", "").replaceAll("\\s*\\d+×\\d+×\\d+ సెం.మీ\\s*$", "").trim();
        return s.startsWith("3D స్కాన్") ? "" : s;
    }
}
