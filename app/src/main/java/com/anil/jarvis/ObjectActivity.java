package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 🧊 360°: one object he walked around. Two views of the same thing, turned by the same finger:
 * 📷 the photo spin (his real photos, blended as he drags, top / bottom rows when he took them, the AI's part tags per side) and
 * 🧊 the shape model (the AI's simple solids drawn as a real 3D body in our own holo style: turn it any way, look underneath,
 * pinch to zoom, tap a part, 💥 pull it apart). Faces nobody photographed are drawn dashed and said to be estimates.
 */
public class ObjectActivity extends Activity {
    static final String EXTRA_ID = "scan";

    static void open(Context c, String id) {
        c.startActivity(new Intent(c, ObjectActivity.class).putExtra(EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** What both views share: where he turned it to. */
    static final class Orbit {
        float yaw = 30, pitch = 22, zoom = 1f, vy, vp;
        boolean auto;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private String id;
    private JSONObject data;
    private final Orbit orbit = new Orbit();
    private SpinView spin;
    private ShapeView shape;
    private TextView info, modeChip, explodeChip, underChip, autoChip;
    private boolean photoMode = true;
    private final java.util.concurrent.ExecutorService work = java.util.concurrent.Executors.newSingleThreadExecutor();
    private int tourAt = -1;
    private final Runnable tourNext = this::tourStep;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF04101B);
        getWindow().setNavigationBarColor(0xFF04101B);
        id = getIntent().getStringExtra(EXTRA_ID);
        data = ScanStore.load(this, id);
        if (data == null) { Toast.makeText(this, "ఆ మోడల్ దొరకలేదు", Toast.LENGTH_SHORT).show(); finish(); return; }
        int pad = Ui.dp(this, 12);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF04101B);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 4));
        TextView x = Ui.text(this, "✕", 20, 0xFFFFFFFF);
        x.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        x.setOnClickListener(v -> finish());
        bar.addView(x);
        TextView t = Ui.mono(this, data.optString("title"), 14, Ui.CYAN);
        t.setSingleLine(true);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        TextView mic = Ui.text(this, "🎙️", 20, 0xFFFFFFFF);
        mic.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        mic.setOnClickListener(v -> listen());
        bar.addView(mic);
        TextView more = Ui.text(this, "⋯", 22, 0xFFFFFFFF);
        more.setPadding(Ui.dp(this, 10), Ui.dp(this, 2), Ui.dp(this, 10), Ui.dp(this, 6));
        more.setOnClickListener(v -> menu());
        bar.addView(more);
        page.addView(bar);

        FrameLayout stage = new FrameLayout(this);
        spin = new SpinView(this);
        shape = new ShapeView(this);
        stage.addView(shape, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(spin, new FrameLayout.LayoutParams(-1, -1));
        page.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        info = Ui.text(this, "", 14, 0xFFFFFFFF);
        info.setLineSpacing(0, 1.2f);
        info.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 8));
        info.setBackground(Ui.round(this, 0xFF0B1E31, Ui.alpha(Ui.CYAN, 0x66), 14));
        info.setMaxLines(5);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.setMargins(pad, Ui.dp(this, 4), pad, 0);
        info.setLayoutParams(ilp);
        page.addView(info);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 10));
        modeChip = chip("🧊 ఆకారం చూపించు", false);
        modeChip.setOnClickListener(v -> setPhotoMode(!photoMode));
        chips.addView(modeChip);
        autoChip = chip("🔄 తిప్పు", false);
        autoChip.setOnClickListener(v -> setAuto(!orbit.auto));
        chips.addView(autoChip);
        underChip = chip("⬇️ కింద చూపించు", false);
        underChip.setOnClickListener(v -> lookUnder());
        chips.addView(underChip);
        explodeChip = chip("💥 విడదీయి", false);
        explodeChip.setOnClickListener(v -> { shape.explode(!shape.exploded); lit(explodeChip, shape.exploded); if (photoMode) setPhotoMode(false); });
        chips.addView(explodeChip);
        TextView dims = chip("📏 కొలతలు", false);
        dims.setOnClickListener(v -> showDims());
        chips.addView(dims);
        TextView tour = chip("▶️ వివరించు", false);
        tour.setOnClickListener(v -> { if (tourAt >= 0) tourStop(); else tourStart(); });
        chips.addView(tour);
        TextView kase = chip("📁 కేస్‌కి", false);
        kase.setOnClickListener(v -> CaseActivity.addTo(this, "scan", id, data.optString("title"), ScanStore.cut(data.optString("say"), 160)));
        chips.addView(kase);
        hs.addView(chips);
        page.addView(hs);
        setContentView(page);

        shape.setParts(Shape3D.build(data.optJSONObject("shape")));
        spin.setViews(data.optJSONArray("views"));
        boolean hasPhotos = spin.count() > 0;
        if (!hasPhotos) setPhotoMode(false);
        setInfo(data.optString("say") + sizeLine() + (data.optBoolean("bottom_seen") ? "" : "\n⬇️ కింద భాగం ఫోటో లేదు: ఆకారం మోడల్‌లో అది అంచనా (గీతలతో)."));
        work.execute(() -> { spin.load(this, id); main.post(() -> { if (!isFinishing()) { spin.invalidate(); } }); });
        main.post(tick);
    }

    private String sizeLine() {
        JSONObject d = data.optJSONObject("dims_cm");
        if (d == null) return "";
        int w = d.optInt("width"), dp = d.optInt("depth"), h = d.optInt("height");
        if (w <= 0 && h <= 0) return "";
        return "\n📏 సుమారు " + w + " × " + dp + " × " + h + " సెం.మీ (అంచనా" + (d.optString("note").isEmpty() ? "" : ": " + d.optString("note")) + ")";
    }

    private void setInfo(String s) { info.setText(s); }

    private void setPhotoMode(boolean photo) {
        if (photo && spin.count() == 0) { Toast.makeText(this, "ఫోటోలు లేవు", Toast.LENGTH_SHORT).show(); return; }
        if (!photo && shape.parts.isEmpty()) { Toast.makeText(this, "AI ఆకారం ఇవ్వలేదు; ఫోటో స్పిన్ మాత్రమే", Toast.LENGTH_SHORT).show(); return; }
        photoMode = photo;
        spin.setVisibility(photo ? View.VISIBLE : View.GONE);
        shape.setVisibility(photo ? View.GONE : View.VISIBLE);
        modeChip.setText(photo ? "🧊 ఆకారం చూపించు" : "📷 ఫోటోలు చూపించు");
        lit(modeChip, !photo);
    }

    private void setAuto(boolean on) { orbit.auto = on; lit(autoChip, on); }

    private void lookUnder() {
        boolean under = orbit.pitch < -30;
        animatePitch(under ? 22 : -70);
        underChip.setText(under ? "⬇️ కింద చూపించు" : "⬆️ పైకి");
        if (!under && photoMode && !spin.hasBottom()) Toast.makeText(this, "కింద ఫోటో లేదు: 🧊 ఆకారం అంచనా చూపిస్తున్నాను", Toast.LENGTH_SHORT).show();
        if (!under && photoMode && !spin.hasBottom() && !shape.parts.isEmpty()) setPhotoMode(false);
    }

    private float pitchTarget = Float.NaN;
    private void animatePitch(float to) { pitchTarget = to; }

    private void showDims() {
        JSONObject d = data.optJSONObject("dims_cm");
        StringBuilder b = new StringBuilder();
        if (d != null) b.append("వెడల్పు ").append(d.optInt("width")).append(" · లోతు ").append(d.optInt("depth")).append(" · ఎత్తు ").append(d.optInt("height")).append(" సెం.మీ\n").append(d.optString("note")).append("\n\n");
        for (Shape3D.Part p : shape.parts) {
            JSONObject j = p.json;
            b.append("• ").append(p.name).append(": ");
            if ("box".equals(p.type)) { JSONArray s = j.optJSONArray("s"); b.append(s == null ? "" : Math.round(s.optDouble(0)) + " × " + Math.round(s.optDouble(1)) + " × " + Math.round(s.optDouble(2))); }
            else { b.append("వ్యాసం ").append(Math.round(j.optDouble("r") * 2)); if (j.optDouble("h", 0) > 0) b.append(", ఎత్తు ").append(Math.round(j.optDouble("h"))); if ("ring".equals(p.type)) b.append(", రంధ్రం ").append(Math.round(j.optDouble("r2") * 2)); }
            b.append(" సెం.మీ").append(p.seen ? "" : " (అంచనా)").append("\n");
        }
        if (b.length() == 0) b.append("కొలతలు రాలేదు.");
        b.append("\n(ఫోటోల నుంచి అంచనా; టేప్‌తో కొలిస్తేనే ఖచ్చితం.)");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("📏 కొలతలు").setMessage(b.toString().trim()).setPositiveButton("సరే", null).show();
    }

    private void menu() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(data.optString("title"))
                .setItems(new String[]{"🔍 ఈ వైపు గురించి అడుగు", "🗑️ ఈ మోడల్ తీసేయి"}, (d, w) -> {
                    if (w == 0) listen();
                    else new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setMessage("ఈ 360° మోడల్ తీసేయనా?")
                            .setPositiveButton("తీసేయి", (a, c) -> { ScanStore.delete(this, id); finish(); }).setNegativeButton("వద్దు", null).show();
                }).setNegativeButton("మూసేయ్", null).show();
    }

    // ---- the frame clock: inertia, auto-turn, the pitch animation

    private long lastTick;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (isFinishing()) return;
            long now = SystemClock.uptimeMillis();
            float dt = lastTick == 0 ? 1 / 60f : Math.min(0.1f, (now - lastTick) / 1000f);
            lastTick = now;
            boolean moved = false;
            if (!dragging) {
                if (orbit.auto) { orbit.yaw += 24 * dt; moved = true; }
                if (Math.abs(orbit.vy) > 2 || Math.abs(orbit.vp) > 2) {
                    orbit.yaw += orbit.vy * dt;
                    orbit.pitch = clampPitch(orbit.pitch + orbit.vp * dt);
                    orbit.vy *= Math.pow(0.08, dt);
                    orbit.vp *= Math.pow(0.08, dt);
                    moved = true;
                } else { orbit.vy = 0; orbit.vp = 0; }
                if (!Float.isNaN(pitchTarget)) {
                    float d = pitchTarget - orbit.pitch;
                    if (Math.abs(d) < 0.5f) { orbit.pitch = pitchTarget; pitchTarget = Float.NaN; }
                    else orbit.pitch += d * Math.min(1, 6 * dt);
                    moved = true;
                }
            }
            if (moved) { orbit.yaw = ((orbit.yaw % 360) + 360) % 360; (photoMode ? spin : shape).invalidate(); }
            main.postDelayed(this, moved || dragging ? 16 : 80);
        }
    };

    private boolean dragging;
    private float clampPitch(float p) { return Math.max(-89, Math.min(89, p)); }

    /** Both views hand their touches here: one finger turns, two fingers zoom. */
    private final class Turn {
        private float lx, ly;
        private long lt;
        private final ScaleGestureDetector scaler = new ScaleGestureDetector(ObjectActivity.this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) { orbit.zoom = Math.max(0.5f, Math.min(3.5f, orbit.zoom * d.getScaleFactor())); (photoMode ? spin : shape).invalidate(); return true; }
        });
        private long downAt;
        private float dx, dy, downX, downY;

        boolean onTouch(View v, MotionEvent e) {
            scaler.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true; orbit.vy = 0; orbit.vp = 0; pitchTarget = Float.NaN;
                    lx = downX = e.getX(); ly = downY = e.getY(); lt = SystemClock.uptimeMillis(); downAt = lt; dx = dy = 0;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (e.getPointerCount() > 1) { lx = e.getX(); ly = e.getY(); return true; }
                    float mx = e.getX() - lx, my = e.getY() - ly;
                    long now = SystemClock.uptimeMillis();
                    float dt = Math.max(1, now - lt) / 1000f;
                    float degPerPx = 180f / Math.max(1, v.getWidth());
                    orbit.yaw = (((orbit.yaw + mx * degPerPx) % 360) + 360) % 360;
                    orbit.pitch = clampPitch(orbit.pitch - my * degPerPx);
                    dx = mx * degPerPx / dt; dy = -my * degPerPx / dt;
                    lx = e.getX(); ly = e.getY(); lt = now;
                    v.invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    dragging = false;
                    long held = SystemClock.uptimeMillis() - downAt;
                    if (held < 250 && Math.hypot(e.getX() - downX, e.getY() - downY) < Ui.dp(ObjectActivity.this, 10)) { tapped(v, e.getX(), e.getY()); return true; }
                    orbit.vy = Math.max(-540, Math.min(540, dx)); orbit.vp = Math.max(-360, Math.min(360, dy));
                    return true;
                }
                case MotionEvent.ACTION_CANCEL: dragging = false; return true;
                default: return true;
            }
        }
    }

    private void tapped(View v, float x, float y) {
        if (v == spin) { String s = spin.tagAt(x, y); if (s != null) setInfo(s); }
        else { Shape3D.Part p = shape.pick(x, y); shape.select(p); if (p != null) setInfo(p.name + (p.section.isEmpty() ? "" : " · " + p.section) + "\n" + p.info + (p.seen ? "" : "\n(ఈ భాగం ఫోటోల్లో కనిపించలేదు: అంచనా)")); }
    }

    // ---- ▶️ the parts one by one

    private void tourStart() {
        if (shape.parts.isEmpty()) { Announcer.sayDirect(this, data.optString("say")); return; }
        setPhotoMode(false);
        tourAt = 0;
        Announcer.sayDirect(this, data.optString("say"));
        main.postDelayed(tourNext, 2500 + data.optString("say").length() * 55L);
    }

    private void tourStep() {
        if (tourAt < 0 || isFinishing()) return;
        if (tourAt >= shape.parts.size()) { tourAt = -1; shape.select(null); shape.explode(false); lit(explodeChip, false); return; }
        Shape3D.Part p = shape.parts.get(tourAt);
        shape.select(p);
        shape.explode(true);
        lit(explodeChip, true);
        String line = p.name + (p.info.isEmpty() ? "" : ". " + p.info) + (p.seen ? "" : ". ఇది ఫోటోల్లో కనిపించలేదు, అంచనా.");
        setInfo(line);
        Announcer.sayDirect(this, line);
        tourAt++;
        main.postDelayed(tourNext, 1800 + line.length() * 60L);
    }

    private void tourStop() { tourAt = -1; main.removeCallbacks(tourNext); Announcer.stop(); }

    // ---- 🎙️ words: turn / under / explode / a question about this side

    private static final int REQ_VOICE = 61;

    private void listen() {
        try {
            startActivityForResult(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "te-IN")
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "\"తిప్పు\", \"కింద చూపించు\", \"విడదీయి\", లేదా ఈ వైపు గురించి అడగండి"), REQ_VOICE);
        } catch (Exception e) { Toast.makeText(this, "వాయిస్ అందుబాటులో లేదు", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int code, int result, Intent d) {
        super.onActivityResult(code, result, d);
        if (code != REQ_VOICE || result != RESULT_OK || d == null) return;
        ArrayList<String> r = d.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (r == null || r.isEmpty()) return;
        heard(r.get(0));
    }

    /** (pure-ish) the words → an action name, for the desk test: turn / stop / under / top / explode / join / dims / photo / shape / front / back / left / right / ask. */
    static String command(String words) {
        String t = words == null ? "" : words.toLowerCase(Locale.ROOT).trim();
        if (t.matches(".*(ఆపు|ఆగు|stop).*")) return "stop";
        if (t.matches(".*(కింద|అడుగు|under|bottom|below).*")) return "under";
        if (t.matches(".*(పై నుంచి|పైన|పైకి|top|above).*")) return "top";
        if (t.matches(".*(విడదీ|విడిగా|explode|apart).*")) return "explode";
        if (t.matches(".*(కలుపు|కలిపి|join|together).*")) return "join";
        if (t.matches(".*(కొలత|సైజు|ఎంత పెద్ద|size|dimension).*")) return "dims";
        if (t.matches(".*(ఫోటో|photo|picture).*")) return "photo";
        if (t.matches(".*(ఆకారం|మోడల్|shape|model).*")) return "shape";
        if (t.matches(".*(ముందు|front).*")) return "front";
        if (t.matches(".*(వెనుక|back).*")) return "back";
        if (t.matches(".*(ఎడమ|left).*")) return "left";
        if (t.matches(".*(కుడి|right).*")) return "right";
        if (t.matches(".*(తిప్పు|తిరుగు|rotate|turn|spin).*")) return "turn";
        return "ask";
    }

    private void heard(String words) {
        String c = command(words);
        switch (c) {
            case "turn": setAuto(true); break;
            case "stop": setAuto(false); tourStop(); break;
            case "under": if (orbit.pitch > -30) lookUnder(); break;
            case "top": animatePitch(70); break;
            case "explode": shape.explode(true); lit(explodeChip, true); setPhotoMode(false); break;
            case "join": shape.explode(false); lit(explodeChip, false); break;
            case "dims": showDims(); break;
            case "photo": setPhotoMode(true); break;
            case "shape": setPhotoMode(false); break;
            case "front": orbit.yaw = 0; animatePitch(15); (photoMode ? spin : shape).invalidate(); break;
            case "back": orbit.yaw = 180; animatePitch(15); (photoMode ? spin : shape).invalidate(); break;
            case "left": orbit.yaw = 270; animatePitch(15); (photoMode ? spin : shape).invalidate(); break;
            case "right": orbit.yaw = 90; animatePitch(15); (photoMode ? spin : shape).invalidate(); break;
            default: askAboutView(words);
        }
    }

    /** A question about what is on the screen now: the nearest photo goes to the AI once (text + that picture, nothing else). */
    private void askAboutView(String q) {
        Prefs p = new Prefs(this);
        if (!Net.online(this) || !p.hasBrain()) { Toast.makeText(this, "దీనికి నెట్ + AI key కావాలి", Toast.LENGTH_SHORT).show(); return; }
        Bitmap pic = spin.nearest();
        setInfo("🔍 " + q + "\n\nచూస్తున్నాను…");
        work.execute(() -> {
            String ans;
            try {
                String sys = "You are JARVIS. Anil asks about one side of an object he photographed (" + data.optString("title") + "). Answer in 2-4 short Telugu lines from what is visible; say when you cannot tell. Never identify people; no ID numbers.";
                ans = Brain.oneShot(p, sys, q, pic == null ? null : JarvisCamera.jpeg(pic, 1200, 80), false, 500);
            } catch (Http.ApiError e) { ans = "AI జవాబు ఇవ్వలేదు: " + Models.explain(p, e); }
            catch (Exception e) { ans = "చూడలేకపోయాను (నెట్ / సమయం)."; }
            final String a = ans == null ? "" : ans.trim();
            main.post(() -> { if (isFinishing()) return; setInfo(a); Announcer.sayDirect(this, a); });
        });
    }

    @Override protected void onPause() { super.onPause(); tourStop(); setAuto(false); main.removeCallbacks(tick); }
    @Override protected void onResume() { super.onResume(); lastTick = 0; main.removeCallbacks(tick); main.post(tick); }
    @Override protected void onDestroy() { super.onDestroy(); work.shutdownNow(); spin.release(); }

    private TextView chip(String s, boolean on) {
        TextView t = Ui.text(this, s, 13.5f, 0xFFFFFFFF);
        t.setSingleLine(true);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
        lit(t, on);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void lit(TextView t, boolean on) { t.setBackground(Ui.round(this, on ? Ui.alpha(Ui.CYAN, 0x55) : 0x40000000, on ? Ui.CYAN : 0x55FFFFFF, 999)); }

    // ================================================================ 📷 the photo spin

    final class SpinView extends View {
        private final Turn turn = new Turn();
        private final List<JSONObject> around = new ArrayList<>(), tops = new ArrayList<>(), bottoms = new ArrayList<>();
        private final java.util.Map<String, Bitmap> pics = new java.util.HashMap<>();
        private final Paint pf = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG), tag = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF dst = new RectF();
        private final List<Object[]> tagRects = new ArrayList<>(); // {RectF, text}

        SpinView(Context c) {
            super(c);
            txt.setColor(0xFFFFFFFF);
            txt.setTextSize(Ui.dp(c, 11.5f));
            txt.setTypeface(Typeface.DEFAULT_BOLD);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Ui.dp(c, 1.2f));
            setOnTouchListener((v, e) -> turn.onTouch(v, e));
        }

        void setViews(JSONArray views) {
            around.clear(); tops.clear(); bottoms.clear();
            for (int i = 0; views != null && i < views.length(); i++) {
                JSONObject v = views.optJSONObject(i);
                if (v == null) continue;
                int tilt = v.optInt("tilt", 0);
                (tilt > 0 ? tops : tilt < 0 ? bottoms : around).add(v);
            }
            Collections.sort(around, (a, b) -> Double.compare(a.optDouble("az", 0), b.optDouble("az", 0)));
        }

        int count() { return around.size() + tops.size() + bottoms.size(); }
        boolean hasBottom() { return !bottoms.isEmpty(); }

        void load(Context c, String sid) {
            for (JSONObject v : all()) {
                Bitmap b = ScanStore.photo(c, sid, v.optString("photo"), 1100);
                if (b != null) synchronized (pics) { pics.put(v.optString("photo"), b); }
            }
        }

        private List<JSONObject> all() { List<JSONObject> l = new ArrayList<>(around); l.addAll(tops); l.addAll(bottoms); return l; }

        void release() { synchronized (pics) { for (Bitmap b : pics.values()) b.recycle(); pics.clear(); } }

        /** The photo closest to where he turned it (for a question about "this side"). */
        Bitmap nearest() {
            JSONObject v = pickView(orbit.yaw, orbit.pitch);
            synchronized (pics) { return v == null ? null : pics.get(v.optString("photo")); }
        }

        private JSONObject pickView(float yaw, float pitch) {
            if (pitch > 45 && !tops.isEmpty()) return tops.get(0);
            if (pitch < -45 && !bottoms.isEmpty()) return bottoms.get(0);
            if (around.isEmpty()) return tops.isEmpty() ? (bottoms.isEmpty() ? null : bottoms.get(0)) : tops.get(0);
            JSONObject best = null;
            float bd = 999;
            for (int i = 0; i < around.size(); i++) { float d = angDiff(yaw, rel(i)); if (d < bd) { bd = d; best = around.get(i); } }
            return best;
        }

        private float angDiff(float a, float b) { float d = Math.abs(((a - b) % 360 + 540) % 360 - 180); return d; }

        @Override protected void onDraw(Canvas c) {
            c.drawColor(0xFF04101B);
            tagRects.clear();
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            // which photos, and how much of each: the two nearest round the turn, blended; the top / bottom rows by the pitch
            JSONObject a = null, b = null;
            float wa = 1, wb = 0;
            if (!around.isEmpty()) {
                // yaw 0 = the first photo; the photos' az are the compass headings they were taken at, relative to the first
                float yaw = orbit.yaw;
                int n = around.size();
                int ia = 0;
                float bd = 999;
                for (int i = 0; i < n; i++) { float d = angDiff(yaw, rel(i)); if (d < bd) { bd = d; ia = i; } }
                a = around.get(ia);
                // the neighbour on the side he is turning towards
                int ib = -1;
                float bd2 = 999;
                for (int i = 0; i < n; i++) { if (i == ia) continue; float d = angDiff(yaw, rel(i)); if (d < bd2) { bd2 = d; ib = i; } }
                if (ib >= 0 && n > 1) {
                    b = around.get(ib);
                    float span = Math.max(1, angDiff(rel(ia), rel(ib)));
                    wb = Math.max(0, Math.min(1, bd / span)) * 0.85f;
                    wa = 1 - wb;
                }
            }
            float tilt = Math.abs(orbit.pitch) > 45 ? Math.min(1, (Math.abs(orbit.pitch) - 45) / 30f) : 0;
            JSONObject t = orbit.pitch > 45 ? (tops.isEmpty() ? null : tops.get(0)) : orbit.pitch < -45 ? (bottoms.isEmpty() ? null : bottoms.get(0)) : null;
            float base = 1 - (t == null ? 0 : tilt);
            if (a != null) drawView(c, a, (int) (255 * wa * base), w, h, wa > 0.5f && t == null);
            if (b != null && wb > 0.02f) drawView(c, b, (int) (255 * wb * base), w, h, false);
            if (t != null) drawView(c, t, (int) (255 * tilt), w, h, tilt > 0.5f);
            if (orbit.pitch < -45 && bottoms.isEmpty()) {
                // nobody photographed the underside: say so where it would be, dimly
                int al = (int) (200 * tilt);
                txt.setColor(Ui.alpha(0xFFFFD08A, al));
                txt.setTextAlign(Paint.Align.CENTER);
                c.drawText("కింద ఫోటో లేదు · 🧊 ఆకారం అంచనా చూపిస్తుంది", w / 2f, h - Ui.dp(getContext(), 28), txt);
                txt.setTextAlign(Paint.Align.LEFT);
                txt.setColor(0xFFFFFFFF);
            }
            // a thin turn dial along the bottom: where he is round the circle
            float cx = w / 2f, cy = h - Ui.dp(getContext(), 14), r = Ui.dp(getContext(), 60);
            line.setColor(Ui.alpha(Ui.CYAN, 0x66));
            c.drawArc(cx - r, cy - r * 0.35f, cx + r, cy + r * 0.35f, 180, 180, false, line);
            double ang = Math.toRadians(180 + (orbit.yaw % 360));
            float px = (float) (cx + r * Math.cos(ang)), py = (float) (cy + r * 0.35f * Math.sin(ang));
            tag.setColor(Ui.CYAN);
            c.drawCircle(px, py, Ui.dp(getContext(), 4), tag);
            txt.setColor(Ui.alpha(0xFFFFFFFF, 0xAA));
            txt.setTextSize(Ui.dp(getContext(), 10.5f));
            c.drawText(Math.round(orbit.yaw) + "°", cx + r + Ui.dp(getContext(), 8), cy + Ui.dp(getContext(), 4), txt);
            txt.setTextSize(Ui.dp(getContext(), 11.5f));
            txt.setColor(0xFFFFFFFF);
        }

        /** The photo's heading relative to the first photo's (so yaw 0 shows the first). */
        private float rel(int i) {
            float a0 = (float) around.get(0).optDouble("az", 0), ai = (float) around.get(i).optDouble("az", 0);
            return ((ai - a0) % 360 + 360) % 360;
        }

        private void drawView(Canvas c, JSONObject v, int alpha, int w, int h, boolean tags) {
            Bitmap bm;
            synchronized (pics) { bm = pics.get(v.optString("photo")); }
            if (bm == null || alpha <= 0) return;
            float s = Math.min(w / (float) bm.getWidth(), h / (float) bm.getHeight()) * 0.92f * orbit.zoom;
            float dw = bm.getWidth() * s, dh = bm.getHeight() * s;
            dst.set(w / 2f - dw / 2, h / 2f - dh / 2 - Ui.dp(getContext(), 8), w / 2f + dw / 2, h / 2f + dh / 2 - Ui.dp(getContext(), 8));
            pf.setAlpha(alpha);
            c.drawBitmap(bm, null, dst, pf);
            if (!tags) return;
            JSONArray items = v.optJSONArray("items");
            for (int i = 0; items != null && i < items.length() && i < 10; i++) {
                JSONObject it = items.optJSONObject(i);
                JSONArray bx = it == null ? null : it.optJSONArray("box");
                if (bx == null || bx.length() != 4) continue;
                float x0 = dst.left + dst.width() * bx.optInt(0) / 1000f, y0 = dst.top + dst.height() * bx.optInt(1) / 1000f;
                float x1 = dst.left + dst.width() * bx.optInt(2) / 1000f, y1 = dst.top + dst.height() * bx.optInt(3) / 1000f;
                boolean fault = it.optBoolean("fault");
                int col = fault ? 0xFFFF6B5E : Ui.CYAN;
                line.setColor(Ui.alpha(col, (int) (alpha * 0.8f)));
                float k = Math.min(Ui.dp(getContext(), 10), (x1 - x0) / 3), kk = Math.min(Ui.dp(getContext(), 10), (y1 - y0) / 3);
                c.drawLine(x0, y0, x0 + k, y0, line); c.drawLine(x0, y0, x0, y0 + kk, line);
                c.drawLine(x1, y0, x1 - k, y0, line); c.drawLine(x1, y0, x1, y0 + kk, line);
                c.drawLine(x0, y1, x0 + k, y1, line); c.drawLine(x0, y1, x0, y1 - kk, line);
                c.drawLine(x1, y1, x1 - k, y1, line); c.drawLine(x1, y1, x1, y1 - kk, line);
                String name = (i + 1) + ". " + it.optString("name");
                float tw = txt.measureText(name) + Ui.dp(getContext(), 12), th = Ui.dp(getContext(), 18);
                float tx = Math.max(dst.left, Math.min(dst.right - tw, x0)), ty = y0 - th - Ui.dp(getContext(), 3);
                if (ty < dst.top) ty = y1 + Ui.dp(getContext(), 3);
                RectF rr = new RectF(tx, ty, tx + tw, ty + th);
                tag.setColor(Ui.alpha(0xFF0B1E31, (int) (alpha * 0.85f)));
                c.drawRoundRect(rr, th / 2, th / 2, tag);
                line.setColor(Ui.alpha(col, (int) (alpha * 0.9f)));
                c.drawRoundRect(rr, th / 2, th / 2, line);
                txt.setColor(Ui.alpha(0xFFFFFFFF, alpha));
                c.drawText(name, tx + Ui.dp(getContext(), 6), ty + th * 0.72f, txt);
                tagRects.add(new Object[]{rr, it.optString("name") + (it.optString("info").isEmpty() ? "" : "\n" + it.optString("info")) + (fault ? "\n⚠️ " + it.optString("fault_why") : "")});
            }
            txt.setColor(0xFFFFFFFF);
        }

        String tagAt(float x, float y) {
            for (Object[] o : tagRects) if (((RectF) o[0]).contains(x, y)) return (String) o[1];
            return null;
        }
    }

    // ================================================================ 🧊 the shape model

    final class ShapeView extends View {
        private final Turn turn = new Turn();
        List<Shape3D.Part> parts = new ArrayList<>();
        private float radius = 1, floorZ = 0;
        boolean exploded;
        private float explodeK;
        private Shape3D.Part selected;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(Paint.ANTI_ALIAS_FLAG), pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float[] tmp = new float[3];
        private final DashPathEffect dash;
        private final List<float[]> drawn = new ArrayList<>(); // screen polygons of the frame, front-most last: {partIndex, x0,y0,x1,y1,...}
        private final List<Object[]> labelRects = new ArrayList<>();

        ShapeView(Context c) {
            super(c);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Ui.dp(c, 1));
            edge.setStrokeJoin(Paint.Join.ROUND);
            grid.setStyle(Paint.Style.STROKE);
            grid.setStrokeWidth(Ui.dp(c, 0.8f));
            txt.setColor(0xFFFFFFFF);
            txt.setTextSize(Ui.dp(c, 11.5f));
            txt.setTypeface(Typeface.DEFAULT_BOLD);
            dash = new DashPathEffect(new float[]{Ui.dp(c, 4), Ui.dp(c, 4)}, 0);
            setOnTouchListener((v, e) -> turn.onTouch(v, e));
        }

        void setParts(List<Shape3D.Part> p) { parts = p; radius = Shape3D.radius(p); floorZ = Shape3D.bottom(p); invalidate(); }
        void explode(boolean on) { exploded = on; invalidate(); }
        void select(Shape3D.Part p) { selected = p; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            c.drawColor(0xFF04101B);
            drawn.clear();
            labelRects.clear();
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0 || parts.isEmpty()) {
                txt.setTextAlign(Paint.Align.CENTER);
                c.drawText("ఆకారం లేదు", w / 2f, h / 2f, txt);
                txt.setTextAlign(Paint.Align.LEFT);
                return;
            }
            // the explode slides in and out
            float target = exploded ? 1 : 0;
            if (Math.abs(explodeK - target) > 0.01f) { explodeK += (target - explodeK) * 0.18f; postInvalidateOnAnimation(); } else explodeK = target;
            float scale = Math.min(w, h) * 0.36f / radius * orbit.zoom;
            float cx = w / 2f, cy = h / 2f + Ui.dp(getContext(), 6);
            float focal = radius * 6;
            // the floor grid: a square under the thing, in perspective, so the eye reads depth
            grid.setColor(Ui.alpha(Ui.CYAN, 0x30));
            int lines = 8;
            float g = radius * 1.4f;
            for (int i = 0; i <= lines; i++) {
                float t = -g + 2 * g * i / lines;
                drawLine3(c, -g, t, floorZ - radius * 0.02f, g, t, floorZ - radius * 0.02f, scale, cx, cy, focal, grid);
                drawLine3(c, t, -g, floorZ - radius * 0.02f, t, g, floorZ - radius * 0.02f, scale, cx, cy, focal, grid);
            }
            // every face of every part, far ones first
            List<Object[]> faces = new ArrayList<>();
            float[] light = {0.35f, -0.5f, 0.8f};
            float ll = (float) Math.sqrt(light[0] * light[0] + light[1] * light[1] + light[2] * light[2]);
            for (int pi = 0; pi < parts.size(); pi++) {
                Shape3D.Part p = parts.get(pi);
                float[] off = Shape3D.explode(p, explodeK * 0.9f, radius * 0.25f * explodeK);
                for (Shape3D.Face f : p.faces) {
                    int n = f.pts.length / 3;
                    float[] sp = new float[n * 2];
                    float depth = 0;
                    for (int i = 0; i < n; i++) {
                        Shape3D.turn(f.pts[i * 3] + off[0], f.pts[i * 3 + 1] + off[1], f.pts[i * 3 + 2] + off[2], orbit.yaw, orbit.pitch, tmp);
                        float per = focal / (focal - tmp[2]);
                        sp[i * 2] = cx + tmp[0] * scale * per;
                        sp[i * 2 + 1] = cy - tmp[1] * scale * per;
                        depth += tmp[2];
                    }
                    depth /= n;
                    // the lit side: the normal turned the same way, against a fixed lamp up-left-front
                    Shape3D.turn(f.n[0], f.n[1], f.n[2], orbit.yaw, orbit.pitch, tmp);
                    float facing = tmp[2]; // > 0 faces the viewer
                    float lambert = Math.abs((tmp[0] * light[0] + tmp[1] * light[1] + tmp[2] * light[2]) / ll);
                    faces.add(new Object[]{depth, sp, f, lambert, facing, pi});
                }
            }
            Collections.sort(faces, (x, y) -> Float.compare((Float) x[0], (Float) y[0]));
            for (Object[] o : faces) {
                float[] sp = (float[]) o[1];
                Shape3D.Face f = (Shape3D.Face) o[2];
                float lambert = (Float) o[3], facing = (Float) o[4];
                if (facing < -0.05f) continue; // the far side of a closed solid: its near faces cover it
                boolean sel = f.part == selected;
                int base = f.part.color;
                float k = 0.45f + 0.55f * lambert;
                int col = Color.rgb((int) (Color.red(base) * k), (int) (Color.green(base) * k), (int) (Color.blue(base) * k));
                int alpha = f.part.seen ? (sel ? 0xE6 : 0xB8) : 0x66;
                fill.setColor(Ui.alpha(col, alpha));
                path.reset();
                path.moveTo(sp[0], sp[1]);
                for (int i = 1; i < sp.length / 2; i++) path.lineTo(sp[i * 2], sp[i * 2 + 1]);
                path.close();
                c.drawPath(path, fill);
                edge.setPathEffect(f.part.seen ? null : dash);
                edge.setColor(sel ? 0xFFFFFFFF : Ui.alpha(Ui.CYAN, f.cap ? 0x90 : 0x38));
                edge.setStrokeWidth(Ui.dp(getContext(), sel ? 1.6f : f.cap ? 1.1f : 0.6f));
                c.drawPath(path, edge);
                float[] rec = new float[sp.length + 1];
                rec[0] = (Integer) o[5];
                System.arraycopy(sp, 0, rec, 1, sp.length);
                drawn.add(rec);
            }
            edge.setPathEffect(null);
            // labels with leader lines, stacked down the right, then the left
            List<Object[]> labs = new ArrayList<>();
            for (int pi = 0; pi < parts.size(); pi++) {
                Shape3D.Part p = parts.get(pi);
                float[] off = Shape3D.explode(p, explodeK * 0.9f, radius * 0.25f * explodeK);
                Shape3D.turn(p.c[0] + off[0], p.c[1] + off[1], p.c[2] + off[2], orbit.yaw, orbit.pitch, tmp);
                float per = focal / (focal - tmp[2]);
                labs.add(new Object[]{cx + tmp[0] * scale * per, cy - tmp[1] * scale * per, p});
            }
            Collections.sort(labs, (x, y) -> Float.compare((Float) x[1], (Float) y[1]));
            float th = Ui.dp(getContext(), 20), gap = Ui.dp(getContext(), 4), yR = Ui.dp(getContext(), 10), yL = Ui.dp(getContext(), 10);
            int shown = 0;
            for (Object[] l : labs) {
                if (shown++ >= 12) break;
                Shape3D.Part p = (Shape3D.Part) l[2];
                float px = (Float) l[0], py = (Float) l[1];
                boolean right = px >= cx;
                String name = p.name + (p.seen ? "" : " (అంచనా)");
                float tw = Math.min(w * 0.42f, txt.measureText(name) + Ui.dp(getContext(), 14));
                float ly = right ? Math.max(yR, py - th / 2) : Math.max(yL, py - th / 2);
                float lx = right ? w - tw - Ui.dp(getContext(), 8) : Ui.dp(getContext(), 8);
                if (right) yR = ly + th + gap; else yL = ly + th + gap;
                boolean sel = p == selected;
                grid.setColor(Ui.alpha(sel ? 0xFFFFFFFF : Ui.CYAN, 0x70));
                c.drawLine(px, py, right ? lx : lx + tw, ly + th / 2, grid);
                pill.setColor(sel ? Ui.alpha(Ui.CYAN, 0x66) : 0xCC0B1E31);
                RectF rr = new RectF(lx, ly, lx + tw, ly + th);
                c.drawRoundRect(rr, th / 2, th / 2, pill);
                edge.setColor(Ui.alpha(sel ? 0xFFFFFFFF : Ui.CYAN, 0x88));
                edge.setStrokeWidth(Ui.dp(getContext(), 1));
                c.drawRoundRect(rr, th / 2, th / 2, edge);
                c.save();
                c.clipRect(rr);
                c.drawText(name, lx + Ui.dp(getContext(), 7), ly + th * 0.7f, txt);
                c.restore();
                labelRects.add(new Object[]{rr, p});
            }
            // the little compass of the turn
            txt.setColor(Ui.alpha(0xFFFFFFFF, 0xAA));
            txt.setTextSize(Ui.dp(getContext(), 10.5f));
            c.drawText(Math.round(orbit.yaw) + "° · " + (orbit.pitch < -30 ? "కింద నుంచి" : orbit.pitch > 50 ? "పై నుంచి" : "పక్క నుంచి"), Ui.dp(getContext(), 10), h - Ui.dp(getContext(), 10), txt);
            txt.setTextSize(Ui.dp(getContext(), 11.5f));
            txt.setColor(0xFFFFFFFF);
        }

        private void drawLine3(Canvas c, float x0, float y0, float z0, float x1, float y1, float z1, float scale, float cx, float cy, float focal, Paint p) {
            Shape3D.turn(x0, y0, z0, orbit.yaw, orbit.pitch, tmp);
            float per = focal / (focal - tmp[2]);
            float ax = cx + tmp[0] * scale * per, ay = cy - tmp[1] * scale * per;
            Shape3D.turn(x1, y1, z1, orbit.yaw, orbit.pitch, tmp);
            per = focal / (focal - tmp[2]);
            c.drawLine(ax, ay, cx + tmp[0] * scale * per, cy - tmp[1] * scale * per, p);
        }

        /** The part under a finger: a label first, else the front-most face containing the point. */
        Shape3D.Part pick(float x, float y) {
            for (Object[] o : labelRects) if (((RectF) o[0]).contains(x, y)) return (Shape3D.Part) o[1];
            for (int i = drawn.size() - 1; i >= 0; i--) {
                float[] r = drawn.get(i);
                if (inside(r, x, y)) return parts.get((int) r[0]);
            }
            return null;
        }

        private boolean inside(float[] r, float x, float y) { // r = {part, x0,y0,...}; even-odd rule
            int n = (r.length - 1) / 2;
            boolean in = false;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                float xi = r[1 + i * 2], yi = r[2 + i * 2], xj = r[1 + j * 2], yj = r[2 + j * 2];
                if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi + 1e-6f) + xi)) in = !in;
            }
            return in;
        }
    }
}
