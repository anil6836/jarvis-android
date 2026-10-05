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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity implements Tools.Host, VoiceIO.Listener, Store.Listener, LiveSession.Listener, LiveScreen.Actions {
    /** The theme this screen was built with (a change in Settings rebuilds it). */
    private int builtTheme;
    static final String EXTRA_WAKE = "wake";
    static final String EXTRA_BRIEF = "brief";
    /** Opened from a notification with a question to ask Jarvis right away. */
    static final String EXTRA_ASK = "jarvis_ask";
    /** From the features screen: what to show as his message for EXTRA_ASK, a request to finish typing, an action. */
    static final String EXTRA_LABEL = "jarvis_label", EXTRA_FILL = "jarvis_fill", EXTRA_DO = "jarvis_do";
    /** Open the document scanner (from the scan_document tool, possibly started from the Hey Jarvis panel). */
    static final String EXTRA_SCAN = "jarvis_scan";
    /** True while the Jarvis screen is in front (then a fresh screenshot would only show Jarvis). */
    static volatile boolean visible;
    private static final String BRIEF_PROMPT = "నాకు ఇప్పటి బ్రీఫింగ్ ఇవ్వు: సమయానికి తగ్గ పలకరింపు, ఈరోజు తేదీ, నా లొకేషన్‌లో వాతావరణం (get_weather వాడు), ఈరోజు క్యాలెండర్, రిమైండర్లు, నా యాక్టివ్ మిషన్లలో ముఖ్యమైనవి, బ్యాటరీ తక్కువగా ఉంటే అది కూడా. 6 వాక్యాలు మించకుండా.";
    private static final int REQ_CAMERA_PERM = 24, REQ_PHOTO_CAM = 25, REQ_FACE_CAM = 26;
    private CameraPanel camera;
    private FrameLayout cameraBox;
    private LinearLayout reminderList;
    private TextView reminderLabel;
    /** True from the moment Anil starts talking until Jarvis has finished answering. Set it with talking(). */
    static volatile boolean inConversation;
    private static volatile long talkingSince;
    /** A Live (real-time) talk is running on the main screen (it goes on while the screen is minimised). */
    static volatile boolean liveOn;

    static void talking(boolean on) {
        if (on) Rest.awake();
        if (on && !inConversation) talkingSince = System.currentTimeMillis();
        inConversation = on;
    }

    /**
     * True while Anil and Jarvis are really talking: then the wake word, message read-outs and Jarvis's own
     * remarks wait. A flag left on by a talk that ended without saying so would keep them silent for good,
     * so after 3 minutes with no Jarvis screen, panel or Live open it is let go.
     */
    static boolean busyTalking() {
        if (JarvisCamera.open) return true; // the camera is open: no panel or remark over it (its mic: WakeService.micBusy)
        if (SheetActivity.talkingNow()) return true; // the panel is talking, even if another screen cleared the flag
        if (TopCard.talkingNow()) return true; // a message card is talking with him (however long)
        if (!inConversation) return false;
        if (visible || liveOn || SheetActivity.open) return true;
        if (System.currentTimeMillis() - talkingSince < 3 * 60_000L) return true;
        talking(false);
        return false;
    }

    private static final int REQ_CAMERA = 11, REQ_GALLERY = 12, REQ_FILE = 13, REQ_PERMS = 21, REQ_MIC = 22, REQ_LIVE = 23;

    private Prefs prefs;
    private Store store;
    private Tools tools;
    private Brain brain;
    private VoiceIO voice;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private HoloOrb orb;               // the hologram core in the header (same states as OrbView)
    private FaceView face;             // Jarvis's face on the home screen (Settings → Jarvis ముఖం)
    private FrameLayout faceBox;
    private TextView faceNote;         // 👁 while the front camera is watching
    private FaceSight sight;           // the front camera (only while the face is on the screen)
    private boolean sightPaused;       // it stopped by itself (no one for 10 minutes): waits for a tap or the next visit
    private boolean keyboardUp;
    private int tab;                   // the tab on the screen
    private FrameLayout stageView;     // the chat panels (keep at least ~190dp for them)
    private int faceRoom = -1;         // the face's height that leaves the chat its room (px); 0 = no room, -1 = not measured yet
    private boolean askingFace;        // the one-time camera question is on the screen
    private String feelingNext;        // the feeling for the next thing Jarvis says (his greeting)
    private static final java.util.Set<String> sightErrorsShown = new java.util.HashSet<>();
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener faceKeys =
            (sp, key) -> { if (key != null && key.startsWith("face_")) main.post(this::updateFace); };
    /** Added to a question that carries his front-camera picture. */
    private static final String SEE_ME = "\n\n(Attached: a live front-camera picture of whoever is talking to you right now, usually Anil. It is how you see him: "
            + "use it only when it matters (how he looks or feels, something he shows you, or when he asks about it); do not describe it otherwise.)";
    private TextView greeting;
    private HudDashboard hudDash;      // status tiles under the header (chat tab only)
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
    private TextView updateBanner;                                  // "new version" note (never downloads by itself)
    private final Karaoke karaoke = new Karaoke();                 // highlights the word being spoken
    private final List<TextView> jarvisBodies = new ArrayList<>();   // Jarvis's reply bubbles, oldest first
    private FrameLayout pauseBtn;   // ⏸/▶ while Jarvis is speaking (like Google Assistant)
    private IconView pauseIcon;
    private IconView actionIcon;

    private String pendingPhoto;       // base64 JPEG (or "pdf:..." for a PDF) waiting to be sent
    /** Sent by itself as soon as the photo is attached (the 🧾 bill chip). */
    private String autoPrompt;
    private static final String BILL_PROMPT = "ఈ ఫోటో ఒక బిల్లు / రసీదు. కట్టిన మొత్తం (Grand Total, GST తో), షాప్ పేరు, బిల్లు తేదీ చదివి, "
            + "కేటగిరీ ఎంచుకుని add_expense తో నా ఖర్చుల్లో ఒక్కసారి చేర్చు. మొత్తం స్పష్టంగా కనిపించకపోతే చేర్చకుండా నన్ను అడుగు. చేర్చాక ఏం చేర్చావో ఒక వాక్యంలో చెప్పు.";
    private static final String EXPIRY_PROMPT = "ఈ ఫోటో ఒక డాక్యుమెంట్ (ఇన్సూరెన్స్ / డ్రైవింగ్ లైసెన్స్ / PUC / RC / పాలసీ / వారంటీ లాంటిది). "
            + "అది ఏ డాక్యుమెంట్, దాని గడువు తేదీ (valid till / expiry / valid upto) చదివి expiry add తో చేర్చు: what = తెలుగులో చిన్న పేరు (ఉదా: 'బైక్ ఇన్సూరెన్స్'), date = YYYY-MM-DD. "
            + "పాలసీ / లైసెన్స్ / ఆధార్ నంబర్లు ఏవీ సేవ్ చేయకు, చెప్పకు. తేదీ స్పష్టంగా కనిపించకపోతే చేర్చకుండా నన్ను అడుగు. చేర్చాక ఒక వాక్యంలో చెప్పు.";
    private static final String WARRANTY_PROMPT = "ఈ ఫోటో ఒక వస్తువు కొన్న బిల్లు (TV, ఫ్రిజ్, ఫోన్, మిక్సీ లాంటిది). వస్తువు పేరు (బ్రాండ్, మోడల్), షాప్, కొన్న తేదీ, మొత్తం, "
            + "వారంటీ ఎన్ని నెలలు (బిల్లు మీద ఉంటే) చదివి expiry add_warranty తో చేర్చు: what = తెలుగులో చిన్న పేరు (ఉదా: 'Samsung TV'), bought = YYYY-MM-DD, months. "
            + "వారంటీ కాలం బిల్లు మీద లేకపోతే చేర్చకుండా నన్ను అడుగు. సీరియల్ / IMEI నంబర్లు సేవ్ చేయకు. చేర్చాక ఒక వాక్యంలో చెప్పు, ఖర్చుల్లో కూడా చేర్చాలా అని అడుగు.";
    private static final String HOMEWORK_PROMPT = "ఈ ఫోటోలో పిల్లల హోంవర్క్ / ప్రశ్న ఉంది. జవాబు మాత్రమే చెప్పకు: పిల్లలకి అర్థమయ్యేలా సరళమైన తెలుగులో "
            + "ఒక్కొక్క స్టెప్ నేర్పించు (లెక్క అయితే ప్రతి స్టెప్ ఎందుకో చెప్పు; ఇంగ్లీష్ / సైన్స్ అయితే అర్థం, ఉదాహరణ). చివర్లో ఇలాంటిదే ఒక చిన్న ప్రాక్టీస్ ప్రశ్న ఇవ్వు, జవాబు వాళ్లనే చెప్పమను.";
    private static final String PLANT_PROMPT = "ఈ ఫోటోలో ఒక మొక్క / పంట / ఆకు ఉంది. ఏ మొక్కో, ఏ తెగులు / పురుగు / పోషక లోపం కావచ్చో చెప్పు (ఖచ్చితంగా తెలియకపోతే అలాగే చెప్పు). "
            + "ముందు సహజ పద్ధతులు (వేప నూనె, కుళ్లిన ఆకులు తీసేయడం, నీళ్లు, ఎండ), తర్వాత అవసరమైతే మందు పేరు, లేబుల్ మీద మోతాదు ప్రకారం, గ్లోవ్స్ / మాస్క్ జాగ్రత్తలతో. "
            + "పొలం అయితే దగ్గర్లోని రైతు వేదిక / వ్యవసాయ అధికారిని (AEO) సంప్రదించమని చెప్పు. చిన్నగా, తెలుగులో.";
    private static final String CARD_PROMPT = "ఈ ఫోటో ఒక విజిటింగ్ కార్డ్. పేరు, ఫోన్ నంబర్(లు), ఈమెయిల్, కంపెనీ, హోదా, అడ్రస్ చదివి చిన్నగా చెప్పు, "
            + "తర్వాత save_contact తో కాంటాక్ట్స్ యాప్‌లో సేవ్ ఫారం తెరువు (నేను చూసి సేవ్ నొక్కుతాను). ఏదైనా స్పష్టంగా లేకపోతే ఆ వివరం వదిలేయి.";
    /** A medical test report (photo or PDF) explained simply. */
    private static final String REPORT_PROMPT = "ఇది నా మెడికల్ టెస్ట్ రిపోర్ట్ (బ్లడ్, షుగర్, థైరాయిడ్, లివర్, కిడ్నీ, కొలెస్ట్రాల్ లాంటిది). సరళమైన తెలుగులో చెప్పు: "
            + "ముందు రిపోర్ట్‌లో ఇచ్చిన నార్మల్ రేంజ్ కంటే ఎక్కువ / తక్కువ ఉన్నవి, తర్వాత మిగతావి నార్మల్ అని ఒక్క మాటలో. ప్రతి టెస్ట్‌కి: పేరు తెలుగులో (అది దేని గురించి), "
            + "విలువ, నార్మల్ / ఎక్కువ / తక్కువ; రిపోర్ట్‌లో రేంజ్ లేకపోతే సాధారణ రేంజ్ అని చెప్పి వాడు. తేడా ఉన్నవాటికి అర్థం ఒక్క వాక్యంలో, భయపెట్టకుండా. "
            + "చివర్లో డాక్టర్‌ని అడగాల్సిన 2-3 ప్రశ్నలు. మందుల పేర్లు, మోతాదులు చెప్పకు; ఖచ్చితమైన నిర్ధారణ డాక్టర్‌దే అని ఒక్క మాట. "
            + "రిపోర్ట్ 3 రోజుల లోపలిదై షుగర్ లేదా BP విలువ ఉంటే 'ఇది మీ రిపోర్టేనా? మీ రీడింగ్స్‌లో రాసుకోనా?' అని అడిగి, అవును అంటేనే health_log add తో రాసుకో. పేరు, వయసు, ఫోన్, ID / సాంపిల్ నంబర్లు చదవకు, ఎక్కడా రాయకు. "
            + "చదవలేని భాగం ఉంటే అలాగే చెప్పు, ఊహించకు.";
    /** The label shown for an automatic photo / file request (null = the bill's). */
    private String autoLabel;
    /** When the chip was tapped: its request only goes with a photo / file picked within a few minutes. */
    private long autoAt;

    /** The chip's request, or null when there is none or it is old (abandoned somewhere: a denied camera, a file too big). */
    private String takeAuto() {
        String p = autoPrompt;
        autoPrompt = null;
        if (p != null && System.currentTimeMillis() - autoAt > 5 * 60_000L) { autoLabel = null; return null; }
        return p;
    }
    private Bitmap pendingThumb;
    private String pendingFileText;    // a text file's contents waiting to be sent
    private String pendingFileName;    // name of the attached file (PDF or text), or null
    private TextView attachText;
    private boolean busy;
    private boolean paused;
    private boolean lastWasVoice;
    /** This listen is the optional one after an answer: silence there just ends the talk. */
    private boolean followListen;
    private volatile int generation;   // increases with every request, so a stopped answer is ignored (and its tools stop)
    private Runnable pendingUndo;
    private LiveSession live;          // an open real-time voice conversation, or null
    private TextView liveBubble;       // Jarvis's reply while it is still being spoken
    private LiveScreen liveScreen;     // the full-screen Live view (hologram core, mute, end)
    private IconView micIcon;          // the mic inside the message box: speak instead of typing

    // ================================================================ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.loadTheme(this); // the chosen colours, before anything is built
        builtTheme = Ui.themeVersion;
        setVolumeControlStream(AudioManager.STREAM_MUSIC); // volume keys = Jarvis's voice, also during talk-over call mode
        prefs = new Prefs(this);
        store = Store.get(this);
        store.listener = this;
        tools = new Tools(this, store, prefs);
        brain = new Brain(prefs, store, tools);
        voice = new VoiceIO(this, prefs, this);
        setContentView(buildUi());
        getSharedPreferences("jarvis", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(faceKeys);
        getWindow().setStatusBarColor(Ui.BG_TOP);
        getWindow().setNavigationBarColor(Ui.BG_BOTTOM);
        renderChat();
        onStoreChanged();
        tick();
        handleIntent(getIntent());
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (sight != null && sight.isOn()) { sight.stopAndWait(700); updateFace(); } // the front camera's picture turned with the phone
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        TopCard.stepAside(); // a message card talking at the top stops: this screen talks now
        Ui.loadTheme(this);
        if (builtTheme != Ui.themeVersion) { recreate(); return; } // the theme changed while this screen was open
        paused = false;
        visible = true;
        main.removeCallbacks(cancelIfAway);
        store.listener = this;
        // Opening Jarvis switches the wake word back on after "ఆపు" in the notification.
        if (prefs.wakePaused()) prefs.setWakePaused(false);
        Reminders.scheduleBriefing(this);
        Proactive.schedule(this);
        Faith.schedule(this); // the morning verse / church reminder
        SoundService.restoreAfterPrayer(this);
        JarvisWidget.refresh(this);
        orb.invalidate();
        updateSetup();
        if (live == null && !busy && !voice.listening && !voice.speaking) setIdle();
        if (live == null && !busy) renderChat();
        syncWakeService();
        if (hudDash != null) hudDash.start();
        UpdateJob.schedule(this); // "new version" notification when a build is out
        BackupJob.schedule(this); // the daily Drive backup (once he has set it up)
        String restored = Backup.takeRestoreNote(this); // just restarted after bringing a backup back
        if (restored != null) new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("♻️ బ్యాకప్").setMessage(restored)
                .setPositiveButton("సెట్టింగ్స్ తెరువు", (d, w) -> startActivity(new Intent(this, SettingsActivity.class)))
                .setNegativeButton("సరే", null).show();
        cancelOldBackupJob();
        NotifyListener.ensureBound(this); // Android has notification access for Jarvis but stopped sending messages: reconnect
        if (FindPhone.running() && FindPhone.age() > 10000) FindPhone.stop(this); // found it (not the ring he just asked for)
        MedicalId.update(this);
        Life.endNightIfMorning(this);     // a night mode left on from last night
        new Thread(() -> Updater.cleanup(getApplicationContext()), "jarvis-cleanup").start();
        showUpdateBanner();
        Updater.lookSoon(this, this::showUpdateBanner);
        sightPaused = false;
        updateFace();
    }

    /** "New version" note at the top of the chat; tapping it opens the update in Settings. */
    private void showUpdateBanner() {
        if (updateBanner == null || isFinishing()) return;
        boolean show = Updater.newAvailable(this);
        updateBanner.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) updateBanner.setText("⬆  Jarvis కొత్త వెర్షన్ 1.0." + Updater.knownLatest(this) + " వచ్చింది. అప్డేట్ చేయడానికి నొక్కండి");
    }

    @Override protected void onPause() {
        super.onPause();
        if (hudDash != null) hudDash.stop();
        paused = true;
        visible = false;
        if (sight != null) sight.stop(); // the front camera only watches while Jarvis is on the screen
        if (camera != null && camera.isOpen()) {
            camera.close();
            cameraBox.setVisibility(View.GONE);
        }
        // Not at once: shown over the lock screen, Android pauses Jarvis for a moment while the screen lights up,
        // and that must not cut the mic. Still away a moment later: stop listening.
        main.removeCallbacks(cancelIfAway);
        main.postDelayed(cancelIfAway, 900);
    }

    private final Runnable cancelIfAway = () -> {
        if (paused && voice.listening) {
            voice.cancelListening();
            finishTurn();
        }
    };

    @Override protected void onStop() {
        super.onStop();
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(false);
    }

    @Override protected void onDestroy() {
        Radio.dismissPicker();
        generation++; // late replies (onReply) are dropped and the Brain stops running tools
        if (store.listener == this) store.listener = null;
        if (live != null) live.stop("destroy");
        liveOn = false;
        if (camera != null) camera.close();
        if (sight != null) sight.stop();
        FaceSight.wake = null;
        getSharedPreferences("jarvis", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(faceKeys);
        voice.shutdown();
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        if (!SheetActivity.open) { // the panel may be talking and listening right now: its talk, its mic
            talking(false);
            if (prefs.wakeReady()) WakeService.resume(this); // it was paused while Anil and Jarvis talked
        }
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
                talking(true);
                main.postDelayed(this::startConversation, 450);
            }
            return;
        }
        String installRepo = i.getStringExtra(AppMaker.EXTRA_INSTALL);
        if (installRepo != null) { // tapped "<app> యాప్ సిద్ధం"
            i.removeExtra(AppMaker.EXTRA_INSTALL);
            main.postDelayed(() -> AppMaker.install(this, installRepo), 400);
            return;
        }
        if (i.getBooleanExtra(EXTRA_SCAN, false)) {
            i.removeExtra(EXTRA_SCAN);
            main.postDelayed(() -> Scanner.start(this), 400);
            return;
        }
        String fillNow = i.getStringExtra(EXTRA_FILL);
        if (fillNow != null) {
            i.removeExtra(EXTRA_FILL);
            main.postDelayed(() -> prefill(fillNow), 350);
            return;
        }
        String doNow = i.getStringExtra(EXTRA_DO);
        if (doNow != null) {
            i.removeExtra(EXTRA_DO);
            main.postDelayed(() -> doAction(doNow), 350);
            return;
        }
        String askNow = i.getStringExtra(EXTRA_ASK);
        if (askNow != null) {
            String label = i.getStringExtra(EXTRA_LABEL);
            i.removeExtra(EXTRA_ASK);
            i.removeExtra(EXTRA_LABEL);
            main.postDelayed(() -> askFromOutside(askNow, label), 500);
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
        talking(true);
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
        p.add(Manifest.permission.ANSWER_PHONE_CALLS);
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        p.add(Manifest.permission.READ_CALL_LOG);
        p.add(Manifest.permission.ACTIVITY_RECOGNITION);
        p.add(Manifest.permission.READ_SMS);
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.READ_MEDIA_VIDEO);
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.READ_MEDIA_AUDIO); // songs saved on the phone
        if (Build.VERSION.SDK_INT >= 31) p.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        return p.toArray(new String[0]);
    }

    /** Core permissions still missing. */
    private List<String> missingPermissionList() {
        // Android 14 "Select photos and videos" grants only READ_MEDIA_VISUAL_USER_SELECTED: that is his
        // choice, so photos/videos count as answered (asking again would never make the card go away).
        boolean partialMedia = Build.VERSION.SDK_INT >= 34
                && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
        List<String> out = new ArrayList<>();
        for (String p : corePermissions()) {
            if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) continue;
            if (partialMedia && (Manifest.permission.READ_MEDIA_VIDEO.equals(p) || Manifest.permission.READ_MEDIA_IMAGES.equals(p))) continue;
            out.add(p);
        }
        return out;
    }

    private boolean missingPermissions() {
        return !missingPermissionList().isEmpty();
    }

    /** Permissions Android no longer shows a dialog for ("Don't allow" twice / "don't ask again"). */
    private java.util.Set<String> blockedPermissions() {
        try {
            return new java.util.HashSet<>(getPreferences(MODE_PRIVATE).getStringSet("perm_blocked", new java.util.HashSet<>()));
        } catch (Exception e) {
            return new java.util.HashSet<>();
        }
    }

    /** The setup card: ask for what can still be asked; if Android won't ask any more, open Jarvis's app settings. */
    private void askCorePermissions() {
        java.util.Set<String> blocked = blockedPermissions();
        List<String> ask = new ArrayList<>();
        for (String p : missingPermissionList()) {
            if (!blocked.contains(p) || shouldShowRequestPermissionRationale(p)) ask.add(p);
        }
        if (!ask.isEmpty()) {
            requestPermissions(ask.toArray(new String[0]), REQ_PERMS);
            return;
        }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
            Toast.makeText(this, "అనుమతులు (Permissions) తెరిచి Allow ఇవ్వండి", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Settings → Apps → Jarvis → Permissions లో Allow ఇవ్వండి", Toast.LENGTH_LONG).show();
        }
    }

    /** Remembers which permissions were refused without a dialog being possible any more. */
    private void notePermissionResults(String[] perms, int[] results) {
        java.util.Set<String> blocked = blockedPermissions();
        boolean changed = false;
        for (int i = 0; i < perms.length && i < results.length; i++) {
            boolean nowBlocked = results[i] != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(perms[i]);
            changed |= nowBlocked ? blocked.add(perms[i]) : blocked.remove(perms[i]);
        }
        if (changed) {
            try { getPreferences(MODE_PRIVATE).edit().putStringSet("perm_blocked", blocked).apply(); } catch (Exception ignored) {}
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (perms != null && results != null) notePermissionResults(perms, results);
        updateSetup();
        if (code == REQ_CAMERA_PERM && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) toggleCamera();
        if (code == REQ_PHOTO_CAM && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera();
        if (code == REQ_FACE_CAM) updateFace();
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
        root.setPadding(dp(16), 0, dp(16), 0);

        // ---- header: the hologram core, a greeting, what Jarvis is doing, settings
        LinearLayout hud = new LinearLayout(this);
        hud.setGravity(Gravity.CENTER_VERTICAL);
        hud.setPadding(0, dp(10), 0, dp(10));
        orb = new HoloOrb(this, 70);
        orb.setContentDescription("Jarvis");
        orb.setOnClickListener(v -> {
            if (live != null) liveScreen.show();
            else if (busy || voice.speaking || voice.listening) onActionPressed();
            else startConversation(); // like "Hey Jarvis": Live when it is switched on, else listen
        });
        hud.addView(orb, new LinearLayout.LayoutParams(dp(78), dp(78)));

        LinearLayout ident = new LinearLayout(this);
        ident.setOrientation(LinearLayout.VERTICAL);
        ident.setPadding(dp(10), 0, dp(8), 0);
        TextView brand = Ui.mono(this, "JARVIS", 12.5f, Ui.C_CYAN);
        brand.setLetterSpacing(0.42f);
        Ui.gradientText(brand, Ui.C_CYAN, Ui.C_VIOLET);
        ident.addView(brand);
        greeting = Ui.text(this, "", 21, 0xFFFFFFFF);
        greeting.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        greeting.setSingleLine(true);
        greeting.setEllipsize(TextUtils.TruncateAt.END);
        ident.addView(greeting);
        status = Ui.text(this, "", 13.5f, Ui.MUTED);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        ident.addView(status);
        hud.addView(ident, new LinearLayout.LayoutParams(0, -2, 1));
        clock = Ui.text(this, "", 12, Ui.MUTED);    // (time and date live in the status tiles now)
        dateView = Ui.text(this, "", 12, Ui.MUTED);

        IconView camIcon = new IconView(this, IconView.CAMERA, 0xFFFFFFFF); // the Jarvis camera: show it anything and ask
        camIcon.setContentDescription("Jarvis కెమెరా");
        camIcon.setBackground(Ui.glass(this, 22));
        camIcon.setOnClickListener(v -> JarvisCamera.open(this, null, null));
        LinearLayout.LayoutParams camLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        camLp.rightMargin = dp(8);
        hud.addView(camIcon, camLp);

        IconView all = new IconView(this, IconView.GRID, 0xFFFFFFFF);
        all.setContentDescription("అన్ని ఫీచర్లు");
        all.setBackground(Ui.glass(this, 22));
        all.setOnClickListener(v -> startActivity(new Intent(this, FeaturesActivity.class)));
        LinearLayout.LayoutParams allLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        allLp.rightMargin = dp(8);
        hud.addView(all, allLp);

        IconView gear = new IconView(this, IconView.GEAR, 0xFFFFFFFF);
        gear.setContentDescription("సెట్టింగ్స్");
        gear.setBackground(Ui.glass(this, 22));
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        hud.addView(gear, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(hud);

        // ---- tabs: a glass pill; the chosen one glows blue-violet
        LinearLayout tabs = new LinearLayout(this);
        tabs.setBackground(Ui.glass(this, 24));
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        String[] names = {"💬 సంభాషణ", "🎯 మిషన్లు", "🧠 జ్ఞాపకాలు"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout tab = new LinearLayout(this);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, dp(8), 0, dp(8));
            tabLabels[i] = Ui.text(this, names[i], 14.5f, Ui.MUTED);
            tabLabels[i].setSingleLine(true);
            tab.addView(tabLabels[i]);
            if (i > 0) {
                TextView count = Ui.text(this, "0", 11.5f, 0xFFFFFFFF);
                count.setBackground(Ui.round(this, Ui.alpha(i == 1 ? Ui.C_PINK : Ui.C_AMBER, 0xD0), 0, 9));
                count.setPadding(dp(6), 0, dp(6), dp(1));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
                clp.leftMargin = dp(5);
                tab.addView(count, clp);
                if (i == 1) missionCount = count; else memoryCount = count;
            }
            tabLines[i] = tab;
            tab.setOnClickListener(v -> showTab(idx));
            tabs.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(tabs);

        // ---- HUD dashboard: time, battery, network, weather, next reminder, wake word (collapsible)
        hudDash = new HudDashboard(this, prefs);
        LinearLayout.LayoutParams hdlp = new LinearLayout.LayoutParams(-1, -2);
        hdlp.topMargin = dp(10);
        root.addView(hudDash, hdlp);

        // ---- Jarvis's face: talks, feels, looks at him (Settings → Jarvis ముఖం). Chat tab only; hidden while typing.
        face = new FaceView(this);
        face.setContentDescription("Jarvis ముఖం");
        face.setStyle(FaceSight.holo(this));
        faceBox = new FrameLayout(this);
        faceBox.addView(face, new FrameLayout.LayoutParams(-1, -1));
        faceNote = Ui.mono(this, "", 12, Ui.CYAN);
        faceNote.setPadding(dp(8), dp(4), dp(8), dp(4));
        faceBox.addView(faceNote, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        faceBox.setOnClickListener(v -> {
            if (sightPaused && FaceSight.camOn(this)) { sightPaused = false; updateFace(); return; } // asleep after no one came by: look again
            orb.performClick();
        });
        faceBox.setOnLongClickListener(v -> { startActivity(new Intent(this, SettingsActivity.class)); return true; });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, dp(FaceSight.big(this) ? 230 : 160));
        flp.topMargin = dp(6);
        root.addView(faceBox, flp);
        sight = new FaceSight(this, sightListener);
        final View rootView = root;
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> { // the keyboard takes the face's room; the chat keeps its room
            boolean up = rootView.getRootView().getHeight() - rootView.getHeight() > dp(220);
            if (up != keyboardUp) { keyboardUp = up; main.post(this::updateFace); return; }
            int room = faceRoom();
            if (room >= 0 && Math.abs(room - faceRoom) > dp(2)) { faceRoom = room; main.post(this::updateFace); }
        });
        FaceSight.wake = () -> main.post(() -> { sightPaused = false; updateFace(); });

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
        stageView = stage;
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
        updateBanner = Ui.text(this, "", 15, Ui.CYAN);
        updateBanner.setBackground(Ui.round(this, 0x1448D1FF, 0x7348D1FF, 12));
        updateBanner.setPadding(dp(14), dp(12), dp(14), dp(12));
        updateBanner.setVisibility(View.GONE);
        updateBanner.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_UPDATE_NOW, true)));
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(-1, -2);
        blp2.bottomMargin = dp(14);
        chatBox.addView(updateBanner, blp2);
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
        dock.setPadding(0, dp(2), 0, dp(12));

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, dp(8), 0, dp(10));
        // colourful quick actions; the last few start a request for him to finish typing
        addAction(chips, "📷", "Jarvis కెమెరా", () -> JarvisCamera.open(this, null, null));
        addAction(chips, "📂", "అన్ని ఫీచర్లు", () -> startActivity(new Intent(this, FeaturesActivity.class)));
        addChip(chips, "🌅", "శుభోదయం బ్రీఫింగ్", BRIEF_PROMPT);
        addChip(chips, "📰", "వార్తలు", "ఈరోజు ముఖ్యమైన 3 వార్తలు చెప్పు: ఒకటి భారతదేశం, ఒకటి తెలంగాణ లేదా ఆంధ్రప్రదేశ్, ఒకటి టెక్నాలజీ. ఇంటర్నెట్‌లో వెతికి, చిన్నగా చెప్పు.");
        addChip(chips, "📍", "లోకల్ వార్తలు", "నా ప్రాంతాల తాజా వార్తలు తెలుగులో చదివి వినిపించు (local_news).");
        addChip(chips, "⛅", "వాతావరణం", "ఇప్పుడు ఇక్కడ వాతావరణం ఎలా ఉంది? రేపు వర్షం పడే అవకాశం ఉందా?");
        addChip(chips, "📷", "ఫోటో స్కాన్", null);
        addAction(chips, "📄", "Scan → PDF", () -> Scanner.start(this));
        addAction(chips, "🧾", "బిల్లు → ఖర్చు", this::billPhoto);
        addAction(chips, "🗓️", "డ్యూటీ", () -> startActivity(new Intent(this, DutyActivity.class)));
        addAction(chips, "🎥", "Live కెమెరా", this::toggleCamera);
        addChip(chips, "📱", "స్క్రీన్ చూడు", "నా స్క్రీన్‌లో ఏముందో చూసి చెప్పు (look_at_screen వాడు).");
        addChip(chips, "💬", "మెసేజ్‌లు", "నాకు వచ్చిన కొత్త మెసేజ్‌లు చదివి చెప్పు (read_notifications వాడు).");
        addChip(chips, "⏰", "రిమైండర్లు", "నా రాబోయే రిమైండర్లు, ఈరోజు క్యాలెండర్ చెప్పు.");
        addAction(chips, "🗣️", "English practice", this::startEnglishPractice);
        addAction(chips, "🌐", "వెబ్‌సైట్", () -> prefill("ఒక వెబ్‌సైట్ తయారు చెయ్: "));
        addAction(chips, "📲", "యాప్", () -> prefill("ఒక Android యాప్ తయారు చెయ్: "));
        addAction(chips, "🐍", "Python", () -> prefill("Python తో లెక్కించు: "));
        addAction(chips, "💻", "కోడ్", () -> prefill("కోడ్ రాయి: "));
        addChip(chips, "🎯", "మిషన్ స్టేటస్", "నా మిషన్ల స్టేటస్ చెప్పు. ఎన్ని పెండింగ్‌లో ఉన్నాయి, ముందు ఏది చేయాలో ఒక్కటి సూచించు.");
        addChip(chips, "🧘", "ఫోకస్ మోడ్", "నేను ఇప్పుడు 25 నిమిషాలు ఫోకస్ చేయాలి. నా మిషన్ల నుంచి ఒకటి ఎంచుకుని మూడు చిన్న స్టెప్స్ చెప్పు, తర్వాత 25 నిమిషాల టైమర్ పెట్టు.");
        addChip(chips, "🦾", "సూట్ అప్", "Jarvis, సూట్ అప్! ఈరోజుని ఎదుర్కోవడానికి నన్ను సిద్ధం చేయి.");
        addChip(chips, "😄", "ఒక జోక్", "ఒక చిన్న తెలుగు జోక్ చెప్పు.");
        chipScroll.addView(chips);
        dock.addView(chipScroll);

        attachRow = new LinearLayout(this);
        attachRow.setGravity(Gravity.CENTER_VERTICAL);
        attachRow.setPadding(0, 0, 0, dp(8));
        attachThumb = new ImageView(this);
        attachThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        attachRow.addView(attachThumb, new LinearLayout.LayoutParams(dp(46), dp(46)));
        attachText = Ui.text(this, "  ఫోటో జత చేశారు", 14, Ui.MUTED);
        attachText.setSingleLine(true);
        attachText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        attachRow.addView(attachText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView attachRemove = Ui.text(this, "తీసేయి", 14, Ui.RED);
        attachRemove.setPadding(dp(10), dp(8), dp(4), dp(8));
        attachRemove.setOnClickListener(v -> clearAttachment());
        attachRow.addView(attachRemove);
        attachRow.setVisibility(View.GONE);
        dock.addView(attachRow);

        // like ChatGPT: [+] message [mic] [blue Live button], all in one rounded box
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.round(this, 0x1AFFFFFF, 0x33FFFFFF, 28));
        row.setPadding(dp(4), dp(4), dp(5), dp(4));
        IconView plus = new IconView(this, IconView.PLUS, Ui.TEXT);
        plus.setContentDescription("ఫోటో జత చేయి");
        plus.setOnClickListener(v -> pickPhoto());
        row.addView(plus, new LinearLayout.LayoutParams(dp(44), dp(44)));

        input = new EditText(this);
        input.setHint("Jarvis ని అడగండి");
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.FAINT);
        input.setTextSize(16.5f);
        input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setBackground(null);
        input.setPadding(dp(4), dp(10), dp(4), dp(10));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { if (!voice.listening) refreshAction(); }
        });
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        micIcon = new IconView(this, IconView.MIC, Ui.TEXT);
        micIcon.setContentDescription("మాట్లాడి అడగండి");
        micIcon.setOnClickListener(v -> onMicPressed());
        row.addView(micIcon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        pauseBtn = new FrameLayout(this);
        pauseBtn.setBackground(Ui.round(this, Ui.PANEL, Ui.CYAN2, 22));
        pauseIcon = new IconView(this, IconView.PAUSE, Ui.CYAN);
        pauseBtn.addView(pauseIcon, new FrameLayout.LayoutParams(-1, -1));
        pauseBtn.setOnClickListener(v -> togglePause());
        pauseBtn.setContentDescription("ఆపు / కొనసాగించు");
        pauseBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(44), dp(44));
        plp.rightMargin = dp(4);
        row.addView(pauseBtn, plp);

        actionBtn = new FrameLayout(this);
        actionBtn.setBackground(actionBg(LiveScreen.blue()));
        actionIcon = new IconView(this, IconView.WAVE, 0xFFFFFFFF);
        actionBtn.addView(actionIcon, new FrameLayout.LayoutParams(-1, -1));
        actionBtn.setOnClickListener(v -> onActionPressed());
        actionBtn.setContentDescription("Live సంభాషణ");
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(46), dp(46));
        alp.leftMargin = dp(2);
        row.addView(actionBtn, alp);
        dock.addView(row);
        root.addView(dock);

        showTab(0);
        // the full-screen Live view sits on top, hidden until Live starts
        FrameLayout top = new FrameLayout(this);
        top.setBackground(new Ui.Aurora());
        top.addView(root, new FrameLayout.LayoutParams(-1, -1));
        liveScreen = new LiveScreen(this, this);
        liveScreen.setVisibility(View.GONE);
        top.addView(liveScreen, new FrameLayout.LayoutParams(-1, -1));
        if (!HudBoot.shown) { // "JARVIS ఆన్‌లైన్" once when the app starts
            HudBoot.shown = true;
            top.addView(new HudBoot(this), new FrameLayout.LayoutParams(-1, -1));
        }
        return top;
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

    /** The blue Live / send button: a blue-violet gradient; stop and listen keep their plain colours. */
    private Drawable actionBg(int bg) {
        if (bg != LiveScreen.blue()) return Ui.round(this, bg, 0, 23);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Ui.C_BLUE, Ui.C_VIOLET});
        g.setShape(GradientDrawable.OVAL);
        return g;
    }

    /** Read when used, so they follow the theme. */
    private static int[][] chipColors() { return new int[][]{
            {Ui.C_CYAN, Ui.C_BLUE}, {Ui.C_VIOLET, Ui.C_PINK}, {Ui.C_AMBER, Ui.C_ORANGE}, {Ui.C_GREEN, Ui.C_TEAL},
            {Ui.C_BLUE, Ui.C_VIOLET}, {Ui.C_PINK, Ui.C_ORANGE}, {Ui.C_TEAL, Ui.C_CYAN}, {Ui.C_ORANGE, Ui.C_PINK}}; }
    private int chipIndex;

    /** A colourful quick-action pill: emoji, words, its own soft gradient. */
    private TextView chip(LinearLayout chips, String emoji, String label) {
        int[][] colors = chipColors();
        int[] c = colors[chipIndex++ % colors.length];
        TextView chip = Ui.text(this, emoji + "  " + label, 14, 0xFFFFFFFF);
        chip.setSingleLine(true);
        GradientDrawable g = Ui.grad(this, new int[]{Ui.alpha(c[0], 0x55), Ui.alpha(c[1], 0x33)}, 999, null);
        g.setStroke(dp(1), Ui.alpha(c[0], 0x99));
        chip.setBackground(g);
        chip.setPadding(dp(13), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        chips.addView(chip, lp);
        return chip;
    }

    private void addAction(LinearLayout chips, String emoji, String label, Runnable action) {
        chip(chips, emoji, label).setOnClickListener(v -> action.run());
    }

    /** A question from outside (a notification, the features screen): into Live when it is running, else a normal turn. */
    private void askFromOutside(String prompt, String label) {
        if (busy) {
            Toast.makeText(this, "Jarvis ఇంకా జవాబిస్తున్నాడు. అయ్యాక మళ్లీ నొక్కండి.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (live != null && live.isOpen()) {
            String shown = label != null ? label : prompt;
            store.addChat("user", shown, false);
            addMessage("user", shown, System.currentTimeMillis(), null);
            live.sendText(prompt);
            return;
        }
        send(prompt, label, false);
    }

    /** An action picked in the features screen. */
    private void doAction(String code) {
        switch (code) {
            case "scan": Scanner.start(this); break;
            case "bill": billPhoto(); break;
            case "expiry_doc": photoFor("📄 డాక్యుమెంట్ ఫోటో → గడువు", EXPIRY_PROMPT); break;
            case "warranty_bill": photoFor("🧾 బిల్ ఫోటో → వారంటీ", WARRANTY_PROMPT); break;
            case "card": photoFor("🪪 విజిటింగ్ కార్డ్ ఫోటో", CARD_PROMPT); break;
            case "homework": photoFor("📚 హోంవర్క్ ఫోటో", HOMEWORK_PROMPT); break;
            case "plant": photoFor("🌿 మొక్క / పంట ఫోటో", PLANT_PROMPT); break;
            case "report": photoFor("🧪 మెడికల్ రిపోర్ట్ (ఫోటో / PDF)", REPORT_PROMPT); break;
            case "live": startLiveFromButton(); break;
            case "english": startEnglishPractice(); break;
            case "camera": toggleCamera(); break;
            case "jarvis_camera": JarvisCamera.open(this, null, null); break;
            case "obd": startActivity(new Intent(this, ObdActivity.class)); break;
            case "photo": pickPhoto(); break;
            case "file": openFiles(); break;
            case "brief": if (!busy) send(BRIEF_PROMPT, "🌅 శుభోదయం బ్రీఫింగ్", false); break;
            case "missions": showTab(1); break;
            case "memories": showTab(2); break;
            default: break;
        }
    }

    /** Starts a request in the message box for him to finish ("ఒక వెబ్‌సైట్ తయారు చెయ్: …"). */
    private void prefill(String start) {
        if (live != null) { liveScreen.show(); return; }
        input.setText(start);
        input.setSelection(input.getText().length());
        input.requestFocus();
        try {
            android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (imm != null) imm.showSoftInput(input, 0);
        } catch (Exception ignored) {}
    }

    private void addChip(LinearLayout chips, String emoji, String label, String prompt) {
        TextView chip = chip(chips, emoji, label);
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
    }

    private void showTab(int idx) {
        for (int i = 0; i < 3; i++) {
            panels[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            tabLabels[i].setTextColor(i == idx ? 0xFFFFFFFF : Ui.MUTED);
            tabLines[i].setBackground(i == idx ? Ui.grad(this, new int[]{Ui.C_BLUE, Ui.C_VIOLET}, 20, null) : null);
        }
        dock.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        if (hudDash != null) hudDash.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        tab = idx;
        updateFace();
        if (idx == 0) scrollToEnd();
    }

    private void tick() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat("HH:mm", Locale.ENGLISH).format(now));
        if (greeting != null) greeting.setText(shortGreeting() + ", " + prefs.name());
        dateView.setText(new SimpleDateFormat("EEEE, d MMM", new Locale("te", "IN")).format(now));
        main.postDelayed(this::tick, 15000);
    }

    private String shortGreeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h >= 4 && h < 12) return "శుభోదయం";
        if (h >= 12 && h < 17) return "శుభ మధ్యాహ్నం";
        if (h >= 17 && h < 21) return "శుభ సాయంత్రం";
        return "శుభ రాత్రి";
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
            setupCard.setOnClickListener(v -> askCorePermissions());
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

    /** He talked over Jarvis: speech already stopped, listen to him now. */
    @Override public void onBargeIn() {
        syncPause();
        if (busy || live != null || isFinishing()) { voice.stopSpeaking(); finishTurn(); return; }
        if (paused) { voice.resume(); return; } // screen not in front: don't start listening, just carry on
        main.postDelayed(this::startListening, 100);
    }

    // ================================================================ chat rendering

    private String hhmm(long t) { return new SimpleDateFormat("HH:mm", Locale.ENGLISH).format(new Date(t)); }

    private TextView addMessage(String role, String text, long t, Bitmap photo) {
        boolean user = "user".equals(role);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(user ? Gravity.END : Gravity.START);

        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        TextView who = Ui.mono(this, (user ? prefs.name().toUpperCase(Locale.ROOT) : "JARVIS") + " · " + hhmm(t), 11, user ? Ui.C_AMBER : Ui.C_CYAN);
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
        if (user) { // blue-violet bubble, like a sent message
            body.setTextColor(0xFFFFFFFF);
            body.setBackground(Ui.corners(this, Ui.grad(this, new int[]{Ui.C_BLUE, Ui.C_VIOLET}, 0, GradientDrawable.Orientation.TL_BR), 20, 20, 6, 20));
            body.setPadding(dp(14), dp(9), dp(14), dp(10));
        } else {    // frosted glass
            body.setBackground(Ui.corners(this, Ui.round(this, 0x16FFFFFF, 0x26FFFFFF, 0), 6, 20, 20, 20));
            body.setPadding(dp(14), dp(10), dp(14), dp(11));
            jarvisBodies.add(body);
            IconView replay = new IconView(this, IconView.SPEAKER, Ui.C_CYAN);
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
        karaoke.clear();
        chatList.removeAllViews();
        jarvisBodies.clear();
        List<JSONObject> turns = store.chat();
        for (JSONObject o : turns) {
            String c = o.optString("content");
            if (c.trim().isEmpty() && !o.optBoolean("photo")) continue; // e.g. a live turn that could not be transcribed
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
            updateFace();
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERM);
            return;
        }
        showTab(0);
        cameraBox.setVisibility(View.VISIBLE);
        if (sight != null) sight.stopAndWait(700); // many phones cannot run the front and back cameras together
        updateFace();
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
        int bg = LiveScreen.blue();
        int fg = 0xFFFFFFFF;
        String desc;
        boolean typed = input.getText().toString().trim().length() > 0 || pendingPhoto != null || pendingFileText != null;
        if (live != null) { icon = IconView.WAVE; desc = "Live తెర చూపించు"; }
        else if (busy || voice.speaking) { icon = IconView.STOP; bg = Ui.RED; fg = 0xFF2A0703; desc = "ఆపు"; }
        else if (voice.listening) { icon = IconView.STOP; bg = Ui.GOLD; fg = Ui.GOLD_INK; desc = "వినడం ఆపు"; }
        else if (typed) { icon = IconView.SEND; desc = "పంపు"; }
        else { icon = IconView.WAVE; desc = "Live సంభాషణ"; }
        actionIcon.setIcon(icon);
        actionIcon.setColor(fg);
        actionBtn.setBackground(actionBg(bg));
        actionBtn.setContentDescription(desc);
        if (micIcon != null) {
            micIcon.setVisibility(live != null ? View.GONE : View.VISIBLE);
            micIcon.setColor(voice.listening ? Ui.GOLD : Ui.TEXT);
        }
        syncPause();
    }

    /** ⏸ shows only while Jarvis is speaking; ▶ while paused. */
    private void syncPause() {
        if (pauseBtn == null) return;
        boolean show = live == null && voice != null && voice.speaking; // ⏸/▶ for every answer
        pauseBtn.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) pauseIcon.setIcon(voice.isPaused() ? IconView.PLAY : IconView.PAUSE);
        if (!show) karaoke.clear();
    }

    @Override public void onWord(String spoken, int start, int end) {
        if (face != null) face.word(spoken, start, end);
        if (showingChat()) karaoke.word(spoken, start, end, jarvisBodies, chatScroll);
    }

    private boolean showingChat() { return chatScroll != null && chatScroll.getVisibility() == View.VISIBLE; }

    private void togglePause() {
        if (voice == null || !voice.speaking) { syncPause(); return; }
        if (voice.isPaused()) {
            voice.resume();
            afterPaused(VoiceIO.RESUMED);
        } else {
            voice.pause();
            afterPaused(VoiceIO.HELD);
        }
    }

    /** The screen after a pause / carry-on (by button or by voice). */
    private void afterPaused(int r) {
        input.setHint("Jarvis ని అడగండి");
        if (r == VoiceIO.HELD) {
            setOrb(OrbView.IDLE);
            status.setText("ఆపాను. \"Jarvis, కొనసాగించు\" అనండి లేదా ▶ నొక్కండి");
            // let "Jarvis" be heard again while paused, so he can say "కొనసాగించు" later
            talking(false);
            if (prefs.wakeReady()) WakeService.resume(this);
            screenMaySleepSoon();
        } else if (r == VoiceIO.RESUMED) {
            setOrb(OrbView.SPEAKING);
            status.setText("మాట్లాడుతున్నాను…");
            talking(true);
            WakeService.pause(this);
            keepScreenOn();
        } else if (r == VoiceIO.STOPPED) {
            finishTurn();
        }
        refreshAction();
    }

    private void onActionPressed() {
        if (live != null) { // Live is running (screen minimised): back to the Live screen
            liveScreen.show();
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
        if (!text.isEmpty() || pendingPhoto != null || pendingFileText != null) {
            input.setText("");
            send(text, null, false);
        } else {
            startLiveFromButton();
        }
    }

    /** The mic in the message box: speak instead of typing (the classic listen-then-answer). */
    private void onMicPressed() {
        if (live != null) { liveScreen.show(); return; }
        if (busy || voice.speaking) { onActionPressed(); return; } // first press stops the answer
        if (voice.listening) { voice.stopListening(); return; }
        startListening();
    }

    /** Spoken-English practice: a Live session with the friendly coach. */
    private void startEnglishPractice() {
        if (live != null) { liveScreen.show(); return; }
        if (prefs.openAiKey().trim().isEmpty()) {
            Toast.makeText(this, "English practice కి OpenAI key కావాలి (సెట్టింగ్స్ → Jarvis మెదడు)", Toast.LENGTH_LONG).show();
            return;
        }
        if (!Net.online(this)) { Toast.makeText(this, "ఇంటర్నెట్ లేదు", Toast.LENGTH_LONG).show(); return; }
        startLive(Brain.tutorInstructions(prefs.name(), null));
    }

    /** The blue button: Live conversation, whether or not Live is set as the default for "Hey Jarvis". */
    private void startLiveFromButton() {
        if (prefs.openAiKey().trim().isEmpty()) {
            Toast.makeText(this, "Live సంభాషణకి OpenAI key కావాలి (సెట్టింగ్స్ → Jarvis మెదడు)", Toast.LENGTH_LONG).show();
            return;
        }
        if (!Net.online(this)) {
            Toast.makeText(this, "ఇంటర్నెట్ లేదు. నెట్ ఆన్ చేసి మళ్లీ నొక్కండి.", Toast.LENGTH_LONG).show();
            return;
        }
        startLive();
    }

    // ---- the Live screen's buttons

    @Override public void onLiveEnd() {
        if (live != null) live.stop("user"); else liveScreen.hide();
    }

    @Override public void onLiveMute(boolean muted) {
        if (live != null) live.setMuted(muted);
        status.setText(muted ? "మైక్ ఆఫ్" : "మాట్లాడండి…");
    }

    @Override public void onLiveMinimize() {
        liveScreen.hide();
        refreshAction();
    }

    /** The GitHub backup was taken out of Jarvis: if its daily job was switched on in an older version, stop it. */
    private void cancelOldBackupJob() {
        try {
            android.app.job.JobScheduler js = getSystemService(android.app.job.JobScheduler.class);
            if (js != null && js.getPendingJob(7401) != null) js.cancel(7401);
        } catch (Exception ignored) {}
    }

    @Override public void onBackPressed() {
        if (liveScreen != null && liveScreen.showing()) { onLiveEnd(); return; } // like ChatGPT: back ends the voice chat
        super.onBackPressed();
    }

    /** Live real-time talk when it is switched on, otherwise the classic listen-then-answer. */
    private void startConversation() {
        if (voice.isPaused()) { startListening(); return; } // "Jarvis" while paused: hear "కొనసాగించు" or a new question
        if (prefs.liveReady() && Net.online(this)) startLive(); else startListening();
    }

    // ================================================================ live (real-time) conversation

    private void startLive() { startLive(null); }

    private void startLive(String instructions) {
        ScreenReader.pauseIfReading(this);
        if (live != null || busy) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_LIVE);
            return;
        }
        voice.stopSpeaking();
        if (voice.listening) voice.cancelListening();
        talking(true);
        WakeService.pause(this);
        keepScreenOn();
        showTab(0);
        input.setHint("Live నడుస్తోంది · నీలం బటన్ = Live తెర");
        Tools.takeInterpreter(); // a stale request from an earlier turn must not start later
        live = new LiveSession(this, prefs, tools, this);
        liveOn = true;
        // full screen, unless the live camera is open (then the chat and the camera stay in view)
        liveScreen.open(cameraBox.getVisibility() != View.VISIBLE);
        try {
            android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Exception ignored) {}
        live.start(instructions != null ? instructions : brain.liveInstructions(store.chat()));
        refreshAction();
    }

    @Override public void onLiveState(int orbState, String text) {
        setOrb(orbState);
        status.setText(text);
        liveScreen.state(orbState, text);
    }

    @Override public void onLiveUser(String text) {
        liveScreen.caption(text, false);
        // LiveSession has already saved it to the Store (before any tool of that turn ran).
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
        liveScreen.caption(text, true);
        if (liveBubble == null) liveBubble = addMessage("assistant", text, System.currentTimeMillis(), null);
        else {
            liveBubble.setText(text);
            scrollToEnd();
        }
    }

    @Override public void onLiveJarvis(String text) {
        liveScreen.caption(text, true);
        if (liveBubble != null) liveBubble.setText(text);
        else addMessage("assistant", text, System.currentTimeMillis(), null);
        liveBubble = null;
        store.addChat("assistant", text, false);
    }

    @Override public void onLiveLevel(float level) {
        orb.setLevel(level);
        if (face != null) face.setMic(level);
        liveScreen.orb.setMic(level);
    }

    @Override public void onLiveVoiceLevel(float level) {
        liveScreen.orb.setVoice(level);
        if (face != null) face.setVoice(level);
    }

    @Override public void onLiveError(String message) {
        String said = describeLive(message);
        TextView t = addMessage("assistant", said, System.currentTimeMillis(), null);
        t.setTextColor(Ui.RED);
        liveScreen.error(said);
    }

    @Override public void onLiveEnded(LiveSession session, String reason) {
        if (session != live) return; // an older session ended; the current one is still running
        live = null;
        liveOn = false;
        liveScreen.hide();
        liveBubble = null;
        String lang = session.interpreterLang();
        if (lang != null && !isFinishing() && !isDestroyed()) {
            // The interpreter tool ran in live mode: continue as the live two-way interpreter.
            startLive(Brain.interpreterInstructions(prefs.name(), lang));
            if (live != null) return;
        }
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
        followListen = false;
        if (busy) return;
        ScreenReader.pauseIfReading(this); // the mic must not hear the page being read
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!voice.canListen()) {
            Toast.makeText(this, "ఈ ఫోన్‌లో Google వాయిస్ టైపింగ్ లేదు. Google యాప్ ఇన్‌స్టాల్/అప్‌డేట్ చేయండి.", Toast.LENGTH_LONG).show();
            finishTurn();
            return;
        }
        talking(true);
        WakeService.pause(this);
        keepScreenOn();
        showTab(0);
        voice.listen(prefs.listenLang());
        setOrb(OrbView.LISTENING);
        status.setText("వింటున్నాను…");
        input.setHint("వింటున్నాను…");
        refreshAction();
    }

    @Override public void onListening() {
        status.setText("వింటున్నాను… మాట్లాడండి");
        syncPause();
    }

    @Override public void onPartial(String text) {
        input.setText(text);
        input.setSelection(input.getText().length());
    }

    @Override public void onHeard(String text) {
        input.setHint("Jarvis ని అడగండి");
        input.setText("");
        if (voice.isPaused()) { // "ఆపు" / "కొనసాగించు" / "చాలు", or a new question
            int r = voice.pausedHeard(text);
            if (r != VoiceIO.NEW) { afterPaused(r); return; }
        }
        if (text == null || text.trim().isEmpty()) { finishTurn(); return; }
        send(text.trim(), null, true);
    }

    @Override public void onListenFailed(int error) {
        input.setHint("Jarvis ని అడగండి");
        String partial = input.getText().toString().trim();
        if (voice.isPaused()) { // nothing (clear) heard while paused: carry on, or stay paused if he paused it
            input.setText("");
            int r = voice.pausedHeard(partial);
            if (r != VoiceIO.NEW) { afterPaused(r); return; }
            send(partial, null, true);
            return;
        }
        if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && !partial.isEmpty()) {
            input.setText("");
            send(partial, null, true);
            return;
        }
        input.setText("");
        boolean nothing = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        else if (!nothing) Toast.makeText(this, VoiceIO.failText(error), error == 12 || error == 13 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
        Sfx.micOff(this, prefs); // he hears that the mic closed
        boolean followed = followListen;
        finishTurn();
        if (!(nothing && followed)) status.setText((nothing ? "ఏమీ వినిపించలేదు" : "మైక్ ఆగిపోయింది") + ". మళ్లీ మైక్ నొక్కండి లేదా \"Jarvis\" అనండి");
    }

    /** The panel started listening over this screen: this listen stopped quietly (the panel's talk goes on). */
    @Override public void onMicTaken() {
        input.setHint("Jarvis ని అడగండి");
        input.setText("");
        if (voice.isPaused()) refreshAction(); else finishTurn();
    }

    @Override public void onLevel(float level) {
        orb.setLevel(level);
        if (face != null) face.setMic(level);
    }

    @Override public void onSpeakStart() {
        keepScreenOn();
        if (face != null) face.setFeeling(feelingNext != null ? feelingNext : voice.feeling());
        feelingNext = null;
        setOrb(OrbView.SPEAKING);
        status.setText("మాట్లాడుతున్నాను…");
        refreshAction();
    }

    @Override public void onSpeakDone() {
        String lang = Tools.takeInterpreter();
        if (lang != null && live == null && !busy) { startLive(Brain.interpreterInstructions(prefs.name(), lang)); return; }
        boolean asked = Tools.awaitingAnswer(); // read every time: the "which one?" flag is used up here
        ScreenReader page = ScreenReader.get(this);
        if (lastWasVoice && (prefs.followUp() || asked) && !paused && !busy && !(page.active() && !page.paused())) { // not while a page is read aloud (a paused one waits)
            lastWasVoice = false;
            main.postDelayed(() -> { startListening(); followListen = !asked; }, 250);
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
        boolean seeMe = false; // Jarvis sees him through the front camera while they talk (Settings → Jarvis ముఖం)
        if (photoNow == null && pendingFileText == null && shown != null && !shown.isEmpty() && sight != null && sight.isOn() && FaceSight.seeMe(this)) {
            String me = FaceSight.picture();
            if (me != null) { photoNow = me; seeMe = true; }
        }
        final String photo = photoNow;
        final Bitmap thumb = pendingThumb;
        final String fileText = pendingFileText, fileName = pendingFileName;
        if ((shown == null || shown.isEmpty()) && photo == null && fileText == null) return;
        if (shown == null || shown.isEmpty()) shown = fileName != null ? "ఈ ఫైల్‌లో ఏముందో చిన్నగా చెప్పు." : "ఈ ఫోటోలో ఏముందో చెప్పు.";
        String q = prompt == null || prompt.isEmpty() ? shown : prompt;
        if (fileText != null) q = q + "\n\n--- ఫైల్: " + fileName + " ---\n" + fileText; // a text file goes in as text
        final String askPlain = q;
        if (seeMe) q = q + SEE_ME;
        final String ask = q;
        if (fileName != null) shown = "📎 " + fileName + "\n" + shown;

        if (!prefs.hasBrain()) {
            addMessage("assistant", "క్షమించండి " + prefs.name() + ", నా మెదడుకి ఇంకా API key లేదు. సెట్టింగ్స్‌లో పెట్టండి.", System.currentTimeMillis(), null);
            updateSetup();
            finishTurn();
            return;
        }
        clearAttachment();
        showTab(0);
        List<JSONObject> history = store.chat();
        store.addChat("user", shown, photo != null && !Brain.isPdf(photo) && !seeMe);
        addMessage("user", seeMe ? shown + "  👁" : shown, System.currentTimeMillis(), thumb);
        thinkingView = addMessage("assistant", "ఆలోచిస్తున్నాను…", System.currentTimeMillis(), null);
        thinkingView.setTextColor(Ui.MUTED);
        thinkingWrap = (View) thinkingView.getParent();

        lastWasVoice = byVoice;
        busy = true;
        talking(true);
        WakeService.pause(this);
        keepScreenOn();
        setOrb(OrbView.THINKING);
        status.setText("ఆలోచిస్తున్నాను…");
        refreshAction();

        final int gen = ++generation;
        Brain.Status progress = new Brain.Status() {
            @Override public void update(String s) {
                main.post(() -> {
                    if (gen != generation) return;
                    status.setText(s);
                    if (thinkingView != null) thinkingView.setText(s);
                });
            }
            // The red stop button (or leaving the screen) bumps generation: stop before the next round/tool.
            @Override public boolean cancelled() { return gen != generation; }
        };
        final boolean sentMe = seeMe;
        worker.submit(() -> {
            String reply = null, error = null;
            try {
                try {
                    reply = brain.ask(history, ask, photo, progress);
                } catch (Http.ApiError e) {
                    if (!sentMe || e.status != 400) throw e;
                    reply = brain.ask(history, askPlain, null, progress); // this model takes no pictures: the question alone
                }
            } catch (java.util.concurrent.CancellationException e) {
                return; // stopped by Anil: no error bubble
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
        if (face != null) face.setFeeling(prefs.emotions() ? Emotion.forText(reply) : Emotion.CALM);
        if (prefs.voiceReplies() && !paused) {
            voice.speak(reply, prefs.speechRate());
            setOrb(OrbView.SPEAKING);
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
        String te = Models.explain(prefs, e); // limits / balance / no model chosen, for the company it came from
        if (te != null) { /* said */ }
        else if (e.status == 401 || e.status == 403) te = "API key పనిచేయడం లేదు. సెట్టింగ్స్‌లో key సరిగ్గా పెట్టారో చెక్ చేయండి.";
        else if (e.status == 429) te = "చాలా ఎక్కువ ప్రశ్నలు ఒకేసారి. కొంచెం ఆగి అడగండి.";
        else if ((e.status == 400 || e.status == 404) && low.contains("model"))
            te = "మోడల్ పేరు \"" + prefs.model() + "\" పనిచేయలేదు. సెట్టింగ్స్‌లో 'అన్ని మోడల్స్ చూపించు' నొక్కి వేరే మోడల్ ఎంచుకోండి.";
        else if (e.status >= 500) te = "సర్వర్ బిజీగా ఉంది. కాసేపటి తర్వాత మళ్లీ అడగండి.";
        else if (e.status == 0) te = "సమాధానం రాలేదు. మళ్లీ అడగండి.";
        else te = "పొరపాటు జరిగింది (" + e.status + ").";
        return te + "\n(" + (m.length() > 180 ? m.substring(0, 180) : m) + ")";
    }

    private void finishTurn() {
        if (busy || live != null) return;
        Tools.takeInterpreter(); // an interpreter request that was never started must not start later
        setIdle();
        if (SheetActivity.talkingNow()) return; // the panel came over this screen and is talking: its talk, its mic
        talking(false);
        if (prefs.wakeReady()) WakeService.resume(this);
    }

    /** While Anil and Jarvis are talking the screen must not go dark. */
    private final Runnable letScreenSleep = () -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    private void keepScreenOn() {
        main.removeCallbacks(letScreenSleep);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** After the talk ends, let the screen turn off on its own a little later. */
    private void screenMaySleepSoon() {
        main.removeCallbacks(letScreenSleep);
        main.postDelayed(letScreenSleep, 20000);
    }

    /** Shows / hides Jarvis's face and starts / stops the front camera, as the settings and the screen allow. */
    private void updateFace() {
        if (face == null || faceBox == null || cameraBox == null || isFinishing()) return;
        boolean place = FaceSight.faceOn(this) && tab == 0 && cameraBox.getVisibility() != View.VISIBLE;
        if (place && hudDash != null && !getPreferences(MODE_PRIVATE).getBoolean("face_dash_folded", false)) {
            hudDash.collapse(); // once, to make room for the face (a tap on the status line opens the tiles again)
            getPreferences(MODE_PRIVATE).edit().putBoolean("face_dash_folded", true).apply();
        }
        int room = faceRoom();
        if (room >= 0) faceRoom = room;
        int h = faceRoom < 0 ? dp(FaceSight.big(this) ? 230 : 160) : faceRoom;
        faceBox.setVisibility(place && !keyboardUp && h > 0 ? View.VISIBLE : View.GONE);
        face.setStyle(FaceSight.holo(this));
        ViewGroup.LayoutParams lp = faceBox.getLayoutParams();
        if (lp != null && h > 0 && lp.height != h) { lp.height = h; faceBox.setLayoutParams(lp); }
        if (place && visible && !FaceSight.asked(this) && !askingFace) askFaceCamera();
        // the eyes keep watching while he types (only the drawing makes room for the keyboard)
        boolean eyes = place && visible && FaceSight.camOn(this) && FaceSight.allowed(this) && !sightPaused && !Guard.running(this);
        if (eyes) sight.start(); else sight.stop();
        faceNote.setText(sight.isOn() ? "👁" : sightPaused && place && FaceSight.camOn(this) ? "💤" : "");
        if (!sight.isOn()) face.look(false, 0, 0, 0);
    }

    /** The face's height that leaves the chat at least ~190dp (px): 0 when there is no room, -1 before the first layout. */
    private int faceRoom() {
        if (faceBox == null || stageView == null || keyboardUp) return -1;
        View root = (View) faceBox.getParent();
        int rootH = root == null ? 0 : root.getHeight();
        if (rootH <= 0 || stageView.getHeight() <= 0 && faceBox.getVisibility() != View.VISIBLE) return -1;
        int margin = ((LinearLayout.LayoutParams) faceBox.getLayoutParams()).topMargin;
        int faceNow = faceBox.getVisibility() == View.VISIBLE ? faceBox.getHeight() + margin : 0;
        int others = rootH - stageView.getHeight() - faceNow; // header, tabs, status tiles, dock
        int h = Math.min(dp(FaceSight.big(this) ? 230 : 160), rootH - others - dp(190) - margin);
        return h < dp(120) ? 0 : h;
    }

    /** Once: may Jarvis look at him through the front camera, and send his picture with questions? */
    private void askFaceCamera() {
        askingFace = true;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Jarvis మిమ్మల్ని చూడాలా?")
                .setMessage("ముందు కెమెరాతో Jarvis మిమ్మల్ని చూస్తాడు: ముఖం మీ వైపు చూస్తుంది, మీరు నవ్వితే నవ్వుతుంది, మీరు పరిచయం చేసినవాళ్లను పేరుతో గుర్తుపడతాడు. "
                        + "Jarvis స్క్రీన్ తెరిచి ఉన్నప్పుడే (ముఖం పక్కన 👁); ఏదీ రికార్డ్ / సేవ్ అవ్వదు.\n\n"
                        + "'ఫోటోతో' అంటే: మీ ముఖం కనిపిస్తున్నప్పుడు అడిగే ప్రశ్నతో ఒక చిన్న ఫోటో మీరు ఎంచుకున్న AI కి వెళ్తుంది, మిమ్మల్ని చూసి మాట్లాడతాడు (API ఖర్చు కొంచెం పెరుగుతుంది).\n\n"
                        + "సెట్టింగ్స్ → Jarvis ముఖంలో ఎప్పుడైనా మార్చొచ్చు.")
                .setPositiveButton("చూడు + ఫోటోతో", (d, w) -> answerFace(true, true))
                .setNeutralButton("చూడు మాత్రమే", (d, w) -> answerFace(true, false))
                .setNegativeButton("కెమెరా వద్దు", (d, w) -> answerFace(false, false))
                .setOnDismissListener(d -> askingFace = false)
                .show();
    }

    private void answerFace(boolean cam, boolean seeMe) {
        getSharedPreferences("jarvis", MODE_PRIVATE).edit().putBoolean("face_asked", true).putBoolean("face_cam", cam).putBoolean("face_seeme", seeMe).apply();
        if (cam && !FaceSight.allowed(this)) requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_FACE_CAM);
    }

    private final FaceSight.Listener sightListener = new FaceSight.Listener() {
        @Override public void onSight(boolean present, float x, float y, float smile) {
            if (face != null) face.look(present, x, y, smile);
        }

        @Override public void onKnown(String name, boolean owner) { greetPerson(name, owner); }

        @Override public void onSightStopped(String why, boolean idle) {
            sightPaused = true;
            if (face != null) face.look(false, 0, 0, 0);
            if (faceNote != null) faceNote.setText(idle ? "💤" : "");
            if (!idle && visible && why != null && sightErrorsShown.add(why)) Toast.makeText(MainActivity.this, why, Toast.LENGTH_LONG).show();
        }
    };

    /** Someone he introduced came into view: a smile, and a hello now and then (not more than once in 3 hours). */
    private void greetPerson(String name, boolean owner) {
        if (face == null) return;
        face.greet();
        android.content.SharedPreferences gp = getSharedPreferences("jarvis_people", MODE_PRIVATE);
        String key = "greeted_" + name.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        if (now - gp.getLong(key, 0) < 3 * 3600_000L) return;
        if (busy || live != null || voice.speaking || voice.listening || !visible) return;
        gp.edit().putLong(key, now).apply();
        if (owner) { status.setText("హాయ్ " + name + " 👋"); return; }
        if (!FaceSight.greetOn(this) || !prefs.voiceReplies()) { status.setText("👋 " + name + " వచ్చారు"); return; }
        face.setFeeling("happy");
        feelingNext = "happy";
        voice.speak("హాయ్ " + name + "! బాగున్నారా?", prefs.speechRate());
    }

    /** What Jarvis is doing, on the hologram core and on his face. */
    private void setOrb(int s) {
        orb.setState(s);
        if (face != null) face.setState(s);
    }

    private void setIdle() {
        screenMaySleepSoon();
        setOrb(prefs.hasBrain() ? OrbView.IDLE : OrbView.OFFLINE);
        status.setText(prefs.hasBrain() ? "సిద్ధంగా ఉన్నాను, " + prefs.name() : "మెదడు ఆఫ్‌లైన్: API key కావాలి");
        input.setHint("Jarvis ని అడగండి");
        refreshAction();
    }

    private void beep() {
        if (!prefs.sfx()) return; // "సౌండ్ ఎఫెక్ట్" off: no sound when he calls
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
            main.postDelayed(tg::release, 400);
        } catch (Exception ignored) {}
    }

    // ================================================================ photos

    /** The ➕ menu, like ChatGPT's: camera, photos, files, and the live camera. */
    private void pickPhoto() {
        android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, 0xF5121A33, 0x33FFFFFF, 24));
        card.setPadding(dp(8), dp(10), dp(8), dp(10));
        attachItem(card, d, IconView.SCAN, Ui.C_CYAN, "Jarvis కెమెరా", "చూపించి ఏదైనా అడగండి, 3D స్కాన్", () -> JarvisCamera.open(this, null, null));
        attachItem(card, d, IconView.CAMERA, Ui.C_BLUE, "Camera", "కెమెరాతో ఫోటో తీయండి", this::openCamera);
        attachItem(card, d, IconView.IMAGE, Ui.C_PINK, "Photos", "గ్యాలరీ నుంచి ఫోటో", this::openGallery);
        attachItem(card, d, IconView.CLIP, Ui.C_AMBER, "Files", "PDF, టెక్స్ట్, కోడ్ ఫైల్స్", this::openFiles);
        attachItem(card, d, IconView.SCAN, Ui.C_VIOLET, "Scan → PDF", "కాగితాలు స్కాన్ చేసి ఒక PDF", () -> Scanner.start(this));
        attachItem(card, d, IconView.VIDEO, Ui.C_GREEN, "Live కెమెరా", "Jarvis చూస్తూ మాట్లాడతాడు", this::toggleCamera);
        d.setContentView(card);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setLayout(dp(280), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.gravity = Gravity.BOTTOM | Gravity.START;
            lp.x = dp(12);
            lp.y = dp(78); // just above the message box
            lp.dimAmount = 0.45f;
            w.setAttributes(lp);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        d.show();
    }

    private void attachItem(LinearLayout card, android.app.Dialog d, int icon, int color, String title, String sub, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        row.setBackground(Ui.ripple(this, 16));
        FrameLayout circle = new FrameLayout(this);
        circle.setBackground(Ui.round(this, (color & 0x00FFFFFF) | 0x33000000, 0, 22));
        circle.addView(new IconView(this, icon, color), new FrameLayout.LayoutParams(-1, -1));
        row.addView(circle, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, 0, 0);
        texts.addView(Ui.text(this, title, 16.5f, Ui.TEXT));
        texts.addView(Ui.text(this, sub, 12.5f, Ui.MUTED));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> { d.dismiss(); action.run(); });
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void openFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "text/*", "image/*", "application/json",
                        "application/xml", "application/javascript", "application/x-python"});
        try {
            startActivityForResult(i, REQ_FILE);
        } catch (Exception e) {
            Toast.makeText(this, "ఫైల్స్ తెరవలేకపోయాను", Toast.LENGTH_SHORT).show();
        }
    }

    private static final String[] TEXT_EXT = {".txt", ".md", ".csv", ".json", ".xml", ".html", ".htm", ".py", ".java", ".kt",
            ".js", ".ts", ".css", ".c", ".cpp", ".h", ".sql", ".yaml", ".yml", ".log", ".ini", ".sh", ".gradle", ".srt"};

    /** A picked file: photos go the photo way, a PDF goes to the AI as a document, a text/code file as text. */
    private void attachFile(Uri uri) {
        worker.submit(() -> {
            String name = "file", mime = null;
            long size = -1;
            try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    int si = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
                }
            } catch (Exception ignored) {}
            try { mime = getContentResolver().getType(uri); } catch (Exception ignored) {}
            String low = name.toLowerCase(Locale.ROOT);
            boolean text = mime != null && (mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") || mime.contains("javascript"));
            for (String ext : TEXT_EXT) if (low.endsWith(ext)) text = true;
            final String fname = name;
            try {
                if (mime != null && mime.startsWith("image/")) {
                    main.post(() -> attachImage(uri));
                } else if ("application/pdf".equals(mime) || low.endsWith(".pdf")) {
                    if (size > 10L * 1024 * 1024) { main.post(() -> { autoPrompt = null; autoLabel = null; }); toast("PDF 10 MB కంటే పెద్దది. చిన్న ఫైల్ ఎంచుకోండి."); return; }
                    byte[] bytes = readAll(uri, 10 * 1024 * 1024 + 1);
                    if (bytes.length > 10 * 1024 * 1024) { main.post(() -> { autoPrompt = null; autoLabel = null; }); toast("PDF 10 MB కంటే పెద్దది. చిన్న ఫైల్ ఎంచుకోండి."); return; }
                    String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    main.post(() -> showFileAttached(Brain.PDF + b64 + "|" + fname, null, fname));
                } else if (text) {
                    byte[] bytes = readAll(uri, 400 * 1024);
                    String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    if (content.length() > 60000) content = content.substring(0, 60000) + "\n… (ఫైల్ ఇంకా ఉంది, మొదటి భాగం మాత్రమే)";
                    final String body = content;
                    main.post(() -> showFileAttached(null, body, fname));
                } else {
                    main.post(() -> { autoPrompt = null; autoLabel = null; });
                    toast("ఈ రకం ఫైల్ ఇంకా చదవలేను. PDF, టెక్స్ట్/కోడ్ ఫైల్స్, ఫోటోలు మాత్రమే.");
                }
            } catch (Exception e) {
                main.post(() -> { autoPrompt = null; autoLabel = null; });
                toast("ఫైల్ తెరవలేకపోయాను");
            }
        });
    }

    /** A scan became a PDF: share it, open it, or ask Jarvis about it. */
    private void scanDone(String name, byte[] pdf, int pages, Uri saved, String where) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("PDF సిద్ధం ✓ (" + pages + " పేజీ" + (pages == 1 ? "" : "లు") + ")")
                .setMessage(where + "\n\nWhatsApp లో పంపాలంటే 'షేర్', Jarvis కి అందులో ఏముందో అడగాలంటే 'Jarvis ని అడుగు'.")
                .setPositiveButton("షేర్", (dl, w) -> {
                    try {
                        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("application/pdf")
                                .putExtra(Intent.EXTRA_STREAM, saved).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "PDF షేర్ చేయండి"));
                    } catch (Exception ignored) {}
                })
                .setNeutralButton("తెరువు", (dl, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(saved, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                    } catch (Exception e) { Toast.makeText(this, "PDF చూసే యాప్ లేదు", Toast.LENGTH_SHORT).show(); }
                })
                .setNegativeButton("Jarvis ని అడుగు", (dl, w) -> {
                    if (pdf.length > 10 * 1024 * 1024) { Toast.makeText(this, "PDF 10 MB కంటే పెద్దది", Toast.LENGTH_LONG).show(); return; }
                    showFileAttached(Brain.PDF + Base64.encodeToString(pdf, Base64.NO_WRAP) + "|" + name, null, name);
                })
                .show();
    }

    private void toast(String s) { main.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show()); }

    private byte[] readAll(Uri uri, int max) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("no stream");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < max) out.write(buf, 0, Math.min(n, max - out.size()));
            return out.toByteArray();
        }
    }

    private void showFileAttached(String pdf, String text, String name) {
        clearAttachment();
        pendingPhoto = pdf;
        pendingFileText = text;
        pendingFileName = name;
        attachThumb.setVisibility(View.GONE);
        attachText.setText((pdf != null ? "📄 " : "📝 ") + name);
        attachRow.setVisibility(View.VISIBLE);
        input.setHint("ఫైల్ గురించి ఏం అడగాలి? (ఖాళీగా పంపితే సారాంశం)");
        showTab(0);
        refreshAction();
        String p = takeAuto(), label = autoLabel; // a text file is not what the chip asked for: it waits for his own question
        autoLabel = null;
        if (p != null && pdf != null) send(p, label != null ? label : "📄 " + name, false); // the report chip with a PDF: straight away
    }

    /** 🧾 A bill photo (camera or gallery) goes straight into his expenses. */
    private void billPhoto() {
        if (busy) return;
        if (live != null) { liveScreen.show(); return; }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("🧾 బిల్లు ఫోటో")
                .setItems(new String[]{"📷 ఇప్పుడు ఫోటో తీయి", "🖼️ గ్యాలరీ నుంచి ఎంచుకో"}, (d, w) -> {
                    autoPrompt = BILL_PROMPT;
                    autoLabel = null;
                    autoAt = System.currentTimeMillis();
                    if (w == 0) openCamera(); else openGallery();
                })
                .setNegativeButton("వద్దు", null)
                .show();
    }

    /** A photo (camera or gallery) that goes to Jarvis with this request. */
    private void photoFor(String title, String prompt) {
        if (busy) return;
        if (live != null) { liveScreen.show(); return; }
        boolean report = REPORT_PROMPT.equals(prompt); // a lab report often comes as a PDF on WhatsApp
        String[] items = report ? new String[]{"📷 ఇప్పుడు ఫోటో తీయి", "🖼️ గ్యాలరీ నుంచి ఎంచుకో", "📄 PDF ఫైల్ ఎంచుకో"}
                : new String[]{"📷 ఇప్పుడు ఫోటో తీయి", "🖼️ గ్యాలరీ నుంచి ఎంచుకో"};
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setItems(items, (d, w) -> {
                    autoPrompt = prompt;
                    autoLabel = title;
                    autoAt = System.currentTimeMillis();
                    if (w == 0) openCamera(); else if (w == 1) openGallery(); else openFiles();
                })
                .setNegativeButton("వద్దు", null)
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
        if (result != RESULT_OK) {
            if (req == REQ_CAMERA || req == REQ_GALLERY || req == REQ_FILE) { autoPrompt = null; autoLabel = null; } // the photo / file was cancelled
            return;
        }
        final Uri uri;
        if (req == REQ_FILE && data != null && data.getData() != null) { attachFile(data.getData()); return; }
        if ((req == Scanner.REQ_SCAN || req == Scanner.REQ_PICK) && data != null) { Scanner.onResult(this, req, data, this::scanDone); return; }
        if (req == REQ_CAMERA) uri = PhotoProvider.uri();
        else if (req == REQ_GALLERY && data != null && data.getData() != null) uri = data.getData();
        else return;
        attachImage(uri);
    }

    private void attachImage(Uri uri) {
        worker.submit(() -> {
            try {
                Bitmap bmp = loadScaled(uri, 1280);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
                String b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                Bitmap thumb = Bitmap.createScaledBitmap(bmp, Math.max(1, bmp.getWidth() * 360 / Math.max(bmp.getWidth(), bmp.getHeight())),
                        Math.max(1, bmp.getHeight() * 360 / Math.max(bmp.getWidth(), bmp.getHeight())), true);
                main.post(() -> {
                    clearAttachment();
                    pendingPhoto = b64;
                    pendingThumb = thumb;
                    attachThumb.setVisibility(View.VISIBLE);
                    attachText.setText("  ఫోటో జత చేశారు");
                    attachThumb.setImageBitmap(thumb);
                    attachRow.setVisibility(View.VISIBLE);
                    input.setHint("ఫోటో గురించి ఏం అడగాలి? (ఖాళీగా పంపితే వివరిస్తాను)");
                    showTab(0);
                    refreshAction();
                    String p = takeAuto(), label = autoLabel;
                    autoLabel = null;
                    if (p != null) { // the 🧾 bill chip (and the other photo chips): send it straight away
                        boolean warranty = WARRANTY_PROMPT.equals(p);
                        if (warranty) { Expiry.billPhoto = b64; Expiry.billPhotoAt = System.currentTimeMillis(); } // kept with the warranty
                        send(p, warranty ? "🧾 ఈ బిల్ వారంటీ గుర్తుపెట్టుకో" : label != null ? label : "🧾 ఈ బిల్లు నా ఖర్చుల్లో చేర్చు", false);
                    }
                });
            } catch (Exception e) {
                main.post(() -> { autoPrompt = null; autoLabel = null; Toast.makeText(this, "ఫోటో తెరవలేకపోయాను", Toast.LENGTH_SHORT).show(); });
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
        pendingFileText = null;
        pendingFileName = null;
        attachRow.setVisibility(View.GONE);
        input.setHint("Jarvis ని అడగండి");
        refreshAction();
    }

    // ================================================================ Tools.Host

    @Override public Activity activity() { return this; }

    @Override public boolean confirm(String title, String message, String yes, int autoSeconds) {
        return Dialogs.confirm(this, title, message, yes, autoSeconds);
    }


    @Override public void askPermissions(String[] permissions) {
        runOnUiThread(() -> requestPermissions(permissions, REQ_PERMS));
    }

    @Override public void notice(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    /** He tapped a station in the radio list: stop talking / listening, so the mic doesn't take the radio for his voice. */
    void radioPicked() {
        runOnUiThread(() -> {
            if (live != null) return;
            if (busy) { generation++; busy = false; removeThinking(); } // "ఏ స్టేషన్?" still on its way: not over the radio
            if (voice.speaking) voice.stopSpeaking();
            if (voice.listening) voice.cancelListening();
            lastWasVoice = false;
            finishTurn();
        });
    }
}
