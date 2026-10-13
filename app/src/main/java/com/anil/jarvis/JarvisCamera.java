package com.anil.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Jarvis camera: a full-screen camera in which he can ask anything about what it sees, by voice (the mic keeps
 * listening) or by tapping the globe. Jarvis looks at exactly what the screen shows, answers in Telugu as captions
 * that light up word by word while it speaks, draws boxes and name tags on the things it found (they stay on them
 * while the phone moves a little), and offers buttons for what fits: shop search, reminder, contact, challan page,
 * the way there, a 3D hologram of the parts, a Telugu PDF... Modes help it look the right way (medicine, plant,
 * electronics, repair, vehicle, papers...). Guided checks (car / bike checkup, a repair, opening a device layer by
 * layer) go step by step. Every scan is kept on the phone ("స్కాన్ చరిత్ర").
 */
public class JarvisCamera extends Activity implements VoiceIO.Listener, ScanActions.Host {
    static final String EXTRA_MODE = "mode", EXTRA_ASK = "ask", EXTRA_IMAGE = "image", EXTRA_SCAN = "scan_id", EXTRA_DEEP = "deep",
            EXTRA_BACK_OF = "back_of", EXTRA_ZOOM_OF = "zoom_of", EXTRA_REGION = "region", EXTRA_CASE = "case_id";
    private static final int REQ_PERMS = 71, REQ_GALLERY = 72, REQ_LOCATION = 73, REQ_MIC = 74;
    static volatile boolean open;
    /** The camera is using the phone's mic (listening, speaking, recording) or has it off: the wake word stays out. */
    static volatile boolean micInUse;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();   // the AI, saving, the PDF
    private final ExecutorService light = Executors.newSingleThreadExecutor();  // QR reading and AR following (never waits on the AI)
    private Prefs p;
    private VoiceIO voice;
    private float d;

    private CamView cam;
    private ImageView still;
    private ScanHud hud;
    private TextView caption, partial, guideBar, micBtn, freezeBtn, torchBtn, camBtn, pauseBtn;
    private LinearLayout speakBar;
    private ScrollView capScroll;
    private LinearLayout actionRow, modeRow, typeRow, guideRow;
    private HorizontalScrollView actionScroll;
    private HoloOrb orb;
    private EditText typeBox;
    private final List<TextView> modeChips = new ArrayList<>();

    private String mode = "auto";
    private String caseId;              // opened for a case: every answer's scan goes onto its board
    private Bitmap photo;               // the picture being talked about (frozen, or the last one asked about)
    private Bitmap shownPic;            // the picture the boxes on the screen belong to (a zoomed freeze: its part)
    private boolean frozen, fromImage;  // frozen: the screen shows the photo; fromImage: a gallery / app picture (fitted)
    private ScanBrain.Result last;
    private String lastId;              // the saved scan of this picture
    private final ArrayDeque<String[]> turns = new ArrayDeque<>();
    private String guide = "", guideNext = "", guideBase = "", guideSteps = "";
    private boolean busy, listenOn = true, speaking, deepThenPdf, arOn = true;
    private StringBuilder collect; // while an answer's own saving actions run: their lines join the answer
    private int failsInRow;
    private String captionBase = "";
    private String shownCode = "";
    // two pictures (⚖️ compare, 🧰 technician)
    private String pairKind;
    private Bitmap pairFirst;
    // opening a device layer by layer
    private String layersId, layersDevice, layerName;
    private int layerCount;
    private boolean layerShot;
    // a close-up of a part of a board, added into it
    private String closeId;
    private float[] closeRegion;
    private boolean closeShot;
    private boolean pendingDeep;
    private boolean dontKeep, skipSave; // (the next answer is not kept in the scan history: "ఏమి మారింది?" uses a picture taken by itself)

    /** W38 / W57: opened from the watch: the answer goes to the watch too (as text; the phone says it). */
    static volatile boolean fromWatch;

