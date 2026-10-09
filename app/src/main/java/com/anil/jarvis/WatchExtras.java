package com.anil.jarvis;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 5 extras for the watch: W54 the parking photo on the wrist, W55 his duty days in the phone's calendar (so the
 * watch's calendar has them; only after he turns it on; Jarvis changes only the events it made), W56 a recording made
 * on the watch, written out in Telugu with a short summary (his OpenAI key, as the phone's recorder does).
 */
final class WatchExtras {
    private WatchExtras() {}

    static final String P_REC_START = "/jarvis/rec/start", P_REC_DATA = "/jarvis/rec/data", P_REC_END = "/jarvis/rec/end";
    private static final String TAG = "#jarvis-duty";

    // ================================================================ W54: the parking photo

    /** The newest parking photo (taken from Jarvis's camera "🅿️ బండి ఇక్కడ పెట్టాను"), or null. */
    static byte[] parkingPhoto(Context c) {
        for (JSONObject o : ScanStore.list(c, false)) {
            if (!"parking".equals(o.optString("kind"))) continue;
            File f = ScanStore.file(c, o.optString("id"), "photo.jpg");
            try { return f.exists() ? java.nio.file.Files.readAllBytes(f.toPath()) : null; } catch (Exception e) { return null; }
        }
        return null;
    }

    static String sendParking(Context c) {
        byte[] b = parkingPhoto(c);
        if (b == null) return "బండి పెట్టిన చోటు ఫోటో లేదు. పెట్టినప్పుడు Jarvis కెమెరా → \"🅿️ బండి ఇక్కడ పెట్టాను\" నొక్కితే ఫోటో కూడా దాస్తాను.";
        return WatchPhoto.send(c, b, "🅿️ బండి పెట్టిన చోటు") ? "బండి ఫోటో వాచ్‌కి పంపాను." : "వాచ్ ఫోన్ దగ్గర లేదు.";
    }

    // ================================================================ W55: duty days in the calendar

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_watch_extras", Context.MODE_PRIVATE); }

    static boolean calOn(Context c) { return sp(c).getBoolean("duty_cal", false); }

    /** His choice (asked first): on = the next two months of duty go into the phone's calendar; off = Jarvis's events go. */
    static String calSet(Context c, boolean on) {
        if (c.checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return "క్యాలెండర్ అనుమతి లేదు: Jarvis కి క్యాలెండర్ అనుమతి ఇవ్వండి.";
        sp(c).edit().putBoolean("duty_cal", on).apply();
        if (!on) { int n = removeOwn(c, 0); return "సరే, Jarvis పెట్టిన " + n + " డ్యూటీ ఈవెంట్లు క్యాలెండర్ నుంచి తీసేశాను."; }
        int n = calSync(c);
        return n < 0 ? "క్యాలెండర్‌లో పెట్టలేకపోయాను (ఫోన్‌లో క్యాలెండర్ అకౌంట్ లేదా?)." : n == 0 ? "డ్యూటీ క్యాలెండర్ ఇంకా సెట్ చేయలేదు."
                : "సరే, వచ్చే 2 నెలల్లో " + n + " డ్యూటీలు ఫోన్ క్యాలెండర్‌లో పెట్టాను. వాచ్ క్యాలెండర్‌లోనూ వస్తాయి. డ్యూటీ మారితే నేనే సరిచేస్తాను.";
    }

    /** Once a day (and on his choice): Jarvis's own future duty events made again from the roster. Count, -1 on a problem. */
    static int calSync(Context c) {
        if (!calOn(c) || c.checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) return -1;
        Duty.Roster r = Duty.load(c);
        if (!Duty.ready(r)) return 0;
        long cal = calendar(c);
        if (cal < 0) return -1;
        long now = System.currentTimeMillis();
        removeOwn(c, now);
        String[] hm = r.timeOf(Duty.ME).split(":");
        int h = Integer.parseInt(hm[0]), m = Integer.parseInt(hm[1]);
        int n = 0;
        LocalDate today = LocalDate.now();
        for (LocalDate[] b : r.blocks(Duty.ME, today.minusDays(3), today.plusDays(60))) {
            LocalDateTime from = b[0].atTime(h, m), to = b[1].plusDays(1).atTime(h, m);
            long s = from.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), e = to.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (e < now) continue;
            ContentValues v = new ContentValues();
            v.put(CalendarContract.Events.CALENDAR_ID, cal);
            v.put(CalendarContract.Events.TITLE, "🏍️ డ్యూటీ");
            v.put(CalendarContract.Events.DESCRIPTION, "Jarvis పెట్టింది " + TAG);
            v.put(CalendarContract.Events.DTSTART, s);
            v.put(CalendarContract.Events.DTEND, e);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
            try { if (c.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, v) != null) n++; } catch (Exception ignored) {}
        }
        sp(c).edit().putLong("cal_synced", now).apply();
        return n;
    }

    static void calTick(Context c) {
        if (calOn(c) && System.currentTimeMillis() - sp(c).getLong("cal_synced", 0) > 20 * 3600_000L) calSync(c);
    }

    /** Jarvis's own duty events (by its tag) ending after `after` removed; how many. */
    private static int removeOwn(Context c, long after) {
        int n = 0;
        try (Cursor cur = c.getContentResolver().query(CalendarContract.Events.CONTENT_URI, new String[]{CalendarContract.Events._ID},
                CalendarContract.Events.DESCRIPTION + " LIKE ? AND " + CalendarContract.Events.DTEND + " >= ? AND " + CalendarContract.Events.DELETED + " = 0",
                new String[]{"%" + TAG + "%", String.valueOf(after)}, null)) {
            while (cur != null && cur.moveToNext()) {
                Uri u = android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, cur.getLong(0));
                try { n += c.getContentResolver().delete(u, null, null); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return n;
    }

    /** His main calendar (primary / Google first), or -1. */
    private static long calendar(Context c) {
        long best = -1;
        int bestRank = 9;
        try (Cursor cur = c.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI,
                new String[]{CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL},
                CalendarContract.Calendars.VISIBLE + " = 1", null, null)) {
            while (cur != null && cur.moveToNext()) {
                if (cur.getInt(3) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue;
                int rank = cur.getInt(1) == 1 ? 0 : "com.google".equals(cur.getString(2)) ? 1 : 2;
                if (rank < bestRank) { bestRank = rank; best = cur.getLong(0); }
            }
        } catch (Exception ignored) {}
        return best;
    }

    // ================================================================ W56: a recording from the watch

    private static final Map<String, Object[]> recs = new ConcurrentHashMap<>(); // id -> {file, started}

    static void recStart(Context c, JSONObject o) {
        String id = o.optString("id");
        File dir = new File(c.getExternalFilesDir(null), "recordings/" + o.optLong("t", System.currentTimeMillis()));
        dir.mkdirs();
        File f = new File(dir, "part00.m4a");
        f.delete();
        recs.put(id, new Object[]{f, o.optLong("t", System.currentTimeMillis())});
    }

    /** "<id>|<bytes>" in order. */
    static void recData(byte[] data) {
        int bar = -1;
        for (int i = 0; i < Math.min(40, data.length); i++) if (data[i] == '|') { bar = i; break; }
        if (bar <= 0) return;
        Object[] r = recs.get(new String(data, 0, bar, StandardCharsets.US_ASCII));
        if (r == null) return;
        try (FileOutputStream out = new FileOutputStream((File) r[0], true)) { out.write(data, bar + 1, data.length - bar - 1); } catch (Exception ignored) {}
    }

    /** The watch finished sending: written out and summed up (background), the result back on the watch and as a notification. */
    static void recEnd(Context c, JSONObject o) {
        Object[] r = recs.remove(o.optString("id"));
        if (r == null) return;
        File f = (File) r[0];
        long started = (long) r[1], ended = started + o.optLong("secs") * 1000;
        new Thread(() -> {
            String say;
            try {
                if (!f.exists() || f.length() < 2000) say = "వాచ్ రికార్డింగ్ ఖాళీగా వచ్చింది.";
                else {
                    JSONObject item = RecorderService.process(c, f.getParentFile(), "meeting", "వాచ్ రికార్డింగ్", started, ended);
                    String st = item.optString("status"), notes = item.optString("notes");
                    say = "ready".equals(st) ? "📝 " + (notes.length() > 600 ? notes.substring(0, 600) + "…" : notes) : item.optString("status_line", "రికార్డింగ్ ఫోన్‌లో దాచాను.");
                }
            } catch (Exception e) {
                say = "రికార్డింగ్ రాయలేకపోయాను: " + e.getMessage();
            }
            final String s = say;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> WatchHub.reply(c, s, false, false, () -> WatchHub.idle(c, null)));
        }, "watch-rec").start();
    }
}
