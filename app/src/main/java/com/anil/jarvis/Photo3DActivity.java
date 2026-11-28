package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 🖼️ 3D ఫోటో (C, 28 Nov): one photo stands out of the screen. The phone's own depth model (MiDaS small, the same one ✨ 11
 * uses; downloaded once on his OK, 63 MB, checked) says how near each part is; tilting the phone or dragging moves near
 * parts against far ones. ₹0, nothing leaves the phone. Front only (what the photo never showed cannot appear). 🎬 makes a
 * 4-second clip of it moving. Kept in the scan history like every camera scan.
 */
public class Photo3DActivity extends Activity implements SensorEventListener {
    static final String EXTRA_FILE = "file", EXTRA_ID = "scan";

    /** From the camera: the picture is in a short-lived file of the app's own cache (read once, then deleted). */
    static void openPhoto(Context c, File f) {
        c.startActivity(new Intent(c, Photo3DActivity.class).putExtra(EXTRA_FILE, f.getPath()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    static void open(Context c, String id) {
        c.startActivity(new Intent(c, Photo3DActivity.class).putExtra(EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private Prefs p;
    private String id;
    private Stage stage;
    private TextView info, moveChip, tiltChip;
    private boolean destroyed, making;
    private SensorManager sensors;
    private boolean tiltOn;
    private float[] rot0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = new Prefs(this);
        getWindow().setStatusBarColor(0xFF04101B);
        getWindow().setNavigationBarColor(0xFF04101B);
        build();
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        String file = getIntent().getStringExtra(EXTRA_FILE);
        id = getIntent().getStringExtra(EXTRA_ID);
        if (file != null) {
            getIntent().removeExtra(EXTRA_FILE);
            fresh(new File(file));
        } else if (id != null) {
            work.execute(this::loadSaved);
        } else finish();
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private void build() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF04101B);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(8), dp(6), dp(4));
        TextView x = Ui.text(this, "✕", 20, 0xFFFFFFFF);
        x.setPadding(dp(10), dp(6), dp(10), dp(6));
        x.setOnClickListener(v -> finish());
        bar.addView(x);
        bar.addView(Ui.mono(this, "🖼️ 3D ఫోటో", 14, Ui.CYAN), new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(bar);
        FrameLayout box = new FrameLayout(this);
        stage = new Stage(this);
        box.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        page.addView(box, new LinearLayout.LayoutParams(-1, 0, 1));
        info = Ui.text(this, "", 13.5f, 0xFFFFFFFF);
        info.setLineSpacing(0, 1.2f);
        int pad = dp(12);
        info.setPadding(pad, dp(8), pad, dp(8));
        info.setBackground(Ui.round(this, 0xFF0B1E31, Ui.alpha(Ui.CYAN, 0x66), 14));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.setMargins(pad, dp(4), pad, 0);
        page.addView(info, ilp);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(dp(8), dp(6), dp(8), dp(10));
        moveChip = chip("🔄 కదలిక", true);
        moveChip.setOnClickListener(v -> { stage.auto = !stage.auto; lit(moveChip, stage.auto); });
        chips.addView(moveChip);
        tiltChip = chip("📱 వంచు", false);
        tiltChip.setOnClickListener(v -> setTilt(!tiltOn));
        chips.addView(tiltChip);
        TextView vid = chip("🎬 వీడియో", false);
        vid.setOnClickListener(v -> video());
        chips.addView(vid);
        TextView kase = chip("📁 కేస్‌కి", false);
        kase.setOnClickListener(v -> { if (id != null) CaseActivity.addTo(this, "scan", id, "🖼️ 3D ఫోటో", "3D ఫోటో"); });
        chips.addView(kase);
        hs.addView(chips);
        page.addView(hs);
        setContentView(page);
        info.setText("🖼️ లోతు చూస్తున్నాను…");
    }

    private TextView chip(String s, boolean on) {
        TextView t = Ui.text(this, s, 13.5f, 0xFFFFFFFF);
        t.setSingleLine(true);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        lit(t, on);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void lit(TextView t, boolean on) { t.setBackground(Ui.round(this, on ? Ui.alpha(Ui.CYAN, 0x55) : 0x40000000, on ? Ui.CYAN : 0x55FFFFFF, 999)); }

    // ================================================================ making it

    /** A new photo: the depth model once (his OK), the depth, kept in the scan history, shown. */
    private void fresh(File f) {
        Bitmap bm = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 1600) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            bm = BitmapFactory.decodeFile(f.getPath(), o2);
        } catch (Exception | OutOfMemoryError ignored) {
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete(); // (the camera's copy is not kept here; the scan history keeps its own)
        }
        if (bm == null) { Toast.makeText(this, "ఫోటో తెరవలేకపోయాను", Toast.LENGTH_SHORT).show(); finish(); return; }
        final Bitmap photo = bm;
        stage.setPhoto(photo);
        if (DepthMap.ready(this)) { make(photo); return; }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("🖼️ 3D ఫోటో")
                .setMessage("దీనికి ఫోన్‌లోనే పనిచేసే లోతు మోడల్ ఒక్కసారి కావాలి (" + DepthMap.sizeMb() + " MB, Wi-Fi మంచిది). తర్వాత ఎప్పుడూ నెట్ లేకుండా, ₹0. ఫోటో ఎక్కడికీ వెళ్ళదు. తెచ్చుకోనా?")
                .setPositiveButton("తెచ్చుకో", (d, w) -> make(photo))
                .setNegativeButton("వద్దు", (d, w) -> finish())
                .setCancelable(false).show();
    }

    private void make(Bitmap photo) {
        if (making) return;
        making = true;
        work.execute(() -> {
            String err = null;
            float[] depth = null;
            int dw = 0, dh = 0;
            try {
                DepthMap.ensure(this, t -> main.post(() -> info.setText(t)));
                main.post(() -> info.setText("🖼️ లోతు చూస్తున్నాను…"));
                try (DepthMap dm = new DepthMap(this)) {
                    dw = dm.width();
                    dh = dm.height();
                    depth = Parallax.smooth(dm.runFull(photo), dw, dh, 2);
                }
                id = save(photo, depth, dw, dh);
            } catch (Exception | OutOfMemoryError e) {
                err = "లోతు మోడల్ రాలేదు / పనిచేయలేదు (" + e.getClass().getSimpleName() + "). నెట్ చూసి మళ్ళీ ప్రయత్నించండి.";
            }
            final String er = err;
            final float[] dd = depth;
            final int fw = dw, fh = dh;
            main.post(() -> {
                making = false;
                if (destroyed) return;
                if (er != null) { info.setText(er); return; }
                stage.setDepth(dd, fw, fh);
                info.setText(words());
            });
        });
    }

    private String words() {
        return "🖼️ ఫోన్ వంచండి లేదా వేలితో లాగండి: దగ్గర ఉన్నవి ఒక వైపు, దూరంవి ఇంకో వైపు కదులుతాయి. ఫోన్‌లోనే లోతు చూసింది (₹0). ముందు వైపు మాత్రమే: ఫోటోలో లేనిది (వెనక వైపు) రాదు.";
    }

    private String save(Bitmap photo, float[] depth, int w, int h) throws Exception {
        JSONObject data = new JSONObject().put("title", "🖼️ 3D ఫోటో").put("kind", "photo3d").put("mode", "auto")
                .put("say", "🖼️ 3D ఫోటో (ఫోన్‌లోనే లోతు, ₹0)").put("noboxes", true).put("estimate", true);
        ScanStore.save(this, data, photo, "photo.jpg");
        String sid = data.optString("id");
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(ScanStore.file(this, sid, "depth.bin")))) {
            o.writeInt(w);
            o.writeInt(h);
            byte[] b = new byte[w * h];
            for (int i = 0; i < b.length; i++) b[i] = (byte) Math.round(Math.max(0, Math.min(1, depth[i])) * 255);
            o.write(b);
        }
        return sid;
    }

    private void loadSaved() {
        Bitmap photo = ScanStore.photo(this, id, "photo.jpg", 1600);
        float[] d = null;
        int w = 0, h = 0;
        try (DataInputStream in = new DataInputStream(new FileInputStream(ScanStore.file(this, id, "depth.bin")))) {
            w = in.readInt();
            h = in.readInt();
            if (w <= 0 || h <= 0 || w > 2048 || h > 2048) throw new IllegalStateException();
            byte[] b = new byte[w * h];
            in.readFully(b);
            d = new float[w * h];
            for (int i = 0; i < b.length; i++) d[i] = (b[i] & 0xFF) / 255f;
        } catch (Exception ignored) {}
        final Bitmap ph = photo;
        final float[] dd = d;
        final int fw = w, fh = h;
        main.post(() -> {
            if (destroyed) return;
            if (ph == null || dd == null) { Toast.makeText(this, "ఆ 3D ఫోటో దొరకలేదు", Toast.LENGTH_SHORT).show(); finish(); return; }
            stage.setPhoto(ph);
            stage.setDepth(dd, fw, fh);
            info.setText(words());
        });
    }

    // ================================================================ 📱 tilting the phone

    private void setTilt(boolean on) {
        tiltOn = on;
        lit(tiltChip, on);
        rot0 = null;
        if (on) {
            Sensor s = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (s == null) { Toast.makeText(this, "ఈ ఫోన్‌లో ఆ సెన్సార్ లేదు; వేలితో లాగండి", Toast.LENGTH_SHORT).show(); tiltOn = false; lit(tiltChip, false); return; }
            sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
            stage.auto = false;
            lit(moveChip, false);
        } else if (sensors != null) sensors.unregisterListener(this);
    }

    @Override public void onSensorChanged(SensorEvent e) {
        float[] r = new float[9], o = new float[3];
        SensorManager.getRotationMatrixFromVector(r, e.values);
        SensorManager.getOrientation(r, o);
        if (rot0 == null) rot0 = o.clone();
        float pitch = o[1] - rot0[1], roll = o[2] - rot0[2];
        stage.target(Math.max(-1, Math.min(1, roll / 0.35f)), Math.max(-1, Math.min(1, -pitch / 0.35f)));
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    @Override protected void onPause() { super.onPause(); if (tiltOn && sensors != null) sensors.unregisterListener(this); }
    @Override protected void onResume() { super.onResume(); if (tiltOn) { tiltOn = false; setTilt(true); } }
    @Override protected void onDestroy() { destroyed = true; super.onDestroy(); work.shutdownNow(); stage.release(); }

    // ================================================================ 🎬 a 4-second clip

    private void video() {
        if (!stage.ready() || making) return;
        making = true;
        info.setText("🎬 వీడియో తయారు చేస్తున్నాను… (4 సెకన్లు)");
        final Bitmap ph = stage.photo;
        final float[] d = stage.depth;
        final int dw = stage.dw, dh = stage.dh;
        work.execute(() -> {
            Coder.Made made = null;
            try {
                float s = Math.min(1f, 720f / Math.min(ph.getWidth(), ph.getHeight()));
                int w = Math.max(16, Math.round(ph.getWidth() * s / 16f) * 16), h = Math.max(16, Math.round(ph.getHeight() * s / 16f) * 16);
                File f = new File(getCacheDir(), "photo3d.mp4");
                HoloVideo v = new HoloVideo(f, w, h);
                Bitmap frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(frame);
                Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
                float focus = Parallax.focus(d, dw, dh);
                for (int i = 0; i < 120; i++) {
                    double a = Math.PI * 2 * i / 120;
                    float tx = (float) Math.sin(a), ty = (float) Math.cos(a) * 0.6f;
                    c.drawColor(0xFF02070F);
                    float[] verts = Parallax.verts(d, dw, dh, Stage.COLS, Stage.ROWS, 0, 0, w, h, tx * w * Stage.SHIFT, ty * w * Stage.SHIFT, focus, Stage.ZOOM);
                    c.drawBitmapMesh(ph, Stage.COLS, Stage.ROWS, verts, 0, null, 0, paint);
                    v.frameUpright(frame);
                    try { Thread.sleep(30); } catch (InterruptedException ignored) {}
                }
                v.finish();
                frame.recycle();
                byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                String name = "Jarvis_3D_photo_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmm", Locale.ENGLISH).format(new java.util.Date()) + ".mp4";
                made = Coder.save(this, "Jarvis/scans", name, "video/mp4", bytes);
            } catch (Exception | OutOfMemoryError ignored) {}
            final Coder.Made m = made;
            main.post(() -> {
                making = false;
                if (destroyed) return;
                if (m == null) { info.setText("వీడియో సేవ్ కాలేదు."); return; }
                info.setText("🎬 వీడియో సేవ్ అయింది: " + m.where);
                if (m.uri != null) new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setMessage("🎬 వీడియో సేవ్ అయింది. పంపనా?")
                        .setPositiveButton("📤 పంపు", (dd, ww) -> {
                            try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, m.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "ఎవరికి పంపాలి?")); } catch (Exception ignored) {}
                        }).setNegativeButton("వద్దు", null).show();
            });
        });
    }

    // ================================================================ the photo standing out

    static final class Stage extends View {
        static final int COLS = 48, ROWS = 64;
        static final float SHIFT = 0.045f, ZOOM = 1.1f;
        Bitmap photo;
        float[] depth;
        int dw, dh;
        boolean auto = true;
        private float tx, ty, gx, gy, focus = 0.5f, phase;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private float lx, ly;

        Stage(Context c) { super(c); }

        void setPhoto(Bitmap b) { photo = b; invalidate(); }
        void setDepth(float[] d, int w, int h) { depth = d; dw = w; dh = h; focus = Parallax.focus(d, w, h); invalidate(); }
        boolean ready() { return photo != null && depth != null; }
        void target(float x, float y) { gx = x; gy = y; }
        void release() { photo = null; }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: lx = e.getX(); ly = e.getY(); auto = false; return true;
                case MotionEvent.ACTION_MOVE:
                    gx = Math.max(-1, Math.min(1, gx + (e.getX() - lx) / (getWidth() * 0.35f)));
                    gy = Math.max(-1, Math.min(1, gy + (e.getY() - ly) / (getHeight() * 0.35f)));
                    lx = e.getX();
                    ly = e.getY();
                    return true;
                default: return true;
            }
        }

        @Override protected void onDraw(Canvas c) {
            Bitmap b = photo;
            if (b == null || b.isRecycled()) return;
            float W = getWidth(), H = getHeight();
            float s = Math.min(W / b.getWidth(), H / b.getHeight());
            float w = b.getWidth() * s, h = b.getHeight() * s, x0 = (W - w) / 2, y0 = (H - h) / 2;
            if (depth == null) { c.drawBitmap(b, null, new android.graphics.RectF(x0, y0, x0 + w, y0 + h), paint); return; }
            if (auto) { phase += 0.03f; gx = (float) Math.sin(phase); gy = (float) Math.cos(phase) * 0.6f; }
            tx += (gx - tx) * 0.15f;
            ty += (gy - ty) * 0.15f;
            c.save();
            c.clipRect(x0, y0, x0 + w, y0 + h);
            float[] verts = Parallax.verts(depth, dw, dh, COLS, ROWS, x0, y0, x0 + w, y0 + h, tx * w * SHIFT, ty * w * SHIFT, focus, ZOOM);
            c.drawBitmapMesh(b, COLS, ROWS, verts, 0, null, 0, paint);
            c.restore();
            postInvalidateOnAnimation();
        }
    }
}
