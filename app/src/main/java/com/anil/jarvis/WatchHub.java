package com.anil.jarvis;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.SpeechRecognizer;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The phone's side of the Jarvis watch app (phase 2a). The watch is Jarvis's remote: its mic, speaker, screen and
 * vibration; the thinking stays on the phone. They talk over the watch's Bluetooth link (Google's Wear "Data Layer"
 * messages; the watch app has the same package name and signing key as this app, which that link requires).
 *
 *  - Wrist raised: the watch sends the next few seconds of sound; "Hey Jarvis" / "Jarvis" (and, if he wants, only his
 *    voice) is checked here (StreamWake), and the rest of that sound is his question.
 *  - His words are written out by the AI he chose for the watch (OpenAI / Gemini, Ears), by the watch's own Google
 *    voice typing (the words come as text), or without internet by Jarvis's offline Telugu ears (TeluguEars).
 *  - The question goes to the brain with the usual tools (WatchTalkActivity); the answer's text and voice go back to
 *    the watch (WatchVoice), or to the phone's earphones when those are on the phone.
 *
 * Messages (paths under /jarvis): see the P_ names. Sound both ways is 16 kHz mu-law (Ulaw), 100-200 ms a message.
 */
final class WatchHub {
    private WatchHub() {}

    // watch -> phone
    static final String P_HELLO = "/jarvis/hello", P_MIC_START = "/jarvis/mic/start", P_MIC_DATA = "/jarvis/mic/data",
            P_MIC_END = "/jarvis/mic/end", P_TEXT = "/jarvis/text", P_STOP = "/jarvis/stop", P_PLAYED = "/jarvis/played",
            P_CONFIRM_ANSWER = "/jarvis/confirm/answer", P_DONE = "/jarvis/done", P_BEAT = "/jarvis/beat";
    // phone -> watch
    static final String P_SETTINGS = "/jarvis/settings", P_STATE = "/jarvis/state", P_MIC_STOP = "/jarvis/mic/stop",
            P_AUDIO_START = "/jarvis/audio/start", P_AUDIO_DATA = "/jarvis/audio/data", P_AUDIO_END = "/jarvis/audio/end",
            P_LISTEN = "/jarvis/listen", P_CONFIRM = "/jarvis/confirm", P_CONFIRM_DONE = "/jarvis/confirm/done",
            P_PING = "/jarvis/ping";

    /** The main thread (made on first use, so the parts without Android can be tested on a desk). */
    private static final class M { static final Handler h = new Handler(Looper.getMainLooper()); }

    // ================================================================ his watch settings (Settings → ⌚ వాచ్)

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_watch", Context.MODE_PRIVATE); }

    /** W9: wrist raised, then "Hey Jarvis" / "Jarvis" (the watch listens a few seconds each time the screen lights up). */
    static boolean raiseOn(Context c) { return sp(c).getBoolean("raise", true); }
    /** W9: always listening in these hours only (more battery). */
    static boolean hoursOn(Context c) { return sp(c).getBoolean("hours", false); }
    static int fromHour(Context c) { return sp(c).getInt("from", 7); }
    static int toHour(Context c) { return sp(c).getInt("to", 9); }

    /**
     * Who writes out what he says to the watch: "openai", "gemini" (his key, from the phone) or "watch" (the watch's own
     * Google voice typing). Never switched by itself; without internet Jarvis's offline Telugu ears hear him (as on the phone).
     */
    static String ears(Context c) {
        String s = sp(c).getString("ears", "");
        if (!s.isEmpty()) return s;
        Prefs p = new Prefs(c);
        String m = p.earsMode();
        if ("openai".equals(m) || "gemini".equals(m)) return m;
        return p.openAiKey().trim().isEmpty() ? "watch" : "openai";
    }

    static String earsText(String m) {
        switch (m) {
            case "gemini": return "Gemini (మీ key తో, ఫోన్ ద్వారా)";
            case "watch": return "వాచ్‌లోని Google వాయిస్ టైపింగ్ (ఉచితం; వాచ్‌లో ఉంటేనే)";
            default: return "OpenAI (మీ key తో, ఫోన్ ద్వారా; సరైన తెలుగు)";
        }
    }

    /** W12: only his voice wakes the watch (his voice print from the phone, a little looser for the watch's mic). */
    static boolean lockOn(Context c) { return sp(c).getBoolean("lock", new Prefs(c).voiceLock()); }
    /** W11: answers spoken on the watch (off: text and a buzz only). */
    static boolean speakOn(Context c) { return sp(c).getBoolean("speak", true); }
    /** W13: saying "Jarvis" while it answers stops it and listens. */
    static boolean stopVoice(Context c) { return sp(c).getBoolean("stop_voice", true); }
    /** W10: opening the watch app (its icon, the Home key double press, a watch-face shortcut) starts listening. */
    static boolean openListen(Context c) { return sp(c).getBoolean("open_listen", true); }
    /** W16 / W17: with the phone locked (in his pocket), messages and calls are told on the watch and answered there. */
    static boolean msgsOn(Context c) { return sp(c).getBoolean("msgs", true); }
    /** W15 / W8: Jarvis's own alerts (cooker, door, medicine, reminders...) on the watch, each with its own vibration. */
    static boolean alertsOn(Context c) { return sp(c).getBoolean("alerts", true); }
    /** W19: Jarvis's alarm only as a vibration on the wrist (the phone rings after 3 minutes if he hasn't stopped it). */
    static boolean alarmOn(Context c) { return sp(c).getBoolean("alarm", true); }
    /** W20: the watch says when he walks away without the phone. */
    static boolean lostOn(Context c) { return sp(c).getBoolean("lost", true); }
    /** W3: what Jarvis looks like on the watch: "orb", "holo" (the face as a hologram) or "human" (the phone's face). */
    static String look(Context c) { return sp(c).getString("look", "orb"); }

