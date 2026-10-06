package com.anil.jarvis;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * A live two-way voice talk with Gemini Live: one open line, Gemini hears him as he talks and answers in its own
 * voice almost at once (no "write out his words, then think, then make a voice" chain). He can talk over it. The
 * everyday tools run here; anything else is done the usual way (classic_jarvis) only after he says yes. Errors are
 * said as they are; it never moves to another AI by itself.
 */
final class GeminiLive implements LiveTalk {
    private static final int IN_RATE = 16000, OUT_RATE = 24000;
    private static final int MIC_CHUNK = 640;        // 40 ms of his voice per message
    private static final int PLAY_CHUNK = 4800;      // 100 ms of Jarvis's voice (bytes)
    private static final int BACKLOG = 75;           // 3 s of his voice kept while the line is (re)connecting

    /** The last Gemini Live talk, for "Jarvis చెక్": how fast it connected and answered, how it ended. */
    static volatile String lastInfo = "";

    private final Context ctx;
    private final Prefs prefs;
    private final Store store;
    private final Tools tools;
    private final Brain brain;
    private final LiveSession.Listener l;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService toolRunner = Executors.newSingleThreadExecutor();
    private final LinkedBlockingQueue<byte[]> playQueue = new LinkedBlockingQueue<>();
    private final Set<String> cancelledTools = ConcurrentHashMap.newKeySet();
    private final AtomicInteger toolsRunning = new AtomicInteger();

    private OkHttpClient client;
    private volatile WebSocket ws;
    /** Which line is current (a dropped or moved line is replaced): messages from an older one are ignored. */
    private volatile int attempt, readyAttempt;
    private volatile boolean ready, closed, muted, jarvisSpeaking, flushRequested, endRequested, generating;
    private volatile boolean everReady, resuming, moveSoon, saidPending;
    /** The parts of the setup Gemini takes (GeminiLiveProto.VOICE ... ): a refused part is left out, the rest stay. */
    private volatile int features = GeminiLiveProto.ALL;
    /** A new voice was just chosen in the talk: on the new line Jarvis says a line in it. */
    private volatile String introVoice;
    private volatile long readyAt;
    /** His words this turn came while Jarvis's voice was playing (or just after): only then can they be its own voice heard back. */
    private volatile boolean heardOverVoice;
    private volatile long voiceEndedAt, askedClassicAt, handoffMine;
    /** The newest of his turns saved by this talk: when (its stamp) and whether it came while Jarvis was talking. */
    private volatile long savedTurnAt;
    private volatile boolean savedTurnOverVoice, askedThisTurn;
    private volatile long moveBy;
    private int reconnects, allReconnects;
    private volatile String endReason = "bye";
    private volatile String interpreterLang;
    private volatile String resumeHandle;
    private String baseInstr = "", setupInstr = "", model = "";
    private boolean liveRules;
    private volatile long lastActivity, startedAt, lastLoudAt, firstVoiceWait = -1, lastHeardAt, heardStartWall;
    private volatile boolean turnVoiceSeen;
    private Thread micThread, playThread;
    private AudioManager am;
    private int oldMode;
    private boolean routed, speakerOn;
    private volatile boolean headset, btHeadset;
    /**
     * On the phone's speaker (no headset, "call voice" off): Jarvis's voice plays as ordinary media, clear and on the
     * media volume, and while it plays the mic is not sent to Gemini (it would hear itself); his talk-over is caught
     * here, the way the classic talk-over does it.
     */
    private volatile boolean echoGate;
    /** He talked over Jarvis: his voice goes to Gemini, and the rest of that answer is dropped. */
    private volatile boolean barged;
    private long lastLevelPost, lastVoicePost;
    /** His words this turn (not saved yet) and Jarvis's words this turn. */
    private final StringBuilder heard = new StringBuilder(), said = new StringBuilder();
    /** Jarvis's last words, squeezed (to tell its own voice heard back from his). */
    private volatile String lastSaidFlat = "";
    private volatile JSONObject turnUsage;

    GeminiLive(Context c, Prefs prefs, Tools tools, Brain brain, LiveSession.Listener l) {
        this.ctx = c.getApplicationContext();
        this.prefs = prefs;
        this.store = Store.get(c);
        this.tools = tools;
        this.brain = brain;
        this.l = l;
    }

    @Override public String interpreterLang() { return interpreterLang; }

    // ================================================================ start / stop

    @Override public void start(String instr) {
        baseInstr = instr == null ? "" : instr;
        // Jarvis's usual live rules, plus what Gemini Live does itself and what goes the usual way
        liveRules = baseInstr.contains("# Live voice conversation");
        model = prefs.geminiLiveModel();
        startedAt = SystemClock.elapsedRealtime();
        lastActivity = startedAt;
        routeAudio();
        echoGate = !headset && !prefs.bargeCallVoice();
        state(OrbView.THINKING, "Gemini Live కి కనెక్ట్ అవుతున్నాను…");
        client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
        startPlayer();
        startMic(); // his first words are kept until the line is ready, so he can talk straight away
        connect();
        main.postDelayed(setupTimeout, 12000);
    }

    /** The Gemini key as typed, without spaces or invisible characters a paste can bring (they'd break the request). */
    private String key() { return prefs.geminiKey().replaceAll("[^\\x21-\\x7E]", ""); }

