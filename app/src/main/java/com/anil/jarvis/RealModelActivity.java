package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 📐 A real 3D scan, kept: the measured surface in its own colours, turned by the finger, pinched near or far. 📏 draws the
 * measured box with its sizes; 🏷️ asks his AI (only on his tap, cost said first) to name the parts on up to four of the
 * kept photos, and the names are pinned onto the surface where those photos looked; 🧱 hands the photos and the measured
 * size to the workshop for a clean model; 📤 saves a .ply file other 3D apps open.
 */
public class RealModelActivity extends Activity implements GLSurfaceView.Renderer {
    static final String EXTRA_ID = "scan";

    static void open(Context c, String id) {
        c.startActivity(new Intent(c, RealModelActivity.class).putExtra(EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private Prefs p;
    private String id;
    private JSONObject data;
    private GLSurfaceView gl;
    private Labels labels;
    private TextView info, autoChip, dimsChip, namesChip;
    private boolean destroyed, busy;

    // the surface and how it is looked at (GL thread reads, the screen writes)
    private volatile Tsdf.Mesh mesh;
    private Tsdf.Mesh uploaded;
    private float[] fit;          // Tsdf.fit
    private float[] mid = new float[3];
    private float radius = 0.5f;
    private volatile float yaw = 35, pitch = 22, zoom = 1f;
    private volatile boolean auto = true, showDims;
    private final MeshGl meshGl = new MeshGl();
    private boolean glOk;
    private int w, h;
    private final float[] proj = new float[16], view = new float[16], mvp = new float[16];
    private volatile List<Tag> tags = new ArrayList<>();

    /** A part's name pinned on the surface. */
    static final class Tag {
        String name, info;
        float[] p, n;
        float sx, sy;
        boolean front, onScreen;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = new Prefs(this);
        getWindow().setStatusBarColor(0xFF04101B);
        getWindow().setNavigationBarColor(0xFF04101B);
        id = getIntent().getStringExtra(EXTRA_ID);
        data = ScanStore.load(this, id);
        if (data == null) { Toast.makeText(this, "ఆ స్కాన్ దొరకలేదు", Toast.LENGTH_SHORT).show(); finish(); return; }
        build();
        readTags();
        work.execute(() -> {
            Tsdf.Mesh m = null;
            try { m = Tsdf.read(ScanStore.file(this, id, "mesh.bin")); } catch (Exception | OutOfMemoryError ignored) {}
            final Tsdf.Mesh mm = m;
            main.post(() -> {
                if (destroyed) return;
                if (mm == null) { info.setText("3D ఫైల్ తెరవలేకపోయాను."); return; }
                fit = Tsdf.fit(mm);
                float[] bb = mm.bounds();
                mid = new float[]{(bb[0] + bb[3]) / 2, (bb[1] + bb[4]) / 2, (bb[2] + bb[5]) / 2};
                radius = Math.max(0.05f, (float) Math.sqrt(sq(bb[3] - bb[0]) + sq(bb[4] - bb[1]) + sq(bb[5] - bb[2])) / 2);
                mesh = mm;
            });
        });
    }

    private static float sq(float x) { return x * x; }
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
        TextView t = Ui.mono(this, data.optString("title"), 14, Ui.CYAN);
        t.setSingleLine(true);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        title = t;
        page.addView(bar);

        FrameLayout stage = new FrameLayout(this);
        gl = new GLSurfaceView(this);
        gl.setEGLContextClientVersion(2);
        gl.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        gl.setRenderer(this);
        gl.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        stage.addView(gl, new FrameLayout.LayoutParams(-1, -1));
        labels = new Labels(this);
        stage.addView(labels, new FrameLayout.LayoutParams(-1, -1));
        labels.setOnTouchListener(this::touch);
        page.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        info = Ui.text(this, data.optString("say") + "\n🏷️ పేర్లు నొక్కితే parts పేర్లు (మీ AI, సుమారు ₹1–3, ముందే అడుగుతాను).", 13.5f, 0xFFFFFFFF);
        info.setLineSpacing(0, 1.2f);
        int pad = dp(12);
        info.setPadding(pad, dp(8), pad, dp(8));
        info.setBackground(Ui.round(this, 0xFF0B1E31, Ui.alpha(Ui.CYAN, 0x66), 14));
        info.setMaxLines(6);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.setMargins(pad, dp(4), pad, 0);
        page.addView(info, ilp);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(dp(8), dp(6), dp(8), dp(10));
        autoChip = chip("🔄 తిప్పు", true);
        autoChip.setOnClickListener(v -> { auto = !auto; lit(autoChip, auto); });
        chips.addView(autoChip);
        dimsChip = chip("📏 కొలతలు", false);
        dimsChip.setOnClickListener(v -> { showDims = !showDims; lit(dimsChip, showDims); if (showDims) info.setText(sizeWords()); });
        chips.addView(dimsChip);
        namesChip = chip("🏷️ పేర్లు", false);
        namesChip.setOnClickListener(v -> names());
        chips.addView(namesChip);
        TextView ws = chip("🧱 వర్క్‌షాప్ బొమ్మగా", false);
        ws.setOnClickListener(v -> toWorkshop());
        chips.addView(ws);
        TextView file = chip("📤 3D ఫైల్", false);
        file.setOnClickListener(v -> exportPly());
        chips.addView(file);
        TextView kase = chip("📁 కేస్‌కి", false);
        kase.setOnClickListener(v -> CaseActivity.addTo(this, "scan", id, data.optString("title"), ScanStore.cut(data.optString("say"), 160)));
        chips.addView(kase);
        hs.addView(chips);
        page.addView(hs);
        setContentView(page);
    }

    private TextView title;

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

    private String sizeWords() {
        JSONObject d = data.optJSONObject("dims_cm");
        if (d == null) return "కొలతలు లేవు.";
        return "📏 వెడల్పు " + d.optInt("width") + " · లోతు " + d.optInt("depth") + " · ఎత్తు " + d.optInt("height") + " సెం.మీ\n"
                + "ఫోన్ ARCore లోతుతో కొలిచింది: కొన్ని సెం.మీ తేడా రావచ్చు. నేల మీద ఉంటే ఎత్తు నేల నుంచి.";
    }

    @Override protected void onResume() { super.onResume(); gl.onResume(); }
    @Override protected void onPause() { super.onPause(); gl.onPause(); }
    @Override protected void onDestroy() { destroyed = true; super.onDestroy(); work.shutdownNow(); }

    // ================================================================ the finger

    private float lx, ly, pinch0, zoom0;
    private boolean moved;

    private boolean touch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: lx = e.getX(); ly = e.getY(); moved = false; break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) { pinch0 = dist(e); zoom0 = zoom; }
                break;
            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() >= 2 && pinch0 > 0) { zoom = Math.max(0.35f, Math.min(4f, zoom0 * pinch0 / Math.max(1f, dist(e)))); moved = true; break; }
                float dx = e.getX() - lx, dy = e.getY() - ly;
                if (Math.abs(dx) + Math.abs(dy) > 4) moved = true;
                yaw += dx * 0.45f;
                pitch = Math.max(-80, Math.min(85, pitch + dy * 0.35f));
                lx = e.getX();
                ly = e.getY();
                if (moved && auto) { auto = false; lit(autoChip, false); }
                break;
            case MotionEvent.ACTION_POINTER_UP: pinch0 = 0; break;
            case MotionEvent.ACTION_UP: if (!moved) tapAt(e.getX(), e.getY()); break;
            default: break;
        }
        return true;
    }

    private static float dist(MotionEvent e) { return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)); }

    /** A tap near a name says what that part is. */
    private void tapAt(float x, float y) {
        Tag best = null;
        float bd = dp(40);
        for (Tag t : tags) {
            if (!t.onScreen) continue;
            float d = (float) Math.hypot(t.sx - x, t.sy - y);
            if (d < bd) { bd = d; best = t; }
        }
        if (best != null) info.setText("🏷️ " + best.name + (best.info == null || best.info.isEmpty() ? "" : "\n" + best.info));
    }

    // ================================================================ GL thread

    @Override public void onSurfaceCreated(GL10 g, EGLConfig c) {
        GLES20.glClearColor(0.016f, 0.063f, 0.106f, 1f);
        glOk = meshGl.init();
        uploaded = null;
    }

    @Override public void onSurfaceChanged(GL10 g, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        w = width;
        h = height;
    }

    @Override public void onDrawFrame(GL10 g) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        Tsdf.Mesh m = mesh;
        if (!glOk || m == null || w == 0) return;
        if (m != uploaded) { meshGl.upload(m); uploaded = m; }
        if (auto) yaw += 0.35f;
        float d = radius * 2.6f * zoom;
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        float ex = mid[0] + d * (float) (Math.cos(pr) * Math.cos(yr)), ey = mid[1] + d * (float) Math.sin(pr), ez = mid[2] + d * (float) (Math.cos(pr) * Math.sin(yr));
        Matrix.perspectiveM(proj, 0, 40f, w / (float) h, Math.max(0.01f, d * 0.05f), d * 10f);
        Matrix.setLookAtM(view, 0, ex, ey, ez, mid[0], mid[1], mid[2], 0, 1, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);
        float lxv = ex - mid[0], lyv = ey - mid[1] + radius, lzv = ez - mid[2];
        float ll = (float) Math.sqrt(lxv * lxv + lyv * lyv + lzv * lzv);
        meshGl.draw(mvp, new float[]{lxv / ll, lyv / ll, lzv / ll}, 1f);
        float[] f = fit;
        if (f != null) {
            float floor = f[5];
            float across = Math.max(f[2] - f[1], f[4] - f[3]);
            meshGl.lines(mvp, MeshGl.ring(mid[0], floor, mid[2], across * 0.75f, 64), Ui.alpha(Ui.CYAN, 0x55), false);
            if (showDims) {
                double r = Math.toRadians(f[0]);
                float c = (float) Math.cos(r), s = (float) Math.sin(r);
                float ca = (f[1] + f[2]) / 2, cb = (f[3] + f[4]) / 2;
                float cx = ca * c - cb * s, cz = ca * s + cb * c;
                meshGl.lines(mvp, MeshGl.box(cx, (f[5] + f[6]) / 2, cz, f[2] - f[1], f[6] - f[5], f[4] - f[3], f[0]), 0xFFF2B24C, true);
                float ha = (f[2] - f[1]) / 2, hb = (f[4] - f[3]) / 2;
                float[] alongA = {cx + hb * s, f[5], cz - hb * c};   // the bottom edge that runs along a (its middle)
                float[] alongB = {cx + ha * c, f[5], cz + ha * s};   // the bottom edge that runs along b
                float[] up = {cx + ha * c + hb * s, (f[5] + f[6]) / 2, cz + ha * s - hb * c}; // an upright edge
                boolean aLonger = f[2] - f[1] >= f[4] - f[3];
                dimAnchors = new float[][]{aLonger ? alongA : alongB, aLonger ? alongB : alongA, up};
            }
        }
        project(ex, ey, ez);
    }

    private volatile float[][] dimAnchors;
    private final float[] clip = new float[4], pt = new float[4];

    /** Where each name (and each size label) is on the screen now; facing away = dim. */
    private void project(float ex, float ey, float ez) {
        List<Tag> ts = tags;
        for (Tag t : ts) {
            float[] s = toScreen(t.p);
            t.onScreen = s != null;
            if (s != null) { t.sx = s[0]; t.sy = s[1]; }
            t.front = t.n == null || (ex - t.p[0]) * t.n[0] + (ey - t.p[1]) * t.n[1] + (ez - t.p[2]) * t.n[2] > 0;
        }
        float[][] da = showDims ? dimAnchors : null;
        float[][] ds = null;
        if (da != null) { ds = new float[3][]; for (int i = 0; i < 3; i++) ds[i] = toScreen(da[i]); }
        final float[][] dsf = ds;
        labels.post(() -> labels.set(ts, dsf));
    }

    private float[] toScreen(float[] p3) {
        pt[0] = p3[0]; pt[1] = p3[1]; pt[2] = p3[2]; pt[3] = 1;
        Matrix.multiplyMV(clip, 0, mvp, 0, pt, 0);
        if (clip[3] <= 0.0001f) return null;
        float x = clip[0] / clip[3], y = clip[1] / clip[3];
        if (x < -1.2f || x > 1.2f || y < -1.2f || y > 1.2f) return null;
        return new float[]{(x + 1) / 2 * w, (1 - y) / 2 * h};
    }

    // ================================================================ 🏷️ names from his AI, pinned where the photos looked

    private void readTags() {
        List<Tag> out = new ArrayList<>();
        JSONArray a = data.optJSONArray("tags");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            JSONArray pp = o == null ? null : o.optJSONArray("p"), nn = o == null ? null : o.optJSONArray("n");
            if (pp == null || pp.length() != 3) continue;
            Tag t = new Tag();
            t.name = o.optString("name");
            t.info = o.optString("info");
            t.p = new float[]{(float) pp.optDouble(0), (float) pp.optDouble(1), (float) pp.optDouble(2)};
            if (nn != null && nn.length() == 3) t.n = new float[]{(float) nn.optDouble(0), (float) nn.optDouble(1), (float) nn.optDouble(2)};
            out.add(t);
        }
        tags = out;
        if (!out.isEmpty()) lit(namesChip, true);
    }

    private void names() {
        if (!tags.isEmpty()) { labels.hidden = !labels.hidden; lit(namesChip, !labels.hidden); labels.invalidate(); return; }
        if (busy) return;
        JSONArray kf = data.optJSONArray("kf");
        if (kf == null || kf.length() == 0) { Toast.makeText(this, "ఈ స్కాన్‌లో ఫోటోలు లేవు (నెమ్మదిగా తిరిగితే ఫోటోలు తీసుకుంటాను)", Toast.LENGTH_LONG).show(); return; }
        if (!Net.online(this) || !p.hasBrain()) { Toast.makeText(this, "పేర్లకి నెట్ + AI key కావాలి", Toast.LENGTH_SHORT).show(); return; }
        if (mesh == null) return;
        final List<Integer> pick = spread(kf, 4);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("🏷️ parts పేర్లు")
                .setMessage("ఈ స్కాన్ " + pick.size() + " ఫోటోలు మీ AI కి ఒక్కసారి వెళ్తాయి (సుమారు ₹1–3). పేర్లు 3D మీద వాటి చోట్లో పెడతాను. సరేనా?")
                .setPositiveButton("సరే", (d, w) -> askNames(kf, pick))
                .setNegativeButton("వద్దు", null).show();
    }

    /** Up to `max` photos spread round the thing. */
    private static List<Integer> spread(JSONArray kf, int max) {
        List<Integer> out = new ArrayList<>();
        int n = kf.length();
        if (n <= max) { for (int i = 0; i < n; i++) out.add(i); return out; }
        float a0 = (float) kf.optJSONObject(0).optDouble("az", 0);
        for (int k = 0; k < max; k++) {
            float want = a0 + 360f * k / max;
            int best = -1;
            float bd = 999;
            for (int i = 0; i < n; i++) {
                if (out.contains(i)) continue;
                float d = Math.abs((((float) kf.optJSONObject(i).optDouble("az", 0) - want) % 360 + 540) % 360 - 180);
                if (d < bd) { bd = d; best = i; }
            }
            if (best >= 0) out.add(best);
        }
        return out;
    }

    private void askNames(JSONArray kf, List<Integer> pick) {
        busy = true;
        info.setText("🏷️ పేర్లు అడుగుతున్నాను…");
        final Tsdf.Mesh m = mesh;
        work.execute(() -> {
            String err = null;
            List<Tag> got = new ArrayList<>();
            String aiTitle = null, aiSay = null;
            List<Bitmap> pics = new ArrayList<>();
            try {
                for (int i : pick) { Bitmap b = ScanStore.photo(this, id, kf.getJSONObject(i).optString("file"), 1100); if (b != null) pics.add(b); }
                if (pics.isEmpty()) throw new IllegalStateException("no photos");
                Bitmap grid = ToWorkshop.grid(pics);
                JSONObject dims = data.optJSONObject("dims_cm");
                String size = dims == null ? "" : dims.optInt("width") + " × " + dims.optInt("depth") + " × " + dims.optInt("height") + " cm";
                String reply = Brain.oneShot(p, namesSystem(size), "This picture is a grid of " + pics.size() + " numbered photos (1, 2, 3… left to right, top to bottom) of ONE object from different sides. Name its visible parts per photo in the JSON.",
                        JarvisCamera.jpeg(grid, 1800, 80), false, 2600);
                grid.recycle();
                JSONObject o = parse(reply);
                if (o == null) throw new IllegalStateException("no answer");
                Recon.scrub(o);
                aiTitle = o.optString("title");
                aiSay = o.optString("say");
                JSONArray views = o.optJSONArray("views");
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (int v = 0; views != null && v < views.length() && v < pick.size(); v++) {
                    JSONObject vo = views.optJSONObject(v);
                    int photo = vo == null ? v : Math.max(0, Math.min(pick.size() - 1, vo.optInt("n", v + 1) - 1));
                    JSONObject k = kf.getJSONObject(pick.get(photo));
                    JSONArray items = vo == null ? null : vo.optJSONArray("items");
                    for (int j = 0; items != null && j < items.length() && got.size() < 24; j++) {
                        JSONObject it = items.optJSONObject(j);
                        JSONArray bx = it == null ? null : it.optJSONArray("box");
                        String nm = it == null ? "" : it.optString("name").trim();
                        if (nm.isEmpty() || bx == null || bx.length() != 4 || !seen.add(nm.toLowerCase(Locale.ROOT))) continue;
                        float[] hit = pin(m, k, (float) (bx.optDouble(0) + bx.optDouble(2)) / 2000f, (float) (bx.optDouble(1) + bx.optDouble(3)) / 2000f);
                        if (hit == null) continue;
                        Tag t = new Tag();
                        t.name = nm;
                        t.info = it.optString("info");
                        t.p = new float[]{hit[0], hit[1], hit[2]};
                        t.n = new float[]{hit[3], hit[4], hit[5]};
                        got.add(t);
                    }
                }
                if (got.isEmpty()) throw new IllegalStateException("no parts placed");
                JSONArray ta = new JSONArray();
                for (Tag t : got) ta.put(new JSONObject().put("name", t.name).put("info", t.info).put("p", arr(t.p)).put("n", arr(t.n)));
                data.put("tags", ta);
                JSONObject d = data.optJSONObject("dims_cm");
                if (aiTitle != null && !aiTitle.trim().isEmpty() && d != null)
                    data.put("title", "📐 " + aiTitle.trim() + " " + d.optInt("width") + "×" + d.optInt("depth") + "×" + d.optInt("height") + " సెం.మీ");
                if (aiSay != null && !aiSay.trim().isEmpty()) data.put("names_say", aiSay.trim());
                ScanStore.save(this, data, null, null);
            } catch (Http.ApiError e) {
                err = "AI జవాబు ఇవ్వలేదు: " + Models.explain(p, e);
            } catch (Exception e) {
                err = "పేర్లు పెట్టలేకపోయాను (" + e.getMessage() + "). నెట్ చూసి మళ్ళీ ప్రయత్నించండి.";
            } finally {
                for (Bitmap b : pics) b.recycle();
            }
            final String er = err, say = aiSay;
            main.post(() -> {
                busy = false;
                if (destroyed) return;
                if (er != null) { info.setText(er); return; }
                tags = got;
                labels.hidden = false;
                lit(namesChip, true);
                title.setText(data.optString("title"));
                info.setText("🏷️ " + got.size() + " parts పేర్లు పెట్టాను. పేరు నొక్కితే దాని పని." + (say == null || say.isEmpty() ? "" : "\n" + say));
            });
        });
    }

    /** A photo's point (fractions of the upright photo) onto the surface: {x, y, z, nx, ny, nz, distance} or null. */
    static float[] pin(Tsdf.Mesh m, JSONObject k, float fu, float fv) {
        JSONArray pa = k.optJSONArray("pose");
        if (pa == null || pa.length() != 16) return null;
        float[] pose = new float[16];
        for (int i = 0; i < 16; i++) pose[i] = (float) pa.optDouble(i);
        return Tsdf.pin(m, pose, (float) k.optDouble("fx"), (float) k.optDouble("fy"), (float) k.optDouble("cx"), (float) k.optDouble("cy"),
                k.optInt("iw"), k.optInt("ih"), k.optInt("rot"), fu, fv);
    }

    private static JSONArray arr(float[] v) throws Exception {
        JSONArray a = new JSONArray();
        for (float x : v) a.put(Math.round(x * 10000) / 10000.0);
        return a;
    }

    static String namesSystem(String size) {
        return "You are JARVIS, naming the parts of ONE real object for Anil (Telangana) from numbered photos of it taken from different sides"
                + (size.isEmpty() ? "" : " (the phone measured the object: " + size + ")") + ". Answer only in simple Telugu. Reply with ONLY this JSON: "
                + "{\"title\":\"short Telugu name of the object\",\"say\":\"2-3 Telugu lines about it\",\"views\":[{\"n\":1,\"items\":[{\"name\":\"Telugu part name\","
                + "\"box\":[x0,y0,x1,y1],\"info\":\"one short Telugu line: what it is / does\"}]}]}. box = integers 0-1000 of THAT numbered photo's own width and height, "
                + "tight round the part. Up to 8 items per photo: real, visible parts only (never invent a part you cannot see); the same part in two photos once. "
                + "People only as 'మనిషి', never who. No ID numbers, phone numbers or number plates.";
    }

    private static JSONObject parse(String reply) {
        try {
            String s = reply == null ? "" : reply.trim();
            int a = s.indexOf('{'), z = s.lastIndexOf('}');
            return a < 0 || z <= a ? null : new JSONObject(s.substring(a, z + 1));
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================ 🧱 to the workshop, 📤 a 3D file

    private void toWorkshop() {
        JSONArray kf = data.optJSONArray("kf");
        if (kf == null || kf.length() == 0) { Toast.makeText(this, "ఈ స్కాన్‌లో ఫోటోలు లేవు", Toast.LENGTH_SHORT).show(); return; }
        work.execute(() -> {
            List<Bitmap> pics = new ArrayList<>();
            List<Float> az = new ArrayList<>();
            for (int i = 0; i < kf.length() && pics.size() < 8; i++) {
                JSONObject k = kf.optJSONObject(i);
                Bitmap b = k == null ? null : ScanStore.photo(this, id, k.optString("file"), 1100);
                if (b == null) continue;
                pics.add(b);
                az.add((float) k.optDouble("az", 0));
            }
            float[] a = new float[az.size()];
            for (int i = 0; i < a.length; i++) a[i] = az.get(i);
            JSONObject d = data.optJSONObject("dims_cm");
            String size = d == null ? null : d.optInt("width") + " × " + d.optInt("depth") + " × " + d.optInt("height") + " cm (width × depth × height, measured by the phone)";
            main.post(() -> {
                if (destroyed) { for (Bitmap b : pics) b.recycle(); return; }
                ToWorkshop.open(this, pics, a, null, ToWorkshop.plainTitle(data.optString("title")), size);
                for (Bitmap b : pics) b.recycle();
            });
        });
    }

    private void exportPly() {
        final Tsdf.Mesh m = mesh;
        if (m == null || busy) return;
        busy = true;
        Toast.makeText(this, "📤 3D ఫైల్ తయారు చేస్తున్నాను…", Toast.LENGTH_SHORT).show();
        work.execute(() -> {
            Coder.Made made = null;
            try {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                Tsdf.writePly(m, o);
                String name = "Jarvis_3D_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmm", Locale.ENGLISH).format(new java.util.Date()) + ".ply";
                made = Coder.save(this, "Jarvis/3d", name, "application/octet-stream", o.toByteArray());
            } catch (Exception | OutOfMemoryError ignored) {}
            final Coder.Made mm = made;
            main.post(() -> {
                busy = false;
                if (destroyed) return;
                if (mm == null) { Toast.makeText(this, "ఫైల్ సేవ్ కాలేదు", Toast.LENGTH_SHORT).show(); return; }
                AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("📤 3D ఫైల్ సేవ్ అయింది")
                        .setMessage(mm.where + "\n\n.ply ఫైల్: రంగులతో. MeshLab, Blender, 3D వ్యూయర్ యాప్‌లు తెరుస్తాయి.")
                        .setNegativeButton("సరే", null);
                if (mm.uri != null) b.setPositiveButton("📤 పంపు", (d, w) -> {
                    try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, mm.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "ఎవరికి పంపాలి?")); } catch (Exception ignored) {}
                });
                b.show();
            });
        });
    }

    // ================================================================ the names and sizes over the 3D

    static final class Labels extends View {
        private List<Tag> tags = new ArrayList<>();
        private float[][] dims;
        boolean hidden;
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RealModelActivity act;

        Labels(RealModelActivity a) {
            super(a);
            act = a;
            txt.setColor(0xFFFFFFFF);
            txt.setTextSize(Ui.dp(a, 12.5f));
            bg.setColor(0xCC061424);
            dot.setColor(Ui.CYAN);
        }

        void set(List<Tag> t, float[][] d) { tags = t; dims = d; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            if (!hidden) for (Tag t : tags) {
                if (!t.onScreen) continue;
                int a = t.front ? 255 : 90;
                dot.setAlpha(a);
                c.drawCircle(t.sx, t.sy, Ui.dp(act, 4), dot);
                float tw = txt.measureText(t.name), pad = Ui.dp(act, 5), y = t.sy - Ui.dp(act, 10);
                bg.setAlpha(t.front ? 210 : 70);
                c.drawRoundRect(t.sx + pad, y - txt.getTextSize(), t.sx + pad * 3 + tw, y + pad, pad, pad, bg);
                txt.setAlpha(a);
                c.drawText(t.name, t.sx + pad * 2, y, txt);
            }
            txt.setAlpha(255);
            JSONObject d = act.data == null ? null : act.data.optJSONObject("dims_cm");
            if (dims != null && d != null) {
                String[] s = {"↔ " + d.optInt("width") + " సెం.మీ", "↔ " + d.optInt("depth") + " సెం.మీ", "↕ " + d.optInt("height") + " సెం.మీ"};
                for (int i = 0; i < 3; i++) {
                    if (dims[i] == null) continue;
                    float tw = txt.measureText(s[i]), pad = Ui.dp(act, 5);
                    bg.setAlpha(220);
                    c.drawRoundRect(dims[i][0] - tw / 2 - pad, dims[i][1] - txt.getTextSize() - pad, dims[i][0] + tw / 2 + pad, dims[i][1] + pad, pad, pad, bg);
                    c.drawText(s[i], dims[i][0] - tw / 2, dims[i][1], txt);
                }
            }
        }
    }
}