    static String lookText(String l) {
        switch (l) {
            case "holo": return "హోలోగ్రామ్ ముఖం";
            case "human": return "మనిషి ముఖం (ఫోన్‌లోని Jarvis ముఖం)";
            default: return "వెలిగే ఆర్బ్ (వలయాలు)";
        }
    }

    static void set(Context c, String key, Object v) {
        SharedPreferences.Editor e = sp(c).edit();
        if (v instanceof Boolean) e.putBoolean(key, (Boolean) v);
        else if (v instanceof Integer) e.putInt(key, (Integer) v);
        else e.putString(key, String.valueOf(v));
        e.apply();
        pushSettings(c);
    }

    static JSONObject settings(Context c) {
        JSONObject o = new JSONObject();
        try {
            Prefs p = new Prefs(c);
            o.put("raise", raiseOn(c)).put("hours", hoursOn(c)).put("from", fromHour(c)).put("to", toHour(c))
                    .put("ears", ears(c)).put("speak", speakOn(c) && p.voiceReplies()).put("stopVoice", stopVoice(c))
                    .put("openListen", openListen(c)).put("name", p.name()).put("lang", p.listenLang())
                    .put("online", Net.online(c)).put("alerts", alertsOn(c)).put("lost", lostOn(c)).put("look", look(c))
                    .put("theme", Ui.theme(c));
        } catch (Exception ignored) {}
        return o;
    }

    static void pushSettings(Context c) { send(c, P_SETTINGS, settings(c)); }

    // ================================================================ the watch

    private static volatile String node;
    private static volatile long nodeAt;
    /** When the watch last sent anything (elapsedRealtime), 0 never. */
    static volatile long seenAt;
    /** What the watch said about itself (its hello): app version, model, mic permission, its speech service. */
    static volatile JSONObject info;
    static volatile String lastError = "";

    /** For Settings and "Jarvis చెక్". */
    static String status(Context c) {
        StringBuilder b = new StringBuilder();
        JSONObject i = info;
        long ago = seenAt == 0 ? -1 : (SystemClock.elapsedRealtime() - seenAt) / 1000;
        if (i == null) b.append("✗ వాచ్ యాప్ ఇంకా ఫోన్‌తో మాట్లాడలేదు (వాచ్‌లో Jarvis ఒకసారి తెరవండి)");
        else {
            b.append("✓ వాచ్: ").append(i.optString("model", "Galaxy Watch")).append(" · యాప్ 1.0.").append(i.optInt("app"))
                    .append(ago >= 0 ? " · చివరిసారి " + (ago < 90 ? ago + " సె" : ago / 60 + " ని") + " క్రితం" : "");
            b.append(i.optBoolean("mic", true) ? "\n✓ వాచ్ మైక్ అనుమతి ఉంది" : "\n✗ వాచ్ మైక్ అనుమతి లేదు: వాచ్‌లో Jarvis తెరిచి Allow నొక్కండి");
            b.append(i.optBoolean("rec", false) ? "\n✓ వాచ్‌లో Google వాయిస్ టైపింగ్ ఉంది" : "\n· వాచ్‌లో Google వాయిస్ టైపింగ్ లేదు (OpenAI / Gemini ఎంచుకోండి)");
            if (!i.optBoolean("listening", true)) b.append("\n✗ వాచ్ చేయి ఎత్తినప్పుడు వినడం ఆగింది: వాచ్‌లో Jarvis ఒకసారి తెరవండి");
        }
        if (!android.provider.Settings.canDrawOverlays(c))
            b.append("\n✗ 'Display over other apps' అనుమతి లేదు: ఫోన్ లాక్‌లో ఉన్నప్పుడు వాచ్ ప్రశ్నలకు జవాబు రాదు (వేక్ వర్డ్ సెక్షన్‌లో బటన్)");
        if (lockOn(c) && VoiceLock.print(c) == null) b.append("\n· మీ గొంతు ఇంకా నేర్పించలేదు: వాచ్ అందరి గొంతుకీ పలుకుతుంది");
        if (StreamWake.lastDistance >= 0)
            b.append(String.format(java.util.Locale.ROOT, "\nచివరి వాచ్ గొంతు పోలిక: %.2f (%s)", StreamWake.lastDistance, StreamWake.lastAccepted ? "మీరే" : "మీరు కాదు అనుకున్నాను"));
        if (!StreamWake.status.isEmpty()) b.append("\n· ").append(StreamWake.status);
        if (!WatchVoice.lastError.isEmpty()) b.append("\n· వాచ్ గొంతు: ").append(WatchVoice.lastError);
        if (!lastError.isEmpty()) b.append("\n· ").append(lastError);
        return b.toString();
    }

    // ================================================================ sending (one at a time, in order)

    private static final ExecutorService out = Executors.newSingleThreadExecutor();

    static void send(Context c, String path, JSONObject o) { send(c, path, o.toString().getBytes(StandardCharsets.UTF_8)); }

    static void send(Context c, String path, byte[] data) { send(c, path, data, null, null, false); }

    /** stillWanted: checked just before it goes (Jarvis's voice after his stop isn't sent ahead of what comes next). */
    static void send(Context c, String path, byte[] data, java.util.function.BooleanSupplier stillWanted) { send(c, path, data, stillWanted, null, false); }

    /**
     * failed: run on the main thread if it couldn't go (no watch connected, or the link refused it), so the phone can
     * do it its own way. near: only to a watch near the phone (Bluetooth), looked up afresh (not through the internet).
     */
    static void send(Context c, String path, byte[] data, java.util.function.BooleanSupplier stillWanted, Runnable failed, boolean near) {
        send(c, path, data, stillWanted, failed, near, null);
    }

