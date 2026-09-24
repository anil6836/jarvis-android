package com.anil.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.telephony.SmsManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** The phone abilities Jarvis can use. Each tool returns a small JSON string for the model. */
final class Tools {

    /** What the tools need from the screen: an activity, confirmations, permission prompts. */
    interface Host {
        Activity activity();
        /** Blocks the calling (background) thread until Anil answers. autoSeconds > 0 confirms automatically. */
        boolean confirm(String title, String message, String yes, int autoSeconds);
        void askPermissions(String[] permissions);
        void notice(String text);
    }

    private final Host host;
    private final Store store;

    Tools(Host host, Store store) {
        this.host = host;
        this.store = store;
    }

    // ================================================================ definitions

    private static final class Def {
        final String name, description;
        final JSONObject params;
        Def(String name, String description, JSONObject params) {
            this.name = name; this.description = description; this.params = params;
        }
    }

    private static JSONObject schema(String[][] props, String... required) {
        try {
            JSONObject p = new JSONObject();
            for (String[] prop : props) {
                JSONObject d = new JSONObject().put("type", prop[1]).put("description", prop[2]);
                p.put(prop[0], d);
            }
            JSONArray req = new JSONArray();
            for (String r : required) req.put(r);
            return new JSONObject().put("type", "object").put("properties", p).put("required", req);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final List<Def> DEFS = new ArrayList<>();
    static {
        DEFS.add(new Def("call_contact",
                "Place a phone call to a contact name or a phone number. The app shows Anil a 4-second countdown he can cancel.",
                schema(new String[][]{{"who", "string", "Contact name as saved in the phone (English letters), or a phone number"}}, "who")));
        DEFS.add(new Def("find_contact",
                "Look up contacts whose saved name contains the text. Returns names and numbers. Use it when unsure which contact he means.",
                schema(new String[][]{{"name", "string", "Part of the saved contact name, English letters"}}, "name")));
        DEFS.add(new Def("send_sms",
                "Send an SMS text message. The app asks Anil to confirm before sending. Write the message in the language he asked for.",
                schema(new String[][]{{"who", "string", "Contact name or phone number"}, {"message", "string", "The message text"}}, "who", "message")));
        DEFS.add(new Def("whatsapp_message",
                "Open WhatsApp with a message ready for a contact. Anil taps send himself.",
                schema(new String[][]{{"who", "string", "Contact name or phone number"}, {"message", "string", "The message text"}}, "who", "message")));
        DEFS.add(new Def("set_alarm",
                "Set an alarm in the phone's clock app.",
                schema(new String[][]{{"hour", "integer", "Hour 0-23 in local time"}, {"minute", "integer", "Minute 0-59"},
                        {"label", "string", "Short label shown with the alarm"}}, "hour", "minute")));
        DEFS.add(new Def("set_timer",
                "Start a countdown timer in the phone's clock app.",
                schema(new String[][]{{"seconds", "integer", "Length in seconds (1-86400)"}, {"label", "string", "Short label"}}, "seconds")));
        DEFS.add(new Def("get_weather",
                "Current weather and 3-day forecast. Leave place empty to use the phone's location.",
                schema(new String[][]{{"place", "string", "City or town in English, e.g. 'Hyderabad'. Empty for current location."}})));
        DEFS.add(new Def("open_app",
                "Open an installed app by its name, e.g. 'WhatsApp', 'YouTube', 'PhonePe', 'Camera'.",
                schema(new String[][]{{"app", "string", "App name"}}, "app")));
        DEFS.add(new Def("open_maps",
                "Show a place in Google Maps, or start navigation to it.",
                schema(new String[][]{{"place", "string", "Place or address"}, {"navigate", "boolean", "true to start turn-by-turn navigation"}}, "place")));
        DEFS.add(new Def("play_youtube",
                "Open YouTube search results for songs, videos or channels.",
                schema(new String[][]{{"query", "string", "What to search on YouTube"}}, "query")));
        DEFS.add(new Def("flashlight",
                "Turn the phone's flashlight on or off.",
                schema(new String[][]{{"on", "boolean", "true = on, false = off"}}, "on")));
        DEFS.add(new Def("device_status",
                "Battery level, charging state and the current date and time.",
                schema(new String[][]{})));
        DEFS.add(new Def("save_memory",
                "Save one lasting fact about Anil (a preference, a person, a date, a plan) to his permanent memory.",
                schema(new String[][]{{"text", "string", "The fact as one short Telugu sentence"}}, "text")));
        DEFS.add(new Def("forget_memory",
                "Delete one saved memory by its id (ids are in the system prompt). Only when Anil asks to forget something.",
                schema(new String[][]{{"id", "string", "Memory id"}}, "id")));
        DEFS.add(new Def("add_mission",
                "Add a task or goal to Anil's mission list.",
                schema(new String[][]{{"text", "string", "The mission as a short Telugu phrase, with any date or time he said"}}, "text")));
        DEFS.add(new Def("complete_mission",
                "Mark one mission as done by its id (ids are in the system prompt).",
                schema(new String[][]{{"id", "string", "Mission id"}}, "id")));
    }

    JSONArray openAiTools() throws Exception {
        JSONArray a = new JSONArray();
        for (Def d : DEFS) {
            a.put(new JSONObject().put("type", "function").put("name", d.name)
                    .put("description", d.description).put("parameters", d.params));
        }
        return a;
    }

    JSONArray anthropicTools() throws Exception {
        JSONArray a = new JSONArray();
        for (Def d : DEFS) {
            a.put(new JSONObject().put("name", d.name).put("description", d.description).put("input_schema", d.params));
        }
        return a;
    }

    /** Short Telugu status line shown while a tool runs. */
    static String statusFor(String tool) {
        switch (tool) {
            case "call_contact": return "కాల్ సిద్ధం చేస్తున్నాను…";
            case "find_contact": return "కాంటాక్ట్స్‌లో వెతుకుతున్నాను…";
            case "send_sms": return "మెసేజ్ సిద్ధం చేస్తున్నాను…";
            case "whatsapp_message": return "WhatsApp తెరుస్తున్నాను…";
            case "set_alarm": return "అలారం పెడుతున్నాను…";
            case "set_timer": return "టైమర్ పెడుతున్నాను…";
            case "get_weather": return "వాతావరణం చూస్తున్నాను…";
            case "open_app": case "open_maps": case "play_youtube": return "తెరుస్తున్నాను…";
            case "save_memory": return "గుర్తుంచుకుంటున్నాను…";
            case "add_mission": return "మిషన్ జోడిస్తున్నాను…";
            default: return "పని చేస్తున్నాను…";
        }
    }

    // ================================================================ dispatch

    String execute(String name, JSONObject a) {
        try {
            switch (name) {
                case "call_contact": return call(a.optString("who"));
                case "find_contact": return findContact(a.optString("name"));
                case "send_sms": return sms(a.optString("who"), a.optString("message"));
                case "whatsapp_message": return whatsapp(a.optString("who"), a.optString("message"));
                case "set_alarm": return alarm(a.optInt("hour", -1), a.optInt("minute", -1), a.optString("label", "Jarvis"));
                case "set_timer": return timer(a.optInt("seconds", 0), a.optString("label", "Jarvis"));
                case "get_weather": return weather(a.optString("place", ""));
                case "open_app": return openApp(a.optString("app"));
                case "open_maps": return maps(a.optString("place"), a.optBoolean("navigate", false));
                case "play_youtube": return youtube(a.optString("query"));
                case "flashlight": return flashlight(a.optBoolean("on", true));
                case "device_status": return deviceStatus();
                case "save_memory": {
                    JSONObject m = store.addMemory(a.optString("text"));
                    if (m == null) return err("empty", "Nothing to save.");
                    host.notice("గుర్తుంచుకున్నాను");
                    return ok().put("saved", true).put("id", m.optString("id")).toString();
                }
                case "forget_memory": {
                    JSONObject m = store.removeMemory(a.optString("id"));
                    if (m == null) return err("not_found", "No memory with that id.");
                    host.notice("మర్చిపోయాను");
                    return ok().put("forgotten", m.optString("text")).toString();
                }
                case "add_mission": {
                    JSONObject m = store.addMission(a.optString("text"));
                    if (m == null) return err("empty", "Nothing to add.");
                    host.notice("మిషన్ జోడించాను");
                    return ok().put("added", true).put("id", m.optString("id")).toString();
                }
                case "complete_mission": {
                    JSONObject m = store.setMissionDone(a.optString("id"), true);
                    if (m == null) return err("not_found", "No mission with that id.");
                    host.notice("మిషన్ పూర్తి");
                    return ok().put("completed", m.optString("text")).toString();
                }
                default:
                    return err("unknown_tool", name);
            }
        } catch (Exception e) {
            return err("failed", String.valueOf(e.getMessage()));
        }
    }

    // ================================================================ helpers

    private static JSONObject ok() throws Exception { return new JSONObject().put("ok", true); }

    private static String err(String code, String detail) {
        try {
            return new JSONObject().put("ok", false).put("error", code).put("detail", detail).toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    private Activity act() { return host.activity(); }

    private boolean has(String perm) {
        return act().checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private String needPermission(String perm, String what) {
        host.askPermissions(new String[]{perm});
        return err("permission_needed", "Anil must allow " + what + " for Jarvis. A permission prompt was shown; ask him to allow it and try again.");
    }

    private void onUi(Runnable r) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        final RuntimeException[] fail = new RuntimeException[1];
        act().runOnUiThread(() -> {
            try { r.run(); } catch (RuntimeException e) { fail[0] = e; } finally { done.countDown(); }
        });
        done.await(10, TimeUnit.SECONDS);
        if (fail[0] != null) throw fail[0];
    }

    private void start(Intent i) throws InterruptedException {
        onUi(() -> act().startActivity(i));
    }

    /** If the phone is locked, ask Anil to unlock first. Returns true when it is safe to continue. */
    private boolean unlocked() throws InterruptedException {
        KeyguardManager km = (KeyguardManager) act().getSystemService(Activity.KEYGUARD_SERVICE);
        if (km == null || !km.isKeyguardLocked()) return true;
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);
        act().runOnUiThread(() -> km.requestDismissKeyguard(act(), new KeyguardManager.KeyguardDismissCallback() {
            @Override public void onDismissSucceeded() { ok.set(true); done.countDown(); }
            @Override public void onDismissCancelled() { done.countDown(); }
            @Override public void onDismissError() { done.countDown(); }
        }));
        done.await(45, TimeUnit.SECONDS);
        return ok.get();
    }

    private static boolean looksLikeNumber(String s) {
        return s != null && s.replaceAll("[\\s\\-()]", "").matches("\\+?\\d{3,15}");
    }

    private static final class Contact {
        final String name, number;
        final int type;
        Contact(String name, String number, int type) { this.name = name; this.number = number; this.type = type; }
    }

    private List<Contact> queryContacts(String q) {
        List<Contact> out = new ArrayList<>();
        Map<String, Contact> seen = new LinkedHashMap<>();
        String[] cols = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE};
        try (Cursor c = act().getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", new String[]{"%" + q + "%"},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            while (c != null && c.moveToNext()) {
                String name = c.getString(0), num = c.getString(1);
                int type = c.getInt(2);
                if (num == null) continue;
                if (name == null) name = num;
                String key = digits(num);
                if (key.length() > 10) key = key.substring(key.length() - 10);
                if (!seen.containsKey(key)) seen.put(key, new Contact(name, num, type));
                if (seen.size() >= 12) break;
            }
        }
        out.addAll(seen.values());
        return out;
    }

    private List<Contact> matches(String who) {
        String q = who.trim();
        List<Contact> list = queryContacts(q);
        if (list.isEmpty() && q.contains(" ")) list = queryContacts(q.split("\\s+")[0]);
        // Prefer exact name matches when there are several.
        List<Contact> exact = new ArrayList<>();
        for (Contact c : list) if (c.name != null && c.name.trim().equalsIgnoreCase(q)) exact.add(c);
        if (!exact.isEmpty()) list = exact;
        // Same person with several numbers: prefer the mobile number.
        boolean samePerson = !list.isEmpty();
        for (Contact c : list) if (!c.name.equalsIgnoreCase(list.get(0).name)) { samePerson = false; break; }
        if (samePerson && list.size() > 1) {
            for (Contact c : list) {
                if (c.type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) {
                    List<Contact> one = new ArrayList<>();
                    one.add(c);
                    return one;
                }
            }
            return list.subList(0, 1);
        }
        return list;
    }

    private static String digits(String s) { return s.replaceAll("[^0-9]", ""); }

    /** Result of resolving "who": either a single contact or an error JSON to hand back. */
    private static final class Target {
        Contact contact;
        String error;
    }

    private Target resolve(String who) throws Exception {
        Target t = new Target();
        if (who == null || who.trim().isEmpty()) { t.error = err("missing", "Who should I contact?"); return t; }
        if (looksLikeNumber(who)) { t.contact = new Contact(who.trim(), who.trim(), 0); return t; }
        if (!has(Manifest.permission.READ_CONTACTS)) { t.error = needPermission(Manifest.permission.READ_CONTACTS, "reading contacts"); return t; }
        List<Contact> m = matches(who);
        if (m.isEmpty()) {
            t.error = err("not_found", "No contact matching '" + who + "'. Ask Anil for the exact saved name or the number.");
        } else if (m.size() > 1) {
            JSONArray arr = new JSONArray();
            for (Contact c : m) arr.put(new JSONObject().put("name", c.name).put("number", c.number));
            t.error = new JSONObject().put("ok", false).put("error", "ambiguous")
                    .put("detail", "Several contacts match. Ask Anil which one.").put("matches", arr).toString();
        } else {
            t.contact = m.get(0);
        }
        return t;
    }

    // ================================================================ tools

    private String findContact(String name) throws Exception {
        if (!has(Manifest.permission.READ_CONTACTS)) return needPermission(Manifest.permission.READ_CONTACTS, "reading contacts");
        List<Contact> list = queryContacts(name.trim());
        JSONArray arr = new JSONArray();
        for (Contact c : list) arr.put(new JSONObject().put("name", c.name).put("number", c.number));
        return ok().put("matches", arr).toString();
    }

    private String call(String who) throws Exception {
        if (!has(Manifest.permission.CALL_PHONE)) return needPermission(Manifest.permission.CALL_PHONE, "phone calls");
        Target t = resolve(who);
        if (t.error != null) return t.error;
        if (!unlocked()) return err("locked", "The phone is locked and Anil did not unlock it.");
        Contact c = t.contact;
        String label = c.name.equals(c.number) ? c.number : c.name + "\n" + c.number;
        if (!host.confirm("కాల్ చేస్తున్నాను", label, "ఇప్పుడే కాల్", 4)) return err("cancelled", "Anil cancelled the call.");
        Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(c.number)));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start(i);
        return ok().put("calling", c.name).put("number", c.number).toString();
    }

    private String sms(String who, String message) throws Exception {
        if (message == null || message.trim().isEmpty()) return err("missing", "What should the message say?");
        if (!has(Manifest.permission.SEND_SMS)) return needPermission(Manifest.permission.SEND_SMS, "sending SMS");
        Target t = resolve(who);
        if (t.error != null) return t.error;
        if (!unlocked()) return err("locked", "The phone is locked and Anil did not unlock it.");
        Contact c = t.contact;
        String to = c.name.equals(c.number) ? c.number : c.name + " (" + c.number + ")";
        if (!host.confirm("SMS పంపాలా?", "ఎవరికి: " + to + "\n\n" + message, "పంపు", 0)) return err("cancelled", "Anil cancelled the SMS.");
        SmsManager sm = Build.VERSION.SDK_INT >= 31 ? act().getSystemService(SmsManager.class) : SmsManager.getDefault();
        ArrayList<String> parts = sm.divideMessage(message);
        sm.sendMultipartTextMessage(c.number, null, parts, null, null);
        return ok().put("sent_to", c.name).toString();
    }

    private static String whatsappNumber(String number) {
        String plus = number.trim().startsWith("+") ? "+" : "";
        String d = digits(number);
        if (!plus.isEmpty()) return d;
        if (d.length() == 11 && d.startsWith("0")) return "91" + d.substring(1);
        if (d.length() == 10) return "91" + d;
        return d;
    }

    private String whatsapp(String who, String message) throws Exception {
        Target t = resolve(who);
        if (t.error != null) return t.error;
        if (!unlocked()) return err("locked", "The phone is locked and Anil did not unlock it.");
        String url = "https://wa.me/" + whatsappNumber(t.contact.number) + "?text=" + URLEncoder.encode(message == null ? "" : message, "UTF-8");
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PackageManager pm = act().getPackageManager();
        for (String pkg : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) {
            if (pm.getLaunchIntentForPackage(pkg) != null) { i.setPackage(pkg); break; }
        }
        try {
            start(i);
        } catch (ActivityNotFoundException e) {
            return err("no_whatsapp", "WhatsApp is not installed.");
        }
        return ok().put("opened_whatsapp_for", t.contact.name).put("note", "Anil must tap send in WhatsApp.").toString();
    }

    private String alarm(int hour, int minute, String label) throws Exception {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return err("bad_time", "Need hour 0-23 and minute 0-59.");
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label == null || label.isEmpty() ? "Jarvis" : label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            start(i);
        } catch (ActivityNotFoundException e) {
            return err("no_clock_app", "No clock app accepts alarms on this phone.");
        }
        return ok().put("alarm", String.format(Locale.ENGLISH, "%02d:%02d", hour, minute)).toString();
    }

    private String timer(int seconds, String label) throws Exception {
        if (seconds < 1 || seconds > 86400) return err("bad_length", "Timer must be 1 second to 24 hours.");
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label == null || label.isEmpty() ? "Jarvis" : label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            start(i);
        } catch (ActivityNotFoundException e) {
            return err("no_clock_app", "No clock app accepts timers on this phone.");
        }
        return ok().put("timer_seconds", seconds).toString();
    }

    private String weather(String place) throws Exception {
        double lat, lon;
        String where;
        if (place != null && !place.trim().isEmpty()) {
            JSONObject g = Http.get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name="
                    + URLEncoder.encode(place.trim(), "UTF-8"));
            JSONArray res = g.optJSONArray("results");
            if (res == null || res.length() == 0) return err("place_not_found", "Could not find '" + place + "'. Try an English spelling.");
            JSONObject r = res.getJSONObject(0);
            lat = r.getDouble("latitude");
            lon = r.getDouble("longitude");
            where = r.optString("name") + ", " + r.optString("admin1") + ", " + r.optString("country");
        } else {
            if (!has(Manifest.permission.ACCESS_COARSE_LOCATION)) return needPermission(Manifest.permission.ACCESS_COARSE_LOCATION, "location for local weather");
            Location loc = lastLocation();
            if (loc == null) return err("no_location", "Phone location is not known yet. Ask Anil which city.");
            lat = loc.getLatitude();
            lon = loc.getLongitude();
            where = "current location";
        }
        String url = String.format(Locale.ENGLISH,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                        + "&timezone=auto&forecast_days=3", lat, lon);
        JSONObject w = Http.get(url);
        JSONObject cur = w.optJSONObject("current");
        JSONObject out = ok().put("place", where);
        if (cur != null) {
            out.put("now", new JSONObject()
                    .put("temp_c", cur.opt("temperature_2m"))
                    .put("feels_like_c", cur.opt("apparent_temperature"))
                    .put("humidity_pct", cur.opt("relative_humidity_2m"))
                    .put("rain_mm", cur.opt("precipitation"))
                    .put("wind_kmh", cur.opt("wind_speed_10m"))
                    .put("sky", sky(cur.optInt("weather_code", -1))));
        }
        JSONObject d = w.optJSONObject("daily");
        if (d != null) {
            JSONArray days = new JSONArray();
            JSONArray dates = d.optJSONArray("time");
            for (int i = 0; dates != null && i < dates.length(); i++) {
                days.put(new JSONObject()
                        .put("date", dates.optString(i))
                        .put("max_c", d.optJSONArray("temperature_2m_max").opt(i))
                        .put("min_c", d.optJSONArray("temperature_2m_min").opt(i))
                        .put("rain_chance_pct", d.optJSONArray("precipitation_probability_max").opt(i))
                        .put("sky", sky(d.optJSONArray("weather_code").optInt(i, -1))));
            }
            out.put("forecast", days);
        }
        return out.toString();
    }

    private Location lastLocation() {
        LocationManager lm = (LocationManager) act().getSystemService(Activity.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException e) {
            return null;
        }
        return best;
    }

    private static String sky(int code) {
        if (code == 0) return "clear sky";
        if (code <= 2) return "partly cloudy";
        if (code == 3) return "overcast";
        if (code == 45 || code == 48) return "fog";
        if (code >= 51 && code <= 57) return "drizzle";
        if (code >= 61 && code <= 67) return "rain";
        if (code >= 71 && code <= 77) return "snow";
        if (code >= 80 && code <= 82) return "rain showers";
        if (code >= 95) return "thunderstorm";
        return "unknown";
    }

    private String openApp(String name) throws Exception {
        if (name == null || name.trim().isEmpty()) return err("missing", "Which app?");
        String q = name.trim().toLowerCase(Locale.ROOT);
        PackageManager pm = act().getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo r : apps) {
            String label = String.valueOf(r.loadLabel(pm)).toLowerCase(Locale.ROOT);
            int score = label.equals(q) ? 3 : label.startsWith(q) ? 2 : (label.contains(q) || q.contains(label)) ? 1 : 0;
            if (score > bestScore) { bestScore = score; best = r; }
        }
        if (best == null) return err("not_found", "No installed app called '" + name + "'.");
        Intent launch = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
        if (launch == null) return err("cannot_open", "That app cannot be opened directly.");
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start(launch);
        return ok().put("opened", String.valueOf(best.loadLabel(pm))).toString();
    }

    private String maps(String place, boolean navigate) throws Exception {
        if (place == null || place.trim().isEmpty()) return err("missing", "Which place?");
        String enc = Uri.encode(place.trim());
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(navigate ? "google.navigation:q=" + enc : "geo:0,0?q=" + enc));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            start(i);
        } catch (ActivityNotFoundException e) {
            Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + enc));
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            start(web);
        }
        return ok().put(navigate ? "navigating_to" : "showing", place).toString();
    }

    private String youtube(String query) throws Exception {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start(i);
        return ok().put("youtube_search", query).toString();
    }

    private String flashlight(boolean on) throws Exception {
        CameraManager cm = (CameraManager) act().getSystemService(Activity.CAMERA_SERVICE);
        if (cm == null) return err("no_camera", "No camera service.");
        for (String id : cm.getCameraIdList()) {
            Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (flash != null && flash) {
                cm.setTorchMode(id, on);
                return ok().put("flashlight", on ? "on" : "off").toString();
            }
        }
        return err("no_flash", "This phone has no flashlight.");
    }

    private String deviceStatus() throws Exception {
        BatteryManager bm = (BatteryManager) act().getSystemService(Activity.BATTERY_SERVICE);
        JSONObject o = ok();
        if (bm != null) {
            o.put("battery_pct", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
            o.put("charging", bm.isCharging());
        }
        o.put("now", new java.text.SimpleDateFormat("EEEE d MMMM yyyy HH:mm", Locale.ENGLISH).format(new java.util.Date()));
        return o.toString();
    }
}
