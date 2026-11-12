package com.anil.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 🎙️ Jarvis's ears and voice inside the workshop (Anil, 12 Nov): while the workshop is in front, "Jarvis" opens no panel;
 * the workshop listens itself (like the camera). His words: a ₹0 word or a change / question about the model goes to the
 * workshop; anything else goes to the same brain as the panel and is answered here (its jobs ask him first, as always).
 * After Jarvis speaks it listens once more for a follow-up, then waits for "Jarvis" again. Nothing during a call.
 */
final class WorkshopVoice implements VoiceIO.Listener, Tools.Host {

    /** The workshop screen, as this needs it. */
    interface Owner {
        Activity screen();
        /** His words: true when the workshop took them (a ₹0 word, a change, a question); false = for the brain. */
        boolean take(String words);
        /** The line under the model (what Jarvis hears, thinks, says). */
        void line(String text);
        /** The 🎙️ button's look. */
        void mic(int state);
        /** The workshop is in front (else no follow-up listen). */
        boolean front();
        /** During the "✋ వద్దు" wait: his words go there first (true = used). */
        boolean waitWords(String words);
    }

    static final int IDLE = 0, LISTENING = 1, THINKING = 2, SPEAKING = 3;
    private static final int OPEN = 0, YES = 1, NO = 2;

    /** The mic is in use here now (a shake / "Jarvis" again must not start a second listen). */
    static volatile boolean micInUse;

    private final Owner o;
    private final Prefs p;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private VoiceIO voice;
    private Brain brain;
    private Store store;
    private boolean ready, wantListen, talkSet, stopped;
    /** One more listen after Jarvis has spoken (the follow-up), then "Jarvis" again. */
    private boolean followUp;
    /** He talked to the workshop by voice (its answers are then said here, with a follow-up listen); typing ends it. */
    volatile boolean voiceFlow;
    private volatile int generation;
    private volatile Http.Cancel inflight;
    /** A yes / no Jarvis asked for (a call, a message): his word or tap ends the wait. */
    private volatile CountDownLatch confirmWait;
    private volatile AtomicInteger confirmState;
    private AlertDialog confirmBox;
    private Runnable autoYes;
    private int confirmAuto;
    private int state = IDLE;

    WorkshopVoice(Owner o, Prefs p) {
        this.o = o;
        this.p = p;
    }

    private void ensure() {
        if (voice != null) return;
        Activity a = o.screen();
        voice = new VoiceIO(a, p, this);
        store = Store.get(a);
        brain = new Brain(p, store, new Tools(this, store, p));
    }

    // ================================================================ hearing him

    /** "Jarvis" heard while the workshop is in front (WakeService has let go of the mic), or 🎙️ tapped. */
    void wake() {
        if (stopped) return;
        if (CallControl.busyWithCall()) { o.line("📞 కాల్‌లో ఉన్నప్పుడు వినను"); idle(); return; } // (nothing heard or said during a call)
        ensure();
        if (voice.speaking) voice.stopSpeaking();
        followUp = false;
        hold();
        Sfx.chirp(o.screen(), p);
        o.line("🎙️ చెప్పండి Sir…");
        listenSoon(250);
    }

    /** 🎙️ tapped: listen now, or stop listening. */
    void tap() {
        if (stopped) return;
        if (voice != null && voice.listening) { voice.cancelListening(); idle(); return; }
        wake();
    }

    /** The "✋ వద్దు" wait: a short listen for his word (the mic stays his). */
    void listenShort(int seconds) {
        if (stopped || CallControl.busyWithCall()) return;
        ensure();
        if (voice.speaking || voice.listening) return;
        hold();
        lastPartial = "";
        understanding = false;
        try { voice.listenShort(p.listenLang(), seconds); state(LISTENING); } catch (Exception e) { idle(); }
    }

    /** He is saying something right now (the "వద్దు" wait holds on for it). */
    boolean hearing() { return voice != null && (understanding || voice.listening && !lastPartial.isEmpty()); }

    /** His words are being written out now (the AI ears, after he stopped talking). */
    private boolean understanding;

    private final Runnable listenR = this::listen;

