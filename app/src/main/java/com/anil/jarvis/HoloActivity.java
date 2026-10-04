package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The hologram screen: the scanned thing in 3D with its parts floating (HoloView), name tags on them, and the tools:
 * pull apart / put together, names, connections, sections in colours, faults, X-ray, the board on / off, the building
 * order step by step, test points, the parts list with prices, adding or correcting a part, a turning video to share,
 * the Telugu PDF, a wave in the air to turn it, and for an opened device its layers (peel them off) and the closing
 * guide (which screws go where).
 */
public class HoloActivity extends Activity implements HoloView.Listener {
    static final String EXTRA_ID = "scan_id";

    static void open(Context c, String id) {
        c.startActivity(new Intent(c, HoloActivity.class).putExtra(EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private Prefs p;
    private float d;
    private String id;
    private JSONObject data;
    private HoloView holo;
    private Labels labels;
    private TextView info, title, banner, layerText;
    private ScrollView infoScroll;
    private LinearLayout infoBox, infoButtons, legend, layerBar;
    private HorizontalScrollView legendScroll;
    private final List<TextView> toggles = new ArrayList<>();
    private boolean labelsOn = true;
    private AirGesture air;
    private int asm = -1;
    // video
    private HoloVideo video;
    private ExecutorService encoder;
    private final AtomicInteger pending = new AtomicInteger();
    private boolean recording;

    private int dp(float v) { return Math.round(v * d); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = new Prefs(this);
        d = getResources().getDisplayMetrics().density;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF02070F);
        getWindow().setNavigationBarColor(0xFF02070F);
        id = getIntent().getStringExtra(EXTRA_ID);
        data = id == null ? null : ScanStore.load(this, id);
        if (data == null) { Toast.makeText(this, "ఈ స్కాన్ దొరకలేదు", Toast.LENGTH_SHORT).show(); finish(); return; }
        build();
        rebuild();
    }

    @Override protected void onResume() { super.onResume(); if (holo != null) holo.onResume(); }

    @Override protected void onPause() {
        super.onPause();
        if (holo != null) holo.onPause();
        if (air != null) air.stop();
        if (recording) stopVideo(false);
        Announcer.stop();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        work.shutdownNow();
        if (encoder != null) encoder.shutdownNow();
    }

    /** The scene again from the saved scan (after an edit too). */
    private void rebuild() {
        final JSONObject dd = data;
        work.execute(() -> {
            HoloView.Scene s = HoloView.build(this, dd);
            main.post(() -> {
                if (isFinishing()) return;
                holo.setScene(s);
                labels.scene = s;
                title.setText(dd.optString("title", "హోలోగ్రామ్"));
                String warn = s.generic ? "⚠️ సాధారణ మోడల్: ఇలాంటి పరికరాల్లో సాధారణంగా ఉండే parts (మీదే ఖచ్చితంగా కాదు)"
                        : dd.optBoolean("estimate", true) ? "ఫోటో నుంచి అంచనా: చిన్న parts మిస్ అవ్వొచ్చు, పేర్లు తప్పు రావొచ్చు (✏️ తో సరిచేయండి)" : "";
                banner.setText(warn);
                banner.setVisibility(warn.isEmpty() ? View.GONE : View.VISIBLE);
                buildLegend();
                layerBar.setVisibility(s.layers.size() > 1 ? View.VISIBLE : View.GONE);
                showLayer();
                if (info.getText().length() == 0) showText("🧊 " + dd.optString("title") + "\n" + dd.optString("say")
                        + "\n\nవేలితో తిప్పండి, రెండు వేళ్ళతో జూమ్, ఒక part నొక్కితే అది ముందుకు వస్తుంది.", null);
            });
        });
    }

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF02070F);
        holo = new HoloView(this);
        holo.listener = this;
        root.addView(holo, new FrameLayout.LayoutParams(-1, -1));
        labels = new Labels(this);
        root.addView(labels, new FrameLayout.LayoutParams(-1, -1));
        labels.setOnTouchListener(this::touch);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xE602070F, 0x0002070F}));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(8), dp(6), dp(4));
        bar.addView(icon("✕", v -> finish()));
        title = Ui.text(this, "", 16, 0xFFFFFFFF);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(icon("🔄", v -> resetView()));
        bar.addView(icon("🎬", v -> { if (recording) stopVideo(true); else startVideo(); }));
        bar.addView(icon("📄", v -> pdf()));
        top.addView(bar);
        banner = Ui.text(this, "", 12, Ui.GOLD);
        banner.setPadding(dp(12), 0, dp(12), dp(4));
        top.addView(banner);
        layerBar = new LinearLayout(this);
        layerBar.setGravity(Gravity.CENTER_VERTICAL);
        layerBar.setPadding(dp(10), dp(2), dp(10), dp(2));
        layerText = Ui.text(this, "", 13.5f, Ui.CYAN);
        layerBar.addView(layerText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView up = chip("⬆️ పొర తీయి", false), down = chip("⬇️ పొర పెట్టు", false), screws = chip("🔩 మూసే గైడ్", false);
        up.setOnClickListener(v -> { HoloView.Scene s = holo.scene(); if (s != null && holo.peel < s.layers.size() - 1) holo.peel++; showLayer(); });
        down.setOnClickListener(v -> { if (holo.peel > 0) holo.peel--; showLayer(); });
        screws.setOnClickListener(v -> screwGuide(0));
        layerBar.addView(up);
        layerBar.addView(down);
        layerBar.addView(screws);
        layerBar.setVisibility(View.GONE);
        top.addView(layerBar);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xF202070F, 0xC002070F, 0x0002070F}));
        bottom.setPadding(0, dp(20), 0, dp(8));
        legendScroll = new HorizontalScrollView(this);
        legendScroll.setHorizontalScrollBarEnabled(false);
        legend = new LinearLayout(this);
        legend.setPadding(dp(8), 0, dp(8), 0);
        legendScroll.addView(legend);
        legendScroll.setVisibility(View.GONE);
        bottom.addView(legendScroll);
        infoBox = new LinearLayout(this);
        infoBox.setOrientation(LinearLayout.VERTICAL);
        infoBox.setBackground(Ui.round(this, 0xE6061424, Ui.alpha(Ui.CYAN, 0x77), 16));
        infoBox.setPadding(dp(14), dp(8), dp(10), dp(6));
        final int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.24f);
        infoScroll = new ScrollView(this) {
            @Override protected void onMeasure(int w, int h) { super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)); }
        };
        info = Ui.text(this, "", 14.5f, 0xFFFFFFFF);
        info.setLineSpacing(0, 1.18f);
        infoScroll.addView(info);
        infoBox.addView(infoScroll);
        HorizontalScrollView ib = new HorizontalScrollView(this);
        ib.setHorizontalScrollBarEnabled(false);
        infoButtons = new LinearLayout(this);
        ib.addView(infoButtons);
        infoBox.addView(ib);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.setMargins(dp(10), dp(4), dp(10), dp(6));
        bottom.addView(infoBox, ilp);
        HorizontalScrollView ts = new HorizontalScrollView(this);
        ts.setHorizontalScrollBarEnabled(false);
        LinearLayout tg = new LinearLayout(this);
        tg.setPadding(dp(8), 0, dp(8), 0);
        addToggle(tg, "💥 విడదీయి", () -> holo.exploded, on -> holo.exploded = on);
        addToggle(tg, "🏷️ పేర్లు", () -> labelsOn, on -> labelsOn = on);
        addToggle(tg, "🔗 కనెక్షన్లు", () -> holo.showLinks, on -> holo.showLinks = on);
        addToggle(tg, "🧩 సెక్షన్లు", () -> holo.showSections, on -> { holo.showSections = on; legendScroll.setVisibility(on && legend.getChildCount() > 0 ? View.VISIBLE : View.GONE); if (!on) holo.sectionFilter = null; });
        addToggle(tg, "🔥 సమస్యలు", () -> holo.faultsOnly, on -> { holo.faultsOnly = on; if (on) showFaults(); });
        addToggle(tg, "🩻 X-ray", () -> holo.xray, on -> holo.xray = on);
        addToggle(tg, "▦ బోర్డు", () -> holo.showBoard, on -> holo.showBoard = on);
        addToggle(tg, "🛠️ తయారీ", () -> asm >= 0, on -> { if (on) assembly(0); else assemblyOff(); });
        addToggle(tg, "📏 టెస్ట్ పాయింట్లు", () -> holo.showTests, on -> { holo.showTests = on; if (on) showTests(); });
        addButton(tg, "🛒 parts లిస్ట్", v -> bom());
        addButton(tg, "➕ part", v -> addPart());
        addToggle(tg, "✋ గాలిలో", () -> air != null && air.isOn(), this::airGestures);
        addButton(tg, "🔬 వివరంగా", v -> deepAgain());
        addButton(tg, "🔍 దగ్గరగా", v -> closeUp());
        addButton(tg, "🔄 వెనక వైపు", v -> { startActivity(new Intent(this, JarvisCamera.class).putExtra(JarvisCamera.EXTRA_BACK_OF, id)); finish(); });
        addButton(tg, "📍 అసలు ఫోటో", v -> startActivity(new Intent(this, JarvisCamera.class).putExtra(JarvisCamera.EXTRA_SCAN, id)));
        ts.addView(tg);
        bottom.addView(ts);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
    }

    private TextView icon(String s, View.OnClickListener l) {
        TextView t = Ui.text(this, s, 20, 0xFFFFFFFF);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setOnClickListener(l);
        return t;
    }

    private TextView chip(String s, boolean lit) {
        TextView t = Ui.text(this, s, 13, 0xFFFFFFFF);
        t.setSingleLine(true);
        t.setPadding(dp(11), dp(7), dp(11), dp(7));
        lit(t, lit);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        t.setLayoutParams(lp);
        return t;
    }

    private void lit(TextView t, boolean on) {
        t.setBackground(Ui.round(this, on ? Ui.alpha(Ui.CYAN, 0x55) : 0x33FFFFFF, on ? Ui.CYAN : 0x44FFFFFF, 999));
    }

    private interface Flip { void set(boolean on); }
    private interface State { boolean on(); }

    /** A switch chip: lit when its state is on (the state is read each time, so a double tap or a wave keeps it right). */
    private void addToggle(LinearLayout row, String label, State st, Flip f) {
        TextView t = chip(label, st.on());
        t.setOnClickListener(v -> { boolean now = !st.on(); f.set(now); lit(t, st.on()); });
        toggles.add(t);
        row.addView(t);
    }

    private void addButton(LinearLayout row, String label, View.OnClickListener l) {
        TextView t = chip(label, false);
        t.setOnClickListener(l);
        row.addView(t);
    }

    /** Words in the info card, with buttons under them (or none). */
    private void showText(String text, List<View> buttons) {
        info.setText(text);
        infoScroll.scrollTo(0, 0);
        infoButtons.removeAllViews();
        if (buttons != null) for (View v : buttons) infoButtons.addView(v);
        infoBox.setVisibility(View.VISIBLE);
    }

    private TextView small(String s, View.OnClickListener l) {
        TextView t = chip(s, false);
        t.setTextSize(12.5f);
        t.setOnClickListener(l);
        return t;
    }

    // ================================================================ touch

    private ScaleGestureDetector scaler;
    private GestureDetector taps;
    private float lastX, lastY;
    private boolean dragging;

    private boolean touch(View v, MotionEvent e) {
        if (scaler == null) {
            scaler = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector g) {
                    holo.dist = Math.max(1.2f, Math.min(12f, holo.dist / g.getScaleFactor()));
                    return true;
                }
            });
            taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                    int tag = labels.tagAt(e.getX(), e.getY());
                    if (tag >= 0) picked(tag); else holo.pick(e.getX(), e.getY());
                    return true;
                }
                @Override public boolean onDoubleTap(MotionEvent e) { holo.exploded = !holo.exploded; lit(toggles.get(0), holo.exploded); return true; }
                @Override public void onLongPress(MotionEvent e) {
                    int tag = labels.tagAt(e.getX(), e.getY());
                    if (tag < 0) tag = holo.selected;
                    if (tag >= 0) edit(tag);
                }
            });
        }
        scaler.onTouchEvent(e);
        taps.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: lastX = e.getX(); lastY = e.getY(); dragging = false; break;
            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() > 1 || scaler.isInProgress()) { lastX = e.getX(); lastY = e.getY(); break; }
                float dx = e.getX() - lastX, dy = e.getY() - lastY;
                if (!dragging && Math.hypot(dx, dy) < dp(6)) break;
                dragging = true;
                if (holo.selected >= 0) { holo.partYaw += dx * 0.45f; holo.partPitch += dy * 0.45f; }
                else { holo.yaw -= dx * 0.3f; holo.pitch = Math.max(8f, Math.min(88f, holo.pitch + dy * 0.22f)); }
                lastX = e.getX();
                lastY = e.getY();
                break;
            default: break;
        }
        return true;
    }

    private void resetView() {
        holo.yaw = 0;
        holo.pitch = 52;
        holo.dist = 4.4f;
        holo.selected = -1;
        holo.partYaw = holo.partPitch = 0;
        holo.peel = 0;
        showLayer();
    }

    /** A part tapped (from the 3D or its tag): it comes forward; its details in the card. */
    @Override public void picked(int i) {
        HoloView.Scene s = holo.scene();
        if (s == null || i < 0 || i >= s.parts.size()) {
            if (holo.selected >= 0) { holo.selected = -1; holo.partYaw = holo.partPitch = 0; }
            return;
        }
        if (holo.selected == i) { holo.selected = -1; return; } // tapped again: back to its place
        holo.selected = i;
        holo.partYaw = 0;
        holo.partPitch = 0;
        ScanBrain.Item it = s.parts.get(i).it;
        StringBuilder b = new StringBuilder(it.n + ". " + it.name);
        if (!it.value.isEmpty()) b.append("  (").append(it.value).append(")");
        if (!it.section.isEmpty()) b.append("\n🧩 ").append(it.section);
        b.append("\n").append(it.info.isEmpty() ? it.note : it.info);
        if (it.fault) b.append("\n⚠️ ").append(it.faultWhy.isEmpty() ? "ఇందులో సమస్య కనిపిస్తోంది" : it.faultWhy);
        if (!it.pins.isEmpty()) b.append("\n📌 పిన్నులు: ").append(it.pins);
        if (!it.price.isEmpty()) b.append("\n💰 ").append(it.price);
        if (!it.sub.isEmpty()) b.append("\n🔁 బదులు: ").append(it.sub);
        List<View> btn = new ArrayList<>();
        final String said = b.toString();
        btn.add(small("🔊 చదువు", v -> Announcer.say(this, said)));
        btn.add(small("🛒 కొను", v -> ScanActions.shop(this, it.buy.isEmpty() ? (it.name + " " + it.value).trim() : it.buy, null)));
        btn.add(small("🔎 వెతుకు", v -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(it.name + " " + it.value)))); } catch (Exception ignored) {} }));
        btn.add(small("✏️ మార్చు", v -> edit(i)));
        btn.add(small("↩️ వెనక్కి", v -> holo.selected = -1));
        showText(said, btn);
    }

    // ================================================================ tools

    private void buildLegend() {
        legend.removeAllViews();
        JSONArray secs = data.optJSONArray("sections");
        for (int i = 0; secs != null && i < secs.length(); i++) {
            JSONObject so = secs.optJSONObject(i);
            if (so == null) continue;
            final String name = so.optString("name"), does = so.optString("does");
            TextView t = chip("● " + name, false);
            int col = Ui.CYAN;
            try { col = Color.parseColor(so.optString("color", "#22D3EE").trim()); } catch (Exception ignored) {}
            t.setTextColor(col | 0xFF000000);
            t.setOnClickListener(v -> {
                holo.sectionFilter = name.equals(holo.sectionFilter) ? null : name;
                showText("🧩 " + name + "\n" + does, null);
            });
            legend.addView(t);
        }
    }

    private void showLayer() {
        HoloView.Scene s = holo.scene();
        if (s == null || s.layers.size() < 2) return;
        int k = Math.min(holo.peel, s.layers.size() - 1);
        layerText.setText("🧅 పొర " + (k + 1) + "/" + s.layers.size() + ": " + s.layers.get(k).name);
    }

    private void showFaults() {
        HoloView.Scene s = holo.scene();
        if (s == null) return;
        StringBuilder b = new StringBuilder("🔥 సమస్యలు కనిపించినవి:\n");
        int n = 0;
        for (HoloView.Part pt : s.parts) if (pt.it.fault) { n++; b.append("• ").append(pt.it.n).append(". ").append(pt.it.name).append(": ").append(pt.it.faultWhy).append('\n'); }
        showText(n == 0 ? "🔥 ఫోటోలో కనిపించే సమస్య (కాలిన గుర్తు, ఉబ్బిన కెపాసిటర్, పగిలిన సోల్డర్) ఏదీ దొరకలేదు. (లోపలి సమస్యలు ఫోటోలో కనిపించవు.)" : b.toString(), null);
    }

    private void showTests() {
        JSONArray t = data.optJSONArray("tests");
        if (t == null || t.length() == 0) { showText("📏 టెస్ట్ పాయింట్లు ఈ స్కాన్‌లో లేవు.", null); return; }
        StringBuilder b = new StringBuilder("📏 multimeter తో చూడాల్సినవి (అంచనా):\n");
        for (int i = 0; i < t.length(); i++) {
            JSONObject o = t.optJSONObject(i);
            if (o != null) b.append("TP").append(i + 1).append(": ").append(o.optString("where")).append(" → ").append(o.optString("expect")).append('\n');
        }
        b.append("\n⚠️ కరెంట్ ఉన్నప్పుడు జాగ్రత్త: ఒక్క ప్రోబ్‌తో ఒకేసారి రెండు పిన్నులు తాకకండి.");
        showText(b.toString(), null);
    }

    /** Building order: each part flies into its place in turn, with the step in Telugu. */
    private void assembly(int k) {
        HoloView.Scene s = holo.scene();
        if (s == null || s.order.isEmpty()) {
            showText("🛠️ ఈ స్కాన్‌లో తయారీ వరుస లేదు (PCB కాకపోవచ్చు).", null);
            return;
        }
        asm = Math.max(0, Math.min(s.order.size() - 1, k));
        holo.asmStep = asm;
        holo.exploded = true;
        HoloView.Part pt = s.parts.get(s.order.get(asm));
        JSONArray build = data.optJSONArray("build");
        String step = build != null && asm < build.length() ? build.optString(asm) : "";
        String text = "🛠️ స్టెప్ " + (asm + 1) + "/" + s.order.size() + ": " + pt.it.n + ". " + pt.it.name + (pt.it.value.isEmpty() ? "" : " (" + pt.it.value + ")")
                + (step.isEmpty() ? "" : "\n" + step);
        List<View> btn = new ArrayList<>();
        btn.add(small("◀ ముందుది", v -> assembly(asm - 1)));
        btn.add(small("తర్వాతది ▶", v -> assembly(asm + 1)));
        btn.add(small("🔊 చదువు", v -> Announcer.say(this, text)));
        showText(text, btn);
    }

    private void assemblyOff() {
        asm = -1;
        holo.asmStep = -1;
    }

    /** The parts list: value, price, what can replace it; tap one to search it in a shop. */
    private void bom() {
        HoloView.Scene s = holo.scene();
        if (s == null || s.parts.isEmpty()) return;
        String[] rows = new String[s.parts.size()];
        for (int i = 0; i < rows.length; i++) {
            ScanBrain.Item it = s.parts.get(i).it;
            rows[i] = it.n + ". " + it.name + (it.value.isEmpty() ? "" : " · " + it.value) + (it.price.isEmpty() ? "" : " · " + it.price)
                    + (it.sub.isEmpty() ? "" : "\n   బదులు: " + it.sub);
        }
        String total = data.optString("bom_total");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("🛒 parts లిస్ట్" + (total.isEmpty() ? "" : " · మొత్తం దాదాపు " + total))
                .setItems(rows, (x, w) -> {
                    ScanBrain.Item it = s.parts.get(w).it;
                    ScanActions.shop(this, it.buy.isEmpty() ? (it.name + " " + it.value).trim() : it.buy, null);
                })
                .setNegativeButton("మూసేయ్", null).show();
    }

    // ================================================================ a deeper scan, a close-up

    /** 🔬: the detailed scan again on the saved photo (every part's job, sections, links, building order, test points). */
    private void deepAgain() {
        if (!p.hasBrain()) { showText("దీనికి AI key కావాలి (Jarvis సెట్టింగ్స్).", null); return; }
        if (data.has("layers")) { showText("పొరలుగా స్కాన్ చేసిన దానికి ఒక్కో పొర ఇప్పటికే వివరంగా ఉంది.", null); return; }
        final Bitmap photo = ScanStore.photo(this, id, "photo.jpg", 1800);
        if (photo == null) return;
        showText("🔬 వివరంగా స్కాన్ చేస్తున్నాను… (కొంచెం టైమ్ పడుతుంది)", null);
        work.execute(() -> {
            String err = null;
            try {
                String reply = Brain.oneShot(p, ScanBrain.deepSystem(p), ScanBrain.context(this, "elec", null, "", "")
                        + "\nHis question: ఇది వివరంగా స్కాన్ చేయి: ప్రతి part, దాని పని, సెక్షన్లు, కనెక్షన్లు, సమస్యలు, తయారీ వరుస, టెస్ట్ పాయింట్లు.",
                        JarvisCamera.jpeg(photo, 1800, 85), p.webSearch(), 12000);
                JSONObject j = ScanBrain.json(reply);
                if (j == null || j.optJSONArray("items") == null) throw new IllegalStateException("no items");
                java.util.Iterator<String> keys = j.keys();
                while (keys.hasNext()) { String k = keys.next(); if (!k.equals("id")) data.put(k, j.get(k)); }
                data.put("board", true);
                ScanStore.save(this, data, null, null);
            } catch (Http.ApiError e) {
                err = "AI జవాబు ఇవ్వలేదు: " + Models.explain(p, e);
            } catch (Exception e) {
                err = "వివరమైన స్కాన్ రాలేదు. మళ్ళీ ప్రయత్నించండి.";
            }
            final String er = err;
            main.post(() -> {
                if (isFinishing()) return;
                if (er != null) { showText(er, null); return; }
                showText("🔬 " + data.optString("title") + "\n" + data.optString("say"), null);
                rebuild();
            });
        });
    }

    /** 🔍: he marks a part of the board; the camera then scans that part close and its small parts are added here. */
    private void closeUp() {
        final Bitmap photo = ScanStore.photo(this, id, data.has("layers") ? firstLayerPhoto() : "photo.jpg", 1800);
        if (photo == null) return;
        FrameLayout f = new FrameLayout(this);
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(photo);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setAdjustViewBounds(true);
        f.addView(iv, new FrameLayout.LayoutParams(-1, -2));
        DrawBox db = new DrawBox(this);
        f.addView(db, new FrameLayout.LayoutParams(-1, -1));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("🔍 దగ్గరగా చూడాల్సిన భాగం చుట్టూ బాక్స్ గీయండి")
                .setView(f)
                .setPositiveButton("కెమెరా తెరువు", (x, w) -> {
                    RectF r = db.box();
                    if (r == null) return;
                    float vw = iv.getWidth(), vh = iv.getHeight(), s = Math.min(vw / photo.getWidth(), vh / photo.getHeight());
                    float ox = (vw - photo.getWidth() * s) / 2, oy = (vh - photo.getHeight() * s) / 2;
                    float[] reg = {clamp((r.left - ox) / s / photo.getWidth()), clamp((r.top - oy) / s / photo.getHeight()),
                            clamp((r.right - ox) / s / photo.getWidth()), clamp((r.bottom - oy) / s / photo.getHeight())};
                    if (reg[2] - reg[0] < 0.03f || reg[3] - reg[1] < 0.03f) return;
                    startActivity(new Intent(this, JarvisCamera.class).putExtra(JarvisCamera.EXTRA_ZOOM_OF, id).putExtra(JarvisCamera.EXTRA_REGION, reg));
                    finish();
                })
                .setNegativeButton("వద్దు", null).show();
    }

    // ================================================================ correcting and adding parts

    /** The item's JSON in the saved scan (top level, or in its layer). */
    private JSONObject itemJson(HoloView.Part pt) {
        JSONArray set;
        JSONArray layers = data.optJSONArray("layers");
        if (layers != null && pt.layer < layers.length()) set = layers.optJSONObject(pt.layer).optJSONArray("items");
        else set = data.optJSONArray("items");
        for (int i = 0; set != null && i < set.length(); i++) {
            JSONObject o = set.optJSONObject(i);
            if (o != null && o.optInt("n", -1) == pt.it.n) return o;
        }
        return null;
    }

    private void edit(int i) {
        HoloView.Scene s = holo.scene();
        if (s == null || i < 0 || i >= s.parts.size()) return;
        HoloView.Part pt = s.parts.get(i);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), 0);
        EditText name = new EditText(this), value = new EditText(this);
        name.setHint("పేరు (ఉదా: 7805 regulator)");
        name.setText(pt.it.name);
        value.setHint("విలువ (ఉదా: 10kΩ, 470µF 25V)");
        value.setText(pt.it.value);
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        value.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(name);
        box.addView(value);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("✏️ " + pt.it.n + " సరిచేయి")
                .setView(box)
                .setPositiveButton("సేవ్", (x, w) -> {
                    JSONObject o = itemJson(pt);
                    if (o == null) return;
                    try {
                        o.put("name", name.getText().toString().trim()).put("value", value.getText().toString().trim()).put("checked", true);
                        saveAndRebuild();
                    } catch (Exception ignored) {}
                })
                .setNeutralButton("తీసేయి", (x, w) -> {
                    JSONArray layers = data.optJSONArray("layers");
                    JSONArray set = layers != null && pt.layer < layers.length() ? layers.optJSONObject(pt.layer).optJSONArray("items") : data.optJSONArray("items");
                    for (int k = 0; set != null && k < set.length(); k++) {
                        JSONObject o = set.optJSONObject(k);
                        if (o != null && o.optInt("n", -1) == pt.it.n) { set.remove(k); break; }
                    }
                    holo.selected = -1;
                    saveAndRebuild();
                })
                .setNegativeButton("వద్దు", null).show();
    }

    private void saveAndRebuild() {
        final JSONObject dd = data;
        work.execute(() -> {
            try { ScanStore.save(this, dd, null, null); } catch (Exception ignored) {}
        });
        rebuild();
    }

    /** ➕: he draws a box around a part that was missed; Jarvis names it (or he does). */
    private void addPart() {
        final Bitmap photo = ScanStore.photo(this, id, data.has("layers") ? firstLayerPhoto() : "photo.jpg", 1800);
        if (photo == null) { Toast.makeText(this, "ఫోటో దొరకలేదు", Toast.LENGTH_SHORT).show(); return; }
        final AlertDialog[] dlg = new AlertDialog[1];
        FrameLayout f = new FrameLayout(this);
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(photo);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setAdjustViewBounds(true);
        f.addView(iv, new FrameLayout.LayoutParams(-1, -2));
        DrawBox db = new DrawBox(this);
        f.addView(db, new FrameLayout.LayoutParams(-1, -1));
        dlg[0] = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("➕ మిస్ అయిన part చుట్టూ బాక్స్ గీయండి")
                .setView(f)
                .setPositiveButton("సరే", (x, w) -> {
                    RectF r = db.box();
                    if (r == null || iv.getDrawable() == null) return;
                    // the box from the view onto the photo (fitted inside the view)
                    float vw = iv.getWidth(), vh = iv.getHeight(), s = Math.min(vw / photo.getWidth(), vh / photo.getHeight());
                    float ox = (vw - photo.getWidth() * s) / 2, oy = (vh - photo.getHeight() * s) / 2;
                    float x0 = (r.left - ox) / s / photo.getWidth(), y0 = (r.top - oy) / s / photo.getHeight();
                    float x1 = (r.right - ox) / s / photo.getWidth(), y1 = (r.bottom - oy) / s / photo.getHeight();
                    x0 = clamp(x0); y0 = clamp(y0); x1 = clamp(x1); y1 = clamp(y1);
                    if (x1 - x0 < 0.005f || y1 - y0 < 0.005f) return;
                    nameNewPart(photo, new float[]{x0, y0, x1, y1});
                })
                .setNegativeButton("వద్దు", null).create();
        dlg[0].show();
    }

    private String firstLayerPhoto() {
        JSONArray l = data.optJSONArray("layers");
        JSONObject o = l == null ? null : l.optJSONObject(0);
        return o == null ? "photo.jpg" : o.optString("photo", "photo.jpg");
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    private void nameNewPart(Bitmap photo, float[] b) {
        int x = Math.round(b[0] * photo.getWidth()), y = Math.round(b[1] * photo.getHeight());
        int w = Math.max(4, Math.round((b[2] - b[0]) * photo.getWidth())), h = Math.max(4, Math.round((b[3] - b[1]) * photo.getHeight()));
        w = Math.min(w, photo.getWidth() - x);
        h = Math.min(h, photo.getHeight() - y);
        final Bitmap crop = Bitmap.createBitmap(photo, x, y, Math.max(1, w), Math.max(1, h));
        if (!p.hasBrain()) { askName(b, "", ""); return; }
        showText("🔎 ఈ part ఏంటో చూస్తున్నాను…", null);
        work.execute(() -> {
            String name = "", value = "", shape = "part", note = "";
            try {
                JSONObject o = ScanBrain.json(Brain.oneShot(p, "You identify one electronic or mechanical part in a close-up photo. " +
                                "Reply JSON only: {\"name\":\"...\",\"value\":\"...\",\"shape\":\"resistor|capacitor|cap_e|ic|chip|transistor|diode|led|inductor|crystal|connector|switch|relay|fuse|pot|module|screw|part\",\"note\":\"one Telugu line: what it does\"}",
                        "What is this part?", JarvisCamera.jpeg(JarvisCamera.scaleTo(crop, 800), 800, 88), false, 400));
                if (o != null) { name = o.optString("name"); value = o.optString("value"); shape = o.optString("shape", "part"); note = o.optString("note"); }
            } catch (Exception ignored) {}
            final String n = name, v = value, sh = shape, nt = note;
            main.post(() -> {
                if (n.isEmpty()) { askName(b, "", ""); return; }
                addItem(b, n, v, sh, nt);
            });
        });
    }

    private void askName(float[] b, String n, String v) {
        EditText name = new EditText(this);
        name.setHint("ఈ part పేరు");
        name.setText(n);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("➕ పేరు").setView(name)
                .setPositiveButton("సేవ్", (x, w) -> { if (!name.getText().toString().trim().isEmpty()) addItem(b, name.getText().toString().trim(), v, "part", ""); })
                .setNegativeButton("వద్దు", null).show();
    }

    private void addItem(float[] b, String name, String value, String shape, String note) {
        try {
            JSONArray layers = data.optJSONArray("layers");
            JSONArray set = layers != null && layers.length() > 0 ? layers.optJSONObject(0).optJSONArray("items") : data.optJSONArray("items");
            if (set == null) { set = new JSONArray(); if (layers != null && layers.length() > 0) layers.optJSONObject(0).put("items", set); else data.put("items", set); }
            int max = 0;
            for (int i = 0; i < set.length(); i++) max = Math.max(max, set.optJSONObject(i) == null ? 0 : set.optJSONObject(i).optInt("n"));
            JSONArray box = new JSONArray().put(Math.round(b[0] * 1000)).put(Math.round(b[1] * 1000)).put(Math.round(b[2] * 1000)).put(Math.round(b[3] * 1000));
            set.put(new JSONObject().put("n", max + 1).put("name", name).put("value", value).put("shape", shape).put("note", note).put("box", box).put("added", true));
            showText("➕ " + (max + 1) + ". " + name + (value.isEmpty() ? "" : " (" + value + ")") + " చేర్చాను.", null);
            saveAndRebuild();
        } catch (Exception ignored) {}
    }

    /** A box drawn with the finger, over the photo in the ➕ dialog. */
    private static final class DrawBox extends View {
        private float x0, y0, x1, y1;
        private boolean has;
        private final Paint pa = new Paint(Paint.ANTI_ALIAS_FLAG);

        DrawBox(Context c) {
            super(c);
            pa.setStyle(Paint.Style.STROKE);
            pa.setStrokeWidth(3 * c.getResources().getDisplayMetrics().density);
            pa.setColor(Ui.CYAN);
        }

        RectF box() { return has ? new RectF(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1)) : null; }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: x0 = x1 = e.getX(); y0 = y1 = e.getY(); has = true; break;
                default: x1 = e.getX(); y1 = e.getY(); break;
            }
            invalidate();
            return true;
        }

        @Override protected void onDraw(Canvas c) { if (has) c.drawRect(box(), pa); }
    }

    // ================================================================ the closing guide (an opened device's screws)

    private void screwGuide(int k) {
        JSONArray layers = data.optJSONArray("layers");
        if (layers == null || layers.length() == 0) return;
        int n = layers.length();
        int step = Math.max(0, Math.min(n - 1, k));
        JSONObject lo = layers.optJSONObject(n - 1 - step); // closing: the innermost first
        if (lo == null) return;
        Bitmap ph = ScanStore.photo(this, id, lo.optString("photo"), 1600);
        List<ScanBrain.Item> screws = new ArrayList<>();
        JSONArray items = lo.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            ScanBrain.Item it = ScanBrain.item(items.optJSONObject(i), i + 1);
            if (it != null && it.box != null && ("screw".equals(it.shape) || it.name.toLowerCase(Locale.ROOT).contains("screw") || it.name.contains("స్క్రూ"))) screws.add(it);
        }
        FrameLayout f = new FrameLayout(this);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setAdjustViewBounds(true);
        if (ph != null) iv.setImageBitmap(ph);
        f.addView(iv, new FrameLayout.LayoutParams(-1, -2));
        ScanHud marks = new ScanHud(this);
        marks.setItems(screws);
        f.addView(marks, new FrameLayout.LayoutParams(-1, -1));
        iv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (ph == null) return;
            float vw = iv.getWidth(), vh = iv.getHeight(), s = Math.min(vw / ph.getWidth(), vh / ph.getHeight());
            float w = ph.getWidth() * s, h = ph.getHeight() * s;
            marks.setContent(new RectF((vw - w) / 2, (vh - h) / 2, (vw + w) / 2, (vh + h) / 2));
        });
        String text = "🔩 మూసేటప్పుడు స్టెప్ " + (step + 1) + "/" + n + ": '" + lo.optString("name") + "'"
                + (screws.isEmpty() ? " (ఈ పొరలో స్క్రూలు గుర్తించలేదు)" : " · " + screws.size() + " స్క్రూలు బిగించండి (బొమ్మలో నంబర్లు)");
        AlertDialog.Builder db = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(text).setView(f)
                .setPositiveButton(step + 1 < n ? "తర్వాత ▶" : "అయిపోయింది", (x, w) -> { if (step + 1 < n) screwGuide(step + 1); })
                .setNegativeButton("మూసేయ్", null);
        if (step > 0) db.setNeutralButton("◀ ముందు", (x, w) -> screwGuide(step - 1));
        db.show();
    }

    // ================================================================ in the air

    private void airGestures(boolean on) {
        if (!on) { if (air != null) air.stop(); return; }
        if (air == null) air = new AirGesture(this, dir -> {
            switch (dir) {
                case AirGesture.LEFT: if (asm >= 0) assembly(asm - 1); else holo.yaw += 40f; break;
                case AirGesture.RIGHT: if (asm >= 0) assembly(asm + 1); else holo.yaw -= 40f; break;
                case AirGesture.UP: holo.exploded = true; lit(toggles.get(0), true); break;
                default: holo.exploded = false; lit(toggles.get(0), false); break;
            }
        });
        if (!air.start()) { Toast.makeText(this, "ముందు కెమెరా తెరవలేకపోయాను", Toast.LENGTH_SHORT).show(); return; }
        showText("✋ ఫోన్ ముందు గాలిలో చేయి ఊపండి: ఎడమ / కుడి = తిప్పు, పైకి = విడదీయి, కిందకి = కలుపు. (ముందు కెమెరా కదలిక మాత్రమే చూస్తుంది; ఏదీ సేవ్ అవ్వదు, పంపదు. ప్రయోగం.)", null);
    }

    // ================================================================ video

    private void startVideo() {
        try {
            File f = new File(getCacheDir(), "hologram.mp4");
            video = new HoloVideo(f, holo.getWidth(), holo.getHeight());
        } catch (Exception e) {
            Toast.makeText(this, "వీడియో మొదలవలేదు", Toast.LENGTH_SHORT).show();
            return;
        }
        encoder = Executors.newSingleThreadExecutor();
        pending.set(0);
        recording = true;
        holo.selected = -1;
        holo.spin = true;
        holo.exploded = true;
        holo.record(true);
        showText("🎬 రికార్డ్ అవుతోంది… (8 సెకన్లు, హోలోగ్రామ్ తిరుగుతుంది)", null);
        main.postDelayed(() -> { if (recording) stopVideo(true); }, 8000);
    }

    @Override public boolean wantFrame() { return recording && encoder != null && pending.get() <= 1; } // (the encoder is not behind)

    @Override public void recordFrame(Bitmap b) {
        if (!recording || encoder == null) { holo.giveBack(b); return; }
        pending.incrementAndGet();
        final HoloVideo v = video;
        encoder.execute(() -> {
            try { if (v != null) v.frame(b); } catch (Exception ignored) {}
            holo.giveBack(b);
            pending.decrementAndGet();
        });
    }

    private void stopVideo(boolean share) {
        recording = false;
        holo.record(false);
        holo.spin = false;
        final HoloVideo v = video;
        video = null;
        if (v == null || encoder == null) return;
        final ExecutorService enc = encoder;
        enc.execute(() -> {
            Coder.Made m = null;
            try {
                v.finish();
                if (share) {
                    byte[] bytes = java.nio.file.Files.readAllBytes(v.file.toPath());
                    String name = "Jarvis_hologram_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmm", Locale.ENGLISH).format(new java.util.Date()) + ".mp4";
                    m = Coder.save(this, "Jarvis/scans", name, "video/mp4", bytes);
                }
            } catch (Exception ignored) {}
            final Coder.Made made = m;
            main.post(() -> {
                if (!share || isFinishing()) return;
                if (made == null) { showText("వీడియో సేవ్ కాలేదు.", null); return; }
                List<View> btn = new ArrayList<>();
                if (made.uri != null) btn.add(small("📤 పంపు", x -> {
                    try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, made.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "ఎవరికి పంపాలి?")); } catch (Exception ignored) {}
                }));
                showText("🎬 వీడియో సేవ్ అయింది: " + made.where, btn);
            });
            enc.shutdown();
        });
    }

    // ================================================================ PDF

    private void pdf() {
        if (!p.hasBrain()) { showText("PDF లోని వివరణ కోసం AI key కావాలి (Jarvis సెట్టింగ్స్).", null); return; }
        showText("📄 తెలుగు PDF తయారు చేస్తున్నాను… (parts, డయాగ్రమ్, తయారీ విధానం)", null);
        work.execute(() -> {
            Coder.Made m = null;
            String err = null;
            try { m = ScanPdf.make(this, p, id); }
            catch (Http.ApiError e) { err = "AI జవాబు ఇవ్వలేదు: " + Models.explain(p, e); }
            catch (Exception e) { err = "PDF చేయలేకపోయాను."; }
            final Coder.Made made = m;
            final String er = err;
            main.post(() -> {
                if (isFinishing()) return;
                if (er != null || made == null) { showText(er != null ? er : "PDF చేయలేకపోయాను.", null); return; }
                List<View> btn = new ArrayList<>();
                if (made.uri != null) {
                    btn.add(small("📄 తెరువు", x -> { try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(made.uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); } catch (Exception e) { Toast.makeText(this, "PDF చూపే యాప్ లేదు", Toast.LENGTH_SHORT).show(); } }));
                    btn.add(small("📤 పంపు", x -> { try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, made.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "ఎవరికి పంపాలి?")); } catch (Exception ignored) {} }));
                }
                btn.add(small("🖼️ డయాగ్రమ్ పంపు", x -> ScanPdf.shareDiagram(this, id)));
                showText("📄 PDF సేవ్ అయింది: " + made.where, btn);
            });
        });
    }

    // ================================================================ the name tags

    /** The tags over the 3D parts (and the test points), drawn where the renderer says they are this frame. */
    private final class Labels extends View {
        volatile HoloView.Scene scene;
        private final Paint tx = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint(Paint.ANTI_ALIAS_FLAG), ln = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<RectF> rects = new ArrayList<>();
        private final List<Integer> owners = new ArrayList<>();

        Labels(Context c) {
            super(c);
            tx.setColor(0xFFFFFFFF);
            bg.setColor(0xCC061424);
            ln.setStyle(Paint.Style.STROKE);
            ln.setStrokeWidth(1.2f * d);
        }

        int tagAt(float x, float y) {
            for (int i = rects.size() - 1; i >= 0; i--) if (rects.get(i).contains(x, y)) return owners.get(i);
            return -1;
        }

        @Override protected void onDraw(Canvas c) {
            rects.clear();
            owners.clear();
            HoloView.Scene s = scene;
            float[] scr = holo.screen;
            if (s != null) {
                int n = s.parts.size();
                for (int i = 0; i < n && i * 3 + 2 < scr.length; i++) {
                    if (scr[i * 3 + 2] < 0.5f) continue;
                    boolean sel = i == holo.selected;
                    if (!labelsOn && !sel) continue;
                    if (holo.asmStep >= 0 && !sel && !s.order.isEmpty() && s.order.indexOf(i) != holo.asmStep) continue;
                    ScanBrain.Item it = s.parts.get(i).it;
                    String t = it.n + ". " + it.name + (sel && !it.value.isEmpty() ? " · " + it.value : "");
                    if (t.length() > 26 && !sel) t = t.substring(0, 25) + "…";
                    tx.setTextSize((sel ? 14f : 11f) * d);
                    float w = tx.measureText(t) + 12 * d, h = (sel ? 22 : 18) * d;
                    float x = scr[i * 3] - w / 2, y = scr[i * 3 + 1] - h - 6 * d;
                    RectF r = new RectF(x, y, x + w, y + h);
                    int col = it.fault ? 0xFFFF4D5E : (holo.showSections ? s.parts.get(i).color : Ui.CYAN);
                    ln.setColor(col);
                    c.drawRoundRect(r, 7 * d, 7 * d, bg);
                    c.drawRoundRect(r, 7 * d, 7 * d, ln);
                    c.drawLine(scr[i * 3], y + h, scr[i * 3], scr[i * 3 + 1], ln);
                    c.drawText(t, x + 6 * d, y + h - 5.5f * d, tx);
                    rects.add(r);
                    owners.add(i);
                }
                if (holo.showTests) {
                    for (int k = 0; k < s.tests.size(); k++) {
                        int o = (n + k) * 3;
                        if (o + 2 >= scr.length || scr[o + 2] < 0.5f) continue;
                        String t = s.tests.get(k).text;
                        if (t.length() > 30) t = t.substring(0, 29) + "…";
                        tx.setTextSize(11f * d);
                        float w = tx.measureText(t) + 12 * d, h = 18 * d, x = scr[o] - w / 2, y = scr[o + 1] - h;
                        ln.setColor(0xFFFFE27A);
                        RectF r = new RectF(x, y, x + w, y + h);
                        c.drawRoundRect(r, 7 * d, 7 * d, bg);
                        c.drawRoundRect(r, 7 * d, 7 * d, ln);
                        c.drawText(t, x + 6 * d, y + h - 5.5f * d, tx);
                    }
                }
            }
            postInvalidateOnAnimation();
        }
    }
}
