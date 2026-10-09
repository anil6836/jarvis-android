package com.anil.jarvis;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Phase 5 music: the watch as the remote for what plays on the phone (W50: YouTube Music, Amazon Music, Jarvis's radio;
 * which song it is), Jarvis's Telugu radio played by the watch itself (W36: the phone finds the station's link, the
 * watch plays it to its own earbuds), and a sleep timer that stops the songs (O46), also without internet.
 */
final class Music {
    private Music() {}

    static final String ACTION_SLEEP = "com.anil.jarvis.MUSIC_SLEEP";
    /** phone -> watch: {play, urls[]} the watch plays a station; {stop: true} it stops. */
    static final String P_RADIO = "/jarvis/radio";
    private static final int REQ_SLEEP = 271;

    /** The song / video session playing on the phone (needs notification access), else the latest one, else null. */
    static MediaController active(Context c) {
        if (!NotifyListener.enabled(c)) return null;
        try {
            MediaSessionManager msm = c.getSystemService(MediaSessionManager.class);
            List<MediaController> list = msm.getActiveSessions(new ComponentName(c, NotifyListener.class));
            MediaController fallback = null;
            for (MediaController mc : list) {
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) return mc;
                if (fallback == null) fallback = mc;
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean playing(MediaController mc) {
        PlaybackState st = mc == null ? null : mc.getPlaybackState();
        return st != null && (st.getState() == PlaybackState.STATE_PLAYING || st.getState() == PlaybackState.STATE_BUFFERING);
    }

    private static String appName(Context c, String pkg) {
        try { return c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception e) { return pkg; }
    }

    /** The watch's music screen: what plays on the phone now. */
    static JSONObject panel(Context c) throws Exception {
        JSONObject o = new JSONObject().put("kind", "music");
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am != null) o.put("vol", am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)));
        if (SoundService.radioOn || !SoundService.nowPlaying.isEmpty()) {
            return o.put("app", "Jarvis రేడియో").put("title", SoundService.station.isEmpty() ? SoundService.nowPlaying : SoundService.station).put("playing", true).put("jarvis", true);
        }
        MediaController mc = active(c);
        if (mc == null) return o.put("none", true).put("access", NotifyListener.enabled(c));
        o.put("app", appName(c, mc.getPackageName())).put("playing", playing(mc));
        MediaMetadata md = mc.getMetadata();
        if (md != null) o.put("title", nz(md.getString(MediaMetadata.METADATA_KEY_TITLE))).put("artist", nz(md.getString(MediaMetadata.METADATA_KEY_ARTIST)));
        return o;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** "ఈ పాట ఏది?" (also offline). */
    static String nowLine(Context c) {
        try {
            JSONObject o = panel(c);
            if (o.optBoolean("none")) return o.optBoolean("access") ? "ఇప్పుడు ఫోన్‌లో ఏ పాటా ప్లే అవడం లేదు." : "ఏ పాటో చూడటానికి Jarvis కి నోటిఫికేషన్ యాక్సెస్ కావాలి.";
            String t = o.optString("title"), ar = o.optString("artist");
            if (t.isEmpty()) return o.optString("app") + " లో ప్లే అవుతోంది, పాట పేరు చూపించడం లేదు.";
            return "ఇది \"" + t + "\"" + (ar.isEmpty() ? "" : ", " + ar) + " (" + o.optString("app") + ").";
        } catch (Exception e) {
            return "ఏ పాటో చూడలేకపోయాను.";
        }
    }

