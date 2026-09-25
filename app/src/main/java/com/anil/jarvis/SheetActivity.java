package com.anil.jarvis;

import android.Manifest;
import android.app.Activity;
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
    private Prefs prefs;
    private Store store;
    private Tools tools;
    private Brain brain;
    private VoiceIO voice;
    private LiveSession live;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private OrbView orb;
    private TextView status, heard, reply;
    private IconView action;
    private boolean busy, stopped;
    /** How many more times to listen after Jarvis speaks without "Jarvis" again (a conversation). */
    private int followUps = 1;
    /** A message/suggestion flow: keep listening for the answers even if follow-up is off. */
    private boolean dialog;
    private int generation;

    private final Runnable autoClose = this::closeSheet;

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
    private String callText;      // non-null while asking about a ringing call
    private int callTries;
    private boolean ringMuted;
    private LinearLayout callRow;
    private final Runnable watchCall = new Runnable() {
        @Override public void run() {
            if (callText == null) return;
            if (!CallControl.isRinging()) { endCallMode(); closeSheet(); return; } // answered elsewhere or stopped
            main.postDelayed(this, 1000);
        }
    };

    // ================================================================ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        // The panel only stays open while Anil and Jarvis talk, so keep the screen lit meanwhile.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = new Prefs(this);
        store = Store.get(this);
        tools = new Tools(this, store, prefs);
        brain = new Brain(prefs, store, tools);
        voice = new VoiceIO(this, prefs, this);
        setContentView(buildUi());
        if (!startCallMode(getIntent()) && !startAnnounce(getIntent()) && !startRun(getIntent())) begin();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (startCallMode(intent)) return;
        if (live == null && !busy && !voice.listening && !voice.speaking && startAnnounce(intent)) return;
        if (live == null && !busy && !voice.listening && !voice.speaking && startRun(intent)) return;
        if (live == null && !busy && !voice.listening) begin(); // called again while the panel is open
    }

    @Override protected void onStart() {
        super.onStart();
        stopped = false; // back on top (e.g. after Jarvis typed a message in WhatsApp)
    }

    @Override protected void onStop() {
        super.onStop();
        stopped = true;
        // Another app came in front (e.g. Jarvis opened YouTube): finish once Jarvis has finished speaking.
        if (callText == null && live == null && !voice.speaking && !busy) closeSheet();
    }

    @Override protected void onDestroy() {
        WaMedia.stop();
        muteRing(false);
        if (live != null) live.stop("closed");
        voice.shutdown();
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        MainActivity.inConversation = false;
        if (prefs.wakeReady()) WakeService.resume(this);
        super.onDestroy();
    }

    private void closeSheet() {
        if (!isFinishing()) finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }

    // ================================================================ UI

    private int dp(float v) { return Ui.dp(this, v); }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> { FindPhone.stop(this); closeSheet(); }); // tap outside the card to dismiss

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Ui.INK);
        float r = dp(26);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(dp(1), Ui.LINE2);
        card.setBackground(bg);
        card.setPadding(dp(18), dp(16), dp(18), dp(22));
        card.setClickable(true); // taps inside the card don't close it

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        orb = new OrbView(this);
        top.addView(orb, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(8), 0);
        TextView name = Ui.mono(this, "JARVIS", 15, Ui.CYAN);
        name.setLetterSpacing(0.3f);
        col.addView(name);
        status = Ui.text(this, Greeting.text(prefs), 17, Ui.TEXT);
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(status);
        top.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

        FrameLayout btn = new FrameLayout(this);
        btn.setBackground(Ui.round(this, Ui.GOLD, 0, 24));
        action = new IconView(this, IconView.STOP, Ui.GOLD_INK);
        btn.addView(action, new FrameLayout.LayoutParams(-1, -1));
        btn.setOnClickListener(v -> onAction());
        btn.setContentDescription("ఆపు / మాట్లాడు");
        top.addView(btn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        card.addView(top);

        // Incoming call: two big buttons besides the voice answer.
        callRow = new LinearLayout(this);
        callRow.setPadding(0, dp(14), 0, 0);
        callRow.setVisibility(View.GONE);
        TextView pick = Ui.text(this, "📞  ఎత్తు", 17, 0xFF06210F);
        pick.setGravity(Gravity.CENTER);
        pick.setPadding(0, dp(12), 0, dp(12));
        pick.setBackground(Ui.round(this, 0xFF3DDC84, 0, 24));
        pick.setOnClickListener(v -> doCall(true));
        TextView cut = Ui.text(this, "✖  కట్", 17, 0xFF2A0703);
        cut.setGravity(Gravity.CENTER);
        cut.setPadding(0, dp(12), 0, dp(12));
        cut.setBackground(Ui.round(this, Ui.RED, 0, 24));
        cut.setOnClickListener(v -> doCall(false));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1);
        half.setMargins(0, 0, dp(6), 0);
        callRow.addView(pick, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, -2, 1);
        half2.setMargins(dp(6), 0, 0, 0);
        callRow.addView(cut, half2);
        card.addView(callRow);

        final int maxText = getResources().getDisplayMetrics().heightPixels * 2 / 5;
        ScrollView scroll = new ScrollView(this) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxText, MeasureSpec.AT_MOST));
            }
        };
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(0, dp(10), 0, 0);
        heard = Ui.text(this, "", 15, Ui.GOLD);
        heard.setVisibility(View.GONE);
        texts.addView(heard);
        reply = Ui.text(this, "", 16.5f, Ui.TEXT);
        reply.setLineSpacing(0, 1.25f);
        reply.setPadding(0, dp(6), 0, 0);
        reply.setVisibility(View.GONE);
        texts.addView(reply);
        scroll.addView(texts);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        TextView open = Ui.text(this, "Jarvis యాప్ తెరువు →", 14, Ui.CYAN2);
        open.setPadding(0, dp(12), 0, 0);
        open.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            closeSheet();
        });
        card.addView(open);

        root.addView(card, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return root;
    }

    private void setAction(int icon) { action.setIcon(icon); }

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
        MainActivity.inConversation = true;
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
        Greeting.play(this, prefs, () -> {
            if (isFinishing()) return;
            if (prefs.liveReady() && Net.online(this)) startLive(); else listen();
        });
    }

    private void listen() {
        if (!voice.canListen()) {
            showReply("ఈ ఫోన్‌లో Google వాయిస్ టైపింగ్ లేదు.", true);
            main.postDelayed(autoClose, 4000);
            return;
        }
        voice.listen(prefs.listenLang());
        orb.setState(OrbView.LISTENING);
        status.setText("వింటున్నాను…");
        setAction(IconView.STOP);
    }

    // ================================================================ incoming call

    private boolean startCallMode(Intent i) {
        String text = i == null ? null : i.getStringExtra(EXTRA_CALL);
        if (text == null) return false;
        i.removeExtra(EXTRA_CALL);
        if (live != null) live.stop("call");
        voice.stopSpeaking();
        if (voice.listening) voice.cancelListening();
        generation++;
        busy = false;
        main.removeCallbacks(autoClose);
        MainActivity.inConversation = true;
        WakeService.pause(this);
        callText = text;
        callTries = 0;
        muteRing(true); // so Jarvis can be heard, and can hear Anil
        callRow.setVisibility(View.VISIBLE);
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
        main.removeCallbacks(autoClose);
        MainActivity.inConversation = true;
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
        main.removeCallbacks(autoClose);
        MainActivity.inConversation = true;
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

    private static final String[] CALL_NO = {"కట్", "cut", "reject", "వద్దు", "decline", "తర్వాత", "busy", "బిజీ", "no", "నో", "తీయకు", "ఎత్తకు"};
    private static final String[] CALL_YES = {"ఎత్తు", "ఎత్తండి", "ఎత్తి", "లిఫ్ట్", "lift", "answer", "attend", "pick", "yes", "అవును", "ఓకే", "ok", "సరే", "మాట్లాడ", "ఆన్సర్"};

    private void onCallWords(String t) {
        String low = t.toLowerCase(Locale.ROOT);
        for (String w : CALL_NO) if (low.contains(w)) { doCall(false); return; }
        for (String w : CALL_YES) if (low.contains(w)) { doCall(true); return; }
        askCallAgain();
    }

    private void askCallAgain() {
        if (callText == null) return;
        if (++callTries >= 3 || !CallControl.isRinging()) {
            status.setText("ఎత్తాలంటే ఆకుపచ్చ, కట్ చేయాలంటే ఎరుపు నొక్కండి");
            orb.setState(OrbView.IDLE);
            return;
        }
        voice.speak("ఎత్తమంటారా, కట్ చేయమంటారా?", prefs.speechRate());
    }

    private void doCall(boolean answer) {
        if (callText == null) return;
        if (voice.listening) voice.cancelListening();
        voice.stopSpeaking();
        String r = answer ? CallControl.answer(this) : CallControl.decline(this);
        endCallMode();
        switch (r) {
            case "answered":
                showReply("కాల్ ఎత్తాను.", false);
                main.postDelayed(this::closeSheet, 500);
                break;
            case "declined":
                showReply("కాల్ కట్ చేశాను.", false);
                main.postDelayed(this::closeSheet, 1200);
                break;
            case "need_permission":
                showReply("కాల్స్ ఎత్తడానికి/కట్ చేయడానికి అనుమతి కావాలి. Allow నొక్కండి, తర్వాత కాల్స్‌కి పనిచేస్తుంది.", true);
                requestPermissions(new String[]{Manifest.permission.ANSWER_PHONE_CALLS}, 31);
                idle();
                break;
            default:
                showReply(answer ? "కాల్ ఎత్తలేకపోయాను, మీరే నొక్కండి." : "కాల్ కట్ చేయలేకపోయాను, మీరే నొక్కండి.", true);
                main.postDelayed(this::closeSheet, 2500);
                break;
        }
    }

    private void endCallMode() {
        callText = null;
        main.removeCallbacks(watchCall);
        if (callRow != null) callRow.setVisibility(View.GONE);
        muteRing(false);
    }

    private void muteRing(boolean mute) {
        if (mute == ringMuted) return;
        try {
            android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
            if (am != null) am.adjustStreamVolume(android.media.AudioManager.STREAM_RING,
                    mute ? android.media.AudioManager.ADJUST_MUTE : android.media.AudioManager.ADJUST_UNMUTE, 0);
            ringMuted = mute;
        } catch (Exception ignored) {
            // without Do Not Disturb access Android may refuse; Jarvis still talks over the ringtone
        }
    }

    private void onAction() {
        WaMedia.stop(); // a voice message playing: the stop button stops it
        if (callText != null) { endCallMode(); closeSheet(); return; }
        if (live != null) { live.stop("user"); return; }
        if (busy) { generation++; busy = false; closeSheet(); return; }
        if (voice.speaking) { voice.stopSpeaking(); closeSheet(); return; }
        if (voice.listening) { voice.cancelListening(); closeSheet(); return; }
        listen(); // idle: tap to talk again
    }

    @Override public void onListening() { status.setText("వింటున్నాను… మాట్లాడండి"); }

    @Override public void onPartial(String text) { showHeard(text); }

    @Override public void onHeard(String text) {
        if (callText != null) {
            if (text == null || text.trim().isEmpty()) askCallAgain(); else onCallWords(text);
            return;
        }
        if (text == null || text.trim().isEmpty()) { idle(); return; }
        ask(text.trim());
    }

    @Override public void onListenFailed(int error) {
        if (callText != null) {
            String p = heard.getVisibility() == View.VISIBLE ? heard.getText().toString() : "";
            if (!p.trim().isEmpty()) onCallWords(p); else askCallAgain();
            return;
        }
        String partial = heard.getVisibility() == View.VISIBLE ? heard.getText().toString().replace("“", "").replace("”", "").trim() : "";
        if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && !partial.isEmpty()) {
            ask(partial);
            return;
        }
        if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) showReply("వాయిస్‌కి ఇంటర్నెట్ కావాలి.", true);
        idle();
    }

    @Override public void onLevel(float level) { orb.setLevel(level); }

    @Override public void onSpeakStart() {
        orb.setState(OrbView.SPEAKING);
        status.setText("మాట్లాడుతున్నాను…");
    }

    @Override public void onSpeakDone() {
        if (callText != null) { main.postDelayed(this::listen, 150); return; }
        String lang = Tools.takeInterpreter();
        if (lang != null) { // "హిందీ అనువాదకుడిగా ఉండు": a live two-way interpreter from now on
            live = new LiveSession(this, prefs, tools, this);
            live.start(Brain.interpreterInstructions(prefs.name(), lang));
            return;
        }
        if (stopped) { closeSheet(); return; }
        // Booking in an app: Jarvis asked him a choice (theatre, time, seats): listen for the answer.
        if (Tools.awaitingAnswer()) { main.postDelayed(this::listen, 250); return; }
        // One follow-up question without saying "Jarvis" again, like a real conversation.
        if ((prefs.followUp() || dialog) && followUps > 0) {
            followUps--;
            main.postDelayed(this::listen, 250);
        } else {
            idle();
        }
    }

    @Override public void onVoiceReady() {}

    private void idle() {
        orb.setState(OrbView.IDLE);
        status.setText("ఇంకేమైనా కావాలంటే మైక్ నొక్కండి");
        setAction(IconView.MIC);
        main.removeCallbacks(autoClose);
        main.postDelayed(autoClose, 5000);
    }

    private void ask(String text) {
        if (!prefs.hasBrain()) {
            showReply("నా మెదడుకి API key లేదు. Jarvis యాప్ సెట్టింగ్స్‌లో పెట్టండి.", true);
            idle();
            return;
        }
        showHeard(text);
        List<JSONObject> history = store.chat();
        store.addChat("user", text, false);
        busy = true;
        orb.setState(OrbView.THINKING);
        status.setText("ఆలోచిస్తున్నాను…");
        setAction(IconView.STOP);
        final int gen = ++generation;
        worker.submit(() -> {
            String answer = null, error = null;
            try {
                answer = brain.ask(history, text, null, s -> main.post(() -> { if (gen == generation) status.setText(s); }));
            } catch (Http.ApiError e) {
                error = e.status == 401 || e.status == 403 ? "API key పనిచేయడం లేదు. సెట్టింగ్స్ చూడండి."
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
                if (err != null) { showReply(err, true); idle(); return; }
                store.addChat("assistant", a, false);
                showReply(a, false);
                if (prefs.voiceReplies()) voice.speak(a, prefs.speechRate()); else idle();
            });
        });
    }

    // ================================================================ live mode

    private void startLive() {
        live = new LiveSession(this, prefs, tools, this);
        live.start(brain.liveInstructions(store.chat()));
    }

    @Override public void onLiveState(int orbState, String text) {
        orb.setState(orbState);
        status.setText(text);
    }

    @Override public void onLiveUser(String text) {
        store.addChat("user", text, false);
        showHeard(text);
    }

    @Override public void onLiveJarvisPartial(String text) { showReply(text, false); }

    @Override public void onLiveJarvis(String text) {
        showReply(text, false);
        store.addChat("assistant", text, false);
    }

    @Override public void onLiveLevel(float level) { orb.setLevel(level); }

    @Override public void onLiveError(String message) { showReply("సమస్య: " + message, true); }

    @Override public void onLiveEnded(String reason) {
        live = null;
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
}
