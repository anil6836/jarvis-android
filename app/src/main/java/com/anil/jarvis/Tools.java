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
    private final Prefs prefs;

    Tools(Host host, Store store, Prefs prefs) {
        this.host = host;
        this.store = store;
        this.prefs = prefs;
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
        DEFS.add(new Def("close_app",
                "Close an app completely: stops what it is playing and force-stops it (the phone's App info page flashes for a second while Jarvis presses Force stop), so nothing keeps running in the background. Leave app empty to close the app Anil is using right now.",
                schema(new String[][]{{"app", "string", "App name, e.g. 'YouTube'. Empty = the app currently on screen."}})));
        DEFS.add(new Def("open_maps",
                "Show a place in Google Maps, or start navigation to it.",
                schema(new String[][]{{"place", "string", "Place or address"}, {"navigate", "boolean", "true to start turn-by-turn navigation"}}, "place")));
        DEFS.add(new Def("play_youtube",
                "Play a song, music or a video. It plays in the app Anil names (YouTube, YouTube Music, Spotify, JioSaavn, Gaana, Wynk, Amazon Music or any other installed app) and starts playing by itself (in YouTube Music it plays the song itself, not the music video); with no app named it plays the top YouTube video.",
                schema(new String[][]{{"query", "string", "What to play, e.g. 'Ghantasala old songs' or a film song name with the film"},
                        {"app", "string", "The app he named, in English letters (e.g. 'YouTube Music', 'Spotify', 'JioSaavn'). Empty when he did not name one."}}, "query")));
        DEFS.add(new Def("flashlight",
                "Turn the phone's flashlight on or off.",
                schema(new String[][]{{"on", "boolean", "true = on, false = off"}}, "on")));
        DEFS.add(new Def("device_status",
                "Battery level, charging state and the current date and time.",
                schema(new String[][]{})));
        DEFS.add(new Def("read_notifications",
                "Read the latest messages that arrived as phone notifications (WhatsApp, SMS, Telegram, Instagram, email apps and others), newest first. Each has an id, app, sender and text, and whether it can be replied to.",
                schema(new String[][]{{"app", "string", "Optional app filter, e.g. 'WhatsApp', 'Messages', 'Telegram'. Empty for all apps."},
                        {"limit", "integer", "How many to return (default 8, max 20)"}})));
        DEFS.add(new Def("reply_to_notification",
                "Reply to one of the notifications from read_notifications using its reply button (works for WhatsApp, SMS, Telegram and most chat apps). The app asks Anil to confirm before sending.",
                schema(new String[][]{{"id", "integer", "Notification id from read_notifications"}, {"message", "string", "The reply text"}}, "id", "message")));
        DEFS.add(new Def("set_reminder",
                "Remind Anil about something at a date and time (he gets a notification and Jarvis says it aloud). Use this for 'remind me' / గుర్తుచేయి requests, not set_alarm.",
                schema(new String[][]{{"text", "string", "What to remind him about, short Telugu phrase"},
                        {"when", "string", "Local date and time 'yyyy-MM-dd HH:mm' (compute it from the current date/time in the system prompt)"}}, "text", "when")));
        DEFS.add(new Def("list_reminders", "List Anil's upcoming reminders with their ids.", schema(new String[][]{})));
        DEFS.add(new Def("cancel_reminder", "Cancel one reminder by id (from list_reminders).",
                schema(new String[][]{{"id", "string", "Reminder id"}}, "id")));
        DEFS.add(new Def("calendar_events",
                "Read events from the phone's calendar (Google Calendar) starting today.",
                schema(new String[][]{{"days", "integer", "How many days from today, 1 = today only (max 14)"}})));
        DEFS.add(new Def("add_calendar_event",
                "Add an event to Anil's calendar (with a 15-minute alert).",
                schema(new String[][]{{"title", "string", "Event title"}, {"start", "string", "Local start 'yyyy-MM-dd HH:mm'"},
                        {"minutes", "integer", "Duration in minutes (default 60)"}, {"location", "string", "Optional place"}}, "title", "start")));
        DEFS.add(new Def("send_email",
                "Write an email and open it in Gmail ready to send (Anil taps send). 'to' can be an email address or a contact name.",
                schema(new String[][]{{"to", "string", "Email address or contact name"}, {"subject", "string", "Subject"},
                        {"body", "string", "Email body, in the language he asked for"}}, "to", "subject", "body")));
        DEFS.add(new Def("media_control",
                "Control music/video that is playing on the phone and the media volume.",
                schema(new String[][]{{"action", "string", "One of: play, pause, toggle, next, previous, volume_up, volume_down, set_volume, mute, unmute"},
                        {"percent", "integer", "Volume 0-100, only for set_volume"}}, "action")));
        DEFS.add(new Def("now_playing", "Which song or video is playing now, and in which app.", schema(new String[][]{})));
        DEFS.add(new Def("look_at_screen",
                "Look at what is on Anil's phone screen (the app he was using when he called Jarvis) and answer a question about it, e.g. 'what is on my screen', 'what should I reply to this message', 'explain this'.",
                schema(new String[][]{{"question", "string", "What Anil wants to know about the screen"}}, "question")));
        DEFS.add(new Def("look_through_camera",
                "Look through the live camera (when Anil has it open in Jarvis) and answer a question about what it sees.",
                schema(new String[][]{{"question", "string", "What Anil wants to know"}}, "question")));
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

    /** Extra tools for the live (real-time) voice session, which has no built-in web search. */
    static JSONArray liveOnlyTools() throws Exception {
        JSONArray a = new JSONArray();
        a.put(new JSONObject().put("type", "function").put("name", "web_search")
                .put("description", "Search the internet for current information (news, cricket scores, prices, film releases, anything that changes). Returns a short summary.")
                .put("parameters", schema(new String[][]{{"query", "string", "What to search for, in English"}}, "query")));
        a.put(new JSONObject().put("type", "function").put("name", "end_conversation")
                .put("description", "End the live voice conversation. Call it when Anil says goodbye, is done, or asks you to stop listening (for example 'bye', 'చాలు', 'ఆపు', 'సరే Jarvis, అంతే'). Say a short goodbye first.")
                .put("parameters", schema(new String[][]{})));
        return a;
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
            case "open_app": case "open_maps": return "తెరుస్తున్నాను…";
            case "close_app": return "మూసేస్తున్నాను…";
            case "play_youtube": return "ప్లే చేస్తున్నాను…";
            case "save_memory": return "గుర్తుంచుకుంటున్నాను…";
            case "read_notifications": return "మెసేజ్‌లు చూస్తున్నాను…";
            case "reply_to_notification": return "రిప్లై సిద్ధం చేస్తున్నాను…";
            case "web_search": return "ఇంటర్నెట్‌లో వెతుకుతున్నాను…";
            case "set_reminder": return "రిమైండర్ పెడుతున్నాను…";
            case "calendar_events": case "add_calendar_event": return "క్యాలెండర్ చూస్తున్నాను…";
            case "send_email": return "మెయిల్ సిద్ధం చేస్తున్నాను…";
            case "media_control": case "now_playing": return "మ్యూజిక్…";
            case "look_at_screen": return "స్క్రీన్ చూస్తున్నాను…";
            case "look_through_camera": return "కెమెరాలో చూస్తున్నాను…";
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
                case "close_app": return closeApp(a.optString("app", ""));
                case "open_maps": return maps(a.optString("place"), a.optBoolean("navigate", false));
                case "play_youtube": return youtube(a.optString("query"), a.optString("app", ""));
                case "flashlight": return flashlight(a.optBoolean("on", true));
                case "device_status": return deviceStatus();
                case "read_notifications": return readNotifications(a.optString("app", ""), a.optInt("limit", 8));
                case "reply_to_notification": return replyNotification(a.optInt("id", -1), a.optString("message"));
                case "web_search": return webSearch(a.optString("query"));
                case "set_reminder": return setReminder(a.optString("text"), a.optString("when"));
                case "list_reminders": return listReminders();
                case "cancel_reminder": return cancelReminder(a.optString("id"));
                case "calendar_events": return calendarEvents(a.optInt("days", 1));
                case "add_calendar_event": return addCalendarEvent(a.optString("title"), a.optString("start"), a.optInt("minutes", 60), a.optString("location", ""));
                case "send_email": return sendEmail(a.optString("to"), a.optString("subject"), a.optString("body"));
                case "media_control": return mediaControl(a.optString("action"), a.optInt("percent", 50));
                case "now_playing": return nowPlaying();
                case "look_at_screen": return lookAtScreen(a.optString("question"));
                case "look_through_camera": return lookThroughCamera(a.optString("question"));
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
        if ((place == null || place.trim().isEmpty()) && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return needPermission(Manifest.permission.ACCESS_COARSE_LOCATION, "location for local weather");
        }
        return weatherJson(act(), place);
    }

    /** Weather as JSON for a place, or for the phone's last known location when place is empty. */
    static String weatherJson(android.content.Context ctx, String place) throws Exception {
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
            Location loc = lastLocation(ctx);
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

    private static Location lastLocation(android.content.Context ctx) {
        LocationManager lm = (LocationManager) ctx.getSystemService(Activity.LOCATION_SERVICE);
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

    /** The installed app whose name best matches, or null. */
    private ResolveInfo findApp(String name) {
        if (name == null || name.trim().isEmpty()) return null;
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
        return best;
    }

    private String closeApp(String name) throws Exception {
        String pkg;
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.equalsIgnoreCase("this") || n.contains("ఈ")) {
            pkg = JarvisAccessibility.currentPackage();
            if (pkg == null || pkg.isEmpty()) return err("which_app", "Which app should I close? Ask Anil for the app name.");
        } else {
            ResolveInfo r = findApp(n);
            if (r == null) return err("not_found", "No installed app called '" + n + "'.");
            pkg = r.activityInfo.packageName;
        }
        if (pkg.equals(act().getPackageName())) return err("self", "That is Jarvis itself; Anil can close it with the back button.");
        String name2 = label(pkg);

        // 1) stop anything it is playing (so it does not keep going in the background)
        boolean mediaStopped = false;
        if (NotifyListener.enabled(act())) {
            try {
                android.media.session.MediaSessionManager msm = act().getSystemService(android.media.session.MediaSessionManager.class);
                for (android.media.session.MediaController mc : msm.getActiveSessions(new android.content.ComponentName(act(), NotifyListener.class))) {
                    if (pkg.equals(mc.getPackageName())) { mc.getTransportControls().stop(); mediaStopped = true; }
                }
            } catch (Exception ignored) {}
        } else {
            for (String[] m : MUSIC_APPS) {
                if (m[1].equals(pkg)) {
                    android.media.AudioManager am = act().getSystemService(android.media.AudioManager.class);
                    if (am != null) { mediaKey(am, android.view.KeyEvent.KEYCODE_MEDIA_STOP); mediaStopped = true; }
                    break;
                }
            }
            if (!mediaStopped && pkg.equals(YT)) {
                android.media.AudioManager am = act().getSystemService(android.media.AudioManager.class);
                if (am != null) { mediaKey(am, android.view.KeyEvent.KEYCODE_MEDIA_STOP); mediaStopped = true; }
            }
        }

        // 2) close it completely with "Force stop" (needs Jarvis's accessibility switch)
        String why;
        if (JarvisAccessibility.enabled()) {
            if (unlocked()) {
                String r = JarvisAccessibility.forceStop(pkg);
                if ("stopped".equals(r) || "already_stopped".equals(r)) {
                    return ok().put("closed", name2).put("fully_closed", true).put("playback_stopped", mediaStopped).toString();
                }
                why = "The automatic Force stop did not go through (" + r + ").";
            } else {
                why = "The phone is locked, so the Force stop screen could not be opened.";
            }
        } else {
            why = "For a complete close (Force stop), Anil must switch on Jarvis under Settings > Accessibility once (Jarvis settings > 'స్క్రీన్ చూడటం' has the button).";
        }

        // 3) fallback: if it is on screen (Jarvis working in the background), leave it with Home
        boolean wentHome = false;
        if (!MainActivity.visible && pkg.equals(JarvisAccessibility.currentPackage())) {
            final boolean[] ok = {false};
            onUi(() -> ok[0] = JarvisAccessibility.goHome());
            wentHome = ok[0];
        }

        // and clear it from memory once it is in the background
        Thread.sleep(800);
        android.app.ActivityManager am = act().getSystemService(android.app.ActivityManager.class);
        if (am != null) am.killBackgroundProcesses(pkg);

        return ok().put("closed", name2).put("fully_closed", false).put("playback_stopped", mediaStopped).put("left_screen", wentHome)
                .put("note", why + " It was stopped and cleared from memory, but may still run a background service.")
                .toString();
    }

    private String openApp(String name) throws Exception {
        if (name == null || name.trim().isEmpty()) return err("missing", "Which app?");
        PackageManager pm = act().getPackageManager();
        ResolveInfo best = findApp(name);
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

    private static final String YT = "com.google.android.youtube";
    private static final String YT_MUSIC = "com.google.android.apps.youtube.music";
    private static final String SPOTIFY = "com.spotify.music";

    private boolean installed(String pkg) {
        return act().getPackageManager().getLaunchIntentForPackage(pkg) != null;
    }

    /** Asks a music app to find and start playing something by itself (the same way Google Assistant does). */
    private boolean playFromSearch(String pkg, String query) {
        Intent i = new Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .setPackage(pkg)
                .putExtra(android.app.SearchManager.QUERY, query)
                .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            start(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Finds the top YouTube video for a search (reads the public results page). */
    private static String topVideoId(String query) {
        try {
            String html = Http.getText("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8"));
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"").matcher(html);
            if (m.find()) return m.group(1);
            m = java.util.regex.Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"").matcher(html);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return null;
    }

    /** Well-known music apps by the names people say. */
    private static final String[][] MUSIC_APPS = {
            {"youtube music", YT_MUSIC}, {"yt music", YT_MUSIC}, {"spotify", SPOTIFY},
            {"jiosaavn", "com.jio.media.jiobeats"}, {"saavn", "com.jio.media.jiobeats"},
            {"gaana", "com.gaana"}, {"wynk", "com.bsbportal.music"}, {"amazon music", "com.amazon.mp3"},
            {"apple music", "com.apple.android.music"}, {"resso", "com.moonvideo.android.resso"},
            {"hungama", "com.hungama.myplay.activity"}, {"soundcloud", "com.soundcloud.android"},
            // the same names written in Telugu
            {"యూట్యూబ్ మ్యూజిక్", YT_MUSIC}, {"యూట్యూబ్ మ్యూసిక్", YT_MUSIC}, {"స్పాటిఫై", SPOTIFY},
            {"జియోసావన్", "com.jio.media.jiobeats"}, {"జియో సావన్", "com.jio.media.jiobeats"}, {"సావన్", "com.jio.media.jiobeats"},
            {"గానా", "com.gaana"}, {"వింక్", "com.bsbportal.music"}};

    private boolean supportsPlayFromSearch(String pkg) {
        Intent i = new Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg);
        return !act().getPackageManager().queryIntentActivities(i, 0).isEmpty();
    }

    private String label(String pkg) {
        try {
            PackageManager pm = act().getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    private boolean locked() {
        KeyguardManager km = (KeyguardManager) act().getSystemService(Activity.KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    /**
     * Starts a song inside a music app without opening its screen, the way Android Auto and
     * Google Assistant do, so it works while the phone stays locked. Returns true once it plays.
     */
    private boolean playWithoutScreen(String pkg, String query, Uri song) throws InterruptedException {
        android.media.session.MediaController mc = null;
        final android.media.browse.MediaBrowser[] browser = {null};
        if (NotifyListener.enabled(act())) {
            try {
                android.media.session.MediaSessionManager msm = act().getSystemService(android.media.session.MediaSessionManager.class);
                for (android.media.session.MediaController c : msm.getActiveSessions(new android.content.ComponentName(act(), NotifyListener.class))) {
                    if (pkg.equals(c.getPackageName())) { mc = c; break; }
                }
            } catch (Exception ignored) {}
        }
        if (mc == null) {
            List<ResolveInfo> svc = act().getPackageManager().queryIntentServices(
                    new Intent(android.service.media.MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0);
            if (svc == null || svc.isEmpty()) return false;
            android.content.ComponentName cn = new android.content.ComponentName(pkg, svc.get(0).serviceInfo.name);
            CountDownLatch connected = new CountDownLatch(1);
            onUi(() -> {
                browser[0] = new android.media.browse.MediaBrowser(act(), cn, new android.media.browse.MediaBrowser.ConnectionCallback() {
                    @Override public void onConnected() { connected.countDown(); }
                    @Override public void onConnectionFailed() { connected.countDown(); }
                }, null);
                browser[0].connect();
            });
            connected.await(4, TimeUnit.SECONDS);
            if (browser[0] == null || !browser[0].isConnected()) {
                if (browser[0] != null) onUi(browser[0]::disconnect);
                return false;
            }
            mc = new android.media.session.MediaController(act(), browser[0].getSessionToken());
        }
        try {
            // The same request Google Assistant sends: "play <song>" to the app's player.
            android.os.Bundle extras = new android.os.Bundle();
            extras.putString(android.app.SearchManager.QUERY, query);
            extras.putString(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio");
            final android.media.session.MediaController player = mc;
            if (startsPlaying(player, () -> mc2(player).playFromSearch(query, extras), 4500)) return true;
            // Some players take the song's link instead.
            if (song != null && startsPlaying(player, () -> mc2(player).playFromUri(song, new android.os.Bundle()), 3500)) return true;
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            android.media.browse.MediaBrowser b = browser[0];
            if (b != null) new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(b::disconnect, 30000);
        }
    }

    private static android.media.session.MediaController.TransportControls mc2(android.media.session.MediaController mc) {
        return mc.getTransportControls();
    }

    /** Sends a request to a player and waits up to ms for it to start playing something new. */
    private static boolean startsPlaying(android.media.session.MediaController mc, Runnable request, long ms) throws InterruptedException {
        android.media.session.PlaybackState before = mc.getPlaybackState();
        boolean wasPlaying = before != null && before.getState() == android.media.session.PlaybackState.STATE_PLAYING;
        String oldTitle = title(mc);
        try { request.run(); } catch (Exception e) { return false; }
        long end = android.os.SystemClock.elapsedRealtime() + ms;
        boolean left = false;
        while (android.os.SystemClock.elapsedRealtime() < end) {
            Thread.sleep(300);
            android.media.session.PlaybackState st = mc.getPlaybackState();
            boolean playing = st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING;
            if (!playing) left = true;
            if (playing && (!wasPlaying || left || !String.valueOf(title(mc)).equals(String.valueOf(oldTitle)))) return true;
        }
        return false;
    }

    private static String title(android.media.session.MediaController mc) {
        android.media.MediaMetadata m = mc.getMetadata();
        return m == null ? null : m.getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
    }

    private static final String YTM_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";
    private static volatile String ytmVisitor;

    /**
     * The top "song" on YouTube Music for a search: the audio version (the Song tab), not the
     * music video. Uses YouTube Music's own search, with the Songs filter.
     */
    static String ytMusicSongId(String query) {
        if (ytmVisitor == null) {
            String v = "";
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"")
                        .matcher(Http.getText("https://music.youtube.com/"));
                if (m.find()) v = m.group(1);
            } catch (Exception ignored) {}
            ytmVisitor = v;
        }
        String version = "1." + new java.text.SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new java.util.Date()) + ".01.00";
        for (String params : new String[]{"EgWKAQIIAWoMEA4QChADEAQQCRAF", null}) {
            try {
                JSONObject client = new JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", version)
                        .put("hl", "en").put("gl", "IN");
                JSONObject body = new JSONObject()
                        .put("context", new JSONObject().put("client", client).put("user", new JSONObject()))
                        .put("query", query);
                if (params != null) body.put("params", params);
                JSONObject res = ytmVisitor.isEmpty()
                        ? Http.post("https://music.youtube.com/youtubei/v1/search?alt=json&prettyPrint=false", body,
                                "User-Agent", YTM_UA, "Origin", "https://music.youtube.com", "Referer", "https://music.youtube.com/")
                        : Http.post("https://music.youtube.com/youtubei/v1/search?alt=json&prettyPrint=false", body,
                                "User-Agent", YTM_UA, "Origin", "https://music.youtube.com", "Referer", "https://music.youtube.com/",
                                "X-Goog-Visitor-Id", ytmVisitor);
                String id = firstSong(res, 0);
                if (id != null) return id;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** First watchEndpoint marked as a song (MUSIC_VIDEO_TYPE_ATV), in page order. */
    private static String firstSong(Object o, int depth) {
        if (depth > 60 || o == null) return null;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            JSONObject we = j.optJSONObject("watchEndpoint");
            if (we != null && we.has("videoId")) {
                JSONObject cfg = we.optJSONObject("watchEndpointMusicSupportedConfigs");
                JSONObject mc = cfg == null ? null : cfg.optJSONObject("watchEndpointMusicConfig");
                if (mc != null && "MUSIC_VIDEO_TYPE_ATV".equals(mc.optString("musicVideoType"))) return we.optString("videoId");
            }
            java.util.Iterator<String> keys = j.keys();
            while (keys.hasNext()) {
                String r = firstSong(j.opt(keys.next()), depth + 1);
                if (r != null) return r;
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                String r = firstSong(a.opt(i), depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static Uri ytMusicLink(String id) {
        return Uri.parse("https://music.youtube.com/watch?v=" + id);
    }

    /**
     * Opens the song in YouTube Music so it plays as a song (audio), not as the music video.
     * A plain search request only opens the app, so the song's own link is used.
     */
    private boolean playOnYtMusic(String q, String songId) throws InterruptedException {
        if (!installed(YT_MUSIC)) return false;
        String id = songId != null ? songId : ytMusicSongId(q);
        if (id == null) id = topVideoId(q); // last resort: may open as a video
        if (id == null) return false;
        Intent i = new Intent(Intent.ACTION_VIEW, ytMusicLink(id))
                .setPackage(YT_MUSIC)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            start(i);
        } catch (Exception e) {
            return false;
        }
        nudgePlay(YT_MUSIC);
        return true;
    }

    /**
     * Some apps open on the song but wait for a tap. Watches the app's player for a few seconds
     * and presses play on it if it is still paused (needs notification access to see the player).
     */
    private void nudgePlay(String pkg) {
        if (!NotifyListener.enabled(act())) return;
        android.content.Context app = act().getApplicationContext();
        new Thread(() -> {
            try {
                android.media.session.MediaSessionManager msm = app.getSystemService(android.media.session.MediaSessionManager.class);
                android.content.ComponentName cn = new android.content.ComponentName(app, NotifyListener.class);
                int still = 0;
                for (int tick = 0; tick < 12; tick++) {
                    Thread.sleep(1000);
                    android.media.session.MediaController mine = null;
                    for (android.media.session.MediaController c : msm.getActiveSessions(cn)) {
                        if (pkg.equals(c.getPackageName())) { mine = c; break; }
                    }
                    if (mine == null) continue;
                    android.media.session.PlaybackState st = mine.getPlaybackState();
                    int state = st == null ? android.media.session.PlaybackState.STATE_NONE : st.getState();
                    if (state == android.media.session.PlaybackState.STATE_PLAYING) return;
                    if (state == android.media.session.PlaybackState.STATE_BUFFERING
                            || state == android.media.session.PlaybackState.STATE_CONNECTING) { still = 0; continue; }
                    if (tick >= 2 && ++still >= 3) {
                        mine.getTransportControls().play();
                        return;
                    }
                }
            } catch (Exception ignored) {}
        }, "jarvis-play-nudge").start();
    }

    private static final String LOCKED_NOTE = "YouTube and YouTube Music (without Premium) pause by their own rule when the screen is locked or off; "
            + "for music with the screen off, Spotify, JioSaavn, Gaana or Wynk keep playing, or YouTube Premium.";

    private String youtube(String query, String app) throws Exception {
        if (query == null || query.trim().isEmpty()) return err("missing", "What should I play?");
        String q = query.trim();
        String a = app == null ? "" : app.trim().toLowerCase(Locale.ROOT);

        // Anil named an app other than plain YouTube: play inside that app.
        if (!a.isEmpty() && !a.equals("youtube") && !a.equals("yt") && !a.equals("యూట్యూబ్")) {
            String pkg = null;
            for (String[] m : MUSIC_APPS) if (a.contains(m[0]) && installed(m[1])) { pkg = m[1]; break; }
            if (pkg == null) {
                ResolveInfo r = findApp(a);
                if (r != null) pkg = r.activityInfo.packageName;
            }
            if (pkg == null) return err("app_not_installed", "'" + app + "' is not installed on this phone. Offer to play it on YouTube instead.");
            boolean lockedNow = !pkg.equals(YT) && locked();
            String songId = pkg.equals(YT_MUSIC) ? ytMusicSongId(q) : null;
            if (lockedNow) {
                // Phone locked: try to start the song without unlocking first, the way Google Assistant does.
                if (playWithoutScreen(pkg, q, songId == null ? null : ytMusicLink(songId))) {
                    return ok().put("playing_on", label(pkg)).put("query", q).put("phone_locked", true).toString();
                }
                if (!unlocked()) {
                    return err("locked", label(pkg) + " can only start this song after the phone is unlocked, and it was not unlocked. "
                            + "Tell Anil to unlock with fingerprint or PIN when asked, then ask again.");
                }
            }
            if (pkg.equals(YT)) {
                a = "youtube"; // fall through to the YouTube video path below
            } else if (pkg.equals(YT_MUSIC) && playOnYtMusic(q, songId)) {
                // YouTube Music only opens for a search request; a song link makes it play.
                JSONObject o = ok().put("playing_on", "YouTube Music").put("query", q);
                if (lockedNow) o.put("note", LOCKED_NOTE);
                return o.toString();
            } else if (supportsPlayFromSearch(pkg) && playFromSearch(pkg, q)) {
                nudgePlay(pkg);
                JSONObject o = ok().put("playing_on", label(pkg)).put("query", q);
                if (lockedNow && pkg.equals(YT_MUSIC)) o.put("note", LOCKED_NOTE);
                return o.toString();
            } else {
                Intent launch = act().getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    start(launch);
                }
                return ok().put("opened", label(pkg)).put("note", label(pkg) + " does not let other apps start a song. It is open; Anil must search and tap play. Offer YouTube if he prefers automatic play.").toString();
            }
        }
        // YouTube cannot start a video behind the lock screen: ask for fingerprint/PIN first.
        boolean wasLocked = locked();
        if (wasLocked && !unlocked()) {
            return err("locked", "YouTube can only play after the phone is unlocked, and it was not unlocked. "
                    + "Tell Anil to unlock with fingerprint or PIN when asked and ask again, or to name a music app such as Spotify or JioSaavn, which can start on the lock screen.");
        }
        // Default: open the top YouTube video directly, so it starts playing by itself.
        String id = topVideoId(q);
        if (id != null) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + id))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (installed(YT)) i.setPackage(YT);
            try {
                start(i);
                JSONObject o = ok().put("playing_on", "YouTube").put("query", q);
                if (wasLocked) o.put("note", LOCKED_NOTE);
                return o.toString();
            } catch (ActivityNotFoundException ignored) {}
        }
        if (a.isEmpty() && installed(YT_MUSIC) && playOnYtMusic(q, null)) {
            return ok().put("playing_on", "YouTube Music").put("query", q).toString();
        }
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start(i);
        return ok().put("youtube_search_opened", q).put("note", "Could not start playback automatically; the search results are open. Ask Anil to tap the first video.").toString();
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

    private String readNotifications(String app, int limit) throws Exception {
        if (!NotifyListener.enabled(act())) {
            onUi(() -> act().startActivity(NotifyListener.settingsIntent()));
            return err("notification_access_off", "Jarvis does not have notification access yet. The settings screen was opened: Anil must switch on 'Jarvis' under Notification access, then ask again.");
        }
        limit = Math.max(1, Math.min(20, limit <= 0 ? 8 : limit));
        List<NotifyListener.Item> list = NotifyListener.recent(app, limit);
        JSONArray arr = new JSONArray();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH);
        for (NotifyListener.Item i : list) {
            arr.put(new JSONObject().put("id", i.id).put("app", i.app).put("from", i.from)
                    .put("text", i.text).put("time", f.format(new java.util.Date(i.when)))
                    .put("can_reply", i.reply != null));
        }
        JSONObject o = ok().put("notifications", arr);
        if (list.isEmpty()) o.put("note", "No recent notifications" + (app.isEmpty() ? "" : " from " + app) + ". Only messages that arrived after notification access was switched on can be read.");
        return o.toString();
    }

    private String replyNotification(int id, String message) throws Exception {
        if (message == null || message.trim().isEmpty()) return err("missing", "What should the reply say?");
        NotifyListener.Item item = NotifyListener.get(id);
        if (item == null) return err("not_found", "That notification is gone. Call read_notifications again.");
        if (item.reply == null) return err("no_reply_button", item.app + " does not allow replies from the notification. Offer to open the app instead.");
        if (!unlocked()) return err("locked", "The phone is locked and Anil did not unlock it.");
        String to = item.from.isEmpty() ? item.app : item.from + " (" + item.app + ")";
        if (!host.confirm("రిప్లై పంపాలా?", "ఎవరికి: " + to + "\n\n" + message, "పంపు", 0)) return err("cancelled", "Anil cancelled the reply.");
        NotifyListener.reply(act(), item, message);
        return ok().put("replied_to", to).toString();
    }

    /** Internet search through the OpenAI Responses API (used by the live voice mode). */
    private String webSearch(String query) throws Exception {
        if (query == null || query.trim().isEmpty()) return err("missing", "What should I search for?");
        String key = prefs.openAiKey().trim();
        if (key.isEmpty()) return err("no_key", "Web search needs an OpenAI key in settings.");
        JSONObject body = new JSONObject()
                .put("model", prefs.openAiModel().trim().isEmpty() ? Prefs.DEFAULT_OPENAI_MODEL : prefs.openAiModel().trim())
                .put("tools", new JSONArray().put(new JSONObject().put("type", "web_search")))
                .put("input", "Search the web and answer in at most 6 short factual sentences, with dates where relevant: " + query);
        JSONObject res = Http.post("https://api.openai.com/v1/responses", body, "Authorization", "Bearer " + key);
        StringBuilder said = new StringBuilder();
        JSONArray out = res.optJSONArray("output");
        for (int i = 0; out != null && i < out.length(); i++) {
            JSONObject item = out.getJSONObject(i);
            if (!"message".equals(item.optString("type"))) continue;
            JSONArray parts = item.optJSONArray("content");
            for (int k = 0; parts != null && k < parts.length(); k++) {
                JSONObject p = parts.getJSONObject(k);
                if ("output_text".equals(p.optString("type"))) said.append(p.optString("text"));
            }
        }
        return ok().put("result", said.toString().trim()).toString();
    }

    // ================================================================ reminders & calendar

    private static final String[] TIME_FORMATS = {"yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss"};

    /** Parses a local date-time like "2026-09-25 17:00"; returns -1 if it cannot. */
    static long parseLocal(String s) {
        if (s == null) return -1;
        s = s.trim();
        for (String f : TIME_FORMATS) {
            try {
                java.text.SimpleDateFormat p = new java.text.SimpleDateFormat(f, Locale.ENGLISH);
                p.setLenient(false);
                java.util.Date d = p.parse(s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {}
        }
        return -1;
    }

    private static String fmt(long t) {
        return new java.text.SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.ENGLISH).format(new java.util.Date(t));
    }

    private String setReminder(String text, String when) throws Exception {
        long at = parseLocal(when);
        if (at < 0) return err("bad_time", "Give the time as 'yyyy-MM-dd HH:mm' in local time.");
        if (at <= System.currentTimeMillis()) return err("in_past", "That time has already passed. Ask Anil for a future time.");
        JSONObject r = store.addReminder(text, at);
        if (r == null) return err("empty", "What should I remind him about?");
        Reminders.schedule(act(), r);
        host.notice("రిమైండర్ పెట్టాను");
        return ok().put("id", r.optString("id")).put("at", fmt(at)).put("text", r.optString("text")).toString();
    }

    private String listReminders() throws Exception {
        JSONArray arr = new JSONArray();
        long now = System.currentTimeMillis();
        List<JSONObject> list = store.reminders();
        list.sort((x, y) -> Long.compare(x.optLong("at"), y.optLong("at")));
        for (JSONObject r : list) {
            if (r.optBoolean("done") || r.optLong("at") < now) continue;
            arr.put(new JSONObject().put("id", r.optString("id")).put("text", r.optString("text")).put("at", fmt(r.optLong("at"))));
        }
        return ok().put("upcoming", arr).toString();
    }

    private String cancelReminder(String id) throws Exception {
        JSONObject r = store.removeReminder(id);
        if (r == null) return err("not_found", "No reminder with that id. Call list_reminders.");
        Reminders.cancel(act(), id);
        return ok().put("cancelled", r.optString("text")).toString();
    }

    /** Calendar events from today for the given number of days, as JSON. */
    static String calendarJson(android.content.Context ctx, int days) throws Exception {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long start = cal.getTimeInMillis();
        long end = start + days * 24L * 60 * 60 * 1000;
        android.net.Uri.Builder b = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon();
        android.content.ContentUris.appendId(b, start);
        android.content.ContentUris.appendId(b, end);
        String[] cols = {android.provider.CalendarContract.Instances.TITLE,
                android.provider.CalendarContract.Instances.BEGIN,
                android.provider.CalendarContract.Instances.END,
                android.provider.CalendarContract.Instances.ALL_DAY,
                android.provider.CalendarContract.Instances.EVENT_LOCATION};
        JSONArray arr = new JSONArray();
        try (Cursor c = ctx.getContentResolver().query(b.build(), cols, null, null,
                android.provider.CalendarContract.Instances.BEGIN + " ASC")) {
            while (c != null && c.moveToNext() && arr.length() < 30) {
                JSONObject e = new JSONObject().put("title", c.getString(0));
                boolean allDay = c.getInt(3) == 1;
                e.put("all_day", allDay);
                if (!allDay) e.put("start", fmt(c.getLong(1))).put("end", fmt(c.getLong(2)));
                else e.put("date", new java.text.SimpleDateFormat("EEE d MMM", Locale.ENGLISH).format(new java.util.Date(c.getLong(1))));
                String loc = c.getString(4);
                if (loc != null && !loc.isEmpty()) e.put("location", loc);
                arr.put(e);
            }
        }
        return ok().put("events", arr).toString();
    }

    private String calendarEvents(int days) throws Exception {
        if (!has(Manifest.permission.READ_CALENDAR)) return needPermission(Manifest.permission.READ_CALENDAR, "reading the calendar");
        return calendarJson(act(), Math.max(1, Math.min(14, days <= 0 ? 1 : days)));
    }

    private String addCalendarEvent(String title, String start, int minutes, String location) throws Exception {
        if (!has(Manifest.permission.WRITE_CALENDAR)) {
            host.askPermissions(new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR});
            return err("permission_needed", "Anil must allow calendar access. A permission prompt was shown; ask him to allow it and try again.");
        }
        long begin = parseLocal(start);
        if (begin < 0) return err("bad_time", "Give the start as 'yyyy-MM-dd HH:mm'.");
        long calId = pickCalendar();
        if (calId < 0) return err("no_calendar", "No writable calendar on this phone. Ask Anil to sign in to Google Calendar.");
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(android.provider.CalendarContract.Events.CALENDAR_ID, calId);
        v.put(android.provider.CalendarContract.Events.TITLE, title);
        v.put(android.provider.CalendarContract.Events.DTSTART, begin);
        v.put(android.provider.CalendarContract.Events.DTEND, begin + Math.max(5, minutes <= 0 ? 60 : minutes) * 60000L);
        v.put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().getID());
        if (location != null && !location.isEmpty()) v.put(android.provider.CalendarContract.Events.EVENT_LOCATION, location);
        android.net.Uri u = act().getContentResolver().insert(android.provider.CalendarContract.Events.CONTENT_URI, v);
        if (u == null) return err("failed", "The calendar did not accept the event.");
        try {
            android.content.ContentValues rv = new android.content.ContentValues();
            rv.put(android.provider.CalendarContract.Reminders.EVENT_ID, android.content.ContentUris.parseId(u));
            rv.put(android.provider.CalendarContract.Reminders.MINUTES, 15);
            rv.put(android.provider.CalendarContract.Reminders.METHOD, android.provider.CalendarContract.Reminders.METHOD_ALERT);
            act().getContentResolver().insert(android.provider.CalendarContract.Reminders.CONTENT_URI, rv);
        } catch (Exception ignored) {}
        return ok().put("added", title).put("start", fmt(begin)).toString();
    }

    private long pickCalendar() {
        String[] cols = {android.provider.CalendarContract.Calendars._ID,
                android.provider.CalendarContract.Calendars.ACCOUNT_NAME,
                android.provider.CalendarContract.Calendars.OWNER_ACCOUNT,
                android.provider.CalendarContract.Calendars.ACCOUNT_TYPE};
        String where = android.provider.CalendarContract.Calendars.VISIBLE + "=1 AND "
                + android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=" + android.provider.CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR;
        long first = -1;
        try (Cursor c = act().getContentResolver().query(android.provider.CalendarContract.Calendars.CONTENT_URI, cols, where, null, null)) {
            while (c != null && c.moveToNext()) {
                long id = c.getLong(0);
                if (first < 0) first = id;
                String acct = c.getString(1), owner = c.getString(2), type = c.getString(3);
                if ("com.google".equals(type) && acct != null && acct.equals(owner)) return id;
            }
        } catch (SecurityException e) {
            return -1;
        }
        return first;
    }

    // ================================================================ email

    private String sendEmail(String to, String subject, String body) throws Exception {
        if (to == null || to.trim().isEmpty()) return err("missing", "Who should the email go to?");
        String address = to.trim();
        String name = address;
        if (!address.contains("@")) {
            if (!has(Manifest.permission.READ_CONTACTS)) return needPermission(Manifest.permission.READ_CONTACTS, "reading contacts");
            address = null;
            try (Cursor c = act().getContentResolver().query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Email.ADDRESS},
                    ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY + " LIKE ?", new String[]{"%" + to.trim() + "%"}, null)) {
                if (c != null && c.moveToNext()) { name = c.getString(0); address = c.getString(1); }
            }
            if (address == null) return err("no_email", "No email address saved for '" + to + "'. Ask Anil for the address.");
        }
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(address)))
                .putExtra(Intent.EXTRA_EMAIL, new String[]{address})
                .putExtra(Intent.EXTRA_SUBJECT, subject == null ? "" : subject)
                .putExtra(Intent.EXTRA_TEXT, body == null ? "" : body)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (act().getPackageManager().getLaunchIntentForPackage("com.google.android.gm") != null) i.setPackage("com.google.android.gm");
        try {
            start(i);
        } catch (ActivityNotFoundException e) {
            return err("no_mail_app", "No email app on this phone.");
        }
        return ok().put("draft_opened_for", name + " <" + address + ">").put("note", "Gmail is open with the email ready; Anil taps send.").toString();
    }

    // ================================================================ music

    private android.media.session.MediaController activeMedia() {
        if (!NotifyListener.enabled(act())) return null;
        try {
            android.media.session.MediaSessionManager msm = act().getSystemService(android.media.session.MediaSessionManager.class);
            List<android.media.session.MediaController> list = msm.getActiveSessions(new android.content.ComponentName(act(), NotifyListener.class));
            android.media.session.MediaController fallback = null;
            for (android.media.session.MediaController mc : list) {
                android.media.session.PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING) return mc;
                if (fallback == null) fallback = mc;
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private void mediaKey(android.media.AudioManager am, int code) {
        long t = android.os.SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, code, 0));
    }

    private String mediaControl(String action, int percent) throws Exception {
        android.media.AudioManager am = act().getSystemService(android.media.AudioManager.class);
        if (am == null) return err("no_audio", "No audio service.");
        android.media.session.MediaController mc = activeMedia();
        android.media.session.MediaController.TransportControls tc = mc == null ? null : mc.getTransportControls();
        String a = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        switch (a) {
            case "play": if (tc != null) tc.play(); else mediaKey(am, android.view.KeyEvent.KEYCODE_MEDIA_PLAY); break;
            case "pause": case "stop": if (tc != null) tc.pause(); else mediaKey(am, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE); break;
            case "toggle": mediaKey(am, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); break;
            case "next": if (tc != null) tc.skipToNext(); else mediaKey(am, android.view.KeyEvent.KEYCODE_MEDIA_NEXT); break;
            case "previous": if (tc != null) tc.skipToPrevious(); else mediaKey(am, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
            case "volume_up":
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI);
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, 0);
                break;
            case "volume_down":
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI);
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, 0);
                break;
            case "mute": am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_MUTE, android.media.AudioManager.FLAG_SHOW_UI); break;
            case "unmute": am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, android.media.AudioManager.FLAG_SHOW_UI); break;
            case "set_volume": {
                int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                int v = Math.round(max * Math.max(0, Math.min(100, percent)) / 100f);
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, android.media.AudioManager.FLAG_SHOW_UI);
                break;
            }
            default:
                return err("bad_action", "Use play, pause, toggle, next, previous, volume_up, volume_down, set_volume, mute or unmute.");
        }
        int vol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 / Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
        return ok().put("done", a).put("volume_pct", vol).toString();
    }

    private String nowPlaying() throws Exception {
        if (!NotifyListener.enabled(act())) {
            onUi(() -> act().startActivity(NotifyListener.settingsIntent()));
            return err("notification_access_off", "To see what is playing, Anil must switch on notification access for Jarvis (settings opened).");
        }
        android.media.session.MediaController mc = activeMedia();
        if (mc == null) return ok().put("playing", false).put("note", "Nothing is playing.").toString();
        JSONObject o = ok().put("app", mc.getPackageName());
        android.media.MediaMetadata md = mc.getMetadata();
        if (md != null) {
            o.put("title", md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE));
            o.put("artist", md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST));
        }
        android.media.session.PlaybackState st = mc.getPlaybackState();
        o.put("playing", st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING);
        return o.toString();
    }

    // ================================================================ screen & camera

    private static final String VISION_SYSTEM =
            "You look at an image from Anil's phone for his assistant Jarvis. Answer his question about it in English, "
            + "in 2-6 short sentences. Mention the important text, names, numbers, prices and dates you can read. "
            + "If the image is unclear, say so.";

    private String lookAtScreen(String question) throws Exception {
        if (!JarvisAccessibility.enabled()) {
            onUi(() -> act().startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            return err("screen_access_off", "Jarvis cannot see the screen yet. Accessibility settings were opened: Anil must switch on 'Jarvis స్క్రీన్'. On Android 13+, if it is greyed out: App info → ⋮ → Allow restricted settings.");
        }
        JarvisAccessibility.Capture cap = JarvisAccessibility.recent(120000);
        if (cap == null && !MainActivity.visible) cap = JarvisAccessibility.captureBlocking(4000);
        if (cap == null) {
            return err("no_recent_screen", "Jarvis's own screen is covering the phone. Ask Anil to open the screen he wants, then call you with the wake word 'Jarvis' and ask again.");
        }
        String q = question == null || question.trim().isEmpty() ? "What is on this screen?" : question;
        String prompt = "Question: " + q + "\nApp on screen: " + cap.pkg
                + "\nText read from the screen:\n" + (cap.text.length() > 3000 ? cap.text.substring(0, 3000) : cap.text);
        String answer = Brain.oneShot(prefs, VISION_SYSTEM, prompt, cap.jpeg, false);
        return ok().put("app", cap.pkg).put("answer", answer).toString();
    }

    private String lookThroughCamera(String question) throws Exception {
        String frame = CameraPanel.latestFrame;
        if (frame == null) return err("camera_off", "The live camera is not open. Ask Anil to tap the 'Live కెమెరా' button first.");
        String q = question == null || question.trim().isEmpty() ? "What do you see?" : question;
        String answer = Brain.oneShot(prefs, VISION_SYSTEM, "Question: " + q + "\n(This is a live camera frame.)", frame, false);
        return ok().put("answer", answer).toString();
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
