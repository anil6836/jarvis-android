package com.anil.jarvis.watch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * W56: a recording on the watch (a meeting, his thoughts; up to 30 minutes), sent to the phone when he stops, where it
 * is written out in Telugu with a short summary. The file stays here until the phone has it.
 */
public class RecService extends Service {
    static final String ACTION_STOP = "com.anil.jarvis.watch.REC_STOP";
    private static final int NOTE = 76;
    static volatile boolean recording;
    static volatile long startedAt;
    static volatile String status = "";
    private MediaRecorder rec;
    private File file;

    static void start(Context c) {
        if (recording) return;
        try { c.startForegroundService(new Intent(c, RecService.class)); } catch (Exception e) { status = "మొదలవలేదు: " + e.getMessage(); }
    }

    static void stop(Context c) { c.startService(new Intent(c, RecService.class).setAction(ACTION_STOP)); }

    private static File pendingFile(Context c) { return new File(c.getFilesDir(), "rec_pending.m4a"); }

    static boolean pending(Context c) { return pendingFile(c).exists() && !recording; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && ACTION_STOP.equals(i.getAction())) { finish(); return START_NOT_STICKY; }
        if (recording) return START_NOT_STICKY;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("jarvis_rec", "Jarvis రికార్డింగ్", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 77, new Intent(this, RecService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 78, new Intent(this, Recorder.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "jarvis_rec").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("🎙️ రికార్డ్ అవుతోంది").setOngoing(true).setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "⏹ ఆపి పంపు", stop).build()).build();
        try { startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE); }
        catch (Exception e) { status = "Android మైక్ ఇవ్వలేదు: Jarvis తెరిచి మళ్లీ నొక్కండి"; stopSelf(); return START_NOT_STICKY; }
        Mic.stop(); // (the wrist-raise listening rests meanwhile)
        try {
            file = new File(getFilesDir(), "rec_now.m4a");
            rec = new MediaRecorder(this);
            rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioSamplingRate(16000);
            rec.setAudioEncodingBitRate(32000);
            rec.setAudioChannels(1);
            rec.setMaxDuration(30 * 60_000);
            rec.setOnInfoListener((r, what, extra) -> { if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finish(); });
            rec.setOutputFile(file.getAbsolutePath());
            rec.prepare();
            rec.start();
            recording = true;
            startedAt = System.currentTimeMillis();
            status = "రికార్డ్ అవుతోంది";
        } catch (Exception e) {
            status = "మైక్ మొదలవలేదు: " + e.getMessage();
            stopSelf();
        }
        Talk.changed();
        return START_NOT_STICKY;
    }

    private void finish() {
        if (!recording) { stopSelf(); return; }
        recording = false;
        long secs = (System.currentTimeMillis() - startedAt) / 1000;
        try { rec.stop(); } catch (Exception ignored) {}
        try { rec.release(); } catch (Exception ignored) {}
        rec = null;
        File p = pendingFile(this);
        p.delete();
        if (file != null && file.renameTo(p)) {
            getSharedPreferences("jarvis_watch_rec", MODE_PRIVATE).edit().putLong("t", startedAt).putLong("secs", secs).apply();
            send(getApplicationContext());
        }
        stopSelf();
        Talk.changed();
    }

    /** The kept recording to the phone (in parts of ~90 KB, in order). */
    static void send(Context app) {
        File f = pendingFile(app);
        if (!f.exists()) return;
        long t = app.getSharedPreferences("jarvis_watch_rec", MODE_PRIVATE).getLong("t", System.currentTimeMillis()),
                secs = app.getSharedPreferences("jarvis_watch_rec", MODE_PRIVATE).getLong("secs", 0);
        String id = "w" + t;
        status = "ఫోన్‌కి పంపుతున్నాను…";
        boolean[] failed = {false};
        Runnable fail = () -> { failed[0] = true; status = "ఫోన్ అందలేదు: రికార్డింగ్ వాచ్‌లోనే ఉంది, \"📤 మళ్లీ పంపు\" నొక్కండి"; Talk.changed(); };
        try {
            Link.send(app, WatchLinkPaths.REC_START, new JSONObject().put("id", id).put("t", t), fail);
            byte[] head = (id + "|").getBytes(StandardCharsets.US_ASCII);
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[90_000];
                int n;
                while ((n = in.read(buf)) > 0) {
                    byte[] part = new byte[head.length + n];
                    System.arraycopy(head, 0, part, 0, head.length);
                    System.arraycopy(buf, 0, part, head.length, n);
                    Link.send(app, WatchLinkPaths.REC_DATA, part, fail);
                }
            }
            Link.send(app, WatchLinkPaths.REC_END, new JSONObject().put("id", id).put("secs", secs).put("bytes", f.length()), fail);
            long parts = f.length() / 90_000 + 3;
            new Thread(() -> {
                boolean gone = Link.flush(Math.max(120_000L, parts * 15_000L));
                if (failed[0]) return;
                // the file stays here until the phone says it has every byte (then it is deleted: see confirmed)
                status = gone ? "ఫోన్‌కి పంపాను: తెలుగులో రాసి సారాంశం ఇక్కడ చూపిస్తుంది" : "ఇంకా పంపుతున్నాను… (రికార్డింగ్ వాచ్‌లోనే ఉంది)";
                Talk.changed();
            }, "rec-sent").start();
        } catch (Exception e) {
            fail.run();
        }
    }

    /** The phone has the whole recording (its size matched): the watch's copy goes. */
    static void confirmed(Context app, String id) {
        long t = app.getSharedPreferences("jarvis_watch_rec", MODE_PRIVATE).getLong("t", -1);
        if (recording || !("w" + t).equals(id)) return;
        File f = pendingFile(app);
        if (f.exists()) f.delete();
        status = "ఫోన్‌కి చేరింది ✓ తెలుగులో రాసి సారాంశం ఇక్కడ చూపిస్తుంది";
        Talk.changed();
    }

    @Override public void onDestroy() {
        if (rec != null) try { rec.release(); } catch (Exception ignored) {}
        recording = false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    /** The phone's paths for a recording (the same as its WatchExtras). */
    static final class WatchLinkPaths {
        static final String REC_START = "/jarvis/rec/start", REC_DATA = "/jarvis/rec/data", REC_END = "/jarvis/rec/end";
    }
}
