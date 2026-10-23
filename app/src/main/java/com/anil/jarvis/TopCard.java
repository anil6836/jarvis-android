package com.anil.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Jarvis's small card at the top of the screen (Anil, 22 Oct: the panel from the bottom only when he calls "Jarvis";
 * everything else up here). A new message, Jarvis's own question (cough, water, diary…) or a job Jarvis does by himself
 * comes as this card: it never takes the keyboard or dims the screen, and what he is doing goes on.
 * - A message (CardTalk): "చదవమంటారా?", read with the word lit, ⏸ / ▶ / ⏪, his reply sent only after he says so.
 * - Jarvis's question or job (CardAsk): said and heard here, the brain answers here (its words on the card too).
 * Its look: a small Jarvis orb (listening / thinking / speaking), the sender's photo, a colour for the kind (💬 blue,
 * health green, reminder amber), how long ago, how many wait under 📬. A tap or a pull down opens the whole text; a swipe
 * up sends it into the notification shade, where it keeps running (its buttons there, a tap brings it back — Anil, 23 Oct);
 * left alone it goes, and what wasn't heard waits under the dot (a question: a notification). Nothing is lost.
 * With the phone locked or the screen off there is no card: Jarvis says it and listens (voiceOnly).
 */
final class TopCard {
    /** A card's talk: CardTalk (a chat message) or CardAsk (Jarvis's question / job). */
    interface Flow {
        boolean talking();
        boolean busy();
        void stop();
        void stop(int yield);
    }

    static final String MSG = "msg", ASK = "ask", RUN = "run";
    /** 📞 A ringing call (CardCall), and the panel he was talking in, minimised here (CardPanel). Build 2. */
    static final String CALL = "call", PANEL = "panel";

    /** One card: a chat's new messages, or Jarvis's question / job. */
    static final class Msg {
        String kind = MSG;
        String app = "", from = "", pkg = "", say = "", ask = "", context = "";
        /** A WhatsApp voice note / audio / photo / video (one message), else null. */
        String media;
        /** When the message came (the media file must not be older). */
        long postedAt;
        final List<String> texts = new ArrayList<>();
        /** talk: Jarvis tells it by voice on the card (else the card only waits for a tap). */
        boolean group, typing, talk;
        int id;
        /** The sender's picture from the notification, or null. */
        android.graphics.drawable.Icon photo;
        /** RUN: the job for the brain, done at once. */
        String run = "";
        /** ASK / RUN: the card's title ("Jarvis"), else from the kind. */
        String title = "";
        /** No window: the phone is locked or the screen is off; Jarvis says it and listens. */
        volatile boolean voiceOnly;
        /** CALL: the line under the name (the app, a warning); PANEL: the panel itself. */
        String sub = "";
        SheetActivity panel;
        /** 📬: the notification the messages came on (gone = read in its app); how the talk starts (-1: ask first). */
        String nkey = "";
        int startHow = -1;
        /** 📬: one chat from the list (fromLater), or all of them in one go (laterAll, with the entries taken out). */
        boolean fromLater, laterAll;
        java.util.List<org.json.JSONObject> laterEntries;
        /** 📬 all: the one-line overview said before the chats ("" when the question said it already). */
        String laterOverview = "";
    }

    private static volatile TopCard showing;

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float d;
    private final Msg m;
    private final Prefs p;
    private final int accent;
    private LinearLayout view, buttons;
    private View chips;
    private boolean compact;
    private TextView line2, status, sub;
    private OrbView orb;
    private boolean touched, heard, expanded;
    private volatile boolean done;
    private int bodyLines = 2;
    private float downY;
    private final Runnable autoHide = () -> close(true);
    /** The talk on this card (null: none yet). */
    private Flow talk;
    /** A hide the talk asked for (and whether the messages then wait under the dot). */
    private boolean laterOnHide, hidePending;
    private long hideMs;
    private final Runnable hide = () -> { hidePending = false; close(laterOnHide); };
    /** ASK / RUN: what to do when it goes unheard (CardAsk: a notification, so the question isn't lost). */
    Runnable onUnheard;

    private TopCard(AccessibilityService svc, Msg m) {
        this.svc = svc;
        this.m = m;
        this.p = new Prefs(svc);
        wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
        d = svc.getResources().getDisplayMetrics().density;
        accent = accent(m);
    }

    private int dp(float v) { return Math.round(v * d); }

    private float sp(float v) { return v + (p.cardBig() ? 2.5f : 0); }

    /**
     * Main thread. A card already up is put off first (its messages under the dot); one that is talking with him is not:
     * then false, and the caller waits its turn (a message) or leaves a notification (Jarvis's question / job).
     */
    static boolean show(AccessibilityService svc, Msg m) {
        boolean first = CALL.equals(m.kind) || PANEL.equals(m.kind); // (a call, or his own talk: before any other card)
        if (PANEL.equals(m.kind) && callCardUp()) return false; // (never over a call's card: ringing, or its talk after a cut)
        if (ASK.equals(m.kind) || RUN.equals(m.kind)) pendingAt = 0; // (this is the question / job that was on its way)
        if (!first && busy()) return false;
        if (first) LaterBox.closeIfOpen(); // (a call / his own talk: the 📬 list goes)
        if (showing != null) showing.close(true, first ? false : true); // (a talk on it stops: its messages under 📬, a question a notification)
        TopCard t = new TopCard(svc, m);
        if (!m.voiceOnly && !t.build()) return false;
        showing = t;
        if (m.voiceOnly) t.watchUnlock();
        if (MSG.equals(m.kind)) {
            if (m.talk || m.voiceOnly) t.startTalk(m.startHow >= 0 ? m.startHow : CardTalk.START_ASK);
            else t.main.postDelayed(t.autoHide, t.p.cardSecs() * 1000L);
        } else if (CALL.equals(m.kind)) {
            t.heard = true; // (nothing of a call waits under 📬)
            CardCall c = new CardCall(svc, t, m);
            t.talk = c;
            c.start();
        } else if (PANEL.equals(m.kind)) {
            t.heard = true;
            CardPanel c = new CardPanel(t, m.panel);
            t.talk = c;
            c.start();
        } else {
            CardAsk a = new CardAsk(svc, t, m);
            t.talk = a;
            a.start();
        }
        return true;
    }

