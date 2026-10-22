package com.anil.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.SpeechRecognizer;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Jarvis's own question (cough, water, diary, a suggestion…) or a job he does by himself (the night's summary, arrived
 * somewhere, an automation), on the top card instead of the panel (Anil, 22 Oct: the panel only when he calls "Jarvis").
 * Jarvis says it, listens, and his answer goes to the brain right here: the phone jobs run from Jarvis's service behind the
 * card (a job that needs a screen says so), a yes Jarvis must ask for is asked on the card (buttons or "అవును / వద్దు";
 * nothing is sent, called or deleted without it), and the answer is said with its words on the card. ⏸ / ▶ / ⏪ / 🔁 while
 * it speaks; 🎙️ to say more; ⬆️ opens the panel to go on there. Left unanswered, the question becomes a notification (a tap
 * has the panel ask it), a job not done or not heard one that does it when tapped: nothing is lost.
 * With the phone locked there is no card: the same talk, by voice only.
 */
final class CardAsk implements VoiceIO.Listener, Tools.Host, TopCard.Flow {
    private static final int SAY = 0, LISTEN = 1, THINK = 2, ANSWER = 3, CONFIRM = 4, IDLE = 5;
    /** Answers that end in a question are followed by listening again, at most this many times. */
    private static final int FOLLOW_UPS = 4;
    /** A yes the job waits for: still open, given, refused. */
    private static final int OPEN = 0, YES = 1, NO = 2;

    private final AccessibilityService svc;
    private final TopCard card;
    private final TopCard.Msg m;
    private final Prefs p;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Context ctx;
    private VoiceIO voice;
    private Store store;
    private Brain brain;
    private volatile int step = SAY;
    private volatile boolean stopped;
    private boolean talkSet, answered;
    /** ✕ / ⬆️: he ended it himself (a job then leaves no "do it later" note). */
    private boolean quit;
    /** A job's answer was said in full (else, closed early, the job waits as a notification). */
    private boolean delivered, failed;
    /** A yes he gave in this talk (something was done): a cut job is then never offered to be done again. */
    private volatile boolean acted;
    private long holdSince;
    private int follows, unclear, heldListens;
    /** The words being said now (lit on the card), and the last answer (🔁). */
    private String speaking = "", lastAnswer = "";
    /** What the recognizer heard so far in this listen (a long answer cut by silence is still his answer). */
    private String partial = "";
    /** The AI request on its way: ✕ / closing cuts it, and its remaining tools don't run. */
    private volatile Http.Cancel inflight;
    /** A yes Jarvis asked for on the card: his tap / word ends the wait. Each ask has its own state. */
    private volatile CountDownLatch confirmWait;
    private volatile AtomicInteger confirmState;
    /** The ask that waits counts down (a call, SOS): seconds, started once it has been said. */
    private int confirmAuto;
    private Runnable autoYes;
    private volatile int generation;

    CardAsk(AccessibilityService svc, TopCard card, TopCard.Msg m) {
        this.svc = svc;
        this.card = card;
        this.m = m;
        this.p = new Prefs(svc);
        this.ctx = new CardContext(svc);
        card.onUnheard = this::unheard;
    }

    /** Jarvis's service as the jobs' context: a screen opened from it needs a new task. */
    private static final class CardContext extends ContextWrapper {
        CardContext(Context base) { super(base); }

