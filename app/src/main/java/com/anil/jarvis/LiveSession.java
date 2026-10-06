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
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * A live, two-way voice conversation with the OpenAI Realtime API.
 * The microphone streams continuously; Jarvis answers in its own voice and
 * stops talking as soon as Anil starts speaking (barge-in).
 */
final class LiveSession implements LiveTalk {
    interface Listener {
        void onLiveState(int orbState, String status);
        void onLiveUser(String text);
        void onLiveJarvisPartial(String text);
        void onLiveJarvis(String text);
        void onLiveLevel(float level);
        /** How loud Jarvis's own voice is right now (0..1), for the Live screen's animation. */
        default void onLiveVoiceLevel(float level) {}
        void onLiveError(String message);
        /** session is the one that ended; a host ignores it when it is no longer its current session. */
        void onLiveEnded(LiveTalk session, String reason);
        /** His words so far, while he is still talking (Gemini Live). */
        default void onLiveUserPartial(String text) {}
    }

    private static final int RATE = 24000;
    private static final int MIC_CHUNK = 2400;     // 100 ms of samples
    private static final int PLAY_CHUNK = 4800;    // 100 ms of bytes

    private static final class Chunk {
        final String item;
        final byte[] pcm;
        Chunk(String item, byte[] pcm) { this.item = item; this.pcm = pcm; }
    }

    private final Context ctx;
    private final Prefs prefs;
    private final Store store;
    private final Tools tools;
    private final Listener l;
    /** Anil's turns whose transcript has not arrived yet (committed audio minus finished transcriptions). */
    private final Object transcripts = new Object();
    private int pendingTranscripts;
    /** Set when the interpreter tool ran: this session ends and the host starts the interpreter. */
    private volatile String interpreterLang;
    private volatile String endReason = "bye";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService toolRunner = Executors.newSingleThreadExecutor();
    private final LinkedBlockingQueue<Chunk> playQueue = new LinkedBlockingQueue<>();

    private OkHttpClient client;
    private WebSocket ws;
    private volatile boolean open, closed;
    private volatile boolean responseActive, jarvisSpeaking, flushRequested, endRequested;
    /** Mic muted from the Live screen: nothing is sent until he unmutes. */
    private volatile boolean muted;
    private long lastVoicePost;
    /** Headphones / Bluetooth in use (the mic is close to his mouth) rather than the phone's speaker. */
    private volatile boolean headset;
    /** The richer session settings were refused once: the plain ones are in use. */
    private volatile boolean plainSession;
    private String instructions = "";
    private volatile long lastActivity;
    private Thread micThread, playThread;
    private AudioManager am;
    private int oldMode;
    private final StringBuilder partial = new StringBuilder();
    private long lastLevelPost;
    private String lastFrameSent;

    LiveSession(Context c, Prefs prefs, Tools tools, Listener l) {
        this.ctx = c.getApplicationContext();
        this.prefs = prefs;
        this.store = Store.get(c);
        this.tools = tools;
        this.l = l;
    }

    /** The language to interpret next, when this session ended to hand over to the live interpreter. */
    @Override public String interpreterLang() { return interpreterLang; }

    // ================================================================ start / stop

