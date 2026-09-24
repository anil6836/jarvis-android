package com.anil.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.AudioManager;
import android.media.ExifInterface;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity implements Tools.Host, VoiceIO.Listener, Store.Listener, LiveSession.Listener {
    static final String EXTRA_WAKE = "wake";
    static final String EXTRA_BRIEF = "brief";
    /** True while the Jarvis screen is in front (then a fresh screenshot would only show Jarvis). */
    static volatile boolean visible;
    private static final String BRIEF_PROMPT = "నాకు ఇప్పటి బ్రీఫింగ్ ఇవ్వు: సమయానికి తగ్గ పలకరింపు, ఈరోజు తేదీ, నా లొకేషన్‌లో వాతావరణం (get_weather వాడు), ఈరోజు క్యాలెండర్, రిమైండర్లు, నా యాక్టివ్ మిషన్లలో ముఖ్యమైనవి, బ్యాటరీ తక్కువగా ఉంటే అది కూడా. 6 వాక్యాలు మించకుండా.";
    private static final int REQ_CAMERA_PERM = 24, REQ_PHOTO_CAM = 25;
    private CameraPanel camera;
    private FrameLayout cameraBox;
    private LinearLayout reminderList;
    private TextView reminderLabel;
    /** True from the moment Anil starts talking until Jarvis has finished answering. */
    static volatile boolean inConversation;

    private static final int REQ_CAMERA = 11, REQ_GALLERY = 12, REQ_PERMS = 21, REQ_MIC = 22, REQ_LIVE = 23;

    private Prefs prefs;
    private Store store;
    private Tools tools;
    private Brain brain;
    private VoiceIO voice;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private OrbView orb;
    private TextView status, clock, dateView, setupCard, undoBar;
    private LinearLayout chatList, missionList, doneList, memoryList;
    private TextView missionEmpty, memoryEmpty, doneLabel, missionCount, memoryCount;
    private ScrollView chatScroll;
    private final View[] panels = new View[3];
    private final TextView[] tabLabels = new TextView[3];
    private final View[] tabLines = new View[3];
    private LinearLayout dock, attachRow;
    private ImageView attachThumb;
    private EditText input;
    private FrameLayout actionBtn;
    private IconView actionIcon;

    private String pendingPhoto;       // base64 JPEG waiting to be sent
    private Bitmap pendingThumb;
    private boolean busy;
    private boolean paused;
    private boolean lastWasVoice;
    private int generation;            // increases with every request, so a stopped answer is ignored
    private Runnable pendingUndo;
    private LiveSession live;          // an open real-time voice conversation, or null
    private TextView liveBubble;       // Jarvis's reply while it is still being spoken

    // ================================================================ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        store = Store.get(this);
        store.listener = this;
        tools = new Tools(this, store, prefs);
        brain = new Brain(prefs, store, tools);
        voice = new VoiceIO(this, prefs, this);
        setContentView(buildUi());
        renderChat();
        onStoreChanged();
        tick();
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        paused = false;
        visible = true;
        store.listener = this;
        Reminders.scheduleBriefing(this);
        orb.invalidate();
        updateSetup();
        if (live == null && !busy && !voice.listening && !voice.speaking) setIdle();
        if (live == null && !busy) renderChat();
        syncWakeService();
    }

    @Override protected void onPause() {
        super.onPause();
        paused = true;
        visible = false;
        if (camera != null && camera.isOpen()) {
            camera.close();
            cameraBox.setVisibility(View.GONE);
        }
        if (voice.listening) {
            voice.cancelListening();
            finishTurn();
        }
    }

    @Override protected void onStop() {
        super.onStop();
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(false);
    }

    @Override protected void onDestroy() {
        if (store.listener == this) store.listener = null;
        if (live != null) live.stop("destroy");
        if (camera != null) camera.close();
        voice.shutdown();
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        inConversation = false;
        super.onDestroy();
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        // Opened from the launcher or by "Hey Google, open Jarvis": start listening right away.
        boolean launcher = Intent.ACTION_MAIN.equals(i.getAction()) && i.hasCategory(Intent.CATEGORY_LAUNCHER);
        if (launcher && !i.getBooleanExtra("jarvis_handled", false)) {
            i.putExtra("jarvis_handled", true);
            if (prefs.listenOnOpen() && prefs.hasBrain()
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                inConversation = true;
                main.postDelayed(this::startConversation, 450);
            }
            return;
        }
        if (i.getBooleanExtra(EXTRA_BRIEF, false)) {
            i.removeExtra(EXTRA_BRIEF);
            main.postDelayed(() -> send(BRIEF_PROMPT, "శుభోదయం బ్రీఫింగ్", false), 500);
            return;
        }
        boolean wake = i.getBooleanExtra(EXTRA_WAKE, false);
        boolean assist = Intent.ACTION_ASSIST.equals(i.getAction()) || Intent.ACTION_VOICE_COMMAND.equals(i.getAction());
        if (!wake && !assist) return;
        i.removeExtra(EXTRA_WAKE);
        if (assist) i.setAction(Intent.ACTION_MAIN); // handle a long-press only once
        if (wake || assist) {
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
            } else {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            }
        }
        inConversation = true;
        beep();
        main.postDelayed(this::startConversation, 300);
    }

    private void syncWakeService() {
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (prefs.wakeReady() && mic) WakeService.start(this, inConversation);
        else WakeService.stop(this);
    }

    static String[] corePermissions() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.RECORD_AUDIO);
        p.add(Manifest.permission.CALL_PHONE);
        p.add(Manifest.permission.SEND_SMS);
        p.add(Manifest.permission.READ_CONTACTS);
        p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        p.add(Manifest.permission.READ_CALENDAR);
        p.add(Manifest.permission.WRITE_CALENDAR);
        p.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        return p.toArray(new String[0]);
    }

    private boolean missingPermissions() {
        for (String p : corePermissions()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return true;
        return false;
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        updateSetup();
        if (code == REQ_CAMERA_PERM && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) toggleCamera();
        if (code == REQ_PHOTO_CAM && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera();
        if (code == REQ_LIVE) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startLive();
            else Toast.makeText(this, "మాట్లాడాలంటే మైక్ అనుమతి కావాలి", Toast.LENGTH_LONG).show();
        }
        if (code == REQ_MIC) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening();
            else Toast.makeText(this, "మాట్లాడాలంటే మైక్ అనుమతి కావాలి", Toast.LENGTH_LONG).show();
        }
        syncWakeService();
    }

    // ================================================================ UI

    private int dp(float v) { return Ui.dp(this, v); }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.INK);
        root.setPadding(dp(16), 0, dp(16), 0);

        // ---- HUD header
        LinearLayout hud = new LinearLayout(this);
        hud.setGravity(Gravity.CENTER_VERTICAL);
        hud.setPadding(0, dp(12), 0, dp(8));
        orb = new OrbView(this);
        orb.setOnClickListener(v -> onActionPressed());
        hud.addView(orb, new LinearLayout.LayoutParams(dp(62), dp(62)));

        LinearLayout ident = new LinearLayout(this);
        ident.setOrientation(LinearLayout.VERTICAL);
        ident.setPadding(dp(12), 0, dp(8), 0);
        TextView name = Ui.mono(this, "JARVIS", 20, Ui.CYAN);
        name.setLetterSpacing(0.34f);
        ident.addView(name);
        status = Ui.text(this, "", 14, Ui.MUTED);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        ident.addView(status);
        hud.addView(ident, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout readout = new LinearLayout(this);
        readout.setOrientation(LinearLayout.VERTICAL);
        readout.setGravity(Gravity.END);
        clock = Ui.mono(this, "--:--", 20, Ui.TEXT);
        clock.setLetterSpacing(0.02f);
        readout.addView(clock);
        dateView = Ui.text(this, "", 12, Ui.MUTED);
        readout.addView(dateView);
        hud.addView(readout);

        IconView gear = new IconView(this, IconView.GEAR, Ui.CYAN);
        gear.setContentDescription("సెట్టింగ్స్");
        gear.setBackground(Ui.round(this, 0, Ui.LINE2, 10));
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(42), dp(42));
        glp.leftMargin = dp(10);
        hud.addView(gear, glp);
        root.addView(hud);

        // ---- tabs
        LinearLayout tabs = new LinearLayout(this);
        String[] names = {"సంభాషణ", "మిషన్లు", "జ్ఞాపకాలు"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout labelRow = new LinearLayout(this);
            labelRow.setGravity(Gravity.CENTER);
            tabLabels[i] = Ui.text(this, names[i], 15.5f, Ui.MUTED);
            labelRow.addView(tabLabels[i]);
            if (i > 0) {
                TextView count = Ui.mono(this, "0", 12, Ui.CYAN);
                count.setLetterSpacing(0);
                count.setBackground(Ui.round(this, Ui.PANEL2, 0, 9));
                count.setPadding(dp(6), 0, dp(6), 0);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
                clp.leftMargin = dp(6);
                labelRow.addView(count, clp);
                if (i == 1) missionCount = count; else memoryCount = count;
            }
            labelRow.setPadding(0, dp(8), 0, dp(7));
            tab.addView(labelRow, new LinearLayout.LayoutParams(-1, -2));
            tabLines[i] = new View(this);
            tab.addView(tabLines[i], new LinearLayout.LayoutParams(-1, dp(2)));
            tab.setOnClickListener(v -> showTab(idx));
            tabs.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(tabs);
        View rule = new View(this);
        rule.setBackgroundColor(Ui.LINE);
        root.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));

        // ---- live camera (hidden until the "Live కెమెరా" button is tapped)
        camera = new CameraPanel(this);
        cameraBox = new FrameLayout(this);
        cameraBox.setBackground(Ui.round(this, 0xFF000000, Ui.CYAN_DIM, 12));
        cameraBox.setPadding(dp(2), dp(2), dp(2), dp(2));
        cameraBox.addView(camera.view, new FrameLayout.LayoutParams(-1, -1));
        TextView camLabel = Ui.mono(this, "● LIVE", 12, Ui.RED);
        camLabel.setPadding(dp(10), dp(6), dp(10), dp(6));
        cameraBox.addView(camLabel, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        TextView camClose = Ui.text(this, "✕", 18, Ui.TEXT);
        camClose.setPadding(dp(12), dp(4), dp(12), dp(4));
        camClose.setOnClickListener(v -> toggleCamera());
        cameraBox.addView(camClose, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        cameraBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(-1, dp(240));
        cblp.topMargin = dp(8);
        root.addView(cameraBox, cblp);

        // ---- panels
        FrameLayout stage = new FrameLayout(this);
        chatScroll = new ScrollView(this);
        LinearLayout chatBox = new LinearLayout(this);
        chatBox.setOrientation(LinearLayout.VERTICAL);
        chatBox.setPadding(0, dp(14), 0, dp(14));
        setupCard = Ui.text(this, "", 15, Ui.TEXT);
        setupCard.setBackground(Ui.round(this, 0x14FF6B5E, 0x73FF6B5E, 12));
        setupCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.bottomMargin = dp(14);
        chatBox.addView(setupCard, slp);
        chatList = new LinearLayout(this);
        chatList.setOrientation(LinearLayout.VERTICAL);
        chatBox.addView(chatList);
        chatScroll.addView(chatBox);
        panels[0] = chatScroll;
        stage.addView(chatScroll);

        panels[1] = buildListPanel(true);
        stage.addView(panels[1]);
        panels[2] = buildListPanel(false);
        stage.addView(panels[2]);

        undoBar = Ui.text(this, "", 15, Ui.TEXT);
        undoBar.setBackground(Ui.round(this, Ui.PANEL2, Ui.CYAN_DIM, 12));
        undoBar.setPadding(dp(14), dp(10), dp(14), dp(10));
        undoBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams ulp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ulp.bottomMargin = dp(12);
        stage.addView(undoBar, ulp);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---- dock
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setPadding(0, dp(8), 0, dp(12));
        View dockRule = new View(this);
        dockRule.setBackgroundColor(Ui.LINE);
        dock.addView(dockRule, new LinearLayout.LayoutParams(-1, dp(1)));

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, dp(8), 0, dp(8));
        addChip(chips, "శుభోదయం బ్రీఫింగ్", BRIEF_PROMPT);
        addChip(chips, "వార్తలు", "ఈరోజు ముఖ్యమైన 3 వార్తలు చెప్పు: ఒకటి భారతదేశం, ఒకటి తెలంగాణ లేదా ఆంధ్రప్రదేశ్, ఒకటి టెక్నాలజీ. ఇంటర్నెట్‌లో వెతికి, చిన్నగా చెప్పు.");
        addChip(chips, "వాతావరణం", "ఇప్పుడు ఇక్కడ వాతావరణం ఎలా ఉంది? రేపు వర్షం పడే అవకాశం ఉందా?");
        addChip(chips, "ఫోటో స్కాన్", null);
        TextView camChip = Ui.pill(this, "Live కెమెరా");
        camChip.setOnClickListener(v -> toggleCamera());
        LinearLayout.LayoutParams ccl = new LinearLayout.LayoutParams(-2, -2);
        ccl.rightMargin = dp(8);
        chips.addView(camChip, ccl);
        addChip(chips, "స్క్రీన్ చూడు", "నా స్క్రీన్‌లో ఏముందో చూసి చెప్పు (look_at_screen వాడు).");
        addChip(chips, "మెసేజ్‌లు", "నాకు వచ్చిన కొత్త మెసేజ్‌లు చదివి చెప్పు (read_notifications వాడు).");
        addChip(chips, "రిమైండర్లు", "నా రాబోయే రిమైండర్లు, ఈరోజు క్యాలెండర్ చెప్పు.");
        addChip(chips, "మిషన్ స్టేటస్", "నా మిషన్ల స్టేటస్ చెప్పు. ఎన్ని పెండింగ్‌లో ఉన్నాయి, ముందు ఏది చేయాలో ఒక్కటి సూచించు.");
        addChip(chips, "ఫోకస్ మోడ్", "నేను ఇప్పుడు 25 నిమిషాలు ఫోకస్ చేయాలి. నా మిషన్ల నుంచి ఒకటి ఎంచుకుని మూడు చిన్న స్టెప్స్ చెప్పు, తర్వాత 25 నిమిషాల టైమర్ పెట్టు.");
        addChip(chips, "సూట్ అప్", "Jarvis, సూట్ అప్! ఈరోజుని ఎదుర్కోవడానికి నన్ను సిద్ధం చేయి.");
        addChip(chips, "ఒక జోక్", "నీ డ్రై బట్లర్ స్టైల్‌లో ఒక చిన్న తెలుగు జోక్ చెప్పు.");
        chipScroll.addView(chips);
        dock.addView(chipScroll);

        attachRow = new LinearLayout(this);
        attachRow.setGravity(Gravity.CENTER_VERTICAL);
        attachRow.setPadding(0, 0, 0, dp(8));
        attachThumb = new ImageView(this);
        attachThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        attachRow.addView(attachThumb, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView attachText = Ui.text(this, "  ఫోటో జత చేశారు", 14, Ui.MUTED);
        attachRow.addView(attachText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView attachRemove = Ui.text(this, "తీసేయి", 14, Ui.RED);
        attachRemove.setPadding(dp(10), dp(8), dp(4), dp(8));
        attachRemove.setOnClickListener(v -> clearAttachment());
        attachRow.addView(attachRemove);
        attachRow.setVisibility(View.GONE);
        dock.addView(attachRow);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.BOTTOM);
        IconView cam = new IconView(this, IconView.CAMERA, Ui.CYAN);
        cam.setContentDescription("ఫోటో");
        cam.setBackground(Ui.round(this, 0, Ui.LINE2, 12));
        cam.setOnClickListener(v -> pickPhoto());
        row.addView(cam, new LinearLayout.LayoutParams(dp(48), dp(48)));

        input = new EditText(this);
        input.setHint("Jarvis తో మాట్లాడండి…");
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.FAINT);
        input.setTextSize(16.5f);
        input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setBackground(Ui.round(this, Ui.DEEP, Ui.LINE2, 14));
        input.setPadding(dp(13), dp(11), dp(13), dp(11));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { if (!voice.listening) refreshAction(); }
        });
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, -2, 1);
        ilp.leftMargin = dp(8);
        ilp.rightMargin = dp(8);
        row.addView(input, ilp);

        actionBtn = new FrameLayout(this);
        actionBtn.setBackground(Ui.round(this, Ui.GOLD, 0, 26));
        actionIcon = new IconView(this, IconView.MIC, Ui.GOLD_INK);
        actionBtn.addView(actionIcon, new FrameLayout.LayoutParams(-1, -1));
        actionBtn.setOnClickListener(v -> onActionPressed());
        actionBtn.setContentDescription("మాట్లాడండి");
        row.addView(actionBtn, new LinearLayout.LayoutParams(dp(52), dp(52)));
        dock.addView(row);
        root.addView(dock);

        showTab(0);
        return root;
    }

    private View buildListPanel(boolean missions) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(14), 0, dp(60));

        LinearLayout add = new LinearLayout(this);
        EditText field = new EditText(this);
        field.setHint(missions ? "కొత్త మిషన్ రాయండి" : "Jarvis గుర్తుంచుకోవాల్సిన విషయం");
        field.setTextColor(Ui.TEXT);
        field.setHintTextColor(Ui.FAINT);
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setBackground(Ui.round(this, Ui.DEEP, Ui.LINE2, 12));
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        add.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
        Button go = new Button(this);
        go.setText(missions ? "జోడించు" : "సేవ్");
        go.setTextColor(Ui.CYAN);
        go.setAllCaps(false);
        go.setBackground(Ui.round(this, Ui.PANEL, Ui.CYAN_DIM, 12));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, dp(48));
        glp.leftMargin = dp(8);
        add.addView(go, glp);
        go.setOnClickListener(v -> {
            String t = field.getText().toString();
            JSONObject o = missions ? store.addMission(t) : store.addMemory(t);
            if (o != null) field.setText("");
        });
        box.addView(add);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(10), 0, 0);
        box.addView(list);
        TextView empty = Ui.text(this, missions
                ? "ఇంకా మిషన్లు లేవు. పైన రాయండి, లేదా Jarvis కి చెప్పండి: “రేపు ఉదయం బ్యాంక్ పని ఉంది, మిషన్‌గా పెట్టు”."
                : "ఇంకా ఏమీ గుర్తుంచుకోలేదు. Jarvis కి చెప్పండి: “గుర్తుంచుకో: నాకు ఫిల్టర్ కాఫీ అంటే ఇష్టం”. ప్రతి సమాధానంలో ఇవన్నీ గుర్తుంటాయి.",
                15, Ui.MUTED);
        empty.setPadding(dp(2), dp(8), dp(2), 0);
        box.addView(empty);

        if (missions) {
            missionList = list;
            missionEmpty = empty;
            doneLabel = Ui.mono(this, "పూర్తయినవి", 12, Ui.FAINT);
            doneLabel.setPadding(dp(2), dp(22), 0, dp(4));
            box.addView(doneLabel);
            doneList = new LinearLayout(this);
            doneList.setOrientation(LinearLayout.VERTICAL);
            box.addView(doneList);
            reminderLabel = Ui.mono(this, "రాబోయే రిమైండర్లు", 12, Ui.FAINT);
            reminderLabel.setPadding(dp(2), dp(22), 0, dp(4));
            box.addView(reminderLabel);
            reminderList = new LinearLayout(this);
            reminderList.setOrientation(LinearLayout.VERTICAL);
            box.addView(reminderList);
        } else {
            memoryList = list;
            memoryEmpty = empty;
        }
        scroll.addView(box);
        return scroll;
    }

    private void addChip(LinearLayout chips, String label, String prompt) {
        TextView chip = Ui.pill(this, label);
        chip.setOnClickListener(v -> {
            if (busy) return;
            if (prompt != null && live != null && live.isOpen()) {
                store.addChat("user", label, false);
                addMessage("user", label, System.currentTimeMillis(), null);
                live.sendText(prompt);
                return;
            }
            if (prompt == null) pickPhoto();
            else send(prompt, label, false);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        chips.addView(chip, lp);
    }

    private void showTab(int idx) {
        for (int i = 0; i < 3; i++) {
            panels[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            tabLabels[i].setTextColor(i == idx ? Ui.TEXT : Ui.MUTED);
            tabLines[i].setBackgroundColor(i == idx ? Ui.CYAN : 0);
        }
        dock.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        if (idx == 0) scrollToEnd();
    }

    private void tick() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat("HH:mm", Locale.ENGLISH).format(now));
        dateView.setText(new SimpleDateFormat("EEEE, d MMM", new Locale("te", "IN")).format(now));
        main.postDelayed(this::tick, 15000);
    }

    private String greetingWord() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h >= 4 && h < 12) return "శుభోదయం";
        if (h >= 12 && h < 17) return "శుభ మధ్యాహ్నం";
        if (h >= 17 && h < 21) return "శుభ సాయంత్రం";
        return "ఇంత రాత్రి వేళ కూడా పనిలోనే ఉన్నారా";
    }

    private void updateSetup() {
        if (!prefs.hasBrain()) {
            setupCard.setText("Jarvis మెదడుకి API key కావాలి. ఇక్కడ నొక్కి సెట్టింగ్స్‌లో మీ OpenAI (లేదా Anthropic) key పెట్టండి.");
            setupCard.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
            setupCard.setVisibility(View.VISIBLE);
        } else if (missingPermissions()) {
            setupCard.setText("కాల్స్, SMS, కాంటాక్ట్స్, మైక్, లొకేషన్ వాడాలంటే అనుమతులు కావాలి. ఇక్కడ నొక్కి Allow ఇవ్వండి.");
            setupCard.setOnClickListener(v -> requestPermissions(corePermissions(), REQ_PERMS));
            setupCard.setVisibility(View.VISIBLE);
        } else if (voice != null && voice.ttsChecked && !voice.teluguVoice && prefs.voiceReplies()) {
            setupCard.setText("ఈ ఫోన్‌లో తెలుగు వాయిస్ ఇన్‌స్టాల్ అవ్వలేదు, అందుకే Jarvis తెలుగు సరిగ్గా పలకలేడు. ఇక్కడ నొక్కి Google Text-to-speech లో తెలుగు వాయిస్ డౌన్‌లోడ్ చేయండి.");
            setupCard.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA));
                } catch (Exception e) {
                    try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); } catch (Exception ignored) {}
                }
            });
            setupCard.setVisibility(View.VISIBLE);
        } else {
            setupCard.setVisibility(View.GONE);
        }
    }

    @Override public void onVoiceReady() { updateSetup(); }

    // ================================================================ chat rendering

    private String hhmm(long t) { return new SimpleDateFormat("HH:mm", Locale.ENGLISH).format(new Date(t)); }

    private TextView addMessage(String role, String text, long t, Bitmap photo) {
        boolean user = "user".equals(role);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(user ? Gravity.END : Gravity.START);

        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        TextView who = Ui.mono(this, (user ? prefs.name().toUpperCase(Locale.ROOT) : "JARVIS") + " · " + hhmm(t), 11.5f, user ? Ui.GOLD : Ui.CYAN2);
        meta.addView(who);
        wrap.addView(meta);

        if (photo != null) {
            ImageView img = new ImageView(this);
            img.setImageBitmap(photo);
            img.setAdjustViewBounds(true);
            img.setMaxWidth(dp(180));
            img.setMaxHeight(dp(180));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
            plp.topMargin = dp(4);
            wrap.addView(img, plp);
        }

        TextView body = Ui.text(this, text, 16.5f, Ui.TEXT);
        body.setLineSpacing(0, 1.25f);
        body.setTextIsSelectable(true);
        body.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.8f));
        if (user) {
            body.setBackground(Ui.round(this, Ui.PANEL, Ui.LINE2, 14));
            body.setPadding(dp(13), dp(8), dp(13), dp(9));
        } else {
            body.setBackground(new Ui.Brackets(this));
            body.setPadding(dp(14), dp(9), dp(14), dp(10));
            IconView replay = new IconView(this, IconView.SPEAKER, Ui.CYAN2);
            replay.setContentDescription("మళ్లీ వినిపించు");
            replay.setOnClickListener(v -> {
                lastWasVoice = false;
                voice.speak(body.getText().toString(), prefs.speechRate());
            });
            meta.addView(replay, new LinearLayout.LayoutParams(dp(28), dp(24)));
        }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = dp(4);
        wrap.addView(body, blp);

        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
        wlp.bottomMargin = dp(16);
        chatList.addView(wrap, wlp);
        scrollToEnd();
        return body;
    }

    private void renderChat() {
        chatList.removeAllViews();
        List<JSONObject> turns = store.chat();
        for (JSONObject o : turns) {
            String c = o.optString("content");
            if (o.optBoolean("photo")) c = "📷 " + c;
            addMessage(o.optString("role"), c, o.optLong("t", System.currentTimeMillis()), null);
        }
        String n = prefs.name();
        String hello;
        if (!turns.isEmpty()) {
            hello = greetingWord() + ", " + n + ". మళ్లీ కలవడం సంతోషం.";
        } else {
            hello = greetingWord() + ", " + n + ". అన్ని వ్యవస్థలు ఆన్‌లైన్‌లో ఉన్నాయి. కింద మైక్ నొక్కి మాట్లాడండి"
                    + (prefs.wakeReady() ? ", లేదా ఎప్పుడైనా \"Hey Jarvis\" అని పిలవండి." : ".");
        }
        addMessage("assistant", hello, System.currentTimeMillis(), null);
    }

    private void scrollToEnd() {
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ================================================================ missions & memories

    @Override public void onStoreChanged() {
        List<JSONObject> ms = store.missions();
        missionList.removeAllViews();
        doneList.removeAllViews();
        int active = 0, done = 0;
        for (JSONObject m : ms) {
            if (!m.optBoolean("done")) { missionList.addView(itemRow(m, true)); active++; }
        }
        for (int i = ms.size() - 1; i >= 0 && done < 30; i--) {
            if (ms.get(i).optBoolean("done")) { doneList.addView(itemRow(ms.get(i), true)); done++; }
        }
        missionEmpty.setVisibility(active == 0 ? View.VISIBLE : View.GONE);
        doneLabel.setVisibility(done == 0 ? View.GONE : View.VISIBLE);
        missionCount.setText(String.valueOf(active));

        List<JSONObject> mem = store.memories();
        memoryList.removeAllViews();
        for (int i = mem.size() - 1; i >= 0; i--) memoryList.addView(itemRow(mem.get(i), false));
        memoryEmpty.setVisibility(mem.isEmpty() ? View.VISIBLE : View.GONE);
        memoryCount.setText(String.valueOf(mem.size()));

        reminderList.removeAllViews();
        List<JSONObject> rs = store.reminders();
        rs.sort((x, y) -> Long.compare(x.optLong("at"), y.optLong("at")));
        long now = System.currentTimeMillis();
        for (JSONObject r : rs) {
            if (r.optBoolean("done") || r.optLong("at") < now) continue;
            reminderList.addView(reminderRow(r));
        }
        reminderLabel.setVisibility(reminderList.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private View reminderRow(JSONObject r) {
        String id = r.optString("id");
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, "⏰ " + r.optString("text"), 16, Ui.TEXT));
        TextView w = Ui.mono(this, new SimpleDateFormat("EEE d MMM · HH:mm", Locale.ENGLISH).format(new Date(r.optLong("at"))), 11.5f, Ui.GOLD);
        w.setLetterSpacing(0.04f);
        col.addView(w);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        IconView del = new IconView(this, IconView.TRASH, Ui.FAINT);
        del.setContentDescription("రిమైండర్ తీసేయి");
        del.setOnClickListener(v -> {
            Reminders.cancel(this, id);
            JSONObject gone = store.removeReminder(id);
            if (gone != null) showUndo("రిమైండర్ తీసేశాను", () -> {
                store.addReminder(gone.optString("text"), gone.optLong("at"));
                for (JSONObject x : store.reminders()) if (x.optLong("at") == gone.optLong("at") && !x.optBoolean("done")) Reminders.schedule(this, x);
            });
        });
        row.addView(del, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        wrap.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        return wrap;
    }

    private void toggleCamera() {
        if (camera.isOpen()) {
            camera.close();
            cameraBox.setVisibility(View.GONE);
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERM);
            return;
        }
        showTab(0);
        cameraBox.setVisibility(View.VISIBLE);
        camera.open();
        Toast.makeText(this, "Live కెమెరా ఆన్. ఏం కనిపిస్తోందో Jarvis ని అడగండి.", Toast.LENGTH_SHORT).show();
    }

    private View itemRow(JSONObject item, boolean mission) {
        boolean done = item.optBoolean("done");
        String id = item.optString("id");
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        if (mission) {
            IconView check = new IconView(this, IconView.CHECK, done ? Ui.INK : 0x00000000);
            check.setBackground(Ui.round(this, done ? Ui.CYAN2 : 0, Ui.CYAN_DIM, 7));
            check.setContentDescription(done ? "మళ్లీ యాక్టివ్ చేయి" : "పూర్తయింది");
            check.setOnClickListener(v -> store.setMissionDone(id, !done));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(26), dp(26));
            clp.rightMargin = dp(12);
            row.addView(check, clp);
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(this, item.optString("text"), 16, done ? Ui.FAINT : Ui.TEXT);
        if (done) t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        col.addView(t);
        long when = item.optLong(done ? "doneAt" : "t", item.optLong("t"));
        TextView w = Ui.mono(this, new SimpleDateFormat("d MMM · HH:mm", Locale.ENGLISH).format(new Date(when)), 11.5f, Ui.FAINT);
        w.setLetterSpacing(0.04f);
        col.addView(w);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

        IconView del = new IconView(this, IconView.TRASH, Ui.FAINT);
        del.setContentDescription("తీసేయి");
        del.setOnClickListener(v -> {
            JSONObject gone = mission ? store.removeMission(id) : store.removeMemory(id);
            if (gone != null) showUndo(mission ? "మిషన్ తీసేశాను" : "జ్ఞాపకం తీసేశాను",
                    () -> { if (mission) store.restoreMission(gone); else store.restoreMemory(gone); });
        });
        row.addView(del, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        wrap.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        return wrap;
    }

    private void showUndo(String text, Runnable undo) {
        if (pendingUndo != null) main.removeCallbacks(pendingUndo);
        undoBar.setText(text + "   ·   తిరిగి తీసుకురా");
        undoBar.setVisibility(View.VISIBLE);
        undoBar.setOnClickListener(v -> { undo.run(); undoBar.setVisibility(View.GONE); });
        pendingUndo = () -> undoBar.setVisibility(View.GONE);
        main.postDelayed(pendingUndo, 6000);
    }

    // ================================================================ talking

    private void refreshAction() {
        int icon;
        int bg = Ui.GOLD;
        int fg = Ui.GOLD_INK;
        String desc;
        if (live != null) { icon = IconView.STOP; bg = Ui.RED; fg = 0xFF2A0703; desc = "Live సంభాషణ ఆపు"; }
        else if (busy || voice.speaking) { icon = IconView.STOP; bg = Ui.RED; fg = 0xFF2A0703; desc = "ఆపు"; }
        else if (voice.listening) { icon = IconView.STOP; desc = "వినడం ఆపు"; }
        else if (input.getText().toString().trim().length() > 0 || pendingPhoto != null) { icon = IconView.SEND; desc = "పంపు"; }
        else { icon = IconView.MIC; desc = "మాట్లాడండి"; }
        actionIcon.setIcon(icon);
        actionIcon.setColor(fg);
        actionBtn.setBackground(Ui.round(this, bg, 0, 26));
        actionBtn.setContentDescription(desc);
    }

    private void onActionPressed() {
        if (live != null) {
            live.stop("user");
            return;
        }
        if (busy) {
            generation++; // ignore the answer that is still on its way
            busy = false;
            removeThinking();
            finishTurn();
            return;
        }
        if (voice.speaking) {
            voice.stopSpeaking();
            finishTurn();
            return;
        }
        if (voice.listening) {
            voice.stopListening();
            return;
        }
        String text = input.getText().toString().trim();
        if (!text.isEmpty() || pendingPhoto != null) {
            input.setText("");
            send(text, null, false);
        } else {
            startConversation();
        }
    }

    /** Live real-time talk when it is switched on, otherwise the classic listen-then-answer. */
    private void startConversation() {
        if (prefs.liveReady()) startLive(); else startListening();
    }

    // ================================================================ live (real-time) conversation

    private void startLive() {
        if (live != null || busy) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_LIVE);
            return;
        }
        voice.stopSpeaking();
        if (voice.listening) voice.cancelListening();
        inConversation = true;
        WakeService.pause(this);
        showTab(0);
        input.setHint("Live: మాట్లాడండి, ఆపాలంటే ఎరుపు బటన్");
        live = new LiveSession(this, prefs, tools, this);
        live.start(brain.liveInstructions(store.chat()));
        refreshAction();
    }

    @Override public void onLiveState(int orbState, String text) {
        orb.setState(orbState);
        status.setText(text);
    }

    @Override public void onLiveUser(String text) {
        store.addChat("user", text, false);
        TextView t = addMessage("user", text, System.currentTimeMillis(), null);
        if (liveBubble != null) {
            // Keep the order right: Anil's words above the reply that is already streaming.
            View userWrap = (View) t.getParent();
            View replyWrap = (View) liveBubble.getParent();
            chatList.removeView(userWrap);
            chatList.addView(userWrap, chatList.indexOfChild(replyWrap));
        }
    }

    @Override public void onLiveJarvisPartial(String text) {
        if (liveBubble == null) liveBubble = addMessage("assistant", text, System.currentTimeMillis(), null);
        else {
            liveBubble.setText(text);
            scrollToEnd();
        }
    }

    @Override public void onLiveJarvis(String text) {
        if (liveBubble != null) liveBubble.setText(text);
        else addMessage("assistant", text, System.currentTimeMillis(), null);
        liveBubble = null;
        store.addChat("assistant", text, false);
    }

    @Override public void onLiveLevel(float level) { orb.setLevel(level); }

    @Override public void onLiveError(String message) {
        TextView t = addMessage("assistant", describeLive(message), System.currentTimeMillis(), null);
        t.setTextColor(Ui.RED);
    }

    @Override public void onLiveEnded(String reason) {
        live = null;
        liveBubble = null;
        finishTurn();
        if ("idle".equals(reason)) status.setText("నిశ్శబ్దంగా ఉంది, Live సంభాషణ ఆపేశాను");
    }

    private String describeLive(String m) {
        String low = String.valueOf(m).toLowerCase(Locale.ROOT);
        String te;
        if (low.contains("401") || low.contains("api key") || low.contains("api_key"))
            te = "OpenAI key పనిచేయడం లేదు. సెట్టింగ్స్‌లో OpenAI key చెక్ చేయండి.";
        else if (low.contains("quota") || low.contains("billing") || low.contains("insufficient") || low.contains("credit"))
            te = "OpenAI అకౌంట్‌లో బ్యాలెన్స్ అయిపోయింది. క్రెడిట్ జోడించండి.";
        else if (low.contains("model") || low.contains("403") || low.contains("404"))
            te = "Live మోడల్ \"" + prefs.realtimeModel() + "\" పనిచేయలేదు. సెట్టింగ్స్‌లో మోడల్ మార్చండి లేదా Live ఆఫ్ చేయండి.";
        else if (low.contains("unable to resolve host") || low.contains("failed to connect") || low.contains("timeout"))
            te = "ఇంటర్నెట్ కనెక్షన్ సమస్య. నెట్ చెక్ చేసి మళ్లీ ప్రయత్నించండి.";
        else if (low.contains("మైక్") || low.contains("mic"))
            te = "మైక్ తెరవలేకపోయాను. వేరే యాప్ మైక్ వాడుతుంటే మూసేసి మళ్లీ ప్రయత్నించండి.";
        else te = "Live సంభాషణలో సమస్య వచ్చింది.";
        return te + "\n(" + (m.length() > 180 ? m.substring(0, 180) : m) + ")";
    }

    private void startListening() {
        if (busy) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!voice.canListen()) {
            Toast.makeText(this, "ఈ ఫోన్‌లో Google వాయిస్ టైపింగ్ లేదు. Google యాప్ ఇన్‌స్టాల్/అప్‌డేట్ చేయండి.", Toast.LENGTH_LONG).show();
            finishTurn();
            return;
        }
        inConversation = true;
        WakeService.pause(this);
        showTab(0);
        voice.listen(prefs.listenLang());
        orb.setState(OrbView.LISTENING);
        status.setText("వింటున్నాను…");
        input.setHint("వింటున్నాను…");
        refreshAction();
    }

    @Override public void onListening() {
        status.setText("వింటున్నాను… మాట్లాడండి");
    }

    @Override public void onPartial(String text) {
        input.setText(text);
        input.setSelection(input.getText().length());
    }

    @Override public void onHeard(String text) {
        input.setHint("Jarvis తో మాట్లాడండి…");
        input.setText("");
        if (text == null || text.trim().isEmpty()) { finishTurn(); return; }
        send(text.trim(), null, true);
    }

    @Override public void onListenFailed(int error) {
        input.setHint("Jarvis తో మాట్లాడండి…");
        String partial = input.getText().toString().trim();
        if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && !partial.isEmpty()) {
            input.setText("");
            send(partial, null, true);
            return;
        }
        input.setText("");
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                Toast.makeText(this, "వాయిస్‌కి ఇంటర్నెట్ కావాలి", Toast.LENGTH_SHORT).show();
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                Toast.makeText(this, "మైక్ బిజీగా ఉంది, మళ్లీ నొక్కండి", Toast.LENGTH_SHORT).show();
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                break;
            case 12: // ERROR_LANGUAGE_NOT_SUPPORTED
            case 13: // ERROR_LANGUAGE_UNAVAILABLE
                Toast.makeText(this, "తెలుగు వాయిస్ టైపింగ్ లేదు. Google యాప్ → Settings → Voice → Languages లో తెలుగు జోడించండి.", Toast.LENGTH_LONG).show();
                break;
            default:
                break;
        }
        finishTurn();
    }

    @Override public void onLevel(float level) { orb.setLevel(level); }

    @Override public void onSpeakStart() {
        orb.setState(OrbView.SPEAKING);
        status.setText("మాట్లాడుతున్నాను…");
        refreshAction();
    }

    @Override public void onSpeakDone() {
        if (lastWasVoice && prefs.followUp() && !paused && !busy) {
            lastWasVoice = false;
            main.postDelayed(this::startListening, 250);
        } else {
            finishTurn();
        }
    }

    private TextView thinkingView;
    private View thinkingWrap;

    private void removeThinking() {
        if (thinkingWrap != null) chatList.removeView(thinkingWrap);
        thinkingWrap = null;
        thinkingView = null;
    }

    private void send(String prompt, String label, boolean byVoice) {
        if (busy) return;
        String shown = label != null ? label : prompt;
        String photoNow = pendingPhoto;
        // With the live camera open, every question carries the current camera picture.
        if (photoNow == null && camera != null && camera.isOpen()) photoNow = CameraPanel.latestFrame;
        final String photo = photoNow;
        final Bitmap thumb = pendingThumb;
        if ((shown == null || shown.isEmpty()) && photo == null) return;
        if (shown == null || shown.isEmpty()) shown = "ఈ ఫోటోలో ఏముందో చెప్పు.";
        final String ask = prompt == null || prompt.isEmpty() ? shown : prompt;

        if (!prefs.hasBrain()) {
            addMessage("assistant", "క్షమించండి " + prefs.name() + ", నా మెదడుకి ఇంకా API key లేదు. సెట్టింగ్స్‌లో పెట్టండి.", System.currentTimeMillis(), null);
            updateSetup();
            finishTurn();
            return;
        }
        clearAttachment();
        showTab(0);
        List<JSONObject> history = store.chat();
        store.addChat("user", shown, photo != null);
        addMessage("user", shown, System.currentTimeMillis(), thumb);
        thinkingView = addMessage("assistant", "ఆలోచిస్తున్నాను…", System.currentTimeMillis(), null);
        thinkingView.setTextColor(Ui.MUTED);
        thinkingWrap = (View) thinkingView.getParent();

        lastWasVoice = byVoice;
        busy = true;
        inConversation = true;
        WakeService.pause(this);
        orb.setState(OrbView.THINKING);
        status.setText("ఆలోచిస్తున్నాను…");
        refreshAction();

        final int gen = ++generation;
        worker.submit(() -> {
            String reply = null, error = null;
            try {
                reply = brain.ask(history, ask, photo, s -> main.post(() -> {
                    if (gen != generation) return;
                    status.setText(s);
                    if (thinkingView != null) thinkingView.setText(s);
                }));
            } catch (Http.ApiError e) {
                error = describe(e);
            } catch (UnknownHostException e) {
                error = "ఇంటర్నెట్ కనెక్షన్ లేదు. నెట్ ఆన్ చేసి మళ్లీ అడగండి.";
            } catch (java.net.SocketTimeoutException e) {
                error = "సమాధానం రావడానికి చాలా ఆలస్యం అయింది. మళ్లీ అడగండి.";
            } catch (Exception e) {
                error = "ఏదో తప్పు జరిగింది: " + e.getMessage();
            }
            final String r = reply, err = error;
            main.post(() -> onReply(gen, r, err));
        });
    }

    private void onReply(int gen, String reply, String error) {
        if (gen != generation) return; // Anil stopped this one
        busy = false;
        removeThinking();
        if (error != null) {
            TextView t = addMessage("assistant", error, System.currentTimeMillis(), null);
            t.setTextColor(Ui.RED);
            finishTurn();
            return;
        }
        store.addChat("assistant", reply, false);
        addMessage("assistant", reply, System.currentTimeMillis(), null);
        if (prefs.voiceReplies() && !paused) {
            voice.speak(reply, prefs.speechRate());
            orb.setState(OrbView.SPEAKING);
            refreshAction();
        } else if (prefs.voiceReplies()) {
            voice.speak(reply, prefs.speechRate()); // keeps talking if another app was opened
            lastWasVoice = false;
        } else {
            finishTurn();
        }
    }

    private String describe(Http.ApiError e) {
        String m = String.valueOf(e.getMessage());
        String low = m.toLowerCase(Locale.ROOT);
        String te;
        if (e.status == 401 || e.status == 403) te = "API key పనిచేయడం లేదు. సెట్టింగ్స్‌లో key సరిగ్గా పెట్టారో చెక్ చేయండి.";
        else if (low.contains("credit") || low.contains("quota") || low.contains("billing") || low.contains("balance"))
            te = "API అకౌంట్‌లో బ్యాలెన్స్ అయిపోయింది. OpenAI/Anthropic అకౌంట్‌లో క్రెడిట్ జోడించండి.";
        else if (e.status == 429) te = "చాలా ఎక్కువ ప్రశ్నలు ఒకేసారి. కొంచెం ఆగి అడగండి.";
        else if ((e.status == 400 || e.status == 404) && low.contains("model"))
            te = "మోడల్ పేరు \"" + prefs.model() + "\" పనిచేయలేదు. సెట్టింగ్స్‌లో మోడల్ మార్చండి.";
        else if (e.status >= 500) te = "సర్వర్ బిజీగా ఉంది. కాసేపటి తర్వాత మళ్లీ అడగండి.";
        else if (e.status == 0) te = "సమాధానం రాలేదు. మళ్లీ అడగండి.";
        else te = "పొరపాటు జరిగింది (" + e.status + ").";
        return te + "\n(" + (m.length() > 180 ? m.substring(0, 180) : m) + ")";
    }

    private void finishTurn() {
        if (busy || live != null) return;
        setIdle();
        inConversation = false;
        if (prefs.wakeReady()) WakeService.resume(this);
    }

    private void setIdle() {
        orb.setState(prefs.hasBrain() ? OrbView.IDLE : OrbView.OFFLINE);
        status.setText(prefs.hasBrain() ? "సిద్ధంగా ఉన్నాను, " + prefs.name() : "మెదడు ఆఫ్‌లైన్: API key కావాలి");
        input.setHint("Jarvis తో మాట్లాడండి…");
        refreshAction();
    }

    private void beep() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
            main.postDelayed(tg::release, 400);
        } catch (Exception ignored) {}
    }

    // ================================================================ photos

    private void pickPhoto() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("ఫోటో")
                .setItems(new String[]{"కెమెరాతో తీయండి", "గ్యాలరీ నుంచి ఎంచుకోండి"}, (d, which) -> {
                    if (which == 0) openCamera(); else openGallery();
                })
                .show();
    }

    private void openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PHOTO_CAM);
            return;
        }
        File f = PhotoProvider.file(this);
        if (f.exists()) f.delete();
        Uri out = PhotoProvider.uri();
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, out);
        i.setClipData(ClipData.newRawUri("photo", out));
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            Toast.makeText(this, "కెమెరా యాప్ తెరవలేకపోయాను. గ్యాలరీ నుంచి ఎంచుకోండి.", Toast.LENGTH_LONG).show();
        }
    }

    private void openGallery() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "ఫోటో ఎంచుకోండి"), REQ_GALLERY);
        } catch (Exception e) {
            Toast.makeText(this, "గ్యాలరీ తెరవలేకపోయాను", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK) return;
        final Uri uri;
        if (req == REQ_CAMERA) uri = PhotoProvider.uri();
        else if (req == REQ_GALLERY && data != null && data.getData() != null) uri = data.getData();
        else return;
        worker.submit(() -> {
            try {
                Bitmap bmp = loadScaled(uri, 1280);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
                String b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                Bitmap thumb = Bitmap.createScaledBitmap(bmp, Math.max(1, bmp.getWidth() * 360 / Math.max(bmp.getWidth(), bmp.getHeight())),
                        Math.max(1, bmp.getHeight() * 360 / Math.max(bmp.getWidth(), bmp.getHeight())), true);
                main.post(() -> {
                    pendingPhoto = b64;
                    pendingThumb = thumb;
                    attachThumb.setImageBitmap(thumb);
                    attachRow.setVisibility(View.VISIBLE);
                    input.setHint("ఫోటో గురించి ఏం అడగాలి? (ఖాళీగా పంపితే వివరిస్తాను)");
                    showTab(0);
                    refreshAction();
                });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "ఫోటో తెరవలేకపోయాను", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private Bitmap loadScaled(Uri uri, int maxSide) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = getContentResolver().openInputStream(uri)) { bmp = BitmapFactory.decodeStream(in, null, o2); }
        if (bmp == null) throw new IllegalStateException("decode failed");
        int rotate = 0;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) rotate = 90;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rotate = 180;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rotate = 270;
        } catch (Exception ignored) {}
        float scale = Math.min(1f, (float) maxSide / Math.max(bmp.getWidth(), bmp.getHeight()));
        if (rotate != 0 || scale < 1f) {
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postRotate(rotate);
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        }
        return bmp;
    }

    private void clearAttachment() {
        pendingPhoto = null;
        pendingThumb = null;
        attachRow.setVisibility(View.GONE);
        input.setHint("Jarvis తో మాట్లాడండి…");
        refreshAction();
    }

    // ================================================================ Tools.Host

    @Override public Activity activity() { return this; }

    @Override public boolean confirm(String title, String message, String yes, int autoSeconds) {
        if (isFinishing()) return false;
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);
        runOnUiThread(() -> {
            AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(yes, (x, w) -> ok.set(true))
                    .setNegativeButton("వద్దు", (x, w) -> ok.set(false))
                    .setOnDismissListener(x -> done.countDown())
                    .create();
            d.show();
            if (autoSeconds > 0) {
                final int[] left = {autoSeconds};
                Runnable[] step = new Runnable[1];
                step[0] = () -> {
                    if (!d.isShowing()) return;
                    if (left[0] <= 0) { ok.set(true); d.dismiss(); return; }
                    Button b = d.getButton(AlertDialog.BUTTON_POSITIVE);
                    if (b != null) b.setText(yes + " (" + left[0] + ")");
                    left[0]--;
                    main.postDelayed(step[0], 1000);
                };
                step[0].run();
            }
        });
        try {
            if (!done.await(90, TimeUnit.SECONDS)) return false;
        } catch (InterruptedException e) {
            return false;
        }
        return ok.get();
    }

    @Override public void askPermissions(String[] permissions) {
        runOnUiThread(() -> requestPermissions(permissions, REQ_PERMS));
    }

    @Override public void notice(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }
}