        @Override public void startActivity(Intent i) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); super.startActivity(i); }

        @Override public void startActivity(Intent i, Bundle o) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); super.startActivity(i, o); }
    }

    private boolean isRun() { return TopCard.RUN.equals(m.kind); }

    // ================================================================ start and end

    void start() {
        voice = new VoiceIO(svc, p, this);
        store = Store.get(svc);
        brain = new Brain(p, store, new Tools(this, store, p));
        if (othersUp()) { yieldToOthers(); return; } // (before taking the talk: what is up keeps it)
        hold();
        if (isRun()) { // a job: done at once, its answer said
            answered = true;
            card.heardNow();
            think(m.run, true);
            return;
        }
        String said = (m.say + " " + m.ask).trim();
        store.addChat("assistant", said + (m.context == null ? "" : m.context), false); // ("Jarvis" later still knows what was asked)
        card.buttons("🎙️ చెప్పండి", (Runnable) this::listenNow, "⏰ తర్వాత", (Runnable) () -> end(true), "✕", (Runnable) this::quit);
        say(said, SAY);
    }

    @Override public boolean talking() { return !stopped && talkSet && android.os.SystemClock.elapsedRealtime() - holdSince < 10 * 60_000L; }

    @Override public boolean busy() { return !stopped && (talkSet || step == CONFIRM || step == THINK || voice != null && voice.isPaused()); }

    @Override public void stop() { stop(0); }

    @Override public void stop(int yield) {
        if (stopped) return;
        stopped = true;
        generation++;
        main.removeCallbacksAndMessages(null);
        Http.Cancel k = inflight;
        inflight = null;
        if (k != null) k.cancel(); // (the request on its way is cut; its remaining tools don't run)
        AtomicInteger st = confirmState;
        CountDownLatch c = confirmWait;
        if (st != null) st.compareAndSet(OPEN, NO); // a job waiting for his yes gets a no
        if (c != null) c.countDown();
        confirmWait = null;
        if (yield == CardTalk.YIELD_AFTER) talkSet = false;
        else if (yield == CardTalk.YIELD_BEFORE && talkSet) { talkSet = false; MainActivity.talking(false); }
        if (voice != null) voice.shutdown();
        letGo();
        worker.shutdown();
        if (isRun() && !delivered && !quit) leftOver();
    }

    /**
     * A job cut before its answer was heard: its answer if it came (to read), a note if something was already done on his
     * yes (never done twice by a tap), else a notification that does it when tapped.
     */
    private void leftOver() {
        try {
            if (!failed && !lastAnswer.isEmpty()) Reminders.notify(svc, "🤖 Jarvis", lastAnswer, m.run.hashCode());
            else if (acted) Reminders.notify(svc, "🤖 Jarvis", "ఈ పని మధ్యలో ఆగింది (మీరు ఒప్పుకున్నది జరిగింది): " + m.run, m.run.hashCode());
            else Proactive.runLater(svc, m.run, null, m.run.hashCode());
        } catch (Exception ignored) {}
    }

    /** Jarvis is talking with him: the wake word lets go of the mic, other cards wait their turn. */
    private void hold() {
        if (talkSet || stopped) return;
        Announcer.stop();
        ScreenReader.pauseIfReading(svc);
        talkSet = !MainActivity.inConversation;
        if (talkSet) MainActivity.talking(true);
        holdSince = android.os.SystemClock.elapsedRealtime();
        WakeService.pause(svc);
    }

    private void letGo() {
        if (!talkSet) return;
        talkSet = false;
        MainActivity.talking(false);
        if (p.wakeReady()) WakeService.resume(svc);
    }

    /** later: not answered, so it stays as a notification. */
    private void end(boolean later) {
        stop();
        card.closeFromTalk(later);
    }

    /** ✕: he closed it. */
    private void quit() {
        quit = true;
        end(false);
    }

    /** The card went before he answered: the question waits as a notification (a tap: the panel asks it again). */
    private void unheard() {
        if (answered || quit || isRun()) return;
        Proactive.askLater(svc, m.say, m.ask, m.context);
    }

    /** The panel, a call or the camera is up: the card must not talk over it (the Jarvis app merely open is fine). */
    private boolean othersUp() {
        return SheetActivity.open || JarvisCamera.open || CallControl.busyWithCall() || MainActivity.inConversation && !talkSet;
    }

    /** It steps aside: the talk state this card took is given back (the panel / camera takes its own). */
    private void yieldToOthers() {
        stop(SheetActivity.open || JarvisCamera.open ? CardTalk.YIELD_BEFORE : 0);
        card.closeFromTalk(!answered);
    }

    // ================================================================ the talk

    private void say(String text, int nextStep) {
        if (stopped || voice == null) return;
        if (othersUp()) { yieldToOthers(); return; }
        step = nextStep;
        speaking = text;
        main.removeCallbacks(listenNowR); // (Jarvis never speaks into his own open mic)
        if (voice.listening) voice.cancelListening();
        hold();
        card.stayOpen();
        card.orb(OrbView.SPEAKING);
        boolean countdown = nextStep == CONFIRM && confirmAuto > 0; // (a call / SOS countdown is always said aloud)
        if (p.voiceReplies() || m.voiceOnly || countdown) voice.speak(Otp.spoken(text), p.speechRate());
        else main.post(this::onSpeakDone); // (voice answers off: the words on the card are the answer)
    }

    private final Runnable listenNowR = this::listen;

    /** 🎙️: he wants to say something (Jarvis stops talking first). */
    private void listenNow() {
        if (stopped || voice == null) return;
        if (voice.speaking) voice.stopSpeaking();
        main.removeCallbacks(listenNowR);
        if (step == IDLE || step == ANSWER) step = LISTEN;
        listen();
    }

    private void listen() {
        if (stopped || voice == null) return;
        if (othersUp()) { yieldToOthers(); return; }
        if (step != CONFIRM) step = LISTEN;
        partial = "";
        hold();
        card.stayOpen();
        card.orb(OrbView.LISTENING);
        card.status("🎙️ వింటున్నాను…");
        voice.listen(p.listenLang());
    }

    /** Held with no screen: one listen for "కొనసాగించు" (the step stays, so the answer carries on where it was). */
    private void listenWhileHeld() {
        if (stopped || voice == null) return;
        partial = "";
        hold();
        card.orb(OrbView.LISTENING);
        voice.listen(p.listenLang());
    }

    /** His words: a yes / no for a job that waits, "తర్వాత", or anything else for the brain. */
    @Override public void onHeard(String text) {
        if (stopped) return;
        String t = text == null ? "" : text.trim();
        partial = "";
        if (step == THINK) return; // (he spoke over "అన్‌లాక్ చేయండి" while the job runs: the job goes on)
        if (step == CONFIRM) { heardConfirm(t); return; }
        if (voice.isPaused()) {
            int r = voice.pausedHeard(t);
            if (r == VoiceIO.RESUMED) { card.status(""); card.orb(OrbView.SPEAKING); return; }
            if (r == VoiceIO.HELD) { held(); return; }
            if (r == VoiceIO.STOPPED) { if (answered) finish(); else end(false); return; }
            // NEW: the paused speech is dropped; these are new words
        }
        if (t.isEmpty()) { onListenFailed(SpeechRecognizer.ERROR_NO_MATCH); return; }
        card.status("🎙️ “" + t + "”");
        // "తర్వాత" / "ఆగు" alone puts it off (a longer answer with తర్వాత in it is an answer: "డ్యూటీ అయిన తర్వాత గుడికి వెళ్ళాను")
        if (!answered && CardTalk.Words.kind(t, CardTalk.Words.REPLY) == CardTalk.Words.LATER) {
            card.status("సరే, తర్వాత");
            end(true);
            return;
        }
        answered = true;
        card.heardNow();
        think(t, true);
    }

    /** His word to "…చేయమంటారా?": a clear yes / no; a stop or wait word is a no; else asked once more. */
    private void heardConfirm(String t) {
        int k = CardTalk.Words.kind(t, CardTalk.Words.CONFIRM);
        boolean no = k == CardTalk.Words.NO || k == CardTalk.Words.CHANGE
                || !t.isEmpty() && CardTalk.Words.kind(t, CardTalk.Words.REPLY) == CardTalk.Words.LATER; // ఆగు / ఆపు / stop: not done
        if (k == CardTalk.Words.YES || no) {
            if (voice.speaking) voice.stopSpeaking(); // (he spoke over the question: his word counts)
            confirmed(k == CardTalk.Words.YES);
            return;
        }
        if (t.isEmpty()) { // a noise over the question: it carries on (the countdown starts once it has been said)
            if (voice.isPaused()) { voice.resume(); card.orb(OrbView.SPEAKING); return; }
            confirmSilence();
            return;
        }
        if (voice.speaking) voice.stopSpeaking();
        card.status("🎙️ “" + t + "”");
        if (++unclear <= 1) {
            say(confirmAuto > 0 ? (autoYes == null ? confirmAuto + " సెకన్లలో చేస్తాను. వద్దంటే వద్దు అనండి." : "వద్దంటే వద్దు అనండి.")
                    : "అవును లేదా వద్దు అని చెప్పండి.", CONFIRM);
            return;
        }
        confirmSilence();
    }

    /** Nothing clear to the yes question: a countdown carries on (and keeps listening for "వద్దు"); else no on a locked phone, buttons on the card. */
    private void confirmSilence() {
        if (confirmWait == null) return;
        if (confirmAuto > 0) {
            if (autoYes == null && !voice.speaking) startCountdown(); // (it was never said in full: the countdown runs anyway)
            main.removeCallbacks(listenNowR);
            main.postDelayed(listenNowR, 300);
            return;
        }
        if (m.voiceOnly) { confirmed(false); return; } // (no screen to tap: not done)
        card.orb(OrbView.IDLE);
        card.status("కింద బటన్ నొక్కండి");
        letGo(); // (the wake word works meanwhile)
    }

    /** The call / SOS countdown, once he has heard it: done at its end unless he says "వద్దు" / taps ✕. */
    private void startCountdown() {
        final CountDownLatch mine = confirmWait;
        final AtomicInteger st = confirmState;
        if (mine == null || st == null || autoYes != null) return;
        autoYes = () -> confirmed(st, mine, true);
        main.postDelayed(autoYes, confirmAuto * 1000L);
    }

    /** The brain answers (its phone jobs run from here); the answer is said with its words on the card. */
    private void think(String text, boolean fromHim) {
        if (stopped) return;
        failed = false;
        if (!p.hasBrain()) { failedWith("నా మెదడుకి API key లేదు. Jarvis యాప్ సెట్టింగ్స్‌లో పెట్టండి."); return; }
        step = THINK;
        card.orb(OrbView.THINKING);
        card.status("ఆలోచిస్తున్నాను…");
        card.buttons("✕", (Runnable) this::quit);
        final List<JSONObject> history = store.chat();
        if (fromHim) store.addChat("user", text, false);
        final int gen = ++generation;
        final Http.Cancel cut = new Http.Cancel();
        inflight = cut;
        Brain.Status progress = new Brain.Status() {
            @Override public void update(String s) { main.post(() -> { if (gen == generation && !stopped) card.status(s); }); }
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
                    Tools.takeInterpreter(); // (a live interpreter can't start from the card: never later either)
                    final boolean waits = Tools.awaitingAnswer(); // a choice he must say (a station, a seat): listen for it
                    final String a = answer, err = error;
                    main.post(() -> {
                        if (gen != generation || stopped) return;
                        if (inflight == cut) inflight = null;
                        if (err != null || a == null || a.trim().isEmpty()) { failedWith(err != null ? err : Brain.EMPTY); return; }
                        store.addChat("assistant", a, false);
                        lastAnswer = a;
                        awaiting = waits;
                        card.status("");
                        card.body(Otp.shown(a), 8);
                        answerButtons();
                        say(a, ANSWER);
                    });
                } finally {
                    Http.CANCEL.remove();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    /** The answer asked him a choice the tools wait for (Tools.awaitingAnswer). */
    private boolean awaiting;

    /** The brain couldn't answer: said too (with no screen, words on the card alone would never reach him). */
    private void failedWith(String err) {
        failed = true;
        lastAnswer = err;
        awaiting = false;
        card.status("");
        card.body(err, 4);
        answerButtons();
        say(err, ANSWER);
    }

    /** While the answer is said: ⏸ / ⏪ / 🎙️ / ⬆️ / ✕. */
    private void answerButtons() {
        card.buttons("⏸", (Runnable) this::pause, "⏪", (Runnable) this::again, "🎙️", (Runnable) this::listenNow, "⬆️ పెద్దగా", (Runnable) this::bigger,
                "✕", (Runnable) this::quit);
    }

    private void pause() {
        if (stopped || voice == null || !voice.speaking || step == CONFIRM) return;
        if (voice.isPaused()) return;
        voice.pause();
        held();
    }

    /** Held (⏸ or "ఆపు"): ▶ carries on from the same word. With no screen: one listen for "కొనసాగించు", else it ends. */
    private void held() {
        if (step == CONFIRM) return; // (the yes question keeps its ✅ / ✕)
        card.orb(OrbView.IDLE);
        card.status("⏸ ఆపాను");
        letGo();
        if (m.voiceOnly) {
            if (heldListens++ < 1) { main.removeCallbacks(listenNowR); main.postDelayed(this::listenWhileHeld, 400); }
            else end(!answered);
            return;
        }
        card.buttons("▶ కొనసాగించు", (Runnable) this::carryOn, "⏪", (Runnable) this::again, "✕", (Runnable) this::quit);
        card.hideIn(60_000, !answered);
    }

    private void carryOn() {
        if (stopped || voice == null || step == CONFIRM) return;
        card.status("");
        if (voice.isPaused()) {
            hold();
            card.stayOpen();
            card.orb(OrbView.SPEAKING);
            if (step == ANSWER) answerButtons();
            voice.resume();
        } else again();
    }

    /** ⏪ / 🔁: the answer once more from its start (the question, before an answer). */
    private void again() {
        if (stopped || voice == null || step == CONFIRM || step == THINK) return;
        String t = lastAnswer.isEmpty() ? (m.say + " " + m.ask).trim() : lastAnswer;
        if (voice.speaking) voice.stopSpeaking();
        card.body(Otp.shown(t), 8);
        if (!lastAnswer.isEmpty()) answerButtons();
        say(t, lastAnswer.isEmpty() ? SAY : ANSWER);
    }

    /** ⬆️: the panel opens and the talk goes on there (it has this talk in its history). */
    private void bigger() {
        quit = true;
        stop(CardTalk.YIELD_BEFORE);
        card.closeFromTalk(false);
        try {
            svc.startActivity(new Intent(svc, SheetActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Exception ignored) {}
    }

    /** After the answer: a question in it (or a choice the tools wait for) is listened for, a few times; else it is done. */
    private void afterAnswer() {
        if (stopped) return;
        if (!failed) delivered = true;
        String a = lastAnswer.trim();
        if (!failed && (awaiting || a.endsWith("?") || a.endsWith("?”")) && follows++ < FOLLOW_UPS) {
            main.removeCallbacks(listenNowR);
            main.postDelayed(listenNowR, 250); // (the voice's tail must not be heard)
            return;
        }
        finish();
    }

    /** Done: 🔁 / 🎙️ / ⬆️ stay for a while (his card setting, doubled), then it goes. */
    private void finish() {
        step = IDLE;
        card.orb(OrbView.IDLE);
        letGo();
        if (m.voiceOnly) { end(false); return; }
        card.buttons("🔁 మళ్ళీ", (Runnable) this::again, "🎙️ చెప్పండి", (Runnable) this::listenNow, "⬆️ పెద్దగా", (Runnable) this::bigger,
                "✕", (Runnable) this::quit);
        card.hideIn(card.staySeconds() * 2000L, false);
    }

    @Override public void onListening() { if (!stopped) card.orb(OrbView.LISTENING); }

    @Override public void onPartial(String text) {
        if (stopped || text == null || text.trim().isEmpty()) return;
        partial = text.trim();
        card.status("🎙️ " + partial);
    }

    @Override public void onListenFailed(int error) {
        if (stopped) return;
        String part = partial;
        partial = "";
        boolean nothing = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
        if (nothing && !part.isEmpty()) { onHeard(part); return; } // (a long answer cut by a pause is still his answer)
        if (step == CONFIRM) { confirmSilence(); return; }
        if (voice.isPaused()) {
            int r = voice.pausedHeard("");
            if (r == VoiceIO.HELD) { held(); return; }
            if (r == VoiceIO.RESUMED) { card.status(""); card.orb(OrbView.SPEAKING); return; }
        }
        card.orb(OrbView.IDLE);
        if (answered) { finish(); return; } // (after an answer: nothing more to say)
        // not answered: a voice-only one waits as a notification; on the card the buttons wait a little
        if (m.voiceOnly) { end(true); return; }
        card.status("జవాబు రాలేదు: 🎙️ నొక్కి చెప్పండి");
        letGo();
        card.hideIn(card.staySeconds() * 1000L, true);
    }

    @Override public void onLevel(float level) { card.level(level); }

    @Override public void onSpeakStart() { if (!stopped) card.orb(OrbView.SPEAKING); }

    @Override public void onSpeakDone() {
        if (stopped) return;
        switch (step) {
            case SAY:
                main.removeCallbacks(listenNowR);
                main.postDelayed(listenNowR, 250);
                break;
            case CONFIRM:
                if (confirmWait == null) break;
                if (confirmAuto > 0) startCountdown(); // the countdown starts once he has heard it
                main.removeCallbacks(listenNowR);
                main.postDelayed(listenNowR, 250);
                break;
            case ANSWER:
                afterAnswer();
                break;
            default:
        }
    }

    @Override public void onVoiceReady() {}

    /** He talked over Jarvis: the speech is held and the mic opens for him. */
    @Override public void onBargeIn() {
        if (stopped) return;
        main.post(() -> {
            if (stopped || voice == null) return;
            if (step == THINK) { if (voice.speaking) voice.stopSpeaking(); return; } // (a line said while a job runs: he heard it)
            if (!voice.listening) { partial = ""; voice.listen(p.listenLang()); }
        });
    }

    @Override public void onWord(String spoken, int start, int end) {
        // (a code is never lit: the spoken words have it left out, and the card must keep showing it)
        if (!stopped && (step == ANSWER || step == SAY) && !hasCode(speaking)) card.highlight(spoken, start, end);
    }

    /** Otp.has for the text being said, worked out once per text (onWord comes for every word). */
    private String codeFor;
    private boolean codeIs;

    private boolean hasCode(String s) {
        if (s != codeFor) { codeFor = s; codeIs = Otp.has(s); }
        return codeIs;
    }

    /** Another Jarvis screen started listening (he opened the panel or the app): the card steps aside. */
    @Override public void onMicTaken() {
        if (!stopped) { stop(CardTalk.YIELD_AFTER); card.closeFromTalk(!answered); }
    }

    // ================================================================ Tools.Host: the jobs run from here

    @Override public Activity activity() { return null; }

    @Override public Context context() { return ctx; }

    /** Emoji / signs are not said ("🏠 ఇంట్లో వినిపించనా?" → "ఇంట్లో వినిపించనా"). */
    private static String plain(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\p{So}\\p{Sk}\\x{FE0F}\\x{200D}]", "").replaceAll("[?？]+\\s*$", "").trim();
    }

    /**
     * A yes the job needs (send, call…): asked on the card and said, by buttons or "అవును / వద్దు". autoSeconds > 0 (a call,
     * SOS): said with the countdown, it is done when the countdown ends unless he says "వద్దు" / taps ✕. Else only his yes.
     */
    @Override public boolean confirm(String title, String message, String yes, int autoSeconds) {
        if (stopped) return false;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger st = new AtomicInteger(OPEN);
        confirmState = st;
        confirmWait = done;
        main.post(() -> {
            if (stopped || confirmWait != done) { st.compareAndSet(OPEN, NO); done.countDown(); return; }
            step = CONFIRM;
            unclear = 0;
            confirmAuto = Math.max(0, autoSeconds);
            if (autoYes != null) { main.removeCallbacks(autoYes); autoYes = null; }
            hold();
            card.stayOpen();
            card.orb(OrbView.IDLE);
            String msg = message == null ? "" : message.trim();
            card.body(title + (msg.isEmpty() ? "" : "\n" + Otp.shown(msg)), 6);
            card.buttons(yes == null || yes.isEmpty() ? "✅ అవును" : "✅ " + yes, (Runnable) () -> confirmed(st, done, true), // (these two answer this ask only)
                    "✕ వద్దు", (Runnable) () -> confirmed(st, done, false));
            String q = plain(title) + (msg.isEmpty() ? "" : ". " + plain(msg));
            if (confirmAuto > 0) {
                card.status("⏱ " + confirmAuto + " సెకన్లలో · వద్దంటే ✕ లేదా \"వద్దు\"");
                say(q + ". " + confirmAuto + " సెకన్లలో చేస్తాను. వద్దంటే వద్దు అనండి.", CONFIRM);
            } else {
                say(q + "?", CONFIRM);
            }
        });
        try { done.await(90 + Math.max(0, autoSeconds), TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (st.compareAndSet(OPEN, NO)) // no answer in time: not done, and the card says so (its ✅ no longer counts)
            main.post(() -> {
                if (stopped || confirmState != st || step != CONFIRM) return;
                if (confirmWait == done) confirmWait = null;
                if (autoYes != null) { main.removeCallbacks(autoYes); autoYes = null; }
                main.removeCallbacks(listenNowR);
                if (voice != null && voice.listening) voice.cancelListening();
                step = THINK;
                hold();
                card.orb(OrbView.THINKING);
                card.status("జవాబు రాలేదు: చేయలేదు");
                card.buttons("✕", (Runnable) this::quit);
            });
        else if (confirmWait == done) confirmWait = null;
        boolean ok = st.get() == YES;
        if (ok) acted = true;
        return ok;
    }

    /** His answer by voice: to the ask on the card now. */
    private void confirmed(boolean yes) {
        CountDownLatch c = confirmWait;
        AtomicInteger st = confirmState;
        if (c != null && st != null) confirmed(st, c, yes);
    }

    private void confirmed(AtomicInteger st, CountDownLatch c, boolean yes) {
        if (stopped || !st.compareAndSet(OPEN, yes ? YES : NO)) return;
        if (confirmWait == c) confirmWait = null;
        if (autoYes != null) { main.removeCallbacks(autoYes); autoYes = null; }
        main.removeCallbacks(listenNowR);
        if (voice != null) { if (voice.listening) voice.cancelListening(); if (voice.speaking) voice.stopSpeaking(); }
        step = THINK;
        hold();
        card.orb(OrbView.THINKING);
        card.status(yes ? "సరే…" : "సరే, వద్దు");
        card.buttons("✕", (Runnable) this::quit);
        c.countDown();
    }

    @Override public void askPermissions(String[] permissions) {
        main.post(() -> { if (!stopped) card.status("🔐 అనుమతి కావాలి: \"Jarvis\" అని పిలిచి మళ్ళీ అడగండి, అక్కడ Allow ఇవ్వండి"); });
    }

    @Override public void notice(String text) {
        main.post(() -> { if (!stopped) card.status(text); });
    }

    /** Something he must do now (unlock the phone): on the card, and said when the voice is free. */
    @Override public void tellHim(String text) {
        main.post(() -> {
            if (stopped) return;
            card.status("🔓 " + text);
            if (voice != null && !voice.speaking && !voice.listening) voice.speak(text, p.speechRate());
        });
    }
}