    /**
     * Main thread. 📞 A ringing call on the card (who: the name line; say / sayShort: what Jarvis says the first / second
     * time; sub: "ఫోన్ కాల్" / "WhatsApp కాల్" and a warning). It comes before any other card. False: it can't (Jarvis's
     * screen access is off): the caller uses the panel as before.
     */
    static boolean call(Context c, String who, String say, String sayShort, String sub, boolean warn, long ringAt) {
        if (!JarvisAccessibility.enabled()) return false;
        Msg m = new Msg();
        m.kind = CALL;
        m.title = "📞 " + who;
        m.say = say == null ? "" : say;
        m.ask = sayShort == null || sayShort.isEmpty() ? m.say : sayShort;
        m.sub = sub == null ? "" : sub;
        m.group = warn; // (a warning: the card red)
        m.postedAt = ringAt;
        m.voiceOnly = !screenUsable(c);
        return JarvisAccessibility.messageCard(m);
    }

    /** Main thread. The panel he was talking in, minimised into the card (the talk goes on here); null if it can't be. */
    static CardPanel panel(Context c, SheetActivity a) {
        if (!JarvisAccessibility.enabled() || a == null) return null;
        Msg m = new Msg();
        m.kind = PANEL;
        m.panel = a;
        m.postedAt = System.currentTimeMillis();
        m.voiceOnly = !screenUsable(c);
        if (!JarvisAccessibility.messageCard(m)) return null;
        TopCard t = showing;
        return t != null && t.talk instanceof CardPanel ? (CardPanel) t.talk : null;
    }

    /** A ringing call's card is up (the loudspeaker is pressed only once it is gone). */
    static boolean callUp() {
        TopCard t = showing;
        Flow f = t == null ? null : t.talk;
        return t != null && CALL.equals(t.m.kind) && !t.done && f instanceof CardCall && ((CardCall) f).ringingNow();
    }

    /** A call's card is up (ringing, or the talk after a cut). */
    static boolean callCardUp() {
        TopCard t = showing;
        return t != null && CALL.equals(t.m.kind) && !t.done;
    }

    /** A screen of Jarvis's own came up (the app): message / question / panel cards step aside; a ringing call's card stays. */
    static void stepAsideExceptCall() {
        LaterBox.closeIfOpen();
        TopCard t = showing;
        if (t == null || CALL.equals(t.m.kind)) return;
        stepAside(t);
    }

    // ================================================================ where Jarvis's own words go

    /** The screen is on and unlocked, by the running service (false without it). */
    static boolean screenUsableNow() {
        JarvisAccessibility s = JarvisAccessibility.service();
        return s != null && screenUsable(s);
    }

    /** The screen is on and unlocked (a card can be seen); else Jarvis talks by voice only. */
    static boolean screenUsable(Context c) {
        android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
        android.app.KeyguardManager km = c.getSystemService(android.app.KeyguardManager.class);
        return pm != null && pm.isInteractive() && km != null && !km.isKeyguardLocked();
    }

    /**
     * Jarvis's own question (ASK: say + ask, the brain's context) or job (RUN: say = the job): the top card when the screen
     * is on and unlocked, else by voice only. False: it can't now (Jarvis's screen access is off, the panel or the camera
     * is up, a call, another card is busy) — the caller then leaves a notification as before.
     */
    static boolean jarvis(Context c, String kind, String say, String ask, String context, Runnable orElse) {
        if (!JarvisAccessibility.enabled() || SheetActivity.open || JarvisCamera.open || CallControl.busyWithCall()) return false;
        synchronized (TopCard.class) { // (two in one tick: the second waits, not both on the way)
            if (busy()) return false;
            pendingAt = android.os.SystemClock.elapsedRealtime();
        }
        Msg m = new Msg();
        m.kind = kind;
        if (RUN.equals(kind)) m.run = say == null ? "" : say;
        else { m.say = say == null ? "" : say; m.ask = ask == null ? "" : ask; m.context = context == null ? "" : context; }
        m.postedAt = System.currentTimeMillis();
        m.voiceOnly = !screenUsable(c);
        boolean ok = JarvisAccessibility.messageCard(m, () -> { // (the card couldn't be put up after all: the caller's way, as before)
            pendingAt = 0;
            if (orElse != null) try { orElse.run(); } catch (Exception ignored) {}
        });
        if (!ok) pendingAt = 0;
        return ok;
    }

    /** A question / job posted to the main thread and not on the card yet: busy() counts it for a few seconds. */
    private static volatile long pendingAt;

    /** Main thread. Gone at once (Jarvis's service is going); unheard messages wait under the dot. */
    static void dismissNow() {
        TopCard t = showing;
        if (t != null) t.close(true, false);
        TopCard c = leaving; // one still sliding away
        if (c != null) c.removeView();
    }

    /** Main thread. A screenshot is about to be taken: a card on the screen goes at once (a voice-only one has none). */
    static void dismissShown() {
        TopCard t = showing;
        if (t != null && t.inShade) return; // (down in the shade: not on the screen)
        if (t != null && (CALL.equals(t.m.kind) || PANEL.equals(t.m.kind))) { t.hideBriefly(); return; } // (a call / his own talk goes on: only out of the picture)
        if (t != null && (!t.m.voiceOnly || screenUsable(t.svc))) t.close(true, false); // (a voice-only talk on an unlocked phone too)
        TopCard c = leaving;
        if (c != null) c.removeView();
    }

    /**
     * Main thread. Another Jarvis screen (the panel, a call, the app, the camera) is starting: the card steps aside before
     * it talks (unheard messages under the dot), leaving the talk state to that screen.
     */
    static void stepAside() {
        LaterBox.closeIfOpen(); // (another Jarvis screen: the 📬 list goes)
        TopCard t = showing;
        if (t == null) return;
        stepAside(t);
    }

