package com.anil.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
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
 * up, or left alone, it goes, and what wasn't heard waits under the dot (a question: a notification). Nothing is lost.
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
        boolean voiceOnly;
    }

    private static TopCard showing;

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float d;
    private final Msg m;
    private final Prefs p;
    private final int accent;
    private LinearLayout view, buttons;
    private TextView line2, status, sub;
    private OrbView orb;
    private boolean touched, done, heard, expanded;
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
        if (!MSG.equals(m.kind)) pendingAt = 0; // (this is the question / job that was on its way)
        if (busy()) return false;
        if (showing != null) showing.close(true);
        TopCard t = new TopCard(svc, m);
        if (!m.voiceOnly && !t.build()) return false;
        showing = t;
        if (m.voiceOnly) t.watchUnlock();
        if (MSG.equals(m.kind)) {
            if (m.talk || m.voiceOnly) t.startTalk(CardTalk.START_ASK);
            else t.main.postDelayed(t.autoHide, t.p.cardSecs() * 1000L);
        } else {
            CardAsk a = new CardAsk(svc, t, m);
            t.talk = a;
            a.start();
        }
        return true;
    }

    // ================================================================ where Jarvis's own words go

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
        if (t != null && (!t.m.voiceOnly || screenUsable(t.svc))) t.close(true, false); // (a voice-only talk on an unlocked phone too)
        TopCard c = leaving;
        if (c != null) c.removeView();
    }

    /**
     * Main thread. Another Jarvis screen (the panel, a call, the app, the camera) is starting: the card steps aside before
     * it talks (unheard messages under the dot), leaving the talk state to that screen.
     */
    static void stepAside() {
        TopCard t = showing;
        if (t == null) return;
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
        TopCard t = showing;
        Flow c = t == null ? null : t.talk;
        return c != null && c.busy();
    }

    /** A card closed but not yet off the screen (its slide-out). */
    private static TopCard leaving;

    /**
     * The screen went off: a card must not stay over the lock screen. A talk going on carries on by voice only (as when
     * locked from the start); a card only waiting goes (its messages under the dot).
     */
    private final android.content.BroadcastReceiver screenOff = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Flow t = talk;
            if (t != null && t.busy() && !done) { toVoiceOnly(); return; }
            close(true, false);
        }
    };
    /** Unlocked while Jarvis talks by voice only: the card comes up with the talk as it is now (⏸ / ▶ and the text). */
    private final android.content.BroadcastReceiver unlocked = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (done || !m.voiceOnly || talk == null || !screenUsable(svc)) return;
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
        if (lastBody != null) body(lastBody, lastBodyLines);
        status(lastStatus);
        if (lastButtons != null) buttons(lastButtons);
        orb(lastOrb);
    }

    // ================================================================ its look

    /** The kind's colour: a chat blue; Jarvis's question green for health, amber for a reminder, violet for a job; else cyan. */
    static int accent(Msg m) {
        if (MSG.equals(m.kind)) return 0xFF3B82F6;
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
        if (MSG.equals(m.kind)) return "💬 " + m.from + (m.group ? " గ్రూప్" : "");
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
        hs.setHorizontalScrollBarEnabled(false);
        buttons = new LinearLayout(svc);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, dp(6), 0, dp(2));
        hs.addView(buttons);
        if (MSG.equals(m.kind) && !m.talk) { // the talk sets its own buttons
            buttons.addView(button("🔊 చదువు", v -> startTalk(CardTalk.START_READ)));
            if (m.group || n >= 3) buttons.addView(button("📝 సారాంశం", v -> startTalk(CardTalk.START_SUMMARY)));
            buttons.addView(button("⏰ తర్వాత", v -> close(true)));
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
        lp.y = dp(p.cardLow() ? 110 : 30);
        try {
            wm.addView(view, lp);
        } catch (Exception e) {
            return false;
        }
        view.setTranslationY(-dp(140));
        view.setAlpha(0f);
        view.animate().translationY(0).alpha(1f).setDuration(240).start();
        try {
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
                if (dy < -dp(30)) { close(true); return true; }
                view.animate().translationY(0).setDuration(120).start();
                if (e.getActionMasked() == MotionEvent.ACTION_UP && (dy > dp(30) || Math.abs(dy) < dp(8))) { // pulled down / tapped: open or fold the text
                    touched = true;
                    setExpanded(!expanded || dy > dp(30));
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

    /** The buttons for this step: label, Runnable, label, Runnable… */
    void buttons(Object... labelThenAction) {
        lastButtons = labelThenAction;
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
        if (later && !heard) {
            if (MSG.equals(m.kind)) { if (!m.texts.isEmpty()) LaterMessages.add(svc, m.app, m.from, m.texts, m.id); }
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
