package com.anil.jarvis;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Toast;

import org.json.JSONObject;

import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A question he asked on the watch, answered by the phone's brain with all its tools (WatchHub sends the words here).
 * Jarvis's tools need a screen of the app to work from, so this is an invisible one: nothing shows on the phone,
 * touches go straight through to whatever is under it, and it closes a minute after the talk ends. The answer goes
 * back to the watch; a tool's "are you sure?" is asked on the watch.
 */
public class WatchTalkActivity extends Activity implements Tools.Host {
    static final String EXTRA_TEXT = "watch_text";
    /** The one open now (main thread), or null. */
    static volatile WatchTalkActivity current;

    private Prefs prefs;
    private Store store;
    private Tools tools;
    private Brain brain;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Bumped to drop an answer still on its way (his stop, a new question). */
    private volatile int generation;
    /** The question the brain is working on now (a tool's "are you sure?" for a dropped one is never asked). */
    private volatile int working;
    private boolean busy;
    /** One more listen after an answer without "Jarvis" again (as in the panel), while he keeps answering. */
    private int followUps = 1;
    /** Kept while a follow-up may come (his reply after an answer); otherwise closed at once after the talk. */
    private static final long STAY_MS = 60_000, AFTER_MS = 1500;
    private final Runnable close = this::finish;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // invisible and untouchable: the phone stays exactly as it was (a fully see-through window lets his touches
        // reach the app under it; a merely transparent one would block them)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.alpha = 0f;
        getWindow().setAttributes(lp);
        current = this;
        prefs = new Prefs(this);
        store = Store.get(this);
        tools = new Tools(this, store, prefs);
        brain = new Brain(prefs, store, tools);
        if (b == null) take(getIntent()); // (rebuilt by Android: the question was already taken)
        else main.postDelayed(close, AFTER_MS);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        take(i);
    }

    private void take(Intent i) {
        String t = i == null ? null : i.getStringExtra(EXTRA_TEXT);
        if (i != null) i.removeExtra(EXTRA_TEXT);
        if (t != null && !t.trim().isEmpty()) hear(t.trim());
        else main.postDelayed(close, STAY_MS);
    }

    /** His words from the watch (main thread). */
    void hear(String text) {
        main.removeCallbacks(close);
        if (busy) { generation++; busy = false; WatchHub.releaseConfirms(); } // a new question over one still on its way
        followUps = Math.max(followUps, 1); // he answered: the talk goes on
        String mode = VoiceSwitch.match(text);
        if (mode != null) {
            store.addChat("user", text, false);
            JSONObject r = VoiceSwitch.apply(prefs, mode);
            String say = r.optString("say");
            store.addChat("assistant", say, false);
            answer(say, !r.optBoolean("ok"));
            return;
        }
        if (!prefs.hasBrain() && Net.online(this)) {
            answer("నా మెదడుకి API key లేదు. ఫోన్‌లో Jarvis సెట్టింగ్స్‌లో పెట్టండి.", true);
            return;
        }
        List<JSONObject> history = store.chat();
        store.addChat("user", text, false);
        busy = true;
        final int gen = ++generation;
        working = gen;
        Brain.Status progress = new Brain.Status() {
            @Override public void update(String s) { if (gen == generation) WatchHub.state(WatchTalkActivity.this, "thinking", null, null, s); }
            @Override public boolean cancelled() { return gen != generation; }
        };
        worker.submit(() -> {
            String reply = null, error = null;
            try {
                reply = brain.ask(history, text, null, progress);
            } catch (java.util.concurrent.CancellationException e) {
                return;
            } catch (Http.ApiError e) {
                String said = Models.explain(prefs, e);
                error = said != null ? said
                        : e.status == 401 || e.status == 403 ? "API key పనిచేయడం లేదు. ఫోన్ సెట్టింగ్స్ చూడండి."
                        : e.status == 429 ? "కొంచెం ఆగి మళ్లీ అడగండి (లిమిట్/బ్యాలెన్స్)."
                        : "పొరపాటు జరిగింది (" + e.status + ").";
            } catch (UnknownHostException e) {
                error = "ఇంటర్నెట్ కనెక్షన్ లేదు.";
            } catch (Exception e) {
                error = "ఏదో తప్పు జరిగింది: " + e.getMessage();
            }
            final String a = reply, err = error;
            main.post(() -> {
                if (gen != generation || isDestroyed()) return;
                busy = false;
                if (err != null) { answer(err, true); return; }
                store.addChat("assistant", a, false);
                answer(a, false);
            });
        });
    }

    /** The answer to the watch, said if he wants it said; then listen again, or the talk is over. */
    private void answer(String text, boolean error) {
        final int gen = generation;
        boolean speak = WatchHub.speakOn(this) && prefs.voiceReplies();
        WatchHub.reply(this, text, error, speak, () -> {
            if (gen != generation || isDestroyed()) return;
            Tools.takeInterpreter(); // (a live interpreter can't run on the watch: never started later)
            if (Tools.awaitingAnswer()) { WatchHub.listen(this, "answer"); return; } // a booking asked him a choice
            if (!error && speak && prefs.followUp() && followUps > 0) {
                followUps--;
                WatchHub.listen(this, "follow");
                main.postDelayed(close, STAY_MS);
                return;
            }
            done();
        });
    }

    private void done() {
        WatchHub.idle(this, null);
        main.removeCallbacks(close);
        main.postDelayed(close, AFTER_MS);
    }

    /** The watch ended the talk itself (silence after an answer, its own error). Main thread. */
    void quietEnd() {
        if (busy) return;
        main.removeCallbacks(close);
        main.postDelayed(close, AFTER_MS);
    }

    /** His stop on the watch (or a new "Jarvis" over the answer): whatever is on its way is dropped. Main thread. */
    void stopTalk() {
        generation++;
        busy = false;
        main.removeCallbacks(close);
        main.postDelayed(close, STAY_MS);
    }

    @Override protected void onDestroy() {
        generation++;
        if (current == this) current = null;
        main.removeCallbacksAndMessages(null);
        WatchHub.releaseConfirms();
        worker.shutdownNow();
        super.onDestroy();
    }

    // ================================================================ Tools.Host

    @Override public Activity activity() { return this; }

    @Override public boolean confirm(String title, String message, String yes, int autoSeconds) {
        if (working != generation) return false; // he stopped it, or asked something new
        return WatchHub.confirm(this, title, message, yes, autoSeconds);
    }

    @Override public void askPermissions(String[] permissions) {
        WatchHub.notice(this, "ఫోన్‌లో ఒక అనుమతి కావాలి: ఫోన్‌లో Jarvis తెరిచి అడగండి.");
    }

    @Override public void notice(String text) {
        WatchHub.notice(this, text);
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }
}
