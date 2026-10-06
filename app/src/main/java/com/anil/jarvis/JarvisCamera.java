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
            EXTRA_BACK_OF = "back_of", EXTRA_ZOOM_OF = "zoom_of", EXTRA_REGION = "region";
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
    private Bitmap photo;               // the picture being talked about (frozen, or the last one asked about)
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
            main.postDelayed(() -> { if (deep) deepScan(); else ask(qq); }, 1600); // the camera has a moment to start and focus
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
        torchBtn = iconBtn("🔦", v -> { cam.setTorch(!cam.torchOn()); refreshButtons(); });
        bar.addView(torchBtn);
        camBtn = iconBtn("🔄", v -> { cam.switchCamera(); hud.clear(); tell("📷 " + cam.which(), false); refreshButtons(); });
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
                    if (frozen) return true;
                    cam.setZoom(cam.zoom() * g.getScaleFactor());
                    return true;
                }
            });
            taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                    ScanBrain.Item it = hud.hit(e.getX(), e.getY());
                    if (it != null) { itemTapped(it); return true; }
                    if (!frozen) { cam.focus(); hud.select(-1); }
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
        listenSoon(900);
        refreshButtons();
    }

    @Override protected void onPause() {
        super.onPause();
        open = false;
        main.removeCallbacks(qrTick);
        main.removeCallbacks(arTick);
        main.removeCallbacks(relisten);
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
    @Override public void onPartial(String text) { partial.setText("🎙️ " + text); }
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

    @Override public void onSpeakDone() {
        speaking = false;
        caption.setText(captionBase);
        refreshButtons();
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
                cam.switchCamera(); refreshButtons(); tell("📷 " + cam.which(), false); listenSoon(600); return;
            }
            if (is(t, "టార్చ్ ఆఫ్", "టార్చ్ ఆపు", "ఫ్లాష్ ఆఫ్", "లైట్ ఆఫ్", "లైట్ ఆపు", "torch off", "flash off")) { cam.setTorch(false); refreshButtons(); listenSoon(300); return; }
            if (is(t, "టార్చ్", "టార్చ్ ఆన్", "టార్చ్ వేయి", "ఫ్లాష్", "ఫ్లాష్ ఆన్", "లైట్ వేయి", "లైట్ ఆన్", "torch", "torch on", "flash on")) { cam.setTorch(true); refreshButtons(); listenSoon(300); return; }
            if (is(t, "జూమ్ తగ్గించు", "జూమ్ ఆఫ్", "zoom out")) { cam.setZoom(1f); listenSoon(300); return; }
            if (is(t, "జూమ్", "జూమ్ చేయి", "zoom", "zoom in", "దగ్గరగా")) { cam.setZoom(cam.zoom() * 2f); listenSoon(300); return; }
            if (is(t, "హోలోగ్రామ్", "హోలోగ్రామ్ చూపించు", "త్రీడీ", "త్రీడీ చూపించు", "3d", "3d చూపించు", "3డి", "3డి చూపించు", "హోలో")) { hologram(); return; }
            if (is(t, "pdf", "pdf చేయి", "పీడీఎఫ్", "పీడీఎఫ్ చేయి", "పిడిఎఫ్")) { pdf(); return; }
            if (pairKind != null && is(t, "పోల్చు", "పోల్చి చెప్పు", "రెండోది", "ఇప్పుడు చూడు", "కొత్తది")) { pairSecond(); return; }
            if (layersId != null && is(t, "పొర", "లేయర్", "layer", "తర్వాతి పొర", "పొర స్కాన్")) { layerShot(); return; }
            if (layersId != null && is(t, "అయిపోయింది", "పూర్తి", "done")) { layersDone(); return; }
            if (!guideNext.isEmpty() && is(t, "చూడు", "చూపిస్తున్నా", "చూపిస్తున్నాను", "అయింది", "రెడీ", "ఇదిగో", "ఇదిగో చూడు")) { guideStep(); return; }
        }
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
            default: return "ఇది ఏంటి? దీని గురించి వివరంగా చెప్పు.";
        }
    }

    private void scanTap() {
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
        if (busy) { if (deep) deepThenPdf = false; Toast.makeText(this, "ఇంకా చూస్తున్నాను…", Toast.LENGTH_SHORT).show(); return; }
        if (!p.hasBrain()) { deepThenPdf = false; say("నా మెదడుకి API key లేదు. Jarvis సెట్టింగ్స్‌లో పెట్టండి."); return; }
        final String q = question == null || question.trim().isEmpty() ? defaultQuestion() : question.trim();
        Bitmap pic = override != null ? override : frozen ? photo : cam.frame(deep ? 1800 : 1400);
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
        setActions(null);
        tell("🔍 " + q + "\n\n" + (deep ? "వివరంగా స్కాన్ చేస్తున్నాను… (కొంచెం టైమ్ పడుతుంది)" : "చూస్తున్నాను…"), false);
        refreshButtons();
        final String talk = talkText(), g = guide, m = mode;
        work.execute(() -> {
            String code = null, err = null;
            ScanBrain.Result r = null;
            try {
                code = QrReader.read(pic);
                String jpeg = jpeg(pic, deep ? 1800 : 1280, 82);
                String sys = deep ? ScanBrain.deepSystem(p) : ScanBrain.system(p);
                String prompt = ScanBrain.context(this, m, code, talk, g) + "\nHis question: " + q;
                String reply = Brain.oneShot(p, sys, prompt, jpeg, p.webSearch(), deep ? 12000 : 3500);
                r = ScanBrain.parse(reply);
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
                answer(q, res, pic, onScreen, deep, layer, close, cd);
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
        turns.add(new String[]{"Anil", q});
        turns.add(new String[]{"Jarvis", r.say});
        while (turns.size() > 12) turns.poll();
        if (onScreen) {
            hud.setItems(r.items);
            arReference(pic);
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
        if (layer) layerGuideIfOn();
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
            frozen = true;
            photo = bm;
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

    @Override public String photoJpeg() { return photo == null ? null : jpeg(photo, 1400, 85); }

    // ================================================================ the menu

    private void menu() {
        final String[] items = {
                "🧊 3D హోలోగ్రామ్ (వివరమైన స్కాన్)", "📄 తెలుగు PDF (parts, డయాగ్రమ్, తయారీ)", "⚖️ రెండు పోల్చు", "🧰 టెక్నీషియన్ చెక్ (పాత / కొత్త part)",
                "🧅 తెరుస్తూ పొర పొరగా స్కాన్", "🎤 శబ్దం వినిపించు (ఏ part సమస్యో)", "✅ చెకప్ (కార్ / బైక్)", "🔌 OBD స్కానర్ (కార్)",
                "🅿️ బండి ఇక్కడ పెట్టాను", "🔑 ఈ వస్తువు ఇక్కడ పెడుతున్నా", "🔍 భూతద్దం (చిన్న అక్షరాలు)", "📚 స్కాన్ చరిత్ర", "🧊 నా బోర్డులు / తెరిచినవి",
                "⌨️ టైప్ చేసి అడుగు", "📡 AR పేర్లు: " + (arOn ? "ఆన్" : "ఆఫ్"), "📤 షేర్ (బొమ్మ + జవాబు)"};
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
                        default: break;
                    }
                }).show();
    }

    /** The parking spot's picture is kept with the scans (the spot itself is saved by its action). */
    private void savePlaceShot(String title) {
        Bitmap bm = frozen ? photo : cam.frame(1400);
        if (bm == null) return;
        work.execute(() -> {
            try {
                ScanStore.save(this, new JSONObject().put("title", title).put("kind", "parking").put("mode", mode)
                        .put("say", title + " · " + new java.text.SimpleDateFormat("d MMM h:mm a", Locale.ENGLISH).format(new java.util.Date())), bm, "photo.jpg");
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
            Bitmap b = last == null ? photo : ScanHud.withBoxes(photo, last.items, d);
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
                        .setMessage("\"" + o.optString("title") + "\" తీసేయనా?")
                        .setPositiveButton("తీసేయి", (a, b) -> { ScanStore.delete(this, o.optString("id")); dlg.dismiss(); history(boards); })
                        .setNegativeButton("వద్దు", null).show();
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
                if (code == null || code.equals(shownCode)) return;
                main.post(() -> codeSeen(code));
            });
        }
    };

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