    @Override public void start(String instructions) {
        routeAudio();
        state(OrbView.THINKING, "కనెక్ట్ అవుతున్నాను…");
        client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
        Request req = new Request.Builder()
                .url("wss://api.openai.com/v1/realtime?model=" + prefs.realtimeModel())
                .addHeader("Authorization", "Bearer " + prefs.openAiKey().replaceAll("[^\\x21-\\x7E]", "")) // (a pasted key may carry invisible characters)
                .build();
        ws = client.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                open = true;
                lastActivity = SystemClock.elapsedRealtime();
                LiveSession.this.instructions = instructions;
                sendSessionUpdate(instructions, true);
                startPlayer();
                // The mic opens only once OpenAI has accepted Jarvis's settings (session.updated):
                // without them it would talk as a plain English assistant with no tools.
                main.postDelayed(setupTimeout, 12000);
            }
            @Override public void onMessage(WebSocket webSocket, String text) {
                try { handle(new JSONObject(text)); } catch (Exception ignored) {}
            }
            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                String msg = String.valueOf(t.getMessage());
                if (response != null) msg = "HTTP " + response.code() + " " + msg;
                final String m = msg;
                if (!closed) main.post(() -> { l.onLiveError(m); stop("error"); });
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                if (!closed) main.post(() -> stop("closed"));
            }
        });
    }

    /** Ends the conversation. Safe to call more than once, from any thread. */
    @Override public void stop(String reason) {
        if (closed) return;
        closed = true;
        open = false;
        main.removeCallbacks(idleCheck);
        main.removeCallbacks(setupTimeout);
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
        synchronized (transcripts) { transcripts.notifyAll(); }
        restoreAudio();
        if (client != null) {
            try { client.dispatcher().executorService().shutdown(); } catch (Exception ignored) {}
        }
        main.post(() -> l.onLiveEnded(this, reason));
    }

    @Override public boolean isOpen() { return open && !closed; }

    @Override public void setMuted(boolean m) {
        muted = m;
        if (!m) lastActivity = SystemClock.elapsedRealtime(); // a fresh start for the quiet timer
    }

    @Override public boolean isMuted() { return muted; }

    /** Sends a typed message (e.g. a protocol button) into the live conversation. */
    @Override public void sendText(String text) {
        if (!isOpen()) return;
        try {
            send(new JSONObject().put("type", "conversation.item.create").put("item", new JSONObject()
                    .put("type", "message").put("role", "user")
                    .put("content", new JSONArray().put(new JSONObject().put("type", "input_text").put("text", text)))));
            send(new JSONObject().put("type", "response.create"));
            lastActivity = SystemClock.elapsedRealtime();
            state(OrbView.THINKING, "ఆలోచిస్తున్నాను…");
        } catch (Exception ignored) {}
    }

    private volatile boolean ready;

    private final Runnable setupTimeout = new Runnable() {
        @Override public void run() {
            if (closed || ready) return;
            l.onLiveError("OpenAI Live సెషన్ సిద్ధం కాలేదు (session setup timeout)");
            stop("error");
        }
    };

    /** OpenAI accepted the settings: now listen. */
    private void onSessionReady() {
        if (ready || closed) return;
        ready = true;
        main.removeCallbacks(setupTimeout);
        lastActivity = SystemClock.elapsedRealtime();
        startMic();
        state(OrbView.LISTENING, "మాట్లాడండి…");
        main.postDelayed(idleCheck, 1000);
    }

    /** He is talking now (OpenAI heard his speech start and not yet its end). */
    private volatile boolean userSpeaking;
    private volatile long voiceEndedAt;
    private final java.util.concurrent.atomic.AtomicInteger toolsRunning = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Nobody talking for his "listening time" (Settings → వాయిస్, 8 s at first): the live talk ends quietly and the
     * mic goes off. Never while Jarvis talks, a tool works, he is talking, or he muted it himself.
     */
    private final Runnable idleCheck = new Runnable() {
        @Override public void run() {
            if (closed) return;
            long now = SystemClock.elapsedRealtime();
            long quiet = now - Math.max(lastActivity, voiceEndedAt);
            boolean calm = !responseActive && !jarvisSpeaking && playQueue.isEmpty() && !userSpeaking && !muted && toolsRunning.get() == 0 && !endRequested;
            if (calm && quiet > prefs.listenWindowSeconds() * 1000L) { stop("idle"); return; }
            main.postDelayed(this, 1000);
        }
    };

    // ================================================================ protocol

    private void send(JSONObject o) {
        WebSocket w = ws;
        if (w != null && !closed) w.send(o.toString());
    }

    /**
     * rich = the ChatGPT-like settings: wait for the end of his thought (semantic turn detection), clean the
     * mic for the speaker or headphones, and transcribe in his language. If the server refuses them, the
     * plain settings are sent again (a refused update would leave the session without instructions or tools).
     */
    private void sendSessionUpdate(String instructions, boolean rich) {
        try {
            JSONArray toolList = tools.openAiTools();
            JSONArray extra = Tools.liveOnlyTools();
            for (int i = 0; i < extra.length(); i++) toolList.put(extra.get(i));
            JSONObject turn = rich && prefs.livePatient()
                    ? new JSONObject()
                        .put("type", "semantic_vad")
                        .put("eagerness", "auto")
                        .put("create_response", true)
                        .put("interrupt_response", prefs.bargeIn())
                    : new JSONObject()
                        .put("type", "server_vad")
                        .put("threshold", 0.55)
                        .put("prefix_padding_ms", 300)
                        .put("silence_duration_ms", 700)
                        .put("create_response", true)
                        .put("interrupt_response", prefs.bargeIn());
            JSONObject transcription = new JSONObject().put("model", "gpt-4o-mini-transcribe");
            if (rich && !Brain.twoLanguages(instructions)) transcription.put("language", prefs.listenLang().startsWith("en") ? "en" : "te");
            JSONObject input = new JSONObject()
                    .put("format", new JSONObject().put("type", "audio/pcm").put("rate", RATE))
                    .put("transcription", transcription)
                    .put("turn_detection", turn);
            if (rich) input.put("noise_reduction", new JSONObject().put("type", headset ? "near_field" : "far_field"));
            JSONObject session = new JSONObject()
                    .put("type", "realtime")
                    .put("instructions", instructions)
                    .put("audio", new JSONObject()
                            .put("input", input)
                            .put("output", new JSONObject()
                                    .put("format", new JSONObject().put("type", "audio/pcm").put("rate", RATE))
                                    .put("voice", prefs.naturalVoiceName())))
                    .put("tools", toolList)
                    .put("tool_choice", "auto");
            send(new JSONObject().put("type", "session.update").put("session", session));
        } catch (Exception e) {
            main.post(() -> l.onLiveError("session setup: " + e.getMessage()));
        }
    }

    /** A refused session setting (semantic turn detection, noise filter, transcription language). */
    private static boolean sessionSettingRefused(JSONObject err) {
        if (err == null) return false;
        String all = (err.optString("param") + " " + err.optString("message") + " " + err.optString("code")).toLowerCase(java.util.Locale.ROOT);
        return all.contains("semantic") || all.contains("eagerness") || all.contains("noise_reduction")
                || all.contains("turn_detection") || all.contains("transcription") || all.contains("session.audio");
    }

    private void handle(JSONObject e) throws Exception {
        String type = e.optString("type");
        switch (type) {
            case "session.updated":
                main.post(this::onSessionReady);
                break;
            case "input_audio_buffer.speech_started":
                lastActivity = SystemClock.elapsedRealtime();
                userSpeaking = true;
                sendCameraFrame();
                if (prefs.bargeIn() && (jarvisSpeaking || !playQueue.isEmpty())) {
                    playQueue.clear();
                    flushRequested = true;
                }
                state(OrbView.LISTENING, "వింటున్నాను…");
                break;
            case "input_audio_buffer.speech_stopped":
                lastActivity = SystemClock.elapsedRealtime();
                userSpeaking = false;
                state(OrbView.THINKING, "ఆలోచిస్తున్నాను…");
                break;
            case "input_audio_buffer.committed":
                // Anil's turn is final; its transcript follows (completed or failed).
                synchronized (transcripts) { pendingTranscripts++; }
                break;
            case "conversation.item.input_audio_transcription.completed": {
                Usage.transcription(e.optJSONObject("usage"));
                String t = e.optString("transcript", "").trim();
                // Saved here, not on the main thread, so the tools of this turn can check his exact words.
                if (!t.isEmpty()) store.addChat("user", t, false);
                transcriptDone();
                if (!t.isEmpty()) main.post(() -> l.onLiveUser(t));
                break;
            }
            case "conversation.item.input_audio_transcription.failed":
                // His words are unknown: save an empty turn so a check of "what did he say last"
                // (payment / send confirmations) fails safe instead of reading an older "yes".
                store.addChat("user", "", false);
                transcriptDone();
                break;
            case "response.created":
                responseActive = true;
                synchronized (partial) { partial.setLength(0); }
                break;
            case "response.output_audio.delta":
            case "response.audio.delta": {
                lastActivity = SystemClock.elapsedRealtime();
                byte[] pcm = Base64.decode(e.optString("delta"), Base64.DEFAULT);
                if (pcm.length > 0) playQueue.offer(new Chunk(e.optString("item_id"), pcm));
                break;
            }
            case "response.output_audio_transcript.delta":
            case "response.audio_transcript.delta": {
                String s;
                synchronized (partial) { partial.append(e.optString("delta")); s = partial.toString(); }
                main.post(() -> l.onLiveJarvisPartial(s));
                break;
            }
            case "response.output_audio_transcript.done":
            case "response.audio_transcript.done": {
                String t = e.optString("transcript", "").trim();
                synchronized (partial) { partial.setLength(0); }
                if (!t.isEmpty()) main.post(() -> l.onLiveJarvis(t));
                break;
            }
            case "response.done":
                onResponseDone(e.optJSONObject("response"));
                break;
            case "error": {
                JSONObject err = e.optJSONObject("error");
                String msg = err == null ? "unknown error" : err.optString("message", err.optString("code"));
                String code = err == null ? "" : err.optString("code");
                if (!ready && sessionSettingRefused(err)) {
                    if (!plainSession) {
                        plainSession = true;
                        sendSessionUpdate(instructions, false); // the plain settings, so instructions and tools are in place
                        break;
                    }
                    // even the plain settings were refused: stop, rather than talk without Jarvis's instructions
                    main.post(() -> { l.onLiveError("Live సెట్టింగ్స్ OpenAI ఒప్పుకోలేదు: " + msg); stop("error"); });
                    break;
                }
                // Truncation races and "no active response" are harmless; don't bother Anil with them.
                if (code.contains("truncat") || code.contains("no_active_response") || msg.contains("truncat")) break;
                main.post(() -> l.onLiveError(msg));
                break;
            }
            default:
                break;
        }
    }

    /** With the live camera open, Jarvis gets the current picture each time Anil starts talking. */
    private void sendCameraFrame() {
        String f = CameraPanel.latestFrame;
        if (f == null || f.equals(lastFrameSent)) return;
        lastFrameSent = f;
        send(safe(() -> new JSONObject().put("type", "conversation.item.create").put("item", new JSONObject()
                .put("type", "message").put("role", "user")
                .put("content", new JSONArray().put(new JSONObject()
                        .put("type", "input_image").put("image_url", "data:image/jpeg;base64," + f))))));
    }

    private void onResponseDone(JSONObject r) throws Exception {
        responseActive = false;
        if (r == null) return;
        Usage.realtime(prefs.realtimeModel(), r.optJSONObject("usage"));
        if ("failed".equals(r.optString("status"))) {
            JSONObject d = r.optJSONObject("status_details");
            JSONObject err = d == null ? null : d.optJSONObject("error");
            String msg = err != null ? err.optString("message") : "response failed";
            main.post(() -> l.onLiveError(msg));
        }
        JSONArray out = r.optJSONArray("output");
        boolean anyCall = false;
        for (int i = 0; out != null && i < out.length(); i++) {
            JSONObject item = out.getJSONObject(i);
            if (!"function_call".equals(item.optString("type"))) continue;
            anyCall = true;
            final String name = item.optString("name");
            final String callId = item.optString("call_id");
            final String rawArgs = item.optString("arguments", "{}");
            state(OrbView.THINKING, Tools.statusFor(name));
            toolsRunning.incrementAndGet();
            toolRunner.submit(() -> {
                try { runTool(name, callId, rawArgs); } finally { toolsRunning.decrementAndGet(); lastActivity = SystemClock.elapsedRealtime(); }
            });
        }
        if (anyCall) {
            toolRunner.submit(() -> {
                lastActivity = SystemClock.elapsedRealtime();
                send(safe(() -> new JSONObject().put("type", "response.create")));
            });
        } else if (endRequested) {
            // The goodbye has been generated; hang up once it has been played.
            main.postDelayed(() -> waitAndClose(0), 300);
        } else if (!jarvisSpeaking && playQueue.isEmpty()) {
            state(OrbView.LISTENING, "మాట్లాడండి…");
        }
    }

    private void transcriptDone() {
        synchronized (transcripts) {
            if (pendingTranscripts > 0) pendingTranscripts--;
            transcripts.notifyAll();
        }
    }

    /** Waits (up to 5 s) until Anil's words of this turn are in the Store, so tools can check them. */
    private void awaitTranscripts() {
        long until = SystemClock.elapsedRealtime() + 5000;
        synchronized (transcripts) {
            while (pendingTranscripts > 0 && !closed) {
                long left = until - SystemClock.elapsedRealtime();
                if (left <= 0) {
                    pendingTranscripts = 0; // treat them as lost, so later tools don't wait for them again
                    break;
                }
                try {
                    transcripts.wait(left);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void runTool(String name, String callId, String rawArgs) {
        String result;
        if ("end_conversation".equals(name)) {
            endRequested = true;
            main.postDelayed(() -> stop("bye"), 12000); // hang up even if no goodbye arrives
            result = "{\"ok\":true}";
        } else {
            awaitTranscripts();
            if (closed) return; // stopped while waiting: don't act any more
            JSONObject args;
            try { args = new JSONObject(rawArgs); } catch (Exception ex) { args = new JSONObject(); }
            result = tools.execute(name, args);
            // He switched how Jarvis listens / talks by voice: this OpenAI talk ends after its line when it is no longer the choice.
            if ("voice_mode".equals(name) && result.contains("\"ok\":true") && !(prefs.liveMode() && !prefs.liveGemini())) {
                endReason = "switched";
                endRequested = true;
                main.postDelayed(() -> stop("switched"), 12000);
            }
            // The interpreter can't run inside this session: after the short "starting" line,
            // end it and let the screen start a live interpreter session.
            String lang = Tools.takeInterpreter();
            if (lang != null) {
                interpreterLang = lang;
                endReason = "interpreter";
                endRequested = true;
                main.postDelayed(() -> stop("interpreter"), 12000);
            }
        }
        final String res = result;
        send(safe(() -> new JSONObject().put("type", "conversation.item.create").put("item", new JSONObject()
                .put("type", "function_call_output").put("call_id", callId).put("output", res))));
    }

    private void waitAndClose(int tries) {
        if (closed) return;
        if ((jarvisSpeaking || !playQueue.isEmpty()) && tries < 40) {
            main.postDelayed(() -> waitAndClose(tries + 1), 250);
            return;
        }
        stop(endReason);
    }

    private interface JsonMaker { JSONObject make() throws Exception; }

    private static JSONObject safe(JsonMaker m) {
        try { return m.make(); } catch (Exception e) { return new JSONObject(); }
    }

    private void state(int orb, String text) {
        main.post(() -> { if (!closed) l.onLiveState(orb, text); });
    }

    // ================================================================ audio

    private boolean routed, speakerOn;

    private void routeAudio() {
        am = ctx.getSystemService(AudioManager.class);
        if (am == null) return;
        oldMode = am.getMode();
        if (oldMode != AudioManager.MODE_NORMAL) return; // a call (or another call app) has the sound: leave it as it is
        routed = true;
        try {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo pick = null;
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    int t = d.getType();
                    if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLE_HEADSET
                            || t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_USB_HEADSET) { pick = d; break; }
                }
                headset = pick != null;
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

    @SuppressLint("MissingPermission") // MainActivity checks RECORD_AUDIO before starting
    private void startMic() {
        micThread = new Thread(() -> {
            AudioRecord rec = null;
            AcousticEchoCanceler aec = null;
            NoiseSuppressor ns = null;
            try {
                int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, MIC_CHUNK * 2 * 4));
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("మైక్ తెరవలేకపోయాను");
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
                byte[] bytes = new byte[MIC_CHUNK * 2];
                while (!closed && !Thread.currentThread().isInterrupted()) {
                    int n = rec.read(buf, 0, MIC_CHUNK);
                    if (n <= 0) { if (n < 0) throw new IllegalStateException("mic read " + n); continue; }
                    long sum = 0;
                    for (int i = 0; i < n; i++) {
                        short s = buf[i];
                        bytes[2 * i] = (byte) (s & 0xFF);
                        bytes[2 * i + 1] = (byte) ((s >> 8) & 0xFF);
                        sum += (long) s * s;
                    }
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastLevelPost > 100) {
                        lastLevelPost = now;
                        float level = muted ? 0f : (float) Math.min(1.0, Math.sqrt(sum / (double) n) / 4000.0);
                        main.post(() -> l.onLiveLevel(level));
                    }
                    if (muted) continue; // muted: send nothing
                    // Without barge-in, don't send the mic while Jarvis talks (stops it hearing itself).
                    if (!prefs.bargeIn() && (jarvisSpeaking || !playQueue.isEmpty())) continue;
                    String b64 = Base64.encodeToString(bytes, 0, n * 2, Base64.NO_WRAP);
                    send(new JSONObject().put("type", "input_audio_buffer.append").put("audio", b64));
                }
            } catch (Exception e) {
                if (!closed) {
                    String msg = String.valueOf(e.getMessage());
                    main.post(() -> { l.onLiveError(msg); stop("error"); });
                }
            } finally {
                if (aec != null) aec.release();
                if (ns != null) ns.release();
                if (rec != null) {
                    try { rec.stop(); } catch (Exception ignored) {}
                    rec.release();
                }
            }
        }, "jarvis-live-mic");
        micThread.start();
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
                int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(Math.max(min, PLAY_CHUNK * 2))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                t.play();
                long base = 0;             // playback head position right after the last flush
                long written = 0;          // frames handed to the track since the last flush
                long itemStart = 0;        // frame at which the current reply item started
                String item = null;
                while (!closed && !Thread.currentThread().isInterrupted()) {
                    if (flushRequested) {
                        flushRequested = false;
                        long played = Math.max(0, t.getPlaybackHeadPosition() - base - itemStart);
                        t.pause();
                        t.flush();
                        t.play();
                        base = t.getPlaybackHeadPosition();
                        if (item != null) {
                            final String it = item;
                            final long ms = played * 1000 / RATE;
                            send(safe(() -> new JSONObject().put("type", "conversation.item.truncate")
                                    .put("item_id", it).put("content_index", 0).put("audio_end_ms", ms)));
                        }
                        written = 0;
                        itemStart = 0;
                        item = null;
                        jarvisSpeaking = false;
                        continue;
                    }
                    Chunk c = playQueue.poll(60, TimeUnit.MILLISECONDS);
                    if (c == null) {
                        if (jarvisSpeaking && t.getPlaybackHeadPosition() - base >= written) {
                            jarvisSpeaking = false;
                            voiceEndedAt = SystemClock.elapsedRealtime();
                            main.post(() -> l.onLiveVoiceLevel(0f));
                            if (!responseActive && !endRequested) state(OrbView.LISTENING, "మాట్లాడండి…");
                        }
                        continue;
                    }
                    if (!c.item.equals(item)) {
                        item = c.item;
                        itemStart = written;
                    }
                    if (!jarvisSpeaking) {
                        jarvisSpeaking = true;
                        state(OrbView.SPEAKING, "మాట్లాడుతున్నాను…");
                    }
                    for (int off = 0; off < c.pcm.length && !flushRequested && !closed; off += PLAY_CHUNK) {
                        int len = Math.min(PLAY_CHUNK, c.pcm.length - off);
                        voiceLevel(c.pcm, off, len);
                        int w = t.write(c.pcm, off, len);
                        if (w > 0) written += w / 2;
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                if (!closed) {
                    String msg = String.valueOf(e.getMessage());
                    main.post(() -> l.onLiveError("speaker: " + msg));
                }
            } finally {
                jarvisSpeaking = false;
                if (t != null) {
                    try { t.pause(); t.flush(); } catch (Exception ignored) {}
                    t.release();
                }
            }
        }, "jarvis-live-play");
        playThread.start();
    }
}
