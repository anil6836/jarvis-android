package com.anil.jarvis;

import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Plays songs saved on the home tablet (MediaStore music longer than a minute), shuffled.
 * Ducks / pauses while Jarvis speaks (audio focus), skips broken files, and stops by itself after 60 minutes.
 * All player work runs on the main looper; the public calls may come from any thread.
 */
final class HomeSongs {
    private HomeSongs() {}

    private static final long SLEEP_MS = 60L * 60_000L;
    private static final int MAX_SONGS = 500;
    private static final int MAX_ERRORS = 3;
    private static final float DUCK = 0.2f;

    private static final String STOP_HINT = "ఆపాలంటే \"Jarvis, పాటలు ఆపు\" అనండి.";
    private static final String NO_SONGS =
            "టాబ్లెట్‌లో పాటలు ఇంకా లేవు. అబ్బాయి పాటలు టాబ్లెట్‌లో పెట్టిన తర్వాత వినిపిస్తాను.";
    private static final String NO_PERMISSION =
            "టాబ్లెట్‌లో ఉన్న పాటలు చూడటానికి అనుమతి లేదు. అబ్బాయిని అనుమతి ఇవ్వమని చెప్పండి.";
    private static final String FAILED = "ఇప్పుడు పాటలు పెట్టడం కుదరలేదు. కాసేపాగి మళ్ళీ అడగండి.";

    /** Words that are not part of a song name. */
    private static final String[] FILLER = {"పాటలు", "పాట", "పాటల", "songs", "song", "music"};

    private static final class Song {
        final Uri uri;
        final String title;

        Song(Uri uri, String title) {
            this.uri = uri;
            this.title = title;
        }
    }

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final AudioAttributes ATTRS = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

    // Main-thread state.
    private static Context app;
    private static MediaPlayer mp;
    private static final ArrayList<Song> queue = new ArrayList<>();
    private static int pos = -1;
    private static int session;          // bumps for every new song; old callbacks are ignored
    private static boolean prepared;
    private static boolean pausedByFocus;
    private static boolean ducked;
    private static int errors;           // errors in a row
    private static AudioManager am;
    private static AudioFocusRequest focusReq;
    private static boolean hasFocus;

    // Read from any thread.
    private static volatile boolean active;
    private static volatile String title = "";

    private static final Runnable SLEEP = HomeSongs::stopNow;
    /** The night songs in her bedroom play softly (1 = as usual). */
    static volatile float soft = 1f;

    /** The songs stop after ms (the night songs: 30 minutes). */
    static void sleepIn(long ms) {
        runMain(() -> { H.removeCallbacks(SLEEP); if (active) H.postDelayed(SLEEP, ms); });
    }

    // ================================================================ public API

    /** Plays songs saved on the tablet; returns what Jarvis should say. */
    static String play(Context c, String query, String who) {
        String name = who == null ? "" : who.trim();
        String lead = name.isEmpty() ? "" : name + ", ";
        if (c == null) return lead + FAILED;
        try {
            Context a = c.getApplicationContext();
            final Context ac = a != null ? a : c;
            if (!canRead(ac)) return lead + NO_PERMISSION;

            String q = clean(query);
            ArrayList<Song> list = new ArrayList<>();
            if (!q.isEmpty()) {
                list = find(ac, new String[]{q});
                if (list.isEmpty()) {
                    String[] words = q.split("\\s+");
                    if (words.length > 1) list = find(ac, words);
                }
            }
            boolean matched = !list.isEmpty();
            if (list.isEmpty()) list = find(ac, null);
            if (list.isEmpty()) return lead + NO_SONGS;
            Collections.shuffle(list);
            final ArrayList<Song> songs = list;

            boolean onMain = Looper.myLooper() == Looper.getMainLooper();
            runMain(() -> begin(ac, songs));
            if (onMain && !active) return lead + FAILED;

            if (matched) return lead + "టాబ్లెట్‌లో ఉన్న " + q + " పాటలు పెడుతున్నాను. " + STOP_HINT;
            if (!q.isEmpty()) {
                return lead + q + " పాటలు టాబ్లెట్‌లో దొరకలేదు. ఉన్న పాటలు పెడుతున్నాను. " + STOP_HINT;
            }
            return lead + "టాబ్లెట్‌లో ఉన్న పాటలు పెడుతున్నాను. " + STOP_HINT;
        } catch (Throwable t) {
            return lead + FAILED;
        }
    }

