package com.anil.jarvis;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Birthdays and wedding anniversaries: from the phone's contacts (the birthday saved on a contact) and ones
 * Anil tells Jarvis. The evening before, a reminder; on the day, a morning note and Jarvis offers to write
 * the wishes on WhatsApp (sent only after his "పంపు").
 */
final class Birthdays {
    private Birthdays() {}

    static final String LIST = "birthdays";
    private static final Pattern DATE = Pattern.compile("(\\d{4}|-)?-?(\\d{1,2})-(\\d{1,2})");

    /** Everyone's dates: {name, month, day, year (0 = unknown), kind: birthday | anniversary, from}. */
    static List<JSONObject> all(Context c) {
        List<JSONObject> out = new ArrayList<>();
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            String[] cols = {ContactsContract.Data.DISPLAY_NAME, ContactsContract.CommonDataKinds.Event.START_DATE, ContactsContract.CommonDataKinds.Event.TYPE};
            try (Cursor cur = c.getContentResolver().query(ContactsContract.Data.CONTENT_URI, cols,
                    ContactsContract.Data.MIMETYPE + " = ?", new String[]{ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE}, null)) {
                while (cur != null && cur.moveToNext()) {
                    int type = cur.getInt(2);
                    String kind = type == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY ? "birthday"
                            : type == ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY ? "anniversary" : null;
                    JSONObject o = kind == null ? null : parse(cur.getString(0), cur.getString(1), kind, "contacts");
                    if (o != null) out.add(o);
                }
            } catch (Exception ignored) {}
        }
        for (JSONObject m : Notes.list(c, LIST)) {
            try { out.add(new JSONObject(m.toString()).put("from", "added")); } catch (Exception ignored) {}
        }
        return out;
    }

    private static JSONObject parse(String name, String date, String kind, String from) {
        if (name == null || date == null) return null;
        Matcher m = DATE.matcher(date.trim());
        if (!m.find()) return null;
        try {
            int year = m.group(1) == null || m.group(1).equals("-") ? 0 : Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2)), day = Integer.parseInt(m.group(3));
            if (month < 1 || month > 12 || day < 1 || day > 31) return null;
            return new JSONObject().put("name", name).put("month", month).put("day", day).put("year", year < 1900 ? 0 : year)
                    .put("kind", kind).put("from", from);
        } catch (Exception e) {
            return null;
        }
    }

    /** Days until the next time this date comes (0 = today), and the age / years it will be. */
    private static JSONObject next(JSONObject o) throws Exception {
        Calendar now = Calendar.getInstance(), d = Calendar.getInstance();
        now.set(Calendar.HOUR_OF_DAY, 0); now.set(Calendar.MINUTE, 0); now.set(Calendar.SECOND, 0); now.set(Calendar.MILLISECOND, 0);
        d.setTimeInMillis(now.getTimeInMillis());
        d.set(Calendar.MONTH, o.optInt("month") - 1);
        d.set(Calendar.DAY_OF_MONTH, Math.min(o.optInt("day"), d.getActualMaximum(Calendar.DAY_OF_MONTH)));
        if (d.before(now)) d.add(Calendar.YEAR, 1);
        int days = (int) Math.round((d.getTimeInMillis() - now.getTimeInMillis()) / 86400000.0);
        JSONObject r = new JSONObject(o.toString()).put("in_days", days)
                .put("date", String.format(Locale.ENGLISH, "%02d-%02d", o.optInt("month"), o.optInt("day")));
        if (o.optInt("year") > 0) r.put("turns", d.get(Calendar.YEAR) - o.optInt("year"));
        return r;
    }

    /** The coming ones within this many days, soonest first (the same person and date only once). */
    static List<JSONObject> upcoming(Context c, int days) {
        List<JSONObject> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JSONObject o : all(c)) {
            try {
                JSONObject n = next(o);
                String key = n.optString("name").toLowerCase(Locale.ROOT) + n.optString("date") + n.optString("kind");
                if (n.optInt("in_days") <= days && seen.add(key)) out.add(n);
            } catch (Exception ignored) {}
        }
        out.sort((a, b) -> Integer.compare(a.optInt("in_days"), b.optInt("in_days")));
        return out;
    }

    /** He tells a date: "అమ్మ పుట్టినరోజు మార్చి 5" -> month 3, day 5 (year if he knows it). */
    static JSONObject add(Context c, String name, String date, String kind) throws Exception {
        String k = kind != null && kind.toLowerCase(Locale.ROOT).startsWith("anniv") ? "anniversary" : "birthday";
        JSONObject o = parse(name == null ? null : name.trim(), date, k, "added");
        if (o == null) return null;
        remove(c, name, k);
        o.remove("from");
        o.put("id", Notes.id("b"));
        Notes.add(c, LIST, o, 500);
        return next(o);
    }

    static boolean remove(Context c, String name, String kind) {
        if (name == null) return false;
        List<JSONObject> l = Notes.list(c, LIST);
        boolean found = false;
        for (int i = l.size() - 1; i >= 0; i--) {
            JSONObject o = l.get(i);
            if (o.optString("name").equalsIgnoreCase(name.trim()) && (kind == null || kind.isEmpty() || o.optString("kind").equals(kind))) {
                l.remove(i);
                found = true;
            }
        }
        if (found) Notes.save(c, LIST, l, 100000);
        return found;
    }

    static String label(JSONObject o) {
        String what = "anniversary".equals(o.optString("kind")) ? "పెళ్లిరోజు" : "పుట్టినరోజు";
        String turns = o.has("turns") ? " (" + o.optInt("turns") + ("anniversary".equals(o.optString("kind")) ? " ఏళ్లు" : " ఏళ్లు నిండుతాయి") + ")" : "";
        return o.optString("name") + " " + what + turns;
    }

    static String when(JSONObject o) {
        int d = o.optInt("in_days");
        return d == 0 ? "ఈరోజు" : d == 1 ? "రేపు" : d + " రోజుల్లో";
    }

    // ---------------------------------------------------------------- reminders (Proactive's regular check)

    private static SharedPreferences told(Context c) { return c.getSharedPreferences("jarvis_bday", Context.MODE_PRIVATE); }

    /** Once per person and year: the evening before (8-11 pm) and the morning of the day (8-11 am). */
    static void tick(Context c, Prefs p, boolean quiet) {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        boolean evening = hour >= 20 && hour < 23, morning = hour >= 8 && hour < 11;
        if (!evening && !morning) return;
        int year = Calendar.getInstance().get(Calendar.YEAR);
        for (JSONObject o : upcoming(c, 1)) {
            int d = o.optInt("in_days");
            if ((evening && d != 1) || (morning && d != 0)) continue;
            String key = (morning ? "day|" : "eve|") + o.optString("name") + "|" + o.optString("kind") + "|" + year;
            if (told(c).getBoolean(key, false)) continue;
            told(c).edit().putBoolean(key, true).apply();
            String who = o.optString("name");
            if (morning) {
                String wish = "ఈరోజు " + label(o) + ". " + who + " కి WhatsApp లో తెలుగులో చిన్న, ఆత్మీయమైన "
                        + ("anniversary".equals(o.optString("kind")) ? "పెళ్లిరోజు" : "పుట్టినరోజు") + " విషెస్ మెసేజ్ సిద్ధం చెయ్ (whatsapp_message). పంపే ముందు నాకు చదివి వినిపించి అడుగు.";
                notify(c, key.hashCode(), "🎂 ఈరోజు " + label(o), "విషెస్ WhatsApp లో సిద్ధం చేయడానికి నొక్కండి.", wish);
                if (!quiet) Announcer.say(c, p.name() + ", ఈరోజు " + label(o) + ". విషెస్ పంపాలంటే notification నొక్కండి.");
            } else {
                notify(c, key.hashCode(), "🎂 రేపు " + label(o), "రేపు ఉదయం గుర్తు చేస్తాను, విషెస్ కూడా సిద్ధం చేస్తాను.", null);
            }
        }
    }

    private static void notify(Context c, int id, String title, String text, String ask) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel("jarvis_bday", "పుట్టినరోజులు", NotificationManager.IMPORTANCE_HIGH));
            Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (ask != null) open.putExtra(MainActivity.EXTRA_ASK, ask).putExtra(MainActivity.EXTRA_LABEL, "🎂 " + title.replace("🎂 ", "") + " · విషెస్");
            PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify("bday", id, new Notification.Builder(c, "jarvis_bday").setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                    .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi).setAutoCancel(true).build());
        } catch (Exception ignored) {}
    }
}
