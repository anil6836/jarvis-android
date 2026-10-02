package com.anil.jarvis;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Records a church sermon, a meeting or a class with the phone's mic (in 10-minute parts, small AAC files), and when it
 * stops: the words written out (OpenAI speech-to-text, his key) and short Telugu notes by his AI: main points and Bible
 * verses for a sermon, decisions and who-does-what for a meeting. Saved in Downloads/Jarvis/recordings. Phone calls are
 * never recorded. The wake word is off while it records (one mic).
 */
public class RecorderService extends Service {
    static final String ACTION_START = "com.anil.jarvis.REC_START", ACTION_STOP = "com.anil.jarvis.REC_STOP";
    static final String KEY = "recordings";
    private static final int NOTE = 251;
    private static final long PART_MS = 10 * 60000L, MAX_MS = 3 * 3600000L;

    static volatile boolean recording;
    /** Recordings whose words / notes are being made now. */
    private static final java.util.concurrent.atomic.AtomicInteger working = new java.util.concurrent.atomic.AtomicInteger();
    static volatile long startedAt;
    private static volatile String kind = "meeting", title = "";
    private static final String STOP_HINT = "ఆపడానికి ఇక్కడ ⏹ నొక్కండి (లేదా Jarvis తెరిచి 'రికార్డింగ్ ఆపు' అనండి)";

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaRecorder rec;
    private File dir;
    private int part;
    private String itemId = "";

    static boolean processing() { return working.get() > 0; }

