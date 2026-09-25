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

    @Override public void onListenerConnected() {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) for (StatusBarNotification sbn : active) add(sbn);
        } catch (Exception ignored) {}
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        add(sbn);
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null) CallControl.onRemoved(sbn.getKey());
    }

    private void add(StatusBarNotification sbn) {
        if (sbn == null || getPackageName().equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n != null && Notification.CATEGORY_CALL.equals(n.category)) {
            announceCall(sbn, n);
            return;
        }
        if (n == null || sbn.isOngoing()) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle x = n.extras;
        if (x == null) return;

        String title = str(x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        if (title.isEmpty()) title = str(x.getCharSequence(Notification.EXTRA_TITLE));
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
        maybeReadAloud(sbn, n, x, app, title, text);
    }

    // ---------------------------------------------------------------- read new messages aloud

    private static final Map<String, String> spoken = new java.util.HashMap<>();
    private static final Map<String, Long> lastFrom = new java.util.HashMap<>();

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
    private void maybeReadAloud(StatusBarNotification sbn, Notification n, Bundle x, String app, String from, String text) {
        Prefs p = new Prefs(this);
        boolean driving = p.driving();
        if (!(p.readMessages() || driving) || (p.night() && !driving)) return;
        if (!chatApp(sbn.getPackageName(), n, x)) return;
        if (x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) && !driving) return; // groups are too chatty
        if (MainActivity.inConversation || CallControl.busyWithCall()) return;
        if (!driving) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL) return; // Do Not Disturb
        }
        String[] lines = text.split("\n");
        String last = lines[lines.length - 1].trim();
        if (last.isEmpty()) return;
        if (last.length() > 220) last = last.substring(0, 220) + "…";
        long now = System.currentTimeMillis();
        synchronized (spoken) {
            if (last.equals(spoken.get(sbn.getKey()))) return; // the same message posted again
            spoken.put(sbn.getKey(), last);
            Long prev = lastFrom.get(from);
            if (prev != null && now - prev < 20000) return; // several quick messages: read the first only
            lastFrom.put(from, now);
        }
        if (now - sbn.getPostTime() > 60000) return; // old ones shown again after a reboot
        int id = 0;
        boolean canReply = false;
        synchronized (items) {
            Item it = items.get(sbn.getKey());
            if (it != null) { id = it.id; canReply = it.reply != null; }
        }
        // First only who and where; the message itself is read only if Anil says yes.
        String say = p.name() + ", " + (from.isEmpty() ? app : from) + " నుంచి " + app + " లో మెసేజ్ వచ్చింది.";
        String context = " [new message, notification id " + id + " (" + app + (canReply ? ", can reply with reply_to_notification" : "")
                + "). Its text: \"" + last + "\". Read it to him ONLY if he says yes (అవును/చదువు); if he says no, just say సరే. "
                + "After reading, ask 'రిప్లై ఇవ్వమంటారా?'. If he dictates a reply, read it back and ask 'పంపమంటారా?', send only after he says send.]";
        if (android.provider.Settings.canDrawOverlays(this)) {
            try {
                startActivity(new android.content.Intent(this, SheetActivity.class)
                        .putExtra(SheetActivity.EXTRA_ANNOUNCE, say)
                        .putExtra(SheetActivity.EXTRA_ANNOUNCE_ASK, "చదవమంటారా?")
                        .putExtra(SheetActivity.EXTRA_ANNOUNCE_CONTEXT, context)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
                return;
            } catch (Exception ignored) {}
        }
        // No panel: say only who wrote; if he calls Jarvis and says "చదువు", the brain has the message.
        Store.get(this).addChat("assistant", say + " చదవమంటారా?" + context, false);
        Announcer.say(this, say + " చదవాలంటే Jarvis అని పిలిచి, చదువు అనండి.");
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