    /** A call notification came or changed: a message / question card steps aside; the call card and his own talk stay. */
    static void stepAsideForCall() {
        TopCard t = showing;
        if (t == null || CALL.equals(t.m.kind) || PANEL.equals(t.m.kind)) return;
        stepAside(t);
    }

    private static void stepAside(TopCard t) {
        LaterBox.closeIfOpen(); // (the 📬 list goes too)
        Flow c = t.talk;
        t.talk = null;
        if (c != null) c.stop(CardTalk.YIELD_BEFORE);
        t.close(!t.heard);
    }

    /** Jarvis is talking or listening on a card right now. */
    static boolean talkingNow() {
        TopCard t = showing;
        Flow c = t == null ? null : t.talk;
        return c != null && c.talking();
    }

    /** A card is busy with him (talking, or waiting for his tap mid-reply): a new card must wait. */
    static boolean busy() {
        if (android.os.SystemClock.elapsedRealtime() - pendingAt < 5000) return true;
        if (LaterBox.isOpen() && TopCard.screenUsableNow()) return true; // (he is looking at the 📬 list: a new card waits, not over it)
        TopCard t = showing;
        Flow c = t == null ? null : t.talk;
        return c != null && c.busy();
    }

    /** Out of a screenshot for a moment (the floating button's screen features), then back. */
    private void hideBriefly() {
        if (view == null || removed || done) return;
        view.setVisibility(View.INVISIBLE);
        main.postDelayed(() -> { if (view != null && !done) view.setVisibility(View.VISIBLE); }, 2500);
    }

    // ================================================================ in the notification shade (Anil, 23 Oct)

    /** Swiped up: off the screen, but still running in the phone's notification shade (its buttons there; a tap: back). */
    private volatile boolean inShade;
    /** The note's tag (its own: no other note of Jarvis's is replaced by it). */
    private static final String SHADE_TAG = "jarvis_card";
    private static final String SHADE_CH = "jarvis_card";
    /** Each new look of the shade note (counted across cards): its intents carry it, so a tap on an old look does nothing. */
    private static int shadeCount;
    private int shadeVer = -1;
    /** The buttons the note shows now (a tap runs these, never newer ones it hasn't shown). */
    private Object[] shadeBtns;
    private int[] shadePick = new int[0];
    private String shadeSig = "";
    private final Runnable shadePost = this::postShade;
    /** The last few looks' buttons, by their number. */
    private final java.util.LinkedHashMap<Integer, Object[]> shadeLooks = new java.util.LinkedHashMap<>();

    /** Seen by him now: on the screen (not voice only, not down in the shade). A countdown runs only while seen. */
    boolean seen() { return !m.voiceOnly && !inShade && !done; }

    /** Notes can be shown (allowed, and the card's channel not switched off). */
    private boolean notesOk() {
        try {
            android.app.NotificationManager nm = svc.getSystemService(android.app.NotificationManager.class);
            if (nm == null || !nm.areNotificationsEnabled()) return false;
            android.app.NotificationChannel ch = nm.getNotificationChannel(SHADE_CH);
            return ch == null || ch.getImportance() != android.app.NotificationManager.IMPORTANCE_NONE;
        } catch (Exception e) {
            return false;
        }
    }

    private void toShade() {
        if (done || inShade) return;
        if (!notesOk()) { close(true); return; } // (no note can show: as before, it goes; unheard: under 📬)
        inShade = true;
        main.removeCallbacks(autoHide); // (a message waiting: it waits down there, not gone in a few seconds)
        if (hidePending) main.postDelayed(hide, Math.max(hideMs, 3000)); // (a talk that was to end soon still ends: the touch had held it)
        if (view != null && !removed) {
            leaving = this;
            view.animate().translationY(-dp(150)).alpha(0f).setDuration(180).withEndAction(this::removeView).start();
            main.postDelayed(this::removeView, 400);
        }
        shadeSig = "";
        postShade();
    }

    /** A tap on the shade note: the card back on the screen as it is now. */
    private void fromShade() {
        if (done || !inShade) return;
        if (!screenUsable(svc)) return; // (locked: it stays down there)
        removed = false;
        m.voiceOnly = false;
        if (!build()) { shadeSig = ""; postShade(); return; } // (no window: the note stays)
        inShade = false;
        main.removeCallbacks(shadePost);
        cancelShade(svc);
        replay();
        closeShadePanel(svc);
        if (talk == null && MSG.equals(m.kind)) main.postDelayed(autoHide, p.cardSecs() * 2000L);
    }