    /**
     * A new line to Gemini: the first one, the plain settings after a refusal, or a replacement (resumed with Gemini's
     * handle, or fresh with the recent talk in the instructions). Main thread.
     */
    private void connect() {
        if (closed) return;
        moveSoon = false; // (this is the move, or a reconnect that replaces it)
        main.removeCallbacks(moveCheck);
        final int my = ++attempt;
        ready = false;
        resuming = resumeHandle != null;
        // whatever was going on the old line is over
        settleHeard();
        generating = false;
        turnVoiceSeen = false;
        main.removeCallbacks(finishLater);
        saidPending = false;
        finishSaid(" …");
        setupInstr = everReady && !resuming && liveRules && brain != null
                ? brain.liveInstructions(store.chat()) + GeminiLiveProto.rules(prefs.name()) // (a fresh line: the talk so far goes with it)
                : baseInstr + (liveRules ? GeminiLiveProto.rules(prefs.name()) : "");
        WebSocket old = ws;
        if (old != null) try { old.close(1000, "moving"); } catch (Exception ignored) {}
        Request req;
        try {
            req = new Request.Builder()
                    .url(GeminiLiveProto.URL)
                    .addHeader("x-goog-api-key", key()) // (in a header, never in the address)
                    .build();
        } catch (Exception e) {
            fail(0, "API key: " + e.getMessage());
            return;
        }
        ws = client.newWebSocket(req, new WebSocketListener() {
            private boolean ended;

            @Override public void onOpen(WebSocket w, Response r) {
                if (my != attempt || closed) return;
                try {
                    w.send(GeminiLiveProto.setup(model, prefs.geminiLiveVoice(), setupInstr,
                            GeminiLiveProto.functions(tools.geminiTools(), (features & GeminiLiveProto.SEARCH) == 0, (features & GeminiLiveProto.WAIT) != 0),
                            features, resumeHandle,
                            prefs.livePatient() ? 800 : 500, startSensitivity()).toString());
                } catch (Exception e) {
                    lineEnded(my, 0, "setup: " + e.getMessage());
                }
            }
            @Override public void onMessage(WebSocket w, String text) { message(my, text); }
            @Override public void onMessage(WebSocket w, ByteString bytes) { message(my, bytes.utf8()); } // (Gemini sends its JSON as binary frames)
            @Override public void onClosing(WebSocket w, int code, String reason) {
                try { w.close(1000, null); } catch (Exception ignored) {}
                if (!ended) { ended = true; lineEnded(my, code, reason); }
            }
            @Override public void onClosed(WebSocket w, int code, String reason) {
                if (!ended) { ended = true; lineEnded(my, code, reason); }
            }
            @Override public void onFailure(WebSocket w, Throwable t, Response r) {
                if (ended) return;
                ended = true;
                String msg = String.valueOf(t.getMessage());
                lineEnded(my, r != null ? r.code() : 0, (r != null ? "HTTP " + r.code() + " " : "") + msg);
            }
        });
    }

    /** "Jarvis ఆగిపోతుంటే / వినకపోతే" slider (0 hears only clear talk ... 4 hears softest). */
    private String startSensitivity() {
        int s = prefs.bargeSens();
        return s <= 1 ? "START_SENSITIVITY_LOW" : s >= 3 ? "START_SENSITIVITY_HIGH" : null;
    }

    private static boolean authProblem(int code, String reason) {
        String r = String.valueOf(reason).toLowerCase(Locale.ROOT);
        return r.contains("api key") || r.contains("api_key") || r.contains("unauthenticated") || r.contains("permission")
                || r.contains("quota") || r.contains("exhausted") || r.contains("billing")
                || (r.contains("model") && (r.contains("not found") || r.contains("not supported")));
    }

    /** A line closed (code / reason from Gemini, or a network failure). */
    private void lineEnded(int my, int code, String reason) {
        main.post(() -> {
            if (closed || my != attempt) return; // ours to end, or an older line
            ready = false;
            if (!everReady) {
                // the fuller settings refused before the talk started: once more with the plain ones (same model, same key)
                // a setting refused before the talk started: once more without that part (same model, same key); his voice is kept unless it was the voice
                int next = GeminiLiveProto.dropFor(code, reason, features);
                if (next >= 0) {
                    features = next;
                    refusedWords = String.valueOf(reason);
                    connect();
                    restartSetupTimer();
                    return;
                }
                fail(code, reason);
                return;
            }
            if (endRequested) { stop(endReason); return; }
            if (authProblem(code, reason) || reconnects >= 3 || allReconnects >= 20) { fail(code, reason); return; }
            // the talk was going: carry on on a new line (resumed where Gemini allows; a refused handle is dropped)
            if (resuming && readyAttempt != my) resumeHandle = null;
            reconnects++;
            allReconnects++;
            long wait = 1000L << (reconnects - 1); // 1, 2, 4 s: a moment without signal on the bike passes
            state(OrbView.THINKING, "మళ్లీ కనెక్ట్ అవుతున్నాను…");
            main.postDelayed(() -> {
                if (closed || my != attempt) return;
                connect();
                restartSetupTimer();
            }, wait);
        });
    }

    private void restartSetupTimer() {
        main.removeCallbacks(setupTimeout);
        main.postDelayed(setupTimeout, 12000);
    }

    private void fail(int code, String reason) {
        if (closed) return;
        String m = GeminiLiveProto.problem(code, reason, model);
        lastInfo = when() + " · " + m.replace('\n', ' ');
        l.onLiveError(m);
        stop("error");
    }

    private final Runnable setupTimeout = () -> {
        if (closed || ready) return;
        fail(0, "Gemini Live సెషన్ సిద్ధం కాలేదు (setup timeout)");
    };