    private void listenSoon(long ms) {
        main.removeCallbacks(listenR);
        main.postDelayed(listenR, ms);
    }

    private void listen() {
        if (stopped || voice == null) return;
        if (CallControl.busyWithCall()) { idle(); return; }
        if (!ready) { wantListen = true; return; }
        hold();
        lastPartial = "";
        understanding = false;
        VoiceIO.yieldOthers(voice);
        voice.listen(p.listenLang());
        state(LISTENING);
    }

    private String lastPartial = "";

    @Override public void onListening() { if (confirmWait == null) o.line("🎙️ వింటున్నాను…"); }

    @Override public void onUnderstanding() { understanding = true; o.line("🎙️ అర్థం చేసుకుంటున్నాను…"); }

    @Override public void onPartial(String text) {
        lastPartial = text == null ? "" : text.trim();
        if (!lastPartial.isEmpty()) o.line("🎙️ “" + lastPartial + "”");
    }

    @Override public void onHeard(String text) {
        if (stopped) return;
        String t = text == null ? "" : text.trim();
        lastPartial = "";
        understanding = false;
        if (confirmWait != null) { heardConfirm(t); return; }
        if (voice.isPaused()) { // (he talked over an answer: "కొనసాగించు" / "ఆపు" / new words)
            int r = voice.pausedHeard(t);
            if (r == VoiceIO.RESUMED) { state(SPEAKING); return; }
            if (r == VoiceIO.HELD || r == VoiceIO.STOPPED) { idle(); return; }
        }
        if (t.isEmpty()) { idle(); return; }
        voiceFlow = true;
        followUp = false;
        if (o.waitWords(t)) { if (state != SPEAKING) idle(); return; }
        o.line("🎙️ “" + t + "”");
        if (o.take(t)) { if (state != SPEAKING) idle(); return; } // (the workshop speaks when it is done; a follow-up listen comes after that)
        think(t);
    }

    @Override public void onListenFailed(int error) {
        lastPartial = "";
        understanding = false;
        if (confirmWait != null) { confirmSilence(); return; }
        if (voice != null && voice.isPaused()) { int r = voice.pausedHeard(""); if (r == VoiceIO.RESUMED) { state(SPEAKING); return; } }
        idle();
    }

    @Override public void onLevel(float level) {}

    @Override public void onVoiceReady() {
        ready = true;
        if (wantListen) { wantListen = false; listenSoon(150); }
    }

    @Override public void onBargeIn() {
        if (stopped || voice == null) return;
        if (!voice.listening) { lastPartial = ""; voice.listen(p.listenLang()); state(LISTENING); } // (the held answer waits)
    }

    @Override public void onMicTaken() { o.line("🎙️ మైక్ వేరే Jarvis స్క్రీన్ తీసుకుంది"); idle(); }

    // ================================================================ speaking

    /** Jarvis says this here (his voice), then listens once more when he is still on the workshop. */
    void say(String text, boolean thenListen) {
        if (stopped || text == null || text.trim().isEmpty()) return;
        if (CallControl.busyWithCall()) { idle(); return; }
        ensure();
        main.removeCallbacks(listenR);
        if (voice.listening) voice.cancelListening();
        hold();
        followUp = thenListen;
        state(SPEAKING);
        if (p.voiceReplies()) voice.speak(Otp.spoken(text), p.speechRate());
        else main.post(this::onSpeakDone);
    }

    @Override public void onSpeakStart() { hold(); state(SPEAKING); } // (the wake word must not hear Jarvis's own voice)

    @Override public void onSpeakDone() {
        if (stopped) return;
        if (confirmWait != null) { // (the yes question was said: his word now; a countdown starts)
            if (confirmAuto > 0 && autoYes == null) startCountdown();
            listenSoon(300);
            return;
        }
        if (followUp && o.front()) { followUp = false; listenSoon(350); return; }
        idle();
    }

    // ================================================================ the brain (the same as the panel's)