    private void toWatch(String said) {
        if (!fromWatch || said == null || said.isEmpty()) return;
        fromWatch = false;
        final Context app = getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> WatchHub.reply(app, said, false, false, () -> WatchHub.idle(app, null)));
    }

    static void open(Context c, String mode, String question) {
        Intent i = new Intent(c, JarvisCamera.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (mode != null) i.putExtra(EXTRA_MODE, mode);
        if (question != null) i.putExtra(EXTRA_ASK, question);
        c.startActivity(i);
    }

    private int dp(float v) { return Math.round(v * d); }

    // ================================================================ screen

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = new Prefs(this);
        d = getResources().getDisplayMetrics().density;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);
        build();
        voice = new VoiceIO(this, p, this);
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handle(i);
    }

    /** What the camera was opened for: a mode, a question, a picture from another app, a saved scan. */
    private void handle(Intent i) {
        if (i == null) return;
        String m = i.getStringExtra(EXTRA_MODE);
        if (m != null) setMode(m);
        String kc = i.getStringExtra(EXTRA_CASE);
        boolean followUp = i.hasExtra(EXTRA_BACK_OF) || i.hasExtra(EXTRA_ZOOM_OF) || i.hasExtra(EXTRA_IMAGE) || i.hasExtra(EXTRA_SCAN);
        if (kc == null && !followUp) caseId = null; // opened plainly (singleTask: the old intent's case must not stick)
        if (kc != null) {
            caseId = kc;
            JSONObject k = CaseStore.get(this, kc);
            main.postDelayed(() -> tell("📁 " + (k == null ? "కేస్" : k.optString("title")) + " కోసం చూస్తున్నాను: ఇప్పుడు స్కాన్ చేసేవన్నీ ఆ బోర్డుకి వెళ్తాయి.", false), 400);
        }
        String back = i.getStringExtra(EXTRA_BACK_OF);
        if (back != null) { i.removeExtra(EXTRA_BACK_OF); main.postDelayed(() -> backSide(back), 300); return; }
        String zoomOf = i.getStringExtra(EXTRA_ZOOM_OF);
        if (zoomOf != null) {
            float[] reg = i.getFloatArrayExtra(EXTRA_REGION);
            i.removeExtra(EXTRA_ZOOM_OF);
            main.postDelayed(() -> closeUp(zoomOf, reg), 300);
            return;
        }
        String img = i.getStringExtra(EXTRA_IMAGE);
        if (img != null) {
            i.removeExtra(EXTRA_IMAGE);
            Bitmap bm = decodeFile(img, 1800);
            if (bm != null) {
                showImage(bm);
                String q = i.getStringExtra(EXTRA_ASK);
                i.removeExtra(EXTRA_ASK);
                main.postDelayed(() -> ask(q != null ? q : defaultQuestion()), 500);
                return;
            }
        }
        String id = i.getStringExtra(EXTRA_SCAN);
        if (id != null) { i.removeExtra(EXTRA_SCAN); openSaved(id); return; }
        String q = i.getStringExtra(EXTRA_ASK);
        boolean deep = i.getBooleanExtra(EXTRA_DEEP, false);
        if (q != null || deep) {
            i.removeExtra(EXTRA_ASK);
            i.removeExtra(EXTRA_DEEP);
            final String qq = q;
            main.postDelayed(() -> { // the camera has a moment to start and focus
                if (deep) deepScan();
                else if (qq != null && LockBrain.isLockAsk(qq)) lockAsk(qq); // ("ఆ కుక్కని జూమ్ చేయి" from anywhere: found, locked, zoomed)
                else ask(qq);
            }, 1600);
        }
    }

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        cam = new CamView(this);
        cam.listener = (isOpen, problem) -> {
            if (problem != null) tell("📷 " + problem, false);
            refreshButtons();
        };
        root.addView(cam, new FrameLayout.LayoutParams(-1, -1));
        still = new ImageView(this);
        still.setBackgroundColor(0xFF000000);
        still.setScaleType(ImageView.ScaleType.FIT_XY);
        still.setVisibility(View.GONE);
        root.addView(still, new FrameLayout.LayoutParams(-1, -1));
        hud = new ScanHud(this);
        root.addView(hud, new FrameLayout.LayoutParams(-1, -1));
        hud.setOnTouchListener(this::touch);
        buildLive(root);
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> fitContent());

        // ---- top: close, name, torch, switch camera, more
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0x00000000}));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(8), dp(6), dp(4));
        bar.addView(iconBtn("✕", v -> finish()));
        TextView name = Ui.mono(this, "JARVIS CAMERA", 13, Ui.CYAN);
        name.setLetterSpacing(0.3f);
        name.setPadding(dp(6), 0, 0, 0);
        bar.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        liveBtn = iconBtn("🎯", v -> setLive(!liveOn())); // rings on / off (held: scan everything)
        liveBtn.setOnLongClickListener(v -> { fullScan(); return true; });
        liveBtn.setAlpha(liveOn() ? 1f : 0.5f);
        liveBtn.setContentDescription("లైవ్ రింగ్స్ (నొక్కి పట్టుకుంటే అన్నీ స్కాన్)");
        bar.addView(liveBtn);
        torchBtn =iconBtn("🔦", v -> { cam.setTorch(!cam.torchOn()); refreshButtons(); });
        bar.addView(torchBtn);
        camBtn = iconBtn("🔄", v -> { switchCam(); tell("📷 " + cam.which(), false); refreshButtons(); });
        bar.addView(camBtn);
        bar.addView(iconBtn("⋯", v -> menu()));
        top.addView(bar);
        HorizontalScrollView ms = new HorizontalScrollView(this);
        ms.setHorizontalScrollBarEnabled(false);
        modeRow = new LinearLayout(this);
        modeRow.setPadding(dp(8), 0, dp(8), dp(6));
        for (String[] m : ScanBrain.MODES) {
            TextView c = chip(m[1], false);
            c.setOnClickListener(v -> { setMode(m[0]); tell(m[1] + " మోడ్. చూపించి అడగండి, లేదా ◉ నొక్కండి.", false); });
            modeChips.add(c);
            modeRow.addView(c);
        }
        ms.addView(modeRow);
        top.addView(ms);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        topPanel = top;

        // ---- bottom: buttons for the answer, the guided step, the captions, the controls
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xF2000000, 0xB3000000, 0x00000000}));
        bottom.setPadding(0, dp(28), 0, dp(10));
        guideRow = new LinearLayout(this);
        guideRow.setGravity(Gravity.CENTER_VERTICAL);
        guideRow.setPadding(dp(12), dp(6), dp(8), dp(6));
        guideRow.setBackground(Ui.round(this, 0xE60B2A3A, Ui.alpha(Ui.CYAN, 0x88), 14));
        guideBar = Ui.text(this, "", 14.5f, 0xFFFFFFFF);
        guideRow.addView(guideBar, new LinearLayout.LayoutParams(0, -2, 1));
        guideRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.setMargins(dp(10), 0, dp(10), dp(6));
        bottom.addView(guideRow, glp);
        actionScroll = new HorizontalScrollView(this);
        actionScroll.setHorizontalScrollBarEnabled(false);
        actionRow = new LinearLayout(this);
        actionRow.setPadding(dp(8), 0, dp(8), dp(4));
        actionScroll.addView(actionRow);
        actionScroll.setVisibility(View.GONE);
        bottom.addView(actionScroll);
        final int capMax = (int) (getResources().getDisplayMetrics().heightPixels * 0.27f);
        capScroll = new ScrollView(this) {
            @Override protected void onMeasure(int w, int h) { super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(capMax, View.MeasureSpec.AT_MOST)); }
        };
        caption = Ui.text(this, "", 18.5f, 0xFFFFFFFF);
        caption.setShadowLayer(4 * d, 0, 1 * d, 0xFF000000);
        caption.setLineSpacing(0, 1.22f);
        caption.setPadding(dp(16), dp(4), dp(16), dp(4));
        caption.setOnClickListener(v -> { if (speaking || voice.speaking) { voice.stopSpeaking(); speaking = false; refreshButtons(); listenSoon(300); } });
        capScroll.addView(caption);
        // while Jarvis reads: ⏸ hold it (▶ carries on from there), ⏹ enough
        speakBar = new LinearLayout(this);
        speakBar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        speakBar.setPadding(dp(10), 0, dp(10), dp(2));
        pauseBtn = pill("⏸ ఆపు", v -> togglePause());
        speakBar.addView(pauseBtn);
        speakBar.addView(pill("⏹ చాలు", v -> stopReading()));
        speakBar.setVisibility(View.GONE);
        bottom.addView(speakBar);
        bottom.addView(capScroll);
        partial = Ui.text(this, "", 14, Ui.alpha(Ui.CYAN, 0xDD));
        partial.setPadding(dp(16), dp(2), dp(16), dp(2));
        partial.setSingleLine(true);
        bottom.addView(partial);
        typeRow = new LinearLayout(this);
        typeRow.setGravity(Gravity.CENTER_VERTICAL);
        typeRow.setPadding(dp(10), dp(4), dp(10), dp(4));
        typeBox = new EditText(this);
        typeBox.setHint("ఇక్కడ టైప్ చేసి అడగండి");
        typeBox.setTextColor(0xFFFFFFFF);
        typeBox.setHintTextColor(Ui.FAINT);
        typeBox.setBackground(Ui.round(this, 0x33FFFFFF, 0x55FFFFFF, 20));
        typeBox.setPadding(dp(14), dp(8), dp(14), dp(8));
        typeBox.setImeOptions(EditorInfo.IME_ACTION_SEND);
        typeBox.setSingleLine(true);
        typeBox.setOnEditorActionListener((v, a, e) -> { sendTyped(); return true; });
        typeRow.addView(typeBox, new LinearLayout.LayoutParams(0, -2, 1));
        typeRow.addView(iconBtn("➤", v -> sendTyped()));
        typeRow.setVisibility(View.GONE);
        bottom.addView(typeRow);
        bottom.addView(zoomButtons());
        LinearLayout ctl = new LinearLayout(this);
        ctl.setGravity(Gravity.CENTER);
        ctl.setPadding(dp(8), dp(6), dp(8), dp(0));
        ctl.addView(roundBtn("🖼️", "గ్యాలరీ", v -> gallery()), weight());
        freezeBtn = roundBtn("⏸️", "ఆపు", v -> toggleFreeze());
        ctl.addView(freezeBtn, weight());
        FrameLayout orbBox = new FrameLayout(this);
        orbBox.setBackground(Ui.round(this, 0x66061424, Ui.alpha(Ui.CYAN, 0xCC), 999));
        orb = new HoloOrb(this, 60);
        orb.setContentDescription("చూడు (నొక్కి పట్టుకుంటే 3D స్కాన్)");
        orbBox.addView(orb, new FrameLayout.LayoutParams(-1, -1));
        orbBox.setOnClickListener(v -> scanTap());
        orbBox.setOnLongClickListener(v -> { deepScan(); return true; });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(dp(78), dp(78));
        olp.setMargins(dp(10), 0, dp(10), 0);
        ctl.addView(orbBox, olp);
        micBtn = roundBtn("🎙️", "వింటున్నా", v -> micTap());
        micBtn.setOnLongClickListener(v -> { toggleMicOff(); return true; });
        ctl.addView(micBtn, weight());
        ctl.addView(roundBtn("🧊", "3D", v -> hologram()), weight());
        bottom.addView(ctl);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        bottomPanel = bottom;
        View.OnLayoutChangeListener safe = (v, l, t, r, bt, ol, ot, or, ob) -> lens.setSafe(top.getHeight(), bottom.getHeight());
        top.addOnLayoutChangeListener(safe); // (the rings and the card keep clear of the bars, also when the captions grow)
        bottom.addOnLayoutChangeListener(safe);
        setContentView(root);
        setMode(mode);
        tell("📷 చూపించి ఏదైనా అడగండి: \"ఇది ఏంటి?\", \"దీని ధర ఎంత?\", \"ఈ మందు దేనికి?\". ◉ నొక్కితే చూసి చెబుతాను; నొక్కి పట్టుకుంటే 3D స్కాన్.", false);
    }

    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }

    private TextView pill(String s, View.OnClickListener l) {
        TextView t = Ui.text(this, s, 14, 0xFFFFFFFF);
        t.setPadding(dp(14), dp(7), dp(14), dp(7));
        t.setBackground(Ui.round(this, 0x99061424, Ui.alpha(Ui.CYAN, 0xAA), 999));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(4), dp(2), dp(4), dp(2));
        t.setLayoutParams(lp);
        return t;
    }

    /** ⏸ / ▶ while Jarvis reads: held right where it is, carried on from there (the mic listens meanwhile for "కొనసాగించు"). */
    private void togglePause() {
        if (voice == null || !(speaking || voice.speaking)) { refreshButtons(); return; }
        if (voice.isPaused()) {
            camHold(); // the wake word must not hear the rest of the answer
            voice.resume(); // (an open mic is let go first)
            speaking = voice.speaking;
        } else {
            voice.pause();
            listenSoon(500); // "కొనసాగించు" / "చాలు" / a new question by voice too
        }
        refreshButtons();
    }

    /** ⏹: enough of this answer. */
    private void stopReading() {
        if (voice == null) return;
        voice.stopSpeaking();
        speaking = false;
        caption.setText(captionBase);
        pendingAuto.clear(); // like saying "చాలు": what was to open after it doesn't either
        refreshButtons();
        listenSoon(300);
    }

    private TextView iconBtn(String s, View.OnClickListener l) {
        TextView t = Ui.text(this, s, 20, 0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setOnClickListener(l);
        return t;
    }

    private TextView roundBtn(String icon, String label, View.OnClickListener l) {
        TextView t = Ui.text(this, icon + "\n" + label, 11.5f, 0xFFDDEFF5);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(4), 0, dp(4));
        t.setOnClickListener(l);
        return t;
    }

    private TextView chip(String s, boolean lit) {
        TextView t = Ui.text(this, s, 13.5f, 0xFFFFFFFF);
        t.setSingleLine(true);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        t.setBackground(Ui.round(this, lit ? Ui.alpha(Ui.CYAN, 0x55) : 0x40000000, lit ? Ui.CYAN : 0x55FFFFFF, 999));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        t.setLayoutParams(lp);
        return t;
    }

    private void setMode(String m) {
        boolean ok = false;
        for (String[] x : ScanBrain.MODES) if (x[0].equals(m)) ok = true;
        mode = ok ? m : "auto";
        for (int i = 0; i < modeChips.size(); i++) {
            boolean lit = ScanBrain.MODES[i][0].equals(mode);
            modeChips.get(i).setBackground(Ui.round(this, lit ? Ui.alpha(Ui.CYAN, 0x55) : 0x40000000, lit ? Ui.CYAN : 0x55FFFFFF, 999));
        }
        if (hud != null) hud.hint(mode.equals("auto") ? "" : ScanBrain.modeLabel(mode));
    }

    private void refreshButtons() {
        if (torchBtn != null) {
            torchBtn.setVisibility(cam.hasFlash() && !cam.isFront() ? View.VISIBLE : View.GONE);
            torchBtn.setAlpha(cam.torchOn() ? 1f : 0.55f);
        }
        if (camBtn != null) camBtn.setVisibility(cam.cameraCount() > 1 ? View.VISIBLE : View.GONE);
        if (freezeBtn != null) freezeBtn.setText(frozen ? "▶️\nలైవ్" : "⏸️\nఆపు");
        if (speakBar != null) {
            boolean reading = voice != null && (speaking || voice.speaking);
            speakBar.setVisibility(reading ? View.VISIBLE : View.GONE);
            if (reading) pauseBtn.setText(voice.isPaused() ? "▶ కొనసాగించు" : "⏸ ఆపు");
        }
        if (micBtn != null) micBtn.setText(!listenOn ? "🔇\nమైక్ ఆఫ్" : voice != null && voice.listening ? "🎙️\nవింటున్నా"
                : wakeMode() && !engaged ? "🎙️\nనొక్కి అడుగు" : "🎙️\nఆన్");
        if (orb != null) orb.setState(busy ? HoloOrb.THINKING : speaking ? HoloOrb.SPEAKING : voice != null && voice.listening ? HoloOrb.LISTENING : HoloOrb.IDLE);
    }

    // ---- touch: pinch = zoom, tap on a box = that thing, tap elsewhere = focus, double tap = freeze / live
    private ScaleGestureDetector scaler;
    private GestureDetector taps;

    private boolean touch(View v, MotionEvent e) {
        if (scaler == null) {
            scaler = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector g) {
                    if (frozen) { // a frozen camera picture: zoom into it (up to 8×)
                        if (!fromImage) setStillZoom(stillZ * g.getScaleFactor(), g.getFocusX(), g.getFocusY());
                        return true;
                    }
                    if (zoomAnim != null && zoomAnim.isRunning()) zoomAnim.cancel();
                    float[] from = zoomState();
                    float[] lock0 = lens.locked() ? lens.lockBox() : null;
                    cam.setZoom(totalZoom() * g.getScaleFactor());
                    if (lock0 != null) lens.setLockBoxNow(mapBox(lock0, from, zoomState())); // (the locked ring stays on it)
                    if (totalZoom() > cam.opticalMax() + 0.01f) zoomNote();
                    return true;
                }
            });
            taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (lens.hitCard(e.getX(), e.getY())) { unlockLens(false); return true; } // (the card tapped: it closes)
                    ScanBrain.Item it = hud.hit(e.getX(), e.getY());
                    if (it != null) { itemTapped(it); return true; }
                    if (!frozen) {
                        LiveTracker.Track t = lens.hitTrack(e.getX(), e.getY());
                        if (t != null) { lockOnTrack(t); return true; } // a ring tapped: lock on, its details
                        cam.focus();
                        hud.select(-1);
                    }
                    return true;
                }
                @Override public boolean onDoubleTap(MotionEvent e) { toggleFreeze(); return true; }
            });
        }
        scaler.onTouchEvent(e);
        if (!scaler.isInProgress()) taps.onTouchEvent(e);
        return true;
    }

    /** A box tapped: its details in the captions (and spoken); a follow-up question can be about it. */
    private void itemTapped(ScanBrain.Item it) {
        StringBuilder b = new StringBuilder(it.n + ". " + it.name);
        if (!it.value.isEmpty()) b.append(" (").append(it.value).append(")");
        b.append('\n');
        if (!it.info.isEmpty()) b.append(it.info); else if (!it.note.isEmpty()) b.append(it.note);
        if (it.fault) b.append("\n⚠️ ").append(it.faultWhy.isEmpty() ? "ఇందులో సమస్య కనిపిస్తోంది." : it.faultWhy);
        if (!it.price.isEmpty()) b.append("\n💰 ").append(it.price);
        say(b.toString());
        List<JSONObject> acts = new ArrayList<>();
        try {
            acts.add(new JSONObject().put("type", "web").put("query", it.name + " " + it.value).put("label", "🔎 " + cut(it.name, 14) + " గురించి"));
            if (!it.buy.isEmpty() || "pcb".equals(last == null ? "" : last.kind) || !it.value.isEmpty())
                acts.add(new JSONObject().put("type", "shop").put("query", it.buy.isEmpty() ? (it.name + " " + it.value).trim() : it.buy).put("label", "🛒 కొనడానికి"));
        } catch (Exception ignored) {}
        setActions(acts);
    }

    // ================================================================ lifecycle

    private boolean talkSet;

    @Override protected void onResume() {
        super.onResume();
        try { Power.sample(this); } catch (Throwable ignored) {} // (the time before this is not the camera's)
        open = true;
        TopCard.stepAside(); // a message card talking at the top stops before the camera listens
        engaged = true; // just opened: he is about to ask (then, in wake mode, it waits for "Jarvis")
        micInUse = true; // the camera's mic from now on (the wake word must not start meanwhile)
        WakeService.cameraWake = wakeRunnable;
        talkSet = !MainActivity.inConversation;
        if (talkSet) MainActivity.talking(true); // the wake word and Jarvis's own remarks wait while the camera talks
        WakeService.pause(this);
        VoiceIO.yieldOthers(voice);
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cam.open();
        main.postDelayed(qrTick, 2000);
        main.postDelayed(arTick, 400);
        liveResume();
        listenSoon(900);
        refreshButtons();
    }

    @Override protected void onPause() {
        super.onPause();
        try { Power.sample(this); } catch (Throwable ignored) {} // V12: the camera's minutes, credited while it is still open
        open = false;
        main.removeCallbacks(qrTick);
        main.removeCallbacks(arTick);
        main.removeCallbacks(relisten);
        livePause();
        voice.cancelListening();
        voice.stopSpeaking();
        speaking = false;
        SoundClip.stop = true;
        cam.close();
        micInUse = false;
        if (WakeService.cameraWake == wakeRunnable) WakeService.cameraWake = null;
        if (talkSet) { talkSet = false; MainActivity.talking(false); }
        if (p.wakeReady()) WakeService.resume(this);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        cam.release();
        liveDestroy();
        voice.shutdown();
        work.shutdownNow();
        light.shutdownNow();
    }

    @Override public void onBackPressed() {
        if (typeRow.getVisibility() == View.VISIBLE) { typeRow.setVisibility(View.GONE); return; }
        if (frozen && !fromImage) { toggleFreeze(); return; }
        super.onBackPressed();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cam.open();
        else if (code == REQ_PERMS) tell("📷 కెమెరా అనుమతి ఇస్తేనే చూడగలను. గ్యాలరీ ఫోటోతో అడగొచ్చు.", true);
        if (code == REQ_LOCATION && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            ScanActions.run(this, act("parking"), this);
        listenSoon(600);
    }

    // ================================================================ listening and speaking

    private final Runnable relisten = this::listenNow;

    private void listenSoon(long ms) {
        main.removeCallbacks(relisten);
        if (listenOn) main.postDelayed(relisten, ms);
    }

    private void listenNow() {
        if (!open || !listenOn || busy || voice.listening) return;
        if ((speaking || voice.speaking) && !voice.isPaused()) return; // (a held speech: he may say "కొనసాగించు")
        if (wakeMode() && !engaged) { idle(); return; } // nothing going on: wait for "Jarvis" silently
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { listenOn = false; refreshButtons(); return; }
        if (!voice.canListen()) { listenOn = false; refreshButtons(); return; }
        camHold();
        voice.listen(p.listenLang(), wakeMode() ? 0 : ALWAYS_WAIT_S);
        refreshButtons();
    }

    /** The always-on mic waits this long for him each time (fewer mic opens and closes, so fewer of the phone's beeps). */
    private static final int ALWAYS_WAIT_S = 30;

    // Listening in the camera. Every time the phone's speech recognizer opens or closes the mic, the phone beeps, and
    // on his phone that beep can't be silenced safely (it is on the ringtone's sound). So by default the camera keeps
    // that mic closed while nothing is going on and waits for "Jarvis" with Jarvis's own silent wake word (the same
    // one as everywhere, handed over from WakeService while the camera is open); after "Jarvis", a 🎙️ tap, or anything
    // Jarvis says, it listens (and once more for a follow-up), then goes back to waiting. With the setting
    // "కెమెరాలో ఎప్పుడూ వింటూ ఉండు", or without the wake word, it listens all the time as before.

    /** A talk is on (just opened, "Jarvis" called, 🎙️ tapped, Jarvis spoke): the next listen happens. */
    private boolean engaged = true;
    private final Runnable wakeRunnable = this::onWakeWord;

    /** "Jarvis" can be heard here now (wake word on, its service running, and not limited to charging). */
    private boolean wakeMode() { return !p.camAlwaysListen() && p.wakeReady() && WakeService.running && !"charging".equals(p.wakeWhen()); }

    /** The phone's mic is the camera's now: the wake word lets go of it, Jarvis's remarks wait. */
    private void camHold() {
        micInUse = true;
        if (!talkSet && !MainActivity.inConversation) { talkSet = true; MainActivity.talking(true); }
        WakeService.pause(this);
    }

    /** Nothing more to hear now: the phone's mic closes and the camera waits for "Jarvis" (silently). */
    private void idle() {
        if (!wakeMode()) { engaged = true; listenSoon(700); return; } // no wake word now: back to always listening
        engaged = false;
        main.removeCallbacks(relisten);
        if (!open) return;
        micInUse = false;
        if (voice.listening) voice.cancelListening();
        WakeService.cameraWake = wakeRunnable;
        if (talkSet) { talkSet = false; MainActivity.talking(false); }
        if (p.wakeReady()) WakeService.resume(this);
        partial.setText(listenOn ? "🎙️ \"Jarvis\" అనండి, లేదా 🎙️ నొక్కండి" : "");
        refreshButtons();
    }

    /** "Jarvis" heard while the camera waits (WakeService has already let go of the mic): listen to him now. */
    private void onWakeWord() {
        if (!open) return;
        if (commentaryOn) setCommentary(false, true); // "Jarvis": the commentary stops, he is talking
        if (!listenOn) { listenOn = true; failsInRow = 0; }
        engaged = true;
        camHold();
        Sfx.chirp(this, p);
        partial.setText("🎙️ చెప్పండి…");
        listenSoon(300);
    }

    /** 🎙️ long-pressed: the mic off altogether (nothing heard, not even "Jarvis"), or on again. */
    private void toggleMicOff() {
        if (wakeMode()) {
            listenOn = !listenOn;
            if (!listenOn) { voice.cancelListening(); partial.setText("🔇 మైక్ ఆఫ్: 🎙️ నొక్కితే వింటాను"); idleMicOff(); }
            else { failsInRow = 0; WakeService.cameraWake = wakeRunnable; idle(); } // "Jarvis" heard again at once
            refreshButtons();
            return;
        }
        toggleListen();
    }

    /** Mic off in wake mode: the wake word too lets go here, nothing is heard. */
    private void idleMicOff() {
        engaged = false;
        micInUse = true; // the wake word stays off here too (it would open nothing over the camera anyway)
        main.removeCallbacks(relisten);
        if (talkSet) { talkSet = false; MainActivity.talking(false); }
        // the wake word itself stays paused while the camera is open with the mic off
        WakeService.pause(this);
    }

    /** 🎙️ tapped: in wake mode, listen now (or stop listening); otherwise the always-on mic on / off. */
    private void micTap() {
        if (voice.listening) { if (wakeMode()) idle(); else toggleListen(); return; } // listening: stop
        if (!wakeMode() && listenOn && engaged) { toggleListen(); return; } // always listening: 🎙️ turns it off
        if (!listenOn) { listenOn = true; failsInRow = 0; WakeService.cameraWake = wakeRunnable; }
        engaged = true;
        camHold();
        listenSoon(100);
        refreshButtons();
    }

    private void toggleListen() {
        listenOn = !listenOn;
        if (listenOn && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
        if (!listenOn) { voice.cancelListening(); partial.setText(""); }
        failsInRow = 0;
        listenSoon(100);
        refreshButtons();
    }

    @Override public void onListening() { partial.setText("🎙️ వింటున్నాను…"); refreshButtons(); }
    @Override public void onUnderstanding() { partial.setText("🎙️ అర్థం చేసుకుంటున్నాను…"); }
    private long heardPartialAt; // (he is mid-sentence: the commentary waits)
    @Override public void onPartial(String text) {
        if (text != null && !text.isEmpty()) heardPartialAt = System.currentTimeMillis();
        partial.setText(text == null || text.isEmpty() ? "🎙️ వింటున్నాను…" : "🎙️ " + text);
    }
    @Override public void onLevel(float level) {}
    @Override public void onVoiceReady() { listenSoon(300); }
    /** He talked over the answer: the speech is held (not lost) and the mic opens for him now. */
    @Override public void onBargeIn() {
        if (!open || busy) return;
        main.removeCallbacks(relisten);
        if (!listenOn) { voice.resume(); return; } // the mic is off: a noise must not hold the answer
        if (!voice.listening) voice.listen(p.listenLang()); // (keeps the held speech)
        refreshButtons();
    }
    @Override public void onMicTaken() { partial.setText("🎙️ మైక్ వేరే Jarvis స్క్రీన్ తీసుకుంది"); refreshButtons(); }

    @Override public void onHeard(String text) {
        partial.setText("");
        if (voice.isPaused()) { // the answer is held: "కొనసాగించు" / "ఆపు" / "చాలు", or a new question
            int r = voice.pausedHeard(text);
            if (afterPaused(r)) return;
        }
        refreshButtons();
        if (text == null || text.trim().isEmpty()) { if (wakeMode()) idle(); else listenSoon(400); return; }
        failsInRow = 0;
        heard(text.trim());
    }

    /** What happened to a held answer after he spoke; true when nothing more is to be done with his words. */
    private boolean afterPaused(int r) {
        if (r == VoiceIO.RESUMED) { if (voice.speaking) camHold(); speaking = voice.speaking; refreshButtons(); if (!speaking) listenSoon(350); return true; }
        if (r == VoiceIO.HELD) { refreshButtons(); if (wakeMode()) idle(); else listenSoon(600); return true; } // ("Jarvis, కొనసాగించు" or ▶)
        speaking = false; // STOPPED or a new question: the held answer is dropped
        caption.setText(captionBase);
        refreshButtons();
        if (r == VoiceIO.STOPPED) { pendingAuto.clear(); listenSoon(400); return true; } // "చాలు": what was to open after it, too
        return false;
    }

    @Override public void onListenFailed(int error) {
        partial.setText("");
        if (voice.isPaused()) { // nothing clear while held: carry on (after a talk-over), or stay held (he held it)
            int r = voice.pausedHeard("");
            if (r == VoiceIO.HELD) { // his own ⏸ / "ఆపు": it stays held, never carried on by itself
                refreshButtons();
                if (wakeMode()) { idle(); return; }
                if (++failsInRow >= 6) { failsInRow = 0; partial.setText("⏸ ఆపి ఉంచాను: ▶ కొనసాగించు నొక్కండి"); return; }
                listenSoon(600);
                return;
            }
            if (afterPaused(r)) return;
        }
        refreshButtons();
        if (wakeMode()) { idle(); return; } // nothing (more) said: wait for "Jarvis" again
        if (++failsInRow >= 6) { // the phone's listening keeps failing: stop trying, the button turns it on again
            listenOn = false;
            refreshButtons();
            partial.setText("🔇 మైక్ ఆగింది: 🎙️ నొక్కితే మళ్ళీ వింటాను");
            return;
        }
        listenSoon(failsInRow > 2 ? 2500 : 700);
    }

    @Override public void onSpeakStart() {
        speaking = true;
        engaged = true; // Jarvis said something: he may answer it (one listen after)
        camHold(); // the wake word must not hear Jarvis's own voice
        refreshButtons();
    }

    /** What is being said is one of the camera's own remarks ("2 మనుషులు కనిపిస్తున్నారు"), not an answer. */
    private boolean remark;

    @Override public void onSpeakDone() {
        speaking = false;
        caption.setText(captionBase);
        refreshButtons();
        if (remark) {
            remark = false;
            if (!listenOn) { if (wakeMode()) idleMicOff(); return; } // (mic off stays off)
            if (wakeMode() && pendingAuto.isEmpty()) { idle(); return; }
        }
        if (!pendingAuto.isEmpty()) runPendingAuto(); // (a listen after it is skipped while it speaks or works)
        listenSoon(350);
        // (in wake mode that is the one follow-up listen; silence then goes back to waiting for "Jarvis")
    }

    @Override public void onWord(String spoken, int start, int end) {
        if (spoken == null || !spoken.equals(captionBase) || start < 0 || end > spoken.length() || start >= end) return;
        SpannableString s = new SpannableString(captionBase);
        int ss = Math.max(0, captionBase.lastIndexOf('.', start - 1) + 1), se = captionBase.indexOf('.', end);
        if (se < 0) se = captionBase.length();
        s.setSpan(new BackgroundColorSpan(Ui.alpha(Ui.CYAN, 0x33)), ss, Math.min(captionBase.length(), se + 1), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(0xFFFFE27A), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        caption.setText(s);
        android.text.Layout l = caption.getLayout();
        if (l != null) {
            int y = l.getLineTop(l.getLineForOffset(start));
            if (Math.abs(capScroll.getScrollY() - (y - dp(20))) > dp(14)) capScroll.smoothScrollTo(0, Math.max(0, y - dp(20)));
        }
    }

    /** Words in the captions, read aloud. */
    private void say(String text) {
        remark = false;
        main.removeCallbacks(relisten); // (a listen starting now would cut the answer)
        captionBase = text == null ? "" : text;
        caption.setText(captionBase);
        capScroll.scrollTo(0, 0);
        voice.cancelListening();
        if (captionBase.trim().isEmpty()) { listenSoon(300); return; }
        voice.speak(captionBase, p.speechRate());
    }

    @Override public void tell(String text, boolean speak) {
        if (collect != null) { collect.append("\n").append(text); return; } // (said together with the answer)
        if (speak) { say(text); return; }
        captionBase = text == null ? "" : text;
        caption.setText(captionBase);
        capScroll.scrollTo(0, 0);
    }

    // ================================================================ what he said

    /** The words are exactly one of these phrases (so a real question that only contains a word like "live" is not a command). */
    private static boolean is(String t, String... phrases) {
        for (String x : phrases) if (t.equals(x)) return true;
        return false;
    }

    /** A few short phrases work the camera itself; everything else is a question about the picture. */
    private void heard(String text) {
        String t = text.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}।]", " ").replaceAll("జార్విస్|jarvis|ప్లీజ్|please", " ")
                .replaceAll("\\s+", " ").trim();
        int words = t.isEmpty() ? 0 : t.split(" ").length;
        if (words <= 4) {
            int cmd = VoiceIO.command(t);
            if (cmd != 0 && (speaking || voice.speaking)) { voice.stopSpeaking(); speaking = false; refreshButtons(); listenSoon(400); return; }
            if (is(t, "మూసేయ్", "మూసెయ్", "క్లోజ్", "క్లోజ్ చేయి", "కెమెరా మూసేయ్", "కెమెరా క్లోజ్", "కెమెరా ఆపు", "బయటికి వెళ్ళు", "exit", "close")) { finish(); return; }
            if (is(t, "ఫ్రీజ్", "ఫ్రీజ్ చేయి", "freeze", "ఫోటో ఆపు", "ఆపి చూడు", "హోల్డ్", "hold")) { if (!frozen) toggleFreeze(); listenSoon(300); return; }
            if (is(t, "లైవ్", "live", "లైవ్ చేయి", "లైవ్ కి వెళ్ళు", "మళ్ళీ కెమెరా")) { if (frozen) toggleFreeze(); listenSoon(300); return; }
            if (is(t, "కెమెరా మార్చు", "ముందు కెమెరా", "వెనక కెమెరా", "సెల్ఫీ", "సెల్ఫీ కెమెరా", "switch camera", "ఫ్రంట్ కెమెరా", "బ్యాక్ కెమెరా")) {
                switchCam(); refreshButtons(); tell("📷 " + cam.which(), false); listenSoon(600); return;
            }
            if (is(t, "టార్చ్ ఆఫ్", "టార్చ్ ఆపు", "ఫ్లాష్ ఆఫ్", "లైట్ ఆఫ్", "లైట్ ఆపు", "torch off", "flash off")) { cam.setTorch(false); refreshButtons(); listenSoon(300); return; }
            if (is(t, "టార్చ్", "టార్చ్ ఆన్", "టార్చ్ వేయి", "ఫ్లాష్", "ఫ్లాష్ ఆన్", "లైట్ వేయి", "లైట్ ఆన్", "torch", "torch on", "flash on")) { cam.setTorch(true); refreshButtons(); listenSoon(300); return; }
            if (is(t, "జూమ్ తగ్గించు", "జూమ్ ఆఫ్", "zoom out", "జూమ్ అవుట్")) { unlockLens(true); listenSoon(300); return; }
            if (is(t, "జూమ్ కొంచెం తగ్గించు", "కొంచెం తగ్గించు", "కొంచెం దూరంగా")) { zoomTo(Math.max(1f, totalZoom() / 2f)); listenSoon(300); return; }
            if (is(t, "జూమ్ పెంచు", "జూమ్ కొంచెం పెంచు")) { zoomTo(Math.min(CamView.MAX_TOTAL, totalZoom() * 2f)); listenSoon(300); return; }
            String tz = t.replace("రెండు", "2").replace("మూడు", "3").replace("నాలుగు", "4").replace("ఐదు", "5").replace("పది", "10")
                    .replace("ఇరవై", "20").replace("ముప్పై", "30").replace("యాభై", "50").replace("వంద", "100").replace("times", "x").replace("టైమ్స్", "x");
            java.util.regex.Matcher zm = java.util.regex.Pattern.compile("^(?:జూమ్ |zoom )?(\\d{1,3}) ?(?:x|×|ఎక్స్|రెట్లు)(?: జూమ్| zoom)?(?: చేయి| చెయ్)?$").matcher(tz);
            if (zm.matches()) { zoomTo(Math.max(1f, Math.min(CamView.MAX_TOTAL, Integer.parseInt(zm.group(1))))); listenSoon(300); return; } // "30x", "జూమ్ 10 రెట్లు"
            if (is(t, "జూమ్", "జూమ్ చేయి", "zoom", "zoom in", "దగ్గరగా", "ఇంకా జూమ్", "ఇంకా జూమ్ చేయి", "మరింత జూమ్", "ఇంకా దగ్గరగా",
                    "దీన్ని జూమ్ చేయి", "దాన్ని జూమ్ చేయి", "ఇది జూమ్ చేయి", "ఇక్కడ జూమ్ చేయి")) { // locked: in on it; else twice as near
                if (lens.locked()) zoomToLock(false); else zoomTo(Math.min(CamView.MAX_TOTAL, totalZoom() * 2f));
                listenSoon(300);
                return;
            }
            if (is(t, "అన్నీ స్కాన్ చేయి", "అన్నీ స్కాన్ చెయ్", "scan all", "scan everything")
                    || mode.equals("auto") && is(t, "స్కాన్", "స్కాన్ చేయి", "స్కాన్ చెయ్", "scan")) { fullScan(); return; } // (in a mode, "స్కాన్ చేయి" is the AI's look, as before)
            if (lens.locked() && t.matches(".*(వాచ్.{0,2}కి|watch.{0,3}(కి|ki|to)) పంపు.*|send (it )?to (the |my )?watch")) { sendLockToWatch(true); listenSoon(300); return; }
            if (is(t, "స్కాన్ ఆపు", "రింగ్స్ ఆపు", "రింగ్స్ ఆఫ్", "లైవ్ రింగ్స్ ఆఫ్")) { setLive(false); listenSoon(300); return; }
            if (is(t, "రింగ్స్", "రింగ్స్ ఆన్", "లైవ్ రింగ్స్", "లైవ్ రింగ్స్ ఆన్")) { setLive(true); listenSoon(300); return; }
            if (is(t, "రాత్రి చూపు", "నైట్ మోడ్", "నైట్ విజన్", "night mode", "night vision")) { toggleNight(); listenSoon(300); return; }
            if (is(t, "ఏమి మారింది", "ఏం మారింది", "ఏమైనా మారిందా", "what changed")) { whatChanged(); return; }
            if (is(t, "ఇక్కడ ఏం జరిగింది", "ఇక్కడ ఏమి జరిగింది", "ఏం జరిగింది", "ఏమి జరిగింది", "what happened here", "what happened")) { shotsStart("recon"); return; }
            if (is(t, "గది విశ్లేషణ", "గది చూడు", "గది కొలతలు", "ఈ గది ఎలా ఉంది", "room analysis", "room check")) { shotsStart("room"); return; }
            if (is(t, "3d మోడల్", "త్రీడీ మోడల్", "గది 3d", "మొత్తం 3d", "చుట్టూ స్కాన్", "3d model", "space scan", "మోడల్ చేయి")) { shotsStart("space"); return; }
            if (is(t, "ఇది సురక్షితమేనా", "ఇది సేఫా", "సురక్షితమేనా", "ప్రమాదమా", "is it safe", "is this safe", "ప్రమాద స్థాయి")) { safetyAsk(); return; }
            if (is(t, "నిర్మాణం చూడు", "పగుళ్లు చూడు", "నిర్మాణ పరీక్ష", "structure check", "పగుళ్లు ఉన్నాయా")) { setMode("struct"); ask(defaultQuestion()); return; }
            if (is(t, "ఎవరో పడిపోయారు", "గాయం అయింది", "ప్రథమ చికిత్స", "గాయపడ్డారు", "first aid", "someone fell", "దెబ్బ తగిలింది")) { firstAid(); return; }
            if (is(t, "ప్రమాద చోట్లు", "ప్రమాద చోట్లు ఏవి", "గుర్తుపెట్టిన ప్రమాదాలు", "hazards", "my hazards")) { hazardList(); return; }
            if (is(t, "సాక్ష్యం కార్డ్", "సాక్ష్యం", "మూడు వైపులు", "వస్తువు మూడు వైపులు", "evidence card", "evidence")) { shotsStart("evidence"); return; }
            if (shots != null && is(t, "తీసుకో", "తీయి", "ఫోటో తీయి", "ఫోటో", "క్లిక్", "take", "capture")) { shotTake(); return; }
            if (shots != null && is(t, "అయింది", "అయిపోయింది", "చాలు", "done", "పూర్తి")) { if (shots.size() >= 2 || "evidence".equals(shotsKind)) shotsDone(); else tell("కనీసం 2 ఫోటోలు కావాలి.", false); return; }
            if (is(t, "కామెంటరీ", "కామెంటరీ చెప్పు", "కామెంటరీ మొదలుపెట్టు", "కామెంటరీ ఆన్", "commentary", "commentary on", "మాట్లాడుతూ ఉండు", "చెబుతూ ఉండు")) { setCommentary(true, false); return; }
            if (is(t, "కామెంటరీ ఆపు", "కామెంటరీ ఆఫ్", "commentary off", "మాట్లాడటం ఆపు", "చెప్పడం ఆపు")) { setCommentary(false, false); listenSoon(300); return; }
            if (is(t, "లోతైన స్కాన్", "లోతైన స్కాన్ చేయి", "డీప్ స్కాన్", "deep scan")) { setSceneLive(true); return; }
            if (is(t, "లోతైన స్కాన్ ఆపు", "డీప్ స్కాన్ ఆపు", "deep scan off")) { setSceneLive(false); listenSoon(300); return; }
            if (is(t, "ఫాలో చేయి", "ఫాలో", "వెంట ఉండు", "follow", "follow him", "follow her")) { followOn = true; tell(lens.locked() ? "📹 ఫాలో-కామ్ ఆన్: లాక్ చేసినది మధ్యలో, ఒకే సైజులో ఉంచుతాను." : "📹 ముందు ఏదైనా లాక్ చేయండి (రింగ్ నొక్కండి).", false); listenSoon(300); return; }
            if (is(t, "ఫాలో ఆపు", "ఫాలో వద్దు", "follow off", "stop following")) { followOn = false; tell("📹 ఫాలో-కామ్ ఆఫ్.", false); listenSoon(300); return; }
            if (commentAsk) { // "కొనసాగించమంటారా?" after 3 minutes of commentary
                if (t.matches(".*(అవును|కొనసాగించు|కంటిన్యూ|ఓకే|ok|yes|continue|సరే).*")) { commentAsk = false; commentSince = System.currentTimeMillis(); say("🎙️ సరే, కొనసాగిస్తాను."); return; }
                if (t.matches(".*(వద్దు|ఆపు|చాలు|no|stop).*")) { setCommentary(false, false); listenSoon(300); return; }
            }
            if (LockBrain.isUnlock(t)) { unlockLens(true); listenSoon(300); return; }
            if (is(t, "హోలోగ్రామ్", "హోలోగ్రామ్ చూపించు", "త్రీడీ", "త్రీడీ చూపించు", "3d", "3d చూపించు", "3డి", "3డి చూపించు", "హోలో")) { hologram(); return; }
            if (is(t, "pdf", "pdf చేయి", "పీడీఎఫ్", "పీడీఎఫ్ చేయి", "పిడిఎఫ్")) { pdf(); return; }
            if (pairKind != null && is(t, "పోల్చు", "పోల్చి చెప్పు", "రెండోది", "ఇప్పుడు చూడు", "కొత్తది")) { pairSecond(); return; }
            if (layersId != null && is(t, "పొర", "లేయర్", "layer", "తర్వాతి పొర", "పొర స్కాన్")) { layerShot(); return; }
            if (layersId != null && is(t, "అయిపోయింది", "పూర్తి", "done")) { layersDone(); return; }
            if (!guideNext.isEmpty() && is(t, "చూడు", "చూపిస్తున్నా", "చూపిస్తున్నాను", "అయింది", "రెడీ", "ఇదిగో", "ఇదిగో చూడు")) { guideStep(); return; }
        }
        boolean flow = pairKind != null || layersId != null || closeId != null || !guideNext.isEmpty() || shots != null;
        if (!flow && Hazards.isMark(text)) { markHazard(text); return; } // "ఇక్కడ గుంత ఉంది"
        if (!flow && !frozen && det != null && liveOn()) { // "ఎంత మంది ఉన్నారు?": the phone's own count, at once
            List<String> kinds = LiveTracker.countAsked(text);
            if (!kinds.isEmpty()) { countNow(kinds); return; }
        }
        if (!flow && !frozen && LockBrain.isLockAsk(text)) { lockAsk(text); return; } // "ఆ ఎర్ర చొక్కా అబ్బాయిని జూమ్ చేయి"
        if (!flow && !frozen && lens.locked() && aboutLocked(text)) { askLocked(text); return; } // a question about the locked one
        ask(text);
    }

    private void sendTyped() {
        String q = typeBox.getText().toString().trim();
        if (q.isEmpty()) return;
        typeBox.setText("");
        try { ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(typeBox.getWindowToken(), 0); } catch (Exception ignored) {}
        heard(q);
    }

    private String defaultQuestion() {
        switch (mode) {
            case "shop": return "ఈ వస్తువు ఏంటి, కంపెనీ, మోడల్, ధర, ఎక్కడ దొరుకుతుంది చెప్పు.";
            case "med": return "ఈ మందు పేరు, దేనికి, ఉపయోగాలు, సైడ్ ఎఫెక్ట్స్, జాగ్రత్తలు A to Z చెప్పు.";
            case "plant": return "ఈ మొక్క / పంట ఏంటి, ఆరోగ్యంగా ఉందా, ఏదైనా వ్యాధి ఉంటే ఏం చేయాలి?";
            case "elec": return "ఈ బోర్డు / పరికరం స్కాన్ చేసి ఏ parts ఉన్నాయి, దేనికి వాడతారో చెప్పు.";
            case "repair": return "ఇందులో ఏం సమస్య ఉంది, ఎలా రిపేర్ చేయాలి?";
            case "vehicle": return "ఇది చూసి చెప్పు: ఏంటి, ఏమైనా సమస్య ఉందా, ఏం చేయాలి?";
            case "doc": return "ఈ కాగితం చదివి ముఖ్యమైనవి చెప్పు; సేవ్ చేయాల్సినవి ఉంటే చెప్పు.";
            case "home": return "ఇందులో సమస్య ఏంటి, ఎలా సరిచేయాలి, ఎంత ఖర్చు అవుతుంది?";
            case "nature": return "ఇది ఏంటి? ప్రమాదమా? దీని గురించి చెప్పు.";
            case "study": return "ఇది స్టెప్ బై స్టెప్ వివరించు.";
            case "inside": return "దీని లోపల ఏ parts ఉంటాయి? పొరలుగా, బయటి నుంచి లోపలికి చెప్పు.";
            case "struct": return "ఈ నిర్మాణం / భాగం చెక్ చేయి: పగుళ్లు, తుప్పు, వంగినవి, లీక్‌లు, వదులైనవి ఎక్కడ? ఒక్కోదానికి ప్రమాద స్థాయి (1 గమనించు, 2 త్వరగా బాగు చేయించు, 3 తీవ్రం), ఏం చేయాలో చెప్పు.";
            case "firstaid": return "ఇక్కడ ఎవరో గాయపడినట్టు ఉన్నారు. కనిపించే దాన్ని బట్టి ఇప్పుడు నేను ఏం చేయాలో ఒక్కో అడుగు చెప్పు; తీవ్రమైతే ముందు 108.";
            default: return "ఇది ఏంటి? దీని గురించి వివరంగా చెప్పు.";
        }
    }

    private void scanTap() {
        if (shots != null) { shotTake(); return; }
        if (closeId != null) { closeShotNow(); return; }
        if (pairKind != null) { pairSecond(); return; }
        if (layersId != null) { layerShot(); return; }
        if (!guideNext.isEmpty()) { guideStep(); return; }
        ask(defaultQuestion());
    }

    // ================================================================ asking

    private void ask(String q) { ask(q, false, null); }

    /**
     * The question goes to the AI with the picture (what the screen shows now, or the frozen one). deep: the detailed
     * scan for the hologram. override: a made picture (two side by side), whose boxes are not drawn on the screen.
     */
    private void ask(String question, boolean deep, Bitmap override) {
        final boolean layer = layerShot; // (a layer of a device being opened: kept with the others)
        layerShot = false;
        final boolean close = closeShot; // (a close-up of a part of a board)
        closeShot = false;
        final boolean keep = !dontKeep;
        dontKeep = false;
        if (busy) { if (deep) deepThenPdf = false; Toast.makeText(this, "ఇంకా చూస్తున్నాను…", Toast.LENGTH_SHORT).show(); return; }
        if (!Net.online(this) && override == null && !deep) { // O30: no internet: English print read on the phone itself (Telugu with the pack)
            Bitmap pic0 = frozen ? stillPic() : cam.frame(1400);
            if (pic0 == null) { say("కెమెరా బొమ్మ ఇంకా రాలేదు. ఒక్క క్షణం ఆగి మళ్ళీ అడగండి."); return; }
            busy = true;
            tell("📖 నెట్ లేదు: ఫోన్‌లోనే రాత చదువుతున్నాను…", false);
            work.execute(() -> {
                String said = OfflineRead.readAloud(this, pic0);
                runOnUiThread(() -> {
                    busy = false;
                    tell("📖 " + said, false);
                    say(said);
                    toWatch(said);
                });
            });
            return;
        }
        if (!p.hasBrain()) { deepThenPdf = false; say("నా మెదడుకి API key లేదు. Jarvis సెట్టింగ్స్‌లో పెట్టండి."); return; }
        final String q = question == null || question.trim().isEmpty() ? defaultQuestion() : question.trim();
        Bitmap pic = override != null ? override : frozen ? stillPic() : cam.frame(deep ? 1800 : 1400);
        if (pic == null) { deepThenPdf = false; say("కెమెరా బొమ్మ ఇంకా రాలేదు. ఒక్క క్షణం ఆగి మళ్ళీ అడగండి."); return; }
        if (override == null && !frozen && pic != photo) {
            if (!layer && !close && !deep && sameScene(pic)) photo = pic; // the same thing, a fresh look: the talk about it goes on
            else newPhoto(pic);
        }
        final boolean onScreen = override == null;
        busy = true;
        voice.cancelListening();
        voice.stopSpeaking();
        speaking = false;
        hud.scanning(true);
        if (onScreen) hud.clear();
        if ("firstaid".equals(mode)) { // the 📞 108 chip stays while Jarvis looks (he presses it; it only opens the dialer)
            List<JSONObject> dial = new ArrayList<>();
            try { dial.add(new JSONObject().put("type", "dial").put("number", "108").put("label", "📞 108 అంబులెన్స్")); } catch (Exception ignored) {}
            setActions(dial);
        } else setActions(null);
        tell("🔍 " + q + "\n\n" + (deep ? "వివరంగా స్కాన్ చేస్తున్నాను… (కొంచెం టైమ్ పడుతుంది)" : "చూస్తున్నాను…"), false);
        refreshButtons();
        final String talk = talkText(), g = guide, m = mode;
        work.execute(() -> {
            String code = null, err = null;
            ScanBrain.Result r = null;
            try {
                code = QrReader.read(pic);
                if (QrPair.is(code)) { // the phone ↔ tablet key never goes to an AI: not its text, not the picture of it
                    code = null;
                    err = "🔒 ఇది ఫోన్ ↔ టాబ్లెట్ లింక్ QR (bot తాళం). దీన్ని AI కి పంపను.";
                } else {
                    String jpeg = jpeg(pic, deep ? 1800 : 1280, 82);
                    String sys = deep ? ScanBrain.deepSystem(p) : ScanBrain.system(p);
                    String prompt = ScanBrain.context(this, m, code, talk, g) + "\nHis question: " + q;
                    String reply = Brain.oneShot(p, sys, prompt, jpeg, p.webSearch(), deep ? 12000 : 3500);
                    r = ScanBrain.parse(reply);
                }
            } catch (Http.ApiError e) {
                err = "AI జవాబు ఇవ్వలేదు: " + Models.explain(p, e);
            } catch (Exception e) {
                err = "చూడలేకపోయాను: నెట్ / సమయం సమస్య. మళ్ళీ అడగండి.";
            }
            final ScanBrain.Result res = r;
            final String er = err, cd = code;
            main.post(() -> {
                busy = false;
                hud.scanning(false);
                refreshButtons();
                if (isFinishing()) return;
                if (er != null || res == null) { deepThenPdf = false; if (open) say(er != null ? er : "జవాబు రాలేదు."); else tell(er, false); return; }
                skipSave = !keep;
                try { answer(q, res, pic, onScreen, deep, layer, close, cd); } finally { skipSave = false; }
            });
        });
    }

    /** The camera still shows what was asked about last (the phone moved only a little): the talk about it goes on. */
    private boolean sameScene(Bitmap pic) {
        if (photo == null || lastId == null || turns.isEmpty() || fromImage) return false;
        Bitmap a = scaleTo(photo, AR_DIM), b = scaleTo(pic, AR_DIM);
        boolean same = false;
        if (a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight()) same = match(gray(a), gray(b), a.getWidth(), a.getHeight(), 0, 0)[2] < 22f;
        if (a != photo) a.recycle();
        if (b != pic) b.recycle();
        return same;
    }

    /** A new picture is being talked about: the talk about the old one ends. */
    private void newPhoto(Bitmap pic) {
        photo = pic;
        shownPic = null;
        lastId = null;
        turns.clear();
        fitContent();
    }

    private String talkText() {
        StringBuilder b = new StringBuilder();
        for (String[] t : turns) b.append(t[0]).append(": ").append(t[1]).append('\n');
        return b.length() > 5000 ? b.substring(b.length() - 5000) : b.toString();
    }

    private void answer(String q, ScanBrain.Result r, Bitmap pic, boolean onScreen, boolean deep, boolean layer, String code) {
        answer(q, r, pic, onScreen, deep, layer, false, code);
    }

    private void answer(String q, ScanBrain.Result r, Bitmap pic, boolean onScreen, boolean deep, boolean layer, boolean close, String code) {
        if (deep && !layer && !close && r.items.isEmpty()) { // a detailed scan that found no parts (or came back cut off): no empty hologram
            deepThenPdf = false;
            say(r.say.isEmpty() || r.say.startsWith("{") ? "వివరమైన స్కాన్ పూర్తిగా రాలేదు. దగ్గరగా, వెలుతురులో చూపించి మళ్ళీ ప్రయత్నించండి." : r.say);
            return;
        }
        last = r;
        toWatch(r.say);
        turns.add(new String[]{"Anil", q});
        turns.add(new String[]{"Jarvis", r.say});
        while (turns.size() > 12) turns.poll();
        if (onScreen) {
            hud.setItems(r.items);
            arReference(pic);
            shownPic = pic;
        }
        // the guided check
        if (!r.next.isEmpty()) {
            if (guideBase.isEmpty()) guideBase = guide.isEmpty() ? (r.title.isEmpty() ? q : r.title) : guide;
            if (!r.steps.isEmpty()) guideSteps = android.text.TextUtils.join(" / ", r.steps);
            guide = guideBase + (guideSteps.isEmpty() ? "" : " | steps: " + guideSteps);
            guideNext = r.next;
            showGuide("👉 " + r.next, "📸 చూడు", this::guideStep);
        } else if (!guide.isEmpty() && layersId == null && pairKind == null && closeId == null) {
            endGuide();
        }
        // what he asked to save by name is saved now, and said with the answer
        pendingAuto.clear();
        collect = new StringBuilder();
        if (open) for (JSONObject a : r.actions) {
            if (a.optBoolean("auto") && a.optString("type").matches("reminder|note|expense|warranty|medicine|item_place")) ScanActions.run(this, a, this);
        }
        String saved = collect.toString().trim();
        collect = null;
        StringBuilder words = new StringBuilder();
        if (r.danger) words.append("⛔ ");
        if (!r.warn.isEmpty()) words.append("⚠️ ").append(r.warn).append("\n\n");
        words.append(r.say);
        if (!r.report.isEmpty()) words.append("\n\n📋 ").append(r.report);
        if (r.estimate) words.append("\n\n(ఇది ఫోటో నుంచి అంచనా)");
        if ("struct".equals(mode) && !r.items.isEmpty()) words.append("\n\n🔴 3 తీవ్రం · 🟠 2 త్వరగా బాగు · 🟡 1 గమనించు (ఫోటో నుంచి అంచనా; ఇంజనీర్ పరిశీలన కాదు)");
        if (!saved.isEmpty()) words.append("\n\n").append(saved);
        if (!open) { tell(words.toString(), false); save(q, r, pic, onScreen, deep, layer, close, false); return; } // he left: kept, not spoken
        say(words.toString());
        if (r.danger) {
            try { ((android.os.Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 220, 120, 220}, -1)); } catch (Exception ignored) {}
        }
        // the buttons
        List<JSONObject> acts = new ArrayList<>(r.actions);
        try {
            if (code != null && !code.isEmpty() && !hasType(acts, "link")) {
                if (code.toLowerCase(Locale.ROOT).matches("^(https?://|www\\.|upi:).*")) acts.add(0, new JSONObject().put("type", "link").put("url", code).put("label", "🔳 QR లింక్"));
            }
            if (!deep && onScreen && (r.hologram || r.items.size() >= 6) && !hasType(acts, "hologram")) acts.add(act("hologram"));
            if (("pcb".equals(r.kind) || "electronics".equals(r.kind)) && !hasType(acts, "pdf")) acts.add(act("pdf"));
            if ("firstaid".equals(mode) && !hasType(acts, "dial")) acts.add(0, new JSONObject().put("type", "dial").put("number", "108").put("label", "📞 108 అంబులెన్స్"));
        } catch (Exception ignored) {}
        setActions(acts);
        // what he asked for by name that opens something (a shop, the map, the parking spot...): after the answer is spoken
        for (JSONObject a : r.actions) {
            if (a.optBoolean("auto") && !a.optString("type").matches("reminder|note|expense|warranty|medicine|item_place")) pendingAuto.add(a);
        }
        boolean auto3d = !deep && !layer && !close && onScreen && lastId == null && manyParts(r); // (once per picture, not on every follow-up)
        if (auto3d) pendingAuto.add(act("hologram")); // many parts: the hologram opens by itself after the answer
        if (captionBase.trim().isEmpty()) runPendingAuto(); // (else when the answer has been spoken)
        save(q, r, pic, onScreen, deep, layer, close, auto3d);
        if (onScreen && !skipSave) caseChip(); // 📁: this answer's scan onto a case board (after save() has given it its id)
        if (layer) layerGuideIfOn();
    }

    /** The 📁 chip under an answer: the scan goes onto a case board (the opened case's by itself, else he picks). */
    private void caseChip() {
        final String title = last == null || last.title.isEmpty() ? ScanStore.cut(captionBase, 30) : last.title;
        final String brief = last == null ? "" : ScanStore.cut(last.say, 160);
        if (caseId != null && lastId != null) {
            final String sid = lastId, cid = caseId;
            work.execute(() -> { CaseStore.addCard(this, cid, "scan", sid, title, brief); main.post(() -> Toast.makeText(this, "📁 కేస్‌కి కలిపాను", Toast.LENGTH_SHORT).show()); });
        }
        TextView c = chip("📁 కేస్‌కి", false);
        c.setOnClickListener(v -> {
            if (lastId == null) { Toast.makeText(this, "ముందు స్కాన్ సేవ్ అవ్వాలి", Toast.LENGTH_SHORT).show(); return; }
            final String sid = lastId;
            work.execute(() -> main.post(() -> CaseActivity.addTo(this, "scan", sid, title, brief))); // (after it is written)
        });
        actionRow.addView(c);
        actionScroll.setVisibility(View.VISIBLE);
    }

    private final List<JSONObject> pendingAuto = new ArrayList<>();
    private final java.util.Set<String> boards = new java.util.HashSet<>(); // scans kept as boards in this session

    private void runPendingAuto() {
        if (pendingAuto.isEmpty()) return;
        List<JSONObject> l = new ArrayList<>(pendingAuto);
        pendingAuto.clear();
        for (JSONObject a : l) ScanActions.run(this, a, this);
    }

    private static boolean hasType(List<JSONObject> l, String type) {
        for (JSONObject a : l) if (type.equals(a.optString("type"))) return true;
        return false;
    }

    private static JSONObject act(String type) {
        try { return new JSONObject().put("type", type); } catch (Exception e) { return new JSONObject(); }
    }

    private void setActions(List<JSONObject> acts) {
        actionRow.removeAllViews();
        if (acts == null || acts.isEmpty()) { actionScroll.setVisibility(View.GONE); return; }
        for (JSONObject a : acts) {
            TextView c = chip(ScanActions.label(a), "hologram".equals(a.optString("type")) || "checkup".equals(a.optString("type")));
            c.setOnClickListener(v -> ScanActions.run(this, a, this));
            actionRow.addView(c);
        }
        actionScroll.setVisibility(View.VISIBLE);
        actionScroll.scrollTo(0, 0);
    }

    private void showGuide(String text, String button, Runnable r) {
        guideRow.removeAllViews();
        guideBar.setText(text);
        guideRow.addView(guideBar, new LinearLayout.LayoutParams(0, -2, 1));
        if (button != null) {
            TextView b = chip(button, true);
            b.setOnClickListener(v -> r.run());
            guideRow.addView(b);
        }
        TextView x = chip("✕", false);
        x.setOnClickListener(v -> cancelFlows());
        guideRow.addView(x);
        guideRow.setVisibility(View.VISIBLE);
    }

    /** ✕ on the guided bar: the comparing, close-up, layers or guided check stops (what was saved stays). */
    private void cancelFlows() {
        if (busy) { Toast.makeText(this, "ఒక్క క్షణం, ఇంకా చూస్తున్నాను…", Toast.LENGTH_SHORT).show(); return; }
        if (shots != null) { for (Bitmap b : shots) b.recycle(); shots = null; main.removeCallbacks(roomTick); roomStart = 0; }
        pairKind = null;
        pairFirst = null;
        closeId = null;
        closeRegion = null;
        deepThenPdf = false;
        if (layersId != null) { layersDone(); return; }
        endGuide();
        tell("సరే, ఆపాను.", false);
    }

    private void endGuide() {
        guide = "";
        guideNext = "";
        guideBase = "";
        guideSteps = "";
        guideRow.setVisibility(View.GONE);
    }

    /** The guided check's step: he is showing what was asked; Jarvis judges it and says the next. */
    private void guideStep() {
        if (guideNext.isEmpty()) return;
        ask("ఇప్పుడు చూపిస్తున్నాను: " + guideNext + ". ఇది చూసి సరిపోతుందా చెప్పి, తర్వాత ఏం చూపించాలో next లో ఇవ్వు (అయిపోతే next ఖాళీగా, report లో రిపోర్ట్).");
    }

    // ================================================================ keeping it

    /**
     * Every answer is kept with its picture (a follow-up about the same picture adds to the same scan). A layer (an opened
     * device, or a board's back side) is added to its scan's layers; a close-up's parts are put into the board's photo
     * where he marked it; a scan with many parts is kept as a board, for the hologram.
     */
    private void save(String q, ScanBrain.Result r, Bitmap pic, boolean onScreen, boolean deep, boolean layer, boolean close, boolean auto3d) {
        if (skipSave) return;
        if ((close && closeId == null) || (layer && layersId == null)) return; // that flow was stopped (✕) while Jarvis looked
        final boolean first = lastId == null || !onScreen;
        final String id = layer ? layersId : close ? closeId : first ? ScanStore.newId() : lastId;
        if (onScreen && !layer && !close) lastId = id;
        if (deep || auto3d || layer || close) boards.add(id); // (known as a board before it is written)
        if (layer) layerCount++;
        final String device = layersDevice, lname = layerName;
        final float[] region = closeRegion;
        final List<String[]> talk = new ArrayList<>(turns);
        work.execute(() -> {
            try {
                JSONObject data = layer || close || !first ? ScanStore.load(this, id) : null;
                if (data == null) data = new JSONObject().put("id", id);
                JSONObject j = r.json == null ? new JSONObject() : r.json;
                JSONArray found = j.optJSONArray("items") == null ? new JSONArray() : j.optJSONArray("items");
                if (layer) {
                    JSONArray ls = data.optJSONArray("layers");
                    if (ls == null) { // a board seen from the front first: that is its first layer
                        ls = new JSONArray();
                        if (ScanStore.file(this, id, "photo.jpg").exists())
                            ls.put(new JSONObject().put("name", "ముందు వైపు").put("photo", "photo.jpg")
                                    .put("items", data.optJSONArray("items") == null ? new JSONArray() : data.optJSONArray("items")));
                    }
                    int no = ls.length();
                    String name = "layer" + no + ".jpg";
                    String title = lname != null ? lname : r.title.isEmpty() ? "పొర " + (no + 1) : r.title;
                    ls.put(new JSONObject().put("name", title).put("photo", name).put("items", found).put("say", r.say));
                    data.put("layers", ls).put("board", true).put("mode", mode);
                    if (data.optString("kind").isEmpty()) data.put("kind", "inside");
                    if (data.optString("title").isEmpty()) data.put("title", device == null ? "పరికరం" : device);
                    if (data.optString("say").isEmpty()) data.put("say", r.say);
                    if (no == 0) data.put("items", found);
                    ScanStore.save(this, data, pic, name);
                    if (no == 0) ScanStore.save(this, data, pic, "photo.jpg");
                    return;
                }
                if (close && region != null) { // the close-up's parts, placed in the marked part of the board's photo
                    JSONArray layers = data.optJSONArray("layers");
                    JSONObject front = layers != null && layers.length() > 0 ? layers.optJSONObject(0) : null;
                    JSONArray items = front != null ? front.optJSONArray("items") : data.optJSONArray("items");
                    if (items == null) { items = new JSONArray(); if (front != null) front.put("items", items); else data.put("items", items); }
                    if (front != null) data.put("items", items); // (the front side's parts are the board's parts)
                    int max = 0, added = 0;
                    for (int i = 0; i < items.length(); i++) max = Math.max(max, items.optJSONObject(i) == null ? 0 : items.optJSONObject(i).optInt("n"));
                    for (int i = 0; i < found.length(); i++) {
                        JSONObject o = found.optJSONObject(i);
                        float[] b = o == null ? null : ScanBrain.box(o.optJSONArray("box"));
                        if (b == null) continue;
                        float[] m = {region[0] + b[0] * (region[2] - region[0]), region[1] + b[1] * (region[3] - region[1]),
                                region[0] + b[2] * (region[2] - region[0]), region[1] + b[3] * (region[3] - region[1])};
                        if (overlapsAny(items, m)) continue; // already there
                        o.put("box", new JSONArray().put(Math.round(m[0] * 1000)).put(Math.round(m[1] * 1000)).put(Math.round(m[2] * 1000)).put(Math.round(m[3] * 1000)));
                        o.put("n", ++max).put("closeup", true);
                        items.put(o);
                        added++;
                    }
                    data.put("board", true);
                    ScanStore.save(this, data, null, null);
                    final int n = added;
                    main.post(() -> {
                        closeId = null;
                        closeRegion = null;
                        guideRow.setVisibility(View.GONE);
                        if (isFinishing() || !open) return;
                        say("🔍 దగ్గర నుంచి " + n + " కొత్త parts కనిపించాయి, బోర్డులో వాటి చోట్లలో చేర్చాను (చోటు అంచనా).");
                        HoloActivity.open(this, id);
                    });
                    return;
                }
                java.util.Iterator<String> keys = j.keys();
                while (keys.hasNext()) { String k = keys.next(); if (!k.equals("id")) data.put(k, j.get(k)); }
                data.put("say", r.say).put("title", r.title.isEmpty() ? ScanStore.cut(q, 40) : r.title).put("kind", r.kind).put("mode", mode)
                        .put("q", q).put("board", deep || auto3d || data.optBoolean("board"));
                if (!data.has("lat")) { // where it was taken (the phone's last known place; for the map board)
                    try { android.location.Location l = Tools.lastLocation(this); if (l != null && System.currentTimeMillis() - l.getTime() < 20 * 60_000L) data.put("lat", l.getLatitude()).put("lon", l.getLongitude()); } catch (Exception ignored) {}
                }
                if (!onScreen) data.put("noboxes", true);
                JSONArray t = new JSONArray();
                for (String[] x : talk) t.put(new JSONArray().put(x[0]).put(x[1]));
                data.put("talk", t);
                ScanStore.save(this, data, pic, "photo.jpg"); // (a follow-up's boxes go with its own picture)
                if (deep) main.post(() -> {
                    if (isFinishing() || !open) { deepThenPdf = false; return; }
                    if (deepThenPdf) { deepThenPdf = false; pdf(); } else HoloActivity.open(this, id);
                });
            } catch (Exception ignored) {}
        });
    }

    /** This box (0..1) covers half of a box already in the list. */
    private static boolean overlapsAny(JSONArray items, float[] m) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            float[] b = o == null ? null : ScanBrain.box(o.optJSONArray("box"));
            if (b == null) continue;
            float ix = Math.max(0, Math.min(b[2], m[2]) - Math.max(b[0], m[0])), iy = Math.max(0, Math.min(b[3], m[3]) - Math.max(b[1], m[1]));
            float inter = ix * iy, am = (m[2] - m[0]) * (m[3] - m[1]), ab = (b[2] - b[0]) * (b[3] - b[1]);
            if (inter > 0.5f * Math.min(am, ab)) return true;
        }
        return false;
    }

    /** Many parts on a board, an engine, a machine, a diagram or a map: it is kept as a board and the hologram opens after the answer. */
    private static boolean manyParts(ScanBrain.Result r) {
        int boxed = 0;
        for (ScanBrain.Item it : r.items) if (it.box != null) boxed++;
        if ("inside".equals(r.kind) && r.items.size() >= 4) return true;
        return boxed >= 8 && r.kind.matches("pcb|electronics|engine|appliance|diagram|map|inside");
    }

    private void openSaved(String id) {
        JSONObject data = ScanStore.load(this, id);
        if (data == null) { tell("ఆ స్కాన్ దొరకలేదు.", false); return; }
        if (data.optBoolean("board") && data.has("layers")) { HoloActivity.open(this, id); }
        Bitmap bm = ScanStore.photo(this, id, "photo.jpg", 1800);
        if (bm == null) { // (an OBD reading, a note): only its words
            if (frozen) toggleFreeze();
            photo = null;
            lastId = null;
            turns.clear();
            hud.clear();
            String when = new java.text.SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH).format(new java.util.Date(data.optLong("t")));
            tell("📚 " + data.optString("title") + " · " + when + "\n\n" + data.optString("say"), false);
            return;
        }
        if (bm != null) {
            resetStillZoom();
            frozen = true;
            photo = bm;
            shownPic = null;
            still.setScaleType(ImageView.ScaleType.FIT_CENTER);
            still.setImageBitmap(bm);
            still.setVisibility(View.VISIBLE);
            fromImage = true;
            fitContent();
        }
        lastId = id;
        turns.clear();
        JSONArray t = data.optJSONArray("talk");
        for (int i = 0; t != null && i < t.length(); i++) {
            JSONArray x = t.optJSONArray(i);
            if (x != null) turns.add(new String[]{x.optString(0), x.optString(1)});
        }
        ScanBrain.Result r = ScanBrain.parse(data.toString());
        last = r;
        hud.setItems(data.optBoolean("noboxes") ? null : r.items);
        refreshButtons();
        List<JSONObject> acts = new ArrayList<>(r.actions);
        if (data.optBoolean("board")) acts.add(0, act("hologram"));
        setActions(acts);
        String when = new java.text.SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH).format(new java.util.Date(data.optLong("t")));
        tell("📚 " + data.optString("title") + " · " + when + "\n\n" + r.say, false);
    }

    // ================================================================ the picture on the screen

    private void toggleFreeze() {
        if (frozen) {
            frozen = false;
            fromImage = false;
            resetStillZoom();
            still.setVisibility(View.GONE);
            still.setImageDrawable(null);
            hud.clear();
            fitContent();
            refreshButtons();
            return;
        }
        Bitmap bm = cam.frame(1800);
        if (bm == null) return;
        newPhoto(bm);
        frozen = true;
        still.setScaleType(ImageView.ScaleType.FIT_XY);
        still.setImageBitmap(bm);
        still.setVisibility(View.VISIBLE);
        fitContent();
        refreshButtons();
        tell("⏸️ ఈ బొమ్మ ఆపాను. దీని గురించి ఎన్ని ప్రశ్నలైనా అడగండి. ▶️ నొక్కితే మళ్ళీ లైవ్.", false);
    }

    private void showImage(Bitmap bm) {
        resetStillZoom();
        newPhoto(bm);
        frozen = true;
        fromImage = true;
        still.setScaleType(ImageView.ScaleType.FIT_CENTER);
        still.setImageBitmap(bm);
        still.setVisibility(View.VISIBLE);
        hud.clear();
        fitContent();
        refreshButtons();
    }

    /** Where the picture is on the screen, for the boxes: the whole camera area, or the fitted photo. */
    private void fitContent() {
        if (hud == null) return;
        int W = hud.getWidth(), H = hud.getHeight();
        if (W == 0 || H == 0) return;
        if (fromImage && photo != null) {
            float s = Math.min(W / (float) photo.getWidth(), H / (float) photo.getHeight());
            float w = photo.getWidth() * s, h = photo.getHeight() * s;
            hud.setContent(new RectF((W - w) / 2, (H - h) / 2, (W + w) / 2, (H + h) / 2));
        } else {
            hud.setContent(new RectF(0, 0, W, H));
        }
    }

    private void gallery() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_GALLERY);
        } catch (Exception e) {
            Toast.makeText(this, "గ్యాలరీ తెరవలేకపోయాను", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_GALLERY || result != RESULT_OK || data == null || data.getData() == null) return;
        Bitmap bm = decodeUri(data.getData(), 1800);
        if (bm == null) { Toast.makeText(this, "ఫోటో తెరవలేకపోయాను", Toast.LENGTH_SHORT).show(); return; }
        showImage(bm);
        main.postDelayed(() -> ask(defaultQuestion()), 300);
    }

    private Bitmap decodeUri(Uri u, int max) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap bm;
            try (java.io.InputStream in = getContentResolver().openInputStream(u)) { bm = BitmapFactory.decodeStream(in, null, o2); }
            if (bm == null) return null;
            int deg = 0;
            try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
                if (in != null) {
                    int ori = new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
                    deg = ori == android.media.ExifInterface.ORIENTATION_ROTATE_90 ? 90 : ori == android.media.ExifInterface.ORIENTATION_ROTATE_180 ? 180
                            : ori == android.media.ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
                }
            } catch (Exception ignored) {}
            return scaleTo(rotate(bm, deg), max);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decodeFile(String path, int max) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            return scaleTo(BitmapFactory.decodeFile(path, o2), max);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap rotate(Bitmap b, int deg) {
        if (b == null || deg == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(deg);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    static Bitmap scaleTo(Bitmap b, int max) {
        if (b == null) return null;
        int l = Math.max(b.getWidth(), b.getHeight());
        if (l <= max) return b;
        float s = max / (float) l;
        return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * s)), Math.max(1, Math.round(b.getHeight() * s)), true);
    }

    static String jpeg(Bitmap b, int max, int quality) {
        Bitmap s = scaleTo(b, max);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        s.compress(Bitmap.CompressFormat.JPEG, quality, o);
        if (s != b) s.recycle();
        return android.util.Base64.encodeToString(o.toByteArray(), android.util.Base64.NO_WRAP);
    }

    @Override public String photoJpeg() { Bitmap b = shownPic != null ? shownPic : photo; return b == null ? null : jpeg(b, 1400, 85); }

    // ================================================================ the menu

    private void menu() {
        final String[] items = {
                "🧊 3D హోలోగ్రామ్ (వివరమైన స్కాన్)", "📄 తెలుగు PDF (parts, డయాగ్రమ్, తయారీ)", "⚖️ రెండు పోల్చు", "🧰 టెక్నీషియన్ చెక్ (పాత / కొత్త part)",
                "🧅 తెరుస్తూ పొర పొరగా స్కాన్", "🎤 శబ్దం వినిపించు (ఏ part సమస్యో)", "✅ చెకప్ (కార్ / బైక్)", "🔌 OBD స్కానర్ (కార్)",
                "🅿️ బండి ఇక్కడ పెట్టాను", "🔑 ఈ వస్తువు ఇక్కడ పెడుతున్నా", "🔍 భూతద్దం (చిన్న అక్షరాలు)", "📚 స్కాన్ చరిత్ర", "🧊 నా బోర్డులు / తెరిచినవి",
                "⌨️ టైప్ చేసి అడుగు", "📡 AR పేర్లు: " + (arOn ? "ఆన్" : "ఆఫ్"), "📤 షేర్ (బొమ్మ + జవాబు)",
                "🎛️ HUD సెట్టింగ్స్ (రింగ్స్, శబ్దాలు, బోర్డులు, ముఖాలు, మెష్…)", "🕸️ అన్నీ స్కాన్ చేయి",
                "🔭 లోతైన స్కాన్ (AI, 15 సెకన్లకు): " + (sceneLive ? "ఆన్" : "ఆఫ్"), "🎙️ కామెంటరీ: " + (commentaryOn ? "ఆన్" : "ఆఫ్"),
                "🌙 రాత్రి చూపు: " + (cam.night() ? "ఆన్" : "ఆఫ్"), "🔁 ఏమి మారింది?", "📸 HUD తో ఫోటో సేవ్", "📏 జూమ్ టెస్ట్", "⌚ లాక్ చేసినది వాచ్‌కి పంపు",
                "🕵️ ఇక్కడ ఏం జరిగింది? (2–6 ఫోటోలు)", "📦 వస్తువు 3 వైపులు (సాక్ష్యం కార్డ్)", "📁 కేస్ బోర్డులు",
                "🧱 నిర్మాణ పరీక్ష (పగుళ్లు, తుప్పు, లీక్)", "🏠 గది విశ్లేషణ (నెమ్మదిగా తిరగండి)", "🩹 గాయపడినవారికి సహాయం", "⚠️ ఇది సురక్షితమేనా?", "📍 ఇక్కడ ప్రమాదం గుర్తుపెట్టు",
                "🧊 గది / యంత్రం 3D మోడల్ (చుట్టూ తిరగండి)"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setItems(items, (dlg, w) -> {
                    switch (w) {
                        case 0: hologram(); break;
                        case 1: pdf(); break;
                        case 2: pairStart("compare"); break;
                        case 3: pairStart("tech"); break;
                        case 4: layers(null); break;
                        case 5: sound(); break;
                        case 6: checkup(null); break;
                        case 7: obd(); break;
                        case 8: ScanActions.run(this, act("parking"), this); savePlaceShot("🅿️ బండి పెట్టిన చోటు"); break;
                        case 9: ask("నేను ఈ వస్తువుని ఇక్కడ పెడుతున్నాను. ఏ వస్తువో, ఏ చోటో (దగ్గర్లో ఉన్నవాటితో) చూసి item_place action (auto=true) ఇవ్వు."); break;
                        case 10: magnifier(); break;
                        case 11: history(false); break;
                        case 12: history(true); break;
                        case 13: typeRow.setVisibility(typeRow.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); if (typeRow.getVisibility() == View.VISIBLE) typeBox.requestFocus(); break;
                        case 14: arOn = !arOn; if (!arOn) hud.setShift(0, 0, 1f); break;
                        case 15: share(); break;
                        case 16: hudSettings(); break;
                        case 17: fullScan(); break;
                        case 18: setSceneLive(!sceneLive); break;
                        case 19: setCommentary(!commentaryOn, false); break;
                        case 20: toggleNight(); break;
                        case 21: whatChanged(); break;
                        case 22: hudPhoto(); break;
                        case 23: zoomTest(); break;
                        case 24: sendLockToWatch(true); break;
                        case 25: shotsStart("recon"); break;
                        case 26: shotsStart("evidence"); break;
                        case 27: CaseActivity.open(this, caseId); break;
                        case 28: setMode("struct"); ask(defaultQuestion()); break;
                        case 29: shotsStart("room"); break;
                        case 30: firstAid(); break;
                        case 31: safetyAsk(); break;
                        case 33: shotsStart("space"); break;
                        case 32: {
                            EditText e = new EditText(this);
                            e.setHint("ఏమిటి? (గుంత, స్పీడ్ బ్రేకర్, నీళ్లు, వైరు…)");
                            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("📍 ఇక్కడ ప్రమాదం").setView(e)
                                    .setPositiveButton("గుర్తుపెట్టు", (dd, ww) -> markHazard("ఇక్కడ " + e.getText().toString().trim() + " ఉంది")).setNegativeButton("వద్దు", null).show();
                            break;
                        }
                        default: break;
                    }
                }).show();
    }

    /** 🎛️ The HUD's switches in one place (ticks = on). */
    private void hudSettings() {
        final String[][] rows = {
                {"cam_live", "🎯 లైవ్ రింగ్స్ (మనుషులు, బండ్లు, వస్తువులు)", "1"},
                {"cam_talk", "🗣️ చూస్తూ మాట్లాడటం (కొత్తగా ఏదైనా వస్తే)", "1"},
                {"cam_hud_sfx", "🔊 HUD శబ్దాలు", "1"},
                {"cam_signs", "🪧 బోర్డుల రాత (ఫోన్‌లోనే; నంబర్ ప్లేట్లు కాదు)", "1"},
                {"cam_faces", "👤 ముఖాలు (పేర్లు మీరు పరిచయం చేసిన వాళ్ళకే)", "1"},
                {"cam_mesh", "🕸️ మెష్ చుక్కలు (అంచులు, మూలలు)", "1"},
                {"cam_scene", "🔭 స్కాన్‌లో AI పెద్ద వస్తువులు (బిల్డింగ్, చెట్లు…; ఒక AI కాల్)", "1"},
                {"cam_follow", "📹 ఫాలో-కామ్ (లాక్ అయినది మధ్యలో, ఒకే సైజులో)", "1"},
                {"cam_steady", "📐 స్థిర జూమ్ (గైరోతో వణుకు తగ్గింపు)", "1"},
                {"cam_saver", "🔋 పొదుపు మోడ్ (నెమ్మది రింగ్స్, చిన్న AI బొమ్మ; 20% కింద ఆటో)", "0"}};
        String[] names = new String[rows.length];
        boolean[] on = new boolean[rows.length];
        for (int i = 0; i < rows.length; i++) { names[i] = rows[i][1]; on[i] = p.sp.getBoolean(rows[i][0], "1".equals(rows[i][2])); }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("🎛️ HUD సెట్టింగ్స్")
                .setMultiChoiceItems(names, on, (d, i, checked) -> {
                    p.sp.edit().putBoolean(rows[i][0], checked).apply();
                    if ("cam_live".equals(rows[i][0])) setLive(checked);
                    if ("cam_hud_sfx".equals(rows[i][0]) && checked) Sfx.hud(this, p, "lock");
                    if ("cam_steady".equals(rows[i][0]) && !checked) { stabX = stabY = 0; cam.setSteady(0, 0); lens.setShift(0, 0); }
                    if (("cam_signs".equals(rows[i][0]) || "cam_faces".equals(rows[i][0])) && !checked) { signDets = new ArrayList<>(); faceDets = new ArrayList<>(); }
                })
                .setPositiveButton("సరే", null).show();
    }

    /** The parking spot's picture is kept with the scans (the spot itself is saved by its action). */
    private void savePlaceShot(String title) {
        Bitmap bm = frozen ? photo : cam.frame(1400);
        if (bm == null) return;
        work.execute(() -> {
            try {
                ScanStore.save(this, new JSONObject().put("title", title).put("kind", "parking").put("mode", mode)
                        .put("say", title + " · " + new java.text.SimpleDateFormat("d MMM h:mm a", Locale.ENGLISH).format(new java.util.Date())), bm, "photo.jpg");
                java.io.ByteArrayOutputStream jo = new java.io.ByteArrayOutputStream(); // W54: the spot's picture on his watch too
                bm.compress(Bitmap.CompressFormat.JPEG, 85, jo);
                WatchPhoto.send(this, jo.toByteArray(), title);
            } catch (Exception ignored) {}
        });
    }

    private void magnifier() {
        if (frozen) toggleFreeze();
        cam.setZoom(3f);
        if (cam.hasFlash()) cam.setTorch(true);
        refreshButtons();
        tell("🔍 దగ్గరగా చూపించండి… చదువుతాను.", false);
        main.postDelayed(() -> ask("ఈ చిన్న అక్షరాలు స్పష్టంగా, ఉన్నది ఉన్నట్టు చదివి వినిపించు; తర్వాత ముఖ్యమైనది ఒక్క మాటలో."), 1800);
    }

    private void share() {
        if (photo == null) { Toast.makeText(this, "ముందు ఏదైనా స్కాన్ చేయండి", Toast.LENGTH_SHORT).show(); return; }
        try {
            Bitmap base = shownPic != null ? shownPic : photo;
            Bitmap b = last == null ? base : ScanHud.withBoxes(base, last.items, d);
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(PhotoProvider.shareFile(this))) { b.compress(Bitmap.CompressFormat.JPEG, 90, o); }
            Uri u = PhotoProvider.shareUri();
            Intent s = new Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, u)
                    .putExtra(Intent.EXTRA_TEXT, last == null ? "" : (last.title + "\n" + last.say)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            s.setClipData(android.content.ClipData.newRawUri("Jarvis", u));
            startActivity(Intent.createChooser(s, "ఎవరికి పంపాలి?").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception e) {
            Toast.makeText(this, "షేర్ చేయలేకపోయాను", Toast.LENGTH_SHORT).show();
        }
    }

    /** The saved scans (or only the boards / opened devices); tap opens, hold deletes. */
    private void history(boolean boards) {
        final List<JSONObject> all = ScanStore.list(this, boards);
        if (all.isEmpty()) { tell(boards ? "ఇంకా 3D స్కాన్‌లు లేవు. ◉ ని నొక్కి పట్టుకుంటే 3D స్కాన్." : "ఇంకా స్కాన్‌లు లేవు.", false); return; }
        String[] names = new String[all.size()];
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH);
        for (int i = 0; i < all.size(); i++) {
            JSONObject o = all.get(i);
            names[i] = (o.optBoolean("board") ? "🧊 " : "📷 ") + o.optString("title") + "\n   " + f.format(new java.util.Date(o.optLong("t")));
        }
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(boards ? "🧊 నా బోర్డులు (పట్టుకుంటే తీసేయి)" : "📚 స్కాన్ చరిత్ర (పట్టుకుంటే తీసేయి)")
                .setItems(names, (x, w) -> {
                    JSONObject o = all.get(w);
                    if (o.optBoolean("board")) HoloActivity.open(this, o.optString("id"));
                    else openSaved(o.optString("id"));
                })
                .setNegativeButton("మూసేయ్", null).create();
        dlg.setOnShowListener(x -> {
            ListView lv = dlg.getListView();
            if (lv != null) lv.setOnItemLongClickListener((parent, view, pos, id) -> {
                JSONObject o = all.get(pos);
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(o.optString("title"))
                        .setItems(new String[]{"📁 కేస్‌కి కలుపు", "🗑️ తీసేయి"}, (a, b) -> {
                            if (b == 0) { dlg.dismiss(); CaseActivity.addTo(this, "scan", o.optString("id"), o.optString("title"), o.optString("brief")); }
                            else new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setMessage("\"" + o.optString("title") + "\" తీసేయనా?")
                                    .setPositiveButton("తీసేయి", (x2, y2) -> { ScanStore.delete(this, o.optString("id")); dlg.dismiss(); history(boards); })
                                    .setNegativeButton("వద్దు", null).show();
                        }).show();
                return true;
            });
        });
        dlg.show();
    }

    // ================================================================ two pictures

    private void pairStart(String kind) {
        Bitmap a = frozen ? photo : cam.frame(1200);
        if (a == null) { tell("కెమెరా బొమ్మ రాలేదు.", false); return; }
        pairKind = kind;
        pairFirst = a;
        boolean tech = "tech".equals(kind);
        say(tech ? "పాత part చూశాను. ఇప్పుడు కొత్త part చూపించి ◉ నొక్కండి, లేదా 'కొత్తది' అనండి." : "మొదటిది గుర్తుపెట్టుకున్నాను. ఇప్పుడు రెండోది చూపించి ◉ నొక్కండి, లేదా 'పోల్చు' అనండి.");
        showGuide(tech ? "🧰 కొత్త part చూపించండి" : "⚖️ రెండోది చూపించండి", "📸 తీసుకో", this::pairSecond);
        if (frozen) toggleFreeze();
    }

    private void pairSecond() {
        if (pairKind == null || pairFirst == null) return;
        Bitmap b = frozen ? photo : cam.frame(1200);
        if (b == null) return;
        boolean tech = "tech".equals(pairKind);
        Bitmap both = sideBySide(pairFirst, b);
        pairKind = null;
        pairFirst = null;
        guideRow.setVisibility(View.GONE);
        ask(tech ? "ఎడమది పాత part (OLD), కుడిది టెక్నీషియన్ పెట్టిన కొత్త part (NEW). టెక్నీషియన్ చెక్ చేయి: కొత్తది నిజంగా కొత్తదా, అదే మోడల్ / రేటింగా, ఒరిజినల్‌లా ఉందా, ధర ఎంత ఉండాలి?"
                : "ఎడమది FIRST, కుడిది SECOND. రెండూ పోల్చి, తేడాలు, ధరలు, ఏది దేనికి మంచిదో చెప్పు.", false, both);
    }

    private static Bitmap sideBySide(Bitmap x, Bitmap y) {
        int h = Math.min(1100, Math.min(x.getHeight(), y.getHeight()));
        int wx = Math.round(x.getWidth() * h / (float) x.getHeight()), wy = Math.round(y.getWidth() * h / (float) y.getHeight());
        Bitmap out = Bitmap.createBitmap(wx + wy + 12, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        c.drawColor(0xFFFFFFFF);
        Paint f = new Paint(Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(x, null, new Rect(0, 0, wx, h), f);
        c.drawBitmap(y, null, new Rect(wx + 12, 0, wx + 12 + wy, h), f);
        return out;
    }

    // ================================================================ host: the actions that are the camera's own

    @Override public void hologram() {
        if (lastId != null && last != null) {
            if (boards.contains(lastId)) { final String id = lastId; work.execute(() -> main.post(() -> HoloActivity.open(this, id))); return; } // (after it is written)
            JSONObject data = ScanStore.load(this, lastId);
            if (data != null && data.optBoolean("board")) { HoloActivity.open(this, lastId); return; }
        }
        deepScan();
    }

    /** The detailed scan: every part with its job; then the hologram opens. */
    private void deepScan() {
        String q = "ఇది వివరంగా స్కాన్ చేయి: ప్రతి part / భాగం, దాని పని, సెక్షన్లు, కనెక్షన్లు, సమస్యలు.";
        ask(q, true, null);
    }

    @Override public void pdf() {
        if (lastId == null) { deepThenPdf = true; deepScan(); return; }
        JSONObject data = ScanStore.load(this, lastId);
        if (data == null || !data.optBoolean("board")) { deepThenPdf = true; deepScan(); return; }
        if (busy) return;
        busy = true;
        refreshButtons();
        tell("📄 తెలుగు PDF తయారు చేస్తున్నాను… (parts, డయాగ్రమ్, తయారీ విధానం)", false);
        final String id = lastId;
        work.execute(() -> {
            Coder.Made made = null;
            String err = null;
            try { made = ScanPdf.make(this, p, id); }
            catch (Http.ApiError e) { err = "AI జవాబు ఇవ్వలేదు: " + Models.explain(p, e); }
            catch (Exception e) { err = "PDF చేయలేకపోయాను."; }
            final Coder.Made m = made;
            final String er = err;
            main.post(() -> {
                busy = false;
                refreshButtons();
                if (er != null || m == null) { say(er != null ? er : "PDF చేయలేకపోయాను."); return; }
                say("📄 PDF తయారైంది: " + m.where + ". తెరవొచ్చు, WhatsApp కి పంపొచ్చు.");
                List<JSONObject> acts = new ArrayList<>();
                setActions(acts);
                if (m.uri != null) {
                    TextView openB = chip("📄 తెరువు", true), shareB = chip("📤 PDF పంపు", false), diag = chip("🖼️ డయాగ్రమ్ పంపు", false);
                    openB.setOnClickListener(v -> { try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(m.uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); } catch (Exception e) { Toast.makeText(this, "PDF చూపే యాప్ లేదు", Toast.LENGTH_SHORT).show(); } });
                    shareB.setOnClickListener(v -> { try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, m.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "ఎవరికి పంపాలి?")); } catch (Exception ignored) {} });
                    diag.setOnClickListener(v -> ScanPdf.shareDiagram(this, id));
                    actionRow.addView(openB);
                    actionRow.addView(shareB);
                    actionRow.addView(diag);
                    actionScroll.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    @Override public void checkup(String what) {
        guide = "checkup: " + (what == null || what.isEmpty() ? "vehicle" : what);
        ask("నా " + (what == null || what.isEmpty() ? "బండి / కారు" : what) + " చెకప్ మొదలుపెట్టు. మొత్తం స్టెప్స్ steps లో ఇచ్చి, మొదట ఏం చూపించాలో next లో చెప్పు. జాగ్రత్తలు warn లో.");
    }

    @Override public void layers(String device) {
        if (layersId != null) { layersDone(); return; }
        layersId = ScanStore.newId();
        layersDevice = device == null || device.trim().isEmpty() ? (last != null && !last.title.isEmpty() ? last.title : "పరికరం") : device.trim();
        layerCount = 0;
        guide = "opening '" + layersDevice + "' layer by layer: show each layer, list its parts and screws";
        say("🧅 సరే. ముందు ప్లగ్ తీసేయండి. మూసి ఉన్న " + layersDevice + " (మోడల్ స్టిక్కర్‌తో) చూపించి 📸 పొర నొక్కండి. ప్రతి పొర తెరిచాక మళ్ళీ 📸 పొర. స్క్రూలు ఎక్కడివో నేను గుర్తుపెట్టుకుంటాను. అయిపోయాక ✅.");
        layerGuide();
    }

    private void layerGuide() {
        guideRow.removeAllViews();
        guideBar.setText("🧅 " + layersDevice + " · పొర " + (layerCount + 1));
        guideRow.addView(guideBar, new LinearLayout.LayoutParams(0, -2, 1));
        TextView shot = chip("📸 పొర", true), done = chip("✅ అయిపోయింది", false);
        shot.setOnClickListener(v -> layerShot());
        done.setOnClickListener(v -> layersDone());
        guideRow.addView(shot);
        guideRow.addView(done);
        guideRow.setVisibility(View.VISIBLE);
    }

    /** A board's back (solder) side, scanned and kept as its second layer. */
    private void backSide(String id) {
        JSONObject data = ScanStore.load(this, id);
        if (data == null) return;
        if (frozen) toggleFreeze();
        layersId = id;
        layersDevice = data.optString("title", "బోర్డు");
        JSONArray ls = data.optJSONArray("layers");
        layerCount = ls == null ? 1 : ls.length();
        layerName = "వెనక వైపు";
        guide = "the back (solder) side of the board '" + layersDevice + "'";
        say("🔄 బోర్డు తిప్పి వెనక వైపు చూపించి 📸 పొర నొక్కండి. అయిపోయాక ✅.");
        layerGuide();
    }

    /** A part of a board marked on its photo, scanned close: the small parts found go into the board in that place. */
    private void closeUp(String id, float[] region) {
        if (region == null || region.length != 4) return;
        if (frozen) toggleFreeze();
        closeId = id;
        closeRegion = region;
        say("🔍 బోర్డులో మీరు గుర్తు పెట్టిన భాగాన్ని దగ్గరగా, వెలుతురులో, ఫోన్ నేరుగా పట్టుకుని చూపించి 📸 నొక్కండి.");
        showGuide("🔍 ఆ భాగాన్ని దగ్గరగా చూపించండి", "📸 తీసుకో", this::closeShotNow);
    }

    private void closeShotNow() {
        if (closeId == null || busy) return;
        closeShot = true;
        ask("ఇది అదే బోర్డులో ఒక చిన్న భాగం, దగ్గర నుంచి. ఇందులో కనిపించే ప్రతి part (చిన్న SMD కూడా) బాక్సుతో, విలువతో, పనితో ఇవ్వు.", true, null);
    }

    private void layerShot() {
        if (layersId == null || busy) return;
        layerShot = true;
        if (layerName != null) {
            ask("ఇది '" + layersDevice + "' బోర్డు వెనక వైపు (సోల్డర్ వైపు). కనిపించే parts (SMD), సోల్డర్ జాయింట్లు, ట్రేస్‌లు, సమస్యలు (పగిలిన సోల్డర్, కాలిన గుర్తు, తుప్పు) బాక్సులతో ఇవ్వు.", true, null);
            return;
        }
        String q = layerCount == 0
                ? "ఇది '" + layersDevice + "' మూసి ఉన్నప్పుడు (పొర 1). మోడల్ ఉంటే చదివి, తెరవడం సురక్షితమా (never-open list), వారంటీ, తెరవాల్సిన స్క్రూలు (shape screw, box తో) చెప్పు."
                : "ఇది '" + layersDevice + "' పొర " + (layerCount + 1) + " (తెరిచాక కనిపించేది). ఈ పొరలో కనిపించే parts అన్నీ, స్క్రూలు (shape screw) బాక్సులతో ఇవ్వు; ఒక్కో part పని చెప్పు.";
        ask(q, true, null);
    }

    private void layerGuideIfOn() { if (layersId != null) layerGuide(); }

    private void layersDone() {
        if (layersId == null) return;
        if (busy) { Toast.makeText(this, "ఈ పొర ఇంకా చూస్తున్నాను, ఒక్క క్షణం…", Toast.LENGTH_SHORT).show(); return; }
        String id = layersId;
        int n = layerCount;
        layersId = null;
        layerName = null;
        endGuide();
        if (n == 0) { tell("ఒక్క పొర కూడా స్కాన్ కాలేదు.", false); return; }
        say("🧅 " + n + " పొరలు సేవ్ అయ్యాయి. 3D లో ఒక్కో పొర తీసి చూడొచ్చు; మూసేటప్పుడు 🔩 గైడ్ స్క్రూలు ఎక్కడివో చూపిస్తుంది.");
        work.execute(() -> main.post(() -> { if (!isFinishing()) HoloActivity.open(this, id); })); // after the last layer is written
    }

    @Override public void obd() { startActivity(new Intent(this, ObdActivity.class)); }

    /** 🎤: a few seconds of the sound it makes, with the picture, to the AI (only Gemini takes sound; never a silent switch). */
    @Override public void sound() {
        if (!p.isGemini()) {
            say("శబ్దాన్ని నేరుగా వినడం Gemini key తోనే అవుతుంది; మీరు ఎంచుకున్న AI శబ్దం తీసుకోదు. శబ్దం ఎలా ఉందో మాటల్లో చెప్పండి, ఉదాహరణకి 'గిర్ గిర్ మని శబ్దం వస్తోంది'.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (busy) return;
        final Bitmap pic = frozen ? photo : cam.frame(1280);
        if (!frozen && pic != null) newPhoto(pic); // the picture this sound is about
        busy = true;
        voice.cancelListening();
        voice.stopSpeaking();
        speaking = false;
        refreshButtons();
        hud.scanning(true);
        camHold(); // the mic is this recording's alone (the wake word lets go)
        tell("🎤 8 సెకన్లు వింటున్నాను… శబ్దం వచ్చే చోటికి ఫోన్ దగ్గరగా పెట్టండి.", false);
        final String talk = talkText(), m = mode;
        work.execute(() -> {
            String err = null;
            ScanBrain.Result r = null;
            try {
                byte[] wav = SoundClip.record(8000);
                if (wav == null) throw new IllegalStateException("mic");
                main.post(() -> tell("🎧 విన్నాను. ఆలోచిస్తున్నాను…", false));
                String prompt = ScanBrain.context(this, m, null, talk, guide) + "\nHe recorded the sound this thing makes (the attached audio, about 8 seconds). "
                        + "Say what the sound suggests (an estimate): which part may be the problem, how to check it, what to do, and safety. Put the likely part as an item.";
                r = ScanBrain.parse(Brain.oneShotSound(p, ScanBrain.system(p), prompt, pic == null ? null : jpeg(pic, 1280, 80),
                        android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP), 2500));
            } catch (Http.ApiError e) {
                err = "AI జవాబు ఇవ్వలేదు: " + Models.explain(p, e);
            } catch (Exception e) {
                err = "శబ్దం రికార్డ్ చేయలేకపోయాను / పంపలేకపోయాను.";
            }
            final ScanBrain.Result res = r;
            final String er = err;
            main.post(() -> {
                busy = false;
                hud.scanning(false);
                refreshButtons();
                if (er != null || res == null) { say(er != null ? er : "జవాబు రాలేదు."); return; }
                answer("🎤 ఈ శబ్దం విని చెప్పు", res, pic, pic != null && pic == photo, false, false, null);
            });
        });
    }

    @Override public void needLocation() { requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION); }

    private static String cut(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n) + "…" : s; }

    // ================================================================ the live HUD: rings on everything, lock-on, lens zoom, wave, gauges
    // (our own design and sounds; the phone finds things by itself, the AI only when he asks or taps)

    private HoloLens lens;
    private View topPanel, bottomPanel;
    private LinearLayout zoomRow;
    private TextView freezeChip, liveBtn;
    private volatile Detector det;
    private boolean detTried, detBusy, opticalSaid, booted;
    private final LiveTracker tracker = new LiveTracker();
    private final ExecutorService detWork = Executors.newSingleThreadExecutor();
    private final java.util.Set<Integer> seenTracks = new java.util.HashSet<>();
    private long lastMarkAt, lastTalkAt, talkCandAt, lockLostAt, lastSnapAt, batteryAt;
    private String talkCand = "", lastTalk = "";
    private int battery = -1;
    private volatile int trackGen; // (bumped when the picture jumps: a look started before it is dropped)
    private volatile long detMs = 60; // how long the phone's finder takes (a slow phone is asked less often)
    // the lock-on
    private LockBrain.Found lockFound;
    private String lockName = "", lockAsked = "";
    private boolean lockClosed, lockWantsZoom;
    private long lockAt; // (when he last locked on / asked about it)
    private Bitmap lockThumb;
    private android.animation.ValueAnimator zoomAnim;
    // "ఏమి మారింది?": a small picture of the scene now and then, with what the phone counted
    private final ArrayDeque<Object[]> snaps = new ArrayDeque<>();
    // a frozen picture can be zoomed into (past 30×, a steady look)
    private float stillZ = 1f, stillCx = 0.5f, stillCy = 0.5f;
    // direction and tilt
    private android.hardware.SensorManager sensors;
    private float heading = Float.NaN, roll = Float.NaN, pitch = Float.NaN;
    private final float[] rot = new float[9], rot2 = new float[9], ori = new float[3];
    // the other on-device eyes: a quarter of the picture looked at closely (small far things), signboards, faces, the mesh
    private final List<Detector.Det> quadDets = new ArrayList<>();
    private long quadAt;
    private int quadNext, tickNo;
    private volatile List<Detector.Det> signDets = new ArrayList<>(), faceDets = new ArrayList<>();
    private volatile long signAt, faceAt;
    private volatile boolean signBusy, faceBusy;
    // the live board: what just happened
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private int remarkNo;
    // the AI's scene map ("లోతైన స్కాన్": every 15 s while on)
    private boolean sceneLive, sceneBusy;
    private long sceneAt, sceneLiveSince;
    private float sceneHeading = Float.NaN;
    // 🎙️ commentary
    private boolean commentaryOn, commentBusy, commentAsk;
    private long commentAt, commentSince;
    private String commentSig = "";
    private float[] commentBox;
    private final ArrayDeque<String> commentLines = new ArrayDeque<>();
    // a steadier zoomed picture (the gyroscope)
    private android.hardware.Sensor gyro;
    private long gyroAt;
    private float stabX, stabY;
    private boolean followOn; // follow-cam: the camera keeps the locked one in the middle, the same size

    private boolean liveOn() { return p.sp.getBoolean("cam_live", true); }
    private boolean talkOn() { return p.sp.getBoolean("cam_talk", true); }
    private boolean signsOn() { return p.sp.getBoolean("cam_signs", true); }
    private boolean facesOn() { return p.sp.getBoolean("cam_faces", true); }
    private boolean meshOn() { return p.sp.getBoolean("cam_mesh", true); }
    private boolean steadyOn() { return p.sp.getBoolean("cam_steady", true); }
    private boolean sceneMapOn() { return p.sp.getBoolean("cam_scene", true); }
    private boolean followPref() { return p.sp.getBoolean("cam_follow", true); }
    /** The phone is strong enough for the closer look at the quarters (not the tablet, finder under 120 ms). */
    private boolean tilesOn() { return !p.homeMode() && !saver() && detMs < 120 && det != null; }
    private float totalZoom() { return cam.zoom() * cam.digital(); }
    /** 🔋 saver: his switch, or by itself when the battery is 20% or less: the finder looks less often, the AI gets smaller pictures. */
    private boolean saver() { return p.sp.getBoolean("cam_saver", false) || (battery >= 0 && battery <= 20); }
    private int aiDim() { return saver() ? 960 : 1280; }

    /** The lens and the zoom buttons, put on the screen (from build()). */
    private void buildLive(FrameLayout root) {
        lens = new HoloLens(this);
        lens.listener = this::lockStep;
        root.addView(lens, 3, new FrameLayout.LayoutParams(-1, -1)); // (over the picture and the boxes, under the bars)
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (topPanel != null && bottomPanel != null) lens.setSafe(topPanel.getHeight(), bottomPanel.getHeight());
        });
    }

    /** 1× 3× 5× 10× 30× 100× and "❄️ ఆపి చూడు" (above the round buttons). */
    private View zoomButtons() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        zoomRow = new LinearLayout(this);
        zoomRow.setGravity(Gravity.CENTER_VERTICAL);
        zoomRow.setPadding(dp(8), dp(2), dp(8), dp(2));
        for (float z : new float[]{1, 3, 5, 10, 30, 100}) {
            TextView c = chip((z == Math.round(z) ? String.valueOf((int) z) : String.valueOf(z)) + "×", false);
            c.setTextSize(12.5f);
            c.setPadding(dp(11), dp(5), dp(11), dp(5));
            c.setTag(z);
            c.setOnClickListener(v -> zoomTo((Float) v.getTag()));
            zoomRow.addView(c);
        }
        freezeChip = chip("❄️ ఆపి చూడు", true);
        freezeChip.setTextSize(12.5f);
        freezeChip.setOnClickListener(v -> { if (!frozen) toggleFreeze(); });
        freezeChip.setVisibility(View.GONE);
        zoomRow.addView(freezeChip);
        hs.addView(zoomRow);
        return hs;
    }

    private void markZoomChips() {
        if (zoomRow == null) return;
        float z = totalZoom();
        for (int i = 0; i < zoomRow.getChildCount(); i++) {
            View v = zoomRow.getChildAt(i);
            if (!(v.getTag() instanceof Float)) continue;
            float f = (Float) v.getTag();
            boolean lit = Math.abs(Math.log(z / f)) < 0.12;
            v.setBackground(Ui.round(this, lit ? Ui.alpha(Ui.CYAN, 0x55) : 0x40000000, lit ? Ui.CYAN : 0x55FFFFFF, 999));
        }
        if (freezeChip != null) freezeChip.setVisibility(z >= 29f && !frozen ? View.VISIBLE : View.GONE);
    }

    /** The camera started (onResume): the finder loads once, the ticks start, the edges light up the first time. */
    private void liveResume() {
        if (!detTried) {
            detTried = true;
            final Context app = getApplicationContext();
            detWork.execute(() -> {
                Detector dd = Detector.open(app);
                main.post(() -> {
                    if (isDestroyed()) { if (dd != null) dd.close(); return; } // (the camera closed while it loaded)
                    det = dd;
                    if (dd == null) Log.i("JarvisCamera", "no object finder in this build");
                });
            });
        }
        main.postDelayed(detTick, 700);
        main.postDelayed(gaugeTick, 300);
        try {
            sensors = getSystemService(android.hardware.SensorManager.class);
            android.hardware.Sensor s = sensors == null ? null : sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR);
            if (s != null) sensors.registerListener(motion, s, android.hardware.SensorManager.SENSOR_DELAY_UI);
            gyro = sensors == null ? null : sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE);
            gyroAt = 0;
            if (gyro != null) sensors.registerListener(shake, gyro, android.hardware.SensorManager.SENSOR_DELAY_GAME);
        } catch (Exception ignored) {}
        main.postDelayed(commentTick, 2000);
        main.postDelayed(sceneTick, 5000);
        if (!booted) {
            booted = true;
            lens.boot();
            Sfx.hud(this, p, "card");
        }
    }

    private void livePause() {
        if (lens.locked()) unlockLens(false);
        if (commentaryOn) setCommentary(false, true);
        sceneLive = false;
        main.removeCallbacks(detTick);
        main.removeCallbacks(gaugeTick);
        main.removeCallbacks(commentTick);
        main.removeCallbacks(sceneTick);
        if (zoomAnim != null) zoomAnim.cancel();
        try { if (sensors != null) { sensors.unregisterListener(motion); sensors.unregisterListener(shake); } } catch (Exception ignored) {}
        stabX = stabY = 0;
        cam.setSteady(0, 0);
        lens.setShift(0, 0);
    }

    private void liveDestroy() {
        final Detector dd = det;
        det = null;
        detWork.execute(() -> { if (dd != null) dd.close(); });
        detWork.shutdown();
        for (Object[] s : snaps) ((Bitmap) s[1]).recycle();
        snaps.clear();
    }

    private final android.hardware.SensorEventListener motion = new android.hardware.SensorEventListener() {
        @Override public void onSensorChanged(android.hardware.SensorEvent e) {
            try {
                android.hardware.SensorManager.getRotationMatrixFromVector(rot, e.values);
                // the camera looks out of the back: its direction and the turn around it
                android.hardware.SensorManager.remapCoordinateSystem(rot, android.hardware.SensorManager.AXIS_X, android.hardware.SensorManager.AXIS_Z, rot2);
                android.hardware.SensorManager.getOrientation(rot2, ori);
                heading = (float) Math.toDegrees(ori[0]);
                pitch = (float) Math.toDegrees(ori[1]);
                roll = (float) Math.toDegrees(ori[2]);
            } catch (Exception ignored) {}
        }
        @Override public void onAccuracyChanged(android.hardware.Sensor s, int a) {}
    };

    /** The gyroscope: the phone's small shakes are countered on the screen while Jarvis's zoom is in (room only inside the zoom). */
    private final android.hardware.SensorEventListener shake = new android.hardware.SensorEventListener() {
        @Override public void onSensorChanged(android.hardware.SensorEvent e) {
            long t = e.timestamp;
            if (gyroAt == 0) { gyroAt = t; return; }
            float dt = (t - gyroAt) / 1e9f;
            gyroAt = t;
            if (dt <= 0 || dt > 0.1f) return;
            float dg = cam.digital();
            if (!steadyOn() || frozen || dg < 1.25f) { if (stabX != 0 || stabY != 0) { stabX = stabY = 0; cam.setSteady(0, 0); lens.setShift(0, 0); } return; }
            float fov = cam.fovShownDeg();
            if (fov <= 0) return;
            float k = (float) (1 / (2 * Math.tan(Math.toRadians(fov / 2)))); // screen heights per radian
            int W = cam.getWidth(), H = cam.getHeight();
            if (W == 0 || H == 0) return;
            // turning about the phone's Y moves the picture sideways, about X up / down: the picture is nudged the other way,
            // and the nudge eases back so a real pan is not fought
            stabX = (stabX - e.values[1] * dt * k * H / W) * 0.985f;
            stabY = (stabY - e.values[0] * dt * k) * 0.985f;
            cam.setSteady(stabX, stabY);
            float[] st = cam.steady();
            lens.setShift(st[0], st[1]);
        }
        @Override public void onAccuracyChanged(android.hardware.Sensor s, int a) {}
    };

    private final Runnable gaugeTick = new Runnable() {
        @Override public void run() {
            if (!open) return;
            main.postDelayed(this, 250);
            long now = System.currentTimeMillis();
            if (now - batteryAt > 20000) {
                batteryAt = now;
                try {
                    Intent b = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    int lv = b == null ? -1 : b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1), sc = b == null ? 100 : b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
                    battery = lv < 0 || sc <= 0 ? -1 : Math.round(lv * 100f / sc);
                } catch (Exception ignored) {}
            }
            String t = new java.text.SimpleDateFormat("h:mm", Locale.ENGLISH).format(new java.util.Date(now));
            lens.setGauges(frozen && !fromImage ? stillZ * totalZoom() : totalZoom(), cam.opticalMax(), heading, roll, pitch, t, battery);
            if (lens.locked() && now - lockAt > 180_000 && !busy && !speaking && !voice.speaking) unlockLens(false); // (3 minutes untouched: the lock goes)
            markZoomChips();
        }
    };

    /** The phone's own finder, a few times a second: the rings follow, the locked one too, and Jarvis says what changed. */
    private final Runnable detTick = new Runnable() {
        @Override public void run() {
            if (!open) return;
            boolean lockedFar = lens.locked() && cam.digital() > 3f;
            main.postDelayed(this, lockedFar ? 420 : saver() ? 600 : Math.max(160, Math.min(700, detMs * 2)));
            if (det != null && det.broken) { // (honest: the phone's finder doesn't run on this phone)
                final Detector dd = det;
                det = null;
                detWork.execute(dd::close);
                tell("🎯 ఫోన్‌లోని చూసే మోడల్ ఈ ఫోన్‌లో పని చేయలేదు; రింగ్స్ ఆపాను. ◉ నొక్కితే AI తో చూస్తాను.", false);
            }
            boolean live = liveOn() && !frozen && det != null;
            lens.setLive(!frozen);
            if (!live) { if (lens.hasTracks()) lens.setTracks(null, false); return; }
            long wall = System.currentTimeMillis();
            if (wall - lastSnapAt > 45_000 && !busy) snapshot(wall);
            if (detBusy) return;
            if (cam.digital() > 3f && !lens.locked()) { // Jarvis's zoom is far in: the finder would see a tiny blown-up picture (rings off till it comes back)
                if (lens.hasTracks()) { lens.setTracks(null, false); tracker.clear(); }
                return;
            }
            final boolean tiles = tilesOn() && cam.digital() <= 1.5f;
            final Bitmap b = lockedFar ? cam.frame(300) : cam.frameFast(tiles ? 640 : saver() ? 360 : 448); // (zoomed far in: a sharp cut of the zoomed part)
            if (b == null) return;
            detBusy = true;
            tickNo++;
            final Detector dd = det;
            final int gen = trackGen;
            final boolean doQuad = tiles && tickNo % 2 == 1, doMesh = meshOn() && tickNo % 2 == 0 && !lockedFar;
            final boolean doSigns = signsOn() && !signBusy && android.os.SystemClock.uptimeMillis() - signAt > 1500 && !lockedFar;
            final boolean doFaces = facesOn() && !faceBusy && android.os.SystemClock.uptimeMillis() - faceAt > 700;
            final Context app = getApplicationContext();
            final Bitmap forSpots = doSigns || doFaces ? b.copy(Bitmap.Config.ARGB_8888, false) : null;
            if (forSpots != null) {
                if (doSigns) signBusy = true;
                if (doFaces) faceBusy = true;
                light.execute(() -> {
                    try {
                        if (doSigns) { signDets = SceneSpots.signs(forSpots); signAt = android.os.SystemClock.uptimeMillis(); }
                        if (doFaces) { faceDets = SceneSpots.faces(app, forSpots, true); faceAt = android.os.SystemClock.uptimeMillis(); }
                    } catch (Throwable ignored) {
                    } finally {
                        forSpots.recycle();
                        if (doSigns) signBusy = false;
                        if (doFaces) faceBusy = false;
                    }
                });
            }
            detWork.execute(() -> {
                boolean ok = false;
                long now = 0;
                float[] pts = null;
                try {
                    long t0 = android.os.SystemClock.uptimeMillis();
                    List<Detector.Det> found = dd == null ? new ArrayList<>() : dd.detect(b);
                    detMs = (detMs * 3 + (android.os.SystemClock.uptimeMillis() - t0)) / 4;
                    if (doQuad && dd != null) { // one quarter, looked at closely, in turn: small far things
                        List<Detector.Det> q = dd.detectQuarter(b, quadNext);
                        synchronized (quadDets) {
                            long nowq = android.os.SystemClock.uptimeMillis();
                            if (nowq - quadAt > 1300 || quadNext == 0) quadDets.clear();
                            quadDets.addAll(q);
                            quadAt = nowq;
                        }
                        quadNext = (quadNext + 1) % 4;
                    }
                    List<Detector.Det> all = found;
                    synchronized (quadDets) { if (!quadDets.isEmpty() && android.os.SystemClock.uptimeMillis() - quadAt < 1300) all = Detector.merge(found, new ArrayList<>(quadDets)); }
                    Detector.colours(b, all);
                    if (!lockedFar) {
                        List<Detector.Det> sg = signDets, fc = faceDets;
                        if (!sg.isEmpty() && android.os.SystemClock.uptimeMillis() - signAt < 2400) all = Detector.merge(all, sg);
                        if (!fc.isEmpty() && android.os.SystemClock.uptimeMillis() - faceAt < 1500) all = Detector.merge(all, fc);
                    }
                    if (doMesh) pts = SceneSpots.corners(b, 110);
                    now = android.os.SystemClock.uptimeMillis();
                    if (gen == trackGen) { tracker.update(all, now); ok = true; }
                } catch (Throwable t) {
                    Log.w("JarvisCamera", "finder: " + t);
                } finally {
                    b.recycle();
                    final boolean fine = ok;
                    final long at = now;
                    final float[] mesh = pts;
                    main.post(() -> {
                        detBusy = false;
                        if (!open || isFinishing()) return;
                        if (mesh != null && !frozen) lens.setPoints(mesh);
                        if (fine) onTracks(at);
                    });
                }
            });
        }
    };

    private void onTracks(long now) {
        List<LiveTracker.Track> ts = tracker.shown(now);
        for (LiveTracker.Track t : ts) t.distM = cam.distanceM(Detector.Labels.height(t.en), t.h()); // (about how far: from its height on the screen)
        lens.setTracks(ts, busy);
        boolean fresh = false; // a new thing came into view: a small ping (not more than once a second)
        LiveTracker.Track newest = null;
        for (LiveTracker.Track t : ts) if (seenTracks.add(t.id)) { fresh = true; if (!"text".equals(t.group) && !"face".equals(t.group)) { newest = t; event("➕ " + t.side() + " " + t.te); } }
        for (LiveTracker.Track g : tracker.drainGone()) if (!"text".equals(g.group) && !"face".equals(g.group) && seenTracks.contains(g.id)) event("➖ " + g.te + " వెళ్లింది");
        if (seenTracks.size() > 500) seenTracks.clear();
        if (fresh && now - lastMarkAt > 1200 && !speaking) { lastMarkAt = now; Sfx.hud(this, p, "mark"); }
        lens.setBoard(tracker.counts(now), new ArrayList<>(events));
        if (newest != null) lastNew = newest;
        followLock(ts, now);
        narrate(now);
    }

    private LiveTracker.Track lastNew;

    private void event(String s) {
        String t = new java.text.SimpleDateFormat("h:mm", Locale.ENGLISH).format(new java.util.Date());
        events.addFirst(t + " " + s);
        while (events.size() > 6) events.removeLast();
    }

    /** The locked one keeps its ring (and, zoomed in by Jarvis, stays in the middle); the follow-cam keeps it the same size. */
    private void followLock(List<LiveTracker.Track> ts, long now) {
        if (!lens.locked() || (zoomAnim != null && zoomAnim.isRunning())) return;
        for (int i = 0; i < lens.extraCount(); i++) { // the 2nd / 3rd locks follow their rings
            LiveTracker.Track e = null;
            int eid = lens.extraTrack(i);
            if (eid >= 0) for (LiveTracker.Track t : ts) if (t.id == eid) e = t;
            if (e == null) { e = tracker.overlapping(lens.extraBox(i)); if (e != null) lens.setExtraTrack(i, e.id); }
            if (e != null) lens.setExtraBox(i, e.box);
        }
        int id = lens.lockTrack();
        LiveTracker.Track hit = null;
        if (id >= 0) for (LiveTracker.Track t : ts) if (t.id == id) hit = t;
        if (hit == null) {
            float[] b = lens.lockBox();
            hit = tracker.overlapping(b);
            if (hit == null) {
                String g = lockFound == null ? null : "person".equals(lockFound.kind) ? "person" : "vehicle".equals(lockFound.kind) ? "vehicle" : null;
                hit = tracker.nearest((b[0] + b[2]) / 2, (b[1] + b[3]) / 2, g, 0.18f);
            }
            if (hit != null && !"text".equals(hit.group) && !"face".equals(hit.group)) lens.setLockTrack(hit.id); else hit = null;
        }
        if (hit == null) {
            if (lockLostAt == 0) lockLostAt = now;
            else if (now - lockLostAt > 6000 && !busy && !speaking && !voice.speaking) { // gone for a while: the lock goes
                unlockLens(false);
                tell("🎯 అది ఇప్పుడు కనిపించడం లేదు, లాక్ వదిలాను. మళ్ళీ కావాలంటే అడగండి.", false);
            }
            return;
        }
        lockLostAt = 0;
        lens.setLockBox(hit.box);
        float dg = cam.digital(), total = totalZoom();
        if (followOn && followPref() && now - followAt > 120) { // follow-cam: a little nearer / farther each look, so it stays about the same size
            followAt = now;
            float want = "person".equals(hit.group) ? 0.5f : 0.45f;
            float ratio = want / Math.max(0.02f, hit.h());
            float step = (float) Math.pow(ratio, 0.12);                 // gentle
            float nt = Math.max(1f, Math.min(CamView.MAX_TOTAL, total * step));
            if (Math.abs(Math.log(ratio)) > 0.1 && Math.abs(nt - total) > 0.005f) {
                float o = Math.min(nt, cam.opticalMax());
                float[] c = cam.digitalCenter();
                cam.setParts(o, Math.max(1f, nt / o), c[0], c[1]);
                dg = cam.digital();
            }
        }
        if (dg > 1.05f) { // Jarvis's zoom: drift gently so it stays in the middle
            float[] c = cam.digitalCenter();
            float g = followOn ? 0.3f : 0.18f;
            float nx = c[0] + (hit.cx() - 0.5f) / dg * g, ny = c[1] + (hit.cy() - 0.5f) / dg * g;
            cam.setParts(cam.zoom(), dg, nx, ny);
        }
    }

    private long followAt;

    /** What he can see, said when it changes (the phone's own count: no AI), not too often, never over him or an answer. */
    private void narrate(long now) {
        if (!talkOn() || lens.locked() || busy || frozen || speaking || voice.speaking || voice.isPaused() || !guideNext.isEmpty() || pairKind != null
                || closeId != null || layersId != null || commentaryOn) return;
        String s = LiveTracker.sentence(tracker.counts(now));
        if (s.isEmpty() || s.equals(lastTalk)) { talkCand = ""; return; }
        if (!s.equals(talkCand)) { talkCand = s; talkCandAt = now; return; }
        if (now - talkCandAt < 1800 || now - lastTalkAt < 10_000) return;
        lastTalk = s;
        lastTalkAt = now;
        // the new one, with its side, when there is one; else the whole count
        String line = s;
        LiveTracker.Track n = lastNew;
        if (n != null && now - n.born < 4000 && !"text".equals(n.group) && !"face".equals(n.group)) {
            String far = n.far() ? "దూరంగా " : "";
            line = (n.side().equals("మధ్య") ? "ముందు " : n.side() + " వైపు ") + far + "కొత్తగా ఒక " + n.te + ("person".equals(n.group) ? " వచ్చారు" : " వచ్చింది");
        }
        if (++remarkNo % 3 == 1) line = "Anil, " + line;
        lens.setRemark("👁️ " + line);
        if (voice.listening || (wakeMode() ? engaged : listenOn)) return; // (he may be talking or about to: only shown)
        say("👁️ " + line + ".");
        remark = true; // (after it, back to waiting for "Jarvis": no mic opening for a remark)
    }

    /** 🎯: the rings on / off. */
    private void setLive(boolean on) {
        p.sp.edit().putBoolean("cam_live", on).apply();
        if (!on) { lens.setTracks(null, false); tracker.clear(); }
        if (liveBtn != null) liveBtn.setAlpha(on ? 1f : 0.5f);
        if (on && detTried && det == null) tell("🎯 ఈ వెర్షన్‌లో ఫోన్‌లోనే చూసే మోడల్ రాలేదు: ◉ నొక్కితే AI తో చూస్తాను.", false);
        else tell(on ? "🎯 లైవ్ రింగ్స్ ఆన్: కనిపించే ప్రతి మనిషి, బండి, వస్తువు మీద రింగ్. రింగ్ నొక్కితే దాని వివరాలు." : "🎯 లైవ్ రింగ్స్ ఆఫ్.", false);
    }

    /** "స్కాన్ చేయి": the wave crosses the picture, the found things' small pictures come up, and Jarvis says what is there. */
    private void fullScan() {
        if (frozen) toggleFreeze();
        if (det == null) { // (no finder in this build: the AI looks, as before)
            ask("ఇక్కడ కనిపించే అన్నిటినీ (మనుషులు, వాహనాలు, జంతువులు, ముఖ్యమైన వస్తువులు) బాక్సులతో చెప్పు; ఎవరు ఏం చేస్తున్నారో కూడా.");
            return;
        }
        if (!liveOn()) setLive(true);
        lens.sweep();
        Sfx.hud(this, p, "scan");
        tell("🕸️ స్కాన్ చేస్తున్నాను…", false);
        if (sceneMapOn() && Net.online(this) && p.hasBrain()) sceneMap(false); // (the AI names the big things too: buildings, trees, shops…)
        main.postDelayed(() -> {
            if (!open || isFinishing()) return;
            long now = android.os.SystemClock.uptimeMillis();
            List<LiveTracker.Track> ts = tracker.shown(now);
            Bitmap pic = cam.frame(1000);
            List<Bitmap> thumbs = new ArrayList<>();
            List<String> names = new ArrayList<>();
            if (pic != null) {
                for (LiveTracker.Track t : ts) {
                    if (thumbs.size() >= 6) break;
                    if ("text".equals(t.group) || "face".equals(t.group)) continue;
                    Bitmap c = crop(pic, t.box, 140);
                    if (c != null) { thumbs.add(c); names.add(t.te); }
                }
                pic.recycle();
            }
            lens.setThumbs(thumbs, names);
            String s = LiveTracker.sides(ts);
            if (s.isEmpty()) s = LiveTracker.sentence(tracker.counts(now));
            lastTalk = LiveTracker.sentence(tracker.counts(now));
            lastTalkAt = now;
            int boards = 0;
            for (LiveTracker.Track t : ts) if ("text".equals(t.group)) boards++;
            String more = boards > 0 ? " " + boards + " బోర్డుల రాత కూడా చదివాను." : "";
            say(s.isEmpty() ? "🕸️ ఇప్పుడు ప్రత్యేకంగా ఏమీ కనిపించడం లేదు. ఫోన్ కొంచెం తిప్పి మళ్లీ \"స్కాన్ చేయి\" అనండి."
                    : "🕸️ Anil, " + s + "." + more + (sceneBusy ? " మిగతా పెద్దవి AI చూస్తోంది…" : ""));
        }, 2300);
    }

    /**
     * 🔭 The AI's scene map: the big things the phone's finder doesn't know (buildings, trees, roads, shops, a giant wheel…)
     * as boxes that stay put while the phone moves a little. loud: says the one-line summary even over nothing.
     */
    private void sceneMap(boolean loud) {
        if (sceneBusy || frozen || !Net.online(this) || !p.hasBrain()) return;
        Bitmap pic = cam.frame(1200);
        if (pic == null) return;
        sceneBusy = true;
        sceneAt = System.currentTimeMillis();
        sceneHeading = heading;
        final String m = mode;
        work.execute(() -> {
            ScanBrain.Result r = null;
            String err = null;
            try {
                String prompt = ScanBrain.context(this, m, null, "", "") + "\nScene map: list EVERY large thing and place in this picture (buildings, houses, shops, "
                        + "signboards (their names), trees, roads, gates, towers, rides like a giant wheel, water, sky things, vehicles, groups of people as a group) — "
                        + "up to 30 items, each with a tight box and a 1-3 word Telugu name; info = one short Telugu line each. People: only 'మనుషులు' as a group, "
                        + "never who they are. say = one short Telugu sentence of what this place is. No actions.";
                String reply = Brain.oneShot(p, ScanBrain.system(p), prompt, jpeg(pic, 1024, 74), false, 2600);
                r = ScanBrain.parse(reply);
            } catch (Http.ApiError e) {
                err = "AI జవాబు ఇవ్వలేదు: " + Models.explain(p, e);
            } catch (Exception e) {
                err = "చూడలేకపోయాను (నెట్ / సమయం).";
            }
            final ScanBrain.Result res = r;
            final String er = err;
            main.post(() -> {
                sceneBusy = false;
                if (!open || isFinishing() || frozen) return;
                if (er != null || res == null) { if (loud) tell("🔭 " + er, false); return; }
                if (!res.items.isEmpty()) { hud.setItems(res.items); arReference(pic); }
                String line = res.say.isEmpty() ? (res.items.size() + " పెద్ద వస్తువులు గుర్తించాను") : res.say;
                if (!speaking && !voice.speaking && !busy && (loud || !voice.listening)) say("🔭 " + line);
                else tell(captionBase + "\n🔭 " + line, false);
            });
        });
    }

    /** "లోతైన స్కాన్": the scene map again every 15 s while on, when the phone has turned or the old boxes were lost (5 minutes at most). */
    private final Runnable sceneTick = new Runnable() {
        @Override public void run() {
            if (!open) return;
            main.postDelayed(this, 3000);
            if (!sceneLive) return;
            long now = System.currentTimeMillis();
            if (now - sceneLiveSince > 5 * 60_000) { sceneLive = false; tell("🔭 లోతైన స్కాన్ 5 నిమిషాలైంది, ఆపాను (మళ్ళీ కావాలంటే మెనూలో ఆన్ చేయండి).", false); return; }
            if (sceneBusy || busy || now - sceneAt < 15_000) return;
            boolean turned = !Float.isNaN(heading) && !Float.isNaN(sceneHeading) && Math.abs(((heading - sceneHeading) % 360 + 540) % 360 - 180) > 20;
            boolean lost = arLostSince > 0 && now - arLostSince > 1500 || !hud.hasItems();
            if (turned || lost) sceneMap(false);
        }
    };

    private void setSceneLive(boolean on) {
        sceneLive = on;
        if (on) {
            sceneLiveSince = System.currentTimeMillis();
            tell("🔭 లోతైన స్కాన్ ఆన్: ఫోన్ తిప్పినప్పుడల్లా (15 సెకన్లకు ఒకసారి) AI పెద్ద వస్తువులను పేర్లతో గుర్తిస్తుంది. ఒక్కోసారి ఒక AI కాల్ (మీ key ఖర్చు); 5 నిమిషాల తర్వాత ఆగుతుంది.", false);
            sceneMap(true);
        } else tell("🔭 లోతైన స్కాన్ ఆఫ్.", false);
    }

    /** "ఎంత మంది ఉన్నారు?": counted on the phone, at once. */
    private void countNow(List<String> kinds) {
        Sfx.hud(this, p, "count");
        say("🔢 " + LiveTracker.countAnswer(kinds, tracker.counts(android.os.SystemClock.uptimeMillis())));
    }

    // ---- 🎙️ commentary: Jarvis talks about what is happening, like a friend beside him (AI, only when something changed)

    private void setCommentary(boolean on, boolean quiet) {
        commentaryOn = on;
        commentAsk = false;
        if (on) {
            commentSince = System.currentTimeMillis();
            commentAt = 0;
            commentSig = "";
            commentLines.clear();
            if (!quiet) say("🎙️ కామెంటరీ ఆన్. ఏదైనా మారినప్పుడల్లా చెబుతాను; \"Jarvis\" అంటే ఆగుతాను. (ఒక్కో మాటకి ఒక చిన్న AI కాల్.)");
        } else if (!quiet) tell("🎙️ కామెంటరీ ఆఫ్.", false);
    }

    /** What the scene is now, in a few words: when it changes, a new line is worth saying. */
    private String sceneSig() {
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : tracker.counts(android.os.SystemClock.uptimeMillis()).entrySet()) b.append(e.getKey()).append(e.getValue()).append(' ');
        if (!Float.isNaN(heading)) b.append("h").append(Math.round(heading / 25f));
        return b.toString();
    }

    private final Runnable commentTick = new Runnable() {
        @Override public void run() {
            if (!open) return;
            main.postDelayed(this, 2000);
            long now = System.currentTimeMillis();
            final boolean lockOnly = !commentaryOn && lockActionDue(now); // (no commentary: only the locked one's doings, when it moved)
            if (!commentaryOn && !lockOnly || commentBusy || commentAsk || frozen || busy || speaking || voice.speaking) return;
            if (voice.listening && (wakeMode() || now - heardPartialAt < 2500)) return; // (in wake mode a listen means he just called; always-on: not mid-sentence)
            if (commentaryOn && now - commentSince > 180_000) { // three minutes: he decides whether it goes on (cost)
                commentAsk = true;
                say("🎙️ కామెంటరీ 3 నిమిషాలైంది. కొనసాగించమంటారా? అవును అంటే కొనసాగిస్తాను.");
                return;
            }
            if (now - commentAt < 6500) return;
            if (!Net.online(JarvisCamera.this) || !p.hasBrain()) { setCommentary(false, true); tell("🎙️ కామెంటరీకి నెట్ + AI key కావాలి; ఆపాను.", false); return; }
            String sig = sceneSig();
            boolean lockMoved = false;
            if (lens.locked()) {
                float[] b = lens.lockBox();
                lockMoved = commentBox == null || Math.abs(b[0] - commentBox[0]) + Math.abs(b[1] - commentBox[1]) + Math.abs(b[2] - commentBox[2]) + Math.abs(b[3] - commentBox[3]) > 0.16f;
            }
            boolean changed = lockOnly ? lockMoved : (!sig.equals(commentSig) || lockMoved || now - commentAt > 25_000 && lens.locked());
            if (!changed) return;
            Bitmap pic = cam.frame(800);
            if (pic == null) return;
            commentBusy = true;
            commentSig = sig;
            commentBox = lens.locked() ? lens.lockBox() : null;
            final String own = LiveTracker.sides(tracker.shown(android.os.SystemClock.uptimeMillis()));
            final float[] lb = commentBox;
            final String lname = lockName;
            final List<String> prev = new ArrayList<>(commentLines);
            work.execute(() -> {
                String line = null;
                try {
                    String reply = Brain.oneShot(p, LockBrain.commentarySystem(), LockBrain.commentaryPrompt(own, lb, lname, prev), jpeg(pic, 800, 68), false, 90);
                    line = reply == null ? "" : reply.trim().replaceAll("^[\"“”]+|[\"“”]+$", "").trim();
                } catch (Exception ignored) {
                } finally {
                    pic.recycle();
                }
                final String ln = line;
                main.post(() -> {
                    commentBusy = false;
                    commentAt = System.currentTimeMillis();
                    if (!open || isFinishing() || !(commentaryOn || lockOnly && lens.locked()) || ln == null || ln.isEmpty() || ln.toUpperCase(Locale.ROOT).startsWith("NONE")) return;
                    if (busy || speaking || voice.speaking || voice.listening && wakeMode()) return; // (something else is going on now)
                    commentLines.addLast(ln);
                    while (commentLines.size() > 4) commentLines.removeFirst();
                    if (lens.locked()) lens.appendCard(ln);
                    lens.setRemark("🎙️ " + ln);
                    say("🎙️ " + ln);
                    remark = true;
                });
            });
        }
    };

    /** The locked one's doings, said when they change (no commentary needed): a light look every 20 s while his talk switch is on. */
    private boolean lockActionDue(long now) {
        return lens.locked() && !commentaryOn && talkOn() && now - commentAt > 20_000 && Net.online(this) && p.hasBrain();
    }

    // ---- locking on

    /** "ఆ ఎర్ర చొక్కా అబ్బాయిని జూమ్ చేయి, ఏం చేస్తున్నాడు?": the AI finds it, the ring locks on, the lens zooms in, then a close look. */
    private void lockAsk(String asked) {
        if (busy) { Toast.makeText(this, "ఇంకా చూస్తున్నాను…", Toast.LENGTH_SHORT).show(); return; }
        if (!Net.online(this)) { say("ఒకరిని వెతికి చెప్పడానికి నెట్ కావాలి. నెట్ లేకపోయినా రింగ్స్, \"ఎంత మంది ఉన్నారు?\" పనిచేస్తాయి."); return; }
        if (!p.hasBrain()) { say("నా మెదడుకి API key లేదు. Jarvis సెట్టింగ్స్‌లో పెట్టండి."); return; }
        if (frozen) toggleFreeze();
        Bitmap pic = cam.frame(1400);
        if (pic == null) { say("కెమెరా బొమ్మ ఇంకా రాలేదు. ఒక్క క్షణం ఆగి మళ్ళీ అడగండి."); return; }
        lens.unlock();
        // the phone itself first: "ఎర్ర చొక్కా అబ్బాయి" = a person whose shirt is red, "ఎడమ వైపు కారు" = a car on the left
        ColourWords.Want w = ColourWords.parse(asked);
        List<LiveTracker.Track> picks = ColourWords.pick(w, tracker.shown(android.os.SystemClock.uptimeMillis()));
        if (!picks.isEmpty() && (!w.kind.isEmpty() || !w.group.isEmpty() || !w.colour.isEmpty())) {
            LiveTracker.Track t = picks.get(0);
            boolean small = (t.box[2] - t.box[0]) * (t.box[3] - t.box[1]) < 0.10f;
            followOn = true;
            Sfx.hud(this, p, "tick");
            tell("🎯 " + asked + "\n\nదొరికింది, లాక్ చేస్తున్నాను…", false);
            startLock(t.box, t.id, null, pic, asked, small);
            if (!small) lookAtLocked(asked, pic, t.box, t.te + (t.colour.isEmpty() ? "" : ", " + ColourWords.te(t.colour)));
            // (small: the zoom first, then the close look — lockStep does it)
            return;
        }
        busy = true;
        voice.cancelListening();
        voice.stopSpeaking();
        speaking = false;
        hud.clear();
        hud.scanning(true);
        setActions(null);
        Sfx.hud(this, p, "scan");
        tell("🎯 " + asked + "\n\nవెతుకుతున్నాను…", false);
        refreshButtons();
        work.execute(() -> {
            LockBrain.Found f = null;
            String err = null;
            try {
                f = LockBrain.parse(Brain.oneShot(p, LockBrain.system(), LockBrain.findPrompt(asked), jpeg(pic, aiDim(), 82), false, 900));
            } catch (Http.ApiError e) {
                err = "AI జవాబు ఇవ్వలేదు: " + Models.explain(p, e);
            } catch (Exception e) {
                err = "చూడలేకపోయాను: నెట్ / సమయం సమస్య. మళ్ళీ అడగండి.";
            }
            final LockBrain.Found ff = f;
            final String er = err;
            main.post(() -> {
                busy = false;
                hud.scanning(false);
                refreshButtons();
                if (isFinishing() || !open) return;
                if (er != null || ff == null) { say(er != null ? er : "జవాబు రాలేదు."); return; }
                if (!ff.found || ff.box == null) { say(ff.say.isEmpty() ? "మీరు చెప్పింది నాకు కనిపించలేదు. ఫోన్ అటు తిప్పి మళ్ళీ అడగండి." : ff.say); return; }
                LiveTracker.Track tr = tracker.overlapping(ff.box);
                boolean small = (ff.box[2] - ff.box[0]) * (ff.box[3] - ff.box[1]) < 0.10f;
                followOn = true;
                startLock(ff.box, tr == null ? -1 : tr.id, ff, pic, asked, small);
            });
        });
    }

    /** A ring tapped: it locks on, and the AI tells about it (no zoom; "జూమ్" then zooms in). A second tap elsewhere: one more lock. */
    private void lockOnTrack(LiveTracker.Track t) {
        if ("text".equals(t.group)) { say("🪧 " + t.te); return; } // (a board: its words, read out)
        if ("face".equals(t.group)) { // a face: the person's ring is the one to lock
            LiveTracker.Track body = tracker.nearest(t.cx(), t.cy() + t.h(), "person", 0.3f);
            if (body == null) { say(t.te.isEmpty() ? "ఈ ముఖం నాకు పరిచయం లేదు. (పేర్లు మీరు పరిచయం చేసిన వాళ్ళకే.)" : "ఇది " + t.te + "."); return; }
            t = body;
        }
        if (lens.locked() && lens.lockTrack() != t.id) {
            if (lens.addLock(t.box, t.id)) { Sfx.hud(this, p, "tick"); Toast.makeText(this, (lens.extraCount() + 1) + "వ లాక్: " + t.te, Toast.LENGTH_SHORT).show(); }
            else Toast.makeText(this, "ఒకేసారి 3 వరకే. కార్డ్ నొక్కి లాక్ వదలండి.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (busy) { Toast.makeText(this, "ఇంకా చూస్తున్నాను…", Toast.LENGTH_SHORT).show(); return; }
        Bitmap pic = cam.frame(1400);
        if (pic == null) return;
        followOn = false;
        startLock(t.box, t.id, null, pic, t.te, false);
        if (!Net.online(this) || !p.hasBrain()) { // (no net / no key: the phone's own name only)
            LockBrain.Found f = new LockBrain.Found();
            f.found = true;
            f.name = t.te;
            f.say = "ఇది " + t.te + " లాగా ఉంది." + (!Net.online(this) ? " వివరాలకి నెట్ కావాలి." : "");
            lockFound = f;
            if (lockClosed) showLockCard();
            return;
        }
        lookAtLocked("ఇది ఏంటి? వివరాలు చెప్పు.", pic, t.box, t.te);
    }

    private void startLock(float[] box, int trackId, LockBrain.Found f, Bitmap pic, String asked, boolean wantsZoom) {
        lockAt = System.currentTimeMillis();
        lockLostAt = 0;
        lockFound = f;
        lockName = f == null ? "" : f.name;
        lockAsked = asked == null ? "" : asked;
        lockClosed = false;
        lockWantsZoom = wantsZoom;
        lockThumb = crop(pic, box, 160);
        hud.clear();
        lens.startLock(box, trackId);
        if (f == null || wantsZoom) lens.setWaiting(wantsZoom ? "దగ్గరగా చూస్తున్నాను" : "చూస్తున్నాను");
    }

    /** The lock-on's steps: the ring opened (1), closed (2: locked), the card opened (3). */
    private void lockStep(int step) {
        if (step == 2) {
            lockClosed = true;
            Sfx.hud(this, p, "lock");
            if (lockWantsZoom) { lockWantsZoom = false; zoomToLock(true); }
            else if (lockFound != null) showLockCard();
        } else if (step == 1) {
            Sfx.hud(this, p, "tick");
        } else if (step == 3) {
            Sfx.hud(this, p, "card");
        }
    }

    /** The card types what Jarvis found, and he says it; the little picture goes to the watch too. */
    private void showLockCard() {
        LockBrain.Found f = lockFound;
        if (f == null || !lens.locked()) return;
        String words = LockBrain.spoken(f);
        if (!f.name.isEmpty()) lockName = f.name;
        lens.setCard(lockName, words, lockThumb);
        String said = (lockName.isEmpty() ? "" : lockName + ". ") + words;
        say("🎯 " + said);
        boolean watch = fromWatch; // (opened from the watch: the answer and its small picture go there)
        toWatch(said);
        if (watch) sendLockToWatch(false);
    }

    /** "వాచ్‌కి పంపు" (or the menu): the locked one's small picture and its line on his watch. Never by itself. */
    private void sendLockToWatch(boolean asked) {
        final Bitmap th = lockThumb;
        LockBrain.Found f = lockFound;
        if (!lens.locked() || th == null) { if (asked) Toast.makeText(this, "ముందు ఏదైనా లాక్ చేయండి (రింగ్ నొక్కండి)", Toast.LENGTH_SHORT).show(); return; }
        if (!WatchHub.known(this)) { if (asked) Toast.makeText(this, "వాచ్ ఇంకా జత కాలేదు", Toast.LENGTH_SHORT).show(); return; }
        final Context app = getApplicationContext();
        final String cap = "🎯 " + (lockName.isEmpty() ? "" : lockName + ": ") + (f == null ? "" : f.say);
        work.execute(() -> {
            try {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                th.compress(Bitmap.CompressFormat.JPEG, 88, o);
                WatchPhoto.send(app, o.toByteArray(), cap);
            } catch (Exception ignored) {}
        });
        if (asked) Toast.makeText(this, "⌚ వాచ్‌కి పంపాను", Toast.LENGTH_SHORT).show();
    }

    private void switchCam() {
        if (zoomAnim != null) zoomAnim.cancel();
        cam.switchCamera();
        hud.clear();
        unlockLens(false);
        trackGen++;
        tracker.clear();
        lens.setTracks(null, false);
    }

    /** The AI looks at the locked one again (a tap's details, a close-up after the zoom, or his question about it). */
    private void lookAtLocked(String question, Bitmap pic, float[] box, String guess) {
        if (busy) { // (the card isn't left waiting; nothing is said again)
            if (lockFound != null && lockClosed) lens.setCard(lockName, LockBrain.spoken(lockFound), lockThumb);
            else lens.setCard(lockName, "ఇంకో పని జరుగుతోంది; ఒక్క క్షణం ఆగి మళ్ళీ అడగండి.", lockThumb);
            return;
        }
        busy = true;
        lens.setWaiting("చూస్తున్నాను");
        refreshButtons();
        final String prompt = box == null ? LockBrain.closePrompt(lockName, question) : LockBrain.boxPrompt(box, guess) + " His question: " + question;
        work.execute(() -> {
            LockBrain.Found f = null;
            String err = null;
            try {
                f = LockBrain.parse(Brain.oneShot(p, LockBrain.system(), prompt, jpeg(pic, aiDim(), 82), false, 900));
            } catch (Http.ApiError e) {
                err = "AI జవాబు ఇవ్వలేదు: " + Models.explain(p, e);
            } catch (Exception e) {
                err = "చూడలేకపోయాను: నెట్ / సమయం సమస్య.";
            }
            final LockBrain.Found ff = f;
            final String er = err;
            main.post(() -> {
                busy = false;
                refreshButtons();
                if (isFinishing() || !open || !lens.locked()) return;
                if (er != null || ff == null || ff.say.isEmpty() && ff.details.isEmpty()) {
                    lens.setCard(lockName, er != null ? er : "స్పష్టంగా చెప్పలేకపోయాను.", lockThumb);
                    if (er != null) say(er);
                    return;
                }
                if (box == null && ff.box != null) { Bitmap t = crop(pic, ff.box, 160); if (t != null) lockThumb = t; } // (the close-up's picture)
                if (ff.name.isEmpty()) ff.name = lockName;
                lockFound = ff;
                if (lockClosed) showLockCard();
            });
        });
    }

    /** His question while something is locked: about it. */
    private void askLocked(String question) {
        if (busy) { Toast.makeText(this, "ఇంకా చూస్తున్నాను…", Toast.LENGTH_SHORT).show(); return; }
        Bitmap pic = cam.frame(1400);
        if (pic == null) return;
        lockAsked = question;
        lockAt = System.currentTimeMillis();
        lookAtLocked(question, pic, lens.lockBox(), lockName);
    }

    /** His question is about the locked one: it was locked a moment ago, or he says "he / she / it / what is he doing". */
    private boolean aboutLocked(String text) {
        if (System.currentTimeMillis() - lockAt < 45_000) return true;
        String t = text.toLowerCase(Locale.ROOT);
        return lockLostAt == 0 // (its ring is still on it)
                && t.matches(".*(అతను|అతని|ఆమె|ఆవిడ|వాళ్ళు|వాళ్లు|వాడు|వాడి|ఇతను|ఈమె|లాక్ చేసిన|ఏం చేస్తున్నా|ఏమి చేస్తున్నా|\\bhe\\b|\\bshe\\b|\\bthey\\b|that one|this one).*");
    }

    /** "జూమ్" while locked (or after the lock-on): the lens grows and the camera zooms in until it fills about 60%. */
    private void zoomToLock(boolean thenLook) {
        float[] b = lens.lockBox();
        float tx = (b[0] + b[2]) / 2, ty = (b[1] + b[3]) / 2, hw = Math.max(0.01f, (b[2] - b[0]) / 2), hh = Math.max(0.01f, (b[3] - b[1]) / 2);
        float k = 0.62f / Math.max(2 * hw, 2 * hh);
        if (k < 1.25f || totalZoom() >= CamView.MAX_TOTAL - 0.5f) { // already big (or as far as it goes)
            if (thenLook) { Bitmap pic = cam.frame(1400); if (pic != null) lookAtLocked(lockAsked, pic, null, lockName); }
            return;
        }
        followOn = true;
        float[] from = zoomState();
        float[] plan = cam.planZoomTo(tx, ty, hw, hh, k);
        lens.lens(b);
        Sfx.hud(this, p, "whoosh");
        animateZoom(plan, () -> {
            cam.focus();
            lens.setLockTrack(-1); // (found again on the next look)
            if (totalZoom() > cam.opticalMax() + 0.01f) zoomNote();
            if (thenLook) main.postDelayed(() -> {
                if (!open || !lens.locked()) return;
                Bitmap pic = cam.frame(1400);
                if (pic != null) lookAtLocked(lockAsked, pic, null, lockName);
            }, 650); // (the camera finds its focus first)
        }, from);
    }

    /** {camera zoom, Jarvis zoom, cx, cy} now. */
    private float[] zoomState() { float[] c = cam.digitalCenter(); return new float[]{cam.zoom(), cam.digital(), c[0], c[1]}; }

    /** Where a box on the screen goes when the zoom changes from one state to another. */
    static float[] mapBox(float[] box, float[] from, float[] to) {
        float[] out = new float[4];
        for (int i = 0; i < 4; i++) {
            boolean x = i % 2 == 0;
            float c0 = x ? from[2] : from[3], c1 = x ? to[2] : to[3];
            float u = c0 + (box[i] - 0.5f) / from[1];                 // in the camera's own picture, then
            float u2 = 0.5f + (u - 0.5f) * (to[0] / from[0]);         // after the camera's zoom, then
            out[i] = Math.max(0f, Math.min(1f, 0.5f + (u2 - c1) * to[1])); // on the screen after Jarvis's zoom
        }
        return out;
    }

    private void animateZoom(float[] plan, Runnable done, float[] from0) {
        if (zoomAnim != null) zoomAnim.cancel();
        final float[] from = from0 != null ? from0 : zoomState();
        final float[] lock0 = lens.locked() ? lens.lockBox() : null;
        trackGen++;
        tracker.clear(); // (the rings were for the old zoom)
        lens.setTracks(null, false);
        zoomAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        zoomAnim.setDuration(650);
        zoomAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        zoomAnim.addUpdateListener(a -> {
            float k = (float) a.getAnimatedValue();
            float o = (float) Math.exp(Math.log(from[0]) + (Math.log(plan[0]) - Math.log(from[0])) * k);
            float dg = (float) Math.exp(Math.log(from[1]) + (Math.log(plan[1]) - Math.log(from[1])) * k);
            float x = from[2] + (plan[2] - from[2]) * k, y = from[3] + (plan[3] - from[3]) * k;
            cam.setParts(o, dg, x, y);
            if (lock0 != null && lens.locked()) lens.setLockBoxNow(mapBox(lock0, from, zoomState()));
        });
        zoomAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) { if (!cancelled && done != null) done.run(); }
        });
        zoomAnim.start();
    }

    /** 1× … 100× buttons (and "జూమ్ తగ్గించు"): smoothly, around the same spot. */
    private void zoomTo(float z) {
        if (frozen && !fromImage) toggleFreeze();
        float o = Math.min(z, cam.opticalMax());
        float[] c = cam.digitalCenter();
        float[] plan = {o, Math.max(1f, z / o), z / o <= 1.001f ? 0.5f : c[0], z / o <= 1.001f ? 0.5f : c[1]};
        if (z > totalZoom() + 0.01f) Sfx.hud(this, p, "whoosh");
        animateZoom(plan, () -> { if (z > cam.opticalMax() + 0.01f) zoomNote(); }, null);
    }

    /** Past what the phone gives: said once. */
    private void zoomNote() {
        if (opticalSaid) return;
        opticalSaid = true;
        tell("📏 ఈ ఫోన్ ఇతర యాప్‌లకు ఇచ్చే నిజమైన జూమ్ " + fmtZoom(cam.opticalMax()) + " వరకే. ఇక్కడి నుంచి Jarvis జూమ్ (కొంచెం మసకగా). "
                + "ఫోన్ స్థిరంగా పట్టుకోండి; 30× పైన \"❄️ ఆపి చూడు\" నొక్కితే ఆగిన బొమ్మలో ఇంకా దగ్గరగా చూడొచ్చు.", false);
    }

    private static String fmtZoom(float z) { return (z < 10 ? String.format(Locale.US, "%.1f", z) : String.valueOf(Math.round(z))) + "×"; }

    /** "లాక్ వదులు" / ✕ on the card / "జూమ్ ఆపు": the lock goes (and the zoom back to 1× when asked). */
    private void unlockLens(boolean zoomOut) {
        lens.unlock();
        followOn = false;
        lockFound = null;
        lockWantsZoom = false;
        lockClosed = false;
        if (zoomOut && totalZoom() > 1.01f) zoomTo(1f);
    }

    /** A box of the picture as a small picture (with a little room around it). */
    private static Bitmap crop(Bitmap pic, float[] box, int max) {
        if (pic == null || box == null) return null;
        try {
            int W = pic.getWidth(), H = pic.getHeight();
            float mx = (box[2] - box[0]) * 0.12f, my = (box[3] - box[1]) * 0.12f;
            int x0 = Math.max(0, Math.round((box[0] - mx) * W)), y0 = Math.max(0, Math.round((box[1] - my) * H));
            int x1 = Math.min(W, Math.round((box[2] + mx) * W)), y1 = Math.min(H, Math.round((box[3] + my) * H));
            if (x1 - x0 < 4 || y1 - y0 < 4) return null;
            Bitmap c = Bitmap.createBitmap(pic, x0, y0, x1 - x0, y1 - y0);
            Bitmap s = scaleTo(c, max);
            if (s != c) c.recycle();
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- "ఏమి మారింది?"

    private void snapshot(long wall) {
        lastSnapAt = wall;
        Bitmap s = cam.frame(900);
        if (s == null) return;
        snaps.addLast(new Object[]{wall, s, tracker.counts(android.os.SystemClock.uptimeMillis())});
        while (snaps.size() > 5) ((Bitmap) snaps.pollFirst()[1]).recycle();
    }

    @SuppressWarnings("unchecked")
    private void whatChanged() {
        if (frozen) toggleFreeze();
        long now = System.currentTimeMillis();
        Object[] pick = null;
        long best = Long.MAX_VALUE;
        for (Object[] s : snaps) { // the one nearest 2 minutes ago (at least 20 seconds old)
            long age = now - (Long) s[0];
            if (age < 20_000) continue;
            long dd = Math.abs(age - 120_000);
            if (dd < best) { best = dd; pick = s; }
        }
        if (pick == null) { say("🔁 ఇంకా పోల్చడానికి పాత దృశ్యం లేదు. కెమెరా ఒకే చోట ఒక నిమిషం ఉంచి మళ్ళీ అడగండి."); return; }
        Bitmap nowPic = cam.frame(900);
        if (nowPic == null) return;
        long mins = Math.max(1, Math.round((now - (Long) pick[0]) / 60_000.0));
        String diff = diffSentence((java.util.Map<String, Integer>) pick[2], tracker.counts(android.os.SystemClock.uptimeMillis()));
        if (!Net.online(this) || !p.hasBrain()) { say("🔁 " + (diff.isEmpty() ? "ఫోన్ లెక్క ప్రకారం పెద్దగా మారలేదు." : diff)); return; }
        Bitmap two = sideBySide((Bitmap) pick[1], nowPic);
        nowPic.recycle();
        dontKeep = true;
        ask("ఈ బొమ్మలో రెండు భాగాలు, ఒకే చోటు: ఎడమది " + mins + " నిమిషాల క్రితం, కుడిది ఇప్పుడు. ఏమి మారిందో (కొత్తగా వచ్చినవి, వెళ్లిపోయినవి, కదిలినవి) తెలుగులో చిన్నగా చెప్పు."
                + (diff.isEmpty() ? "" : " ఫోన్ లెక్క: " + diff), false, two);
    }

    /** "2 మనుషులు వచ్చారు, 1 బైక్ వెళ్లిపోయింది" from two counts. */
    static String diffSentence(java.util.Map<String, Integer> before, java.util.Map<String, Integer> after) {
        java.util.Set<String> kinds = new java.util.LinkedHashSet<>(after.keySet());
        kinds.addAll(before.keySet());
        StringBuilder b = new StringBuilder();
        for (String k : kinds) {
            int d = after.getOrDefault(k, 0) - before.getOrDefault(k, 0);
            if (d == 0) continue;
            String[] te = Detector.Labels.te(k);
            boolean person = "person".equals(k);
            if (b.length() > 0) b.append(", ");
            int n = Math.abs(d);
            String name = person ? (n == 1 ? "ఒకరు" : n + " మంది") : (n == 1 ? "ఒక " + te[0] : n + " " + te[1]);
            b.append(name).append(d > 0 ? (person ? " వచ్చారు" : n == 1 ? " వచ్చింది" : " వచ్చాయి")
                    : (person ? " వెళ్లిపోయారు" : n == 1 ? " వెళ్లిపోయింది" : " వెళ్లిపోయాయి"));
        }
        return b.length() == 0 ? "" : b + ".";
    }

    // ---- 🕵️ "ఇక్కడ ఏం జరిగింది?" (2–6 photos) and 📦 the evidence card (front, side, top)

    private List<Bitmap> shots;
    private String shotsKind; // "recon" / "evidence"
    private static final String[] EVIDENCE_SIDES = {"ముందు వైపు", "పక్క వైపు", "పై నుంచి"};

    private void shotsStart(String kind) {
        if (busy) { Toast.makeText(this, "ఇంకా చూస్తున్నాను…", Toast.LENGTH_SHORT).show(); return; }
        if (!Net.online(this) || !p.hasBrain()) { say("దీనికి నెట్ + AI key కావాలి."); return; }
        if (frozen) toggleFreeze();
        shots = new ArrayList<>();
        shotsKind = kind;
        if ("evidence".equals(kind)) say("📦 వస్తువును ముందు వైపు నుంచి చూపించి 📸 నొక్కండి. పక్కన నాణెం లేదా ఫోన్ పెడితే కొలతలు బాగా వస్తాయి.");
        else if ("room".equals(kind)) { say("🏠 గది మధ్యలో నిలబడి ఫోన్‌ని నెమ్మదిగా ఒక చుట్టు తిప్పండి; నేనే ఫోటోలు తీసుకుంటాను. అయ్యాక ✅ నొక్కండి."); roomHeading = heading; roomTurned = 0; main.postDelayed(roomTick, 600); }
        else if ("space".equals(kind)) { shotHeads = new ArrayList<>(); say("🧊 గది అయితే మధ్యలో నిలబడి నెమ్మదిగా తిరగండి; బైక్ / యంత్రం అయితే దాని చుట్టూ నడవండి. నేనే ఫోటోలు తీసుకుంటాను (📸 తో కూడా తీయొచ్చు). అయ్యాక ✅."); roomHeading = heading; roomTurned = 0; main.postDelayed(roomTick, 600); }
        else say("🕵️ జరిగిన చోటు / వస్తువు 2 నుంచి 6 ఫోటోలు తీయండి: ఒక్కోటి చూపించి 📸, అయ్యాక ✅.");
        shotsGuide();
    }

    private float roomHeading = Float.NaN, roomTurned;
    private long roomStart;
    private List<String> shotHeads; // the direction each space photo was taken in

    /** The room sweep: a photo every ~40° of turning (up to 9), done after a full turn or 40 s. */
    private final Runnable roomTick = new Runnable() {
        @Override public void run() {
            if (shots == null || !("room".equals(shotsKind) || "space".equals(shotsKind)) || !open) return;
            boolean space = "space".equals(shotsKind);
            if (roomStart == 0) roomStart = System.currentTimeMillis();
            main.postDelayed(this, 300);
            if (shots.isEmpty()) { Bitmap b = cam.frame(space ? 1200 : 1000); if (b != null) { shots.add(b); if (shotHeads != null) shotHeads.add(HoloLens.direction(heading)); roomHeading = heading; shotsGuide(); } return; }
            if (!Float.isNaN(heading) && !Float.isNaN(roomHeading)) {
                float dlt = Math.abs(((heading - roomHeading) % 360 + 540) % 360 - 180);
                if (dlt >= (space ? 45 : 40)) {
                    Bitmap b = cam.frame(space ? 1200 : 1000);
                    if (b != null) { shots.add(b); if (shotHeads != null) shotHeads.add(HoloLens.direction(heading)); Sfx.hud(JarvisCamera.this, p, "mark"); }
                    roomTurned += dlt;
                    roomHeading = heading;
                    shotsGuide();
                }
            }
            if (shots.size() >= (space ? 8 : 9) || roomTurned >= 330 || System.currentTimeMillis() - roomStart > (space ? 90_000 : 40_000)) { roomStart = 0; shotsDone(); }
        }
    };

    private void shotsGuide() {
        if (shots == null) return;
        boolean ev = "evidence".equals(shotsKind), room = "room".equals(shotsKind), space = "space".equals(shotsKind);
        String text = ev ? "📦 " + EVIDENCE_SIDES[Math.min(2, shots.size())] + " చూపించండి (" + (shots.size() + 1) + "/3)" : room ? "🏠 నెమ్మదిగా తిరగండి… " + shots.size() + "/9 ఫోటోలు"
                : space ? "🧊 తిరగండి / చుట్టూ నడవండి… " + shots.size() + "/8" : "🕵️ ఫోటో " + (shots.size() + 1) + " (2–6)";
        guideRow.removeAllViews();
        guideBar.setText(text);
        guideRow.addView(guideBar, new LinearLayout.LayoutParams(0, -2, 1));
        if (!room) { TextView shot = chip("📸 తీసుకో", !space); shot.setOnClickListener(v -> shotTake()); guideRow.addView(shot); }
        if (!ev && shots.size() >= 2) { TextView done = chip("✅ అయింది", room); done.setOnClickListener(v -> shotsDone()); guideRow.addView(done); }
        TextView x = chip("✕", false);
        x.setOnClickListener(v -> cancelFlows());
        guideRow.addView(x);
        guideRow.setVisibility(View.VISIBLE);
    }

    private void shotTake() {
        if (shots == null) return;
        Bitmap b = cam.frame(1400);
        if (b == null) { Toast.makeText(this, "కెమెరా బొమ్మ రాలేదు", Toast.LENGTH_SHORT).show(); return; }
        shots.add(b);
        if (shotHeads != null) shotHeads.add(HoloLens.direction(heading));
        Sfx.hud(this, p, "mark");
        boolean ev = "evidence".equals(shotsKind);
        if ("space".equals(shotsKind)) { roomHeading = heading; if (shots.size() >= 8) { shotsDone(); return; } shotsGuide(); return; }
        if (ev && shots.size() >= 3 || !ev && shots.size() >= 6) { shotsDone(); return; }
        if (ev) say(EVIDENCE_SIDES[shots.size()] + " చూపించి 📸.");
        else tell("📸 " + shots.size() + " తీశాను. ఇంకోటి, లేదా ✅.", false);
        shotsGuide();
    }

    private void shotsDone() {
        if (shots == null || shots.isEmpty() || busy) return;
        final List<Bitmap> pics = shots;
        final String kind = shotsKind;
        shots = null;
        guideRow.setVisibility(View.GONE);
        busy = true;
        hud.scanning(true);
        refreshButtons();
        tell("evidence".equals(kind) ? "📦 కార్డ్ తయారు చేస్తున్నాను…" : "room".equals(kind) ? "🏠 గదిని చూస్తున్నాను…" : "space".equals(kind) ? "🧊 3D మోడల్ తయారు చేస్తున్నాను… (కొన్ని సెకన్లు)" : "🕵️ ఏం జరిగిందో చూస్తున్నాను… (కొన్ని సెకన్లు)", false);
        main.removeCallbacks(roomTick);
        roomStart = 0;
        final String[] heads = shotHeads == null ? null : shotHeads.toArray(new String[0]);
        shotHeads = null;
        final String cid = caseId;
        work.execute(() -> {
            String err = null, said = null;
            Runnable after = null;
            try {
                if ("evidence".equals(kind)) {
                    Bitmap three = Recon.threeViews(pics);
                    String reply = Brain.oneShot(p, Recon.evidenceSystem(), Recon.evidencePrompt(null), jpeg(three, 1700, 80), false, 1200);
                    JSONObject e = Recon.parseEvidence(reply);
                    if (e == null) throw new IllegalStateException("no card");
                    String text = Recon.evidenceText(e);
                    JSONObject data = new JSONObject().put("title", "📦 " + e.optString("name")).put("kind", "evidence").put("mode", mode).put("say", text).put("evidence", e).put("noboxes", true);
                    ScanStore.save(this, data, three, "photo.jpg");
                    final String sid = data.optString("id");
                    three.recycle();
                    said = "📦 " + e.optString("name") + ". " + text;
                    after = () -> { if (cid != null) { CaseStore.addCard(this, cid, "evidence", sid, "📦 " + e.optString("name"), ScanStore.cut(text, 160)); Toast.makeText(this, "📁 కేస్‌కి కలిపాను", Toast.LENGTH_SHORT).show(); } else CaseActivity.addTo(this, "evidence", sid, "📦 " + e.optString("name"), ScanStore.cut(text, 160)); };
                } else if ("space".equals(kind)) {
                    Bitmap grid = Recon.grid(pics);
                    String reply = Brain.oneShot(p, Recon.spaceSystem(), Recon.spacePrompt(pics.size(), null), jpeg(grid, 1800, 78), false, 6000);
                    grid.recycle();
                    JSONArray layers = Recon.parseSpace(reply, pics.size(), heads);
                    if (layers.length() < 2) throw new IllegalStateException("no views");
                    JSONObject meta = layers.getJSONObject(layers.length() - 1);
                    layers.remove(layers.length() - 1);
                    String title = meta.optString("_title").isEmpty() ? "3D మోడల్" : meta.optString("_title");
                    int parts = 0;
                    for (int i = 0; i < layers.length(); i++) parts += layers.getJSONObject(i).optJSONArray("items").length();
                    JSONObject data = new JSONObject().put("title", "🧊 " + title).put("kind", "space").put("mode", mode).put("board", true).put("say", meta.optString("_say"))
                            .put("layers", layers).put("items", layers.getJSONObject(0).optJSONArray("items")).put("estimate", true);
                    for (int i = 0; i < pics.size() && i < layers.length(); i++) ScanStore.save(this, data, pics.get(i), "view" + i + ".jpg");
                    ScanStore.save(this, data, pics.get(0), "photo.jpg");
                    final String sid = data.optString("id");
                    said = "🧊 " + title + ". " + meta.optString("_say") + " " + layers.length() + " వైపులు, " + parts + " భాగాలు గుర్తించాను. 3D లో ⬆️ తో ఒక్కో వైపు తీయొచ్చు, ▶️ తో వివరిస్తాను.";
                    after = () -> { if (cid != null) CaseStore.addCard(this, cid, "scan", sid, "🧊 " + title, ScanStore.cut(meta.optString("_say"), 160)); HoloActivity.open(this, sid); };
                } else if ("room".equals(kind)) {
                    Bitmap grid = Recon.grid(pics);
                    String reply = Brain.oneShot(p, Recon.roomSystem(), Recon.roomPrompt(pics.size()), jpeg(grid, 1600, 78), false, 1600);
                    JSONObject r = Recon.parseRoom(reply);
                    if (r == null) throw new IllegalStateException("no room");
                    String text = Recon.roomText(r);
                    JSONObject data = new JSONObject().put("title", "🏠 " + r.optString("title")).put("kind", "room").put("mode", mode).put("say", text).put("room", r).put("noboxes", true);
                    ScanStore.save(this, data, grid, "photo.jpg");
                    final String sid = data.optString("id");
                    grid.recycle();
                    said = "🏠 " + r.optString("title") + ". " + text + (r.optInt("confidence", -1) >= 0 ? " (ఫోటోల నుంచి అంచనా, సుమారు " + r.optInt("confidence") + "% నమ్మకం.)" : "");
                    after = () -> { if (cid != null) { CaseStore.addCard(this, cid, "scan", sid, "🏠 " + r.optString("title"), ScanStore.cut(text, 160)); } };
                } else {
                    Bitmap grid = Recon.grid(pics);
                    String reply = Brain.oneShot(p, Recon.system(), Recon.prompt(pics.size(), null), jpeg(grid, 1600, 80), false, 2200);
                    JSONObject r = Recon.parse(reply);
                    if (r == null) throw new IllegalStateException("no story");
                    r.put("photos", pics.size()).put("t", System.currentTimeMillis());
                    java.io.File dir = new java.io.File(new java.io.File(getFilesDir(), "cases"), cid == null ? "_camera" : cid);
                    dir.mkdirs();
                    String base = "r" + System.currentTimeMillis();
                    java.io.File jf = new java.io.File(dir, base + ".json");
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(dir, base + ".jpg"))) { grid.compress(Bitmap.CompressFormat.JPEG, 80, o); }
                    grid.recycle();
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(jf)) { o.write(r.toString().getBytes("UTF-8")); }
                    said = Recon.spoken(r);
                    final String path = jf.getPath(), title = "🕵️ " + r.optString("title"), brief = ScanStore.cut(r.optString("story"), 160);
                    after = () -> {
                        if (cid != null) CaseStore.addCard(this, cid, "timeline", path, title, brief);
                        startActivity(new Intent(this, TimelineActivity.class).putExtra(TimelineActivity.EXTRA_FILE, path));
                        if (cid == null) main.postDelayed(() -> CaseActivity.addTo(this, "timeline", path, title, brief), 800);
                    };
                }
            } catch (Http.ApiError e) {
                err = "AI జవాబు ఇవ్వలేదు: " + Models.explain(p, e);
            } catch (Exception e) {
                err = "చూడలేకపోయాను (నెట్ / సమయం). మళ్ళీ ప్రయత్నించండి.";
            } finally {
                for (Bitmap b : pics) b.recycle();
            }
            final String er = err, sd = said;
            final Runnable af = after;
            main.post(() -> {
                busy = false;
                hud.scanning(false);
                refreshButtons();
                if (isFinishing()) return;
                if (er != null) { say(er); return; }
                say(sd);
                if (af != null) af.run();
            });
        });
    }

    // ---- ⚠️ "ఇది సురక్షితమేనా?", 🩹 first aid, 📍 hazards

    private void safetyAsk() {
        ask("ఇది సురక్షితమేనా? ఇక్కడ కనిపించే ప్రమాదాలు (కరెంట్, పడే వస్తువులు, జారే చోటు, లోతు, వాహనాలు, జంతువులు, నిర్మాణం) చూసి say మొదటి మాటగా 🟢 సురక్షితం / 🟠 జాగ్రత్త / 🔴 ప్రమాదం అని చెప్పి, ఎందుకో, ఏం జాగ్రత్త తీసుకోవాలో 2-3 లైన్లలో చెప్పు. ప్రమాదం ఉన్న చోట్లు బాక్సులుగా (fault, risk).");
    }

    private void firstAid() {
        setMode("firstaid");
        say("🩹 చూస్తున్నాను. తీవ్రంగా ఉంటే ముందు 108 కి కాల్ చేయండి.");
        main.postDelayed(() -> ask(defaultQuestion()), 4500); // (after the 108 line has been said; the 📞 108 chip stays on while Jarvis looks)
    }

    /** "ఇక్కడ గుంత ఉంది": the spot (and a picture of it) is kept; Jarvis warns when he comes this way again. */
    private void markHazard(String text) {
        final String what = Hazards.label(text);
        final Bitmap pic = frozen ? stillPic() : cam.frame(1200);
        tell("📍 " + what + " గుర్తుపెడుతున్నాను…", false);
        work.execute(() -> {
            String sid = null;
            try {
                if (pic != null) {
                    JSONObject data = new JSONObject().put("title", "⚠️ " + what).put("kind", "hazard").put("mode", mode).put("say", "ఇక్కడ " + what + " (ప్రమాద చోటు)").put("noboxes", true);
                    ScanStore.save(this, data, pic, "photo.jpg");
                    sid = data.optString("id");
                }
            } catch (Exception ignored) {}
            android.location.Location here = null;
            try { here = Travel.here(this); } catch (Exception ignored) {}
            final android.location.Location h = here;
            final String s = sid;
            main.post(() -> {
                if (h == null) { say("📍 ఈ చోటు ఎక్కడో ఫోన్‌కి తెలియలేదు (లొకేషన్ ఆన్ చేసి మళ్ళీ చెప్పండి). ఫోటో మాత్రం స్కాన్ చరిత్రలో ఉంచాను."); return; }
                JSONObject o = Hazards.add(this, h.getLatitude(), h.getLongitude(), what, s);
                if (o == null) { say("గుర్తుపెట్టలేకపోయాను."); return; }
                Sfx.hud(this, p, "lock");
                say("📍 గుర్తుపెట్టాను: " + what + ". ఈ దారిలో మళ్ళీ వచ్చినప్పుడు 90 మీటర్ల ముందే చెబుతాను" + (Hazards.canWatch(this) ? "." : "; దానికి లొకేషన్ అనుమతి కావాలి."));
            });
        });
    }

    private void hazardList() {
        work.execute(() -> {
            android.location.Location here = null;
            try { here = Travel.here(this); } catch (Exception ignored) {}
            final String t = Hazards.listText(this, here);
            main.post(() -> say("📍 " + t));
        });
    }

    // ---- 🌙, 📸 with the HUD, 📏

    private void toggleNight() {
        cam.setNight(!cam.night());
        tell(cam.night() ? "🌙 రాత్రి చూపు ఆన్: కెమెరా ఎక్కువ వెలుతురు తీసుకుంటుంది (కదిలితే కొంచెం మసకగా). ఆపాలంటే మళ్ళీ \"రాత్రి చూపు\"."
                : "🌙 రాత్రి చూపు ఆఫ్.", false);
    }

    /** The picture with the rings, boxes and card on it, saved to the phone (his tap only). */
    private void hudPhoto() {
        Bitmap base = frozen ? stillPic() : cam.frame(1800);
        if (base == null) return;
        try {
            Bitmap out = base.copy(Bitmap.Config.ARGB_8888, true);
            Canvas c = new Canvas(out);
            if (hud.getWidth() > 0) {
                c.save();
                c.scale(out.getWidth() / (float) hud.getWidth(), out.getHeight() / (float) hud.getHeight());
                hud.draw(c);
                c.restore();
            }
            lens.drawInto(c, out.getWidth(), out.getHeight());
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.JPEG, 92, o);
            out.recycle();
            final byte[] jpg = o.toByteArray();
            final Context app = getApplicationContext();
            work.execute(() -> {
                String r;
                try {
                    Coder.Made m = Coder.save(app, "Jarvis/camera", "hud_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(new java.util.Date()) + ".jpg", "image/jpeg", jpg);
                    r = "📸 HUD తో ఫోటో సేవ్ అయింది: " + m.where;
                } catch (Exception e) {
                    r = "📸 సేవ్ కాలేదు: " + e.getMessage();
                }
                final String rr = r;
                main.post(() -> Toast.makeText(app, rr, Toast.LENGTH_LONG).show());
            });
            Sfx.hud(this, p, "card");
        } catch (Exception e) {
            Toast.makeText(this, "ఫోటో తయారు కాలేదు", Toast.LENGTH_SHORT).show();
        }
    }

    private void zoomTest() {
        float fov = cam.fovShownDeg();
        String finder = det == null ? "ఫోన్‌లో చూసే మోడల్ లేదు" : ("lite2".equals(det.kind) ? "పెద్ద మోడల్ (Lite2)" : "చిన్న మోడల్ (Lite0)") + ", ఒక చూపుకి సుమారు " + detMs + " మి.సె" + (tilesOn() ? ", నాలుగు ముక్కల దగ్గరి చూపు ఆన్" : "");
        say("📏 ఈ ఫోన్ Jarvis కి ఇచ్చే నిజమైన జూమ్: " + fmtZoom(cam.opticalMax()) + ". దాని పైన Jarvis జూమ్ " + fmtZoom(CamView.MAX_TOTAL)
                + " వరకు (నిజమైన జూమ్ దాటాక కొంచెం మసకగా). " + (fov > 0 ? "ఇప్పుడు స్క్రీన్ చూపే కోణం సుమారు " + Math.round(fov) + " డిగ్రీలు. " : "")
                + finder + ". Samsung కెమెరా యాప్ 100× లో వాళ్ల సొంత AI ఉంటుంది; అది ఇతర యాప్‌లకు ఇవ్వరు.");
    }

    // ---- a frozen picture, zoomed into

    private void setStillZoom(float z, float fx, float fy) {
        int W = still.getWidth(), H = still.getHeight();
        if (W == 0 || H == 0) return;
        float nz = Math.max(1f, Math.min(8f, z));
        float px = stillCx + (fx / W - 0.5f) / stillZ, py = stillCy + (fy / H - 0.5f) / stillZ; // the photo point under his fingers stays put
        stillZ = nz;
        float half = 0.5f / nz;
        stillCx = Math.max(half, Math.min(1 - half, px - (fx / W - 0.5f) / nz));
        stillCy = Math.max(half, Math.min(1 - half, py - (fy / H - 0.5f) / nz));
        applyStillZoom();
        hud.clear(); // (its boxes were for the picture as it was)
    }

    private void applyStillZoom() {
        int W = still.getWidth(), H = still.getHeight();
        still.setPivotX(0);
        still.setPivotY(0);
        still.setScaleX(stillZ);
        still.setScaleY(stillZ);
        still.setTranslationX(-(stillCx - 0.5f / stillZ) * W * stillZ);
        still.setTranslationY(-(stillCy - 0.5f / stillZ) * H * stillZ);
    }

    private void resetStillZoom() {
        stillZ = 1f;
        stillCx = stillCy = 0.5f;
        applyStillZoom();
    }

    /** The frozen picture as he sees it now (the zoomed part of it). */
    private Bitmap stillPic() {
        if (photo == null || fromImage || stillZ <= 1.01f) return photo;
        try {
            int W = photo.getWidth(), H = photo.getHeight();
            int w = Math.round(W / stillZ), h = Math.round(H / stillZ);
            int x = Math.max(0, Math.min(W - w, Math.round((stillCx - 0.5f / stillZ) * W))), y = Math.max(0, Math.min(H - h, Math.round((stillCy - 0.5f / stillZ) * H)));
            return Bitmap.createBitmap(photo, x, y, w, h);
        } catch (Exception e) {
            return photo;
        }
    }

    // ================================================================ QR / barcode seen by itself

    private final Runnable qrTick = new Runnable() {
        @Override public void run() {
            if (!open) return;
            main.postDelayed(this, 1600);
            if (busy || frozen || speaking || !(mode.equals("auto") || mode.equals("shop") || mode.equals("doc"))) return;
            final Bitmap b = cam.frame(900);
            if (b == null) return;
            light.execute(() -> {
                String code = QrReader.read(b);
                b.recycle();
                if (QrPair.is(code)) { main.post(() -> paired(code)); return; } // (his phone's QR: the tablet joins his bot)
                if (code == null || code.equals(shownCode)) return;
                main.post(() -> codeSeen(code));
            });
        }
    };

    /** His phone's pairing QR was seen: this device joins his bot (never shown, never sent anywhere). */
    private boolean pairedDone;

    private void paired(String code) {
        if (pairedDone || isFinishing()) return;
        pairedDone = true;
        // only the home tablet (or a device not joined to any bot yet) joins by QR: his phone's own bot is never replaced
        if (!p.homeMode() && !Guard.token(this).isEmpty()) {
            android.widget.Toast.makeText(this, "ఈ QR ఇంటి టాబ్లెట్ కోసం.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        String r = QrPair.apply(this, code);
        android.widget.Toast.makeText(this, r, android.widget.Toast.LENGTH_LONG).show();
        main.postDelayed(this::finish, 1800);
    }

    private void codeSeen(String code) {
        shownCode = code;
        boolean link = code.toLowerCase(Locale.ROOT).matches("^(https?://|www\\.|upi:).*");
        TextView c = chip((link ? "🔳 QR: " : "🏷️ బార్‌కోడ్: ") + cut(code.replaceFirst("^https?://", ""), 22), true);
        c.setOnClickListener(v -> {
            if (link) ScanActions.link(this, code, this);
            else ask("ఈ బార్‌కోడ్ " + code + ": ఏ ప్రోడక్ట్? కంపెనీ, ధర, వివరాలు వెబ్‌లో చూసి చెప్పు.");
        });
        actionRow.addView(c, 0);
        actionScroll.setVisibility(View.VISIBLE);
        actionScroll.scrollTo(0, 0);
    }

    // ================================================================ AR: the tags stay on the things while the phone moves a little

    private static final int AR_DIM = 200;
    private int[] arRef;
    private int arW, arH;
    private float arDx, arDy;
    private long arLostSince;
    private boolean arBusy;

    private void arReference(Bitmap pic) {
        arRef = null;
        arDx = arDy = 0;
        arLostSince = 0;
        if (frozen || pic == null) return;
        Bitmap s = scaleTo(pic, AR_DIM);
        arW = s.getWidth();
        arH = s.getHeight();
        arRef = gray(s);
        if (s != pic) s.recycle();
    }

    private static int[] gray(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) { int c = px[i]; px[i] = (((c >> 16) & 0xff) * 3 + ((c >> 8) & 0xff) * 6 + (c & 0xff)) / 10; }
        return px;
    }

    private final Runnable arTick = new Runnable() {
        @Override public void run() {
            if (!open) return;
            main.postDelayed(this, 130);
            if (!arOn || frozen || arRef == null || arBusy || !hud.hasItems() || busy) return;
            final Bitmap b = cam.frame(AR_DIM);
            if (b == null) return;
            if (b.getWidth() != arW || b.getHeight() != arH) { b.recycle(); return; }
            arBusy = true;
            final int[] ref = arRef;
            final float px = arDx, py = arDy;
            light.execute(() -> {
                int[] cur = gray(b);
                b.recycle();
                float[] m = match(ref, cur, arW, arH, Math.round(px), Math.round(py));
                main.post(() -> {
                    arBusy = false;
                    if (ref != arRef) return;
                    float scale = hud.getWidth() / (float) arW;
                    if (m[2] < 22f) { // found again
                        arDx = m[0];
                        arDy = m[1];
                        arLostSince = 0;
                        hud.setShift(arDx * scale, arDy * scale, 1f);
                    } else {
                        if (arLostSince == 0) arLostSince = System.currentTimeMillis();
                        long gone = System.currentTimeMillis() - arLostSince;
                        hud.setShift(arDx * scale, arDy * scale, gone > 1500 ? 0f : 0.35f); // moved too far: the tags fade (ask again there)
                    }
                });
            });
        }
    };

    /** Where the reference picture went in the current one: {dx, dy, mean difference}; searched around the last place. */
    private static float[] match(int[] ref, int[] cur, int w, int h, int cx, int cy) {
        int best = Integer.MAX_VALUE, bx = cx, by = cy, R = 10, step = 3;
        int x0 = w / 5, x1 = w - w / 5, y0 = h / 5, y1 = h - h / 5;
        for (int dy = cy - R; dy <= cy + R; dy++) {
            for (int dx = cx - R; dx <= cx + R; dx++) {
                long sum = 0;
                int n = 0;
                for (int y = y0; y < y1; y += step) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int x = x0; x < x1; x += step) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        sum += Math.abs(ref[y * w + x] - cur[yy * w + xx]);
                        n++;
                    }
                }
                if (n < 50) continue;
                int mean = (int) (sum * 16 / n);
                if (mean < best) { best = mean; bx = dx; by = dy; }
            }
        }
        return new float[]{bx, by, best / 16f};
    }
}