    static boolean start(Context c, String kind, String title) {
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false;
        Intent i = new Intent(c, RecorderService.class).setAction(ACTION_START).putExtra("kind", kind).putExtra("title", title);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void stop(Context c) {
        if (!recording) return;
        try { c.startService(new Intent(c, RecorderService.class).setAction(ACTION_STOP)); } catch (Exception ignored) {}
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        String a = i == null ? ACTION_STOP : i.getAction();
        if (ACTION_START.equals(a)) {
            if (recording) return START_NOT_STICKY;
            kind = i.getStringExtra("kind") == null ? "meeting" : i.getStringExtra("kind");
            title = i.getStringExtra("title") == null ? "" : i.getStringExtra("title");
            if (!note("🎙️ రికార్డ్ అవుతోంది", STOP_HINT, true)) { // Android refused the mic in the background
                Announcer.say(this, "రికార్డింగ్ మొదలుపెట్టలేకపోయాను. Jarvis తెరిచి ఉంచి మళ్లీ అడగండి.");
                if (!processing()) stopSelf();
                return START_NOT_STICKY;
            }
            dir = new File(getExternalFilesDir(null), "recordings/" + System.currentTimeMillis());
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            part = 0;
            recording = true; // first: the wake word must not take the mic back
            startedAt = System.currentTimeMillis();
            WakeService.pause(this);
            try {
                JSONObject item = new JSONObject().put("id", Notes.id("rec")).put("dir", dir.getPath()).put("kind", kind)
                        .put("name", name(kind, title, startedAt)).put("started", startedAt).put("minutes", 0).put("status", "recording");
                Notes.add(this, KEY, item, 50);
                itemId = item.optString("id");
            } catch (Exception ignored) {}
            main.postDelayed(() -> begin(true), 600); // a moment for the wake word to let go of the mic
        } else if (ACTION_STOP.equals(a)) {
            finish();
        }
        return START_NOT_STICKY;
    }

    private void begin(boolean firstTry) {
        if (!recording) return;
        try {
            startPart();
            main.postDelayed(tick, 60000);
            main.postDelayed(this::finish, MAX_MS); // 3 hours at most
        } catch (Exception e) {
            if (firstTry) { main.postDelayed(() -> begin(false), 1500); return; }
            recording = false;
            WakeService.resume(this);
            Announcer.say(this, "రికార్డింగ్ మొదలుపెట్టలేకపోయాను: మైక్ వేరే యాప్ వాడుతోంది కావచ్చు.");
            if (!processing()) { stopForeground(true); stopSelf(); }
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            long m = (System.currentTimeMillis() - startedAt) / 60000;
            note("🎙️ రికార్డ్ అవుతోంది · " + m + " నిమిషాలు", STOP_HINT, true);
            main.postDelayed(this, 60000);
        }
    };

    private void startPart() throws Exception {
        MediaRecorder r = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioSamplingRate(16000);
            r.setAudioEncodingBitRate(32000);
            r.setAudioChannels(1);
            r.setOutputFile(new File(dir, String.format(Locale.ENGLISH, "part%02d.m4a", part)).getPath());
            r.setMaxDuration((int) PART_MS);
            r.setOnInfoListener((mr, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) main.post(this::nextPart);
            });
            r.prepare();
            r.start();
        } catch (Exception e) {
            try { r.release(); } catch (Exception ignored) {}
            throw e;
        }
        rec = r;
    }

    private void nextPart() {
        if (!recording) return;
        release();
        part++;
        try { startPart(); } catch (Exception e) { finish(); }
    }

    private void release() {
        MediaRecorder r = rec;
        rec = null;
        if (r == null) return;
        try { r.stop(); } catch (Exception ignored) {} // nothing recorded yet: that part is just empty
        try { r.release(); } catch (Exception ignored) {}
    }

    /** Stop recording; the notes are made in the background, then the service ends (unless a new recording began). */
    private void finish() {
        if (!recording) { if (!processing()) stopSelf(); return; }
        recording = false;
        main.removeCallbacksAndMessages(null);
        release();
        WakeService.resume(this);
        long started = startedAt, ended = System.currentTimeMillis();
        final File d = dir;
        final String k = kind, t = title, id = itemId;
        working.incrementAndGet();
        note("🎙️ రికార్డింగ్ అయింది", "మాటలు రాసి సారాంశం తయారు చేస్తున్నాను…", false);
        Context app = getApplicationContext();
        new Thread(() -> {
            try { process(app, d, k, t, started, ended); }
            catch (Exception e) { failed(app, d, e); }
            finally {
                working.decrementAndGet();
                main.post(() -> { if (!recording && !processing()) { stopForeground(true); stopSelf(); } });
            }
        }, "jarvis-recording").start();
    }

    private boolean note(String head, String text, boolean mic) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_rec", "రికార్డింగ్", NotificationManager.IMPORTANCE_LOW));
            Notification.Builder b = new Notification.Builder(this, "jarvis_rec").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle(head).setContentText(text).setOngoing(true).setShowWhen(false);
            if (mic) b.addAction(new Notification.Action.Builder(null, "⏹ ఆపు",
                    PendingIntent.getService(this, 252, new Intent(this, RecorderService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)).build());
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTE, b.build(), mic ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTE, b.build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public void onDestroy() {
        if (recording) { recording = false; release(); WakeService.resume(this); }
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- the words and the notes

    static String label(String kind) {
        switch (kind == null ? "" : kind) {
            case "sermon": return "ప్రసంగం";
            case "class": return "క్లాస్";
            case "meeting": return "మీటింగ్";
            default: return "రికార్డింగ్";
        }
    }

    static String name(String kind, String title, long started) {
        return (title == null || title.trim().isEmpty() ? label(kind) : title.trim()) + " · " + new SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(new Date(started));
    }

    /** Something broke while making the notes: said in the notification; "రికార్డింగ్ సారాంశం" tries again (parts already written are kept). */
    private static void failed(Context c, File dir, Exception e) {
        try {
            for (JSONObject o : Notes.list(c, KEY)) if (o.optString("dir").equals(dir.getPath()))
                finishWith(c, o, "failed", "సారాంశం తయారుచేయడం మధ్యలో ఆగింది (" + (e.getMessage() == null ? "error" : e.getMessage()) + "). 'రికార్డింగ్ సారాంశం' అంటే మళ్లీ ప్రయత్నిస్తాను.");
        } catch (Exception ignored) {}
    }

    /** Writes the words and the notes; saves them; a notification when ready. Also used to try again later ("summary"). */
    static JSONObject process(Context c, File dir, String kind, String title, long started, long ended) throws Exception {
        Prefs p = new Prefs(c);
        long minutes = Math.max(1, (ended - started) / 60000);
        JSONObject item = null;
        for (JSONObject o : Notes.list(c, KEY)) if (o.optString("dir").equals(dir.getPath())) item = o;
        if (item == null) {
            item = new JSONObject().put("id", Notes.id("rec")).put("dir", dir.getPath()).put("kind", kind).put("name", name(kind, title, started))
                    .put("started", started);
            Notes.add(c, KEY, item, 50);
        }
        if (item.optLong("minutes") <= 0 || "recording".equals(item.optString("status"))) item.put("minutes", minutes);
        item.put("status", "recorded");
        cleanup(c);
        String key = p.openAiKey().trim();
        if (key.isEmpty()) return finishWith(c, item, "saved", "మాటలు రాయడానికి OpenAI key కావాలి (సెట్టింగ్స్). ఆడియో ఫోన్‌లో ఉంది.");
        if (!Net.online(c)) return finishWith(c, item, "waiting", "ఇంటర్నెట్ లేదు: నెట్ వచ్చాక 'రికార్డింగ్ సారాంశం' అని అడగండి.");
        File[] parts = dir.listFiles((d, n) -> n.endsWith(".m4a"));
        if (parts == null || parts.length == 0) return finishWith(c, item, "empty", "ఏమీ రికార్డ్ కాలేదు.");
        Arrays.sort(parts);
        StringBuilder words = new StringBuilder();
        for (File f : parts) {
            if (f.length() < 2000) continue; // an empty part (stopped right after a new part began)
            File txt = new File(dir, f.getName().replace(".m4a", ".txt"));
            String t;
            if (txt.exists()) t = new String(java.nio.file.Files.readAllBytes(txt.toPath()), StandardCharsets.UTF_8);
            else {
                t = WaMedia.transcribe(c, key, Uri.fromFile(f), "part.m4a", "audio/mp4"); // a failure here: the parts done so far stay written
                java.nio.file.Files.write(txt.toPath(), t.getBytes(StandardCharsets.UTF_8));
            }
            if (!t.trim().isEmpty()) words.append(t.trim()).append("\n");
        }
        if (words.toString().trim().isEmpty()) return finishWith(c, item, "no_words", "రికార్డింగ్‌లో మాటలు వినపడలేదు.");
        String notes = "";
        String why = "";
        if (!p.apiKey().isEmpty()) {
            String ask;
            switch (kind) {
                case "sermon": ask = "This is a church sermon. In simple Telugu: the message's title (one line); the main points (3-7 short bullets); "
                        + "every Bible verse mentioned (book chapter:verse); one practical takeaway for this week."; break;
                case "meeting": ask = "This is a meeting. In simple Telugu: a 3-5 line summary; decisions made; action items (who does what, by when); "
                        + "dates, amounts and numbers mentioned."; break;
                default: ask = "In simple Telugu: a 3-5 line summary and the key points (short bullets)."; break;
            }
            String w = words.length() > 60000 ? words.substring(0, 60000) + "\n…" : words.toString(); // a very long one: the first hours
            try {
                notes = Brain.oneShot(p, "You write clear, faithful notes of a recording. Only what was said; nothing invented. Plain text, no markdown symbols except '-' bullets.",
                        ask + "\n\nWords of the recording (machine-written, may have small mistakes):\n" + w, null, false, 3000);
            } catch (Exception e) {
                why = e.getMessage() == null ? "AI error" : e.getMessage(); // the words are still saved
            }
        }
        String file = item.optString("name").replaceAll("[^\\p{L}\\p{M}\\p{N}]+", "_") + ".txt";
        String body = item.optString("name") + " (" + item.optLong("minutes") + " నిమిషాలు)\n\n"
                + (notes == null || notes.isEmpty() ? "" : "సారాంశం\n" + notes.trim() + "\n\n") + "మాటలు\n" + words;
        Coder.Made m = Coder.save(c, "Jarvis/recordings", file, "text/plain", body.getBytes(StandardCharsets.UTF_8));
        item.put("notes", notes == null ? "" : notes.trim()).put("file", m.where);
        if (m.uri != null) item.put("uri", m.uri.toString());
        String line = notes != null && !notes.isEmpty() ? "సారాంశం రెడీ: " + m.where
                : !why.isEmpty() ? "మాటలు రాశాను, సారాంశం కాలేదు (" + why + "): " + m.where : "మాటలు రాశాను (సారాంశానికి AI key కావాలి): " + m.where;
        return finishWith(c, item, "ready", line);
    }

    /** Audio of recordings no longer in the list (the list keeps the newest 50) is deleted after a day. */
    private static void cleanup(Context c) {
        try {
            java.util.Set<String> keep = new java.util.HashSet<>();
            for (JSONObject o : Notes.list(c, KEY)) keep.add(o.optString("dir"));
            File root = new File(c.getExternalFilesDir(null), "recordings");
            File[] dirs = root.listFiles(File::isDirectory);
            if (dirs == null) return;
            for (File d : dirs) {
                if (keep.contains(d.getPath()) || System.currentTimeMillis() - d.lastModified() < 86400000L) continue;
                File[] fs = d.listFiles();
                if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                //noinspection ResultOfMethodCallIgnored
                d.delete();
            }
        } catch (Exception ignored) {}
    }

    private static JSONObject finishWith(Context c, JSONObject item, String status, String line) throws Exception {
        item.put("status", status).put("status_line", line);
        List<JSONObject> l = Notes.list(c, KEY);
        for (int i = 0; i < l.size(); i++) if (l.get(i).optString("id").equals(item.optString("id"))) l.set(i, item);
        Notes.save(c, KEY, l, 50);
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_rec_done", "రికార్డింగ్ సారాంశం", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(c, 253, new Intent(c, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_ASK, "నా చివరి రికార్డింగ్ సారాంశం చెప్పు (voice_recorder summary).")
                    .putExtra(MainActivity.EXTRA_LABEL, "🎙️ రికార్డింగ్ సారాంశం").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(NOTE + 1, new Notification.Builder(c, "jarvis_rec_done").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("🎙️ " + item.optString("name")).setContentText(line).setStyle(new Notification.BigTextStyle().bigText(line))
                    .setContentIntent(open).setAutoCancel(true).build());
        } catch (Exception ignored) {}
        return item;
    }

    /** The recordings, newest first. */
    static JSONArray list(Context c) throws Exception {
        JSONArray a = new JSONArray();
        List<JSONObject> l = Notes.list(c, KEY);
        for (int i = l.size() - 1; i >= 0; i--) {
            JSONObject o = l.get(i);
            a.put(new JSONObject().put("n", l.size() - i).put("name", o.optString("name")).put("minutes", o.optLong("minutes"))
                    .put("status", o.optString("status")).put("file", o.optString("file")));
        }
        return a;
    }

    /** The n-th newest (1 = last); notes made again if they are not there yet. */
    static JSONObject summary(Context c, int n) throws Exception {
        List<JSONObject> l = Notes.list(c, KEY);
        if (l.isEmpty()) return new JSONObject().put("ok", false).put("error", "none").put("message", "No recording yet.");
        int i = l.size() - Math.max(1, Math.min(n <= 0 ? 1 : n, l.size()));
        JSONObject o = l.get(i);
        if ("recording".equals(o.optString("status")) && recording) return new JSONObject().put("ok", false).put("error", "recording").put("message", "It is still recording.");
        boolean noNotes = "ready".equals(o.optString("status")) && o.optString("notes").isEmpty() && !new Prefs(c).apiKey().isEmpty();
        if ((!"ready".equals(o.optString("status")) || noNotes) && !processing()) {
            long mins = Math.max(1, o.optLong("minutes"));
            o = process(c, new File(o.optString("dir")), o.optString("kind"), "", o.optLong("started"), o.optLong("started") + mins * 60000L);
        }
        return new JSONObject().put("ok", "ready".equals(o.optString("status"))).put("name", o.optString("name")).put("minutes", o.optLong("minutes"))
                .put("notes", o.optString("notes")).put("saved_in", o.optString("file")).put("status", o.optString("status_line"));
    }
}