    /** Gemini took the settings: the talk is on. */
    private void onReady(int my) {
        if (closed || ready || my != attempt) return;
        ready = true;
        readyAttempt = my;
        reconnects = 0;
        main.removeCallbacks(setupTimeout);
        lastActivity = SystemClock.elapsedRealtime();
        readyAt = lastActivity;
        String intro = introVoice;
        if (intro != null) { // he chose a new voice: Jarvis says a line in it
            introVoice = null;
            try {
                send(GeminiLiveProto.text("(A note from the Jarvis app, not words from " + prefs.name() + ": your voice was just changed to " + intro
                        + " as he asked. Say ONE short, warm Telugu line in your new voice, like 'ఇప్పుడు నా గొంతు ఇలా ఉంది " + prefs.name() + ", నచ్చిందా?'.)"));
            } catch (Exception ignored) {}
        }
        if (!everReady) {
            everReady = true;
            lastInfo = when() + " · కనెక్ట్ " + Ears.sec(lastActivity - startedAt) + " సె" + refusedNote();
            main.postDelayed(idleCheck, 1000);
        }
        if (!jarvisSpeaking && playQueue.isEmpty()) state(OrbView.LISTENING, "మాట్లాడండి…");
    }

    /** Gemini said this line closes soon: move to a new one at a calm moment (not mid-answer, not mid-tool), or just before it closes. */
    private final Runnable moveCheck = new Runnable() {
        @Override public void run() {
            if (closed || !moveSoon) return;
            long now = SystemClock.elapsedRealtime();
            boolean calm = !generating && toolsRunning.get() == 0 && !jarvisSpeaking && playQueue.isEmpty()
                    && now - lastLoudAt > 1500 && now - lastHeardAt > 1500; // (not in the middle of his sentence either)
            if (calm || now >= moveBy) {
                moveSoon = false;
                connect();
                restartSetupTimer();
                return;
            }
            main.postDelayed(this, 250);
        }
    };

    @Override public void stop(String reason) {
        if (closed) return;
        settleHeard(); // his last words are kept
        closed = true; // (a usual-way run still finishing keeps its guard until it ends: nothing said now counts as its yes)
        ready = false;
        main.removeCallbacks(idleCheck);
        main.removeCallbacks(setupTimeout);
        main.removeCallbacks(heardSettled);
        main.removeCallbacks(moveCheck);
        main.removeCallbacks(finishLater);
        try { if (ws != null) ws.close(1000, "bye"); } catch (Exception ignored) {}
        Thread m = micThread, p = playThread;
        micThread = null;
        playThread = null;
        playQueue.clear();
        for (Thread t : new Thread[]{m, p}) {
            if (t == null) continue;
            t.interrupt();
            try { t.join(800); } catch (InterruptedException ignored) {}
        }
        toolRunner.shutdownNow();
        restoreAudio();
        if (client != null) {
            try { client.dispatcher().executorService().shutdown(); } catch (Exception ignored) {}
        }
        String r = reason;
        main.post(() -> l.onLiveEnded(this, r));
    }

    @Override public boolean isOpen() { return ready && !closed; }

    @Override public void setMuted(boolean m) {
        boolean was = muted;
        muted = m;
        if (m && !was) sendRaw(GeminiLiveProto.audioEnd()); // what he said before muting is taken as said
        if (!m) lastActivity = SystemClock.elapsedRealtime();
    }

    @Override public boolean isMuted() { return muted; }

    @Override public void sendText(String text) {
        if (!isOpen() || text == null || text.trim().isEmpty()) return;
        try {
            send(GeminiLiveProto.text(text));
            lastActivity = SystemClock.elapsedRealtime();
            state(OrbView.THINKING, "ఆలోచిస్తున్నాను…");
        } catch (Exception ignored) {}
    }

    /** What Gemini didn't take this time, said in "Jarvis చెక్" (with its words). */
    private volatile String refusedWords = "";

    private String refusedNote() {
        String d = GeminiLiveProto.dropped(features);
        if (d.isEmpty()) return "";
        String w = refusedWords.length() > 120 ? refusedWords.substring(0, 120) : refusedWords;
        return " (Gemini ఒప్పుకోనివి: " + d + (w.isEmpty() ? "" : " · \"" + w + "\"") + ")";
    }

    /**
     * Nobody talking for his "listening time" (Settings → వాయిస్, 8 s at first): the live talk ends quietly, the mic
     * goes off. Counted from his last word, Jarvis's last sound or the line being ready; never while Jarvis is talking,
     * a tool is working, the line is (re)connecting, or he muted it himself.
     */
    private final Runnable idleCheck = new Runnable() {
        @Override public void run() {
            if (closed) return;
            long now = SystemClock.elapsedRealtime();
            long last = Math.max(Math.max(lastActivity, voiceEndedAt), Math.max(lastHeardAt, readyAt));
            boolean calm = ready && !muted && !generating && !jarvisSpeaking && playQueue.isEmpty() && toolsRunning.get() == 0 && !endRequested
                    && now - lastLoudAt > 1500; // (not while the mic hears him right now)
            if (calm && now - last > prefs.listenWindowSeconds() * 1000L) {
                lastInfo = when() + " · " + prefs.listenWindowSeconds() + " సె నిశ్శబ్దం: Live ఆపాను" + refusedNote();
                stop("idle");
                return;
            }
            main.postDelayed(this, 1000);
        }
    };

    // ================================================================ messages

    private void send(JSONObject o) { sendRaw(o.toString()); }

    /** Only on a line Gemini has set up (anything sent before the setup would make it close the line). */
    private void sendRaw(String s) {
        WebSocket w = ws;
        if (w != null && ready && !closed) w.send(s);
    }

    private void message(int my, String text) {
        if (my != attempt || closed) return;
        try {
            GeminiLiveProto.handle(new JSONObject(text), new Reader(my));
        } catch (Exception ignored) {}
    }

