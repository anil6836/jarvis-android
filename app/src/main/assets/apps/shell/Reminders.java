package __PKG__;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Iterator;

/**
 * 🔔 The app's reminders: kept in its own settings, each one an alarm; at its time a notification (a tap opens the app at
 * the reminder's screen), then the next one for "every day / week / month / year". Set again after the phone restarts.
 */
public class Reminders extends BroadcastReceiver {
    private static final String PREFS = "jarvis_reminders", CHANNEL = "reminders";

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static JSONObject all(Context c) {
        try { return new JSONObject(sp(c).getString("all", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    private static void keep(Context c, JSONObject all) { sp(c).edit().putString("all", all.toString()).apply(); }

    static synchronized void add(Context c, JSONObject r) throws Exception {
        String id = r.optString("id");
        if (id.isEmpty()) throw new IllegalArgumentException("no id");
        String every = r.isNull("every") ? "" : r.optString("every", ""); // (Android reads a JSON null as the word "null")
        if (!every.matches("day|week|month|year")) every = "";
        JSONObject x = new JSONObject().put("id", id).put("at", r.optLong("at")).put("every", every)
                .put("title", r.optString("title")).put("text", r.optString("text")).put("screen", r.optString("screen"));
        x.put("at", next(x));
        JSONObject all = all(c);
        all.put(id, x);
        keep(c, all);
        schedule(c, x);
    }

    static synchronized void remove(Context c, String id) {
        JSONObject all = all(c);
        all.remove(id);
        keep(c, all);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c, id));
    }

    static synchronized JSONArray list(Context c) {
        JSONArray out = new JSONArray();
        JSONObject all = all(c);
        for (Iterator<String> it = all.keys(); it.hasNext(); ) out.put(all.optJSONObject(it.next()));
        return out;
    }

    private static PendingIntent pending(Context c, String id) {
        Intent i = new Intent(c, Reminders.class).setAction("fire").putExtra("id", id);
        return PendingIntent.getBroadcast(c, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The reminder's next time from now (a repeating one moves on by its step). */
    static long next(JSONObject r) {
        long at = r.optLong("at");
        String every = r.optString("every", "");
        if (every.isEmpty()) return at;
        int field = every.equals("week") ? Calendar.WEEK_OF_YEAR : every.equals("month") ? Calendar.MONTH : every.equals("year") ? Calendar.YEAR : Calendar.DAY_OF_YEAR;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(at);
        long now = System.currentTimeMillis();
        for (int n = 0; cal.getTimeInMillis() <= now && n < 100000; n++) cal.add(field, 1);
        return cal.getTimeInMillis();
    }

    private static void schedule(Context c, JSONObject r) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long at = r.optLong("at");
        PendingIntent pi = pending(c, r.optString("id"));
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    @Override public void onReceive(Context c, Intent i) {
        String act = i == null ? null : i.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(act) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(act)) { again(c); return; }
        if (!"fire".equals(act)) return;
        String id = i.getStringExtra("id");
        synchronized (Reminders.class) {
            JSONObject all = all(c), r = id == null ? null : all.optJSONObject(id);
            if (r == null || r.optLong("at") > System.currentTimeMillis() + 60_000) return; // (only at its time: the receiver is open for the phone's restart)
            show(c, r);
            if (r.optString("every", "").isEmpty()) all.remove(id);
            else {
                try { r.put("at", next(r)); all.put(id, r); } catch (Exception ignored) {}
                schedule(c, r);
            }
            keep(c, all);
        }
    }

    /** After a restart: every reminder set again (one-time ones whose time passed while the phone was off: shown now, once). */
    private static synchronized void again(Context c) {
        JSONObject all = all(c);
        long now = System.currentTimeMillis();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject r = all.optJSONObject(id);
            if (r == null) continue;
            if (r.optString("every", "").isEmpty() && r.optLong("at") <= now) { show(c, r); it.remove(); continue; }
            try { r.put("at", next(r)); } catch (Exception ignored) {}
            schedule(c, r);
        }
        keep(c, all);
    }

    private static void show(Context c, JSONObject r) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "రిమైండర్లు", NotificationManager.IMPORTANCE_HIGH));
        String id = r.optString("id");
        Intent open = new Intent(c, MainActivity.class).putExtra("screen", r.optString("screen")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_bell).setContentTitle(r.optString("title")).setContentText(r.optString("text")).setAutoCancel(true).setContentIntent(pi)
                .setStyle(new Notification.BigTextStyle().bigText(r.optString("text")));
        nm.notify(id.hashCode(), b.build());
    }
}