    /** The shade is pulled back up after a tap, so the card is seen at once. */
    private static void closeShadePanel(AccessibilityService svc) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) svc.performGlobalAction(15); // GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
            else svc.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        } catch (Exception ignored) {}
    }

    /** The screen went off with the card down in the shade: a talk carries on by voice only (no taps it can't get); else it goes. */
    private void shadeScreenOff() {
        Flow t = talk;
        if (t != null && t.busy()) { m.voiceOnly = true; watchUnlock(); shadeSig = ""; shadeChanged(); return; }
        close(true, false);
    }

    private boolean shadeQueued;

    private void shadeChanged() {
        if (!inShade || done || shadeQueued) return;
        shadeQueued = true;
        main.postDelayed(shadePost, 500); // (two looks a second at most, while it keeps changing: the shade isn't a screen)
    }

    private static PendingIntent shadeIntent(Context c, String op, int ver, int idx) {
        Intent i = new Intent(c, CardShade.class).setAction(CardShade.ACTION).putExtra("op", op).putExtra("ver", ver).putExtra("idx", idx)
                .setData(android.net.Uri.parse("jarvis-card://" + ver + "/" + op + "/" + idx)); // (its own intent: an old look's never gets newer extras)
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The card's note in the shade: its title, what Jarvis is doing, the text, and up to 3 of its own buttons. */
    private void postShade() {
        shadeQueued = false;
        main.removeCallbacks(shadePost);
        if (!inShade || done) return;
        try {
            CharSequence text = lastBody != null ? lastBody : line2 != null ? line2.getText() : "";
            String body = Otp.spoken((lastStatus.isEmpty() ? "" : lastStatus + "\n") + (text == null ? "" : text.toString())); // (a code: never on the lock screen)
            Object[] b = lastButtons;
            int nb = b == null ? 0 : b.length / 2;
            int[] pick;
            if (nb <= 3 || CALL.equals(m.kind)) { pick = new int[Math.min(3, nb)]; for (int i = 0; i < pick.length; i++) pick[i] = i; } // (a call: ఎత్తు / కట్ / సైలెంట్)
            else pick = new int[]{0, 1, nb - 1}; // (the first two and the last: usually ✕)
            Flow f0 = talk;
            boolean running0 = f0 != null && f0.busy();
            StringBuilder sig = new StringBuilder(title()).append('|').append(body).append('|').append(m.voiceOnly).append('|').append(running0);
            for (int i : pick) sig.append('|').append(b[2 * i]);
            if (sig.toString().equals(shadeSig) && shadeBtns == b) return;
            final int ver = ++shadeCount; // (committed to this card only once the note is up)
            android.app.NotificationManager nm = svc.getSystemService(android.app.NotificationManager.class);
            if (nm == null) return;
            android.app.NotificationChannel ch = new android.app.NotificationChannel(SHADE_CH, "Jarvis పైన కార్డ్ (నోటిఫికేషన్ బార్‌లో)",
                    android.app.NotificationManager.IMPORTANCE_LOW); // (no sound, no pop-up: it must not cover anything again)
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            Flow f = talk;
            boolean running = f != null && f.busy();
            String sub = subLine();
            android.app.Notification.Builder nb2 = new android.app.Notification.Builder(svc, SHADE_CH)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle(title())
                    .setContentText(body.isEmpty() ? sub : body)
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(body.isEmpty() ? sub : body))
                    .setSubText(running ? "🔊 Jarvis నడుస్తున్నాడు · నొక్కితే కార్డ్" : "నొక్కితే కార్డ్")
                    .setOnlyAlertOnce(true)
                    .setOngoing(running)
                    .setShowWhen(false)
                    .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(new android.app.Notification.Builder(svc, SHADE_CH).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                            .setContentTitle("Jarvis").build()) // (the lock screen shows only "Jarvis" when he hides contents there)
                    .setLocalOnly(true) // (not bridged to the watch)
                    .setContentIntent(shadeIntent(svc, CardShade.OP_SHOW, ver, -1))
                    .setDeleteIntent(shadeIntent(svc, CardShade.OP_GONE, ver, -1));
            if (!m.voiceOnly) { // (locked: no buttons that act unseen; a tap there needs him to unlock anyway)
                for (int i : pick) {
                    android.app.Notification.Action.Builder ab = new android.app.Notification.Action.Builder(null, (String) b[2 * i],
                            shadeIntent(svc, CardShade.OP_BUTTON, ver, i));
                    if (android.os.Build.VERSION.SDK_INT >= 31) ab.setAuthenticationRequired(true); // (from the lock screen: unlock first)
                    nb2.addAction(ab.build());
                }
            }
            nm.notify(SHADE_TAG, 1, nb2.build());
            shadeSig = sig.toString();
            shadeVer = ver;
            shadeBtns = b;
            shadePick = pick;
            shadeLooks.put(ver, b == null ? new Object[0] : b); // (a tap on a look a moment old still runs, if its buttons are still these)
            while (shadeLooks.size() > 8) shadeLooks.remove(shadeLooks.keySet().iterator().next());
        } catch (Exception ignored) {}
    }

    static void cancelShade(Context c) {
        try {
            android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
            if (nm != null) nm.cancel(SHADE_TAG, 1);
        } catch (Exception ignored) {}
    }

    /** Main thread. From the shade note (CardShade): one of the card's buttons, a tap (the card back), or swiped away (closed). */
    static void shade(Context c, String op, int ver, int idx) {
        TopCard t = showing;
        if (t == null || !t.inShade || t.done) { cancelShade(c); return; } // (an old note: the card is gone)
        if (CardShade.OP_GONE.equals(op)) { t.close(true, false); return; } // (swiped away down there — only he does that: closed; unheard: under 📬 / a notification)
        if (CardShade.OP_SHOW.equals(op)) { t.fromShade(); return; } // (a tap on the note: the card back, whichever look it was)
        if (!CardShade.OP_BUTTON.equals(op)) return;
        Object[] b = t.shadeLooks.get(ver);
        if (b == null || b != t.lastButtons) { t.shadeSig = ""; t.postShade(); return; } // (its buttons changed meanwhile: the new look, nothing run)
        if (idx < 0 || 2 * idx + 1 >= b.length) return;
        t.touched = true;
        t.main.removeCallbacks(t.autoHide);
        t.main.removeCallbacks(t.hide); // (as a tap on the card itself)
        String label = String.valueOf(b[2 * idx]);
        try { ((Runnable) b[2 * idx + 1]).run(); } catch (Exception ignored) {}
        // a button that opens a screen (the panel, a photo, a video, the chat's app): the shade goes up so it is seen
        if (t.done || label.contains("⬆️") || label.contains("👁") || label.contains("▶ ప్లే") || label.contains("📲") || label.contains("📞 ఎత్తు"))
            closeShadePanel(t.svc);
    }

    /** A card closed but not yet off the screen (its slide-out). */
    private static TopCard leaving;

    /**
     * The screen went off: a card must not stay over the lock screen. A talk going on carries on by voice only (as when
     * locked from the start); a card only waiting goes (its messages under the dot).
     */
    private final android.content.BroadcastReceiver screenOff = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (inShade && !done) { shadeScreenOff(); return; }
            Flow t = talk;
            if (t != null && t.busy() && !done) { toVoiceOnly(); return; }
            close(true, false);
        }
    };
    /** Unlocked while Jarvis talks by voice only: the card comes up with the talk as it is now (⏸ / ▶ and the text). */
    private final android.content.BroadcastReceiver unlocked = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (done || !m.voiceOnly || talk == null || !screenUsable(svc)) return;
            if (inShade) { m.voiceOnly = false; shadeSig = ""; shadeChanged(); return; } // (he put it in the shade: it stays there, its buttons back)
            m.voiceOnly = false;
            removed = false;
            if (!build()) { m.voiceOnly = true; return; }
            replay();
        }
    };
    private boolean listeningScreen, listeningUnlock, removed;
    /** What the talk last set (kept when there is no window, so a card built on unlock shows it). */
    private CharSequence lastBody;
    private int lastBodyLines = 2, lastOrb = OrbView.IDLE;
    private String lastStatus = "";
    private Object[] lastButtons;

    /** No window from now on (the screen went off): the talk goes on by voice; unlocking brings the card back. */
    private void toVoiceOnly() {
        m.voiceOnly = true;
        if (listeningScreen) { listeningScreen = false; try { svc.unregisterReceiver(screenOff); } catch (Exception ignored) {} }
        removeView();
        watchUnlock();
    }

    private void watchUnlock() {
        if (listeningUnlock) return;
        try {
            svc.registerReceiver(unlocked, new android.content.IntentFilter(Intent.ACTION_USER_PRESENT));
            listeningUnlock = true;
        } catch (Exception ignored) {}
    }

    /** A card built later (on unlock): the talk's text, status, buttons and orb as they are now. */
    private void replay() {
        boolean c = compact;
        compact = false;
        compact(c);
        if (lastBody != null) body(lastBody, lastBodyLines);
        status(lastStatus);
        if (lastButtons != null) buttons(lastButtons);
        orb(lastOrb);
    }

    // ================================================================ its look

    /** The kind's colour: a chat blue; Jarvis's question green for health, amber for a reminder, violet for a job; else cyan. */
    static int accent(Msg m) {
        if (MSG.equals(m.kind)) return 0xFF3B82F6;
        if (CALL.equals(m.kind)) return m.group ? 0xFFEF4444 : 0xFF22C55E; // (a warning about the number: red)
        if (PANEL.equals(m.kind)) return 0xFF8B5CF6;
        if (RUN.equals(m.kind)) return 0xFFA855F7;
        String t = (m.title + " " + m.say + " " + m.ask).toLowerCase(Locale.ROOT);
        for (String w : HEALTH) if (t.contains(w)) return 0xFF22C55E;
        for (String w : REMIND) if (t.contains(w)) return 0xFFF59E0B;
        return 0xFF22D3EE;
    }

    private static final String[] HEALTH = {"దగ్గు", "తుమ్ము", "జలుబు", "మందు", "మాత్ర", "నీళ్ళు", "నీళ్లు", "ఆరోగ్య", "షుగర్", "bp", "బీపీ", "నిద్ర", "నడ",
            "వ్యాయామ", "శ్వాస", "ఒత్తిడి", "అలసి", "నొప్పి", "జ్వరం"};
    private static final String[] REMIND = {"గుర్తు", "బిల్", "టైమ్", "డ్యూటీ", "ఛార్జ్", "పండుగ", "హ్యాండోవర్", "కట్టాలి", "గడువు", "డైరీ"};

    /** The card's title line. */
    private String title() {
        if (MSG.equals(m.kind) && m.laterAll) return "📬 తర్వాత మెసేజ్‌లు";
        if (MSG.equals(m.kind)) return "💬 " + m.from + (m.group ? " గ్రూప్" : "");
        if (PANEL.equals(m.kind)) return "🤖 Jarvis";
        if (!m.title.isEmpty()) return m.title;
        return (accent == 0xFF22C55E ? "🩺 " : accent == 0xFFF59E0B ? "⏰ " : RUN.equals(m.kind) ? "🌙 " : "🤖 ") + "Jarvis";
    }

    /** The small line under the title: the app, how long ago, how many, how many wait under 📬. */
    private String subLine() {
        StringBuilder b = new StringBuilder();
        if (MSG.equals(m.kind)) {
            b.append(m.app);
            long ago = m.postedAt > 0 ? (System.currentTimeMillis() - m.postedAt) / 60_000 : 0;
            b.append(" · ").append(ago < 1 ? "ఇప్పుడే" : ago + " ని. క్రితం");
            if (m.texts.size() > 1) b.append(" · ").append(m.texts.size()).append(" మెసేజ్‌లు");
        } else if (CALL.equals(m.kind)) {
            b.append(m.sub.isEmpty() ? "కాల్ వస్తోంది" : m.sub);
            return b.toString(); // (no 📬 count on a ringing call)
        } else if (PANEL.equals(m.kind)) {
            b.append("మీతో మాట్లాడుతున్నాడు");
        } else {
            b.append(RUN.equals(m.kind) ? "Jarvis చేస్తున్నాడు" : "Jarvis అడుగుతున్నాడు");
        }
        int later = 0;
        try { later = LaterMessages.count(svc); } catch (Exception ignored) {}
        if (later > 0) b.append(" · 📬 ").append(later);
        return b.toString();
    }

    private boolean build() {
        view = new LinearLayout(svc);
        view.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xF7081A2E, 0xF70B1222});
        bg.setStroke(Math.max(1, dp(1.2f)), FaceRig.withAlpha(accent, 0xB0));
        bg.setCornerRadius(dp(20));
        view.setBackground(bg);
        view.setElevation(dp(8));
        view.setPadding(dp(12), dp(10), dp(10), dp(6));

        // the head: Jarvis's orb · who / what · the sender's photo or the app's icon
        LinearLayout head = new LinearLayout(svc);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        orb = new OrbView(svc);
        orb.setState(OrbView.IDLE);
        head.addView(orb, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout names = new LinearLayout(svc);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(10), 0, dp(6), 0);
        TextView line1 = new TextView(svc);
        line1.setText(title());
        line1.setTextColor(accent);
        line1.setTextSize(sp(14.5f));
        line1.setTypeface(Typeface.DEFAULT_BOLD);
        line1.setSingleLine(true);
        line1.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(line1);
        sub = new TextView(svc);
        sub.setText(subLine());
        sub.setTextColor(0xFF8AA4BF);
        sub.setTextSize(sp(11.5f));
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(sub);
        head.addView(names, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Drawable pic = appIcon();
        if (pic != null || MSG.equals(m.kind) && m.photo != null) {
            ImageView iv = new ImageView(svc);
            if (pic != null) iv.setImageDrawable(pic);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setClipToOutline(true);
            GradientDrawable round = new GradientDrawable();
            round.setShape(GradientDrawable.OVAL);
            iv.setBackground(round);
            head.addView(iv, new LinearLayout.LayoutParams(dp(32), dp(32)));
            if (MSG.equals(m.kind) && m.photo != null) { // the sender's photo, loaded off the main thread
                try { m.photo.loadDrawableAsync(svc, dr -> { if (dr != null && !done) iv.setImageDrawable(dr); }, main); } catch (Exception ignored) {}
            }
        }
        view.addView(head);

        // the text: the message (lit word by word while read), Jarvis's question or his answer
        line2 = new TextView(svc);
        int n = m.texts.size();
        if (MSG.equals(m.kind)) line2.setText(n == 0 ? "" : Otp.shown(m.texts.get(n - 1)));
        else if (CALL.equals(m.kind)) line2.setText(m.say);
        else if (PANEL.equals(m.kind)) line2.setText(m.panel == null ? "" : m.panel.miniText());
        else line2.setText(RUN.equals(m.kind) ? "చూస్తున్నాను…" : (m.say + (m.ask.isEmpty() ? "" : "\n" + m.ask)).trim());
        line2.setTextColor(0xFFF2F7FF);
        line2.setTextSize(sp(14.5f));
        line2.setLineSpacing(0, 1.12f);
        line2.setMaxLines(MSG.equals(m.kind) ? 2 : 4);
        bodyLines = MSG.equals(m.kind) ? 2 : 4;
        line2.setEllipsize(TextUtils.TruncateAt.END);
        line2.setPadding(dp(2), dp(6), 0, dp(2));
        view.addView(line2); // (scrolled by code to follow the reading; not by touch, so a swipe anywhere moves the card)
        status = new TextView(svc);
        status.setTextColor(FaceRig.withAlpha(accent, 0xEE));
        status.setTextSize(sp(12.5f));
        status.setMaxLines(3);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setPadding(dp(2), dp(2), 0, 0);
        status.setVisibility(View.GONE);
        view.addView(status);

        // the buttons: chips in one row that scrolls sideways when there are many
        HorizontalScrollView hs = new HorizontalScrollView(svc);
        chips = hs;
        hs.setHorizontalScrollBarEnabled(false);
        buttons = new LinearLayout(svc);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, dp(6), 0, dp(2));
        hs.addView(buttons);
        if (MSG.equals(m.kind) && !m.talk && talk == null && lastButtons == null) { // the talk sets its own buttons
            if (m.group || n >= 3) buttons("🔊 చదువు", (Runnable) () -> startTalk(CardTalk.START_READ), "📝 సారాంశం", (Runnable) () -> startTalk(CardTalk.START_SUMMARY),
                    "⏰ తర్వాత", (Runnable) () -> close(true));
            else buttons("🔊 చదువు", (Runnable) () -> startTalk(CardTalk.START_READ), "⏰ తర్వాత", (Runnable) () -> close(true));
        }
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.END;
        view.addView(hs, hp);
        view.setOnTouchListener(this::swipe);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(svc.getResources().getDisplayMetrics().widthPixels - dp(16),
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, // (a talk isn't cut by the screen timeout)
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(CALL.equals(m.kind) ? 200 : p.cardLow() ? 110 : 30); // (a call: below the phone's own call pop-up and its buttons)
        try {
            wm.addView(view, lp);
        } catch (Exception e) {
            return false;
        }
        view.setTranslationY(-dp(140));
        view.setAlpha(0f);
        view.animate().translationY(0).alpha(1f).setDuration(240).start();
        if (!listeningScreen) try {
            svc.registerReceiver(screenOff, new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF));
            listeningScreen = true;
        } catch (Exception ignored) {}
        return true;
    }

    /** The app's icon (the sender's photo replaces it once loaded); null for Jarvis's own cards. */
    private Drawable appIcon() {
        if (!MSG.equals(m.kind)) return null;
        try { if (!m.pkg.isEmpty()) return svc.getPackageManager().getApplicationIcon(m.pkg); } catch (Exception ignored) {}
        return null;
    }

    private TextView button(String label, View.OnClickListener l) {
        TextView t = new TextView(svc);
        t.setText(label);
        t.setTextColor(0xFFE6F6FF);
        t.setTextSize(sp(13.5f));
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(FaceRig.withAlpha(accent, 0x2E));
        chip.setStroke(Math.max(1, dp(1)), FaceRig.withAlpha(accent, 0x66));
        chip.setCornerRadius(dp(16));
        t.setBackground(chip);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(6));
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> { if (done) return; touched = true; main.removeCallbacks(autoHide); main.removeCallbacks(hide); l.onClick(v); });
        return t;
    }

    /** Swipe up: gone (the messages wait under the dot). Pull down, or a tap: the whole text. */
    private boolean swipe(View v, MotionEvent e) {
        if (done) return true; // going away: a touch must not stop that
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getRawY();
                main.removeCallbacks(autoHide);
                main.removeCallbacks(hide); // (re-set when the finger lifts, longer if he opened the text)
                return true;
            case MotionEvent.ACTION_MOVE:
                view.setTranslationY(Math.min(0, e.getRawY() - downY));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float dy = e.getRawY() - downY;
                if (dy < -dp(30)) { toShade(); return true; } // (swiped up: into the notification shade, still running — Anil, 23 Oct)
                view.animate().translationY(0).setDuration(120).start();
                if (e.getActionMasked() == MotionEvent.ACTION_UP && (dy > dp(30) || Math.abs(dy) < dp(8))) { // pulled down / tapped: open or fold the text
                    touched = true;
                    if (compact) compact(false); // (the slim pill tapped: the full card with its buttons)
                    else setExpanded(!expanded || dy > dp(30));
                }
                if (talk == null) main.postDelayed(autoHide, touched ? p.cardSecs() * 2000L : 4000); // (opened to read: longer)
                else if (hidePending) main.postDelayed(hide, Math.max(hideMs, p.cardSecs() * 2000L));
                return true;
            }
            default:
                return false;
        }
    }

    /** The whole text (every message of this card), or back to its short look. */
    private void setExpanded(boolean on) {
        if (line2 == null) return;
        expanded = on;
        line2.setEllipsize(on || bodyLines > 2 ? null : TextUtils.TruncateAt.END);
        line2.setMaxLines(on ? 16 : bodyLines);
        if (on && MSG.equals(m.kind) && talk == null && m.texts.size() > 1) { // (not read yet: all of them, not only the newest)
            StringBuilder b = new StringBuilder();
            for (String t : m.texts) b.append(b.length() == 0 ? "" : "\n").append(Otp.shown(t));
            line2.setText(b);
        }
        line2.scrollTo(0, 0);
    }

    /** 🔊 / 📝 tapped on a card without talk: Jarvis reads it (or sums it up) on the card, then asks about a reply. */
    private void startTalk(int how) {
        if (talk != null || done) return;
        main.removeCallbacks(autoHide);
        CardTalk c = new CardTalk(svc, this, m);
        talk = c;
        c.start(how);
    }

    // ================================================================ for the talks (main thread)

    /** The small line under the text: what Jarvis is doing ("🎙️ వింటున్నాను…"); empty hides it. */
    void status(String s) {
        lastStatus = s == null ? "" : s;
        shadeChanged();
        if (done || status == null) return;
        status.setText(s == null ? "" : s);
        status.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** The orb: OrbView.IDLE / LISTENING / THINKING / SPEAKING. */
    void orb(int state) { lastOrb = state; if (!done && orb != null) orb.setState(state); }

    /** The microphone's loudness while listening (0..1). */
    void level(float l) { if (!done && orb != null) orb.setLevel(l); }

    /** The text area: the messages being read, a summary, Jarvis's answer, or his reply. */
    void body(CharSequence s, int maxLines) {
        lastBody = s;
        lastBodyLines = maxLines;
        shadeChanged();
        if (done || line2 == null) return;
        bodyLines = maxLines;
        int lines = expanded ? Math.max(16, maxLines) : maxLines;
        line2.setEllipsize(lines <= 2 ? TextUtils.TruncateAt.END : null);
        line2.setMaxLines(lines);
        line2.setText(s);
        line2.scrollTo(0, 0);
    }

    /** The word being read now, lit, and scrolled into view. */
    void highlight(String text, int start, int end) {
        if (done || line2 == null || text == null) return;
        android.text.SpannableString sp = new android.text.SpannableString(text);
        int a = Math.max(0, Math.min(start, text.length())), b = Math.max(a, Math.min(end, text.length()));
        if (b > a) sp.setSpan(new android.text.style.BackgroundColorSpan(FaceRig.withAlpha(accent, 0x70)), a, b, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line2.setText(sp);
        android.text.Layout l = line2.getLayout();
        if (l != null && line2.getHeight() > 0) {
            int y = l.getLineTop(l.getLineForOffset(a)) - line2.getHeight() / 3;
            int max = Math.max(0, l.getHeight() - line2.getHeight() + line2.getTotalPaddingTop() + line2.getTotalPaddingBottom());
            line2.scrollTo(0, Math.max(0, Math.min(y, max)));
        }
    }

    /** The slim pill ("🎙️ Jarvis వింటున్నాడు…": the orb and one line) while he only has to speak; the full card again after. */
    void compact(boolean on) {
        if (on && touched) on = false; // (he tapped it open: it stays the full card)
        if (compact == on) return;
        compact = on;
        if (done || line2 == null) return;
        line2.setVisibility(on ? View.GONE : View.VISIBLE);
        if (sub != null) sub.setVisibility(on ? View.GONE : View.VISIBLE);
        if (chips != null) chips.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    /** The buttons for this step: label, Runnable, label, Runnable… */
    void buttons(Object... labelThenAction) {
        lastButtons = labelThenAction;
        shadeChanged();
        if (done || buttons == null) return;
        buttons.removeAllViews();
        for (int i = 0; i + 1 < labelThenAction.length; i += 2) {
            final Runnable r = (Runnable) labelThenAction[i + 1];
            buttons.addView(button((String) labelThenAction[i], v -> r.run()));
        }
    }

    /** Heard now (read, summed up, played, answered or declined): closing no longer puts it off for later. */
    void heardNow() { heard = true; }

    boolean heard() { return heard; }

    /** Read only in part (held, or a reply not sent): if the card goes now, the messages wait under the dot. */
    void notHeard() { heard = false; }

    // ================================================================ 📬 read from the list (LaterBox)

    /** Main thread. One chat from 📬, read on the card (a voice note played, a photo described), then the reply question. */
    static boolean later(AccessibilityService svc, org.json.JSONObject row) {
        if (svc == null || row == null || busy()) return false;
        org.json.JSONObject e = LaterMessages.find(svc, row.optString("app"), row.optString("from")); // (as it is now: texts that came since the list was drawn too)
        if (e == null) return false;
        Msg m = new Msg();
        m.kind = MSG;
        m.app = e.optString("app");
        m.from = e.optString("from");
        m.pkg = e.optString("pkg");
        m.nkey = e.optString("key");
        m.group = e.optBoolean("group");
        org.json.JSONArray t = e.optJSONArray("texts");
        for (int i = 0; t != null && i < t.length(); i++) m.texts.add(t.optString(i));
        String media = e.optString("media");
        m.media = media.isEmpty() ? null : media;
        m.postedAt = e.optLong("posted", e.optLong("t"));
        NotifyListener.Item it = NotifyListener.liveItem(m.nkey, m.pkg, m.from); // (by its notification as it is now, never a saved number)
        m.id = it != null && it.reply != null ? it.id : 0; // (its notification gone: no reply from here, the app is offered instead)
        m.talk = true;
        m.startHow = CardTalk.START_READ;
        m.fromLater = true;
        m.voiceOnly = !screenUsable(svc);
        return show(svc, m); // (it stays in 📬 until its texts are read out in full)
    }

    /**
     * Main thread. All of 📬 on the card: a one-line overview, then each chat, the important ones first. ask: asked first
     * ("చదవమంటారా?", the hour's reminder); a "వద్దు" or a card left alone puts them all back.
     */
    static boolean laterAll(AccessibilityService svc, boolean ask) {
        if (svc == null || busy()) return false;
        String over = LaterMessages.overview(svc).replace("⭐", "");
        java.util.List<org.json.JSONObject> l = LaterMessages.sorted(svc); // (nothing taken out: each chat leaves 📬 once read out in full)
        if (l.isEmpty()) return false;
        Msg m = new Msg();
        m.kind = MSG;
        m.app = "📬";
        m.from = "తర్వాత మెసేజ్‌లు";
        m.group = true; // (no "who" before the reading: each chat says its own)
        m.laterOverview = ask ? "" : "తర్వాత చదవాల్సినవి: " + over + "."; // (asked first: the question already said it)
        for (org.json.JSONObject e : l) m.texts.add(LaterMessages.spoken(java.util.Collections.singletonList(e)));
        m.id = 0;
        m.talk = true;
        m.laterAll = true;
        m.laterEntries = l;
        m.postedAt = System.currentTimeMillis();
        m.say = new Prefs(svc).name() + ", తర్వాత చదవాల్సిన మెసేజ్‌లు ఉన్నాయి: " + over + ".";
        m.ask = "చదవమంటారా?";
        m.startHow = ask ? CardTalk.START_ASK : CardTalk.START_READ;
        m.voiceOnly = !screenUsable(svc);
        return show(svc, m);
    }

    /** A short line on the screen (from Jarvis's service). */
    static void toast(Context c, String s) {
        try { android.widget.Toast.makeText(c, s, android.widget.Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
    }

    static void toastApp(Context c, String s) { toast(c.getApplicationContext(), s); }

    /** The notification shade pulled back up (a tap there opened something of Jarvis's on the screen). */
    static void closeShadeNow(AccessibilityService svc) { closeShadePanel(svc); }

    /** No window (the phone is locked / the screen is off): only voice. */
    boolean voiceOnly() { return m.voiceOnly; }

    /** Jarvis is talking or listening: the card stays. */
    void stayOpen() {
        main.removeCallbacks(autoHide);
        main.removeCallbacks(hide);
        hidePending = false;
    }

    /** The card goes in ms (later: unheard messages wait under the dot). */
    void hideIn(long ms, boolean later) {
        if (done) return;
        main.removeCallbacks(autoHide);
        main.removeCallbacks(hide);
        laterOnHide = later;
        hidePending = true;
        hideMs = ms;
        main.postDelayed(hide, ms);
    }

    /** How long a card stays for a tap (his setting). */
    long staySeconds() { return p.cardSecs(); }

    /** The talk ended itself and closes the card. */
    void closeFromTalk(boolean later) {
        talk = null;
        close(later);
    }

    /** The talk ended itself: the card off the screen at once (a call answered: nothing may cover the call screen). */
    void closeFromTalkNow() {
        talk = null;
        close(false, false);
    }

    /** The card's text now (the panel's answer, when the card is built later). */
    boolean done() { return done; }

    private void close(boolean later) { close(later, true); }

    /** later: not heard yet, so a message waits under the floating button's dot (a question: a notification). */
    private void close(boolean later, boolean animate) {
        if (done) { if (!animate) removeView(); return; }
        done = true;
        main.removeCallbacks(autoHide);
        main.removeCallbacks(hide);
        Flow t = talk; // whatever Jarvis was saying or hearing on it stops
        talk = null;
        if (t != null) t.stop();
        if (showing == this) showing = null;
        if (listeningScreen) { listeningScreen = false; try { svc.unregisterReceiver(screenOff); } catch (Exception ignored) {} }
        if (listeningUnlock) { listeningUnlock = false; try { svc.unregisterReceiver(unlocked); } catch (Exception ignored) {} }
        if (inShade) { inShade = false; main.removeCallbacks(shadePost); cancelShade(svc); }
        if (MSG.equals(m.kind) && (m.laterAll || m.fromLater)) { /* (📬: its texts stay there until each is read out in full) */ }
        else if (later && !heard) {
            if (MSG.equals(m.kind)) { if (!m.texts.isEmpty()) LaterMessages.add(svc, m.app, m.from, m.pkg, m.nkey, m.id, m.texts, m.group, m.media, m.postedAt); }
            else if (onUnheard != null) try { onUnheard.run(); } catch (Exception ignored) {}
        }
        if (view == null || removed) return; // (voice only: no window)
        if (!animate) { removeView(); return; }
        leaving = this;
        view.animate().translationY(-dp(150)).alpha(0f).setDuration(180).withEndAction(this::removeView).start();
        main.postDelayed(this::removeView, 400); // even if the animation never ends
    }

    private void removeView() {
        if (leaving == this) leaving = null;
        if (removed || view == null) return;
        removed = true;
        try { view.animate().cancel(); } catch (Exception ignored) {}
        try { wm.removeView(view); } catch (Exception ignored) {}
    }
}
