package com.anil.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 📬 The later-messages box (Anil, 23 Oct: 1-9). A list at the top of the screen: one row per chat — its app's icon, who
 * (⭐ his important people, 👥 a group, "@మీ పేరు" when a group named him, 🔕), the app, how long ago, how many, a voice
 * note / photo / video, and its last line. A tap on a row: Jarvis reads that chat on the top card (a voice note played,
 * a photo described) and asks about a reply (sent only on his word; if its notification is gone, he offers to open the
 * app). Swipe a row aside or 🗑: it goes. 🔕 on a row: that chat never comes as a card again, straight here (🔔 undoes).
 * "🔊 అన్నీ చదువు" reads all of them, the important ones first, after a one-line overview. Also a quiet note in the
 * notification shade while anything waits here, and one gentle "చదవమంటారా?" when they wait an hour and he is free.
 */
final class LaterBox {
    private static final int ACCENT = 0xFF3B82F6;
    private static LaterBox open;

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float d;
    private LinearLayout root, rows;
    private TextView head, overview, expired, clearAll;
    private boolean clearArmed, gone;
    private int expiredShown;
    private boolean emptyShown;

    private LaterBox(AccessibilityService svc) {
        this.svc = svc;
        wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
        d = svc.getResources().getDisplayMetrics().density;
    }

    private int dp(float v) { return Math.round(v * d); }

    /** Main thread. The 📬 list on the screen (again, if it is up). */
    static void show(AccessibilityService svc) {
        if (svc == null) return;
        if (!TopCard.screenUsable(svc)) { TopCard.toast(svc, "ఫోన్ అన్‌లాక్ చేసి 📬 చూడండి"); return; } // (never over the lock screen)
        if (open != null) { open.refresh(); return; }
        if (LaterMessages.count(svc) == 0) { TopCard.toast(svc, "📬 ఖాళీ: తర్వాత చదవాల్సిన మెసేజ్‌లు ఏవీ లేవు"); return; }
        LaterBox b = new LaterBox(svc);
        if (b.build()) open = b;
    }

    /** The list is on the screen (new cards wait meanwhile, so they don't come over it). */
    static boolean isOpen() { return open != null; }

    private static final Handler ui = new Handler(Looper.getMainLooper());

    /** Any thread. 📬 changed (a message came, a chat was read in its app, one ran out of time): the open list redrawn. */
    static void listChanged() {
        ui.post(() -> { LaterBox b = open; if (b != null) b.refreshSoon(); });
    }

    private boolean touching;
    private final Runnable redraw = () -> {
        if (gone) return;
        if (touching) { refreshSoon(); return; } // (never under his finger mid-swipe)
        refresh();
    };

    private void refreshSoon() {
        main.removeCallbacks(redraw);
        if (!gone) main.postDelayed(redraw, 400);
    }

    /** Left alone this long, the list goes (it never sits over his screen, or new cards, for long). */
    private static final long IDLE_MS = 60_000;
    private final Runnable idleClose = this::close;

    private void touched() {
        main.removeCallbacks(idleClose);
        if (!gone) main.postDelayed(idleClose, IDLE_MS);
    }

