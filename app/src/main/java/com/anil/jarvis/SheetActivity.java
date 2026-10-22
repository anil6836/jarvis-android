package com.anil.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The small Google-Assistant-style panel that slides up when Anil says "Jarvis":
 * it answers "చెప్పండి, Anil?", listens, replies, and gets out of the way.
 * The app underneath stays where it was.
 */
public class SheetActivity extends Activity implements Tools.Host, VoiceIO.Listener, LiveSession.Listener {
    /** The theme this screen was built with (a change in Settings rebuilds it). */
    private int builtTheme;
    private Prefs prefs;
    private Store store;
    private Tools tools;
    private Brain brain;
    private VoiceIO voice;
    private LiveTalk live;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** The panel is open (so a talk in it is real, see MainActivity.busyTalking). */
    static volatile boolean open;
    /** The newest panel: a closing one's late onDestroy must not let the wake word take the mic from it. */
    private static SheetActivity current;

    /**
     * The panel is really in a talk right now (greeting, listening, thinking, speaking, a call or Live), whatever the
     * shared "talking" flag says: another screen ending its own talk must not let the wake word take the mic from it.
     */
    static boolean talkingNow() {
        SheetActivity a = current;
        if (a == null || !open || a.voice == null) return false;
        return a.greeting || a.busy || a.callText != null || a.live != null || a.voice.listening
                || (a.voice.speaking && !a.voice.isPaused());
    }

    private HoloOrb orb;               // the small hologram core (same states as OrbView)
    private TextView status, heard, reply;
    private IconView action;
    private FrameLayout pauseBtn;   // ⏸/▶ at the bottom right while Jarvis is speaking
    private final Karaoke karaoke = new Karaoke();   // highlights the word being spoken
    private ScrollView textScroll;
    private IconView pauseIcon;
    private boolean busy, stopped;
    /** How many more times to listen after Jarvis speaks without "Jarvis" again (a conversation). */
    private int followUps = 1;
    /** This listen is the optional one after an answer: silence there just ends the talk. */
    private boolean followListen;
    /** A message/suggestion flow: keep listening for the answers even if follow-up is off. */
    private boolean dialog;
    /** Bumped to drop (and stop the tools of) an answer that is still on its way. */
    private volatile int generation;
    /** The AI request of the question now on its way: ⏹ / a new question / closing cuts it at once (Http.Cancel). */
    private volatile Http.Cancel inflight;

    private void cutInflight() {
        Http.Cancel k = inflight;
        inflight = null;
        if (k != null) k.cancel();
        Filler.stop(); // (a "ఒక్క క్షణం" for the dropped answer stops too)
    }

    /** ⚡ E: the answer's words, waiting for its voice to start (shown then, or after 2.5 s at most); null: none. */
    private String heldReply;
    private final Runnable showHeld = this::showHeldReply;

    private void showHeldReply() {
        main.removeCallbacks(showHeld);
        String r = heldReply;
        if (r == null) return;
        heldReply = null;
        showReply(r, false);
    }
    /** What the recognizer has heard so far in the current listen() only (partial results). */
    private String partialHeard = "";
    /** "చెప్పండి, Anil?" is playing; only the callback of the latest greeting may start listening. */
    private boolean greeting;
    /**
     * The talk is over but the panel stays up a while (like Gemini): "Jarvis" can be heard again meanwhile,
     * and then this same panel greets and listens, with the last answer still on it.
     */
    private boolean waiting;
    private static final long STAY_OPEN_MS = 60_000;
    /** Paused by him: the panel waits this long for "కొనసాగించు" or ▶, then closes. */
    private static final long PAUSED_OPEN_MS = 5 * 60_000;
    private int greetToken;

    private final Runnable autoClose = new Runnable() {
        @Override public void run() {
            if (Radio.holdPanel()) { main.postDelayed(this, 3000); return; } // his radio list is open / the radio app is being asked
            closeSheet();
        }
    };

    /** Opened for an incoming call: the text to say ("Anil, Ravi నుంచి కాల్ వస్తోంది"). */
    static final String EXTRA_CALL = "jarvis_call";
    /** Opened to read a new message aloud, then offer a reply. */
    static final String EXTRA_ANNOUNCE = "jarvis_announce";
    static final String EXTRA_ANNOUNCE_CONTEXT = "jarvis_announce_ctx";
    /** The question after the announcement (default: "రిప్లై ఇవ్వమంటారా?"). */
    static final String EXTRA_ANNOUNCE_ASK = "jarvis_announce_ask";
    static final String EXTRA_IS_MESSAGE = "jarvis_is_message";
    /** Opened to carry out a command straight away (arrived at office, nightly summary...). */
    static final String EXTRA_RUN = "jarvis_run";
    /** The second time he is asked about the ringing call: "రవి కాల్ చేస్తున్నారు". */
    static final String EXTRA_CALL_SHORT = "jarvis_call_short";
    /** Which ring this panel is about (CallCare.ringAt): a newer call ringing meanwhile is never answered / cut by these words. */
    static final String EXTRA_CALL_AT = "jarvis_call_at";
    private String callText, callShort; // non-null while asking about a ringing call
    private long callAt;
    /** Times his answer was listened for in this ring: the name is said 2 times in all (then the buttons only, so his ringtone is heard). */
    private int callTries;
    /** 📞 How long each listen for his word to the ringing call (seconds), and his ringtone at his own volume between two. */
    private static final int CALL_LISTEN_S = 4, CALL_RING_GAP_MS = 1500, CALL_SAYS = 2;
    private LinearLayout callRow, quickRow;
    private final Runnable watchCall = new Runnable() {
        @Override public void run() {
            if (callText == null) return;
            if (!CallControl.isRinging()) { endCallMode(); if (callReply == null) closeSheet(); return; } // answered / cut on the watch, or stopped
            main.postDelayed(this, 1000);
        }
    };
    /** 📞 The talk after a cut call (the message to the caller, the reminder), else null. */
    private CallWords.Reply callReply;
    private CallWords.Step replyStep;
    /** His own ringtone rang at his volume for a few seconds: now once more who it is (softer ring meanwhile). */
    private final Runnable askAgain = () -> {
        if (callText == null || !CallControl.isRinging() || CallCare.silenced || isFinishing() || !sameCall()) return;
        CallCare.ringLow(this, true);
        orb.setState(OrbView.SPEAKING);
        voice.speak((callShort == null || callShort.isEmpty() ? callText : callShort) + ". ఎత్తమంటారా?", prefs.speechRate()); // (never "కట్": the watch's mic hears it too)
    };