    /** Gemini's messages on one line. */
    private final class Reader implements GeminiLiveProto.Events {
        private final int my;

        Reader(int my) { this.my = my; }

        @Override public void setupComplete() { main.post(() -> onReady(my)); }

        @Override public void voice(byte[] pcm) {
            lastActivity = SystemClock.elapsedRealtime();
            if (barged && turnVoiceSeen) return; // he talked over this answer: the rest of it isn't played
            if (!turnVoiceSeen) barged = false;  // a new answer: Jarvis's own voice is kept from Gemini again
            if (saidPending) { // a new answer: the last one's words are complete
                main.removeCallbacks(finishLater);
                saidPending = false;
                finishSaid("");
            }
            generating = true;
            if (!turnVoiceSeen) {
                turnVoiceSeen = true;
                long loud = lastLoudAt;
                if (loud > 0 && lastActivity - loud < 20000) firstVoiceWait = lastActivity - loud;
            }
            playQueue.offer(pcm);
        }

        @Override public void heardMore(String text) {
            long now = SystemClock.elapsedRealtime();
            lastActivity = now;
            lastHeardAt = now;
            String all;
            boolean overVoice = prefs.bargeIn() && (jarvisSpeaking || !playQueue.isEmpty() || now - voiceEndedAt < 800);
            synchronized (heard) {
                if (heard.length() == 0) { heardStartWall = System.currentTimeMillis(); heardOverVoice = false; } // when he began saying it
                if (overVoice) heardOverVoice = true;
                heard.append(text);
                all = heard.toString().trim();
            }
            main.post(() -> { if (!closed) l.onLiveUserPartial(all); });
            state(OrbView.LISTENING, "వింటున్నాను…");
            // saved after a pause longer than his turn's end (or before any tool runs, or when the line ends)
            main.removeCallbacks(heardSettled);
            main.postDelayed(heardSettled, (prefs.livePatient() ? 800 : 500) + 900);
        }

        @Override public void saidMore(String text) {
            lastActivity = SystemClock.elapsedRealtime();
            String s;
            synchronized (said) {
                said.append(text);
                s = said.toString().trim();
            }
            if (!askedThisTurn && askedUsualWay(flat(s))) { // it asked "పాత పద్ధతిలో చేయమంటారా?" (once a turn)
                askedThisTurn = true;
                askedClassicAt = System.currentTimeMillis();
            }
            main.post(() -> { if (!closed) l.onLiveJarvisPartial(s); });
        }

        @Override public void turnDone() {
            generating = false;
            turnVoiceSeen = false;
            askedThisTurn = false;
            // its words can come a moment after the turn's end: finish them shortly
            saidPending = true;
            main.removeCallbacks(finishLater);
            main.postDelayed(finishLater, 700);
            JSONObject u = turnUsage;
            turnUsage = null;
            Usage.geminiLive(u);
            long w = firstVoiceWait;
            if (w >= 0) {
                firstVoiceWait = -1;
                lastInfo = when() + " · చివరి జవాబు మీరు ఆపిన " + Ears.sec(w) + " సె కి మొదలైంది" + refusedNote();
            }
            if (endRequested) main.postDelayed(() -> waitAndClose(0), 300);
            else if (!jarvisSpeaking && playQueue.isEmpty()) state(OrbView.LISTENING, "మాట్లాడండి…");
        }

        @Override public void interrupted() {
            lastActivity = SystemClock.elapsedRealtime();
            playQueue.clear();
            flushRequested = true;
            generating = false;
            turnVoiceSeen = false;
            askedThisTurn = false;
            main.removeCallbacks(finishLater);
            saidPending = false;
            finishSaid(" …"); // what Jarvis had said when he spoke over it
        }

        @Override public void toolCall(String id, String name, JSONObject args) {
            lastActivity = SystemClock.elapsedRealtime();
            state(OrbView.THINKING, "classic_jarvis".equals(name) ? "పాత పద్ధతిలో చేస్తున్నాను…" : Tools.statusFor(name));
            toolsRunning.incrementAndGet();
            try {
                toolRunner.submit(() -> {
                    try { runTool(my, id, name, args); } finally { toolsRunning.decrementAndGet(); lastActivity = SystemClock.elapsedRealtime(); }
                });
            } catch (Exception e) { // (stopped)
                toolsRunning.decrementAndGet();
            }
        }

        @Override public void toolCancelled(List<String> ids) { cancelledTools.addAll(ids); }

        @Override public void goAway(long msLeft) {
            main.post(() -> {
                if (closed || moveSoon) return;
                moveSoon = true;
                moveBy = SystemClock.elapsedRealtime() + Math.max(0, msLeft - 2000);
                main.post(moveCheck);
            });
        }

        @Override public void resumeHandle(String handle) { resumeHandle = handle; }

        @Override public void usage(JSONObject u) { turnUsage = u; }
    }

    private final Runnable finishLater = () -> {
        saidPending = false;
        finishSaid("");
    };