    private void think(String text) {
        if (!p.hasBrain()) { say("నా మెదడుకి API key లేదు. Jarvis సెట్టింగ్స్‌లో పెట్టండి.", false); return; }
        state(THINKING);
        o.line("🧠 ఆలోచిస్తున్నాను…");
        final List<JSONObject> history = store.chat();
        store.addChat("user", text, false);
        final int gen = ++generation;
        final Http.Cancel cut = new Http.Cancel();
        inflight = cut;
        final Brain.Status progress = new Brain.Status() {
            @Override public void update(String s) { main.post(() -> { if (gen == generation && !stopped) o.line(s); }); }
            @Override public boolean cancelled() { return gen != generation || stopped; }
        };
        try {
            worker.submit(() -> {
                Http.CANCEL.set(cut);
                try {
                    String answer = null, error = null;
                    try {
                        answer = brain.ask(history, text, null, progress);
                    } catch (java.util.concurrent.CancellationException e) {
                        return;
                    } catch (Http.ApiError e) {
                        String s = Models.explain(p, e);
                        error = s != null ? s : e.status == 429 ? "కొంచెం ఆగి మళ్లీ అడగండి (లిమిట్/బ్యాలెన్స్)." : "పొరపాటు జరిగింది (" + e.status + ").";
                    } catch (java.net.UnknownHostException e) {
                        error = "ఇంటర్నెట్ కనెక్షన్ లేదు.";
                    } catch (Exception e) {
                        if (Http.stopped() || stopped) return;
                        error = "ఏదో తప్పు జరిగింది: " + e.getMessage();
                    }
                    Tools.takeInterpreter(); // (no live interpreter from here)
                    final String a = answer, err = error;
                    main.post(() -> {
                        if (gen != generation || stopped) return;
                        if (inflight == cut) inflight = null;
                        String said = err != null || a == null || a.trim().isEmpty() ? (err != null ? err : Brain.EMPTY) : a;
                        if (err == null && a != null && !a.trim().isEmpty()) store.addChat("assistant", a, false);
                        o.line("💬 " + Otp.shown(said));
                        say(said, true);
                    });
                } finally {
                    Http.CANCEL.remove();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    // ================================================================ Tools.Host: the jobs run from the workshop screen

    @Override public Activity activity() { return o.screen(); }

    @Override public void askPermissions(String[] permissions) {
        main.post(() -> { try { o.screen().requestPermissions(permissions, 41); } catch (Exception ignored) {} });
    }

    @Override public void notice(String text) { main.post(() -> { if (!stopped) o.line(text); }); }

    /** A yes / no (a call, a message, a purchase step): a box with ✅ / ✕, said aloud, and his "అవును" / "వద్దు" heard. */
    @Override public boolean confirm(String title, String message, String yes, int autoSeconds) {
        if (stopped) return false;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger st = new AtomicInteger(OPEN);
        main.post(() -> {
            if (stopped) { st.compareAndSet(OPEN, NO); done.countDown(); return; }
            confirmState = st;
            confirmWait = done;
            confirmAuto = Math.max(0, autoSeconds);
            if (autoYes != null) { main.removeCallbacks(autoYes); autoYes = null; }
            String msg = message == null ? "" : message.trim();
            try {
                confirmBox = new AlertDialog.Builder(o.screen()).setTitle(title).setMessage(Otp.shown(msg))
                        .setPositiveButton(yes == null || yes.isEmpty() ? "✅ అవును" : "✅ " + yes, (d, w) -> confirmed(st, done, true))
                        .setNegativeButton("✕ వద్దు", (d, w) -> confirmed(st, done, false))
                        .setOnCancelListener(d -> confirmed(st, done, false)).show();
            } catch (Exception e) { confirmBox = null; }
            String q = plain(title) + (msg.isEmpty() ? "" : ". " + plain(msg));
            say(confirmAuto > 0 ? q + ". " + confirmAuto + " సెకన్లలో చేస్తాను. వద్దంటే వద్దు అనండి." : q + "?", false);
        });
        try { done.await(90 + Math.max(0, autoSeconds), TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (st.compareAndSet(OPEN, NO)) main.post(() -> { if (confirmState == st) closeConfirm(); o.line("జవాబు రాలేదు: చేయలేదు"); });
        return st.get() == YES;
    }

    private void heardConfirm(String t) {
        CountDownLatch done = confirmWait;
        AtomicInteger st = confirmState;
        if (done == null || st == null) return;
        int k = CardTalk.Words.kind(t, CardTalk.Words.CONFIRM);
        if (k == CardTalk.Words.YES) { confirmed(st, done, true); return; }
        if (k == CardTalk.Words.NO || k == CardTalk.Words.CHANGE || !t.isEmpty() && CardTalk.Words.kind(t, CardTalk.Words.REPLY) == CardTalk.Words.LATER) {
            confirmed(st, done, false);
            return;
        }
        confirmSilence();
    }

    /** Nothing clear: a countdown keeps listening for "వద్దు"; otherwise the box waits for his tap. */
    private void confirmSilence() {
        if (confirmWait == null) return;
        if (confirmAuto > 0) { if (autoYes == null) startCountdown(); listenSoon(300); return; }
        o.line("✅ / ✕ నొక్కండి");
        letGo();
        state(IDLE);
    }

    private void startCountdown() {
        final CountDownLatch done = confirmWait;
        final AtomicInteger st = confirmState;
        if (done == null || st == null || autoYes != null) return;
        autoYes = () -> confirmed(st, done, true);
        main.postDelayed(autoYes, confirmAuto * 1000L);
    }

    private void confirmed(AtomicInteger st, CountDownLatch done, boolean yes) {
        if (!st.compareAndSet(OPEN, yes ? YES : NO)) return;
        if (confirmState == st) closeConfirm();
        done.countDown();
        if (voice != null && voice.speaking) voice.stopSpeaking();
        if (voice != null && voice.listening) voice.cancelListening();
        o.line(yes ? "✅ సరే" : "✕ చేయలేదు");
        state(THINKING);
    }

    private void closeConfirm() {
        if (autoYes != null) { main.removeCallbacks(autoYes); autoYes = null; }
        confirmWait = null;
        confirmState = null;
        if (confirmBox != null) { try { confirmBox.dismiss(); } catch (Exception ignored) {} confirmBox = null; }
    }

    /** Emoji / signs are not said. */
    private static String plain(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\p{So}\\p{Cn}\\x{FE0F}\\x{200D}]+", " ").replaceAll("\\s+", " ").trim();
    }

    // ================================================================ the mic and the wake word

    /** Jarvis is talking with him: the wake word lets go of the mic. */
    private void hold() {
        micInUse = true;
        if (!talkSet && !MainActivity.inConversation) { talkSet = true; MainActivity.talking(true); }
        WakeService.pause(o.screen());
    }

    private void letGo() {
        micInUse = false;
        if (talkSet) { talkSet = false; MainActivity.talking(false); }
        if (p.wakeReady()) WakeService.resume(o.screen());
    }

    /** Nothing more to hear: the mic closes, "Jarvis" works again. */
    void idle() {
        main.removeCallbacks(listenR);
        wantListen = false;
        if (voice != null && voice.listening) voice.cancelListening();
        if (voice != null && voice.speaking && !voice.isPaused()) { state(SPEAKING); return; } // (the answer goes on)
        followUp = false;
        letGo();
        state(IDLE);
    }

    /** The workshop went behind another screen: no more listening (an answer being said is left to finish). */
    void away() {
        main.removeCallbacks(listenR);
        followUp = false;
        if (voice != null && voice.listening) voice.cancelListening();
        if (voice == null || !voice.speaking) { letGo(); state(IDLE); }
    }

    /** He typed: the talk by voice is over. */
    void typed() { voiceFlow = false; }

    private void state(int s) {
        state = s;
        o.mic(s);
    }

    void shutdown() {
        stopped = true;
        generation++;
        main.removeCallbacksAndMessages(null);
        Http.Cancel k = inflight;
        inflight = null;
        if (k != null) k.cancel();
        AtomicInteger st = confirmState;
        CountDownLatch c = confirmWait;
        if (st != null) st.compareAndSet(OPEN, NO);
        if (c != null) c.countDown();
        closeConfirm();
        if (voice != null) voice.shutdown();
        letGo();
        worker.shutdown();
    }
}