    /** sent: run on the main thread once it has gone. */
    static void send(Context c, String path, byte[] data, java.util.function.BooleanSupplier stillWanted, Runnable failed, boolean near, Runnable sent) {
        final Context app = c.getApplicationContext();
        out.execute(() -> {
            if (stillWanted != null && !stillWanted.getAsBoolean()) return;
            boolean ok = false;
            try {
                String n = near ? nearId(app) : nodeId(app);
                if (n == null) lastError = near ? "వాచ్ దగ్గర లేదు / కనెక్ట్ కాలేదు" : "వాచ్ కనెక్ట్ అయి లేదు (Bluetooth / Galaxy Wearable చూడండి)";
                else {
                    Tasks.await(Wearable.getMessageClient(app).sendMessage(n, path, data), 4, TimeUnit.SECONDS);
                    ok = true;
                }
            } catch (Exception e) {
                node = null; // look the watch up again next time
                lastError = "వాచ్‌కి పంపలేకపోయాను: " + e.getMessage();
            }
            if (!ok && failed != null) M.h.post(failed);
            if (ok && sent != null) M.h.post(sent);
        });
    }

    static void send(Context c, String path, JSONObject o, Runnable failed, boolean near) {
        send(c, path, o.toString().getBytes(StandardCharsets.UTF_8), null, failed, near);
    }

    /** A watch connected over Bluetooth right now (fresh look, not the cached one). Background only. */
    private static String nearId(Context app) throws Exception {
        List<Node> nodes = Tasks.await(Wearable.getNodeClient(app).getConnectedNodes(), 3, TimeUnit.SECONDS);
        for (Node x : nodes) if (x.isNearby()) { node = x.getId(); nodeAt = SystemClock.elapsedRealtime(); return x.getId(); }
        return null;
    }

    /** The watch's id on the link: the one it last wrote from, else a connected one (nearby first). Background only. */
    private static String nodeId(Context app) throws Exception {
        String n = node;
        if (n != null && SystemClock.elapsedRealtime() - nodeAt < 10 * 60_000L) return n;
        List<Node> nodes = Tasks.await(Wearable.getNodeClient(app).getConnectedNodes(), 3, TimeUnit.SECONDS);
        Node pick = null;
        for (Node x : nodes) if (x.isNearby()) { pick = x; break; }
        if (pick == null && !nodes.isEmpty()) pick = nodes.get(0);
        if (pick == null) return null;
        node = pick.getId();
        nodeAt = SystemClock.elapsedRealtime();
        return node;
    }

    static void state(Context c, String s, String heard, String reply, String status) {
        JSONObject o = new JSONObject();
        try {
            o.put("s", s).put("online", Net.online(c));
            if (heard != null) o.put("heard", heard);
            if (reply != null) o.put("reply", reply);
            if (status != null) o.put("status", status);
        } catch (Exception ignored) {}
        send(c, P_STATE, o);
    }

    // ================================================================ a watch talk going on

    private static volatile boolean talkingFlag;
    private static volatile long talkAt;
    /** He raised his wrist and the watch heard "Jarvis" (elapsedRealtime). */
    static volatile long wokeAt;
    /** The last sound of a wrist-raise listen (elapsedRealtime). */
    private static volatile long wakeDataAt;

    /** A watch talk is going on: the phone's own wake word and remarks wait (let go 2 minutes after the last step). */
    static boolean talking() { return talkingFlag && SystemClock.elapsedRealtime() - talkAt < 2 * 60_000L; }

    private static void talk(boolean on) { talkingFlag = on; talkAt = SystemClock.elapsedRealtime(); }

    /** The watch is hearing his wrist-raise sound right now (the phone's own wake word waits a moment for it). */
    static boolean wakeStreaming() { return SystemClock.elapsedRealtime() - wakeDataAt < 1500; }

    static boolean wokeRecently(long ms) { return wokeAt > 0 && SystemClock.elapsedRealtime() - wokeAt < ms; }

    // ================================================================ sound from the watch

    private static final short[] END = new short[0];

    /** One stretch of sound from the watch (a wrist raise, or a tap / follow-up question). */
    static final class Session implements Ears.Source {
        final int id;
        volatile boolean wake;
        final String why;
        private final LinkedBlockingQueue<short[]> q = new LinkedBlockingQueue<>();
        /** Given first: the start of his question, kept while the wake word / voice was checked. */
        final ArrayDeque<short[]> front = new ArrayDeque<>();
        volatile boolean stopped;
        /** The watch itself ended this sound (P_MIC_END). */
        volatile boolean watchEnded;
        /** This sound had "Hey Jarvis" in it (what follows is his question). */
        volatile boolean woken;
        volatile ListenMic ears;
        private int nextSeq;
        private final TreeMap<Integer, byte[]> early = new TreeMap<>();
        private short[] carry = new short[0];

        Session(int id, boolean wake, String why) { this.id = id; this.wake = wake; this.why = why; }

        /** A message of sound (the link thread). Out-of-order ones wait a moment; a lost one is skipped. */
        synchronized void data(int seq, byte[] p) {
            if (seq < nextSeq) return;
            early.put(seq, p);
            if (early.size() > 6) nextSeq = early.firstKey(); // one went missing: carry on without it
            while (!early.isEmpty() && early.firstKey() == nextSeq) {
                byte[] b = early.remove(nextSeq++);
                frames(Ulaw.decode(b, 8, b.length - 8));
            }
        }

        private void frames(short[] s) {
            short[] all = new short[carry.length + s.length];
            System.arraycopy(carry, 0, all, 0, carry.length);
            System.arraycopy(s, 0, all, carry.length, s.length);
            int k = 0;
            for (; k + 320 <= all.length; k += 320) q.offer(java.util.Arrays.copyOfRange(all, k, k + 320));
            carry = java.util.Arrays.copyOfRange(all, k, all.length);
        }

        void end() { q.offer(END); }

        /** The next 20 ms (null: ended, or no sound for 3 s: the watch went away). */
        short[] next() {
            short[] f = front.poll();
            if (f != null) return f;
            try { f = q.poll(3, TimeUnit.SECONDS); } catch (InterruptedException e) { return null; }
            return f == null || f == END || stopped ? null : f;
        }

