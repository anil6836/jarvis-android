package com.anil.jarvis;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stations Jarvis has no link for are played by Anil's "Telugu Radios" app (teluguradios.com), through the standard
 * Android media interface that app offers to cars and assistants (its MediaBrowserService, the one Android Auto uses):
 * Jarvis looks the station up by name in the app's folders and asks the app to play it. The app plays it itself;
 * no links or keys are taken out of it.
 */
final class AppRadio {
    private AppRadio() {}

    static final String PKG = "com.tamilradiosnet.telugu";
    private static final ComponentName SERVICE = new ComponentName(PKG, PKG + ".service.TRAutoMediaBrowserService");
    /** The app's folders: favourites, recently played, for you, then Christian, Music, Live, Comedy, Top, AIR Telugu. */
    private static final String[] FOLDERS = {"favorites", "recently_played", "for_you",
            "category_93", "category_91", "category_90", "category_88", "category_95", "category_89"};

    static final String OK = "ok", NOT_INSTALLED = "not_installed", NO_CONNECT = "no_connect", NOT_FOUND = "not_found",
            NOT_STARTED = "not_started", CANCELLED = "cancelled";
    static final String ACTION_PAUSE = "com.anil.jarvis.APP_RADIO_PAUSE";

    interface Done { void result(String status, String title); }

    private static volatile long startedAt;
    private static final java.util.concurrent.atomic.AtomicInteger GEN = new java.util.concurrent.atomic.AtomicInteger();

    static boolean installed(Context c) {
        try { c.getPackageManager().getPackageInfo(PKG, 0); return true; } catch (Exception e) { return false; }
    }

    /** The app is playing something Jarvis asked it for (so "ఆపు" pauses it). */
    static boolean startedRecently() { return startedAt > 0 && android.os.SystemClock.elapsedRealtime() - startedAt < 12 * 3600000L; }

    static void stopped() { startedAt = 0; }

    /** Another sound was asked for: a request still on its way must not start the app over it. */
    static void cancelPending() { GEN.incrementAndGet(); }