    /** Lower case, letters and digits only: to compare what he said with what Jarvis said. */
    private static String flat(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\p{N}]+", "");
    }

    /**
     * His words of this turn: saved (stamped with when he began them, so a check of "did he say send after the draft"
     * goes by when he spoke) and shown. Words that are only Jarvis's own voice heard back are dropped.
     */
    private void settleHeard() {
        String t;
        long began;
        boolean overVoice;
        synchronized (heard) {
            t = heard.toString().trim();
            heard.setLength(0);
            began = heardStartWall;
            overVoice = heardOverVoice;
        }
        main.removeCallbacks(heardSettled);
        if (t.isEmpty()) return;
        if (overVoice) { // heard while Jarvis was talking (talk-over on): words that are only Jarvis's own are its voice heard back
            String f = flat(t);
            String now;
            synchronized (said) { now = flat(said.toString()); }
            if (f.length() >= 4 && (lastSaidFlat.contains(f) || now.contains(f))) return;
        }
        long at = began > 0 ? began : System.currentTimeMillis();
        savedTurnAt = at;
        savedTurnOverVoice = overVoice;
        store.addChat("user", t, false, at);
        main.post(() -> { if (!closed) l.onLiveUser(t); });
    }

    private final Runnable heardSettled = this::settleHeard;

    /** Jarvis's words of this turn are complete (or cut off by him): shown and kept (the screen saves them). */
    private void finishSaid(String tail) {
        String s;
        synchronized (said) {
            s = said.toString().trim();
            said.setLength(0);
        }
        if (s.isEmpty()) return;
        String fl = flat(s);
        lastSaidFlat = fl.length() > 600 ? fl.substring(fl.length() - 600) : fl;
        String all = s + tail;
        main.post(() -> { if (!closed) l.onLiveJarvis(all); });
    }

    /** Before a tool: his words of this turn are all in (they can trail Gemini's call by a moment) and saved. */
    private void awaitHeard() {
        long until = SystemClock.elapsedRealtime() + 3000;
        while (!closed && SystemClock.elapsedRealtime() < until) {
            long now = SystemClock.elapsedRealtime();
            boolean flowing = now - lastHeardAt < 1000;
            boolean coming = lastLoudAt > lastHeardAt + 300 && now - lastLoudAt < 2500; // the mic heard him; his words aren't in yet
            if (!flowing && !coming) break;
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        settleHeard();
    }

    private void runTool(int my, String id, String name, JSONObject args) {
        if (closed || cancelledTools.remove(id)) return;
        awaitHeard();
        if (closed || cancelledTools.remove(id)) return;
        String result;
        switch (name) {
            case "end_conversation":
                endRequested = true;
                endReason = "bye";
                main.postDelayed(() -> stop("bye"), 12000); // hang up even if no goodbye comes
                result = "{\"ok\":true}";
                break;
            case "voice_mode": {
                JSONObject r = VoiceSwitch.apply(prefs, args.optString("mode"));
                if (r.optBoolean("ok") && !(prefs.liveMode() && prefs.liveGemini())) { // no longer Gemini Live: this talk ends after the line
                    endRequested = true;
                    endReason = "switched";
                    main.postDelayed(() -> stop("switched"), 12000);
                }
                result = r.toString();
                break;
            }
            case "classic_jarvis":
                result = classic(args.optString("request"));
                break;
            case "live_voice":
                result = changeVoice(args.optString("voice"));
                break;
            default:
                result = tools.execute(name, args);
        }
        // the interpreter can't run inside this talk (asked directly or the usual way): end it and start one
        String lang = Tools.takeInterpreter();
        if (lang != null) {
            interpreterLang = lang;
            endReason = "interpreter";
            endRequested = true;
            main.postDelayed(() -> stop("interpreter"), 12000);
        }
        if (closed || my != attempt || cancelledTools.remove(id)) return; // (an older line's call: its talk is gone)
        try { send(GeminiLiveProto.toolResponse(id, name, result)); } catch (Exception ignored) {}
    }

    /** "గొంతు మార్చు": the new voice is saved and the talk moves to a new line in it (a voice is fixed for a line). */
    private String changeVoice(String asked) {
        try {
            String now = prefs.geminiLiveVoice();
            String a = asked == null ? "" : asked.trim();
            String v = a.isEmpty() || a.equalsIgnoreCase("next") || a.contains("ఇంకో") || a.contains("వేరే") ? GeminiLiveProto.nextVoice(now)
                    : GeminiLiveProto.voiceName(a);
            if (v == null) {
                return new JSONObject().put("ok", false).put("error", "no such voice: " + a + ". Say the names from the list.").toString();
            }
            if ((features & GeminiLiveProto.VOICE) == 0) {
                return new JSONObject().put("ok", false).put("error", "Gemini did not accept a voice choice in this talk, so the voice can't change now.").toString();
            }
            prefs.sp.edit().putString("gemini_live_voice", v).apply();
            introVoice = v;
            main.postDelayed(() -> { // after this answer goes: a new line in the new voice (the talk so far goes with it)
                if (closed) return;
                playQueue.clear();
                flushRequested = true;
                resumeHandle = null;
                connect();
                restartSetupTimer();
            }, 300);
            return new JSONObject().put("ok", true).put("voice", v).put("note", "Say nothing now: you continue in the new voice in a moment.").toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    /** His newest words in the talk (already saved): {words, when}. */
    private JSONObject lastHeardTurn() {
        List<JSONObject> chat = store.chat();
        for (int i = chat.size() - 1; i >= 0; i--) {
            JSONObject o = chat.get(i);
            if ("user".equals(o.optString("role"))) return o;
        }
        return new JSONObject();
    }

    private static final java.util.Set<String> DO_IT_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "చేయి", "చెయ్", "చేయండి", "చేసెయ్", "చేసేయ్", "కానివ్వు", "అవును", "ఔను", "సరే", "ఓకే", "ok", "okay", "yes", "హా", "హాం", "అలాగే",
            "ఆ", "రా", "అండి", "ప్లీజ్", "please", "jarvis", "జార్విస్", "పర్లేదు", "go", "ahead", "do", "it"));

    /** Jarvis's question "పాత పద్ధతిలో చేయమంటారా?" (or a close form), in its own words squeezed by flat(). */
    static boolean askedUsualWay(String flatSaid) {
        String f = flatSaid == null ? "" : flatSaid;
        return (f.contains("పాతపద్ధతి") || f.contains("మామూలుపద్ధతి"))
                && (f.contains("మంటారా") || f.contains("చేయాలా") || f.contains("చేయనా") || f.contains("చేద్దామా"));
    }

    private static final java.util.regex.Pattern QUESTION = java.util.regex.Pattern.compile("(\\?|ఎందుకు|ఏంటి|ఏమిటి|\\bwhy\\b|\\bwhat\\b)");

    /** He asked for the usual way himself ("పాత పద్ధతిలో బైక్ రేంజ్ చెప్పు"): not "చేయొద్దు", not a question about it. */
    static boolean wantsUsualWay(String said) {
        String s = said == null ? "" : said.trim();
        if (!(s.contains("పాత పద్ధతి") || s.contains("మామూలు పద్ధతి"))) return false;
        if (CardTalk.Words.kind(s, CardTalk.Words.CONFIRM) == CardTalk.Words.NO || QUESTION.matcher(s).find()) return false;
        for (String tok : CardTalk.Words.tokens(s)) if (tok.endsWith("ారా") || tok.endsWith("ావా") || tok.endsWith("ాలా")) return false; // (questions)
        return true;
    }

    /** A short, clear yes to "పాత పద్ధతిలో చేయమంటారా?" (not a request that happens to contain "చెయ్" or "కావాలి"). */
    static boolean saidDoIt(String said) {
        String s = said == null ? "" : said.trim();
        if (s.isEmpty() || s.contains("?") || CardTalk.Words.count(s) > 5) return false;
        if (CardTalk.Words.kind(s, CardTalk.Words.CONFIRM) == CardTalk.Words.NO) return false;
        boolean yes = false;
        for (String tok : CardTalk.Words.tokens(s)) {
            if (tok.isEmpty()) continue;
            if (tok.startsWith("అవున") || tok.startsWith("సరే") && !tok.endsWith("ా")) { yes = true; continue; } // (అవునండి, సరేనండి)
            if (!DO_IT_WORDS.contains(tok)) return false;
            if (!tok.equals("ఆ") && !tok.equals("రా") && !tok.equals("అండి") && !tok.equals("ప్లీజ్") && !tok.equals("please")
                    && !tok.equals("jarvis") && !tok.equals("జార్విస్") && !tok.equals("it")) yes = true;
        }
        return yes;
    }

    /** What Live can't do yet, done the usual way (his chosen AI, all of Jarvis's tools), only after he said yes. */
    private String classic(String request) {
        try {
            if (brain == null || request == null || request.trim().isEmpty()) return "{\"ok\":false,\"error\":\"nothing to do\"}";
            JSONObject turn = lastHeardTurn();
            String his = turn.optString("content", "");
            long asked = askedClassicAt;
            // words that came while Jarvis was talking may be its own voice: they never agree to anything
            boolean overVoice = savedTurnOverVoice && turn.optLong("t") == savedTurnAt;
            // in code, not only in the instructions: he asked for the usual way, or said yes after Jarvis asked
            boolean agreed = !overVoice && (wantsUsualWay(his) || asked > 0 && turn.optLong("t") > asked && saidDoIt(his));
            if (!agreed) {
                return new JSONObject().put("ok", false).put("error", "not_confirmed")
                        .put("say", "First ask him: 'ఇది ఇంకా Live లో రాలేదు, పాత పద్ధతిలో చేయమంటారా?' and call classic_jarvis only after he says yes. "
                                + "His last words were: '" + his + "'").toString();
            }
            askedClassicAt = 0; // (this yes is used up)
            if (!prefs.hasBrain()) return "{\"ok\":false,\"error\":\"Jarvis's usual AI has no key in Settings\"}";
            Brain.Status st = new Brain.Status() {
                @Override public void update(String s) { state(OrbView.THINKING, s); }
                @Override public boolean cancelled() { return closed; } // the live talk ended: stop
            };
            String a;
            long mine = System.currentTimeMillis();
            handoffMine = mine;
            Tools.liveHandoffSince = mine; // nothing he says in the live talk meanwhile counts as a yes to a send / payment / button
            try {
                a = brain.ask(store.chat(), request.trim(), null, st);
            } finally {
                if (Tools.liveHandoffSince == mine) Tools.liveHandoffSince = 0;
            }
            return new JSONObject().put("ok", true).put("answer", a == null ? "" : a).toString();
        } catch (Exception e) {
            try {
                return new JSONObject().put("ok", false).put("error", "the usual way failed: " + e.getMessage()).toString();
            } catch (Exception ignored) {
                return "{\"ok\":false}";
            }
        }
    }

    private void waitAndClose(int tries) {
        if (closed) return;
        if ((jarvisSpeaking || !playQueue.isEmpty()) && tries < 40) {
            main.postDelayed(() -> waitAndClose(tries + 1), 250);
            return;
        }
        stop(endReason);
    }

    private void state(int orb, String text) {
        main.post(() -> { if (!closed) l.onLiveState(orb, text); });
    }

    private static String when() { return new java.text.SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new java.util.Date()); }

    // ================================================================ audio

    private void routeAudio() {
        am = ctx.getSystemService(AudioManager.class);
        if (am == null) return;
        oldMode = am.getMode();
        if (oldMode != AudioManager.MODE_NORMAL) return; // a call (or another call app) has the sound: leave it as it is
        routed = true;
        try {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION); // the phone's echo cancelling works best on this path
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo pick = null;
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    int t = d.getType();
                    if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLE_HEADSET
                            || t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_USB_HEADSET) { pick = d; break; }
                }
                headset = pick != null;
                btHeadset = pick != null && (pick.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || pick.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET);
                if (pick == null) {
                    for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                        if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { pick = d; break; }
                    }
                }
                if (pick != null) am.setCommunicationDevice(pick);
            } else if (!am.isWiredHeadsetOn() && !am.isBluetoothScoOn()) {
                if (!am.isSpeakerphoneOn()) { am.setSpeakerphoneOn(true); speakerOn = true; }
            } else {
                headset = true;
                btHeadset = am.isBluetoothScoOn();
            }
        } catch (Exception ignored) {}
    }

    /** Only what Jarvis changed is undone, and never over a real call. */
    private void restoreAudio() {
        if (am == null || !routed) return;
        routed = false;
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice(); // (Jarvis's device choice is always let go)
            if (am.getMode() != AudioManager.MODE_IN_COMMUNICATION) return; // a call took over: its sound is its own
            if (Build.VERSION.SDK_INT < 31 && speakerOn) am.setSpeakerphoneOn(false);
            am.setMode(oldMode);
        } catch (Exception ignored) {}
    }

    @SuppressLint("MissingPermission") // the screens check RECORD_AUDIO before starting
    private void startMic() {
        micThread = new Thread(() -> {
            AudioRecord rec = null;
            AcousticEchoCanceler aec = null;
            NoiseSuppressor ns = null;
            ArrayDeque<byte[]> backlog = new ArrayDeque<>();
            ArrayDeque<byte[]> pre = new ArrayDeque<>(); // his last 400 ms while Jarvis talked: sent when he talks over it
            boolean wasHeld = false;
            float floor = 300f;
            // talk-over on the speaker (as the classic talk-over): the echo path is learnt from Jarvis's known output level
            Ring ratio = new Ring(75), quiet = new Ring(75);
            int sounding = 0, loud = 0;
            int lv = Math.max(0, Math.min(4, prefs.bargeSens()));
            double k = BARGE_SCALE[lv];
            int need = Math.max(3, 5 + BARGE_NEED_ADJ[lv] / 2); // about 200 ms of his voice above Jarvis's echo
            try {
                int min = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, MIC_CHUNK * 2 * 8));
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("మైక్ తెరవలేకపోయాను (mic)");
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(rec.getAudioSessionId());
                    if (aec != null) aec.setEnabled(true);
                }
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(rec.getAudioSessionId());
                    if (ns != null) ns.setEnabled(true);
                }
                rec.startRecording();
                short[] buf = new short[MIC_CHUNK];
                while (!closed && !Thread.currentThread().isInterrupted()) {
                    int n = rec.read(buf, 0, MIC_CHUNK);
                    if (n <= 0) { if (n < 0) throw new IllegalStateException("mic read " + n); continue; }
                    byte[] bytes = new byte[n * 2];
                    long sum = 0;
                    for (int i = 0; i < n; i++) {
                        short s = buf[i];
                        bytes[2 * i] = (byte) (s & 0xFF);
                        bytes[2 * i + 1] = (byte) ((s >> 8) & 0xFF);
                        sum += (long) s * s;
                    }
                    float rms = (float) Math.sqrt(sum / (double) n);
                    long now = SystemClock.elapsedRealtime();
                    boolean speakingNow = jarvisSpeaking || !playQueue.isEmpty();
                    // his voice (for "how soon did Jarvis answer"): clearly above the room's level, while Jarvis is quiet
                    if (!speakingNow) {
                        if (rms < floor) floor = floor * 0.8f + rms * 0.2f; else floor += (rms - floor) * 0.01f;
                        if (rms > Math.max(floor * 4f, 900f)) lastLoudAt = now;
                    }
                    if (now - lastLevelPost > 100) {
                        lastLevelPost = now;
                        float level = muted ? 0f : Math.min(1f, rms / 4000f);
                        main.post(() -> l.onLiveLevel(level));
                    }
                    // muted, or (talk-over off) Jarvis is talking: nothing is sent (so it doesn't hear itself)
                    boolean hold = muted || (!prefs.bargeIn() && speakingNow);
                    if (hold) {
                        if (!wasHeld && ready && !muted) sendRaw(GeminiLiveProto.audioEnd()); // what he said is taken as said
                        wasHeld = true;
                        backlog.clear();
                        continue;
                    }
                    wasHeld = false;
                    if (echoGate && !barged) {
                        if (speakingNow) {
                            double out = PlaybackLevel.now();
                            double noise = quiet.n >= 5 ? quiet.pct(0.5) : 0;
                            if (out > 300) sounding++;
                            if (out > 1000) ratio.add(Math.sqrt(Math.max(0, (double) rms * rms - noise * noise)) / out); // the echo alone
                            else if (out >= 0 && out < 150) quiet.add(rms); // the room, in Jarvis's pauses
                            boolean judge = out >= 0 && sounding >= 15 && ratio.n >= 10; // after 0.6 s of Jarvis sounding
                            double limit = Math.max(Math.max(500, noise * 3), Math.hypot(ratio.pct(0.85) * Math.max(0, out) * 1.8, noise)) * k;
                            if (prefs.bargeIn() && judge && rms > limit) {
                                if (++loud >= need) { // he is talking over Jarvis: stop it, and Gemini hears him from his first word
                                    loud = 0;
                                    barged = true;
                                    playQueue.clear();
                                    flushRequested = true;
                                }
                            } else {
                                loud = Math.max(0, loud - 1);
                            }
                        } else {
                            sounding = 0;
                            loud = 0;
                        }
                        if (!barged && (speakingNow || now - voiceEndedAt < 250)) { // Jarvis's voice (or its last echo): not sent
                            pre.addLast(bytes);
                            while (pre.size() > 10) pre.removeFirst();
                            continue;
                        }
                    }
                    if (!pre.isEmpty()) {
                        if (barged) while (!pre.isEmpty()) backlog.addLast(pre.removeFirst()); // his first words over Jarvis go first
                        else pre.clear(); // (Jarvis just finished: that was its own echo)
                    }
                    if (!ready) { // (re)connecting: keep his last few seconds for when the line is ready
                        backlog.addLast(bytes);
                        while (backlog.size() > BACKLOG) backlog.removeFirst();
                        continue;
                    }
                    while (!backlog.isEmpty()) {
                        byte[] b = backlog.removeFirst();
                        sendRaw(GeminiLiveProto.audio(b, 0, b.length));
                    }
                    sendRaw(GeminiLiveProto.audio(bytes, 0, bytes.length));
                }
            } catch (Exception e) {
                if (!closed) {
                    String msg = String.valueOf(e.getMessage());
                    main.post(() -> fail(0, msg));
                }
            } finally {
                if (aec != null) aec.release();
                if (ns != null) ns.release();
                if (rec != null) {
                    try { rec.stop(); } catch (Exception ignored) {}
                    rec.release();
                }
            }
        }, "jarvis-gemini-mic");
        micThread.start();
    }

    /** The talk-over slider (Settings): 0 hard to interrupt ... 4 very easy (as the classic talk-over). */
    private static final double[] BARGE_SCALE = {1.6, 1.25, 1.0, 0.8, 0.65};
    private static final int[] BARGE_NEED_ADJ = {4, 2, 0, -2, -4};

    /** Recent values with a percentile (the echo statistics). */
    private static final class Ring {
        final double[] v, tmp;
        int n, pos;
        Ring(int size) { v = new double[size]; tmp = new double[size]; }
        void add(double x) { v[pos] = x; pos = (pos + 1) % v.length; if (n < v.length) n++; }
        double pct(double p) {
            if (n == 0) return 0;
            System.arraycopy(v, 0, tmp, 0, n);
            java.util.Arrays.sort(tmp, 0, n);
            return tmp[Math.min(n - 1, (int) Math.floor(p * (n - 1) + 0.5))];
        }
    }

    /** Loudness of a piece of Jarvis's voice (16-bit little-endian), sent to the screen about 20 times a second. */
    private void voiceLevel(byte[] pcm, int off, int len) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastVoicePost < 50) return;
        lastVoicePost = now;
        long sum = 0;
        int n = 0;
        for (int i = off; i + 1 < off + len; i += 2) {
            int v = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            sum += (long) v * v;
            n++;
        }
        if (n == 0) return;
        float level = (float) Math.min(1.0, Math.sqrt(sum / (double) n) / 5000.0);
        main.post(() -> l.onLiveVoiceLevel(level));
    }

    private void startPlayer() {
        playThread = new Thread(() -> {
            AudioTrack t = null;
            try {
                int min = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                // Jarvis's own clear voice on the media volume (the volume keys work on it); the call path only with
                // "call voice" on or a Bluetooth headset (its mic only works on that path)
                boolean callVoice = prefs.bargeCallVoice() || btHeadset;
                t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(callVoice ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(OUT_RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(Math.max(min, PLAY_CHUNK * 2))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                t.play();
                PlaybackLevel.begin(t, OUT_RATE, 0); // how loud Jarvis is at each moment (talk-over, the face's lips)
                long base = 0;    // playback head position right after the last flush
                long written = 0; // frames handed to the track since the last flush
                while (!closed && !Thread.currentThread().isInterrupted()) {
                    if (flushRequested) { // he spoke over Jarvis: drop what is not played yet
                        flushRequested = false;
                        t.pause();
                        t.flush();
                        t.play();
                        base = t.getPlaybackHeadPosition();
                        written = 0;
                        PlaybackLevel.begin(t, OUT_RATE, base);
                        if (jarvisSpeaking) {
                            jarvisSpeaking = false;
                            voiceEndedAt = SystemClock.elapsedRealtime();
                            main.post(() -> l.onLiveVoiceLevel(0f));
                        }
                        continue;
                    }
                    byte[] c = playQueue.poll(60, TimeUnit.MILLISECONDS);
                    if (c == null) {
                        if (jarvisSpeaking && t.getPlaybackHeadPosition() - base >= written) {
                            jarvisSpeaking = false;
                            voiceEndedAt = SystemClock.elapsedRealtime();
                            main.post(() -> l.onLiveVoiceLevel(0f));
                            if (!generating && !endRequested) state(OrbView.LISTENING, "మాట్లాడండి…");
                        }
                        continue;
                    }
                    if (!jarvisSpeaking) {
                        jarvisSpeaking = true;
                        state(OrbView.SPEAKING, "మాట్లాడుతున్నాను…");
                    }
                    for (int off = 0; off < c.length && !flushRequested && !closed; off += PLAY_CHUNK) {
                        int len = Math.min(PLAY_CHUNK, c.length - off);
                        voiceLevel(c, off, len);
                        int w = t.write(c, off, len);
                        if (w > 0) {
                            written += w / 2;
                            PlaybackLevel.feed(c, off, w);
                        }
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                if (!closed) {
                    String msg = String.valueOf(e.getMessage());
                    main.post(() -> l.onLiveError("Gemini Live: స్పీకర్ సమస్య (" + msg + ")"));
                }
            } finally {
                jarvisSpeaking = false;
                if (t != null) {
                    PlaybackLevel.end(t);
                    try { t.pause(); t.flush(); } catch (Exception ignored) {}
                    t.release();
                }
            }
        }, "jarvis-gemini-play");
        playThread.start();
    }
}
