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
            return o.put("app", "Jarvis రేడియో").put("title", SoundService.station.isEmpty() ? SoundService.nowPlaying : SoundService.station).put("playing", !(SoundService.radioOn && SoundService.radioPaused)).put("jarvis", true); // (the radio paused: ▶ on the keys)
        }
        MediaController mc = active(c);
        if (mc == null) return o.put("none", true).put("access", NotifyListener.enabled(c));
        o.put("app", appName(c, mc.getPackageName())).put("playing", playing(mc));
        MediaMetadata md = mc.getMetadata();
        if (md != null) o.put("title", nz(md.getString(MediaMetadata.METADATA_KEY_TITLE))).put("artist", nz(md.getString(MediaMetadata.METADATA_KEY_ARTIST)));
        return o;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /**
     * How far the song is, as its music app tells it: {position ms, when that was (elapsed clock), speed × 1000,
     * playing 1/0, length ms (-1 unknown)}; null with no music app (or Jarvis's own radio: no length).
     */
    static long[] progress(Context c) {
        try {
            if (SoundService.radioOn) return null;
            MediaController mc = active(c);
            if (mc == null) return null;
            android.media.session.PlaybackState st = mc.getPlaybackState();
            MediaMetadata md = mc.getMetadata();
            long dur = md == null ? -1 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (st == null) return new long[]{-1, 0, 1000, 0, dur <= 0 ? -1 : dur};
            return new long[]{st.getPosition(), st.getLastPositionUpdateTime(), Math.round(st.getPlaybackSpeed() * 1000), playing(mc) ? 1 : 0, dur <= 0 ? -1 : dur};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The song's picture for the car screen (off the main thread), at most {@code size} pixels, or null: the picture the
     * music app gives with the song, else its player notification's picture, else the picture's link in the song's
     * details (the app's own file, or its https image). Only the playing app's player card is looked at, nothing else.
     */
    static android.graphics.Bitmap art(Context c, int size) {
        MediaController mc = active(c);
        if (mc == null) return null;
        android.graphics.Bitmap b = null;
        MediaMetadata md = mc.getMetadata();
        if (md != null) {
            for (String k : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
                try { b = md.getBitmap(k); } catch (Exception ignored) {}
                if (b != null) break;
            }
        }
        if (b == null) b = NotifyListener.playerPicture(c, mc.getPackageName(), mc.getSessionToken(), md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE));
        if (b == null && md != null) {
            for (String k : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
                String u = md.getString(k);
                if (u != null && !u.isEmpty()) b = pictureAt(c, u, size);
                if (b != null) break;
            }
        }
        return b == null ? null : fit(b, size);
    }

    /** A picture from the song's link: the app's own content / file, or an https image (≤ 3 MB, 8 s). */
    private static android.graphics.Bitmap pictureAt(Context c, String link, int size) {
        try {
            android.net.Uri u = android.net.Uri.parse(link);
            String s = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
            byte[] data;
            if (s.equals("content") || s.equals("android.resource") || s.equals("file")) {
                try (java.io.InputStream in = c.getContentResolver().openInputStream(u)) { data = read(in); }
            } else if (s.equals("https")) {
                java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(link).openConnection();
                h.setConnectTimeout(8000);
                h.setReadTimeout(8000);
                try (java.io.InputStream in = h.getInputStream()) { data = read(in); } finally { h.disconnect(); }
            } else return null;
            if (data == null) return null;
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int big = Math.max(o.outWidth, o.outHeight), sample = 1;
            while (big / (sample * 2) >= size) sample *= 2;
            o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable e) {
            return null; // (not readable for us: the note stays)
        }
    }

    private static byte[] read(java.io.InputStream in) throws java.io.IOException {
        if (in == null) return null;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > 3_000_000) return null; // (not a song's picture)
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** At most size × size (the screen shows it small; a phone-sized picture would only cost memory). */
    private static android.graphics.Bitmap fit(android.graphics.Bitmap b, int size) {
        int w = b.getWidth(), h = b.getHeight(), big = Math.max(w, h);
        if (w <= 0 || h <= 0) return null;
        if (big <= size) return b;
        float k = size / (float) big;
        return android.graphics.Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
    }

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

    // ---------------------------------------------------------------- ⑪'s player: 🔀 🔁 ⏪10 10⏩ the place in the song, ❤️, 📃 up next
    // (only where the music app allows them: the app says so in its own player state; else the key greys out and says so)

    static final int C_SHUFFLE = 1, C_REPEAT = 2, C_LIKE = 4, C_SEEK = 8, C_QUEUE = 16;
    // the player state's action bits (the framework's, and the compat library's that ride in the same number)
    static final long A_SET_RATING = 1L << 7, A_SEEK_TO = 1L << 8, A_SKIP_TO_QUEUE_ITEM = 1L << 12, A_SET_REPEAT = 1L << 18, A_SET_SHUFFLE = 1L << 21;
    private static final String SET_SHUFFLE = "android.support.v4.media.session.action.SET_SHUFFLE_MODE",
            ARG_SHUFFLE = "android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE",
            SET_REPEAT = "android.support.v4.media.session.action.SET_REPEAT_MODE",
            ARG_REPEAT = "android.support.v4.media.session.action.ARGUMENT_REPEAT_MODE";
    /** The compat library's repeat modes: none, one, all. */
    static final int R_NONE = 0, R_ONE = 1, R_ALL = 2;

    /** The app's own button for this ("shuffle", "repeat", "like") among its extra buttons ("id|name" each); -1: none. Pure. */
    static int customFor(String what, String[] customs) {
        if (customs == null) return -1;
        for (int i = 0; i < customs.length; i++) {
            String n = customs[i] == null ? "" : customs[i].toLowerCase(java.util.Locale.ROOT);
            switch (what) {
                case "shuffle": if (n.contains("shuffle") || n.contains("షఫుల్")) return i; break;
                case "repeat": if (n.contains("repeat") || n.contains("loop")) return i; break;
                case "like":
                    if (n.contains("dislike") || n.contains("unlike") || n.contains("down") || n.contains("remove")) break;
                    if (n.contains("like") || n.contains("thumb") || n.contains("heart") || n.contains("favo") || n.contains("love") || n.contains("library")) return i;
                    break;
                default: break;
            }
        }
        return -1;
    }

    /** What the app allows (C_… bits), from its player state's actions, its extra buttons, its rating kind and its queue. Pure. */
    static int caps(long actions, String[] customs, int ratingType, boolean queue) {
        int c = 0;
        if ((actions & A_SET_SHUFFLE) != 0 || customFor("shuffle", customs) >= 0) c |= C_SHUFFLE;
        if ((actions & A_SET_REPEAT) != 0 || customFor("repeat", customs) >= 0) c |= C_REPEAT;
        if ((actions & A_SET_RATING) != 0 && (ratingType == 1 /* heart */ || ratingType == 2 /* thumbs */) || customFor("like", customs) >= 0) c |= C_LIKE;
        if ((actions & A_SEEK_TO) != 0) c |= C_SEEK;
        if (queue) c |= C_QUEUE;
        return c;
    }

    /** Where ±delta lands in a song of this length (never before 0, never past its end; length unknown: only not before 0). Pure. */
    static long seekTarget(long pos, long dur, long delta) {
        long t = Math.max(0, pos + delta);
        if (dur > 0) t = Math.min(t, Math.max(0, dur - 1000));
        return t;
    }

    /** 🔁 off → all → one → off. Pure. */
    static int nextRepeat(int now) { return now == R_NONE ? R_ALL : now == R_ALL ? R_ONE : R_NONE; }

    /** What we last asked each app for (the app keeps its own: we show what we asked, said honestly as such). */
    private static final java.util.Map<String, int[]> asked = new java.util.HashMap<>();

    private static String[] customs(PlaybackState st) {
        if (st == null || st.getCustomActions() == null) return new String[0];
        List<PlaybackState.CustomAction> ca = st.getCustomActions();
        String[] out = new String[ca.size()];
        for (int i = 0; i < out.length; i++) out[i] = ca.get(i).getAction() + "|" + ca.get(i).getName();
        return out;
    }

    /**
     * ⑪'s player: {caps (C_… bits), shuffle 0/1, repeat (R_…), liked, queue (titles, up to 30), app (package)} for the
     * playing app; null with none (or Jarvis's radio).
     */
    static JSONObject extras(Context c) {
        try {
            if (SoundService.radioOn) return null;
            MediaController mc = active(c);
            if (mc == null) return null;
            PlaybackState st = mc.getPlaybackState();
            List<android.media.session.MediaSession.QueueItem> q = null;
            try { q = mc.getQueue(); } catch (Exception ignored) {}
            boolean hasQ = q != null && !q.isEmpty() && st != null && (st.getActions() & A_SKIP_TO_QUEUE_ITEM) != 0;
            int cp = caps(st == null ? 0 : st.getActions(), customs(st), mc.getRatingType(), hasQ);
            int[] a = asked.get(mc.getPackageName());
            boolean liked = false;
            MediaMetadata md = mc.getMetadata();
            if (md != null) {
                try {
                    android.media.Rating r = md.getRating(MediaMetadata.METADATA_KEY_USER_RATING);
                    if (r != null && r.isRated()) liked = r.getRatingStyle() == android.media.Rating.RATING_HEART ? r.hasHeart() : r.getRatingStyle() == android.media.Rating.RATING_THUMB_UP_DOWN && r.isThumbUp();
                } catch (Exception ignored) {}
            }
            JSONArray qa = new JSONArray();
            if (hasQ) {
                long cur = st.getActiveQueueItemId();
                for (int i = 0; i < q.size() && qa.length() < 30; i++) {
                    android.media.session.MediaSession.QueueItem it = q.get(i);
                    CharSequence t = it.getDescription().getTitle(), sub = it.getDescription().getSubtitle();
                    qa.put(new JSONObject().put("id", it.getQueueId()).put("title", t == null ? "" : t.toString()).put("sub", sub == null ? "" : sub.toString()).put("now", it.getQueueId() == cur));
                }
            }
            return new JSONObject().put("caps", cp).put("shuffle", a == null ? -1 : a[0]).put("repeat", a == null ? -1 : a[1]).put("liked", liked)
                    .put("queue", qa).put("app", mc.getPackageName());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A key of ⑪'s player: "shuffle", "repeat", "like", "back10", "fwd10", "seek:<ms>", "queue:<id>". A short word back
     * (honest when the app doesn't allow it).
     */
    static String extra(Context c, String what) {
        MediaController mc = active(c);
        if (SoundService.radioOn || mc == null) return "ఇప్పుడు మ్యూజిక్ యాప్ పాట లేదు";
        PlaybackState st = mc.getPlaybackState();
        long acts = st == null ? 0 : st.getActions();
        String[] cs = customs(st);
        MediaController.TransportControls tc = mc.getTransportControls();
        String app = appName(c, mc.getPackageName());
        int[] a = asked.get(mc.getPackageName());
        if (a == null) asked.put(mc.getPackageName(), a = new int[]{0, R_NONE});
        try {
            if (what.equals("shuffle") || what.equals("repeat")) {
                int k = customFor(what, cs);
                boolean shuffle = what.equals("shuffle");
                if (k >= 0) { // (the app's own button: it knows its state, and shows it)
                    tc.sendCustomAction(st.getCustomActions().get(k), null);
                    if (shuffle) a[0] = 1 - a[0]; else a[1] = nextRepeat(a[1]);
                    return (shuffle ? "🔀 " : "🔁 ") + app + " కి చెప్పాను";
                }
                if ((acts & (shuffle ? A_SET_SHUFFLE : A_SET_REPEAT)) == 0) return app + " లో " + (shuffle ? "షఫుల్" : "రిపీట్") + " ఇక్కడి నుంచి మార్చడానికి ఆ యాప్ ఒప్పుకోదు: ఆ యాప్‌లోనే మార్చండి";
                android.os.Bundle b = new android.os.Bundle();
                if (shuffle) { a[0] = 1 - a[0]; b.putInt(ARG_SHUFFLE, a[0]); tc.sendCustomAction(SET_SHUFFLE, b); return a[0] == 1 ? "🔀 షఫుల్ ఆన్" : "🔀 షఫుల్ ఆఫ్"; }
                a[1] = nextRepeat(a[1]);
                b.putInt(ARG_REPEAT, a[1]);
                tc.sendCustomAction(SET_REPEAT, b);
                return a[1] == R_ONE ? "🔂 ఈ పాటే మళ్ళీ మళ్ళీ" : a[1] == R_ALL ? "🔁 అన్నీ మళ్ళీ" : "🔁 రిపీట్ ఆఫ్";
            }
            if (what.equals("like")) {
                int k = customFor("like", cs);
                if (k >= 0) { tc.sendCustomAction(st.getCustomActions().get(k), null); return "❤️ " + app + " కి చెప్పాను"; }
                int rt = mc.getRatingType();
                if ((acts & A_SET_RATING) == 0 || (rt != android.media.Rating.RATING_HEART && rt != android.media.Rating.RATING_THUMB_UP_DOWN))
                    return app + " లో ❤️ ఇక్కడి నుంచి కుదరదు: ఆ యాప్‌లోనే నొక్కండి";
                tc.setRating(rt == android.media.Rating.RATING_HEART ? android.media.Rating.newHeartRating(true) : android.media.Rating.newThumbRating(true));
                return "❤️ నచ్చింది అని " + app + " కి చెప్పాను";
            }
            if (what.equals("back10") || what.equals("fwd10") || what.startsWith("seek:")) {
                if ((acts & A_SEEK_TO) == 0) return app + " పాటలో ముందుకు / వెనక్కి వెళ్లనివ్వదు";
                long[] pr = progress(c);
                long pos = pr == null || pr[0] < 0 ? 0 : pr[0] + (pr[3] == 1 ? Math.round((SystemClock.elapsedRealtime() - pr[1]) * (pr[2] / 1000.0)) : 0);
                long dur = pr == null ? -1 : pr[4];
                long to = what.startsWith("seek:") ? seekTarget(Long.parseLong(what.substring(5)), dur, 0) : seekTarget(pos, dur, what.equals("back10") ? -10_000 : 10_000);
                tc.seekTo(to);
                return what.equals("back10") ? "⏪ 10 సె" : what.equals("fwd10") ? "10 సె ⏩" : "";
            }
            if (what.startsWith("queue:")) {
                if ((acts & A_SKIP_TO_QUEUE_ITEM) == 0) return app + " తర్వాతి పాటల జాబితా నుంచి ఎంచుకోనివ్వదు";
                tc.skipToQueueItem(Long.parseLong(what.substring(6)));
                return "▶";
            }
        } catch (Exception e) {
            return "అది ఇప్పుడు కాలేదు";
        }
        return "";
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