    /** The screen went off: the list goes (never over the lock screen). */
    private final android.content.BroadcastReceiver screenOff = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { close(); }
    };
    private boolean listening;

    /** Main thread. The list goes (a card is reading now, or Jarvis's service is going). */
    static void closeIfOpen() {
        LaterBox b = open;
        if (b != null) b.close();
    }

    private void close() {
        if (gone) return;
        gone = true;
        if (open == this) open = null;
        main.removeCallbacks(idleClose);
        main.removeCallbacks(redraw);
        if (listening) { listening = false; try { svc.unregisterReceiver(screenOff); } catch (Exception ignored) {} }
        try { wm.removeView(root); } catch (Exception ignored) {}
    }

    private boolean build() {
        root = new LinearLayout(svc);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xF7081A2E, 0xF70B1222});
        bg.setStroke(Math.max(1, dp(1.2f)), FaceRig.withAlpha(ACCENT, 0xB0));
        bg.setCornerRadius(dp(20));
        root.setBackground(bg);
        root.setElevation(dp(8));
        root.setPadding(dp(14), dp(10), dp(12), dp(10));

        LinearLayout top = new LinearLayout(svc);
        top.setGravity(Gravity.CENTER_VERTICAL);
        head = text("", 15.5f, ACCENT, true);
        top.addView(head, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(chip("✕", this::close));
        root.addView(top);
        overview = text("", 12.5f, 0xFF9FB4CC, false);
        overview.setPadding(0, dp(2), 0, dp(2));
        root.addView(overview);
        expired = text("", 12f, 0xFFF59E0B, false);
        root.addView(expired);

        final int maxH = svc.getResources().getDisplayMetrics().heightPixels * 11 / 20;
        ScrollView sv = new ScrollView(svc) {
            @Override protected void onMeasure(int w, int h) { super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)); }
        };
        rows = new LinearLayout(svc);
        rows.setOrientation(LinearLayout.VERTICAL);
        sv.addView(rows);
        LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(-1, -2);
        svl.topMargin = dp(6);
        root.addView(sv, svl);

        LinearLayout foot = new LinearLayout(svc);
        foot.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        foot.setPadding(0, dp(8), 0, 0);
        foot.addView(chip("🔊 అన్నీ చదువు", this::readAll));
        clearAll = chip("🗑 అన్నీ తీసేయి", this::clearAll);
        foot.addView(clearAll);
        root.addView(foot);

        // a tap outside: the list goes (the app below gets that tap)
        root.setOnTouchListener((v, e) -> { if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { close(); return true; } touched(); return false; });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(svc.getResources().getDisplayMetrics().widthPixels - dp(16),
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(new Prefs(svc).cardLow() ? 110 : 30);
        refresh();
        try {
            wm.addView(root, lp);
        } catch (Exception e) {
            return false;
        }
        root.setTranslationY(-dp(140));
        root.setAlpha(0f);
        root.animate().translationY(0).alpha(1f).setDuration(220).start();
        try {
            svc.registerReceiver(screenOff, new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF));
            listening = true;
        } catch (Exception ignored) {}
        touched();
        return true;
    }

    /** The rows as the list is now (⭐ / direct / his name first). */
    private void refresh() {
        if (gone) return;
        List<JSONObject> l = LaterMessages.sorted(svc);
        head.setText("📬 తర్వాత చదవాల్సినవి · " + l.size());
        overview.setText(LaterMessages.overview(svc));
        int ex = expiredShown += LaterMessages.takeExpired(svc); // (stays said while the list is up)
        if (ex > 0) expired.setText("🕒 " + LaterMessages.keepHours(svc) + " గంటలు దాటిన " + ex + (ex == 1 ? " పాత చాట్" : " పాత చాట్‌లు") + " తీసేశాను");
        expired.setVisibility(ex > 0 ? View.VISIBLE : View.GONE);
        rows.removeAllViews();
        for (JSONObject e : l) rows.addView(row(e));
        if (l.isEmpty()) {
            rows.addView(text("📬 ఖాళీ", 14, 0xFFE2E8F0, false));
            main.removeCallbacks(idleClose);
            main.postDelayed(idleClose, 1500);
            emptyShown = true;
        } else if (emptyShown) { // (a message came just as it emptied: the list stays)
            emptyShown = false;
            touched();
        }
    }

    private View row(final JSONObject e) {
        final String app = e.optString("app"), from = e.optString("from"), pkg = e.optString("pkg");
        LinearLayout r = new LinearLayout(svc);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(8), dp(8), dp(6), dp(8));
        GradientDrawable rb = new GradientDrawable();
        rb.setColor(0x18FFFFFF);
        rb.setCornerRadius(dp(14));
        r.setBackground(rb);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.bottomMargin = dp(6);
        r.setLayoutParams(rl);

        Drawable icon = null;
        try { if (!pkg.isEmpty()) icon = svc.getPackageManager().getApplicationIcon(pkg); } catch (Exception ignored) {}
        if (icon != null) {
            ImageView iv = new ImageView(svc);
            iv.setImageDrawable(icon);
            r.addView(iv, new LinearLayout.LayoutParams(dp(32), dp(32)));
        } else {
            TextView t = text("💬", 20, 0xFFFFFFFF, false);
            r.addView(t, new LinearLayout.LayoutParams(dp(32), -2));
        }

        LinearLayout mid = new LinearLayout(svc);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(10), 0, dp(6), 0);
        boolean quiet = LaterMessages.quiet(svc, pkg, from);
        String name = (e.optBoolean("vip") ? "⭐ " : "") + from + (e.optBoolean("group") ? " 👥" : "") + (quiet ? " 🔕" : "");
        mid.addView(text(name, 14.5f, 0xFFF2F7FF, true));
        long ago = (System.currentTimeMillis() - e.optLong("t")) / 60_000;
        int n = LaterMessages.messages(e);
        String media = e.optString("media");
        String meta = app + " · " + (ago < 1 ? "ఇప్పుడే" : ago < 60 ? ago + " ని. క్రితం" : (ago / 60) + " గం. క్రితం") + " · "
                + (n == 1 ? "1 మెసేజ్" : n + " మెసేజ్‌లు")
                + ("voice".equals(media) || "audio".equals(media) ? " · 🎤 వాయిస్" : "photo".equals(media) ? " · 📷 ఫోటో" : "video".equals(media) ? " · 🎥 వీడియో" : "")
                + (e.optBoolean("mention") ? " · @మీ పేరు" : "");
        mid.addView(text(meta, 11.5f, 0xFF8AA4BF, false));
        JSONArray t = e.optJSONArray("texts");
        String last = t == null || t.length() == 0 ? "" : t.optString(t.length() - 1);
        TextView pv = text(last, 13f, 0xFFCBD5E1, false);
        pv.setSingleLine(true);
        pv.setEllipsize(TextUtils.TruncateAt.END);
        mid.addView(pv);
        r.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));

        r.addView(chip(quiet ? "🔔" : "🔕", () -> {
            LaterMessages.setQuiet(svc, pkg, from, app, !quiet);
            TopCard.toast(svc, quiet ? "🔔 " + from + ": మళ్ళీ కార్డ్‌లో చెబుతాను" : "🔕 " + from + ": ఇక కార్డ్ రాదు, నేరుగా 📬 లోకి");
            refresh();
        }));
        r.addView(chip("🗑", () -> { LaterMessages.remove(svc, app, from); refresh(); }));

        // a tap: read this chat; a swipe aside: it goes
        final float[] down = new float[2];
        r.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = ev.getRawX();
                    down[1] = ev.getRawY();
                    touching = true;
                    touched();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    v.setTranslationX(ev.getRawX() - down[0]);
                    return true;
                case MotionEvent.ACTION_UP: {
                    touching = false;
                    float dx = ev.getRawX() - down[0], dy = ev.getRawY() - down[1];
                    if (Math.abs(dx) > dp(90)) {
                        v.animate().translationX(dx > 0 ? v.getWidth() : -v.getWidth()).alpha(0f).setDuration(160)
                                .withEndAction(() -> { LaterMessages.remove(svc, app, from); refresh(); }).start();
                        return true;
                    }
                    v.animate().translationX(0).setDuration(120).start();
                    if (Math.abs(dx) < dp(10) && Math.abs(dy) < dp(10)) read(e);
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    touching = false;
                    v.animate().translationX(0).setDuration(120).start();
                    return true;
                default:
                    return false;
            }
        });
        return r;
    }

    /** One chat read on the top card (then the reply question); the list goes. */
    private void read(JSONObject e) {
        if (LaterMessages.find(svc, e.optString("app"), e.optString("from")) == null) { // (read in its own app meanwhile)
            TopCard.toast(svc, "ఈ చాట్ ఇప్పటికే చదివారు: 📬 లో లేదు");
            refresh();
            return;
        }
        close(); // (first: the card comes where the list was)
        if (!TopCard.later(svc, e)) { show(svc); TopCard.toast(svc, "ఇప్పుడు ఇంకో మాట నడుస్తోంది: అది అయ్యాక నొక్కండి"); }
    }

    private void readAll() {
        close();
        if (!TopCard.laterAll(svc, false)) { show(svc); TopCard.toast(svc, "ఇప్పుడు ఇంకో మాట నడుస్తోంది: అది అయ్యాక నొక్కండి"); }
    }

    /** 🗑 అన్నీ: a second tap to be sure (nothing is lost by one stray tap). */
    private void clearAll() {
        if (!clearArmed) {
            clearArmed = true;
            clearAll.setText("✓ ఖచ్చితంగా? మళ్ళీ నొక్కండి");
            main.postDelayed(() -> { clearArmed = false; if (!gone) clearAll.setText("🗑 అన్నీ తీసేయి"); }, 4000);
            return;
        }
        LaterMessages.clear(svc);
        close();
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(svc);
        t.setText(s);
        t.setTextSize(size + (new Prefs(svc).cardBig() ? 2 : 0));
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView chip(String label, Runnable r) {
        TextView t = new TextView(svc);
        t.setText(label);
        t.setTextColor(0xFFE6F6FF);
        t.setTextSize(13);
        t.setPadding(dp(11), dp(6), dp(11), dp(6));
        GradientDrawable c = new GradientDrawable();
        c.setColor(FaceRig.withAlpha(ACCENT, 0x2E));
        c.setStroke(Math.max(1, dp(1)), FaceRig.withAlpha(ACCENT, 0x66));
        c.setCornerRadius(dp(16));
        t.setBackground(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginStart(dp(5));
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> { touched(); r.run(); });
        return t;
    }

    // ================================================================ 📲 the quiet note in the shade (item 5)

    private static final String NOTE_TAG = "jarvis_mailbox", NOTE_CH = "jarvis_mailbox";

    /** The list changed: the note in the shade says how many wait (gone when none, or when he switched it off). */
    static void noteChanged(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            int n = LaterMessages.count(c);
            Prefs p = new Prefs(c);
            if (n == 0 || !p.sp.getBoolean("later_note", true) || p.homeMode()) { nm.cancel(NOTE_TAG, 1); return; }
            NotificationChannel ch = new NotificationChannel(NOTE_CH, "📬 Jarvis తర్వాత మెసేజ్‌లు", NotificationManager.IMPORTANCE_LOW); // (no sound, no pop-up)
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            String over = LaterMessages.overview(c);
            Notification.Builder b = new Notification.Builder(c, NOTE_CH)
                    .setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle("📬 Jarvis దగ్గర " + (n == 1 ? "ఒక చాట్" : n + " చాట్‌లు") + " తర్వాత చదవడానికి")
                    .setContentText(over)
                    .setStyle(new Notification.BigTextStyle().bigText(over))
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(new Notification.Builder(c, NOTE_CH).setSmallIcon(android.R.drawable.ic_dialog_email)
                            .setContentTitle("📬 Jarvis").build())
                    .setLocalOnly(true) // (not bridged to the watch)
                    .setContentIntent(intent(c, CardShade.OP_LATER_LIST))
                    .addAction(action("🔊 అన్నీ చదువు", intent(c, CardShade.OP_LATER_ALL)))
                    .addAction(action("📋 లిస్ట్", intent(c, CardShade.OP_LATER_LIST)));
            nm.notify(NOTE_TAG, 1, b.build());
        } catch (Exception ignored) {}
    }

    /** A button on the note: from the lock screen it asks for the unlock first (API 31+). */
    private static Notification.Action action(String label, PendingIntent pi) {
        Notification.Action.Builder b = new Notification.Action.Builder(null, label, pi);
        if (android.os.Build.VERSION.SDK_INT >= 31) b.setAuthenticationRequired(true);
        return b.build();
    }

    private static PendingIntent intent(Context c, String op) {
        Intent i = new Intent(c, CardShade.class).setAction(CardShade.ACTION).putExtra("op", op)
                .setData(android.net.Uri.parse("jarvis-later://" + op));
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Main thread. From the shade note: the list, or all read. */
    static void action(Context c, String op) {
        AccessibilityService svc = JarvisAccessibility.service();
        if (svc == null) { TopCard.toastApp(c, "Jarvis స్క్రీన్ యాక్సెస్ ఆఫ్‌లో ఉంది: Settings → Accessibility → Jarvis ఆన్ చేయండి"); return; }
        if (!TopCard.screenUsable(svc)) { TopCard.toast(svc, "ఫోన్ అన్‌లాక్ చేసి 📬 నొక్కండి"); return; } // (nothing read aloud / shown from the lock screen)
        TopCard.closeShadeNow(svc);
        if (CardShade.OP_LATER_ALL.equals(op)) {
            closeIfOpen();
            if (!TopCard.laterAll(svc, false)) TopCard.toast(svc, "ఇప్పుడు ఇంకో మాట నడుస్తోంది: అది అయ్యాక చదువుతాను");
        } else {
            show(svc);
        }
    }

    // ================================================================ ⏰ one gentle reminder (item 6)

    /**
     * Proactive's tick: messages have waited here an hour and he is free (the phone in his hand, not driving, at work, at
     * night, in a call or a talk): once per set of messages the top card asks "చదవమంటారా?".
     */
    static void remindTick(Context c, Prefs p) {
        try {
            if (!p.sp.getBoolean("later_remind", true) || p.homeMode()) return;
            int n = LaterMessages.count(c);
            if (n == 0) return;
            long now = System.currentTimeMillis();
            if (now - LaterMessages.oldest(c) < 60 * 60_000L || !LaterMessages.remindDue(c)) return; // (once in 3 h, and only for new, not 🔕)
            if (p.night() || p.driving() || Bike.riding(c) || DutyMode.on(c) || MainActivity.busyTalking() || CallControl.busyWithCall()) return;
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL) return; // (Do Not Disturb)
            new Handler(Looper.getMainLooper()).post(() -> {
                AccessibilityService svc = JarvisAccessibility.service();
                if (svc == null || !TopCard.screenUsable(svc) || TopCard.busy() || SheetActivity.open) return; // (only with the phone in his hand)
                if (TopCard.laterAll(svc, true)) LaterMessages.reminded(c);
            });
        } catch (Exception ignored) {}
    }
}