    private static void key(AudioManager am, int code) {
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    /** A button on the watch: play / pause / toggle / next / previous / volume_up / volume_down. A short word back. */
    static String control(Context c, String action) {
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am == null) return "సౌండ్ సర్వీస్ లేదు.";
        String a = action == null ? "" : action;
        if (SoundService.radioOn) {
            String k = a.equals("pause") ? SoundService.ACTION_PAUSE : a.equals("toggle") ? SoundService.ACTION_TOGGLE
                    : a.equals("play") ? SoundService.ACTION_PLAY : a.equals("next") ? SoundService.ACTION_NEXT : a.equals("previous") ? SoundService.ACTION_PREV : null;
            if (k != null) { SoundService.control(c, k); return "📻 " + SoundService.station; }
        } else if (!SoundService.nowPlaying.isEmpty() && (a.equals("pause") || a.equals("toggle"))) {
            SoundService.stop(c);
            return "ఆపాను.";
        }
        MediaController mc = active(c);
        MediaController.TransportControls tc = mc == null ? null : mc.getTransportControls();
        MicQuiet.giveBack();
        switch (a) {
            case "play": if (tc != null) tc.play(); else key(am, KeyEvent.KEYCODE_MEDIA_PLAY); return "▶";
            case "pause": if (tc != null) tc.pause(); else key(am, KeyEvent.KEYCODE_MEDIA_PAUSE); return "⏸";
            case "toggle":
                if (tc != null) { if (playing(mc)) tc.pause(); else tc.play(); } else key(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                return playing(mc) ? "⏸" : "▶";
            case "next": if (tc != null) tc.skipToNext(); else key(am, KeyEvent.KEYCODE_MEDIA_NEXT); return "⏭";
            case "previous": if (tc != null) tc.skipToPrevious(); else key(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS); return "⏮";
            case "volume_up": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0); break;
            case "volume_down": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0); break;
            default: return "";
        }
        return "🔊 " + am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) + "%";
    }

    // ---------------------------------------------------------------- O46: the sleep timer

    private static PendingIntent sleepPi(Context c) {
        return PendingIntent.getBroadcast(c, REQ_SLEEP, new Intent(c, AlarmReceiver.class).setAction(ACTION_SLEEP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Songs stop after this many minutes (0 = the timer is cancelled). */
    static String sleepAfter(Context c, int minutes) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return "టైమర్ పెట్టలేకపోయాను.";
        if (minutes <= 0) { am.cancel(sleepPi(c)); return "సరే, పాటల టైమర్ తీసేశాను."; }
        int m = Math.min(minutes, 6 * 60);
        long at = System.currentTimeMillis() + m * 60_000L;
        try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, sleepPi(c)); }
        catch (Exception e) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, sleepPi(c)); }
        return "సరే, " + m + " నిమిషాల తర్వాత పాటలు ఆపుతాను" + (WatchHub.known(c) ? " (వాచ్‌లో రేడియో కూడా)." : ".");
    }

    /** The timer's time: every song stops (the phone's apps, Jarvis's radio and sounds, the radio app, the watch's radio). */
    static void sleepNow(Context c) {
        try { if (SoundService.radioOn || !SoundService.nowPlaying.isEmpty()) SoundService.stop(c); } catch (Exception ignored) {}
        try { AppRadio.pauseNow(c); } catch (Exception ignored) {}
        try {
            MediaController mc = active(c);
            if (mc != null && playing(mc)) mc.getTransportControls().pause();
            else { AudioManager am = c.getSystemService(AudioManager.class); if (am != null && am.isMusicActive()) key(am, KeyEvent.KEYCODE_MEDIA_PAUSE); }
        } catch (Exception ignored) {}
        try { WatchHub.send(c, P_RADIO, new JSONObject().put("stop", true).put("sleep", true)); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- W36: the radio on the watch

    /** His stations for the watch's radio screen (names, favourites first). */
    static JSONObject radioPanel(Context c) throws Exception {
        JSONArray all = Radio.list(c), out = new JSONArray();
        for (int fav = 1; fav >= 0; fav--) {
            for (int i = 0; i < all.length(); i++) {
                JSONObject st = all.optJSONObject(i);
                if (st == null || st.optBoolean("hidden")) continue;
                boolean f = Radio.isFav(c, st.optString("name"));
                if (f != (fav == 1)) continue;
                out.put(new JSONObject().put("name", st.optString("name")).put("fav", f));
            }
        }
        return new JSONObject().put("kind", "radio").put("stations", out).put("online", Net.online(c));
    }

    /** Plays the station on the watch (background thread): its links found here, the watch streams it. "" or a problem. */
    static String radioOnWatch(Context c, String want) {
        if (!WatchHub.known(c) || !WatchHub.watchHere(c)) return "వాచ్ ఫోన్ దగ్గర లేదు.";
        JSONObject st = want == null || want.trim().isEmpty() ? Radio.find(c, Radio.last(c)) : Radio.find(c, want);
        if (st == null) return "ఆ స్టేషన్ మీ లిస్ట్‌లో లేదు.";
        String[] urls = Radio.urls(c, st);
        if (urls.length == 0) return st.optString("name") + " కి లింక్ దొరకలేదు (అది Telugu Radios యాప్‌లోనే వస్తుంది).";
        try {
            JSONArray u = new JSONArray();
            for (String x : urls) u.put(x);
            WatchHub.send(c, P_RADIO, new JSONObject().put("play", st.optString("name")).put("urls", u));
            Radio.setLast(c, st.optString("name"));
            if (SoundService.radioOn) SoundService.stop(c); // (one radio at a time)
            return "";
        } catch (Exception e) {
            return "వాచ్‌కి పంపలేకపోయాను: " + e.getMessage();
        }
    }

    /** Plays the station on the phone instead (background thread). */
    static String radioOnPhone(Context c, String want) {
        JSONObject st = Radio.find(c, want);
        if (st == null) return "ఆ స్టేషన్ మీ లిస్ట్‌లో లేదు.";
        String[] urls = Radio.urls(c, st);
        if (urls.length == 0) return st.optString("name") + " కి లింక్ దొరకలేదు.";
        Radio.play(c, st, urls, 0);
        try { WatchHub.send(c, P_RADIO, new JSONObject().put("stop", true)); } catch (Exception ignored) {}
        return "📱 " + st.optString("name");
    }

    /** O46 words: "30 నిమిషాల తర్వాత పాట ఆపు" -> 30; "పాటల టైమర్ తీసేయ్" -> 0; -1 when it isn't that (pure: tested on a desk). */
    static int sleepWords(String bare) {
        String t = bare == null ? "" : bare.trim().toLowerCase(java.util.Locale.ROOT);
        boolean song = t.matches("(?s).*(పాట|పాటలు|మ్యూజిక్|music|song|రేడియో|radio|స్లీప్ టైమర్|sleep timer).*");
        if (!song) return -1;
        if (t.matches("(?s).*(టైమర్)\\s*(తీసేయ్|తీసేయి|ఆపు|క్యాన్సిల్|వద్దు|cancel).*")) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3})\\s*(నిమిషాల|నిమిషాలు|నిమిషం|ని|min|minutes|గంట|గంటల|గంటలు|hour)").matcher(t);
        if (!m.find()) {
            if (t.matches("(?s).*(అరగంట|అర గంట|half an hour).*") && t.matches("(?s).*(ఆపు|ఆపేయ్|ఆపేయి|stop|టైమర్|timer).*")) return 30;
            if (t.matches("(?s).*(గంట తర్వాత|గంటలో|an hour).*") && t.matches("(?s).*(ఆపు|ఆపేయ్|stop|టైమర్).*")) return 60;
            return -1;
        }
        int n = Integer.parseInt(m.group(1));
        boolean hours = m.group(2).startsWith("గంట") || m.group(2).startsWith("hour");
        if (!t.matches("(?s).*(తర్వాత|తరువాత|లో|after|in|టైమర్|timer).*") || !t.matches("(?s).*(ఆపు|ఆపేయ్|ఆపేయి|ఆపండి|off|stop|టైమర్|timer).*")) return -1;
        return hours ? n * 60 : n;
    }
}
