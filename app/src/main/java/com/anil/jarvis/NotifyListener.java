package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Person;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps the last messages that arrived as notifications (WhatsApp, SMS, Telegram…)
 * so Jarvis can read them out and reply through the notification's own reply button.
 * Nothing is stored on disk; the list lives only while the app is running.
 */
public class NotifyListener extends NotificationListenerService {

    static final class Item {
        int id;
        String key, pkg, app, from, text;
        long when;
        boolean group;
        Notification.Action reply;
    }

    private static final int MAX = 150;
    private static final long KEEP_MS = 24L * 60 * 60 * 1000;
    private static final Map<String, Item> items = new LinkedHashMap<>();
    private static int nextId = 1;

    static boolean enabled(Context c) {
        ComponentName me = new ComponentName(c, NotifyListener.class);
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            return nm != null && nm.isNotificationListenerAccessGranted(me);
        }
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(me.flattenToString());
    }

    static Intent settingsIntent() {
        return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Android is sending Jarvis the notifications right now. */
    static volatile boolean connected;
    /** What Jarvis did with the newest chat message (for the check in Settings). */
    static volatile String lastMessageNote = "";

    /**
     * Opening Jarvis: if notification access is given but Android isn't sending the notifications
     * (it can drop the link after an update or when the phone kills the app), ask it to connect again.
     */
    static void ensureBound(Context c) {
        if (connected || !enabled(c)) return;
        try { requestRebind(new ComponentName(c, NotifyListener.class)); } catch (Exception ignored) {}
    }

    @Override public void onListenerDisconnected() {
        connected = false;
        try { requestRebind(new ComponentName(this, NotifyListener.class)); } catch (Exception ignored) {}
    }

    /** Screen and unlock events for rest mode (this service always runs, the wake word may not). */
    private final android.content.BroadcastReceiver screenEvents = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i == null ? null : i.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(a)) Rest.screen(true);
            else if (Intent.ACTION_SCREEN_OFF.equals(a)) { Rest.screen(false); Sleep.screenOff(c); }
            else if (Intent.ACTION_USER_PRESENT.equals(a)) { Rest.awake(); Sleep.unlocked(c); schedule(1500); } // held messages now
        }
    };
    private boolean screenRegistered;
    /** The battery level, for "95% ఛార్జ్ అయింది" (this listener stays alive even without the wake word). */
    private final android.content.BroadcastReceiver battery = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) { Charge.onBattery(c, i); }
    };

    @Override public void onDestroy() {
        if (screenRegistered) { try { unregisterReceiver(screenEvents); } catch (Exception ignored) {} screenRegistered = false; }
        try { unregisterReceiver(battery); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public void onListenerConnected() {
        if (!screenRegistered) {
            try {
                android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
                f.addAction(Intent.ACTION_SCREEN_OFF);
                f.addAction(Intent.ACTION_USER_PRESENT);
                registerReceiver(screenEvents, f);
                screenRegistered = true;
                registerReceiver(battery, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            } catch (Exception ignored) {}
        }
        connected = true;
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) for (StatusBarNotification sbn : active) add(sbn);
        } catch (Exception ignored) {}
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        add(sbn);
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        // the maps app itself ended its navigation (arrived / stopped), not him swiping it away
        if (sbn != null && Drive.isNavApp(sbn.getPackageName()) && (reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL))
            Drive.navEnded(this, sbn.getKey());
        super.onNotificationRemoved(sbn, rankingMap, reason);
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        String[] call = CallControl.ended(sbn.getKey());
        CallControl.onRemoved(sbn.getKey());
        if (call != null) CallNote.after(this, call[0], Long.parseLong(call[1]));
    }

    private void add(StatusBarNotification sbn) {
        if (sbn == null || getPackageName().equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n != null && Notification.CATEGORY_CALL.equals(n.category)) {
            announceCall(sbn, n);
            return;
        }
        // the maps app's turn-by-turn: next turn, distance, arrival time (its other notifications go on as usual)
        if (Drive.isNavApp(sbn.getPackageName()) && Drive.fromNotification(this, sbn)) return;
        if (n == null || sbn.isOngoing()) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle x = n.extras;
        if (x == null) return;

        String title = str(x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        if (title.isEmpty()) title = str(x.getCharSequence(Notification.EXTRA_TITLE));
        title = cleanTitle(title);
        String text = messages(x);
        if (text.isEmpty()) text = str(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (text.isEmpty()) text = str(x.getCharSequence(Notification.EXTRA_TEXT));
        if (text.isEmpty() && title.isEmpty()) return;
        if (text.length() > 1200) text = text.substring(0, 1200);

        Notification.Action reply = null;
        if (n.actions != null) {
            for (Notification.Action a : n.actions) {
                RemoteInput[] ri = a.getRemoteInputs();
                if (ri != null && ri.length > 0 && a.actionIntent != null) { reply = a; break; }
            }
        }

        String app = sbn.getPackageName();
        try {
            PackageManager pm = getPackageManager();
            app = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(sbn.getPackageName(), 0)));
        } catch (Exception ignored) {}

        synchronized (items) {
            Item it = items.remove(sbn.getKey());
            if (it == null) { it = new Item(); it.id = nextId++; }
            it.key = sbn.getKey();
            it.pkg = sbn.getPackageName();
            it.app = app;
            it.from = title;
            it.text = text;
            it.when = sbn.getPostTime();
            it.reply = reply;
            it.group = x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false);
            items.put(it.key, it); // re-insert at the end = newest
            prune();
        }
        findPhoneCode(sbn, x);
        maybeReadNews(sbn, app, title, text);
        maybeReadAloud(sbn, n, x, app, title, text);
        ScamGuard.check(this, sbn.getPackageName(), app, title, text); // scam-looking message or a new autopay: warn
    }

    /** A chat's name without the counts apps add to it: "Family (37 messages)" -> "Family". */
    static String cleanTitle(String t) {
        if (t == null) return "";
        return t.replaceAll("\\s*[(（]\\s*\\d+\\s*(?i:new\\s+)?(?i:messages?|మెసేజ్‌లు|మెసేజ్|సందేశాలు|సందేశం|కొత్త సందేశాలు)\\s*[)）]", "")
                .replaceAll("(?i)\\s*[·•,:-]?\\s*\\d+\\s+new\\s+messages?\\s*$", "")
                .trim();
    }

    // ---------------------------------------------------------------- Way2News read aloud

    /** Way2News and its other-language apps (Telugu: sun.way2sms.hyd.com, English: sun.way2english.hyd.com...). */
    static boolean newsApp(String pkg, String label) {
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
        if (p.contains("reporter") || p.contains("promoter")) return false;
        return (p.startsWith("sun.way2") && p.endsWith(".hyd.com")) || p.contains("way2news")
                || String.valueOf(label).toLowerCase(Locale.ROOT).replace(" ", "").contains("way2news");
    }

    private static final Map<String, Long> newsSaid = new LinkedHashMap<String, Long>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> e) { return size() > 100; }
    };

    /** A Way2News headline: said as it comes (not at night, in a call, while talking, or on Do Not Disturb). */
    private void maybeReadNews(StatusBarNotification sbn, String app, String title, String text) {
        if (!newsApp(sbn.getPackageName(), app)) return;
        Prefs p = new Prefs(this);
        if (!p.readNews()) return;
        long now = System.currentTimeMillis();
        if (now - sbn.getPostTime() > 120000) return; // old ones shown again after a reboot
        if (p.night() || MainActivity.busyTalking() || CallControl.busyWithCall()) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (SoundService.prayerOn || RecorderService.recording || nm != null && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL) return;
        String t = title == null ? "" : title.trim(), b = text == null ? "" : text.trim();
        if (t.equalsIgnoreCase("way2news") || t.equalsIgnoreCase(app)) t = "";
        String said = t.isEmpty() || b.contains(t) ? b : b.isEmpty() || t.contains(b) ? t : t + ". " + b;
        said = said.replaceAll("https?://\\S+", "").replaceAll("(?i)(tap|click) (here )?to read( more)?\\.?", "").replaceAll("\\s+", " ").trim();
        if (said.length() < 8) return;
        if (said.length() > 350) said = said.substring(0, 350) + "…";
        synchronized (newsSaid) {
            if (newsSaid.containsKey(said)) return; // the same story posted again
            newsSaid.put(said, now);
        }
        Announcer.say(this, "Way2News వార్త: " + said);
    }

    // ---------------------------------------------------------------- read new messages aloud


    /** A leading "Name: " (chat lines carry the sender's name); a caption with a colon after the media emoji is left alone. */
    private static final java.util.regex.Pattern SENDER = java.util.regex.Pattern.compile("^[^:\\n🎤🎵🎥📹📷]{1,80}:\\s+");

    private static String withoutSender(String line) {
        java.util.regex.Matcher m = SENDER.matcher(line);
        return m.find() ? line.substring(m.end()).trim() : line;
    }

    /** What a WhatsApp notification is about: voice, audio, video, photo, or null for text. */
    private static String mediaKind(String t) {
        String l = t.toLowerCase(Locale.ROOT).trim();
        if (t.contains("🎤") || l.contains("voice message") || l.contains("వాయిస్")) return "voice";
        if (t.contains("🎵") || l.startsWith("audio")) return "audio";
        if (t.contains("🎥") || t.contains("📹") || l.startsWith("video") || l.startsWith("వీడియో")) return "video";
        if (t.contains("📷") || l.startsWith("photo") || l.startsWith("image") || l.startsWith("ఫోటో")) return "photo";
        return null;
    }

    /** Chat and social apps whose messages Jarvis offers to read. */
    private static final String[] CHAT_APPS = {"com.whatsapp", "org.telegram", "org.thunderdog.challegram",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.instagram.android",
            "com.facebook.orca", "com.facebook.katana", "com.facebook.mlite", "com.snapchat.android",
            "org.thoughtcrime.securesms", "com.linkedin.android", "com.twitter.android", "com.discord",
            "in.mohalla.sharechat", "jp.naver.line.android", "com.viber.voip", "com.imo.android.imoim",
            "com.truecaller", "com.microsoft.teams", "com.Slack", "com.skype.raider", "com.kakao.talk", "com.google.android.apps.dynamite"};

    private boolean chatApp(String pkg, Notification n, Bundle x) {
        for (String c : CHAT_APPS) if (pkg.startsWith(c)) return true;
        if (pkg.equals(android.provider.Telephony.Sms.getDefaultSmsPackage(this))) return true;
        // any other app that posts a chat message (message category or a conversation)
        return Notification.CATEGORY_MESSAGE.equals(n.category) || x.containsKey(Notification.EXTRA_MESSAGES);
    }

    /** A new chat message: Jarvis says who sent it and asks "చదవమంటారా?" before reading it. */
    private void note(String app, String from, String what) {
        lastMessageNote = new java.text.SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new java.util.Date())
                + " · " + app + (from.isEmpty() ? "" : " · " + from) + "\n→ " + what;
    }

    private void maybeReadAloud(StatusBarNotification sbn, Notification n, Bundle x, String app, String from, String text) {
        if (newsApp(sbn.getPackageName(), app)) return; // news is read out by maybeReadNews
        if (!chatApp(sbn.getPackageName(), n, x)) return;
        if (System.currentTimeMillis() - sbn.getPostTime() > 60000) return; // old ones shown again after a reboot or reconnect
        Life.endNightIfMorning(this); // a night mode left on from last night ends in the morning
        Prefs p = new Prefs(this);
        boolean driving = p.driving();
        if (!(p.readMessages() || driving)) { note(app, from, "చదవలేదు: Settings → కాల్స్ card లో 'కొత్త మెసేజ్ వస్తే… చెప్పు' ఆఫ్‌లో ఉంది"); return; }
        if (p.night() && !driving) { note(app, from, "చదవలేదు: నైట్ మోడ్ ఆన్‌లో ఉంది (\"గుడ్ మార్నింగ్\" అంటే ఆఫ్ అవుతుంది)"); return; }
        boolean group = x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false);
        String groupMode = p.groupMode();
        if (group && !driving && "none".equals(groupMode)) { note(app, from, "గ్రూప్ మెసేజ్: Settings లో గ్రూప్ మెసేజ్‌లు 'వద్దు' అని ఉంది"); return; }
        if (RecorderService.recording) { note(app, from, "చదవలేదు: రికార్డింగ్ జరుగుతోంది"); return; }
        if (!driving) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (SoundService.prayerOn || nm != null && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL) { // Do Not Disturb / prayer time
                note(app, from, SoundService.prayerOn ? "చదవలేదు: ప్రార్థన సమయం" : "చదవలేదు: ఫోన్‌లో Do Not Disturb ఆన్‌లో ఉంది");
                return;
            }
        }
        List<String> fresh = newMessages(sbn, x, text, group);
        if (fresh.isEmpty()) return; // the same messages posted again, or his own reply
        if (group && !driving && !"all".equals(groupMode)) { // "నా పేరు ఉంటేనే": only the messages that name him
            fresh = naming(fresh, p.myNames());
            if (fresh.isEmpty()) { note(app, from, "గ్రూప్ మెసేజ్: మీ పేరు లేదు, చెప్పలేదు (సెట్టింగ్స్ → కాల్స్, ఉదయం బ్రీఫింగ్ → గ్రూప్ మెసేజ్‌లు)"); return; }
        }
        int id = 0;
        boolean canReply = false;
        synchronized (items) {
            Item it = items.get(sbn.getKey());
            if (it != null) { id = it.id; canReply = it.reply != null; }
        }
        long now = System.currentTimeMillis();
        synchronized (queue) {
            Pending q = queue.get(sbn.getKey());
            if (q == null) {
                q = new Pending();
                q.key = sbn.getKey();
                q.firstAt = now;
                queue.put(q.key, q);
            }
            q.pkg = sbn.getPackageName();
            q.app = app;
            q.from = from;
            q.group = group;
            q.id = id;
            q.canReply = canReply;
            q.texts.addAll(fresh);
            while (q.texts.size() > 12) q.texts.remove(0);
            q.lastAt = now;
        }
        note(app, from, "వచ్చింది, వరుసలో ఉంది: చెప్తాను");
        schedule(2500); // wait a moment: messages usually come in a burst, say them together
    }

    /** The messages that call him by one of his names (or @name). */
    static List<String> naming(List<String> texts, String names) {
        List<String> out = new ArrayList<>();
        java.util.List<String> ns = new ArrayList<>();
        for (String n : names.split(",")) { String t = n.trim().toLowerCase(Locale.ROOT); if (t.length() >= 2) ns.add(t); }
        for (String t : texts) {
            String body = withoutSender(t).toLowerCase(Locale.ROOT); // the sender's own name is not a mention
            for (String n : ns) if (body.contains(n)) { out.add(t); break; }
        }
        return out;
    }

    // ---------------------------------------------------------------- the queue: nothing is dropped while Jarvis is busy

    /** Messages of one chat waiting to be said. */
    private static final class Pending {
        String key, pkg, app, from;
        boolean group, canReply, rested;
        int id;
        final List<String> texts = new ArrayList<>();
        long firstAt, lastAt;
    }

    private static final LinkedHashMap<String, Pending> queue = new LinkedHashMap<>();
    /** Per chat: the newest message time already taken, and the last text (for apps that give no times). */
    private static final Map<String, Long> seenUpTo = new java.util.HashMap<>();
    private static final Map<String, String> seenText = new java.util.HashMap<>();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable dispatcher = this::dispatch;
    private long nextAllowed;

    private void schedule(long ms) {
        main.removeCallbacks(dispatcher);
        main.postDelayed(dispatcher, ms);
    }

    /** The messages in this notification that are new since last time (never his own replies). */
    private List<String> newMessages(StatusBarNotification sbn, Bundle x, String text, boolean group) {
        List<String> out = new ArrayList<>();
        String key = sbn.getKey();
        long now = System.currentTimeMillis();
        Parcelable[] arr = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        synchronized (seenUpTo) {
            if (arr != null && arr.length > 0) {
                Long seen = seenUpTo.get(key);
                // first time for this chat: all of them (a chat notification holds the unread messages; after a spell
                // without signal they arrive together with older send times, and none must be lost)
                long since = seen != null ? seen : 0;
                long newest = seen != null ? seen : 0;
                boolean anyTime = false;
                for (Parcelable pa : arr) {
                    if (!(pa instanceof Bundle)) continue;
                    Bundle b = (Bundle) pa;
                    String t = str(b.getCharSequence("text"));
                    long time = b.getLong("time", 0);
                    if (time > 0) anyTime = true;
                    if (t.isEmpty() || time <= since) continue;
                    newest = Math.max(newest, time);
                    String who = "";
                    if (Build.VERSION.SDK_INT >= 28) {
                        Object pp = b.getParcelable("sender_person");
                        if (pp instanceof Person) who = str(((Person) pp).getName());
                    }
                    if (who.isEmpty()) who = str(b.getCharSequence("sender"));
                    if (who.isEmpty()) continue; // no sender = his own message (a reply from another device)
                    out.add(group ? who + ": " + t : t);
                }
                if (anyTime) {
                    if (newest > 0) seenUpTo.put(key, newest);
                    return out;
                }
                out.clear(); // an app that gives no message times: fall back to the text
            }
            String[] lines = text.split("\n");
            String last = lines[lines.length - 1].trim();
            if (last.isEmpty() || last.equals(seenText.get(key))) return out;
            seenText.put(key, last);
            out.add(last);
            if (seenUpTo.size() > 300) seenUpTo.clear();
            if (seenText.size() > 300) seenText.clear();
        }
        return out;
    }

    /** Says the oldest waiting chat when Jarvis is free; tries again every few seconds while he talks or is in a call. */
    private void dispatch() {
        long now = System.currentTimeMillis();
        Pending next = null;
        synchronized (queue) {
            boolean resting = Rest.resting(this);
            for (Iterator<Pending> it = queue.values().iterator(); it.hasNext(); ) {
                Pending q = it.next();
                if (resting) { q.rested = true; continue; } // sleeping after duty: they wait until he wakes
                if (!q.rested && now - q.firstAt > 30 * 60000L) { // busy for half an hour: too late to announce
                    note(q.app, q.from, "చెప్పలేకపోయాను: అరగంట పాటు కాల్ / మాటల్లో ఉన్నారు (\"కొత్త మెసేజ్‌లు చదువు\" అంటే చదువుతాను)");
                    it.remove();
                }
            }
            if (queue.isEmpty()) return;
            if (resting) {
                Pending first = queue.values().iterator().next();
                note(first.app, first.from, "మీరు విశ్రాంతిలో ఉన్నారు: లేచాక చెప్తాను");
                schedule(60000);
                return;
            }
            for (Pending q : queue.values()) if (now - q.lastAt >= 2500) { next = q; break; }
        }
        if (next == null) { schedule(1500); return; }
        Prefs p = new Prefs(this);
        if (p.night() && !p.driving()) {
            synchronized (queue) { queue.clear(); }
            note(next.app, next.from, "చదవలేదు: నైట్ మోడ్ ఆన్‌లో ఉంది");
            return;
        }
        if (MainActivity.busyTalking() || TopCard.busy() || JarvisCamera.open || CallControl.busyWithCall() || now < nextAllowed || FindPhone.running()) {
            note(next.app, next.from, "వరుసలో ఉంది: " + (CallControl.busyWithCall() ? "కాల్ అయ్యాక" : "ఇప్పటి మాటలు అయ్యాక") + " చెప్తాను");
            schedule(3000);
            return;
        }
        synchronized (queue) { queue.remove(next.key); }
        announce(next, p);
        nextAllowed = now + 8000; // give the panel time to start speaking before the next one
        synchronized (queue) { if (!queue.isEmpty()) schedule(8000); }
    }

    /** "Ravi నుంచి WhatsApp లో 3 మెసేజ్‌లు వచ్చాయి. చదవమంటారా?" and the texts go to the brain for when he says yes. */
    private void announce(Pending q, Prefs p) {
        String app = q.app, from = q.from;
        int id = q.id, count = q.texts.size();
        String last = q.texts.get(count - 1);
        if (last.length() > 220) last = last.substring(0, 220) + "…";
        // First only who and where; the message itself is read only if Anil says yes.
        String who = from.isEmpty() ? app : from;
        String where = (q.rested ? "మీరు పడుకున్నప్పుడు " : "") + (q.group ? who + " గ్రూప్‌లో" : who + " నుంచి " + app + " లో");
        String body = withoutSender(last); // "Ravi: 📷 Photo" -> "📷 Photo"
        String media = count == 1 && q.pkg.startsWith("com.whatsapp") ? mediaKind(body) : null;
        String reply = " Then ask 'రిప్లై ఇవ్వమంటారా?'. If he dictates a reply, read it back and ask 'పంపమంటారా?', send with reply_to_notification (id "
                + id + ") only after he says send.]";
        String say, ask, context;
        if ("voice".equals(media) || "audio".equals(media)) {
            say = p.name() + ", " + where + " " + ("voice".equals(media) ? "వాయిస్ మెసేజ్" : "ఆడియో") + " వచ్చింది.";
            ask = "వినిపించమంటారా?";
            context = " [new WhatsApp " + media + " message from " + who + ". ONLY if he says yes: whatsapp_media kind=" + media
                    + " action=play; if he wants the words ('ఏం చెప్పారు'), action=text. If no, say సరే." + reply;
        } else if ("video".equals(media)) {
            String cap = body.replaceAll("^[🎥📹]\\s*", "").replaceAll("(?i)^video\\s*", "").trim();
            say = p.name() + ", " + where + " వీడియో వచ్చింది" + (cap.isEmpty() ? "." : ": " + cap);
            ask = "ప్లే చేయమంటారా?";
            context = " [new WhatsApp video from " + who + ". ONLY if he says yes: whatsapp_media kind=video action=play. If no, say సరే." + reply;
        } else if ("photo".equals(media)) {
            String cap = body.replaceAll("^📷\\s*", "").replaceAll("(?i)^(photo|image)\\s*", "").trim();
            say = p.name() + ", " + where + " ఫోటో వచ్చింది" + (cap.isEmpty() ? "." : ": " + cap);
            ask = "చూపించమంటారా, లేక ఏముందో చెప్పమంటారా?";
            context = " [new WhatsApp photo from " + who + ". If he says show: whatsapp_media kind=photo action=show; if 'ఏముంది/చెప్పు': action=describe. If no, say సరే." + reply;
        } else if (count == 1) {
            say = p.name() + ", " + where + " మెసేజ్ వచ్చింది.";
            ask = "చదవమంటారా?";
            context = " [new message, notification id " + id + " (" + app + (q.canReply ? ", can reply with reply_to_notification" : "")
                    + "). Its text: \"" + last + "\". Read it to him ONLY if he says yes (అవును/చదువు); if he says no, just say సరే. "
                    + "After reading, ask 'రిప్లై ఇవ్వమంటారా?'. If he dictates a reply, read it back and ask 'పంపమంటారా?', send only after he says send.]";
        } else {
            say = p.name() + ", " + where + " " + count + " మెసేజ్‌లు వచ్చాయి.";
            ask = "చదవమంటారా?";
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < count; i++) {
                String t = q.texts.get(i);
                if (t.length() > 200) t = t.substring(0, 200) + "…";
                all.append(i + 1).append(") ").append(t).append(i < count - 1 ? " | " : "");
            }
            context = " [" + count + " new messages, notification id " + id + " (" + app + (q.group ? ", a group: say who wrote each" : "")
                    + (q.canReply ? ", can reply with reply_to_notification" : "") + "). In order: " + all
                    + ". Read ALL of them to him in order, briefly, ONLY if he says yes (అవును/చదువు); for a photo / voice / video line say what it is "
                    + "(whatsapp_media works on the newest one). If he says no, just say సరే. "
                    + "After reading, ask 'రిప్లై ఇవ్వమంటారా?'. If he dictates a reply, read it back and ask 'పంపమంటారా?', send only after he says send.]";
        }
        if (cardFits(p)) { // he is in another app: a small card at the top, not the panel from the bottom
            final TopCard.Msg m = new TopCard.Msg();
            m.app = app;
            m.from = who;
            m.pkg = q.pkg;
            m.group = q.group;
            m.id = q.canReply ? id : 0;
            m.texts.addAll(q.texts);
            m.say = say;
            m.ask = ask;
            m.context = context;
            m.media = media;
            m.postedAt = q.firstAt;
            final String fSay = say, fAsk = ask, fContext = context;
            final int fCount = count;
            main.postDelayed(() -> { // after the app's own banner has gone
                if (TopCard.busy()) { // he started talking on the card now up: this one waits its turn (not the panel over it)
                    synchronized (queue) { // a newer message from the same chat may be waiting already: these go before it
                        Pending cur = queue.get(q.key);
                        if (cur == null) queue.put(q.key, q);
                        else {
                            cur.texts.addAll(0, q.texts);
                            while (cur.texts.size() > 12) cur.texts.remove(0);
                            cur.firstAt = Math.min(cur.firstAt, q.firstAt);
                            cur.rested |= q.rested;
                        }
                    }
                    note(app, from, "వరుసలో ఉంది: పైన కార్డ్‌లో మాటలు అయ్యాక చెప్తాను");
                    schedule(3000);
                    return;
                }
                m.typing = JarvisAccessibility.keyboardOpen();
                Prefs now = new Prefs(this);
                String typing = now.typingMode(); // while he types: read (like any time) / say who only / silent card
                m.talk = !m.typing || "read".equals(typing);
                if (cardFits(now) && JarvisAccessibility.messageCard(m)) {
                    Store.get(this).addChat("assistant", fSay + " " + fAsk + fContext, false); // "Jarvis, చదువు / రిప్లై" later works too
                    note(app, from, m.talk ? "పైన కార్డ్‌లో చెప్పి అడిగాను" + (m.typing ? " (టైప్ చేస్తున్నా: సెట్టింగ్ 'చదువు')" : "")
                            : "name".equals(typing) ? "పైన కార్డ్ చూపించి ఎవరో మాత్రమే చెప్పాను (టైప్ చేస్తున్నారు)"
                            : "పైన కార్డ్ మాత్రమే చూపించాను (టైప్ చేస్తున్నారు: సెట్టింగ్ 'నిశ్శబ్దం')");
                    if (!m.talk && "name".equals(typing)) Announcer.say(this, fSay);
                } else {
                    openPanel(app, from, fSay, fAsk, fContext, fCount);
                }
            }, 2500);
            return;
        }
        openPanel(app, from, say, ask, context, count);
    }

    /**
     * The top card fits: the screen is on and unlocked, he is in an app (not the home screen, not a Jarvis screen) and
     * not riding, and the floating button is on (messages put off for later wait as its 📬 dot). Locked, home screen or
     * riding: the panel, so he can answer by voice without touching the phone.
     */
    private boolean cardFits(Prefs p) {
        if (!JarvisAccessibility.enabled() || !FloatBubble.on(this) || p.driving() || MainActivity.visible || SheetActivity.open || JarvisCamera.open) return false;
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        if (pm == null || !pm.isInteractive() || km == null || km.isKeyguardLocked()) return false;
        String front = JarvisAccessibility.frontPackage();
        if (JarvisAccessibility.isKeyboard(front) || "com.android.systemui".equals(front)) front = JarvisAccessibility.currentPackage();
        return front != null && !front.isEmpty() && !JarvisAccessibility.homeScreen(this, front);
    }

    /** Jarvis's panel from the bottom: says who wrote and asks "చదవమంటారా?". */
    private void openPanel(String app, String from, String say, String ask, String context, int count) {
        if (android.provider.Settings.canDrawOverlays(this)) {
            try {
                note(app, from, "చెప్పాను ✓ (panel తెరిచి)" + (count > 1 ? " · " + count + " మెసేజ్‌లు కలిపి" : ""));
                startActivity(new android.content.Intent(this, SheetActivity.class)
                        .putExtra(SheetActivity.EXTRA_ANNOUNCE, say)
                        .putExtra(SheetActivity.EXTRA_ANNOUNCE_ASK, ask)
                        .putExtra(SheetActivity.EXTRA_IS_MESSAGE, true)
                        .putExtra(SheetActivity.EXTRA_ANNOUNCE_CONTEXT, context)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
                return;
            } catch (Exception ignored) {}
        }
        // No panel: say only who wrote; if he calls Jarvis and says "చదువు", the brain has the message.
        note(app, from, android.provider.Settings.canDrawOverlays(this) ? "చెప్పాను (గొంతుతో మాత్రమే: panel తెరవలేకపోయాను)"
                : "చెప్పాను (గొంతుతో మాత్రమే: 'Display over other apps' అనుమతి లేదు)");
        Store.get(this).addChat("assistant", say + " " + ask + context, false);
        Announcer.say(this, say + " కావాలంటే Jarvis అని పిలిచి చెప్పండి.");
    }

    private static final Map<String, Long> announced = new java.util.HashMap<>();

    /** "Anil, Ravi నుంచి కాల్ వస్తోంది" when a phone or WhatsApp call starts ringing. */
    private void announceCall(StatusBarNotification sbn, Notification n) {
        Prefs p = new Prefs(this);
        if (n.extras == null) return;
        boolean incoming = n.fullScreenIntent != null;
        if (Build.VERSION.SDK_INT >= 31) {
            int type = n.extras.getInt(Notification.EXTRA_CALL_TYPE, 0);
            if (type == Notification.CallStyle.CALL_TYPE_INCOMING) incoming = true;
            else if (type == Notification.CallStyle.CALL_TYPE_ONGOING || type == Notification.CallStyle.CALL_TYPE_SCREENING) incoming = false;
        }
        TopCard.stepAside(); // a call: a message card talking at the top stops at once
        String pkgLow = sbn.getPackageName().toLowerCase(Locale.ROOT);
        boolean isPhone = pkgLow.contains("dialer") || pkgLow.contains("telecom") || pkgLow.contains("incallui") || pkgLow.contains("phone") || pkgLow.contains("contacts");
        if (!incoming) {
            CallControl.onOngoing(sbn.getKey(), n, isPhone);
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (announced) {
            Long prev = announced.get(sbn.getKey());
            if (prev != null && now - prev < 60000) return;
            announced.put(sbn.getKey(), now);
        }
        String who = "";
        if (Build.VERSION.SDK_INT >= 31) {
            Object person = n.extras.getParcelable(Notification.EXTRA_CALL_PERSON);
            if (person instanceof Person) who = str(((Person) person).getName());
        }
        if (who.isEmpty()) who = str(n.extras.getCharSequence(Notification.EXTRA_TITLE));
        String pkg = sbn.getPackageName().toLowerCase(Locale.ROOT);
        boolean phone = pkg.contains("dialer") || pkg.contains("telecom") || pkg.contains("incallui") || pkg.contains("phone") || pkg.contains("contacts");
        String app = sbn.getPackageName();
        try {
            PackageManager pm = getPackageManager();
            app = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(sbn.getPackageName(), 0)));
        } catch (Exception ignored) {}
        CallControl.onIncoming(sbn.getKey(), n, phone, who);
        if (!p.announceCalls()) return;
        String say = p.name() + ", " + (who.isEmpty() ? "ఎవరో" : who) + " నుంచి " + (phone ? "" : app + " ") + "కాల్ వస్తోంది";
        if (p.callByVoice() && android.provider.Settings.canDrawOverlays(this)) {
            // Show the Jarvis panel: it says who is calling, asks "ఎత్తమంటారా?" and listens for the answer.
            try {
                startActivity(new android.content.Intent(this, SheetActivity.class)
                        .putExtra(SheetActivity.EXTRA_CALL, say)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
                return;
            } catch (Exception ignored) {}
        }
        Announcer.say(this, say);
    }

    /** Text of the last few messages in a chat-style notification, "sender: text" per line. */
    private static String messages(Bundle x) {
        Parcelable[] arr = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (arr == null || arr.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, arr.length - 6); i < arr.length; i++) {
            if (!(arr[i] instanceof Bundle)) continue;
            Bundle b = (Bundle) arr[i];
            String t = str(b.getCharSequence("text"));
            if (t.isEmpty()) continue;
            String who = "";
            if (Build.VERSION.SDK_INT >= 28) {
                Object p = b.getParcelable("sender_person");
                if (p instanceof Person) who = str(((Person) p).getName());
            }
            if (who.isEmpty()) who = str(b.getCharSequence("sender"));
            if (sb.length() > 0) sb.append('\n');
            if (!who.isEmpty()) sb.append(who).append(": ");
            sb.append(t);
        }
        return sb.toString();
    }

    private static String str(CharSequence c) { return c == null ? "" : c.toString().trim(); }

    /** His find-my-phone code arriving by SMS / WhatsApp: only the newest message counts, and each message once. */
    private void findPhoneCode(StatusBarNotification sbn, Bundle x) {
        String last = "";
        long msgTime = 0;
        Parcelable[] arr = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (arr != null && arr.length > 0 && arr[arr.length - 1] instanceof Bundle) {
            Bundle b = (Bundle) arr[arr.length - 1];
            last = str(b.getCharSequence("text"));
            msgTime = b.getLong("time", 0);
        }
        if (last.isEmpty()) last = str(x.getCharSequence(Notification.EXTRA_TEXT));
        FindPhone.check(this, sbn.getKey(), last, sbn.getPostTime(), msgTime);
    }

    private static void prune() {
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        Iterator<Map.Entry<String, Item>> it = items.entrySet().iterator();
        while (it.hasNext()) {
            Item i = it.next().getValue();
            if (i.when < cutoff || items.size() > MAX) it.remove(); else break;
        }
    }

    /** Newest first; app filter matches the app name loosely ("whatsapp", "sms", "messages"). */
    static List<Item> recent(String appFilter, int limit) {
        String f = appFilter == null ? "" : appFilter.trim().toLowerCase(Locale.ROOT);
        if (f.equals("sms") || f.equals("text")) f = "messag";
        List<Item> out = new ArrayList<>();
        synchronized (items) {
            prune();
            List<Item> all = new ArrayList<>(items.values());
            for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--) {
                Item x = all.get(i);
                if (f.isEmpty() || x.app.toLowerCase(Locale.ROOT).contains(f) || x.pkg.toLowerCase(Locale.ROOT).contains(f)) out.add(x);
            }
        }
        return out;
    }

    static Item get(int id) {
        synchronized (items) {
            for (Item i : items.values()) if (i.id == id) return i;
        }
        return null;
    }

    /** Sends a reply through the notification's own reply button. */
    static void reply(Context c, Item item, String message) throws Exception {
        Notification.Action a = item.reply;
        if (a == null) throw new IllegalStateException("no reply button");
        RemoteInput[] inputs = a.getRemoteInputs();
        Intent fill = new Intent();
        Bundle results = new Bundle();
        for (RemoteInput ri : inputs) results.putCharSequence(ri.getResultKey(), message);
        RemoteInput.addResultsToIntent(inputs, fill, results);
        a.actionIntent.send(c, 0, fill);
    }
}
