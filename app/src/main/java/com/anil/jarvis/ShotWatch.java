package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 📸 C (Anil, 12 Nov): a screenshot he takes is read on the phone (nothing sent; the text itself not kept) and, when it holds
 * a ticket / show / function / bill-due day, an address or a shop's number, one notification offers it in a tap: ⏰ a
 * reminder, 🗺️ the map, 📇 save the contact (the contacts app opens filled in; he saves). Nothing happens without his
 * tap. Screens with an OTP / PIN / password / card / balance / ID are left alone. Switch ⚙️ (on). Phone only.
 */
final class ShotWatch {
    private ShotWatch() {}

    static final String ACTION_REMIND = "com.anil.jarvis.SHOT_REMIND";
    private static final String CH = "jarvis_shots";
    private static final int NID = 0x5407;
    private static HandlerThread thread;
    private static ContentObserver observer;
    private static long lastId = -1;

    static boolean on(Context c) { return new Prefs(c).sp.getBoolean("shot_help", true); }

    /** From NotifyListener (it always runs): starts watching the photos for new screenshots. */
    static synchronized void start(Context c) {
        if (observer != null) return;
        final Context app = c.getApplicationContext();
        thread = new HandlerThread("shot-watch");
        thread.start();
        final Handler h = new Handler(thread.getLooper());
        final Runnable look = () -> { try { look(app); } catch (Throwable ignored) {} };
        observer = new ContentObserver(h) {
            @Override public void onChange(boolean self, Uri uri) {
                h.removeCallbacks(look);
                h.postDelayed(look, 1500); // (the file is still being written; many changes come together)
            }
        };
        try { app.getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer); }
        catch (Exception e) { observer = null; thread.quitSafely(); }
    }

    static synchronized void stop(Context c) {
        if (observer != null) { try { c.getApplicationContext().getContentResolver().unregisterContentObserver(observer); } catch (Exception ignored) {} }
        observer = null;
        if (thread != null) thread.quitSafely();
        thread = null;
    }

    /** The newest picture, if it is a screenshot taken in the last 20 s that wasn't looked at yet: read and offered. */
    private static void look(Context c) {
        if (!on(c) || !PhotoText.allowed(c) || new Prefs(c).homeMode() || CarBox.on(c)) return;
        long id;
        String path, name;
        try (Cursor cur = c.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DISPLAY_NAME,
                        android.os.Build.VERSION.SDK_INT >= 29 ? "relative_path" : "_data"}, // (the folder: …/Screenshots/)
                null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (cur == null || !cur.moveToFirst()) return;
            id = cur.getLong(0);
            if (System.currentTimeMillis() / 1000 - cur.getLong(1) > 20) return;
            name = cur.getString(2) == null ? "" : cur.getString(2);
            path = cur.getString(3) == null ? "" : cur.getString(3);
        } catch (Exception e) { return; }
        if (id == lastId) return;
        String low = (path + "/" + name).toLowerCase(Locale.ROOT);
        if (!low.contains("screenshot")) return;
        lastId = id;
        Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
        String text = read(c, u);
        if (text == null || text.isEmpty()) return;
        ShotRead.Offer o = ShotRead.parse(text, LocalDateTime.now());
        if (o != null) offer(c, o);
    }

    private static String read(Context c, Uri u) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = c.getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 1800) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap b;
            try (java.io.InputStream in = c.getContentResolver().openInputStream(u)) { b = BitmapFactory.decodeStream(in, null, o2); }
            if (b == null) return null;
            try { return OfflineRead.text(b); } finally { b.recycle(); }
        } catch (Throwable e) {
            return null;
        }
    }

    private static final DateTimeFormatter SHOW = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH);

    /** When the reminder rings: 2 hours before (a bill: the day before at 9 am); too close: 15 minutes before; past: none. */
    static LocalDateTime remindAt(ShotRead.Offer o, LocalDateTime now) {
        if (o.when == null) return null;
        LocalDateTime r = "bill".equals(o.kind) ? o.when.toLocalDate().minusDays(1).atTime(9, 0) : o.when.minusHours(2);
        if (!r.isAfter(now.plusMinutes(5))) r = o.when.minusMinutes(15);
        return r.isAfter(now.plusMinutes(2)) ? r : null;
    }

    private static void offer(Context c, ShotRead.Offer o) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CH, "📸 స్క్రీన్‌షాట్ సహాయం", NotificationManager.IMPORTANCE_DEFAULT));
        StringBuilder body = new StringBuilder();
        String title = "📸 స్క్రీన్‌షాట్‌లో";
        Notification.Builder b = new Notification.Builder(c, CH).setSmallIcon(android.R.drawable.ic_menu_gallery).setAutoCancel(true);
        LocalDateTime now = LocalDateTime.now(), at = remindAt(o, now);
        if (o.when != null) {
            title += ": " + o.title + " · " + SHOW.format(o.when);
            if (at != null) {
                String what = o.title + " " + SHOW.format(o.when) + " (స్క్రీన్‌షాట్ నుంచి)";
                Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION_REMIND).putExtra("text", what)
                        .putExtra("at", at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
                b.addAction(new Notification.Action.Builder(null, "⏰ " + ("bill".equals(o.kind) ? "ముందు రోజు గుర్తుచేయి" : "2 గం. ముందు గుర్తుచేయి"),
                        PendingIntent.getBroadcast(c, 0x5401, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
                body.append("⏰ ").append(SHOW.format(at)).append(" కి గుర్తుచేస్తాను (మీరు నొక్కితేనే)\n");
            }
        }
        if (o.address != null) {
            body.append("🗺️ ").append(o.address).append('\n');
            Intent map = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(o.address))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            b.addAction(new Notification.Action.Builder(null, "🗺️ మ్యాప్‌లో చూపించు", PendingIntent.getActivity(c, 0x5402, map, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        }
        if (o.phone != null) {
            body.append("📇 ").append(o.name).append(" · ").append(o.phone).append('\n');
            Intent add = new Intent(Intent.ACTION_INSERT).setType(android.provider.ContactsContract.Contacts.CONTENT_TYPE)
                    .putExtra(android.provider.ContactsContract.Intents.Insert.NAME, o.name).putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, o.phone)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            b.addAction(new Notification.Action.Builder(null, "📇 కాంటాక్ట్‌గా సేవ్", PendingIntent.getActivity(c, 0x5403, add, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        }
        if (body.length() == 0) return; // (a day already past and nothing else)
        String t = body.toString().trim();
        b.setContentTitle(title).setContentText(t).setStyle(new Notification.BigTextStyle().bigText(t));
        try { nm.notify(NID, b.build()); } catch (Exception ignored) {}
    }

    /** ⏰ tapped: the reminder set (AlarmReceiver). */
    static void remind(Context c, Intent i) {
        String text = i.getStringExtra("text");
        long at = i.getLongExtra("at", 0);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NID);
        if (text == null || at <= System.currentTimeMillis()) return;
        try {
            org.json.JSONObject r = Store.get(c).addReminder(text, at);
            Reminders.schedule(c, r);
            android.widget.Toast.makeText(c.getApplicationContext(), "⏰ " + SHOW.format(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), ZoneId.systemDefault()))
                    + " కి గుర్తుచేస్తాను", android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    }
}