        @Override public int read(short[] f, int n) {
            short[] fr = next();
            if (fr == null) return -1;
            int k = Math.min(n, fr.length);
            System.arraycopy(fr, 0, f, 0, k);
            return k;
        }
    }

    private static volatile Session cur;
    private static final Object WAKE = new Object();
    private static StreamWake wake;
    /** A follow-up listen (after an answer): silence there just ends the talk. */
    private static volatile boolean followListen;

    // ================================================================ messages from the watch (WatchLink)

    static void onMessage(Context c, String from, String path, byte[] data) {
        final Context app = c.getApplicationContext();
        appCtx = app;
        node = from;
        nodeAt = SystemClock.elapsedRealtime();
        seenAt = nodeAt;
        try {
            switch (path) {
                case P_MIC_DATA: {
                    Session s = cur;
                    if (s != null && s.id == Ulaw.id(data)) {
                        if (s.wake) wakeDataAt = SystemClock.elapsedRealtime();
                        s.data(Ulaw.seq(data), data);
                    }
                    return;
                }
                case P_HELLO: {
                    JSONObject o = new JSONObject(text(data));
                    info = o;
                    seen(app, o);
                    pushSettings(app);
                    WatchAlerts.schedule(app);
                    new Thread(() -> WatchAlerts.pushInfo(app), "watch-info").start();
                    return;
                }
                case P_BEAT: seen(app, new JSONObject(text(data)));
                    return;
                case WatchAlerts.P_ALERT_ACTION: {
                    JSONObject o = new JSONObject(text(data));
                    M.h.post(() -> WatchAlerts.action(app, o));
                    return;
                }
                case WatchAlerts.P_ALARM_ANSWER: {
                    JSONObject o = new JSONObject(text(data));
                    M.h.post(() -> WatchAlerts.alarmAnswered(app, o));
                    return;
                }
                case P_MIC_START: micStart(app, new JSONObject(text(data)));
                    return;
                case P_MIC_END: {
                    Session s = cur;
                    if (s != null && s.id == new JSONObject(text(data)).optInt("id")) { s.watchEnded = true; s.end(); }
                    return;
                }
                case P_DONE: M.h.post(() -> done(app)); // the watch ended the talk itself (nothing heard, its own error)
                    return;
                case P_TEXT: {
                    JSONObject o = new JSONObject(text(data));
                    String t = o.optString("text").trim();
                    boolean follow = "follow".equals(o.optString("why"));
                    M.h.post(() -> {
                        Session s = cur; // (a card's word tapped while the watch's mic was listening: that listen is over)
                        if (s != null && !s.wake) { s.stopped = true; s.end(); ListenMic l = s.ears; s.ears = null; if (l != null) l.cancel(); }
                        if (callKey == null) interrupt(app); // (the announcement still being said, and its listen after it, are over)
                        heard(app, t, follow);
                    });
                    return;
                }
                case P_STOP: M.h.post(() -> stopAll(app, true));
                    return;
                case P_PLAYED: {
                    int id = new JSONObject(text(data)).optInt("id");
                    M.h.post(() -> played(id));
                    return;
                }
                case P_CONFIRM_ANSWER: {
                    JSONObject o = new JSONObject(text(data));
                    LinkedBlockingQueue<Boolean> w = confirms.get(o.optInt("id"));
                    if (w != null) w.offer(o.optBoolean("yes"));
                    return;
                }
                default:
            }
        } catch (Exception e) {
            lastError = "వాచ్ సందేశం (" + path + "): " + e.getMessage();
        }
    }

    private static String text(byte[] d) { return d == null ? "" : new String(d, StandardCharsets.UTF_8); }

    private static void micStart(Context app, JSONObject o) {
        boolean isWake = "wake".equals(o.optString("kind"));
        Session s = new Session(o.optInt("id"), isWake, o.optString("why"));
        Session old = cur;
        cur = s;
        if (old != null) {
            old.stopped = true;
            old.end();
            ListenMic l = old.ears;
            if (l != null) M.h.post(l::cancel);
        }
        if (isWake) {
            new Thread(() -> wakeLoop(app, s), "jarvis-watch-wake").start();
        } else {
            followListen = "follow".equals(s.why);
            talk(true);
            M.h.post(() -> { fine(app); hear(app, s); });
        }
    }

    private static void stopMic(Context app, Session s) {
        s.stopped = true;
        s.end();
        try { send(app, P_MIC_STOP, new JSONObject().put("id", s.id)); } catch (Exception ignored) {}
    }

    /** Wrist-raise sound: "Hey Jarvis" / "Jarvis" (his voice when he wants), then his question goes on to be heard. */
    private static void wakeLoop(Context app, Session s) {
        try { wakeSteps(app, s); } finally { letWakeGoLater(app); }
    }

