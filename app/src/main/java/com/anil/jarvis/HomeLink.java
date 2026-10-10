package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * W41 / W64 / W65: his main phone and the old phone at home (the guard) talk through his own Telegram bot, without any
 * other service: each one writes into his chat with the bot and pins it quietly, and the other reads the latest pinned
 * message. Both phones need internet and the same bot token (it stays on the two phones).
 *   main -> home: "📢 ఇంటికి: …" is said aloud at home; "📷" asks for a picture now; "🛡️ ఆపు / మొదలు" pauses or resumes
 *   the camera alerts; "🎤 ఇంటికి" a voice clip played at home.
 *   home -> main: every guard picture is pinned (his main phone shows the latest one, also on the watch); "🎤 ఇంటి నుంచి"
 *   a voice clip recorded at home (the guard notification's button).
 * Only messages the bot itself pinned are acted on.
 */
final class HomeLink {
    private HomeLink() {}

    static final String SAY = "📢 ఇంటికి: ", PHOTO = "📷 ఇంటి ఫోటో కావాలి", PAUSE = "🛡️ కాపలా ఆపు", RESUME = "🛡️ కాపలా మొదలుపెట్టు",
            VOICE_HOME = "🎤 ఇంటికి", VOICE_FROM = "🎤 ఇంటి నుంచి";
    static final String ACTION_PLAY = "com.anil.jarvis.HOME_PLAY";
    private static final int NOTE = 268;

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_homelink", Context.MODE_PRIVATE); }

    /** This is his main phone, joined to the home phone's bot. */
    static boolean linked(Context c) { return !Guard.token(c).isEmpty() && !Guard.chat(c).isEmpty() && !Guard.running(c) && !new Prefs(c).homeMode(); }

    // ================================================================ at home (the guard phone), every ~20 seconds

    /** The camera alerts are paused from his main phone (the camera still answers "📷"). */
    static volatile boolean paused;

    /** At home: the pinned message is a word from his main phone not yet acted on (it must not be covered by a picture). */
    static boolean commandWaiting(Context c) {
        JSONObject m = Guard.pinned(c);
        if (m == null || m.optLong("message_id") <= sp(c).getLong("seen", 0)) return false;
        String text = m.optString("text"), cap = m.optString("caption");
        return text.startsWith(SAY) || text.startsWith(PHOTO) || text.startsWith(PAUSE) || text.startsWith(RESUME) || cap.startsWith(VOICE_HOME);
    }

    static void homePoll(Context c) {
        JSONObject m = Guard.pinned(c);
        if (m == null) return;
        long id = m.optLong("message_id"), seen = sp(c).getLong("seen", 0);
        if (seen == 0) { sp(c).edit().putLong("seen", id).apply(); return; } // (first look: nothing old is replayed)
        if (id <= seen) return;
        sp(c).edit().putLong("seen", id).apply();
        JSONObject from = m.optJSONObject("from");
        if (from == null || !from.optBoolean("is_bot")) return;
        if (System.currentTimeMillis() / 1000 - m.optLong("date") > 10 * 60) return; // (an old one: the phone was off)
        String text = m.optString("text"), cap = m.optString("caption");
        String time = new java.text.SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new java.util.Date());
        if (text.startsWith(SAY)) {
            String say = text.substring(SAY.length()).trim();
            if (!say.isEmpty()) {
                String line = new Prefs(c).name() + " చెప్తున్నారు: " + say;
                if (HomeCare.on(c)) HomeCare.say(c, line, "happy"); // (on the home screen, with the face)
                else Announcer.say(c, line);
                Guard.sendQuiet(c, "✓ ఇంట్లో వినిపించాను (" + time + ")");
            }
        } else if (text.startsWith(PHOTO)) {
            byte[] j = GuardService.snap(6000);
            if (j == null || !Guard.sendPhoto(c, j, "🏠 ఇప్పుడు ఇంట్లో · " + time)) Guard.sendQuiet(c, "⚠️ ఇంటి కెమెరా ఇప్పుడు ఫోటో ఇవ్వలేదు");
        } else if (text.startsWith(PAUSE)) {
            paused = true;
            Guard.sendQuiet(c, "🛡️ ఇంటి కాపలా అలర్ట్స్ ఆగాయి (" + time + "). ఫోటో అడిగితే ఇస్తాను.");
        } else if (text.startsWith(RESUME)) {
            paused = false;
            Guard.sendQuiet(c, "🛡️ ఇంటి కాపలా మళ్లీ మొదలైంది (" + time + ")");
        } else if (cap.startsWith(VOICE_HOME)) {
            JSONObject a = m.has("audio") ? m.optJSONObject("audio") : m.optJSONObject("voice");
            byte[] b = a == null ? null : Guard.file(c, a.optString("file_id"));
            if (b != null) play(c, b, "home_in.m4a");
        }
    }

    // ================================================================ his main phone

    /** "ఇంట్లో ఎలా ఉంది?": asks the home phone for a picture now and waits for it (up to a minute; background thread). */
    static String look(Context c) {
        if (!linked(c)) return notLinked();
        if (!Net.online(c)) return "ఇంటి ఫోటోకి నెట్ కావాలి.";
        long asked = Guard.sendQuiet(c, PHOTO);
        if (asked < 0 || !Guard.pin(c, asked)) return "ఇంటి ఫోన్‌కి అడగలేకపోయాను (Telegram చేరలేదు).";
        long until = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < until) {
            try { Thread.sleep(4000); } catch (InterruptedException e) { break; }
            JSONObject m = Guard.pinned(c);
            if (m != null && m.optLong("message_id") > asked && m.has("photo")) {
                sp(c).edit().putLong("fetched", m.optLong("message_id")).apply(); // (not shown twice by the Telegram notification)
                String r = showPhoto(c, m);
                return r != null ? r : "ఫోటో వచ్చింది కానీ తెరవలేకపోయాను; Telegram లో చూడండి.";
            }
        }
        JSONObject last = Guard.pinned(c);
        String shown = last != null && last.has("photo") ? showPhoto(c, last) : null;
        return "ఇంటి ఫోన్ నిమిషంలో జవాబు ఇవ్వలేదు (దానికి నెట్ / కాపలా మోడ్ ఆన్‌లో ఉందా?)." + (shown != null ? " చివరి ఫోటో: " + shown : "");
    }

    /** "ఇంటికి చెప్పు: …" (only after his yes): said aloud at home. */
    static String say(Context c, String text) {
        if (!linked(c)) return notLinked();
        if (!Net.online(c)) return "ఇంటికి చెప్పడానికి నెట్ కావాలి.";
        long id = Guard.sendQuiet(c, SAY + text.trim());
        if (id < 0 || !Guard.pin(c, id)) return "పంపలేకపోయాను (Telegram చేరలేదు).";
        return "పంపాను: సుమారు 20 సెకన్లలో ఇంట్లో ఫోన్ \"" + text.trim() + "\" అని వినిపిస్తుంది (దానికి నెట్ ఉంటే).";
    }

    /** W64: the camera alerts at home paused / resumed. */
    static String guard(Context c, boolean on) {
        if (!linked(c)) return notLinked();
        long id = Guard.sendQuiet(c, on ? RESUME : PAUSE);
        if (id < 0 || !Guard.pin(c, id)) return "ఇంటి ఫోన్‌కి చెప్పలేకపోయాను (Telegram చేరలేదు).";
        return on ? "ఇంటి కాపలా మళ్లీ మొదలుపెట్టమని చెప్పాను." : "ఇంటి కాపలా అలర్ట్స్ ఆపమని చెప్పాను (మీరు ఇంట్లో ఉన్నంత వరకు).";
    }

    private static String notLinked() {
        return "ఇంటి ఫోన్‌తో కలపలేదు: ఈ ఫోన్ సెట్టింగ్స్ → కాపలా మోడ్ లో ఇంటి ఫోన్‌లో ఉన్న అదే bot token పెట్టి \"Telegram చాట్ కనుక్కో\" నొక్కండి (కాపలా ఇక్కడ ఆన్ చేయకండి).";
    }

    /** A Telegram notification of his bot arrived on his main phone: the latest pinned picture / voice from home (background). */
    static void onBotNote(Context c) {
        if (!linked(c) || !Net.online(c)) return;
        long now = System.currentTimeMillis();
        if (now - sp(c).getLong("note_at", 0) < 5000) return;
        sp(c).edit().putLong("note_at", now).apply();
        new Thread(() -> {
            try { Thread.sleep(2500); } catch (InterruptedException ignored) {} // (the pin comes right after the message)
            JSONObject m = Guard.pinned(c);
            if (m == null) return;
            long id = m.optLong("message_id");
            if (id <= sp(c).getLong("fetched", 0)) return;
            sp(c).edit().putLong("fetched", id).apply();
            if (m.has("photo")) showPhoto(c, m);
            else if (m.optString("caption").startsWith(VOICE_FROM)) voiceFromHome(c, m);
        }, "home-link").start();
    }

    /** The pinned picture: saved (Downloads/Jarvis/home), on his watch, and opened if he is looking at the phone. */
    private static String showPhoto(Context c, JSONObject m) {
        try {
            JSONArray sizes = m.getJSONArray("photo");
            JSONObject big = sizes.getJSONObject(sizes.length() - 1);
            byte[] b = Guard.file(c, big.optString("file_id"));
            if (b == null) return null;
            String cap = m.optString("caption");
            Coder.Made made = Coder.save(c, "Jarvis/home", "home_" + System.currentTimeMillis() + ".jpg", "image/jpeg", b);
            WatchPhoto.send(c, b, cap.isEmpty() ? "🏠 ఇంటి ఫోటో" : cap);
            if (made.uri != null && !WatchHub.phoneIdle(c)) Cards.view(c, made);
            return (cap.isEmpty() ? "ఇంటి ఫోటో" : cap) + " (" + made.where + " లో ఉంది" + (WatchHub.known(c) ? ", వాచ్‌లోనూ" : "") + ").";
        } catch (Exception e) {
            return null;
        }
    }

    private static void voiceFromHome(Context c, JSONObject m) {
        JSONObject a = m.has("audio") ? m.optJSONObject("audio") : m.optJSONObject("voice");
        byte[] b = a == null ? null : Guard.file(c, a.optString("file_id"));
        if (b == null) return;
        try (FileOutputStream o = new FileOutputStream(new File(c.getCacheDir(), "home_voice.m4a"))) { o.write(b); } catch (Exception e) { return; }
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("jarvis_home", "ఇల్లు", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent play = PendingIntent.getBroadcast(c, NOTE, new Intent(c, AlarmReceiver.class).setAction(ACTION_PLAY), PendingIntent.FLAG_IMMUTABLE);
            nm.notify(NOTE, new Notification.Builder(c, "jarvis_home").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("🏠 ఇంటి నుంచి వాయిస్ మెసేజ్").setContentText("వినడానికి ▶ నొక్కండి (Telegram లోనూ ఉంది)").setAutoCancel(true)
                    .setTimeoutAfter(6 * 3600_000L).addAction(new Notification.Action.Builder(null, "▶ వినిపించు", play).build()).build());
        } catch (Exception ignored) {}
    }

    /** "▶ వినిపించు" on the voice from home (phone / his earphones). */
    static void playFromHome(Context c) {
        File f = new File(c.getCacheDir(), "home_voice.m4a");
        if (f.exists()) play(c, null, f.getName());
    }

    private static MediaPlayer player;

    private static synchronized void play(Context c, byte[] data, String name) {
        try {
            File f = new File(c.getCacheDir(), name);
            if (data != null) try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
            if (player != null) try { player.release(); } catch (Exception ignored) {}
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.setOnCompletionListener(MediaPlayer::release);
            player.prepare();
            player.start();
        } catch (Exception ignored) {}
    }

    /** At home: a voice clip from the guard phone's notification button, to his main phone (background thread). */
    static boolean sendVoiceFromHome(Context c, byte[] m4a) {
        long id = Guard.sendFile(c, "sendAudio", "audio", "home.m4a", "audio/mp4", m4a, VOICE_FROM + " (" +
                new java.text.SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new java.util.Date()) + ")", false);
        if (id > 0) Guard.pin(c, id);
        return id > 0;
    }
}