    /** True from play() until the songs stop (also while paused for Jarvis's voice). */
    static boolean playing() {
        return active;
    }

    /** Stops the songs and gives the audio focus back. */
    static void stop() {
        runMain(HomeSongs::stopNow);
    }

    /** Skips to the next song. */
    static void next() {
        runMain(() -> {
            if (!active) return;
            errors = 0;
            playNext();
        });
    }

    /** Title of the song playing now, or "" when nothing plays. */
    static String nowTitle() {
        return active ? title : "";
    }

    // ================================================================ finding songs

    private static boolean canRead(Context c) {
        try {
            String p = android.os.Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_AUDIO"
                    : android.Manifest.permission.READ_EXTERNAL_STORAGE;
            return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Query without LIKE wildcards and words like "పాటలు". */
    private static String clean(String query) {
        if (query == null) return "";
        String q = query.replace('%', ' ').replace('_', ' ').replace('"', ' ').replace('\'', ' ').trim();
        StringBuilder sb = new StringBuilder();
        for (String w : q.split("\\s+")) {
            if (w.isEmpty()) continue;
            boolean filler = false;
            for (String f : FILLER) {
                if (f.equalsIgnoreCase(w)) {
                    filler = true;
                    break;
                }
            }
            if (filler) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.toString();
    }

    /** Music longer than a minute; when terms are given, title / artist / album must contain one of them. */
    private static ArrayList<Song> find(Context c, String[] terms) {
        ArrayList<Song> out = new ArrayList<>();
        Cursor cur = null;
        try {
            StringBuilder sel = new StringBuilder("is_music != 0 AND duration > 60000");
            ArrayList<String> args = new ArrayList<>();
            if (terms != null) {
                StringBuilder or = new StringBuilder();
                int used = 0;
                for (String t : terms) {
                    if (t == null || t.trim().length() < 2 || used >= 4) continue;
                    String like = "%" + t.trim() + "%";
                    if (or.length() > 0) or.append(" OR ");
                    or.append("title LIKE ? OR artist LIKE ? OR album LIKE ?");
                    args.add(like);
                    args.add(like);
                    args.add(like);
                    used++;
                }
                if (used == 0) return out;
                sel.append(" AND (").append(or).append(')');
            }
            Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            cur = c.getContentResolver().query(base, new String[]{"_id", "title"}, sel.toString(),
                    args.isEmpty() ? null : args.toArray(new String[0]), null);
            if (cur == null) return out;
            int cId = cur.getColumnIndex("_id");
            int cTitle = cur.getColumnIndex("title");
            if (cId < 0) return out;
            while (cur.moveToNext()) {
                String t = cTitle >= 0 ? cur.getString(cTitle) : null;
                if (t == null || t.trim().isEmpty()) t = "పాట";
                out.add(new Song(ContentUris.withAppendedId(base, cur.getLong(cId)), t.trim()));
            }
            if (out.size() > MAX_SONGS) {
                Collections.shuffle(out);
                return new ArrayList<>(out.subList(0, MAX_SONGS));
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (cur != null) cur.close();
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    // ================================================================ player (main thread)

    private static void runMain(Runnable r) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) r.run();
            else H.post(r);
        } catch (Throwable ignored) {
        }
    }

    private static void begin(Context ac, ArrayList<Song> songs) {
        try {
            app = ac;
            release();
            queue.clear();
            queue.addAll(songs);
            pos = -1;
            errors = 0;
            pausedByFocus = false;
            ducked = false;
            if (!requestFocus()) {
                stopNow();
                return;
            }
            active = true;
            H.removeCallbacks(SLEEP);
            H.postDelayed(SLEEP, SLEEP_MS);
            playNext();
        } catch (Throwable t) {
            stopNow();
        }
    }

    private static void playNext() {
        if (!active || queue.isEmpty() || app == null) {
            stopNow();
            return;
        }
        release();
        pos++;
        if (pos >= queue.size()) {
            pos = 0;
            if (queue.size() > 1) Collections.shuffle(queue);
        }
        Song s = queue.get(pos);
        final int my = ++session;
        title = s.title;
        prepared = false;
        MediaPlayer p = new MediaPlayer();
        mp = p;
        try {
            p.setAudioAttributes(ATTRS);
            p.setOnPreparedListener(x -> onPrepared(my));
            p.setOnCompletionListener(x -> {
                if (my != session) return;
                errors = 0;
                H.post(() -> {
                    if (active && my == session) playNext();
                });
            });
            p.setOnErrorListener((x, what, extra) -> {
                if (my == session) failed(my);
                return true;
            });
            p.setDataSource(app, s.uri);
            p.prepareAsync();
        } catch (Throwable t) {
            failed(my);
        }
    }

    private static void onPrepared(int my) {
        try {
            if (my != session || mp == null || !active) return;
            prepared = true;
            errors = 0;
            float v = (ducked ? DUCK : 1f) * soft;
            mp.setVolume(v, v);
            if (!pausedByFocus) mp.start();
        } catch (Throwable t) {
            failed(my);
        }
    }

    /** A song failed: skip it, or stop after 3 in a row. */
    private static void failed(int my) {
        errors++;
        if (errors >= MAX_ERRORS) {
            stopNow();
            return;
        }
        H.post(() -> {
            if (active && my == session) playNext();
        });
    }

    private static void release() {
        MediaPlayer p = mp;
        mp = null;
        prepared = false;
        if (p == null) return;
        try {
            p.setOnPreparedListener(null);
            p.setOnCompletionListener(null);
            p.setOnErrorListener(null);
        } catch (Throwable ignored) {
        }
        try {
            p.release();
        } catch (Throwable ignored) {
        }
    }

    private static void stopNow() {
        try {
            active = false;
            soft = 1f;
            title = "";
            session++;
            H.removeCallbacks(SLEEP);
            release();
            queue.clear();
            pos = -1;
            errors = 0;
            pausedByFocus = false;
            ducked = false;
            abandonFocus();
        } catch (Throwable ignored) {
        }
    }

    // ================================================================ audio focus

    private static final AudioManager.OnAudioFocusChangeListener FOCUS = change -> {
        try {
            switch (change) {
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    ducked = true;
                    if (mp != null) mp.setVolume(DUCK * soft, DUCK * soft);
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    pausedByFocus = true;
                    if (mp != null && prepared && mp.isPlaying()) mp.pause();
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    ducked = false;
                    if (mp != null) {
                        mp.setVolume(soft, soft);
                        if (pausedByFocus && prepared && active && !mp.isPlaying()) mp.start();
                    }
                    pausedByFocus = false;
                    break;
                case AudioManager.AUDIOFOCUS_LOSS:
                    stopNow();
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {
        }
    };

    private static boolean requestFocus() {
        try {
            if (am == null) am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return true;
            if (focusReq == null) {
                focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(ATTRS)
                        .setWillPauseWhenDucked(false)
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener(FOCUS, H)
                        .build();
            }
            int r = am.requestAudioFocus(focusReq);
            hasFocus = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            // Refused (for example during a phone call): do not play over it.
            return hasFocus;
        } catch (Throwable t) {
            return true;
        }
    }

    private static void abandonFocus() {
        try {
            if (am != null && focusReq != null && hasFocus) am.abandonAudioFocusRequest(focusReq);
        } catch (Throwable ignored) {
        }
        hasFocus = false;
    }
}