    private static void wakeSteps(Context app, Session s) {
        synchronized (WAKE) {
            if (s.stopped) return;
            try {
                if (wake == null) wake = new StreamWake(app);
                wake.reset();
            } catch (Throwable e) {
                lastError = "వాచ్ కోసం \"Hey Jarvis\" మోడల్ తెరవలేకపోయాను: " + e.getMessage();
                stopMic(app, s);
                return;
            }
            short[] chunk = new short[StreamWake.CHUNK];
            int fill = 0;
            long t = 0, heardT = 0, lastVoiceT = 0;
            double noise = 0;
            ArrayDeque<short[]> recent = new ArrayDeque<>();
            List<short[]> kept = null;
            while (!s.stopped) {
                short[] f = s.next();
                if (f == null) break; // the watch ended this sound (its limit) without "Jarvis"
                wakeDataAt = SystemClock.elapsedRealtime();
                // The watch keeps sending until this verdict: no "Jarvis" once his voice has stopped a while (or 10 s).
                // Decided on the sound's own clock, so a slow moment here never cuts his question off.
                heardT += 20;
                double sum = 0;
                for (short v : f) sum += (double) v * v;
                double rms = Math.sqrt(sum / Math.max(1, f.length));
                if (rms > Math.max(250, noise * 2.5)) lastVoiceT = heardT;
                else noise = noise == 0 ? rms : noise * 0.95 + rms * 0.05;
                if (kept == null && !wake.pending() && (heardT > 2500 && heardT - lastVoiceT > 1500 || heardT > 10_000)) {
                    stopMic(app, s);
                    return;
                }
                if (kept != null) kept.add(f);
                else { recent.addLast(f); while (recent.size() > 10) recent.removeFirst(); } // the last 200 ms
                int off = 0;
                while (off < f.length) {
                    int k = Math.min(StreamWake.CHUNK - fill, f.length - off);
                    System.arraycopy(f, off, chunk, fill, k);
                    fill += k;
                    off += k;
                    if (fill < StreamWake.CHUNK) continue;
                    fill = 0;
                    t += 80;
                    int r;
                    try { r = wake.feed(chunk, t); } catch (Throwable e) { lastError = "వాచ్ వేక్ వర్డ్: " + e.getMessage(); stopMic(app, s); return; }
                    if ((r == StreamWake.CANDIDATE || r == StreamWake.WOKE) && kept == null) kept = new ArrayList<>(recent);
                    if (r == StreamWake.WOKE) {
                        s.front.addAll(kept); // what he says right after "Jarvis" is his question
                        woke(app, s);
                        return;
                    }
                    if (r == StreamWake.NOT_HIM) {
                        stopMic(app, s);
                        state(app, "nolock", null, null, "మీ గొంతులా అనిపించలేదు");
                        return;
                    }
                }
            }
        }
    }

    /** The wake models are let go after 10 minutes without a wrist raise. */
    private static void letWakeGoLater(Context app) {
        M.h.removeCallbacks(dropWake);
        M.h.postDelayed(dropWake, 10 * 60_000L);
    }

    private static final Runnable dropWake = () -> new Thread(() -> {
        synchronized (WAKE) {
            StreamWake w = wake;
            if (w == null || SystemClock.elapsedRealtime() - w.usedAt < 9 * 60_000L) return;
            w.close();
            wake = null;
        }
    }, "jarvis-watch-drop").start();

    /** The watch heard "Jarvis" (session thread). */
    private static void woke(Context app, Session s) {
        wokeAt = SystemClock.elapsedRealtime();
        M.h.post(() -> {
            if (s.stopped || cur != s) return;
            talk(true);
            followListen = false;
            fine(app);
            interrupt(app); // an answer still going (he said "Jarvis" over it): stopped, his new question now
            state(app, "woke", null, null, null);
            if ("watch".equals(ears(app))) { // the watch's own Google voice typing hears the question
                stopMic(app, s);
                try { send(app, P_LISTEN, new JSONObject().put("why", "wake")); } catch (Exception ignored) {}
                return;
            }
            s.wake = false;
            s.woken = true;
            hear(app, s); // (all that the watch sent after "Jarvis" is waiting in this sound)
        });
    }

    /** He is talking to the watch: a "బాగున్నారా?" check (a fall heard at home) is answered. Main thread. */
    private static void fine(Context app) {
        if (CrashAlert.active && "fall".equals(CrashAlert.kind)) CrashAlert.ok(app);
    }

    /** His question in this sound: written out by his watch choice, or without internet by the offline Telugu ears. Main thread. */
    private static void hear(Context app, Session s) {
        if (s.stopped || cur != s) return;
        Prefs p = new Prefs(app);
        String mode = ears(app);
        boolean online = Net.online(app);
        if (online && ("openai".equals(mode) || "gemini".equals(mode))) {
            Ears e = new Ears(app, p).from(s, mode);
            s.ears = e;
            e.start(6000, false, new Heard(app, s, false));
        } else if (TeluguEars.ready(app) && !TeluguEars.broken(app)) {
            // no internet (the phone's, or the watch's own for its voice typing): Jarvis's offline Telugu ears, said so
            TeluguEars e = new TeluguEars(app).from(s);
            s.ears = e;
            e.start(6000, false, new Heard(app, s, true));
        } else {
            stopMic(app, s);
            talk(false);
            state(app, "error", null, null, online
                    ? "వాచ్‌కి ఇంటర్నెట్ లేదు, ఫోన్‌లో Offline తెలుగు మోడల్ కూడా లేదు (ఫోన్ Settings → Offline వాయిస్)."
                    : "ఫోన్‌కి ఇంటర్నెట్ లేదు, Offline తెలుగు మోడల్ కూడా లేదు (నెట్ ఉన్నప్పుడు ఫోన్ Settings → Offline వాయిస్ లో డౌన్‌లోడ్ చేయండి).");
        }
    }

    private static final class Heard implements Ears.Callback {
        private final Context app;
        private final Session s;
        private final boolean offline;
        private long partialAt;
        Heard(Context app, Session s, boolean offline) { this.app = app; this.s = s; this.offline = offline; }

