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
    private boolean busy, followUpUsed, stopped;
    private int generation;

    private final Runnable autoClose = this::closeSheet;

    // ================================================================ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        prefs = new Prefs(this);
        store = Store.get(this);
        tools = new Tools(this, store, prefs);
        brain = new Brain(prefs, store, tools);
        voice = new VoiceIO(this, prefs, this);
        setContentView(buildUi());
        begin();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (live == null && !busy && !voice.listening) begin(); // called again while the panel is open
    }

    @Override protected void onStop() {
        super.onStop();
        stopped = true;
        // Another app came in front (e.g. Jarvis opened YouTube): finish once Jarvis has finished speaking.
        if (live == null && !voice.speaking && !busy) closeSheet();
    }

    @Override protected void onDestroy() {
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
        root.setOnClickListener(v -> closeSheet()); // tap outside the card to dismiss

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
        Greeting.play(this, prefs, () -> {
            if (isFinishing()) return;
            if (prefs.liveReady()) startLive(); else listen();
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

    private void onAction() {
        if (live != null) { live.stop("user"); return; }
        if (busy) { generation++; busy = false; closeSheet(); return; }
        if (voice.speaking) { voice.stopSpeaking(); closeSheet(); return; }
        if (voice.listening) { voice.cancelListening(); closeSheet(); return; }
        listen(); // idle: tap to talk again
    }

    @Override public void onListening() { status.setText("వింటున్నాను… మాట్లాడండి"); }

    @Override public void onPartial(String text) { showHeard(text); }

    @Override public void onHeard(String text) {
        if (text == null || text.trim().isEmpty()) { idle(); return; }
        ask(text.trim());
    }

    @Override public void onListenFailed(int error) {
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
        if (stopped) { closeSheet(); return; }
        // One follow-up question without saying "Jarvis" again, like a real conversation.
        if (prefs.followUp() && !followUpUsed) {
            followUpUsed = true;
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
