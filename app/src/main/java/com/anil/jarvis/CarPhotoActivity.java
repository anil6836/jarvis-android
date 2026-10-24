package com.anil.jarvis;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 📍 Once, on his own car's photo: he taps the front wheel's centre, the rear wheel's centre, the headlight and the tail
 * light, and sets the wheels' size with a slider (two circles show it), so the car screen can turn his wheels and light
 * his lamps. A tap near a mark afterwards moves it. Nothing leaves the box.
 */
public class CarPhotoActivity extends Activity {
    private Bitmap bmp;
    private final float[] m = new float[CarPhoto.N];
    private int step;
    private float baseR;
    private TextView title;
    private Button save;
    private SeekBar size;
    private Marks view;
    private Prefs prefs;

    private static final String[] STEPS = {
            "1/4 · ముందు చక్రం (హెడ్‌లైట్ వైపుది) మధ్యలో తాకండి",
            "2/4 · వెనుక చక్రం మధ్యలో తాకండి",
            "3/4 · హెడ్‌లైట్ మీద తాకండి",
            "4/4 · వెనుక లైట్ (టెయిల్ లైట్) మీద తాకండి",
            "కింది స్లైడర్‌తో రెండు గుండ్రటి గీతలు టైర్ అంచుకి సరిపోయేలా చేసి ✓ సేవ్. (ఏదైనా గుర్తు తప్పైతే దాని దగ్గర తాకితే అక్కడికి జరుగుతుంది)"};

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 1;
            bmp = BitmapFactory.decodeFile(CarPhoto.file(this).getPath(), o);
        } catch (Throwable ignored) {}
        if (bmp == null) { Toast.makeText(this, "కారు ఫోటో దొరకలేదు: మళ్ళీ ఎంచుకోండి", Toast.LENGTH_LONG).show(); finish(); return; }
        float[] old = CarPhoto.marks(prefs);
        if (old != null) { System.arraycopy(old, 0, m, 0, CarPhoto.N); step = 4; baseR = m[CarPhoto.R]; }

        int p = Ui.dp(this, 12);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(p, p, p, p);
        col.setBackgroundColor(0xFF0B121B);
        title = Ui.text(this, "", 17, Ui.TEXT);
        title.setPadding(0, 0, 0, p / 2);
        col.addView(title);
        view = new Marks(this);
        col.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.text(this, "🛞 చక్రం సైజు", 15, Ui.MUTED));
        size = new SeekBar(this);
        size.setMax(100);
        size.setProgress(50);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user && baseR > 0) { m[CarPhoto.R] = baseR * (0.5f + v / 100f); view.invalidate(); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        row.addView(size, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(row);
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        Button again = new Button(this);
        again.setText("↺ మళ్ళీ");
        again.setOnClickListener(v -> { step = 0; baseR = 0; size.setProgress(50); show(); });
        save = new Button(this);
        save.setText("✓ సేవ్");
        save.setOnClickListener(v -> {
            String s = CarPhoto.format(m);
            if (CarPhoto.parse(s) == null) { Toast.makeText(this, "గుర్తులు సరిగా లేవు: ↺ మళ్ళీ", Toast.LENGTH_LONG).show(); return; }
            prefs.sp.edit().putString("car_photo_marks", s).putString("car_hero", "photo").apply();
            Toast.makeText(this, "సరే: కారు స్క్రీన్ మీద మీ కారు", Toast.LENGTH_LONG).show();
            finish();
        });
        btns.addView(again);
        btns.addView(save);
        col.addView(btns);
        setContentView(col);
        show();
    }

    private void show() {
        title.setText(STEPS[Math.min(step, 4)]);
        save.setEnabled(step >= 4);
        size.setEnabled(step >= 4);
        view.invalidate();
    }

    private void tapped(float nx, float ny) {
        float aspect = bmp.getHeight() / (float) bmp.getWidth();
        switch (step) {
            case 0: m[CarPhoto.FX] = nx; m[CarPhoto.FY] = ny; break;
            case 1:
                m[CarPhoto.RX] = nx; m[CarPhoto.RY] = ny;
                baseR = CarPhoto.guessRadius(m[CarPhoto.FX], m[CarPhoto.FY], nx, ny, aspect);
                m[CarPhoto.R] = baseR;
                size.setProgress(50);
                break;
            case 2: m[CarPhoto.HX] = nx; m[CarPhoto.HY] = ny; break;
            case 3: m[CarPhoto.TX] = nx; m[CarPhoto.TY] = ny; break;
            default: { // done: the nearest mark moves here
                int[][] pts = {{CarPhoto.FX, CarPhoto.FY}, {CarPhoto.RX, CarPhoto.RY}, {CarPhoto.HX, CarPhoto.HY}, {CarPhoto.TX, CarPhoto.TY}};
                int best = -1;
                double bd = 0.08;
                for (int i = 0; i < pts.length; i++) {
                    double d = Math.hypot(m[pts[i][0]] - nx, (m[pts[i][1]] - ny) * aspect);
                    if (d < bd) { bd = d; best = i; }
                }
                if (best >= 0) { m[pts[best][0]] = nx; m[pts[best][1]] = ny; }
                view.invalidate();
                return;
            }
        }
        step++;
        show();
    }

    /** The photo fitted in, with the marks so far. */
    private final class Marks extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float s, ox, oy;

        Marks(Context c) { super(c); }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            s = Math.min(w / (float) bmp.getWidth(), h / (float) bmp.getHeight());
            ox = (w - bmp.getWidth() * s) / 2f;
            oy = (h - bmp.getHeight() * s) / 2f;
            cv.save();
            cv.translate(ox, oy);
            cv.scale(s, s);
            p.setFilterBitmap(true);
            cv.drawBitmap(bmp, 0, 0, p);
            cv.restore();
            float bw = bmp.getWidth() * s, bh = bmp.getHeight() * s;
            p.setStrokeWidth(Math.max(3, w / 300f));
            if (step > 0) wheel(cv, ox + m[CarPhoto.FX] * bw, oy + m[CarPhoto.FY] * bh, m[CarPhoto.R] * bw, "ముందు");
            if (step > 1) wheel(cv, ox + m[CarPhoto.RX] * bw, oy + m[CarPhoto.RY] * bh, m[CarPhoto.R] * bw, "వెనుక");
            if (step > 2) dot(cv, ox + m[CarPhoto.HX] * bw, oy + m[CarPhoto.HY] * bh, 0xFFFFE066, "హెడ్‌లైట్");
            if (step > 3) dot(cv, ox + m[CarPhoto.TX] * bw, oy + m[CarPhoto.TY] * bh, 0xFFFF4D4D, "టెయిల్ లైట్");
        }

        private void wheel(Canvas cv, float x, float y, float r, String label) {
            p.setStyle(Paint.Style.STROKE);
            p.setColor(0xFF5FF3FF);
            if (r > 0) cv.drawCircle(x, y, r, p);
            dot(cv, x, y, 0xFF5FF3FF, label);
        }

        private void dot(Canvas cv, float x, float y, int c, String label) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xCC000000);
            cv.drawCircle(x, y, Ui.dp(getContext(), 9), p);
            p.setColor(c);
            cv.drawCircle(x, y, Ui.dp(getContext(), 6), p);
            p.setTextSize(Ui.dp(getContext(), 14));
            p.setColor(0xFF000000);
            cv.drawText(label, x + Ui.dp(getContext(), 11) + 1, y - Ui.dp(getContext(), 8) + 1, p);
            p.setColor(c);
            cv.drawText(label, x + Ui.dp(getContext(), 11), y - Ui.dp(getContext(), 8), p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP && s > 0) {
                float nx = (e.getX() - ox) / (bmp.getWidth() * s), ny = (e.getY() - oy) / (bmp.getHeight() * s);
                if (nx >= 0 && nx <= 1 && ny >= 0 && ny <= 1) tapped(nx, ny);
                performClick();
            }
            return true;
        }

        @Override public boolean performClick() { return super.performClick(); }
    }
}