        @Override public void opened() {
            if (cur != s) return;
            sstate(app, s, "listening", null, offline ? "వాచ్ / ఫోన్‌కి నెట్ లేదు: Offline తెలుగు చెవులతో వింటున్నాను" : null);
        }
        @Override public void level(float level) {}
        @Override public void understanding() {
            if (cur != s) return;
            stopMic(app, s);
            sstate(app, s, "understanding", null, null);
        }
        @Override public void partial(String text) {
            if (cur != s) return;
            long now = SystemClock.elapsedRealtime();
            if (now - partialAt < 400) return;
            partialAt = now;
            sstate(app, s, "listening", text, null);
        }
        @Override public void heard(String text) {
            s.ears = null;
            stopMic(app, s);
            if (cur != s) return;
            String t = text == null ? "" : text.trim();
            if (s.woken && onlyWakeWord(t)) t = ""; // (just the end of "Hey Jarvis": he hasn't asked anything yet)
            if (t.isEmpty()) {
                talk(false);
                endScreen();
                sstate(app, s, "idle", null, followListen ? null : "ఏమీ వినిపించలేదు");
                return;
            }
            WatchHub.heard(app, t, followListen);
        }
        @Override public void failed(int error) {
            s.ears = null;
            stopMic(app, s);
            if (cur != s) return;
            boolean nothing = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            talk(false);
            endScreen();
            if (nothing && followListen) { sstate(app, s, "idle", null, null); return; } // silence after an answer: the talk is over
            sstate(app, s, nothing ? "idle" : "error", null, VoiceIO.failText(error));
        }
    }

    /** A state about this sound (the watch ignores it once it is hearing a newer one). */
    private static void sstate(Context app, Session s, String st, String heard, String status) {
        JSONObject o = new JSONObject();
        try {
            o.put("s", st).put("online", Net.online(app)).put("sid", s.id);
            if (heard != null) o.put("heard", heard);
            if (status != null) o.put("status", status);
        } catch (Exception ignored) {}
        send(app, P_STATE, o);
    }

