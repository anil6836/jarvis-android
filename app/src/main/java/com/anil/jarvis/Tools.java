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
                "Write an SMS: opens his messages app with the text typed in as a draft. It is NOT sent yet: then read him the message and ask 'పంపమంటారా?'; send only with send_draft when he says send. Write the message in the language he asked for.",
                schema(new String[][]{{"who", "string", "Contact name or phone number"}, {"message", "string", "The message text"}}, "who", "message")));
        DEFS.add(new Def("whatsapp_message",
                "Write a WhatsApp message: opens the chat with the text typed in as a draft. It is NOT sent yet: then read him the message and ask 'పంపమంటారా?'; send only with send_draft when he says send. Write the message in the language he asked for.",
                schema(new String[][]{{"who", "string", "Contact name or phone number"}, {"message", "string", "The message text"}}, "who", "message")));
        DEFS.add(new Def("telegram_message",
                "Write a Telegram message: opens the chat with the text typed in as a draft. It is NOT sent yet: then read him the message and ask 'పంపమంటారా?'; send only with send_draft when he says send.",
                schema(new String[][]{{"who", "string", "Contact name, phone number, or @username"}, {"message", "string", "The message text"}}, "who", "message")));
        DEFS.add(new Def("send_draft",
                "Press Send on the message or email draft Jarvis just prepared (WhatsApp, Telegram, SMS, Gmail). Call ONLY after Anil has heard the message and clearly said to send it (పంపు, సెండ్ చెయ్, yes send).",
                schema(new String[][]{})));
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
                "Reply to one of the notifications from read_notifications using its reply button (works for WhatsApp, SMS, Telegram, Instagram and most chat apps). This sends immediately, so first read him the reply and ask 'పంపమంటారా?', and call it only after he says send.",
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
                "Write an email in Gmail: opens it as a draft with to, subject and body filled in. It is NOT sent yet: then tell him the subject and gist and ask 'పంపమంటారా?'; send only with send_draft when he says send. 'to' can be an email address or a contact name.",
                schema(new String[][]{{"to", "string", "Email address or contact name"}, {"subject", "string", "Subject"},
                        {"body", "string", "Email body, in the language he asked for"}}, "to", "subject", "body")));
        DEFS.add(new Def("media_control",
                "Control the song or video playing in any app (YouTube, YouTube Music, Spotify, JioSaavn, Gaana, Amazon Music...) and the media volume. "
                        + "pause = song off / పాట ఆపు / ఆఫ్ చేయి (keeps its place); play = continue the paused song from where it stopped; "
                        + "stop = music stop: stops the music AND fully closes that music app.",
                schema(new String[][]{{"action", "string", "One of: pause, play, stop, next, previous, toggle, volume_up, volume_down, set_volume, mute, unmute"},
                        {"percent", "integer", "Volume 0-100, only for set_volume"}}, "action")));
        DEFS.add(new Def("phone_setting",
                "Change a phone setting: wifi, bluetooth, mobile_data, airplane, location, hotspot (on/off, flipped on the settings page through accessibility), "
                        + "brightness (value = percent), auto_brightness, auto_rotate, silent, vibrate, sound (ringer back on), dnd (Do Not Disturb).",
                schema(new String[][]{{"setting", "string", "wifi, bluetooth, mobile_data, airplane, location, hotspot, brightness, auto_brightness, auto_rotate, silent, vibrate, sound, dnd"},
                        {"value", "string", "'on' or 'off'; for brightness a percent like '30'"}}, "setting")));
        DEFS.add(new Def("photos",
                "His phone's photos. action show = open them in the gallery; count = how many; send = send them on WhatsApp to a contact "
                        + "(opens WhatsApp with the photos attached as a draft; then ask 'పంపమంటారా?' and use send_draft).",
                schema(new String[][]{{"action", "string", "show, send or count"},
                        {"when", "string", "latest (default), today, yesterday, this week, this month, or a date yyyy-MM-dd"},
                        {"who", "string", "For send: contact name"}, {"count", "integer", "For send: how many of the newest (default 1, max 10)"},
                        {"caption", "string", "For send: optional message with the photo"},
                        {"screenshots", "boolean", "Include screenshots (default false)"}}, "action")));
        DEFS.add(new Def("call_control",
                "Answer or decline the ringing call, or end the call in progress (phone, WhatsApp and other app calls).",
                schema(new String[][]{{"action", "string", "answer, decline or end"}}, "action")));
        DEFS.add(new Def("bank_spending",
                "Money spent and received, estimated from bank and UPI SMS on the phone for the last N days, with recent transactions.",
                schema(new String[][]{{"days", "integer", "How many days back (default 30, max 92)"}})));
        DEFS.add(new Def("save_place",
                "Remember where the phone is right now under a name (home, office, gym...), for location reminders.",
                schema(new String[][]{{"name", "string", "Place name in English, e.g. 'home', 'office'"}}, "name")));
        DEFS.add(new Def("location_reminder",
                "Remind Anil when he arrives at or leaves a place ('ఇంటికి చేరగానే గుర్తుచేయి'). Place = a saved place (home, office) or an address. Also list or cancel them.",
                schema(new String[][]{{"action", "string", "add (default), list or cancel"}, {"place", "string", "Saved place name or address, English"},
                        {"text", "string", "What to remind him"}, {"when", "string", "arrive (default) or leave"},
                        {"id", "string", "For cancel: the reminder id from list"}})));
        DEFS.add(new Def("ride_app",
                "Open Uber, Ola or Rapido for a trip, with pickup and drop filled in where the app allows. Jarvis does not book or pay: Anil checks fares and taps Book himself.",
                schema(new String[][]{{"app", "string", "Uber, Ola or Rapido"}, {"pickup", "string", "Pickup place in English; empty = current location"},
                        {"drop", "string", "Drop place in English"}}, "app", "drop")));
        DEFS.add(new Def("food_app",
                "Open Zomato, Swiggy or Blinkit at a search for the food or item Anil wants. Jarvis does not order or pay: Anil picks, orders and pays himself.",
                schema(new String[][]{{"app", "string", "Zomato, Swiggy or Blinkit"}, {"query", "string", "What he wants, e.g. 'chicken biryani', 'milk'"}}, "app", "query")));
        DEFS.add(new Def("driving_mode",
                "Driving mode on/off: every new message is read aloud and calls are announced for voice answering. Optionally start navigation.",
                schema(new String[][]{{"on", "boolean", "true to start, false to stop"}, {"destination", "string", "Optional place to navigate to"}}, "on")));
        DEFS.add(new Def("night_mode",
                "'గుడ్ నైట్' = on: phone quiet (Do Not Disturb or vibrate), low brightness, nothing read aloud, optional wake-up alarm. 'గుడ్ మార్నింగ్' = off: sound back on, then brief him.",
                schema(new String[][]{{"on", "boolean", "true for good night, false for good morning"}, {"alarm", "string", "Optional wake-up time 'HH:mm' (24h)"}}, "on")));
        DEFS.add(new Def("find_phone",
                "Anil cannot find his phone ('ఎక్కడున్నావ్?', 'where are you'): ring loudly and blink the flashlight.",
                schema(new String[][]{})));
        DEFS.add(new Def("add_expense",
                "Write down an expense (e.g. from a bill photo he shows, or 'petrol 500 రాసుకో'). It counts in bank_spending and day_summary.",
                schema(new String[][]{{"amount", "number", "Amount in rupees"}, {"what", "string", "Short description, e.g. 'Groceries - More supermarket'"}}, "amount")));
        DEFS.add(new Def("day_summary",
                "Summary of today: calls (missed ones), messages and who sent them, money spent, reminders, missions done. For 'ఈరోజు ఏం జరిగింది?'.",
                schema(new String[][]{})));
        DEFS.add(new Def("scan_qr",
                "Read a QR code or barcode from the live camera, or else from the newest photo/screenshot. UPI codes show the payee; Jarvis never pays.",
                schema(new String[][]{{"open", "boolean", "true to open the link / UPI app after reading (only when he asks)"}})));
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
            case "whatsapp_message": return "WhatsApp లో మెసేజ్ టైప్ చేస్తున్నాను…";
            case "telegram_message": return "Telegram లో మెసేజ్ టైప్ చేస్తున్నాను…";
            case "send_draft": return "పంపుతున్నాను…";
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
            case "phone_setting": return "సెట్టింగ్ మారుస్తున్నాను…";
            case "photos": return "ఫోటోలు చూస్తున్నాను…";
            case "call_control": return "కాల్…";
            case "bank_spending": return "బ్యాంక్ మెసేజ్‌లు లెక్కపెడుతున్నాను…";
            case "save_place": return "ఈ చోటు గుర్తుపెట్టుకుంటున్నాను…";
            case "location_reminder": return "లొకేషన్ రిమైండర్…";
            case "driving_mode": return "డ్రైవింగ్ మోడ్…";
            case "ride_app": return "రైడ్ యాప్ తెరుస్తున్నాను…";
            case "food_app": return "వెతుకుతున్నాను…";
            case "night_mode": return "నైట్ మోడ్…";
            case "find_phone": return "ఇక్కడే ఉన్నాను!";
            case "add_expense": return "ఖర్చు రాస్తున్నాను…";
            case "day_summary": return "ఈరోజు లెక్క చూస్తున్నాను…";
            case "scan_qr": return "QR చదువుతున్నాను…";
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
                case "telegram_message": return telegram(a.optString("who"), a.optString("message"));
                case "send_draft": return sendDraft();
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
                case "phone_setting": return phoneSetting(a.optString("setting"), a.optString("value", "on"));
                case "photos": return photos(a.optString("action", "show"), a.optString("when", ""), a.optString("who", ""),
                        a.optInt("count", 1), a.optString("caption", ""), a.optBoolean("screenshots", false));
                case "call_control": return callControl(a.optString("action"));
                case "bank_spending": return bankSpending(a.optInt("days", 30));
                case "save_place": return savePlace(a.optString("name"));
                case "ride_app": return rideApp(a.optString("app"), a.optString("pickup", ""), a.optString("drop"));
                case "food_app": return foodApp(a.optString("app"), a.optString("query"));
                case "driving_mode": return drivingMode(a.optBoolean("on", true), a.optString("destination", ""));
                case "night_mode": return nightMode(a.optBoolean("on", true), a.optString("alarm", ""));
                case "find_phone": return findPhone();
                case "add_expense": return addExpense(a.optDouble("amount", 0), a.optString("what", ""));
                case "day_summary": return daySummary();
                case "scan_qr": return scanQr(a.optBoolean("open", false));
                case "location_reminder": return locationReminder(a.optString("action", "add"), a.optString("place"),
                        a.optString("text"), a.optString("when", "arrive"), a.optString("id"));
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
    private boolean unlocked() throws InterruptedException { return unlocked(45); }

    private boolean unlocked(int seconds) throws InterruptedException {
        KeyguardManager km = (KeyguardManager) act().getSystemService(Activity.KEYGUARD_SERVICE);
        if (km == null || !km.isKeyguardLocked()) return true;
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);
        act().runOnUiThread(() -> km.requestDismissKeyguard(act(), new KeyguardManager.KeyguardDismissCallback() {
            @Override public void onDismissSucceeded() { ok.set(true); done.countDown(); }
            @Override public void onDismissCancelled() { done.countDown(); }
            @Override public void onDismissError() { done.countDown(); }
        }));
        done.await(seconds, TimeUnit.SECONDS);
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
        Target t = resolve(who);
        if (t.error != null) return t.error;
        if (!unlocked()) return err("locked", "The phone is locked and Anil did not unlock it.");
        Contact c = t.contact;
        String to = c.name.equals(c.number) ? c.number : c.name + " (" + c.number + ")";
        String pkg = android.provider.Telephony.Sms.getDefaultSmsPackage(act());
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number)))
                .putExtra("sms_body", message)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pkg != null) i.setPackage(pkg);
        try {
            start(i);
        } catch (ActivityNotFoundException e) {
            return err("no_sms_app", "No messages app on this phone.");
        }
        return draftReady(pkg, to, c.number, "SMS", message, true);
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
        if (message == null || message.trim().isEmpty()) return err("missing", "What should the message say?");
        Target t = resolve(who);
        if (t.error != null) return t.error;
        if (!unlocked()) return err("locked", "The phone is locked and Anil did not unlock it.");
        String url = "https://wa.me/" + whatsappNumber(t.contact.number) + "?text=" + URLEncoder.encode(message, "UTF-8");
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String pkg = null;
        PackageManager pm = act().getPackageManager();
        for (String p : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) {
            if (pm.getLaunchIntentForPackage(p) != null) { pkg = p; i.setPackage(p); break; }
        }
        try {
            start(i);
        } catch (ActivityNotFoundException e) {
            return err("no_whatsapp", "WhatsApp is not installed.");
        }
        return draftReady(pkg, t.contact.name, t.contact.number, "WhatsApp", message, true);
    }

    private static final String[] TELEGRAMS = {"org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram", "org.telegram.plus"};

    private String telegram(String who, String message) throws Exception {
        if (message == null || message.trim().isEmpty()) return err("missing", "What should the message say?");
        String pkg = null;
        for (String p : TELEGRAMS) if (installed(p)) { pkg = p; break; }
        if (pkg == null) return err("no_telegram", "Telegram is not installed.");
        String w = who == null ? "" : who.trim();
        String link, to;
        if (w.startsWith("@")) {
            link = "tg://resolve?domain=" + Uri.encode(w.substring(1)) + "&text=" + Uri.encode(message);
            to = w;
        } else {
            Target t = resolve(w);
            if (t.error != null) return t.error;
            link = "tg://resolve?phone=" + whatsappNumber(t.contact.number) + "&text=" + Uri.encode(message);
            to = t.contact.name;
        }
        if (!unlocked()) return err("locked", "The phone is locked and Anil did not unlock it.");
        try {
            start(new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            return err("no_telegram", "Telegram could not open that chat.");
        }
        return draftReady(pkg, to, null, "Telegram", message, true);
    }

    // ---------------------------------------------------------------- drafts: write, ask, then send

    private static final class Draft {
        String pkg, to, number, app, message;
        long time;
    }

    /** The message Jarvis typed and is waiting for Anil's "send" on. */
    private static volatile Draft pendingDraft;

    /**
     * The app is open with the message typed in. Makes sure the text is in the box, remembers the
     * draft, and brings Jarvis back so Anil can say "send" by voice.
     */
    private String draftReady(String pkg, String to, String number, String app, String message, boolean typeIt) throws Exception {
        boolean typed = false;
        if (pkg != null && typeIt && JarvisAccessibility.enabled()) {
            String r = JarvisAccessibility.typeInto(pkg, message, 7000);
            typed = "typed".equals(r) || "already".equals(r);
        } else {
            Thread.sleep(1500);
        }
        Draft d = new Draft();
        d.pkg = pkg; d.to = to; d.number = number; d.app = app; d.message = message;
        d.time = android.os.SystemClock.elapsedRealtime();
        pendingDraft = d;
        Thread.sleep(500);
        backToJarvis();
        JSONObject o = ok().put("draft_ready", true).put("app", app).put("to", to).put("message", message)
                .put("sent", false)
                .put("next", "Not sent yet. Read him the message in one short line and ask 'పంపమంటారా?'. Call send_draft only when he clearly says send. "
                        + "If he wants changes, call this tool again with the new text. If he says no, leave it unsent.");
        if (!JarvisAccessibility.enabled()) {
            o.put("note", "Jarvis's accessibility switch is off, so it cannot press Send by voice; Anil can tap send himself, or switch on Jarvis in Settings > Accessibility.");
        } else if (typeIt && !typed) {
            o.put("note", "The text may not be in the message box; ask Anil to check the draft.");
        }
        return o.toString();
    }

    /** Brings the Jarvis panel (or screen) back on top of the app, so the voice conversation continues. */
    private void backToJarvis() {
        try {
            Activity a = act();
            start(new Intent(a, a.getClass()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Exception ignored) {}
    }

    private String sendDraft() throws Exception {
        Draft d = pendingDraft;
        if (d == null || android.os.SystemClock.elapsedRealtime() - d.time > 30 * 60 * 1000L) {
            return err("no_draft", "There is no message waiting to be sent. Ask Anil what to send and to whom.");
        }
        if (!unlocked()) return err("locked", "The phone is locked and Anil did not unlock it.");
        if (d.pkg == null || !JarvisAccessibility.enabled()) {
            if ("SMS".equals(d.app) && d.number != null && has(Manifest.permission.SEND_SMS)) {
                SmsManager sm = Build.VERSION.SDK_INT >= 31 ? act().getSystemService(SmsManager.class) : SmsManager.getDefault();
                sm.sendMultipartTextMessage(d.number, null, sm.divideMessage(d.message), null, null);
                pendingDraft = null;
                return ok().put("sent_to", d.to).put("app", "SMS")
                        .put("note", "Sent directly; the typed copy may still sit in the messages app as a draft.").toString();
            }
            return err("no_accessibility", "Jarvis cannot press Send without its accessibility switch (Settings > Accessibility > Jarvis). The message is ready in " + d.app + "; Anil can tap send.");
        }
        // 1) the app may still be visible under the Jarvis panel
        String r = JarvisAccessibility.clickSend(d.pkg, 1500);
        // 2) step Jarvis aside so the app is in front, then press Send
        if (!"sent".equals(r)) {
            onUi(() -> act().moveTaskToBack(true));
            Thread.sleep(700);
            r = JarvisAccessibility.clickSend(d.pkg, 6000);
        }
        // 3) last try: bring the app forward by its icon
        if (!"sent".equals(r)) {
            Intent l = act().getPackageManager().getLaunchIntentForPackage(d.pkg);
            if (l != null) {
                l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { start(l); } catch (Exception ignored) {}
                Thread.sleep(900);
                r = JarvisAccessibility.clickSend(d.pkg, 5000);
            }
        }
        if (!"sent".equals(r)) {
            return err("no_send_button", "Jarvis could not find " + d.app + "'s Send button. The message is ready there; ask Anil to tap send once.");
        }
        pendingDraft = null;
        return ok().put("sent_to", d.to).put("app", d.app).toString();
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
        return closePackage(pkg, 45);
    }

    /** Stops what the app plays, then force-stops it. unlockSeconds: how long to wait for a locked phone to be unlocked. */
    private String closePackage(String pkg, int unlockSeconds) throws Exception {
        String name2 = label(pkg);
        if (pkg.equals(lastMediaPkg)) lastMediaPkg = null;

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
            if (unlocked(unlockSeconds)) {
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
            connected.await(3, TimeUnit.SECONDS);
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
            if (startsPlaying(player, () -> mc2(player).playFromSearch(query, extras), 4000)) return true;
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

    /** The app's current player, if Jarvis may see it (needs notification access). */
    private android.media.session.MediaController sessionOf(String pkg) {
        if (!NotifyListener.enabled(act())) return null;
        try {
            android.media.session.MediaSessionManager msm = act().getSystemService(android.media.session.MediaSessionManager.class);
            for (android.media.session.MediaController c : msm.getActiveSessions(new android.content.ComponentName(act(), NotifyListener.class))) {
                if (pkg.equals(c.getPackageName())) return c;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Phone locked: opens the song link in the app behind the lock screen (nothing is unlocked).
     * YouTube Music with Premium starts playing there. Returns true once music is heard playing.
     */
    private boolean playBehindLock(String pkg, Uri link) throws InterruptedException {
        android.media.AudioManager am = act().getSystemService(android.media.AudioManager.class);
        boolean wasActive = am != null && am.isMusicActive();
        Intent i = new Intent(Intent.ACTION_VIEW, link).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { start(i); } catch (Exception e) { return false; }
        long end = android.os.SystemClock.elapsedRealtime() + 8000;
        while (android.os.SystemClock.elapsedRealtime() < end) {
            Thread.sleep(400);
            android.media.session.MediaController mc = sessionOf(pkg);
            if (mc != null) {
                android.media.session.PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING) return true;
            } else if (am != null && am.isMusicActive() && !wasActive) {
                return true;
            }
        }
        return false;
    }

    /** Waits (up to 90 s) for Anil to unlock, then makes sure the app's song is playing. */
    private void playAfterUnlock(String pkg) {
        android.content.Context app = act().getApplicationContext();
        KeyguardManager km = (KeyguardManager) app.getSystemService(Activity.KEYGUARD_SERVICE);
        if (km == null) return;
        new Thread(() -> {
            try {
                long end = android.os.SystemClock.elapsedRealtime() + 90000;
                while (km.isKeyguardLocked()) {
                    if (android.os.SystemClock.elapsedRealtime() > end) return;
                    Thread.sleep(500);
                }
                Thread.sleep(2500);
                android.media.session.MediaController mc = sessionOf(pkg);
                if (mc == null) return;
                android.media.session.PlaybackState st = mc.getPlaybackState();
                int state = st == null ? android.media.session.PlaybackState.STATE_NONE : st.getState();
                if (state != android.media.session.PlaybackState.STATE_PLAYING
                        && state != android.media.session.PlaybackState.STATE_BUFFERING) {
                    mc.getTransportControls().play();
                }
            } catch (Exception ignored) {}
        }, "jarvis-play-after-unlock").start();
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

    /** The app Jarvis last started or paused music in, so "play" resumes it and "stop" closes it. */
    static volatile String lastMediaPkg;

    private String youtube(String query, String app) throws Exception {
        String r = youtubeInner(query, app);
        try {
            JSONObject o = new JSONObject(r);
            String on = o.optString("playing_on", o.optString("ready_on", ""));
            if (!on.isEmpty()) {
                if (on.equals("YouTube")) lastMediaPkg = YT;
                else if (on.equals("YouTube Music")) lastMediaPkg = YT_MUSIC;
                else {
                    ResolveInfo ri = findApp(on);
                    if (ri != null) lastMediaPkg = ri.activityInfo.packageName;
                }
            }
        } catch (Exception ignored) {}
        return r;
    }

    private String youtubeInner(String query, String app) throws Exception {
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
                if (playWithoutScreen(pkg, q, null)) {
                    return ok().put("playing_on", label(pkg)).put("query", q).put("phone_locked", true).toString();
                }
                if (pkg.equals(YT_MUSIC) && songId != null) {
                    // With Premium, YouTube Music can start the song behind the lock screen.
                    if (playBehindLock(YT_MUSIC, ytMusicLink(songId))) {
                        return ok().put("playing_on", "YouTube Music").put("query", q).put("phone_locked", true).toString();
                    }
                    playAfterUnlock(YT_MUSIC);
                    return ok().put("ready_on", "YouTube Music").put("query", q)
                            .put("note", "The song is open in YouTube Music behind the lock screen but did not start while locked. "
                                    + "Tell Anil to unlock with his fingerprint; it plays as soon as he unlocks. If it never plays with the "
                                    + "phone locked, YouTube Music's background play may be off or the phone's battery saver may be putting YouTube Music to sleep.")
                            .toString();
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
        if (!unlocked()) return err("locked", "The phone is locked and Anil did not unlock it.");
        String gmail = act().getPackageManager().getLaunchIntentForPackage("com.google.android.gm") != null ? "com.google.android.gm" : null;
        if (gmail != null) i.setPackage(gmail);
        try {
            start(i);
        } catch (ActivityNotFoundException e) {
            return err("no_mail_app", "No email app on this phone.");
        }
        return draftReady(gmail, name + " <" + address + ">", null, "Gmail",
                (subject == null ? "" : subject + ": ") + (body == null ? "" : body), false);
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

    private static boolean isPlaying(android.media.session.MediaController mc) {
        android.media.session.PlaybackState st = mc.getPlaybackState();
        return st != null && (st.getState() == android.media.session.PlaybackState.STATE_PLAYING
                || st.getState() == android.media.session.PlaybackState.STATE_BUFFERING);
    }

    private List<android.media.session.MediaController> sessions() {
        if (!NotifyListener.enabled(act())) return new ArrayList<>();
        try {
            android.media.session.MediaSessionManager msm = act().getSystemService(android.media.session.MediaSessionManager.class);
            return msm.getActiveSessions(new android.content.ComponentName(act(), NotifyListener.class));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** "పాట ఆపు / ఆఫ్ చేయి": pauses whatever is playing, keeping its place. */
    private String pauseMedia(android.media.AudioManager am) throws Exception {
        List<android.media.session.MediaController> list = sessions();
        String paused = null;
        for (android.media.session.MediaController mc : list) {
            if (isPlaying(mc)) {
                mc.getTransportControls().pause();
                if (paused == null) paused = mc.getPackageName();
            }
        }
        if (paused == null && am.isMusicActive()) mediaKey(am, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE);
        if (paused == null && list.isEmpty() && !am.isMusicActive() && lastMediaPkg == null) {
            return ok().put("done", "pause").put("note", "Nothing seems to be playing.").toString();
        }
        if (paused != null) lastMediaPkg = paused;
        return ok().put("done", "pause").put("paused", label(paused != null ? paused : lastMediaPkg != null ? lastMediaPkg : ""))
                .put("resume_hint", "Saying 'play' continues from the same place.").toString();
    }

    /** "ప్లే చేయి": continues the paused song or video from where it stopped. */
    private String resumeMedia(android.media.AudioManager am) throws Exception {
        List<android.media.session.MediaController> list = sessions();
        for (android.media.session.MediaController mc : list) {
            if (isPlaying(mc)) return ok().put("done", "play").put("already_playing", label(mc.getPackageName())).toString();
        }
        android.media.session.MediaController pick = null;
        for (android.media.session.MediaController mc : list) {
            if (mc.getPackageName().equals(lastMediaPkg)) { pick = mc; break; }
        }
        if (pick == null) {
            for (android.media.session.MediaController mc : list) {
                android.media.session.PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == android.media.session.PlaybackState.STATE_PAUSED) { pick = mc; break; }
            }
        }
        if (pick == null && !list.isEmpty()) pick = list.get(0);
        if (pick != null) {
            final android.media.session.MediaController player = pick;
            if (startsPlaying(player, () -> player.getTransportControls().play(), 2500)) {
                lastMediaPkg = player.getPackageName();
                return ok().put("done", "play").put("resumed", label(player.getPackageName())).toString();
            }
        }
        // No player visible to Jarvis (or it ignored the request): press the phone's play button.
        mediaKey(am, android.view.KeyEvent.KEYCODE_MEDIA_PLAY);
        for (int i = 0; i < 8; i++) {
            Thread.sleep(500);
            if (am.isMusicActive() || (pick != null && isPlaying(pick))) return ok().put("done", "play").toString();
        }
        String app = pick != null ? pick.getPackageName() : lastMediaPkg;
        if (YT.equals(app) && locked()) {
            return err("youtube_locked", "YouTube videos cannot play while the phone is locked (that needs YouTube Premium). Anil must unlock first.");
        }
        return err("nothing_to_resume", "Nothing paused could be resumed. Ask Anil what to play, then use play_youtube.");
    }

    /** "మ్యూజిక్ స్టాప్": stops the music and closes that music app completely. */
    private String stopAndCloseMedia(android.media.AudioManager am) throws Exception {
        String pkg = null;
        for (android.media.session.MediaController mc : sessions()) {
            if (isPlaying(mc)) { pkg = mc.getPackageName(); break; }
        }
        if (pkg == null) pkg = lastMediaPkg;
        if (pkg == null) {
            List<android.media.session.MediaController> list = sessions();
            if (!list.isEmpty()) pkg = list.get(0).getPackageName();
        }
        if (pkg == null || pkg.equals(act().getPackageName())) {
            mediaKey(am, android.view.KeyEvent.KEYCODE_MEDIA_STOP);
            return ok().put("done", "stop").put("note", "Music stopped. Jarvis could not tell which app was playing, so none was closed.").toString();
        }
        return closePackage(pkg, 25);
    }

    private String mediaControl(String action, int percent) throws Exception {
        android.media.AudioManager am = act().getSystemService(android.media.AudioManager.class);
        if (am == null) return err("no_audio", "No audio service.");
        android.media.session.MediaController mc = activeMedia();
        android.media.session.MediaController.TransportControls tc = mc == null ? null : mc.getTransportControls();
        String a = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        switch (a) {
            case "play": case "resume": return resumeMedia(am);
            case "pause": case "off": return pauseMedia(am);
            case "stop": case "close": return stopAndCloseMedia(am);
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
                return err("bad_action", "Use play, pause, stop, toggle, next, previous, volume_up, volume_down, set_volume, mute or unmute.");
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

    // ================================================================ phone settings

    private static boolean offWord(String v) {
        String x = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return x.equals("off") || x.equals("false") || x.equals("disable") || x.equals("0") || x.contains("ఆఫ్") || x.contains("ఆపు") || x.equals("no");
    }

    private String phoneSetting(String setting, String value) throws Exception {
        String s = setting == null ? "" : setting.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        boolean on = !offWord(value);
        switch (s) {
            case "wifi": case "wi_fi":
                return viaSettings(Build.VERSION.SDK_INT >= 29 ? new Intent(android.provider.Settings.Panel.ACTION_WIFI)
                        : new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS), null, on, "Wi-Fi");
            case "bluetooth":
                return viaSettings(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS), null, on, "Bluetooth");
            case "mobile_data": case "data": case "internet":
                return viaSettings(Build.VERSION.SDK_INT >= 29 ? new Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                                : new Intent(android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS),
                        new String[]{"mobile data", "మొబైల్ డేటా", "mobile network", "sim"}, on, "Mobile data");
            case "airplane": case "airplane_mode": case "flight_mode":
                return viaSettings(new Intent(android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS),
                        new String[]{"airplane", "aeroplane", "flight", "విమాన"}, on, "Airplane mode");
            case "location": case "gps":
                return viaSettings(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS), null, on, "Location");
            case "hotspot":
                return viaSettings(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
                        new String[]{"hotspot", "హాట్‌స్పాట్", "tethering"}, on, "Hotspot");
            case "brightness": {
                int pct = 50;
                try { pct = Integer.parseInt(value.replaceAll("[^0-9]", "")); } catch (Exception ignored) {
                    if (offWord(value) || value.contains("తగ్గ") || value.contains("low")) pct = 15;
                    else if (value.contains("full") || value.contains("max") || value.contains("పూర్తి")) pct = 100;
                }
                pct = Math.max(1, Math.min(100, pct));
                String e = needWriteSettings();
                if (e != null) return e;
                android.content.ContentResolver cr = act().getContentResolver();
                android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, Math.round(pct * 255 / 100f));
                return ok().put("brightness_pct", pct).toString();
            }
            case "auto_brightness": case "adaptive_brightness": {
                String e = needWriteSettings();
                if (e != null) return e;
                android.provider.Settings.System.putInt(act().getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                        on ? android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC : android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                return ok().put("auto_brightness", on).toString();
            }
            case "auto_rotate": case "rotation": {
                String e = needWriteSettings();
                if (e != null) return e;
                android.provider.Settings.System.putInt(act().getContentResolver(), android.provider.Settings.System.ACCELEROMETER_ROTATION, on ? 1 : 0);
                return ok().put("auto_rotate", on).toString();
            }
            case "silent": case "vibrate": case "sound": case "ring": case "normal": {
                android.media.AudioManager am = act().getSystemService(android.media.AudioManager.class);
                android.app.NotificationManager nm = act().getSystemService(android.app.NotificationManager.class);
                int mode = s.equals("vibrate") ? android.media.AudioManager.RINGER_MODE_VIBRATE
                        : s.equals("silent") && on ? android.media.AudioManager.RINGER_MODE_SILENT
                        : android.media.AudioManager.RINGER_MODE_NORMAL;
                try {
                    am.setRingerMode(mode);
                } catch (SecurityException se) {
                    return needDndAccess(nm);
                }
                if (am.getRingerMode() != mode && nm != null && !nm.isNotificationPolicyAccessGranted()) return needDndAccess(nm);
                return ok().put("ringer", mode == 0 ? "silent" : mode == 1 ? "vibrate" : "sound on").toString();
            }
            case "dnd": case "do_not_disturb": {
                android.app.NotificationManager nm = act().getSystemService(android.app.NotificationManager.class);
                if (nm == null || !nm.isNotificationPolicyAccessGranted()) return needDndAccess(nm);
                nm.setInterruptionFilter(on ? android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
                        : android.app.NotificationManager.INTERRUPTION_FILTER_ALL);
                return ok().put("do_not_disturb", on).toString();
            }
            default:
                return err("unknown_setting", "Use wifi, bluetooth, mobile_data, airplane, location, hotspot, brightness, auto_brightness, auto_rotate, silent, vibrate, sound or dnd. Volume is media_control.");
        }
    }

    private String needWriteSettings() throws Exception {
        if (android.provider.Settings.System.canWrite(act())) return null;
        start(new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + act().getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return err("permission_needed", "Anil must switch on 'Modify system settings' for Jarvis once (the page is open), then ask again.");
    }

    private String needDndAccess(android.app.NotificationManager nm) throws Exception {
        start(new Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return err("permission_needed", "Silent / Do Not Disturb needs 'Do Not Disturb access' for Jarvis. The page is open: Anil switches Jarvis on there once, then asks again.");
    }

    /** Opens a settings page or quick panel and flips its switch through accessibility, then comes back. */
    private String viaSettings(Intent page, String[] labels, boolean on, String name) throws Exception {
        page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!unlocked()) return err("locked", "The phone is locked and Anil did not unlock it.");
        try {
            start(page);
        } catch (Exception e) {
            return err("no_page", "This phone has no " + name + " settings page Jarvis can open.");
        }
        if (!JarvisAccessibility.enabled()) {
            return ok().put("opened", name).put("switched", false)
                    .put("note", "Android lets only the user flip " + name + ". The switch is open on screen for Anil to tap. With Jarvis's accessibility switch on, Jarvis flips it itself.").toString();
        }
        String r = JarvisAccessibility.setSwitch(labels, on, 6000);
        Thread.sleep(300);
        JarvisAccessibility.back();
        Thread.sleep(500);
        backToJarvis();
        if ("done".equals(r) || "already".equals(r)) return ok().put(name, on ? "on" : "off").put("was_already", "already".equals(r)).toString();
        return err("no_switch", "Jarvis could not find the " + name + " switch on that page. Anil can flip it himself in quick settings.");
    }

    // ================================================================ photos

    private String photoPermission() {
        String perm = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (has(perm)) return null;
        if (Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) return null;
        return needPermission(perm, "reading photos (choose 'Allow all')");
    }

    /** [from, to) in milliseconds for "today", "yesterday", "this week", "yyyy-MM-dd"; null = no limit. */
    private static long[] range(String when) {
        String w = when == null ? "" : when.trim().toLowerCase(Locale.ROOT);
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
        long today = c.getTimeInMillis(), day = 86400000L;
        if (w.isEmpty() || w.equals("last") || w.equals("latest") || w.equals("recent")) return null;
        if (w.contains("today") || w.contains("ఈరోజు") || w.contains("ఈ రోజు")) return new long[]{today, today + day};
        if (w.contains("yesterday") || w.contains("నిన్న")) return new long[]{today - day, today};
        if (w.contains("week") || w.contains("వారం")) return new long[]{today - 7 * day, today + day};
        if (w.contains("month") || w.contains("నెల")) return new long[]{today - 30 * day, today + day};
        try {
            java.util.Date d = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(w);
            if (d != null) return new long[]{d.getTime(), d.getTime() + day};
        } catch (Exception ignored) {}
        return null;
    }

    private List<Uri> findPhotos(String when, int max, boolean screenshots) {
        List<Uri> out = new ArrayList<>();
        Uri base = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        long[] r = range(when);
        String sel = null;
        String[] args = null;
        if (r != null) {
            sel = "(" + android.provider.MediaStore.Images.Media.DATE_TAKEN + " >= ? AND " + android.provider.MediaStore.Images.Media.DATE_TAKEN + " < ?) OR ("
                    + android.provider.MediaStore.Images.Media.DATE_TAKEN + " IS NULL AND " + android.provider.MediaStore.Images.Media.DATE_ADDED + " >= ? AND "
                    + android.provider.MediaStore.Images.Media.DATE_ADDED + " < ?)";
            args = new String[]{String.valueOf(r[0]), String.valueOf(r[1]), String.valueOf(r[0] / 1000), String.valueOf(r[1] / 1000)};
        }
        try (Cursor c = act().getContentResolver().query(base,
                new String[]{android.provider.MediaStore.Images.Media._ID, android.provider.MediaStore.Images.Media.BUCKET_DISPLAY_NAME},
                sel, args, android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            while (c != null && c.moveToNext() && out.size() < max) {
                String bucket = c.getString(1);
                if (!screenshots && bucket != null && bucket.toLowerCase(Locale.ROOT).contains("screenshot")) continue;
                out.add(android.content.ContentUris.withAppendedId(base, c.getLong(0)));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private String photos(String action, String when, String who, int count, String caption, boolean screenshots) throws Exception {
        String e = photoPermission();
        if (e != null) return e;
        String a = action == null ? "show" : action.trim().toLowerCase(Locale.ROOT);
        if (a.equals("count")) {
            int n = findPhotos(when, 5000, screenshots).size();
            return ok().put("photos", n).put("when", when).toString();
        }
        if (a.equals("send")) {
            int max = Math.max(1, Math.min(10, count <= 0 ? 1 : count));
            List<Uri> list = findPhotos(when, max, screenshots);
            if (list.isEmpty()) return err("no_photos", "No photos found for '" + when + "'.");
            Target t = resolve(who);
            if (t.error != null) return t.error;
            if (!unlocked()) return err("locked", "The phone is locked and Anil did not unlock it.");
            String pkg = null;
            for (String p : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) if (installed(p)) { pkg = p; break; }
            if (pkg == null) return err("no_whatsapp", "WhatsApp is not installed.");
            Intent i;
            if (list.size() == 1) {
                i = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, list.get(0));
            } else {
                i = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(list));
            }
            android.content.ClipData clip = android.content.ClipData.newRawUri("photo", list.get(0));
            for (int k = 1; k < list.size(); k++) clip.addItem(new android.content.ClipData.Item(list.get(k)));
            i.setClipData(clip);
            i.setType("image/*").setPackage(pkg)
                    .putExtra("jid", whatsappNumber(t.contact.number) + "@s.whatsapp.net")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (caption != null && !caption.trim().isEmpty()) i.putExtra(Intent.EXTRA_TEXT, caption.trim());
            try {
                start(i);
            } catch (ActivityNotFoundException ex) {
                return err("no_whatsapp", "WhatsApp could not open the photo.");
            }
            String what = list.size() == 1 ? "1 photo" : list.size() + " photos";
            return draftReady(pkg, t.contact.name, t.contact.number, "WhatsApp",
                    what + (caption == null || caption.isEmpty() ? "" : " with: " + caption), false);
        }
        // show
        List<Uri> list = findPhotos(when, 500, screenshots);
        if (list.isEmpty()) return err("no_photos", "No photos found for '" + when + "'.");
        if (!unlocked()) return err("locked", "The phone is locked and Anil did not unlock it.");
        Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(list.get(0), "image/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            start(v);
        } catch (ActivityNotFoundException ex) {
            return err("no_gallery", "No gallery app to show photos.");
        }
        return ok().put("showing", list.size()).put("note", "Opened the newest one; he can swipe for the others.").toString();
    }

    // ================================================================ calls

    private String callControl(String action) throws Exception {
        String a = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        String r;
        if (a.startsWith("ans") || a.equals("accept") || a.equals("pick")) r = CallControl.answer(act());
        else if (a.startsWith("dec") || a.equals("reject")) r = CallControl.decline(act());
        else r = CallControl.hangUp(act());
        if ("need_permission".equals(r)) return needPermission(Manifest.permission.ANSWER_PHONE_CALLS, "answering and ending calls");
        if ("no_call".equals(r)) return err("no_call", "There is no call to " + a + " right now.");
        if ("failed".equals(r)) return err("failed", "The call app did not accept that; Anil must tap the button himself.");
        return ok().put("done", r).toString();
    }

    // ================================================================ money from bank SMS

    private static final java.util.regex.Pattern AMOUNT = java.util.regex.Pattern.compile(
            "(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", java.util.regex.Pattern.CASE_INSENSITIVE);

    private String bankSpending(int days) throws Exception {
        if (!has(Manifest.permission.READ_SMS)) return needPermission(Manifest.permission.READ_SMS, "reading bank SMS");
        days = Math.max(1, Math.min(92, days <= 0 ? 30 : days));
        JSONObject o = spendingSince(System.currentTimeMillis() - days * 86400000L, 60);
        return o.put("days", days).toString();
    }

    /** Bank/UPI SMS plus expenses Anil added himself (bills), since a time. */
    private JSONObject spendingSince(long since, int maxItems) throws Exception {
        double spent = 0, received = 0;
        JSONArray items = new JSONArray();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d MMM", Locale.ENGLISH);
        int n = 0;
        try (Cursor c = act().getContentResolver().query(Uri.parse("content://sms/inbox"),
                new String[]{"address", "body", "date"}, "date >= ?", new String[]{String.valueOf(since)}, "date DESC")) {
            while (c != null && c.moveToNext()) {
                String body = c.getString(1);
                if (body == null) continue;
                String low = body.toLowerCase(Locale.ROOT);
                if (low.contains("otp") || low.contains("one time password") || low.contains("will be debited")
                        || low.contains("due") && !low.contains("debited") || low.contains("request")) continue;
                boolean debit = low.contains("debited") || low.contains("spent") || low.contains("withdrawn") || low.contains("paid")
                        || low.contains("sent") || low.contains("purchase") || low.contains(" dr ") || low.contains("debit");
                boolean credit = low.contains("credited") || low.contains("received") || low.contains("deposited") || low.contains("refund");
                if (!debit && !credit) continue;
                java.util.regex.Matcher m = AMOUNT.matcher(body);
                if (!m.find()) continue;
                double amt;
                try { amt = Double.parseDouble(m.group(1).replace(",", "")); } catch (Exception ex) { continue; }
                boolean isCredit = credit && !(low.indexOf("debited") >= 0 && low.indexOf("debited") < Math.max(0, low.indexOf("credited")));
                if (isCredit) received += amt; else spent += amt;
                if (n++ < maxItems) {
                    String snippet = body.replaceAll("\\s+", " ").replaceAll("[0-9Xx*]{6,}", "…");
                    if (snippet.length() > 110) snippet = snippet.substring(0, 110);
                    items.put(new JSONObject().put("date", f.format(new java.util.Date(c.getLong(2))))
                            .put("type", isCredit ? "credit" : "debit").put("amount", amt)
                            .put("from", c.getString(0)).put("sms", snippet));
                }
            }
        }
        double manual = 0;
        JSONArray bills = new JSONArray();
        for (JSONObject e : Money.expenses(act())) {
            if (e.optLong("t") < since) continue;
            manual += e.optDouble("amount");
            if (bills.length() < maxItems) bills.put(e);
        }
        return ok().put("total_spent_sms", Math.round(spent)).put("total_received", Math.round(received))
                .put("bills_added_by_anil", Math.round(manual)).put("bills", bills)
                .put("total_spent", Math.round(spent + manual))
                .put("transactions", n).put("recent", items)
                .put("note", "Estimated from bank/UPI SMS on this phone plus bills Anil added; card or app payments without an SMS are missing.");
    }


    // ================================================================ modes, find phone, bills, day summary, QR

    private String drivingMode(boolean on, String destination) throws Exception {
        prefs.set("driving", on);
        if (on) prefs.set("night", false);
        JSONObject o = ok().put("driving_mode", on);
        if (on) {
            android.app.NotificationManager nm = act().getSystemService(android.app.NotificationManager.class);
            if (nm != null && nm.isNotificationPolicyAccessGranted()) nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL);
            if (destination != null && !destination.trim().isEmpty()) {
                maps(destination.trim(), true);
                o.put("navigating_to", destination.trim());
            }
            o.put("note", "Every new message is read aloud and calls are announced; he answers by voice. Say 'డ్రైవింగ్ అయిపోయింది' to stop.");
        }
        return o.toString();
    }

    private String nightMode(boolean on, String alarmTime) throws Exception {
        prefs.set("night", on);
        android.app.NotificationManager nm = act().getSystemService(android.app.NotificationManager.class);
        android.media.AudioManager am = act().getSystemService(android.media.AudioManager.class);
        boolean dnd = nm != null && nm.isNotificationPolicyAccessGranted();
        boolean canWrite = android.provider.Settings.System.canWrite(act());
        JSONObject o = ok().put("night_mode", on);
        if (on) {
            prefs.set("driving", false);
            if (dnd) nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY);
            else try { am.setRingerMode(android.media.AudioManager.RINGER_MODE_VIBRATE); } catch (Exception ignored) {}
            o.put("phone", dnd ? "do not disturb (alarms still ring)" : "vibrate");
            if (canWrite) {
                android.provider.Settings.System.putInt(act().getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                android.provider.Settings.System.putInt(act().getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS, 20);
                o.put("brightness", "low");
            }
            if (alarmTime != null && alarmTime.matches("\\s*\\d{1,2}[:.]\\d{2}\\s*")) {
                String[] hm = alarmTime.trim().split("[:.]");
                alarm(Integer.parseInt(hm[0]), Integer.parseInt(hm[1]), "Good morning");
                o.put("alarm", alarmTime.trim());
            }
            if (!dnd) o.put("tip", "For full Do Not Disturb, Anil can allow 'Do Not Disturb access' for Jarvis once.");
        } else {
            if (dnd) nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL);
            try { am.setRingerMode(android.media.AudioManager.RINGER_MODE_NORMAL); } catch (Exception ignored) {}
            if (canWrite) android.provider.Settings.System.putInt(act().getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            o.put("next", "Sound is back on. Now give him a short good-morning briefing: call get_weather and list_reminders / calendar_events for today, then say it in 3-4 sentences.");
        }
        return o.toString();
    }

    private String findPhone() throws Exception {
        FindPhone.start(act());
        return ok().put("ringing", true).put("note", "The phone rings loudly and the flashlight blinks for 40 seconds, or until he unlocks it.").toString();
    }

    private String addExpense(double amount, String what) throws Exception {
        if (amount <= 0) return err("missing", "How much was it?");
        JSONObject e = Money.add(act(), amount, what == null ? "" : what.trim());
        return ok().put("added", e).put("month_total_bills", Math.round(Money.totalSince(act(), monthStart()))).toString();
    }

    private static long dayStart() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static long monthStart() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(dayStart());
        c.set(java.util.Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    private String daySummary() throws Exception {
        long start = dayStart();
        JSONObject o = ok();
        // calls
        if (has(Manifest.permission.READ_CALL_LOG)) {
            int in = 0, out = 0, missed = 0;
            JSONArray missedFrom = new JSONArray();
            try (Cursor c = act().getContentResolver().query(android.provider.CallLog.Calls.CONTENT_URI,
                    new String[]{android.provider.CallLog.Calls.TYPE, android.provider.CallLog.Calls.CACHED_NAME, android.provider.CallLog.Calls.NUMBER},
                    android.provider.CallLog.Calls.DATE + " >= ?", new String[]{String.valueOf(start)}, null)) {
                while (c != null && c.moveToNext()) {
                    int t = c.getInt(0);
                    if (t == android.provider.CallLog.Calls.INCOMING_TYPE) in++;
                    else if (t == android.provider.CallLog.Calls.OUTGOING_TYPE) out++;
                    else if (t == android.provider.CallLog.Calls.MISSED_TYPE) {
                        missed++;
                        String who = c.getString(1);
                        if (missedFrom.length() < 8) missedFrom.put(who == null || who.isEmpty() ? c.getString(2) : who);
                    }
                }
            } catch (Exception ignored) {}
            o.put("calls", new JSONObject().put("incoming", in).put("outgoing", out).put("missed", missed).put("missed_from", missedFrom));
        } else {
            host.askPermissions(new String[]{Manifest.permission.READ_CALL_LOG});
            o.put("calls", "Call history needs the call-log permission (a prompt was shown).");
        }
        // messages
        java.util.Map<String, Integer> senders = new java.util.LinkedHashMap<>();
        int msgs = 0;
        for (NotifyListener.Item it : NotifyListener.recent("", 60)) {
            if (it.when < start) continue;
            msgs++;
            String k = (it.from.isEmpty() ? it.app : it.from) + " (" + it.app + ")";
            senders.put(k, senders.containsKey(k) ? senders.get(k) + 1 : 1);
        }
        o.put("messages", new JSONObject().put("count", msgs).put("from", new JSONObject(senders)));
        // money
        if (has(Manifest.permission.READ_SMS)) {
            JSONObject m = spendingSince(start, 8);
            o.put("money_today", new JSONObject().put("spent", m.optLong("total_spent")).put("received", m.optLong("total_received")).put("items", m.optJSONArray("recent")));
        } else {
            o.put("money_today", new JSONObject().put("bills_added", Math.round(Money.totalSince(act(), start))));
        }
        // reminders and missions
        JSONArray remDone = new JSONArray(), remLeft = new JSONArray(), doneMissions = new JSONArray();
        for (JSONObject r : store.reminders()) {
            long at = r.optLong("at");
            if (at < start || at >= start + 86400000L) continue;
            (r.optBoolean("done") ? remDone : remLeft).put(r.optString("text"));
        }
        int active = 0;
        for (JSONObject m : store.missions()) {
            if (m.optBoolean("done") && m.optLong("doneAt") >= start) doneMissions.put(m.optString("text"));
            if (!m.optBoolean("done")) active++;
        }
        o.put("reminders_done", remDone).put("reminders_left_today", remLeft)
                .put("missions_completed_today", doneMissions).put("missions_still_active", active);
        return o.toString();
    }

    private String scanQr(boolean open) throws Exception {
        android.graphics.Bitmap bmp = null;
        String source;
        String frame = CameraPanel.latestFrame;
        if (frame != null) {
            byte[] jpg = android.util.Base64.decode(frame, android.util.Base64.DEFAULT);
            bmp = android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
            source = "live camera";
        } else {
            String e = photoPermission();
            if (e != null) return e;
            List<Uri> last = findPhotos("", 1, true);
            if (last.isEmpty()) return err("no_image", "Open the live camera and point it at the QR code, or take a screenshot of it, then ask again.");
            android.graphics.BitmapFactory.Options opt = new android.graphics.BitmapFactory.Options();
            opt.inSampleSize = 2;
            try (java.io.InputStream in = act().getContentResolver().openInputStream(last.get(0))) {
                bmp = android.graphics.BitmapFactory.decodeStream(in, null, opt);
            }
            source = "newest photo/screenshot";
        }
        if (bmp == null) return err("no_image", "Could not read the picture.");
        String text = QrReader.read(bmp);
        if (text == null) return err("no_qr", "No QR code or barcode found in the " + source + ". Hold it steady and closer, then ask again.");
        JSONObject o = ok().put("source", source).put("qr", text);
        if (text.toLowerCase(Locale.ROOT).startsWith("upi:")) {
            Uri u = Uri.parse(text);
            o.put("upi_payee", u.getQueryParameter("pn")).put("upi_id", u.getQueryParameter("pa")).put("amount", u.getQueryParameter("am"))
                    .put("note", "A UPI payment code. Jarvis never pays by itself; if he wants to pay, call scan_qr again with open=true and he finishes in his UPI app with his PIN.");
        }
        if (open && (text.startsWith("http") || text.toLowerCase(Locale.ROOT).startsWith("upi:"))) {
            if (!unlocked()) return err("locked", "The phone is locked and Anil did not unlock it.");
            start(Intent.createChooser(new Intent(Intent.ACTION_VIEW, Uri.parse(text)), "తెరవండి").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            o.put("opened", true);
        }
        return o.toString();
    }


    // ================================================================ rides and food (Anil books and pays himself)

    private double[] geocode(String place) {
        if (place == null || place.trim().isEmpty()) return null;
        double[] saved = GeoReminders.place(act(), place);
        if (saved != null) return saved;
        try {
            List<android.location.Address> a = new android.location.Geocoder(act(), Locale.ENGLISH).getFromLocationName(place, 1);
            if (a != null && !a.isEmpty()) return new double[]{a.get(0).getLatitude(), a.get(0).getLongitude()};
        } catch (Exception ignored) {}
        return null;
    }

    private boolean openIn(String pkg, String url) {
        try {
            start(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean launch(String pkg) throws InterruptedException {
        Intent l = act().getPackageManager().getLaunchIntentForPackage(pkg);
        if (l == null) return false;
        start(l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }

    /** Opens a ride app with the trip filled in where the app allows it. Anil checks the prices and books himself. */
    private String rideApp(String app, String pickup, String drop) throws Exception {
        String a = app == null ? "" : app.trim().toLowerCase(Locale.ROOT);
        if (drop == null || drop.trim().isEmpty()) return err("missing", "Where to?");
        if (!unlocked()) return err("locked", "The phone is locked and Anil did not unlock it.");
        double[] to = geocode(drop);
        double[] from = pickup == null || pickup.trim().isEmpty() ? null : geocode(pickup);
        String name, pkg;
        boolean filled = false;
        if (a.contains("uber")) {
            name = "Uber"; pkg = "com.ubercab";
            if (!installed(pkg)) return err("not_installed", "Uber is not installed.");
            StringBuilder u = new StringBuilder("uber://?action=setPickup");
            if (from != null) u.append("&pickup[latitude]=").append(from[0]).append("&pickup[longitude]=").append(from[1])
                    .append("&pickup[nickname]=").append(Uri.encode(pickup));
            else u.append("&pickup=my_location");
            if (to != null) u.append("&dropoff[latitude]=").append(to[0]).append("&dropoff[longitude]=").append(to[1]);
            u.append("&dropoff[nickname]=").append(Uri.encode(drop)).append("&dropoff[formatted_address]=").append(Uri.encode(drop));
            filled = openIn(pkg, u.toString());
            if (!filled) launch(pkg);
        } else if (a.contains("ola")) {
            name = "Ola"; pkg = "com.olacabs.customer";
            if (!installed(pkg)) return err("not_installed", "Ola is not installed.");
            if (to != null) {
                StringBuilder u = new StringBuilder("olacabs://app/launch?drop_lat=").append(to[0]).append("&drop_lng=").append(to[1]);
                if (from != null) u.append("&lat=").append(from[0]).append("&lng=").append(from[1]);
                filled = openIn(pkg, u.toString());
            }
            if (!filled) launch(pkg);
        } else if (a.contains("rapido")) {
            name = "Rapido"; pkg = "com.rapido.passenger";
            if (!launch(pkg)) return err("not_installed", "Rapido is not installed.");
        } else {
            return err("unknown_app", "Use Uber, Ola or Rapido.");
        }
        return ok().put("opened", name).put("trip_filled_in", filled).put("from", pickup == null || pickup.isEmpty() ? "current location" : pickup).put("to", drop)
                .put("next", (filled ? "The trip is filled in. " : name + " does not accept a trip from other apps, so Anil types the drop place. ")
                        + "When the fares show, he can say 'Jarvis, ధరలు చెప్పు' and you read them with look_at_screen. Anil taps Book himself; Jarvis never books or pays.")
                .toString();
    }

    /** Opens a food / grocery app at a search for what Anil wants. He picks, orders and pays himself. */
    private String foodApp(String app, String query) throws Exception {
        String a = app == null ? "" : app.trim().toLowerCase(Locale.ROOT);
        if (query == null || query.trim().isEmpty()) return err("missing", "Ask Anil what he wants to eat or buy first.");
        if (!unlocked()) return err("locked", "The phone is locked and Anil did not unlock it.");
        String q = Uri.encode(query.trim());
        String name, pkg;
        boolean searched;
        if (a.contains("zomato")) {
            name = "Zomato"; pkg = "com.application.zomato";
            if (!installed(pkg)) return err("not_installed", "Zomato is not installed.");
            searched = openIn(pkg, "zomato://search?q=" + q) || openIn(pkg, "https://www.zomato.com/search?q=" + q);
        } else if (a.contains("swiggy") || a.contains("instamart")) {
            name = "Swiggy"; pkg = "in.swiggy.android";
            if (!installed(pkg)) return err("not_installed", "Swiggy is not installed.");
            searched = openIn(pkg, "swiggy://explore?query=" + q) || openIn(pkg, "https://www.swiggy.com/search?query=" + q);
        } else if (a.contains("blinkit")) {
            name = "Blinkit"; pkg = "com.grofers.customer";
            if (!installed(pkg)) return err("not_installed", "Blinkit is not installed.");
            searched = openIn(pkg, "https://blinkit.com/s/?q=" + q);
        } else {
            return err("unknown_app", "Use Zomato, Swiggy or Blinkit.");
        }
        if (!searched) launch(pkg);
        return ok().put("opened", name).put("searched_for", query).put("search_opened", searched)
                .put("next", (searched ? "The search is open. " : name + " opened on its home screen; Anil searches for it. ")
                        + "He can say 'Jarvis, మెనూ, ధరలు చెప్పు' and you read the items and prices with look_at_screen. Anil adds to cart, orders and pays himself; Jarvis never orders or pays.")
                .toString();
    }

    // ================================================================ places & location reminders

    private Location freshLocation() throws InterruptedException {
        LocationManager lm = (LocationManager) act().getSystemService(Activity.LOCATION_SERVICE);
        if (lm == null) return null;
        if (Build.VERSION.SDK_INT >= 30) {
            String provider = Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER) ? LocationManager.FUSED_PROVIDER
                    : lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
            final Location[] got = {null};
            CountDownLatch done = new CountDownLatch(1);
            try {
                lm.getCurrentLocation(provider, null, act().getMainExecutor(), l -> { got[0] = l; done.countDown(); });
                done.await(20, TimeUnit.SECONDS);
            } catch (SecurityException | IllegalArgumentException ignored) {}
            if (got[0] != null) return got[0];
        }
        return lastLocation(act());
    }

    private String savePlace(String name) throws Exception {
        if (name == null || name.trim().isEmpty()) return err("missing", "What should I call this place (home, office...)?");
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) return needPermission(Manifest.permission.ACCESS_FINE_LOCATION, "precise location");
        Location l = freshLocation();
        if (l == null) return err("no_location", "Could not get the phone's location. Is Location on?");
        GeoReminders.savePlace(act(), name.trim(), l.getLatitude(), l.getLongitude());
        return ok().put("saved_place", name.trim()).put("accuracy_m", Math.round(l.getAccuracy())).toString();
    }

    private String locationReminder(String action, String place, String text, String when, String id) throws Exception {
        String a = action == null || action.isEmpty() ? "add" : action.trim().toLowerCase(Locale.ROOT);
        if (a.equals("list")) {
            JSONArray arr = new JSONArray();
            for (JSONObject r : GeoReminders.all(act())) {
                arr.put(new JSONObject().put("id", r.optString("id")).put("place", r.optString("place"))
                        .put("when", r.optBoolean("arrive") ? "arrive" : "leave").put("text", r.optString("text")));
            }
            return ok().put("location_reminders", arr).put("saved_places", GeoReminders.places(act())).toString();
        }
        if (a.equals("cancel")) {
            return GeoReminders.cancel(act(), id) ? ok().put("cancelled", id).toString() : err("not_found", "No location reminder with id " + id);
        }
        if (place == null || place.trim().isEmpty() || text == null || text.trim().isEmpty()) return err("missing", "Need the place and what to remind.");
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) return needPermission(Manifest.permission.ACCESS_FINE_LOCATION, "precise location");
        double[] ll = GeoReminders.place(act(), place);
        if (ll == null) {
            try {
                List<android.location.Address> found = new android.location.Geocoder(act(), Locale.ENGLISH).getFromLocationName(place, 1);
                if (found != null && !found.isEmpty()) ll = new double[]{found.get(0).getLatitude(), found.get(0).getLongitude()};
            } catch (Exception ignored) {}
        }
        if (ll == null) {
            return err("unknown_place", "'" + place + "' is not a saved place and could not be found on the map. When Anil is there, he can say 'ఈ place ని " + place + " గా సేవ్ చెయ్' (save_place).");
        }
        boolean arrive = when == null || !when.toLowerCase(Locale.ROOT).startsWith("leav");
        Location here = lastLocation(act());
        boolean inside = here != null && GeoReminders.distance(here.getLatitude(), here.getLongitude(), ll[0], ll[1]) < GeoReminders.RADIUS_M;
        JSONObject r = GeoReminders.add(act(), place.trim(), ll[0], ll[1], text.trim(), arrive, inside);
        JSONObject o = ok().put("id", r.optString("id")).put("place", place).put("when", arrive ? "arrive" : "leave").put("text", text);
        if (Build.VERSION.SDK_INT >= 29 && !has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            host.askPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION});
            o.put("note", "Set. For it to work when Jarvis is closed, Anil must choose 'Allow all the time' for Jarvis's location (the page was opened).");
        } else {
            o.put("note", "Android checks location every few minutes when the phone is idle, so it may come a little after he arrives.");
        }
        if (inside && arrive) o.put("already_there", "He is there now; it will remind him the next time he arrives.");
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