    // ================================================================ lifecycle

    /** The home tablet: this panel never shows; what it was opened for goes to Jarvis on the big home screen. */
    private boolean toHome;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (new Prefs(this).homeMode()) {
            toHome = true;
            sendHome(this, getIntent());
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        Ui.loadTheme(this); // the chosen colours, before anything is built
        builtTheme = Ui.themeVersion;
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC); // volume keys = Jarvis's voice
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        // The panel only stays open while Anil and Jarvis talk, so keep the screen lit meanwhile.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        open = true;
        current = this;
        prefs = new Prefs(this);
        store = Store.get(this);
        tools = new Tools(this, store, prefs);
        brain = new Brain(prefs, store, tools);
        voice = new VoiceIO(this, prefs, this);
        setContentView(buildUi());
        TopCard.stepAside(); // a message card talking at the top stops before the panel talks
        if (!startCallMode(getIntent()) && !startAnnounce(getIntent()) && !startRun(getIntent())) begin();
        VoiceIO.yieldOthers(voice); // the app's mic under this panel stops now (it would hear the panel talk)
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (toHome || prefs == null || prefs.homeMode()) { sendHome(this, intent); finish(); return; }
        if (startCallMode(intent)) return;
        if (live == null && !busy && !greeting && !voice.listening && !voice.speaking && startAnnounce(intent)) return;
        if (live == null && !busy && !greeting && !voice.listening && !voice.speaking && startRun(intent)) return;
        if (waiting && live == null && !busy && !greeting && !voice.listening && !voice.speaking && callText == null) { // "Jarvis" again while the panel waits
            begin(); // the greeting again ("చెప్పండి, Anil?"), in this same panel, then it listens
            return;
        }
        if (live == null && !busy && !greeting && !voice.listening && voice.isPaused()) { // "Jarvis" while paused
            MainActivity.talking(true);
            WakeService.pause(this);
            listen();
            return;
        }
        if (live == null && !busy && !greeting && !voice.listening) begin(); // called again while the panel is open
    }

    @Override protected void onStart() {
        super.onStart();
        stopped = false; // back on top (e.g. after Jarvis typed a message in WhatsApp)
        main.removeCallbacks(closeIfAway);
    }

    @Override protected void onStop() {
        super.onStop();
        stopped = true;
        // Another app came in front (e.g. Jarvis opened YouTube): finish once Jarvis has finished. Not at once: on the
        // lock screen the panel is often stopped and started again in a moment while the screen lights up, and the
        // greeting or the mic must not be cut by that.
        main.removeCallbacks(closeIfAway);
        main.postDelayed(closeIfAway, 1500);
    }

    /** Still away and nothing going on (no greeting, mic, answer or call): close. */
    private final Runnable closeIfAway = () -> {
        if (stopped && callText == null && callReply == null && live == null && !greeting && !voice.listening && !voice.speaking && !busy) closeSheet();
    };

    /**
     * The home tablet (his mother's): whatever would open this panel (the wake word, the assist button, a tile, a
     * proactive question, a message) goes to the home screen instead, and the Jarvis figure there says / asks it.
     */
    static void sendHome(Context c, Intent in) {
        final Context app = c.getApplicationContext();
        String ann = in == null ? null : in.getStringExtra(EXTRA_ANNOUNCE), ask = in == null ? null : in.getStringExtra(EXTRA_ANNOUNCE_ASK),
                run = in == null ? null : in.getStringExtra(EXTRA_RUN);
        Intent home = new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        final String say = ((ann == null ? "" : ann) + " " + (ask == null ? "" : ask)).trim();
        if (run != null && !run.trim().isEmpty()) home.putExtra(MainActivity.EXTRA_ASK, run.trim());
        else if (say.isEmpty() && (in == null || in.getStringExtra(EXTRA_CALL) == null)) home.putExtra(MainActivity.EXTRA_WAKE, true);
        try { app.startActivity(home); } catch (Exception ignored) {}
        if (!say.isEmpty()) {
            final boolean listen = ask != null && !ask.trim().isEmpty();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!MainActivity.homeAsk(say, Emotion.forText(say), listen)) Announcer.sayDirect(app, say);
            }, 700);
        }
    }

    @Override protected void onDestroy() {
        if (toHome) { super.onDestroy(); return; } // (never shown: nothing was set up)
        Radio.dismissPicker();
        generation++; cutInflight(); // a reply still on its way is dropped, and its remaining tools don't run
        greetToken++;
        WaMedia.stop();
        CallCare.ringLow(this, false); // (his ringtone back to his own volume)
        if (callReply != null) CallCare.releaseReply("panel");
        if (live != null) live.stop("closed");
        voice.shutdown();
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        if (current == this) { // a newer panel may already be talking and listening
            current = null;
            MainActivity.talking(false);
            open = false;
            if (prefs.wakeReady()) WakeService.resume(this);
        }
        super.onDestroy();
    }

    private void closeSheet() {
        if (!isFinishing()) finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }

    // ================================================================ UI

    private int dp(float v) { return Ui.dp(this, v); }

    private static final int SHEET_BOTTOM = 0xFF150C38;

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> { FindPhone.stop(this); closeSheet(); }); // tap outside the card to dismiss

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        // the same colourful look as the app: deep indigo-violet card, glowing handle, hologram core
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF16225C, 0xFF0D1440, SHEET_BOTTOM});
        float r = dp(28);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(dp(1), Ui.alpha(Ui.C_VIOLET, 0x77));
        card.setBackground(bg);
        card.setPadding(dp(18), dp(10), dp(18), dp(22));
        card.setClickable(true); // taps inside the card don't close it
        getWindow().setNavigationBarColor(SHEET_BOTTOM);

        View handle = new View(this);
        handle.setBackground(Ui.grad(this, new int[]{Ui.C_CYAN, Ui.C_VIOLET, Ui.C_PINK}, 2, null));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(46), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(8);
        card.addView(handle, hlp);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        orb = new HoloOrb(this, 60);
        orb.setOnClickListener(v -> { if (live != null) live.interrupt(); }); // in Live: tap Jarvis to stop its answer
        top.addView(orb, new LinearLayout.LayoutParams(dp(62), dp(62)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), 0, dp(8), 0);
        TextView name = Ui.mono(this, "JARVIS", 14, Ui.C_CYAN);
        name.setLetterSpacing(0.4f);
        Ui.gradientText(name, Ui.C_CYAN, Ui.C_VIOLET);
        col.addView(name);
        status = Ui.text(this, Greeting.text(prefs), 17, 0xFFFFFFFF);
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(status);
        top.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

        FrameLayout btn = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable ab = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{Ui.C_BLUE, Ui.C_VIOLET});
        ab.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        btn.setBackground(ab);
        action = new IconView(this, IconView.STOP, 0xFFFFFFFF);
        btn.addView(action, new FrameLayout.LayoutParams(-1, -1));
        btn.setOnClickListener(v -> onAction());
        btn.setContentDescription("ఆపు / మాట్లాడు");
        top.addView(btn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        card.addView(top);

        // Incoming call: three big buttons besides the voice answer, and his ready messages (a tap = cut + that SMS).
        callRow = new LinearLayout(this);
        callRow.setPadding(0, dp(14), 0, 0);
        callRow.setVisibility(View.GONE);
        TextView pick = Ui.text(this, "📞 ఎత్తు", 16, 0xFFFFFFFF);
        pick.setGravity(Gravity.CENTER);
        pick.setPadding(0, dp(12), 0, dp(12));
        pick.setBackground(Ui.grad(this, new int[]{Ui.C_GREEN, Ui.C_TEAL}, 24, null));
        pick.setOnClickListener(v -> doCall(true, false));
        TextView cut = Ui.text(this, "✖ కట్", 16, 0xFFFFFFFF);
        cut.setGravity(Gravity.CENTER);
        cut.setPadding(0, dp(12), 0, dp(12));
        cut.setBackground(Ui.grad(this, new int[]{0xFFF43F5E, Ui.C_ORANGE}, 24, null));
        cut.setOnClickListener(v -> doCall(false, false));
        TextView hush = Ui.text(this, "🔕 సైలెంట్", 16, 0xFFFFFFFF);
        hush.setGravity(Gravity.CENTER);
        hush.setPadding(0, dp(12), 0, dp(12));
        hush.setBackground(Ui.grad(this, new int[]{Ui.C_VIOLET, Ui.C_BLUE}, 24, null));
        hush.setOnClickListener(v -> silenceCall());
        LinearLayout.LayoutParams third = new LinearLayout.LayoutParams(0, -2, 1);
        third.setMargins(0, 0, dp(5), 0);
        callRow.addView(pick, third);
        LinearLayout.LayoutParams third2 = new LinearLayout.LayoutParams(0, -2, 1);
        third2.setMargins(dp(5), 0, dp(5), 0);
        callRow.addView(cut, third2);
        LinearLayout.LayoutParams third3 = new LinearLayout.LayoutParams(0, -2, 1);
        third3.setMargins(dp(5), 0, 0, 0);
        callRow.addView(hush, third3);
        card.addView(callRow);
        quickRow = new LinearLayout(this);
        quickRow.setPadding(0, dp(8), 0, 0);
        quickRow.setVisibility(View.GONE);
        for (String[] q : CallCare.READY) {
            TextView t = Ui.text(this, "💬 " + q[1].replaceFirst("^\\S+\\s", ""), 13.5f, 0xFFFFFFFF);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(9), 0, dp(9));
            t.setBackground(Ui.round(this, Ui.alpha(0xFFFFFFFF, 0x1E), 0, 18));
            final String id = q[0];
            t.setOnClickListener(v -> quickTap(id));
            LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(0, -2, 1);
            ql.setMargins(dp(3), 0, dp(3), 0);
            quickRow.addView(t, ql);
        }
        card.addView(quickRow);

        final int maxText = getResources().getDisplayMetrics().heightPixels * 2 / 5;
        ScrollView scroll = new ScrollView(this) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxText, MeasureSpec.AT_MOST));
            }
        };
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(0, dp(10), 0, 0);
        heard = Ui.text(this, "", 15, Ui.C_AMBER);
        heard.setVisibility(View.GONE);
        texts.addView(heard);
        reply = Ui.text(this, "", 16.5f, Ui.TEXT);
        reply.setLineSpacing(0, 1.25f);
        reply.setBackground(Ui.corners(this, Ui.round(this, 0x16FFFFFF, 0x26FFFFFF, 0), 6, 18, 18, 18)); // frosted glass, like the app
        reply.setPadding(dp(14), dp(10), dp(14), dp(11));
        reply.setVisibility(View.GONE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.topMargin = dp(8);
        texts.addView(reply, rlp);
        scroll.addView(texts);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        textScroll = scroll;

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, dp(12), 0, 0);
        TextView open = Ui.text(this, "Jarvis యాప్ తెరువు →", 14, Ui.C_CYAN);
        open.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            closeSheet();
        });
        bottom.addView(open, new LinearLayout.LayoutParams(0, -2, 1));
        pauseBtn = new FrameLayout(this);
        pauseBtn.setBackground(Ui.round(this, 0x1AFFFFFF, Ui.alpha(Ui.C_CYAN, 0xAA), 24));
        pauseIcon = new IconView(this, IconView.PAUSE, Ui.C_CYAN);
        pauseBtn.addView(pauseIcon, new FrameLayout.LayoutParams(-1, -1));
        pauseBtn.setOnClickListener(v -> togglePause());
        pauseBtn.setContentDescription("ఆపు / కొనసాగించు");
        pauseBtn.setVisibility(View.GONE);
        bottom.addView(pauseBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        card.addView(bottom);

        root.addView(card, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return root;
    }

    private void setAction(int icon) { action.setIcon(icon); syncPause(); }

    /** ⏸ shows only while Jarvis is speaking; ▶ while paused. */
    private void syncPause() {
        if (pauseBtn == null) return;
        boolean show = live == null && callText == null && voice != null && voice.speaking; // ⏸/▶ for every answer
        pauseBtn.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) pauseIcon.setIcon(voice.isPaused() ? IconView.PLAY : IconView.PAUSE);
        if (!show) karaoke.clear();
    }

    @Override public void onWord(String spoken, int start, int end) {
        if (reply.getVisibility() == View.VISIBLE) karaoke.word(spoken, start, end, java.util.Collections.singletonList(reply), textScroll);
    }

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

    /** The panel after a pause / carry-on (by button or by voice). */
    private void afterPaused(int r) {
        main.removeCallbacks(autoClose);
        if (r == VoiceIO.HELD) {
            orb.setState(OrbView.IDLE);
            status.setText("ఆపాను. \"Jarvis, కొనసాగించు\" అనండి లేదా ▶ నొక్కండి");
            MainActivity.talking(false);
            if (prefs.wakeReady()) WakeService.resume(this); // "Jarvis" can be heard while paused
            setAction(IconView.STOP);
            main.postDelayed(autoClose, PAUSED_OPEN_MS); // not lit up for ever
        } else if (r == VoiceIO.RESUMED) {
            orb.setState(OrbView.SPEAKING);
            status.setText("మాట్లాడుతున్నాను…");
            MainActivity.talking(true);
            WakeService.pause(this);
            setAction(IconView.STOP);
        } else if (r == VoiceIO.STOPPED) {
            idle();
        }
        syncPause();
    }

    private void showHeard(String t) {
        heard.setText("“" + t + "”");
        heard.setVisibility(View.VISIBLE);
    }

    private void showReply(String t, boolean error) {
        reply.setText(t);
        reply.setTextColor(error ? Ui.RED : Ui.TEXT);
        reply.setVisibility(View.VISIBLE);
    }

    // ================================================================ flow

    private void begin() {
        main.removeCallbacks(autoClose);
        waiting = false;
        followUps = 1;
        followListen = false;
        dialog = false;
        MainActivity.talking(true);
        WakeService.pause(this);
        orb.setState(OrbView.SPEAKING);
        status.setText(Greeting.text(prefs));
        setAction(IconView.STOP);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "మైక్ అనుమతి కావాలి. Jarvis యాప్ తెరిచి Allow ఇవ్వండి.", Toast.LENGTH_LONG).show();
            closeSheet();
            return;
        }
        Sfx.chirp(this, prefs);
        final int token = ++greetToken;
        greeting = true;
        Greeting.play(this, prefs, () -> {
            if (token != greetToken) return; // a newer greeting, a call or stop took over
            greeting = false;
            if (isFinishing()) return;
            if (prefs.liveReady() && Net.online(this)) startLive(); else listen();
        });
    }

    /** Forget a greeting that is still playing, so its callback does nothing. */
    private void cancelGreeting() {
        greetToken++;
        greeting = false;
    }

    /** The mic button in the waiting panel: listens straight away (the wake word is told to let go of the mic first). */
    private void listenAgain(boolean tapped) {
        main.removeCallbacks(autoClose);
        waiting = false;
        followUps = 1;
        followListen = false;
        dialog = false;
        MainActivity.talking(true);
        WakeService.pause(this);
        if (!tapped) Sfx.chirp(this, prefs);
        orb.setState(OrbView.LISTENING);
        status.setText("వింటున్నాను…");
        setAction(IconView.STOP);
        main.postDelayed(() -> { if (!isFinishing() && !busy && live == null && !voice.listening) listen(); }, tapped ? 350 : 250);
    }

    private void listen() {
        ScreenReader.pauseIfReading(this); // the mic must not hear the page being read
        main.removeCallbacks(autoClose);
        waiting = false;
        // Only partial words heard in THIS listen may be sent on a timeout, never the previous turn's.
        partialHeard = "";
        heard.setText("");
        heard.setVisibility(View.GONE);
        if (!voice.canListen()) {
            showReply("ఈ ఫోన్‌లో Google వాయిస్ టైపింగ్ లేదు.", true);
            main.postDelayed(autoClose, 4000);
            return;
        }
        if (callText != null) voice.listenShort(prefs.listenLang(), CALL_LISTEN_S); // 📞 one short word: the name said twice, then the ringtone alone
        else voice.listen(prefs.listenLang());
        orb.setState(OrbView.LISTENING);
        status.setText("వింటున్నాను…");
        setAction(IconView.STOP);
    }

    // ================================================================ incoming call

    private boolean startCallMode(Intent i) {
        String text = i == null ? null : i.getStringExtra(EXTRA_CALL);
        if (text == null) return false;
        i.removeExtra(EXTRA_CALL);
        waiting = false;
        cancelGreeting();
        if (live != null) live.stop("call");
        voice.stopSpeaking();
        if (voice.listening) voice.cancelListening();
        generation++; cutInflight();
        busy = false;
        main.removeCallbacks(autoClose);
        MainActivity.talking(true);
        WakeService.pause(this);
        callText = text;
        callShort = i.getStringExtra(EXTRA_CALL_SHORT);
        callAt = i.getLongExtra(EXTRA_CALL_AT, CallCare.ringAt);
        callTries = 0;
        if (callReply != null) { callReply = null; CallCare.releaseReply("panel"); } // (a new call: the old message talk is over)
        main.removeCallbacks(askAgain);
        CallCare.ringLow(this, true); // his ringtone softer (not off) while Jarvis says who it is and listens
        callRow.setVisibility(View.VISIBLE);
        quickRow.setVisibility(View.VISIBLE);
        heard.setVisibility(View.GONE);
        reply.setVisibility(View.GONE);
        status.setText(text);
        setAction(IconView.STOP);
        orb.setState(OrbView.SPEAKING);
        voice.speak(text + ". ఎత్తమంటారా?", prefs.speechRate());
        main.removeCallbacks(watchCall);
        main.postDelayed(watchCall, 2000);
        return true;
    }

    /** Runs a command without asking first (sent by Jarvis itself, e.g. when he reaches the office). */
    private boolean startRun(Intent i) {
        String text = i == null ? null : i.getStringExtra(EXTRA_RUN);
        if (text == null) return false;
        i.removeExtra(EXTRA_RUN);
        waiting = false;
        main.removeCallbacks(autoClose);
        MainActivity.talking(true);
        WakeService.pause(this);
        followUps = 2;
        ask(text);
        return true;
    }

    /** Says who sent a message and asks before reading it; then listens for the answers (the brain handles them). */
    private boolean startAnnounce(Intent i) {
        String text = i == null ? null : i.getStringExtra(EXTRA_ANNOUNCE);
        if (text == null) return false;
        String ctx = i.getStringExtra(EXTRA_ANNOUNCE_CONTEXT);
        i.removeExtra(EXTRA_ANNOUNCE);
        waiting = false;
        main.removeCallbacks(autoClose);
        MainActivity.talking(true);
        WakeService.pause(this);
        String ask = i.getStringExtra(EXTRA_ANNOUNCE_ASK);
        String said = text + (ask == null ? ". రిప్లై ఇవ్వమంటారా?" : " " + ask);
        store.addChat("assistant", said + (ctx == null ? "" : ctx), false);
        heard.setVisibility(View.GONE);
        status.setText(ask == null || i.getBooleanExtra(EXTRA_IS_MESSAGE, false) ? "కొత్త మెసేజ్" : "Jarvis సూచన");
        showReply(text, false);
        setAction(IconView.STOP);
        orb.setState(OrbView.SPEAKING);
        // "చదవమంటారా?" -> read -> "రిప్లై ఇవ్వమంటారా?" -> his reply -> "పంపమంటారా?" -> send
        followUps = 4;
        dialog = true;
        voice.speak(said, prefs.speechRate());
        return true;
    }

    /** The call ringing now is still the one this panel announced (not a newer one that rang meanwhile). */
    private boolean sameCall() { return callAt == 0 || CallCare.ringAt == callAt; }

    /** A newer call rang while this panel asked about the earlier one: it acts on neither by voice. */
    private boolean otherCall() {
        if (sameCall()) return false;
        stopCallTalk();
        CallCare.ringLow(this, false);
        orb.setState(OrbView.IDLE);
        status.setText("ఇంకో కాల్ వచ్చింది: ఫోన్ స్క్రీన్‌లో చూసి మీరే నొక్కండి.");
        if (callRow != null) callRow.setVisibility(View.GONE);
        if (quickRow != null) quickRow.setVisibility(View.GONE);
        return true;
    }

    /** 📞 His words to the ringing call (CallWords: the same rules as the watch's; the caller's name and his own never count). */
    private void onCallWords(String t) {
        if (otherCall()) return;
        CallWords.Word w = CallWords.classify(t, new String[]{CallCare.who, prefs.name()});
        switch (w.kind) {
            case CallWords.ANSWER: doCall(true, true); return;
            case CallWords.SILENT: silenceCall(); return;
            case CallWords.DECLINE:
            case CallWords.MESSAGE: cutCall(w, false); return;
            default: askCallAgain();
        }
    }

    private void askCallAgain() {
        if (callText == null) return;
        if (CallCare.silenced) { quietCallMode(true); return; }
        CallCare.ringLow(this, false); // his own ringtone at his volume meanwhile
        if (++callTries >= CALL_SAYS || !CallControl.isRinging()) {
            status.setText("ఎత్తాలంటే ఆకుపచ్చ, కట్ చేయాలంటే ఎరుపు నొక్కండి");
            orb.setState(OrbView.IDLE);
            return;
        }
        orb.setState(OrbView.IDLE);
        status.setText(callText);
        main.removeCallbacks(askAgain);
        main.postDelayed(askAgain, CALL_RING_GAP_MS);
    }

    /** Answer (byVoice: the phone isn't in his hand: the loudspeaker) or cut, by the buttons or his word. */
    private void doCall(boolean answer, boolean byVoice) {
        if (callText == null || otherCall()) return;
        if (!answer) { CallWords.Word w = new CallWords.Word(); w.kind = CallWords.DECLINE; cutCall(w, !byVoice); return; }
        stopCallTalk();
        String r = CallControl.answer(this);
        if ("no_call".equals(r) && CallCare.doneJustNow(true)) r = "answered"; // (the watch answered it a moment ago)
        endCallMode();
        if ("answered".equals(r)) {
            CallCare.answered(this, byVoice);
            boolean spk = byVoice && CallCare.speakerWanted(this);
            showReply(spk ? "కాల్ ఎత్తాను, స్పీకర్ ఆన్ చేస్తున్నాను." : "కాల్ ఎత్తాను.", false);
            main.postDelayed(this::closeSheet, spk ? 150 : 500); // (the speaker press waits for this panel to go: it covers the call screen's buttons)
            return;
        }
        callFailed(r, true);
    }

    /** Cut (his word, the button, or a ready-message tap); then the message / the reminder, if he wants one. */
    private void cutCall(CallWords.Word w, boolean tapped) {
        if (callText == null || otherCall()) return;
        stopCallTalk();
        CallWords.Reply r = CallCare.newReply();
        String res = CallControl.decline(this);
        if ("no_call".equals(res) && CallCare.doneJustNow(false)) res = "declined"; // (cut on the watch a moment ago)
        endCallMode();
        if (!"declined".equals(res)) { callFailed(res, false); return; }
        CallCare.declined(this);
        CallWords.Step step = tapped && w.kind == CallWords.DECLINE ? null : CallCare.claimReply("panel") ? CallCare.afterCut(this, r, w, tapped) : null;
        if (step == null) {
            CallCare.releaseReply("panel");
            showReply("కాల్ కట్ చేశాను.", false);
            main.postDelayed(this::closeSheet, 1200);
            return;
        }
        callReply = r;
        doStep(step);
    }

    /** A ready message tapped while it rings: cut + that SMS (the tap is his yes). */
    private void quickTap(String q) {
        if (callText == null) return;
        CallWords.Word w = new CallWords.Word();
        w.kind = CallWords.MESSAGE;
        w.quick = q;
        cutCall(w, true);
    }

    private void stopCallTalk() {
        if (voice.listening) voice.cancelListening();
        voice.stopSpeaking();
        main.removeCallbacks(askAgain);
    }

    private void callFailed(String r, boolean answer) {
        if ("need_permission".equals(r)) {
            showReply("కాల్స్ ఎత్తడానికి/కట్ చేయడానికి అనుమతి కావాలి. Allow నొక్కండి, తర్వాత కాల్స్‌కి పనిచేస్తుంది.", true);
            requestPermissions(new String[]{Manifest.permission.ANSWER_PHONE_CALLS}, 31);
            CallCare.log(this, "⚠️ కాల్స్ ఎత్తే/కట్ చేసే అనుమతి లేదు");
            idle();
            return;
        }
        CallCare.log(this, answer ? "⚠️ ఎత్తలేకపోయాను" : "⚠️ కట్ చేయలేకపోయాను");
        showReply(answer ? "కాల్ ఎత్తలేకపోయాను, మీరే నొక్కండి." : "కాల్ కట్ చేయలేకపోయాను, మీరే నొక్కండి.", true);
        main.postDelayed(this::closeSheet, 2500);
    }

    /** "సైలెంట్": this call's ring goes quiet (no voice: he asked for quiet); the buttons stay while it rings. */
    private void silenceCall() {
        if (callText == null || otherCall()) return;
        stopCallTalk();
        boolean ok = CallCare.silence(this);
        WatchHub.callQuiet(this, ok);
        CallCare.log(this, ok ? "🔕 సైలెంట్ చేశాను" : "⚠️ సైలెంట్ కాలేదు");
        quietCallMode(ok);
    }

    private void quietCallMode(boolean ok) {
        orb.setState(OrbView.IDLE);
        status.setText(ok ? "🔕 సైలెంట్ చేశాను. ఎత్తాలంటే ఆకుపచ్చ, కట్ చేయాలంటే ఎరుపు." : "సైలెంట్ చేయలేకపోయాను: ఫోన్ వాల్యూమ్ బటన్ నొక్కండి (⚙️ → 📞 లో 🔕 అనుమతి ఇవ్వండి).");
    }

    /** The talk after the cut, one step at a time (CallWords: the same as on the watch). */
    private void doStep(CallWords.Step s) {
        replyStep = s;
        if (callReply == null) return;
        if (s.send) { sendReply(); return; }
        if (s.remind) CallCare.remind(this, callReply);
        if (s.over) {
            callReply = null;
            CallCare.releaseReply("panel");
            if (s.say.isEmpty()) { closeSheet(); return; }
            followUps = 0;
            showReply(s.say, false);
            orb.setState(OrbView.SPEAKING);
            voice.speak(s.say, prefs.speechRate()); // (then idle: the panel closes by itself)
            return;
        }
        showReply(s.say, false);
        orb.setState(OrbView.SPEAKING);
        voice.speak(s.say, prefs.speechRate()); // its end: listen (onSpeakDone)
    }

    private void sendReply() {
        final CallWords.Reply r = callReply;
        status.setText("💬 పంపుతున్నాను…");
        orb.setState(OrbView.THINKING);
        CallCare.send(this, r, err -> {
            if (callReply != r || isFinishing()) return;
            doStep(CallWords.sent(r, err));
        });
    }

    private void replyHeard(String text) {
        if (callReply == null) return;
        doStep(CallWords.heard(callReply, text));
    }

    private void endCallMode() {
        callText = null;
        main.removeCallbacks(watchCall);
        main.removeCallbacks(askAgain);
        if (callRow != null) callRow.setVisibility(View.GONE);
        if (quickRow != null) quickRow.setVisibility(View.GONE);
        CallCare.ringLow(this, false);
    }

    private void onAction() {
        WaMedia.stop(); // a voice message playing: the stop button stops it
        if (callReply != null) { callReply = null; CallCare.releaseReply("panel"); stopCallTalk(); closeSheet(); return; }
        if (callText != null) { endCallMode(); closeSheet(); return; }
        if (greeting) { cancelGreeting(); closeSheet(); return; } // stop pressed during "చెప్పండి"
        if (live != null) { live.stop("user"); return; }
        if (busy) { generation++; cutInflight(); busy = false; closeSheet(); return; }
        if (voice.speaking) { voice.stopSpeaking(); closeSheet(); return; }
        if (voice.listening) { voice.cancelListening(); closeSheet(); return; }
        listenAgain(true); // idle: tap to talk again
    }

    @Override public void onListening() { status.setText("వింటున్నాను… మాట్లాడండి"); syncPause(); }

    @Override public void onUnderstanding() { status.setText("అర్థం చేసుకుంటున్నాను…"); }

    @Override public void onPartial(String text) {
        partialHeard = text == null ? "" : text.trim();
        if (partialHeard.isEmpty()) heard.setVisibility(View.GONE); // (that was only a noise)
        else showHeard(text);
    }

    @Override public void onHeard(String text) {
        partialHeard = "";
        if (callReply != null) { replyHeard(text == null ? "" : text); return; }
        if (callText != null) {
            if (text == null || text.trim().isEmpty()) askCallAgain(); else onCallWords(text);
            return;
        }
        if (voice.isPaused()) { // "ఆపు" / "కొనసాగించు" / "చాలు", or a new question
            int r = voice.pausedHeard(text);
            if (r != VoiceIO.NEW) { afterPaused(r); return; }
        }
        if (text == null || text.trim().isEmpty()) { idle(); return; }
        followUps = Math.max(followUps, 1); // he answered: the talk goes on (one more listen after this answer)
        ask(text.trim());
    }

    @Override public void onListenFailed(int error) {
        String partial = partialHeard; // heard in this listen only (reset by listen())
        partialHeard = "";
        if (callReply != null) { replyHeard(partial); return; }
        if (callText != null) {
            if (!partial.isEmpty()) onCallWords(partial); else askCallAgain();
            return;
        }
        if (voice.isPaused()) { // nothing (clear) heard while paused: carry on, or stay paused if he paused it
            int r = voice.pausedHeard(partial);
            if (r != VoiceIO.NEW) { afterPaused(r); return; }
            ask(partial);
            return;
        }
        if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && !partial.isEmpty()) {
            ask(partial);
            return;
        }
        boolean nothing = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
        String why = VoiceIO.failText(error);
        if (!nothing) showReply(error == 12 || error == 13 ? why : why + " మళ్లీ ప్రయత్నించండి.", true); // why the mic stopped
        Sfx.micOff(this, prefs); // he hears that the mic closed
        idle();
        if (nothing && followListen) return; // silence after an answer: the talk is simply over
        status.setText((nothing ? "ఏమీ వినిపించలేదు. " : "మైక్ ఆగిపోయింది. ")
                + (prefs.wakeReady() ? "మళ్లీ \"Jarvis\" అనండి లేదా మైక్ నొక్కండి" : "మళ్లీ మైక్ నొక్కండి"));
    }

    @Override public void onMicTaken() { idle(); }

    @Override public void onLevel(float level) { orb.setLevel(level); }

    @Override public void onSpeakStart() {
        showHeldReply(); // ⚡ E: the words with the voice
        if (live == null) setVolumeControlStream(voice.volumeStream()); // volume keys = Jarvis's voice (the AI assistant volume)
        orb.setState(OrbView.SPEAKING);
        status.setText("మాట్లాడుతున్నాను…");
        syncPause();
    }

    @Override public void onSpeakDone() {
        showHeldReply(); // (the voice failed before it started: the words come now)
        if (live == null) setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC); // (Jarvis is quiet: songs again)
        syncPause();
        if (callText != null) { main.postDelayed(this::listen, 150); return; }
        if (callReply != null) { if (replyStep != null && replyStep.listen) main.postDelayed(this::listen, 200); return; }
        if (busy) return; // (⚡ B: the brain still writes this answer: its arrival ends the turn, not this)
        if (liveAfterSpeak) { // he switched to Live by voice: the talk goes on live
            liveAfterSpeak = false;
            if (prefs.liveReady() && Net.online(this) && live == null) { startLive(); return; }
            if (!Net.online(this)) status.setText("ఇంటర్నెట్ లేదు: నెట్ వచ్చాక Live మొదలవుతుంది");
        }
        String lang = Tools.takeInterpreter();
        if (lang != null && live == null) { // "హిందీ అనువాదకుడిగా ఉండు": a live two-way interpreter from now on
            startInterpreter(lang);
            return;
        }
        if (stopped) { closeSheet(); return; }
        // Booking in an app: Jarvis asked him a choice (theatre, time, seats): listen for the answer.
        if (Tools.awaitingAnswer()) { followListen = false; main.postDelayed(this::listen, 250); return; }
        // One follow-up question without saying "Jarvis" again, like a real conversation.
        if ((prefs.followUp() || dialog) && followUps > 0 && !pageBeingRead()) { // (not while a page is read aloud: the mic would hear it)
            followUps--;
            followListen = !dialog;
            main.postDelayed(this::listen, 250);
        } else {
            idle();
        }
    }

    @Override public void onVoiceReady() {}

    /** He talked over Jarvis: listen to him straight away (not only as a limited follow-up). */
    @Override public void onBargeIn() {
        syncPause();
        if (live != null || isFinishing()) return;
        followListen = false;
        if (callText != null) { main.postDelayed(this::listen, 100); return; }
        main.postDelayed(this::listen, 100);
    }

    private void idle() { idle(STAY_OPEN_MS); }

    /** A page is being read aloud right now (a paused one doesn't stop the talk: it waits for "కొనసాగించు"). */
    private boolean pageBeingRead() {
        ScreenReader r = ScreenReader.get(this);
        return r.active() && !r.paused();
    }

    /** Nothing more to say or hear now: the panel stays a while, listening for "Jarvis" again, then closes. */
    private void idle(long stayMs) {
        Filler.stop(); // (the talk is over, e.g. an error after a phone job: the wake mic may open now)
        Tools.takeInterpreter(); // an interpreter request that was never started must not start later
        orb.setState(OrbView.IDLE);
        boolean wake = prefs.wakeReady();
        status.setText(wake ? "ఇంకేమైనా కావాలంటే \"Jarvis\" అనండి లేదా మైక్ నొక్కండి" : "ఇంకేమైనా కావాలంటే మైక్ నొక్కండి");
        setAction(IconView.MIC);
        main.removeCallbacks(autoClose);
        waiting = true;
        MainActivity.talking(false); // the talk is over: the wake word may listen
        if (wake) WakeService.resume(this);
        main.postDelayed(autoClose, stayMs);
        if (stopped) { main.removeCallbacks(closeIfAway); main.postDelayed(closeIfAway, 1500); } // he is in another app
    }

    /** Switched to Live by voice: Live starts when the confirmation has been said. */
    private boolean liveAfterSpeak;

    /** "Google వాయిస్‌కి మారు", "Live పెట్టు", "Live ఆపు": done here at once (on the bike, no Settings). */
    private void switchVoice(String text, String mode) {
        showHeard(text);
        store.addChat("user", text, false);
        JSONObject r = VoiceSwitch.apply(prefs, mode);
        boolean ok = r.optBoolean("ok");
        String say = r.optString("say");
        store.addChat("assistant", say, false);
        showReply(say, !ok);
        liveAfterSpeak = ok && prefs.liveReady();
        if (prefs.voiceReplies()) voice.speak(say, prefs.speechRate());
        else if (liveAfterSpeak && Net.online(this)) { liveAfterSpeak = false; startLive(); }
        else { liveAfterSpeak = false; idle(); }
    }

    private void ask(String text) {
        main.removeCallbacks(autoClose);
        waiting = false;
        String mode = VoiceSwitch.match(text);
        if (mode != null) { switchVoice(text, mode); return; }
        if (!prefs.hasBrain()) {
            showReply("నా మెదడుకి API key లేదు. Jarvis యాప్ సెట్టింగ్స్‌లో పెట్టండి.", true);
            idle();
            return;
        }
        showHeard(text);
        if (prefs.speedE() && prefs.voiceReplies()) reply.setVisibility(View.GONE); // ⚡ E: the last answer goes; the new one comes with its voice
        List<JSONObject> history = store.chat();
        store.addChat("user", text, false);
        busy = true;
        orb.setState(OrbView.THINKING);
        status.setText("ఆలోచిస్తున్నాను…");
        setAction(IconView.STOP);
        cutInflight(); // (a new question over one still on its way: that request is cut)
        final int gen = ++generation;
        final Http.Cancel cut = new Http.Cancel();
        inflight = cut;
        showHeldReply();
        if (prefs.voiceReplies()) {
            NaturalVoice.warm(this, prefs); // ⚡ G: the voice's line opens while the brain thinks
            Filler.prepare(this, prefs);    // ⚡ F: "ఒక్క క్షణం" made once, ready for a phone job
        }
        final boolean[] fillerSaid = {false};
        final String[] early = {null}; // ⚡ B: the first sentence spoken while the brain still wrote the rest
        Brain.Status progress = new Brain.Status() {
            @Override public void update(String s) { main.post(() -> { if (gen == generation) status.setText(s); }); }
            // ⚡ B: the answer's first sentence is spoken at once; its rest follows when written
            @Override public boolean wantsEarly() { return prefs.voiceReplies() && live == null && !isFinishing() && voice.canSpeakEarly(); }
            @Override public boolean early(String first) {
                if (gen != generation || live != null || isFinishing()) return false;
                main.post(() -> {
                    if (gen != generation || !busy || live != null || isFinishing() || early[0] != null) return;
                    Filler.stop();
                    if (!voice.speakFirst(first, prefs.speechRate())) return;
                    early[0] = first;
                    showReply(first + " …", false);
                });
                return true;
            }
            @Override public boolean cancelled() { return gen != generation; } // stop pressed / panel closed
            // ⚡ F: a phone job inside the answer: "ఒక్క క్షణం, చూస్తున్నాను" (once, and only if it isn't over at once)
            @Override public void toolRound(String tool) {
                if (!Filler.before(tool)) return;
                main.post(() -> {
                    if (gen != generation || fillerSaid[0] || !prefs.speedF() || !prefs.voiceReplies() || live != null || isFinishing()) return;
                    fillerSaid[0] = true;
                    main.postDelayed(() -> {
                        if (gen == generation && busy && live == null && !isFinishing() && !voice.speaking && !voice.listening)
                            Filler.play(SheetActivity.this, prefs);
                    }, 500);
                });
            }
        };
        worker.submit(() -> { Http.CANCEL.set(cut); try {
            String answer = null, error = null;
            try {
                answer = brain.ask(history, text, null, progress);
            } catch (java.util.concurrent.CancellationException e) {
                return; // Anil stopped it: no error bubble
            } catch (Http.ApiError e) {
                String said = Models.explain(prefs, e);
                error = said != null ? said
                        : e.status == 401 || e.status == 403 ? "API key పనిచేయడం లేదు. సెట్టింగ్స్ చూడండి."
                        : e.status == 429 ? "కొంచెం ఆగి మళ్లీ అడగండి (లిమిట్/బ్యాలెన్స్)."
                        : "పొరపాటు జరిగింది (" + e.status + ").";
            } catch (UnknownHostException e) {
                error = "ఇంటర్నెట్ కనెక్షన్ లేదు.";
            } catch (Exception e) {
                error = "ఏదో తప్పు జరిగింది: " + e.getMessage();
            }
            final String a = answer, err = error;
            main.post(() -> {
                if (gen != generation || isFinishing()) return;
                busy = false;
                boolean spoke = early[0] != null;
                if (err == null && (a == null || a.trim().isEmpty())) { // (never stuck)
                    showReply(Brain.EMPTY, true);
                    if (!(spoke && voice.finishOpen(null))) idle();
                    return;
                }
                if (err != null) { // ⚡ B: after a first sentence, "the rest didn't come" is said; the turn ends with it
                    showReply(err, true);
                    if (!(spoke && voice.finishOpen(null))) idle();
                    return;
                }
                store.addChat("assistant", a, false);
                if (spoke) { // ⚡ B: the rest after the first sentence, in the same voice
                    showReply(a, false);
                    if (!voice.finishOpen(a)) idle(); // (that speech was stopped meanwhile)
                    return;
                }
                if (prefs.voiceReplies() && prefs.speedE()) { // ⚡ E: the words come with the voice (2.5 s at most)
                    heldReply = a;
                    main.postDelayed(showHeld, 2500);
                } else {
                    showReply(a, false);
                }
                if (prefs.voiceReplies()) voice.speak(a, prefs.speechRate()); else idle();
            });
        } finally { Http.CANCEL.remove(); } });
    }

    // ================================================================ live mode

    private void startLive() {
        ScreenReader.pauseIfReading(this);
        if (live != null) return;
        Tools.takeInterpreter(); // a stale request from an earlier turn
        live = LiveTalk.create(this, prefs, tools, brain, this);
        live.start(brain.liveInstructions(store.chat()));
        setVolumeControlStream(live.volumeStream()); // volume keys = Jarvis's live voice (the AI assistant volume)
    }

    private void startInterpreter(String lang) {
        if (live != null) return;
        main.removeCallbacks(autoClose);
        live = LiveTalk.create(this, prefs, tools, brain, this);
        live.start(Brain.interpreterInstructions(prefs.name(), lang));
        setVolumeControlStream(live.volumeStream());
    }

    @Override public void onLiveState(int orbState, String text) {
        orb.setState(orbState);
        status.setText(text);
    }

    @Override public void onLiveUser(String text) {
        showHeard(text); // LiveSession has already saved it to the Store
    }

    @Override public void onLiveUserPartial(String text) { showHeard(text); } // his words as he says them

    @Override public void onLiveJarvisPartial(String text) { showReply(text, false); }

    @Override public void onLiveJarvis(String text) {
        showReply(text, false);
        store.addChat("assistant", text, false);
    }

    @Override public void onLiveLevel(float level) { orb.setLevel(level); }

    @Override public void onLiveError(String message) { showReply("సమస్య: " + message, true); }

    @Override public void onLiveEnded(LiveTalk session, String reason) {
        if (session != live) return; // an older session: the current one is still running
        live = null;
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        if (isFinishing() || callText != null) return; // a call took over: keep the panel for it
        String lang = session.interpreterLang();
        if (lang != null) { startInterpreter(lang); return; } // the interpreter tool ran in live mode
        if ("switched".equals(reason)) { // he switched how Jarvis listens by voice: carry on the new way
            if (prefs.liveReady() && Net.online(this)) startLive(); else main.postDelayed(this::listen, 300);
            return;
        }
        main.postDelayed(autoClose, 1200);
    }

    // ================================================================ Tools.Host

    @Override public Activity activity() { return this; }

    @Override public boolean confirm(String title, String message, String yes, int autoSeconds) {
        return Dialogs.confirm(this, title, message, yes, autoSeconds);
    }

    @Override public void askPermissions(String[] permissions) {
        runOnUiThread(() -> requestPermissions(permissions, 31));
    }

    @Override public void notice(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    /** He tapped a station in the radio list: stop talking / listening and close, so the mic doesn't take the radio for his voice. */
    void radioPicked() {
        main.removeCallbacks(autoClose);
        runOnUiThread(() -> {
            if (live != null || callText != null) return;
            if (busy) { generation++; cutInflight(); busy = false; } // "ఏ స్టేషన్?" still on its way: not over the radio
            if (voice.speaking) voice.stopSpeaking();
            if (voice.listening) voice.cancelListening();
            idle(5000); // closes by itself shortly (after the radio app has answered, if it is being asked): the radio is what he wants now
        });
    }
}