    /** Only "Hey Jarvis" (or a piece of it): no question in it. */
    static boolean onlyWakeWord(String t) {
        String s = t == null ? "" : t.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\p{Punct}।…\\s]+", " ")
                .replaceAll("\\b(hey|hi|jarvis|jarvi|jervis|javis)\\b|హే|హాయ్|జార్విస్|జార్వీస్|జార్విస|జార్వి", " ").trim();
        return s.isEmpty();
    }

    /** His words (from the phone's ears or the watch's own voice typing). Main thread. */
    private static void heard(Context app, String text, boolean follow) {
        if (callKey != null && CallControl.isRinging()) { callWords(app, text); return; } // W17: "ఎత్తు" / "కట్"
        if (text.isEmpty()) {
            talk(false);
            endScreen();
            state(app, "idle", null, null, follow ? null : "ఏమీ వినిపించలేదు");
            return;
        }
        talk(true);
        state(app, "thinking", text, null, null);
        WatchTalkActivity a = WatchTalkActivity.current;
        if (a != null && !a.isFinishing() && !a.isDestroyed()) { a.hear(text); return; }
        if (!android.provider.Settings.canDrawOverlays(app) && !MainActivity.visible) {
            talk(false);
            state(app, "error", text, null, "ఫోన్‌లో Jarvis కి 'Display over other apps' అనుమతి కావాలి (ఫోన్ Settings → వేక్ వర్డ్ → బటన్). అప్పుడే ఫోన్ లాక్‌లో ఉన్నా జవాబు వస్తుంది.");
            return;
        }
        try {
            app.startActivity(new Intent(app, WatchTalkActivity.class).putExtra(WatchTalkActivity.EXTRA_TEXT, text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Exception e) {
            talk(false);
            state(app, "error", text, null, "ఫోన్‌లో జవాబు మొదలుపెట్టలేకపోయాను: " + e.getMessage());
        }
    }

    // ================================================================ the answer (WatchTalkActivity)

    private static volatile int playId;
    private static Runnable whenPlayed;

    /** The answer: its text on the watch, then its voice (watch speaker / earbuds, or the phone's earphones). Main thread. */
    static void reply(Context c, String text, boolean error, boolean speak, Runnable spoken) {
        talk(true);
        Context app = c.getApplicationContext();
        appCtx = app;
        android.media.AudioManager am = app.getSystemService(android.media.AudioManager.class);
        boolean local = speak && am != null && Sounds.earphones(am); // his earphones are on the phone: said there
        JSONObject o = new JSONObject();
        try {
            o.put("s", error ? "error" : "reply").put("online", Net.online(app)).put("reply", text)
                    .put("voice", !speak ? "none" : local ? "phone" : "watch");
        } catch (Exception ignored) {}
        send(app, P_STATE, o);
        M.h.removeCallbacks(playedLate);
        whenPlayed = null;
        if (!speak) { M.h.post(spoken); return; }
        int id = ++playId;
        whenPlayed = spoken;
        WatchVoice.say(app, text, id, local, ms -> M.h.post(() -> {
            if (playId != id || whenPlayed == null) return;
            // the watch says when it has played it; if that never comes (link lost), go on anyway
            M.h.removeCallbacks(playedLate);
            M.h.postDelayed(playedLate, ms + 8000);
        }), () -> M.h.post(() -> played(id)));
    }

    private static final Runnable playedLate = () -> played(playId);

    private static void played(int id) {
        if (id != playId) return;
        Runnable r = whenPlayed;
        whenPlayed = null;
        M.h.removeCallbacks(playedLate);
        if (r != null) r.run();
    }

    /** After an answer that wants his reply (or a follow-up): the watch listens again. */
    static void listen(Context c, String why) {
        talk(true);
        try { send(c, P_LISTEN, new JSONObject().put("why", why)); } catch (Exception ignored) {}
    }

    /** The watch ended the talk itself (nothing heard in its own voice typing, its error): quietly over. Main thread. */
    private static void done(Context app) {
        Session s = cur;
        if (s != null && s.ears != null) return; // (a question still being heard here: not over)
        talk(false);
        endScreen();
    }

    /** The talk ended without a new question: the invisible screen closes soon. Main thread. */
    private static void endScreen() {
        WatchTalkActivity a = WatchTalkActivity.current;
        if (a != null) a.quietEnd();
    }

    // ================================================================ his watch: known, near, on his wrist

    /** Its last news (wall clock), kept so a restarted phone app still knows the watch. */
    private static void seen(Context app, JSONObject o) {
        SharedPreferences.Editor e = sp(app).edit().putLong("seen_wall", System.currentTimeMillis());
        if (o.has("worn")) { worn = o.optBoolean("worn"); e.putString("worn", String.valueOf(worn)); }
        e.apply();
    }

    /** On his wrist: true / false; null when the watch can't tell. */
    static volatile Boolean worn;

    /** Worn, as last told (kept over a phone restart). */
    static Boolean worn(Context c) {
        Boolean w = worn;
        if (w != null) return w;
        String s = sp(c).getString("worn", "");
        return s.isEmpty() ? null : Boolean.valueOf(s);
    }

    /** The Jarvis watch app has talked to this phone (installed and set up). */
    static boolean known(Context c) { return sp(c).getLong("seen_wall", 0) > 0; }

    /** The watch is connected now (it says so every 10 minutes, and with everything it sends). */
    static boolean watchHere(Context c) {
        long wall = sp(c).getLong("seen_wall", 0);
        return wall > 0 && System.currentTimeMillis() - wall < 40 * 60_000L; // (its news comes every ~15-30 min; sends check afresh)
    }

    /** The phone is in his pocket / on the table: screen off or locked. */
    static boolean phoneIdle(Context c) {
        android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
        android.app.KeyguardManager km = c.getSystemService(android.app.KeyguardManager.class);
        return pm != null && !pm.isInteractive() || km != null && km.isKeyguardLocked();
    }

    /** On the bike (or driving): the watch only buzzes; voice stays with the phone and his helmet (W18). */
    static boolean riding(Context c) { return Bike.riding(c) || new Prefs(c).driving(); }

    /** Messages / calls go to the watch now: it is near and on his wrist, the phone is locked, he isn't riding. */
    static boolean routeToWatch(Context c, boolean call) {
        return msgsOn(c) && watchHere(c) && !Boolean.FALSE.equals(worn(c)) && phoneIdle(c) && !riding(c) && (call || !talking());
    }

    /** A question from the watch may act while the phone is locked in his pocket (a call, a reply: still only after his yes). */
    static boolean lockedOk(Context c) { return sp(c).getBoolean("locked_ok", true); }

    // ================================================================ W16 / W17: messages and calls on the watch

    /** One line said on the watch (good morning after its alarm, a short answer). Main thread. */
    static void say(Context c, String text) {
        reply(c, text, false, speakOn(c) && new Prefs(c).voiceReplies(), () -> idle(c, null));
    }

    /**
     * W16: a new message told on the watch instead of the phone's panel: who wrote, "చదవమంటారా?", and the watch listens.
     * The message itself is with the brain (as for the panel): it reads it only if he says yes, and sends a reply only
     * after his "పంపు". The card's buttons answer the same way as his voice. Main thread.
     */
    static void announce(Context c, String say, String ask, String context, Runnable phoneWay) {
        Context app = c.getApplicationContext();
        final int gen = ++msgGen;
        Runnable back = () -> { // not reachable after all: the talk here ends, the phone tells it its way
            if (gen != msgGen) return;
            msgGen++;
            interrupt(app);
            talk(false);
            phoneWay.run(); // (the panel gives the brain the message itself)
        };
        Runnable got = () -> Store.get(app).addChat("assistant", say + " " + ask + context, false); // the brain has it for "చదువు"
        try {
            JSONObject o = new JSONObject().put("id", ("msg" + say).hashCode()).put("kind", "message").put("title", say)
                    .put("text", ask).put("says", new org.json.JSONArray().put("చదువు").put("వద్దు"));
            send(app, WatchAlerts.P_ALERT, o.toString().getBytes(StandardCharsets.UTF_8), null, back, true, got);
        } catch (Exception ignored) {}
        reply(app, say + " " + ask, false, speakOn(app) && new Prefs(app).voiceReplies(), () -> listen(app, "answer"));
    }

    /** Bumped by each message / call sent to the watch (its "not reachable" fallback runs only for the latest of each). */
    private static int msgGen, callGen;

    /** The ringing call being told on the watch (its key), or null. */
    private static volatile String callKey;
    private static int callTries;

    /** W17: who is calling, said on the watch; "ఎత్తు" / "కట్" by voice or the card's buttons. Main thread. */
    static void call(Context c, String key, String say, Runnable phoneWay) {
        Context app = c.getApplicationContext();
        if (talking()) { // a call comes first: the talk on the watch stops (its mic too)
            Session s = cur;
            if (s != null && !s.wake) stopMic(app, s);
            stopAll(app, false);
        }
        callKey = key;
        callTries = 0;
        final int gen = ++callGen;
        Runnable back = () -> {
            if (gen != callGen || callKey == null) return;
            callGen++;
            callKey = null;
            interrupt(app);
            talk(false);
            phoneWay.run();
        };
        try {
            send(app, WatchAlerts.P_ALERT, new JSONObject().put("id", ("call" + key).hashCode()).put("kind", "call").put("title", say)
                    .put("text", "ఎత్తమంటారా, కట్ చేయమంటారా?").put("says", new org.json.JSONArray().put("ఎత్తు").put("కట్")), back, true);
        } catch (Exception ignored) {}
        reply(app, say + ". ఎత్తమంటారా, కట్ చేయమంటారా?", false, speakOn(app), () -> listen(app, "call"));
        M.h.removeCallbacks(callWatch);
        M.h.postDelayed(callWatch, 1000);
    }

    /** The call stopped ringing (answered on the phone / watch, or ended): the watch stops asking. */
    private static final Runnable callWatch = new Runnable() {
        @Override public void run() {
            if (callKey == null) return;
            if (CallControl.isRinging()) { M.h.postDelayed(this, 1000); return; }
            String key = callKey;
            callKey = null;
            if (appCtx != null) try { send(appCtx, WatchAlerts.P_ALERT_GONE, new JSONObject().put("id", ("call" + key).hashCode())); } catch (Exception ignored) {}
            Session s = cur;
            if (s != null) { s.stopped = true; s.end(); ListenMic l = s.ears; s.ears = null; if (l != null) l.cancel(); }
            interrupt(appCtx);
            talk(false);
            if (appCtx != null) state(appCtx, "idle", null, null, null);
        }
    };
    private static Context appCtx;

    private static final String[] CALL_NO = {"కట్", "cut", "reject", "వద్దు", "decline", "తర్వాత", "busy", "బిజీ", "no", "నో", "తీయకు", "ఎత్తకు", "ఎత్తొద్దు",
            "ఎత్తకండి", "తీయొద్దు", "మాట్లాడలేను", "మాట్లాడను", "మాట్లాడలేం"};
    private static final String[] CALL_YES = {"ఎత్తు", "ఎత్తండి", "ఎత్తి", "లిఫ్ట్", "lift", "answer", "attend", "pick", "yes", "అవును", "ఓకే", "ok", "okay", "సరే", "మాట్లాడ", "ఆన్సర్"};

    /** "ఎత్తు" / "కట్" (as in the phone's panel; "no" never matches "now"). Main thread. */
    private static void callWords(Context app, String text) {
        String low = text.toLowerCase(java.util.Locale.ROOT);
        boolean no = hasWord(low, CALL_NO), yes = !no && hasWord(low, CALL_YES);
        if (!no && !yes) {
            // (after 3 tries it stops asking; the card's buttons still answer the call while it rings)
            if (++callTries >= 3) { talk(false); state(app, "idle", null, null, "ఎత్తాలంటే వాచ్ కార్డ్‌లో \"ఎత్తు\" / ఫోన్‌లో ఆకుపచ్చ నొక్కండి"); return; }
            reply(app, "ఎత్తమంటారా, కట్ చేయమంటారా?", false, speakOn(app), () -> listen(app, "call"));
            return;
        }
        callKey = null;
        String r = yes ? CallControl.answer(app) : CallControl.decline(app);
        String msg;
        switch (r) {
            case "answered": msg = "కాల్ ఎత్తాను."; break;
            case "declined": msg = "కాల్ కట్ చేశాను."; break;
            case "need_permission": msg = "కాల్స్ ఎత్తడానికి ఫోన్‌లో అనుమతి కావాలి: ఫోన్‌లో Jarvis తెరిచి Allow నొక్కండి."; break;
            default: msg = yes ? "కాల్ ఎత్తలేకపోయాను, మీరే నొక్కండి." : "కాల్ కట్ చేయలేకపోయాను, మీరే నొక్కండి.";
        }
        talk(false);
        reply(app, msg, !"answered".equals(r) && !"declined".equals(r), false, () -> idle(app, null)); // (text only: the call has the sound now)
    }

    private static boolean hasWord(String low, String[] words) {
        for (String tok : low.split("[^\\p{L}\\p{M}\\p{N}]+")) {
            if (tok.isEmpty()) continue;
            for (String w : words) {
                if (tok.equals(w)) return true;
                if (w.charAt(0) > 0x7F && tok.startsWith(w)) return true;
            }
        }
        return false;
    }

    /** The talk is over. */
    static void idle(Context c, String status) {
        talk(false);
        state(c, "idle", null, null, status);
    }

    /** A short line on the watch (a tool's notice). */
    static void notice(Context c, String text) { state(c, "notice", null, null, text); }

    /** Stops what is going on (his tap on the watch, covering it, or a new "Jarvis" over an answer). Main thread. */
    static void stopAll(Context app, boolean byWatch) {
        Session s = cur;
        if (s != null) {
            s.stopped = true;
            s.end();
            ListenMic l = s.ears;
            s.ears = null;
            if (l != null) l.cancel();
        }
        interrupt(app);
        talk(false);
        endScreen();
        // (nothing sent back: the watch is already idle, and a late "idle" would stop his next tap-to-talk)
    }

    /** An answer on its way or being said is dropped. Main thread. */
    private static void interrupt(Context app) {
        releaseConfirms();
        WatchVoice.stop();
        playId++;
        whenPlayed = null;
        M.h.removeCallbacks(playedLate);
        WatchTalkActivity a = WatchTalkActivity.current;
        if (a != null) a.stopTalk();
    }

    // ================================================================ "are you sure?" on the watch (Tools.Host.confirm)

    private static final Map<Integer, LinkedBlockingQueue<Boolean>> confirms = new ConcurrentHashMap<>();
    private static int confirmId;

    /** Asks on the watch and waits for his tap (background thread). autoSeconds > 0: yes by itself after that, if he sees it. */
    static boolean confirm(Context c, String title, String message, String yes, int autoSeconds) {
        int id;
        synchronized (confirms) { id = ++confirmId; }
        LinkedBlockingQueue<Boolean> w = new LinkedBlockingQueue<>();
        confirms.put(id, w);
        try {
            send(c, P_CONFIRM, new JSONObject().put("id", id).put("title", title == null ? "" : title)
                    .put("msg", message == null ? "" : message).put("yes", yes == null ? "సరే" : yes).put("auto", autoSeconds));
            Boolean b = w.poll(autoSeconds > 0 ? autoSeconds + 40 : 45, TimeUnit.SECONDS);
            return b != null && b;
        } catch (Exception e) {
            return false;
        } finally {
            confirms.remove(id);
            try { send(c, P_CONFIRM_DONE, new JSONObject().put("id", id)); } catch (Exception ignored) {} // (the watch stops asking)
        }
    }

    /** "Are you sure?" questions still waiting get "no" (his stop, a new question). */
    static void releaseConfirms() {
        for (LinkedBlockingQueue<Boolean> w : confirms.values()) w.offer(false);
    }

    /** Jarvis's voice for answer id is still wanted (not stopped, not replaced). */
    static boolean voiceWanted(int id) { return id == playId; }
}