    /** Pauses what the app is playing (the phone's media pause key goes to the app playing now). */
    static void pauseNow(Context c) {
        try {
            android.media.AudioManager am = c.getSystemService(android.media.AudioManager.class);
            if (am == null) return;
            am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE));
            am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE));
        } catch (Exception ignored) {}
        startedAt = 0;
    }

    /** "30 నిమిషాలు": the app has no timer of ours, so an alarm pauses it then. */
    static void pauseAfter(Context c, int minutes) {
        try {
            android.app.AlarmManager am = c.getSystemService(android.app.AlarmManager.class);
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(c, 86,
                    new android.content.Intent(c, AlarmReceiver.class).setAction(ACTION_PAUSE),
                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            if (minutes <= 0) { am.cancel(pi); return; }
            am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + minutes * 60000L, pi);
        } catch (Exception ignored) {}
    }

    /** Asks the app to play the station; the answer comes on the main thread within about 25 seconds. */
    static void play(Context ctx, String station, Done done) {
        final Context c = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final int my = GEN.incrementAndGet();
        main.post(() -> {
            if (!installed(c)) { done.result(NOT_INSTALLED, ""); return; }
            final boolean[] over = {false};
            final MediaBrowser[] mb = new MediaBrowser[1];
            final Runnable[] finish = new Runnable[1];
            final String[] status = {NO_CONNECT}, title = {""};
            finish[0] = () -> {
                if (over[0]) return;
                over[0] = true;
                main.removeCallbacksAndMessages(finish); // the time limit below
                if (OK.equals(status[0])) startedAt = android.os.SystemClock.elapsedRealtime();
                // the app's player keeps running on its own; let go of the connection a moment later
                main.postDelayed(() -> { try { if (mb[0] != null) mb[0].disconnect(); } catch (Exception ignored) {} }, OK.equals(status[0]) ? 3000 : 0);
                done.result(status[0], title[0]);
            };
            main.postAtTime(finish[0], finish, android.os.SystemClock.uptimeMillis() + 24000); // safety net
            final long readUntil = android.os.SystemClock.uptimeMillis() + 9000; // connecting + reading its folders
            try {
                mb[0] = new MediaBrowser(c, SERVICE, new MediaBrowser.ConnectionCallback() {
                    @Override public void onConnected() {
                        status[0] = NOT_FOUND;
                        collect(mb[0], 0, readUntil, new ArrayList<>(), items -> {
                            if (over[0]) return;
                            if (my != GEN.get()) { status[0] = CANCELLED; finish[0].run(); return; }
                            MediaBrowser.MediaItem pick = best(items, station);
                            if (pick == null) { finish[0].run(); return; }
                            title[0] = String.valueOf(pick.getDescription().getTitle());
                            status[0] = NOT_STARTED; // found it; now it has to start
                            main.removeCallbacksAndMessages(finish);
                            main.postAtTime(finish[0], finish, android.os.SystemClock.uptimeMillis() + 14000); // startIt decides within 12 s
                            startIt(c, main, mb[0], pick, ok -> {
                                status[0] = ok ? OK : NOT_STARTED;
                                finish[0].run();
                            });
                        });
                    }
                    @Override public void onConnectionFailed() { finish[0].run(); }
                    @Override public void onConnectionSuspended() { finish[0].run(); }
                }, null);
                mb[0].connect();
            } catch (Exception e) {
                finish[0].run();
            }
        });
    }

    /** The same, waiting for the answer (call off the main thread): {status, title}. */
    static String[] playBlocking(Context c, String station) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String[]> out = new AtomicReference<>(new String[]{NO_CONNECT, ""});
        play(c, station, (s, t) -> { out.set(new String[]{s, t}); done.countDown(); });
        try {
            if (!done.await(40, TimeUnit.SECONDS)) cancelPending();
        } catch (InterruptedException e) {
            cancelPending(); // the panel closed: don't start it later
            Thread.currentThread().interrupt();
            return new String[]{CANCELLED, ""};
        }
        return out.get();
    }

    private interface Items { void got(List<MediaBrowser.MediaItem> items); }

    /** Reads the folders one after another (each answers once), then hands over every playable station seen. */
    private static void collect(MediaBrowser mb, int i, long until, List<MediaBrowser.MediaItem> seen, Items then) {
        if (i >= FOLDERS.length || !mb.isConnected() || android.os.SystemClock.uptimeMillis() > until) { then.got(seen); return; }
        final String id = FOLDERS[i];
        final boolean[] answered = {false};
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable skip = () -> { if (!answered[0]) { answered[0] = true; unsub(mb, id); collect(mb, i + 1, until, seen, then); } };
        h.postDelayed(skip, 3000); // a folder that never answers
        try {
            mb.subscribe(id, new MediaBrowser.SubscriptionCallback() {
                @Override public void onChildrenLoaded(String parentId, List<MediaBrowser.MediaItem> children) {
                    if (answered[0]) return;
                    answered[0] = true;
                    h.removeCallbacks(skip);
                    for (MediaBrowser.MediaItem m : children) if (m.isPlayable()) seen.add(m);
                    unsub(mb, id);
                    collect(mb, i + 1, until, seen, then);
                }
                @Override public void onError(String parentId) {
                    if (answered[0]) return;
                    answered[0] = true;
                    h.removeCallbacks(skip);
                    unsub(mb, id);
                    collect(mb, i + 1, until, seen, then);
                }
            });
        } catch (Exception e) {
            h.removeCallbacks(skip);
            skip.run();
        }
    }

    private static void unsub(MediaBrowser mb, String id) {
        try { mb.unsubscribe(id); } catch (Exception ignored) {}
    }

    /** The station he means among the app's (many) titles: the same name only, never just a similar one. */
    static MediaBrowser.MediaItem best(List<MediaBrowser.MediaItem> items, String station) {
        if (station == null || station.trim().isEmpty()) return null;
        MediaBrowser.MediaItem close = null;
        String want = Radio.norm(station);
        for (MediaBrowser.MediaItem m : items) {
            MediaDescription d = m.getDescription();
            String t = d.getTitle() == null ? "" : d.getTitle().toString();
            if (Radio.norm(t).equals(want)) return m;
            if (close == null && Radio.sameStation(t, station)) close = m; // "Prema FM" = "Prema FM Telugu"? no; "Prema" = "Prema FM": yes
        }
        return close;
    }

    private interface Started { void result(boolean ok); }

    /** Asks the app to play it and waits up to 12 s: playing = yes, error = no, still connecting at the end = yes. */
    private static void startIt(Context c, Handler main, MediaBrowser mb, MediaBrowser.MediaItem item, Started then) {
        MediaController mc;
        try { mc = new MediaController(c, mb.getSessionToken()); } catch (Exception e) { then.result(false); return; }
        final boolean[] over = {false};
        final MediaController.Callback[] cb = new MediaController.Callback[1];
        final Runnable[] end = new Runnable[1];
        end[0] = () -> {
            if (over[0]) return;
            over[0] = true;
            main.removeCallbacks(end[0]);
            try { mc.unregisterCallback(cb[0]); } catch (Exception ignored) {}
            then.result(started(mc.getPlaybackState()));
        };
        cb[0] = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState s) {
                if (s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_ERROR)) end[0].run();
            }
        };
        try {
            mc.registerCallback(cb[0], main);
            mc.getTransportControls().playFromMediaId(item.getMediaId(), item.getDescription().getExtras());
            main.postDelayed(end[0], 12000);
        } catch (Exception e) {
            try { mc.unregisterCallback(cb[0]); } catch (Exception ignored) {}
            over[0] = true;
            then.result(false);
        }
    }

    private static boolean started(PlaybackState s) {
        if (s == null) return false;
        int st = s.getState();
        return st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING || st == PlaybackState.STATE_CONNECTING;
    }

    /** One Telugu line for what went wrong. */
    static String why(String status, String station) {
        switch (status) {
            case NOT_INSTALLED: return station + " కి లింక్ లేదు, Telugu Radios యాప్ కూడా ఫోన్‌లో లేదు.";
            case NOT_FOUND: return station + " Telugu Radios యాప్‌లో దొరకలేదు. ఆ యాప్‌లో ఆ స్టేషన్‌ని ఒకసారి ప్లే చేసి లేదా Favorites లో పెడితే, తర్వాత నుంచి నేను ప్లే చేయగలను.";
            case NOT_STARTED: return "Telugu Radios యాప్‌కి " + station + " ప్లే చేయమని చెప్పాను, కానీ అది మొదలుకాలేదు.";
            default: return "Telugu Radios యాప్‌తో కనెక్ట్ కాలేకపోయాను.";
        }
    }
}
