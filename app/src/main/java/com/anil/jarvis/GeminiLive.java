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
    /** ... whether it came over Jarvis's voice in full talk (the mic open while Jarvis talked), and its words. */
    private volatile boolean heardOverFull;
    private volatile long moveBy;
    private int reconnects, allReconnects;
    private volatile String endReason = "bye";
    private volatile String interpreterLang;
    private volatile String resumeHandle;
    private String baseInstr = "", setupInstr = "", model = "";
    private boolean liveRules;
    private volatile long lastActivity, startedAt, lastLoudAt, firstVoiceWait = -1, lastHeardAt, heardStartWall;
    /** When the mic's latest stretch of his voice began, while Jarvis was quiet. */
    private volatile long loudRunAt;
    private volatile boolean turnVoiceSeen;
    private Thread micThread, playThread;
    private AudioManager am;
    private int oldMode;
    private volatile boolean routed;
    private boolean speakerOn;
    /** With Jarvis's own echo removal: watches for earbuds / a Bluetooth speaker / headphones connected meanwhile. */
    private android.media.AudioDeviceCallback deviceWatch;
    /** Another output was connected at the start (Bluetooth speaker, headphones ...): its type, or 0. */
    private int otherOutput;
    /**
     * Jarvis's own echo removal (on the phone's speaker): Jarvis knows exactly what it plays, so it takes that out of the
     * mic itself (EchoGuard), as the Gemini app does. Null when it isn't used.
     */
    private volatile EchoGuard echo;
    private volatile boolean aecOn;
    /** The speaker's latency (its position -> sound), refined from its time stamps; and how the play times were found. */
    private long outLatNs = 40_000_000L;
    private int stampsUsed, guessesUsed;
    private final android.media.AudioTimestamp outStamp = new android.media.AudioTimestamp();
    private volatile boolean headset, btHeadset;
    /**
     * On the phone's speaker (no headset, "call voice" off): Jarvis's voice plays clear on the AI assistant volume, and
     * while it plays the mic is not sent to Gemini (it would hear itself); his talk-over ("Jarvis" / "stop", his loud
     * voice, or a tap) is caught here. (Full talk, below, lifts this when the phone's echo cancelling is good.)
     */
    private volatile boolean echoGate;
    /**
     * Full talk, as in the Gemini app: Jarvis's own echo removal was measured (while Jarvis talked) to take Jarvis's
     * voice out of the mic well enough, so the mic goes to Gemini all the time and Gemini stops the moment he talks.
     * Until then (and if it isn't good enough) the safe way above stays.
     */
    private volatile boolean duplex;
    /** Full talk stopped Jarvis without him saying anything (its own echo): the safe way stays for this talk. */
    private volatile boolean duplexFailed;
    /** Gemini cut Jarvis off in full talk: what he was heard saying since (guarded by heard), and what Jarvis had said. */
    private final StringBuilder afterCut = new StringBuilder();
    /** His words heard over Jarvis's voice in this answer, in full talk (guarded by heard): the start of what cut it off. */
    private final StringBuilder overHeard = new StringBuilder();
    private volatile boolean cutWatch;
    private volatile String cutSaid = "";
    /** When full talk was last turned off (his words still on their way are treated as heard over Jarvis's voice). */
    private volatile long duplexOffAt = -100000;
    private volatile long cutAt;
    private volatile int cutWaits;
    /** When the echo removal's measures were last written for "Jarvis చెక్" (mic thread). */
    private long infoAt;
    /** How talk-over works in the last Gemini Live talk, for "Jarvis చెక్". */
    static volatile String bargeInfo = "";
    /** The echo removal's file (what it learnt of the phone's speaker-to-mic path) was read in this run of the app. */
    private static boolean echoLoaded;
    /** Jarvis's echo can only be its last words (when it was cut off mid-answer: its words can run ahead of its voice). */
    private static final int ECHO_WORDS_CUT = 40;
    /** He talked over Jarvis: his voice goes to Gemini, and the rest of that answer is dropped. */
    private volatile boolean barged;
    /** How he stops Jarvis mid-answer on the speaker (Settings): "word", "voice" or "off". */
    private volatile String bargeMode = "word";
    /** The offline "Jarvis" / "stop" word detector (the same small model as the wake word), or null until it is ready. */
    private volatile org.vosk.Recognizer word;
    private org.vosk.Model wordModel;

    /** Loads the word detector in the background (the model is on the phone once the wake word has used it). */
    private void loadWord() {
        new Thread(() -> {
            org.vosk.Model m = null;
            org.vosk.Recognizer r = null;
            try {
                java.io.File dir = VoskModel.ensure(ctx, s -> {});
                if (closed) return;
                org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS);
                m = new org.vosk.Model(dir.getAbsolutePath());
                r = new org.vosk.Recognizer(m, (float) IN_RATE, "[\"jarvis\", \"hey jarvis\", \"stop\", \"[unk]\"]");
                r.setWords(true);
                synchronized (GeminiLive.this) {
                    if (!closed) { wordModel = m; word = r; m = null; r = null; }
                }
            } catch (Throwable ignored) { // (no model: the loudness way is used instead)
            } finally {
                try { if (r != null) r.close(); } catch (Throwable ignored) {}
                try { if (m != null) m.close(); } catch (Throwable ignored) {}
            }
        }, "jarvis-live-word").start();
    }

    /** Frees the word detector (the mic thread, when it ends). */
    private void closeWord() {
        org.vosk.Recognizer r;
        org.vosk.Model m;
        synchronized (this) { r = word; m = wordModel; word = null; wordModel = null; }
        try { if (r != null) r.close(); } catch (Throwable ignored) {}
        try { if (m != null) m.close(); } catch (Throwable ignored) {}
    }

    /**
     * "Jarvis" (or "stop") in this piece of the mic, over Jarvis's own voice: 2 = a finished word Vosk is sure of,
     * 1 = "jarvis" still being heard (counts only when the mic also shows his voice on top of the echo), 0 = no.
     */
    private static int stopWord(org.vosk.Recognizer r, byte[] pcm) {
        try {
            if (r.acceptWaveForm(pcm, pcm.length)) {
                JSONObject res = new JSONObject(r.getResult());
                org.json.JSONArray ws = res.optJSONArray("result");
                for (int i = 0; ws != null && i < ws.length(); i++) {
                    JSONObject w = ws.getJSONObject(i);
                    String t = w.optString("word");
                    double c = w.optDouble("conf", 0);
                    if ("jarvis".equals(t) && c >= 0.85 || "stop".equals(t) && c >= 0.9) return 2;
                }
                return 0;
            }
            return new JSONObject(r.getPartialResult()).optString("partial").contains("jarvis") ? 1 : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private long lastLevelPost, lastVoicePost;
    /** His words this turn (not saved yet) and Jarvis's words this turn. */
    private final StringBuilder heard = new StringBuilder(), said = new StringBuilder();
    /** Jarvis's last words, squeezed (to tell its own voice heard back from his). */
    private volatile String lastSaidFlat = "";
    /** The end of Jarvis's last answer as said (with its words apart), for "was that only Jarvis's own voice". */
    private volatile String lastSaidText = "";
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
        bargeMode = prefs.liveBarge();
        routeAudio();
        echoGate = !headset && !prefs.bargeCallVoice();
        duplex = false;
        // Jarvis's own echo removal: on the phone's own speaker, talk-over on, and not in a phone call
        aecOn = echoGate && prefs.liveAec() && !"off".equals(bargeMode) && otherOutput == 0 && oldMode == AudioManager.MODE_NORMAL;
        if (aecOn) {
            loadEcho();
            echo = new EchoGuard(true, prefs.echoRecord());
            watchDevices();
        }
        if (echoGate && "word".equals(bargeMode)) loadWord();
        bargeInfo = "off".equals(bargeMode) ? "ఆఫ్ (Jarvis పూర్తయ్యాక వింటుంది)"
                : !echoGate ? (headset ? "హెడ్‌సెట్: మీరు మాట్లాడగానే ఆగుతుంది (Gemini లా)" : "కాల్ మార్గం: మీరు మాట్లాడగానే ఆగుతుంది")
                : otherOutput != 0 ? "వేరే స్పీకర్ / హెడ్‌ఫోన్స్ కనెక్ట్ అయి ఉన్నాయి (రకం " + otherOutput + "): " + safeWay()
                : oldMode != AudioManager.MODE_NORMAL ? "ఫోన్ వేరే కాల్‌లో ఉంది: " + safeWay()
                : !aecOn ? "Jarvis సొంత echo తీసివేత ఆఫ్ (సెట్టింగ్స్): " + safeWay()
                : "Jarvis సొంత echo తీసివేత: Jarvis మాట్లాడుతుండగా నేర్చుకుంటోంది (అప్పటిదాకా " + safeWay() + ")";
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
                ? brain.liveInstructions(store.chat()) + GeminiLiveProto.rules(prefs.realName()) // (a fresh line: the talk so far goes with it)
                : baseInstr + (liveRules ? GeminiLiveProto.rules(prefs.realName()) : "");
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
        main.removeCallbacks(cutCheck);
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
        EchoGuard eg = echo; // (the mic has stopped: what it learnt is complete)
        if (eg != null) {
            eg.save();
            bargeInfo = when() + " · " + (duplexFailed ? bargeInfo.replaceFirst("^[^·]*· ", "") : (duplex ? "Gemini లా ఆగుతోంది ✓" : "జాగ్రత్త పద్ధతి: " + safeWay()))
                    + " | " + eg.info() + speakerTimes();
            keepEcho(eg);
        }
        if (client != null) {
            try { client.dispatcher().executorService().shutdown(); } catch (Exception ignored) {}
        }
        String r = reason;
        main.post(() -> l.onLiveEnded(this, r));
    }

    @Override public boolean isOpen() { return ready && !closed; }

    /** Jarvis's voice goes the call way (a Bluetooth headset, or "call voice" on). */
    private boolean callPathVoice() { return prefs.bargeCallVoice() || btHeadset; }

    /** The AI assistant volume (Android's assistant stream, 11) while Jarvis talks there; the call volume on the call path. */
    @Override public int volumeStream() {
        if (callPathVoice() && routed) return AudioManager.USE_DEFAULT_STREAM_TYPE;
        return Build.VERSION.SDK_INT >= 29 ? 11 : AudioManager.STREAM_MUSIC; // (11 = STREAM_ASSISTANT; on old phones it follows media)
    }

    /** He tapped Jarvis: the rest of this answer is dropped and his voice goes to Gemini. */
    @Override public void interrupt() {
        if (closed || !(jarvisSpeaking || !playQueue.isEmpty())) return;
        bargedAt = SystemClock.elapsedRealtime();
        barged = true;
        playQueue.clear();
        flushRequested = true;
        lastActivity = SystemClock.elapsedRealtime();
    }

    /** When he stopped Jarvis (tap, word or voice): its voice still sounds for a moment, and that is not sent. */
    private volatile long bargedAt;
    /**
     * After Jarvis's voice stops, the speaker still sounds for a moment (the phone's output delay and the room's echo):
     * the mic is kept from Gemini that long too, or Gemini hears Jarvis's last words and answers them as his.
     */
    private static final long ECHO_TAIL_MS = 700;
    /** After he stops Jarvis: how long its cut-off voice may still sound. */
    private static final long CUT_TAIL_MS = 350;

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
            if (!turnVoiceSeen) { // a new answer: Jarvis's own voice is kept from Gemini again
                barged = false;
                synchronized (heard) { overHeard.setLength(0); }
            }
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
            // full talk (now, or just before): the mic went to Gemini while Jarvis talked, so words can be its own voice;
            // unless the mic heard his voice begin only after Jarvis went quiet (his quick answer to Jarvis's question)
            boolean full = duplex || now - duplexOffAt < 3000 || cutWatch;
            boolean jarvisOn = jarvisSpeaking || !playQueue.isEmpty();
            boolean overVoice = full
                    ? jarvisOn || cutWatch || now - voiceEndedAt < 2000 && loudRunAt <= voiceEndedAt + 300
                    : prefs.bargeIn() && (jarvisOn || now - voiceEndedAt < 800);
            synchronized (heard) {
                if (heard.length() == 0) { heardStartWall = System.currentTimeMillis(); heardOverVoice = false; heardOverFull = false; } // when he began saying it
                if (overVoice) heardOverVoice = true;
                if (overVoice && full) heardOverFull = true;
                if (cutWatch) afterCut.append(text);
                else if (full && overVoice) overHeard.append(text);
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
            if (duplex) main.post(GeminiLive.this::startCutWatch); // full talk: was it him, or Jarvis's own voice?
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

    /**
     * Shortly after Gemini cut Jarvis off in full talk: if no words of his came (or only Jarvis's own words heard back),
     * Jarvis stopped for its own voice: the safe way stays for the rest of this talk.
     */
    private final Runnable cutCheck = this::checkCut;

    /** Main thread: Gemini cut Jarvis off in full talk; his words (those already heard over Jarvis's voice, and what comes) are watched. */
    private void startCutWatch() {
        if (closed || !duplex) return;
        synchronized (heard) {
            afterCut.setLength(0);
            afterCut.append(overHeard).append(' ');
            overHeard.setLength(0);
            cutWatch = true;
        }
        cutSaid = GeminiLiveProto.lastWords(lastSaidText, ECHO_WORDS_CUT);
        cutAt = SystemClock.elapsedRealtime();
        cutWaits = 0;
        main.removeCallbacks(cutCheck);
        main.postDelayed(cutCheck, 3500);
    }

    private void checkCut() {
        String his;
        synchronized (heard) { his = afterCut.toString(); }
        boolean echo = GeminiLiveProto.mostlyEcho(his, cutSaid);
        // no words yet, but the mic hears a voice since Jarvis went quiet: his words may still be coming (a long sentence)
        if (!closed && duplex && echo && his.trim().isEmpty() && lastLoudAt > cutAt + 500 && cutWaits++ < 3) {
            main.postDelayed(cutCheck, 2000);
            return;
        }
        synchronized (heard) {
            afterCut.setLength(0);
            cutWatch = false;
        }
        if (closed || !duplex || !echo) return;
        duplexFailed = true; // (first: the mic thread doesn't turn full talk back on)
        duplexOffAt = SystemClock.elapsedRealtime();
        duplex = false;
        bargeInfo = when() + " · Jarvis తన గొంతుకే తానే ఆగింది: ఈ Live లో జాగ్రత్త పద్ధతికి మారాను — " + safeWay()
                + ". (Jarvis గొంతు కొంచెం తగ్గిస్తే Gemini లా ఆగడం మళ్లీ పనిచేయొచ్చు)";
    }

    /** How he stops Jarvis in the safe way (the mic kept from Gemini while Jarvis talks). */
    private String safeWay() {
        return "voice".equals(bargeMode) ? "మీరు గట్టిగా మాట్లాడితే లేదా Jarvis ని తాకితే ఆగుతుంది"
                : "'Jarvis' / 'stop' అంటే లేదా Jarvis ని తాకితే ఆగుతుంది";
    }

    /** Mic thread: full talk follows the echo removal's measure (never back on after Jarvis cut itself off). */
    private void followEcho(EchoGuard eg) {
        boolean want = eg.ready() && !duplexFailed;
        long now = SystemClock.elapsedRealtime();
        if (want != duplex) {
            if (!want) duplexOffAt = now;
            duplex = want;
            infoAt = 0;
        }
        if (now - infoAt < 2000 || duplexFailed) return;
        infoAt = now;
        bargeInfo = when() + " · " + (duplex ? "Gemini లా: మీరు మాట్లాడగానే Jarvis ఆగి వింటుంది ✓" : "జాగ్రత్త పద్ధతి: " + safeWay()) + " | " + eg.info();
    }

    /** How the play times were found (for "Jarvis చెక్"). */
    private String speakerTimes() {
        int a = stampsUsed, b = guessesUsed;
        if (a + b == 0) return "";
        return ", స్పీకర్ సమయం " + (a >= b ? "ఖచ్చితంగా" : "అంచనాగా") + " (" + Math.round(outLatNs / 1e6) + " ms)";
    }

    /** What the echo removal learnt of this phone's speaker-to-mic path is read once a run (it starts from it). */
    private void loadEcho() {
        synchronized (GeminiLive.class) {
            if (echoLoaded) return;
            echoLoaded = true;
        }
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "echo-path.bin");
            if (f.exists() && f.length() < 1_000_000) EchoGuard.importState(java.nio.file.Files.readAllBytes(f.toPath()));
        } catch (Exception ignored) {}
    }

    /** After a talk: what was learnt is kept for the next one (and, if asked in Settings, the last 30 s for a check). */
    private void keepEcho(EchoGuard eg) {
        boolean record = prefs.echoRecord();
        new Thread(() -> {
            byte[] wav = record ? eg.recording() : null; // (the mic has stopped: built here, off the screen's thread)
            try {
                byte[] b = EchoGuard.exportState();
                if (b != null) {
                    java.io.File tmp = new java.io.File(ctx.getFilesDir(), "echo-path.tmp");
                    java.nio.file.Files.write(tmp.toPath(), b);
                    //noinspection ResultOfMethodCallIgnored
                    tmp.renameTo(new java.io.File(ctx.getFilesDir(), "echo-path.bin"));
                }
            } catch (Exception ignored) {}
            if (wav != null) {
                try {
                    String name = "jarvis-echo-" + new java.text.SimpleDateFormat("MMdd-HHmmss", Locale.ENGLISH).format(new java.util.Date()) + ".wav";
                    Coder.Made m = Coder.save(ctx, "Jarvis", name, "audio/wav", wav);
                    echoRecording = m.where;
                } catch (Exception e) {
                    echoRecording = "సేవ్ కాలేదు: " + e.getMessage();
                }
            }
        }, "jarvis-echo-save").start();
    }

    /** Where the last echo check recording was saved (for "Jarvis చెక్"). */
    static volatile String echoRecording = "";

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
        boolean overVoice, overFull;
        synchronized (heard) {
            t = heard.toString().trim();
            heard.setLength(0);
            began = heardStartWall;
            overVoice = heardOverVoice;
            overFull = heardOverFull;
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
        // full talk: words that began over Jarvis's voice may be its own voice heard back (even a lone "ok" or "హా"):
        // kept in the chat, but never taken as his yes to a send or a payment (a yes he begins after Jarvis has
        // finished counts as always)
        if (overFull) Tools.echoTurns.add(at);
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
        lastSaidText = s.length() > 800 ? s.substring(s.length() - 800) : s;
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
        // On the phone's speaker (no headset, "call voice" off) the phone stays as it is: Jarvis's voice plays on the
        // AI assistant volume like Gemini's or ChatGPT's, and the volume keys work on it (the call mode would take the
        // volume keys for the call volume); Jarvis takes its own voice out of the mic itself (EchoGuard). A headset (or
        // "call voice") uses the call path, as a headset's mic needs it.
        try { // a headset with a mic (wired, USB, or Bluetooth for calls: a helmet)
            if (Build.VERSION.SDK_INT >= 31) {
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    int t = d.getType();
                    if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLE_HEADSET) { btHeadset = true; headset = true; }
                    if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_USB_HEADSET) headset = true;
                }
            } else {
                btHeadset = am.isBluetoothScoOn();
                headset = btHeadset || am.isWiredHeadsetOn();
            }
        } catch (Exception ignored) {}
        if (!headset && !prefs.bargeCallVoice()) {
            // (another output - a Bluetooth speaker or car, earbuds for media only, wired headphones: Jarvis's voice plays
            // there, and the echo removal, made for the phone's own speaker, isn't used)
            otherOutput = externalOutput();
            return;
        }
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

    /**
     * An output other than the phone's own speaker and earpiece is connected (its type, for "Jarvis చెక్"), or 0.
     * (A headset with a mic is found before this.)
     */
    private int externalOutput() {
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (!phoneOwn(d.getType())) return d.getType();
            }
        } catch (Exception ignored) {}
        return 0;
    }

    /** The phone's own outputs (speaker, earpiece, the phone line and inner paths), not something connected to it. */
    private static boolean phoneOwn(int t) {
        return t == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE || t == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                || t == AudioDeviceInfo.TYPE_TELEPHONY || t == AudioDeviceInfo.TYPE_REMOTE_SUBMIX
                || t == AudioDeviceInfo.TYPE_FM || t == AudioDeviceInfo.TYPE_BUS
                || t == 24 /* TYPE_BUILTIN_SPEAKER_SAFE */ || t == 28 /* TYPE_ECHO_REFERENCE */;
    }

    /** With the echo removal: something connected during the talk takes Jarvis's voice (the removal stops trusting itself). */
    private void watchDevices() {
        deviceWatch = new android.media.AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                for (AudioDeviceInfo d : added) {
                    if (d.isSink() && !phoneOwn(d.getType())) { outputAdded(d.getType()); return; }
                }
            }
        };
        try { am.registerAudioDeviceCallback(deviceWatch, main); } catch (Exception e) { deviceWatch = null; }
    }

    /** Main thread: earbuds / a speaker / headphones were connected: Jarvis plays there now; the safe way for this talk. */
    private void outputAdded(int type) {
        if (closed || !aecOn) return;
        duplexFailed = true; // (first: the mic thread doesn't turn full talk back on)
        duplexOffAt = SystemClock.elapsedRealtime();
        duplex = false;
        otherOutput = type;
        bargeInfo = when() + " · వేరే స్పీకర్ / హెడ్‌ఫోన్స్ కనెక్ట్ అయ్యాయి (రకం " + type + "): " + safeWay();
    }

    /** Only what Jarvis changed is undone, and never over a real call. */
    private void restoreAudio() {
        android.media.AudioDeviceCallback w = deviceWatch;
        deviceWatch = null;
        if (w != null && am != null) {
            try { am.unregisterAudioDeviceCallback(w); } catch (Exception ignored) {}
        }
        if (am == null || !routed) return;
        routed = false;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice(); // (Jarvis's device choice is always let go)
                // only Jarvis's own request is dropped (a real call keeps the sound): otherwise the phone could go back
                // to Jarvis's left-over mode after the call
                am.setMode(oldMode);
                return;
            }
            if (am.getMode() != AudioManager.MODE_IN_COMMUNICATION) return; // a call took over: its sound is its own
            if (speakerOn) am.setSpeakerphoneOn(false);
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
            boolean wasHeld = false;
            float floor = 300f;
            // talk-over on the speaker (as the classic talk-over): the echo path is learnt from Jarvis's known output level
            Ring ratio = new Ring(75), quiet = new Ring(75);
            int sounding = 0, loud = 0;
            EchoGuard eg = echo;
            // with Jarvis's own echo removal the mic is the plain one (no level control or echo cancelling of the phone's
            // that would change it as it goes): its voice is quieter, so "loud" starts lower
            float loudMin = eg != null ? 300f : 900f;
            double overMin = eg != null ? 250 : 500;
            android.media.AudioTimestamp inStamp = new android.media.AudioTimestamp();
            long framesRead = 0;
            int lv = Math.max(0, Math.min(4, prefs.bargeSens()));
            double k = BARGE_SCALE[lv];
            int need = Math.max(3, 5 + BARGE_NEED_ADJ[lv] / 2); // about 200 ms of his voice above Jarvis's echo
            boolean wasSpeaking = false;
            int overRecent = 0; // the last 15 pieces (0.6 s): where his voice was clearly on top of Jarvis's echo
            try {
                int min = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord(eg != null ? MediaRecorder.AudioSource.VOICE_RECOGNITION : MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, MIC_CHUNK * 2 * 8));
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("మైక్ తెరవలేకపోయాను (mic)");
                if (eg == null && AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(rec.getAudioSessionId());
                    if (aec != null) aec.setEnabled(true);
                }
                if (eg == null && NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(rec.getAudioSessionId());
                    if (ns != null) ns.setEnabled(true);
                }
                rec.startRecording();
                short[] buf = new short[MIC_CHUNK];
                while (!closed && !Thread.currentThread().isInterrupted()) {
                    int n = 0;
                    while (n < MIC_CHUNK && !closed) { // (a whole piece: the echo removal works in 8 ms steps)
                        int got = rec.read(buf, n, MIC_CHUNK - n);
                        if (got < 0) throw new IllegalStateException("mic read " + got);
                        n += got;
                    }
                    if (n < MIC_CHUNK) continue;
                    short[] use = buf;
                    if (eg != null) {
                        use = eg.process(buf, n, heardAt(rec, inStamp, framesRead, n));
                        followEcho(eg);
                    }
                    framesRead += n;
                    byte[] bytes = new byte[n * 2];
                    long sum = 0;
                    for (int i = 0; i < n; i++) {
                        short s = use[i];
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
                        if (rms > Math.max(floor * 4f, loudMin)) {
                            if (now - lastLoudAt > 700) loudRunAt = now; // his voice began (a new stretch of it)
                            lastLoudAt = now;
                        }
                    }
                    if (now - lastLevelPost > 100) {
                        lastLevelPost = now;
                        float level = muted ? 0f : Math.min(1f, rms / 4000f);
                        main.post(() -> l.onLiveLevel(level));
                    }
                    // muted, or (talk-over off) Jarvis is talking or its voice is still fading: nothing is sent (so it doesn't hear itself)
                    boolean hold = muted || ("off".equals(bargeMode) && (speakingNow || echoGate && now - voiceEndedAt < ECHO_TAIL_MS));
                    if (hold) {
                        if (!wasHeld && ready && !muted) sendRaw(GeminiLiveProto.audioEnd()); // what he said is taken as said
                        wasHeld = true;
                        backlog.clear();
                        continue;
                    }
                    wasHeld = false;
                    boolean newAnswer = speakingNow && !wasSpeaking;
                    wasSpeaking = speakingNow;
                    if (echoGate && !barged) {
                        org.vosk.Recognizer wr = "word".equals(bargeMode) ? word : null;
                        if (newAnswer && wr != null) { try { wr.getFinalResult(); } catch (Throwable ignored) {} } // a new answer: the word detector starts clean
                        if (speakingNow) {
                            // Jarvis's echo, learnt from its known output level (as the classic talk-over)
                            double out = PlaybackLevel.now();
                            double noise = quiet.n >= 5 ? quiet.pct(0.5) : 0;
                            double alone = Math.sqrt(Math.max(0, (double) rms * rms - noise * noise)); // the mic without the room
                            if (out > 300) sounding++;
                            if (out > 1000) ratio.add(alone / out); // the echo alone
                            else if (out >= 0 && out < 150) quiet.add(rms); // the room, in Jarvis's pauses
                            boolean judge = out >= 0 && sounding >= 15 && ratio.n >= 10; // after 0.6 s of Jarvis sounding
                            double echo = Math.hypot(ratio.pct(0.85) * Math.max(0, out), noise);
                            overRecent = ((overRecent << 1) | (judge && rms > Math.max(overMin, echo * 1.4) ? 1 : 0)) & 0x7FFF;
                            boolean stop = false;
                            if (wr != null) {
                                int w = stopWord(wr, bytes); // "Jarvis" / "stop": sure, or being said while his voice is in the mic
                                stop = w == 2 || w == 1 && Integer.bitCount(overRecent) >= 3;
                            } else if ("voice".equals(bargeMode)) { // any loud talk (the word way waits for its detector: only a tap meanwhile)
                                double limit = Math.max(Math.max(overMin, noise * 3), echo * 1.8) * k;
                                if (judge && rms > limit) stop = ++loud >= need; else loud = Math.max(0, loud - 1);
                            }
                            if (stop) { // he is talking over Jarvis: stop it; Gemini hears him once its voice has faded
                                loud = 0;
                                bargedAt = now;
                                barged = true;
                                playQueue.clear();
                                flushRequested = true;
                            }
                        } else {
                            sounding = 0;
                            loud = 0;
                            overRecent = 0;
                        }
                        // Jarvis's voice (or its fading echo): not sent (in full talk Jarvis has taken it out itself: sent)
                        if (!barged && !duplex && (speakingNow || now - voiceEndedAt < ECHO_TAIL_MS)) continue;
                    }
                    if (barged && !duplex && now - bargedAt < CUT_TAIL_MS) continue; // the cut-off voice is still fading: not sent
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
                closeWord();
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

    /** When the first of these n mic samples was heard (System.nanoTime()), from the mic's own time stamp if it has one. */
    private static long heardAt(AudioRecord rec, android.media.AudioTimestamp ts, long firstFrame, int n) {
        long now = System.nanoTime();
        long guess = now - (long) (n * 1e9 / IN_RATE) - 10_000_000L;
        try {
            if (rec.getTimestamp(ts, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                long t = ts.nanoTime + (long) ((firstFrame - ts.framePosition) * 1e9 / IN_RATE);
                if (t <= now && t > now - 1_000_000_000L) return t;
            }
        } catch (Exception ignored) {}
        return guess;
    }

    /**
     * Player thread: tells the echo removal when the speaker plays which frame - from the speaker's own time stamp
     * (exact) while it plays on, else (it had run dry: the stamp is from before the gap) from its position and the
     * latency learnt from earlier stamps.
     */
    private void mapSpeaker(AudioTrack t, long base, long written, boolean afterDry) {
        EchoGuard eg = echo;
        if (eg == null) return;
        long now = System.nanoTime();
        long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        boolean ok = false;
        if (!afterDry) {
            try { ok = t.getTimestamp(outStamp); } catch (Exception ignored) {}
        }
        if (ok && outStamp.nanoTime > now - 80_000_000L && outStamp.nanoTime < now + 100_000_000L
                && outStamp.framePosition >= base && outStamp.framePosition <= base + written) {
            eg.presented(outStamp.nanoTime, outStamp.framePosition);
            long lat = outStamp.nanoTime + (long) ((head - outStamp.framePosition) * 1e9 / OUT_RATE) - now;
            if (lat > 0 && lat < 400_000_000L) outLatNs = (long) (.8 * outLatNs + .2 * lat);
            stampsUsed++;
        } else {
            eg.presented(now + outLatNs, head);
            guessesUsed++;
        }
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
                // Jarvis's own clear voice on the AI assistant volume (as Gemini's and ChatGPT's; the volume keys work on it);
                // the call path only with "call voice" on or a Bluetooth headset (its mic only works on that path)
                boolean callVoice = callPathVoice();
                t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(callVoice ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_ASSISTANT)
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
                        long headBefore = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                        t.pause();
                        t.flush();
                        t.play();
                        base = t.getPlaybackHeadPosition();
                        written = 0;
                        EchoGuard eg = echo;
                        if (eg != null) eg.flushed(headBefore, base);
                        PlaybackLevel.begin(t, OUT_RATE, base);
                        if (jarvisSpeaking) {
                            jarvisSpeaking = false;
                            voiceEndedAt = SystemClock.elapsedRealtime();
                            main.post(() -> l.onLiveVoiceLevel(0f));
                        }
                        continue;
                    }
                    byte[] c = playQueue.poll(60, TimeUnit.MILLISECONDS);
                    if (c == null && jarvisSpeaking) mapSpeaker(t, base, written, false); // (still sounding: keep the play times fresh)
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
                        // (everything handed over has been taken: this piece starts sounding after a gap)
                        boolean dry = echo != null && (t.getPlaybackHeadPosition() & 0xFFFFFFFFL) >= base + written;
                        int w = t.write(c, off, len);
                        if (w > 0) {
                            EchoGuard eg = echo;
                            if (eg != null) eg.written(c, off, w);
                            written += w / 2;
                            mapSpeaker(t, base, written, dry);
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
