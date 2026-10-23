package com.anil.jarvis;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The panel he was talking in, minimised into the top card (Anil, 22 Oct; Build 2): he tapped outside it, another app
 * came up, or a call rang. The panel itself goes on behind (its voice, its listening, its brain and tools): this card
 * only shows where the talk is (the orb, what Jarvis is doing, the answer with the spoken word lit) and its controls —
 * ⏸ / ▶ / ⏪ while it speaks, 🎙️ to say more, ⬆️ to bring the panel back, ✕ to end it. After a call: "▶ కొనసాగించు"
 * carries on from where it stopped. A yes a job needs is asked here (✅ / ✕); with no card to tap (locked) the panel
 * comes back for it. Closing this card ends the talk.
 */
final class CardPanel implements TopCard.Flow {
    private final TopCard card;
    private final SheetActivity a;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopped;
    /** The panel took the talk back (it came to the front, or it closed itself): this card just goes. */
    private boolean detached;
    private int shownState = -1;
    private String shownStatus;
    private CharSequence shownText;
    /** A yes being asked on this card (its buttons stay until he answers). */
    private volatile CountDownLatch confirmWait;
    private volatile AtomicInteger confirmState;
    private Runnable countdown;

    CardPanel(TopCard card, SheetActivity a) {
        this.card = card;
        this.a = a;
    }

    private final Runnable sync = new Runnable() {
        @Override public void run() {
            if (stopped) return;
            refresh();
            main.postDelayed(this, 250);
        }
    };

    void start() {
        refresh();
        main.postDelayed(sync, 250);
    }

    /** The card as the panel is now: the orb, what Jarvis is doing, the answer, the buttons for that. */
    private void refresh() {
        if (stopped || a.isDestroyed()) return;
        int st = a.miniState();
        card.orb(st == SheetActivity.M_LISTEN || st == SheetActivity.M_LIVE ? OrbView.LISTENING
                : st == SheetActivity.M_THINK ? OrbView.THINKING
                : st == SheetActivity.M_SPEAK || st == SheetActivity.M_GREET ? OrbView.SPEAKING : OrbView.IDLE);
        boolean pill = (st == SheetActivity.M_LISTEN || st == SheetActivity.M_GREET) && confirmWait == null; // (he only has to speak: the slim pill)
        card.compact(pill);
        String s = pill ? (st == SheetActivity.M_GREET ? "🎙️ Jarvis…" : "🎙️ Jarvis వింటున్నాడు…") : a.miniStatus();
        if (!s.equals(shownStatus)) { shownStatus = s; card.status(s); }
        CharSequence t = a.miniText();
        if (st != SheetActivity.M_SPEAK && t != null && !t.toString().equals(shownText == null ? null : shownText.toString())) { // (while it speaks, the lit word owns the text)
            shownText = t;
            card.body(t, 6);
        }
        if (st != shownState && confirmWait == null) {
            shownState = st;
            buttons(st);
        }
    }

    private void buttons(int st) {
        Runnable up = a::miniRestore, end = this::endTalk;
        switch (st) {
            case SheetActivity.M_SPEAK:
                card.buttons("⏸", (Runnable) a::miniPlayPause, "⏪", (Runnable) a::miniAgain, "⬆️ పెద్దగా", up, "✕", end);
                break;
            case SheetActivity.M_PAUSED:
                card.buttons("▶ కొనసాగించు", (Runnable) a::miniPlayPause, "⏪", (Runnable) a::miniAgain, "⬆️ పెద్దగా", up, "✕", end);
                break;
            case SheetActivity.M_CALL:
                card.buttons("▶ కొనసాగించు", (Runnable) a::miniResume, "⬆️ పెద్దగా", up, "✕", end);
                break;
            case SheetActivity.M_IDLE:
                card.buttons("🎙️ చెప్పండి", (Runnable) a::miniTalk, "⏪", (Runnable) a::miniAgain, "⬆️ పెద్దగా", up, "✕", end);
                break;
            default: // listening, thinking, the greeting, Live
                card.buttons("⬆️ పెద్దగా", up, "✕", end);
        }
    }

    /** The word being said now, lit on the card (the panel's karaoke). */
    void highlight(String spoken, int start, int end) {
        if (stopped || spoken == null) return;
        if (Otp.has(spoken)) return; // (a code stays as it is on the card)
        card.highlight(spoken, start, end);
    }

    /** ✕ on the card: the talk ends (the panel closes). */
    private void endTalk() {
        stop();
        card.closeFromTalk(false);
    }

    @Override public boolean talking() { return !stopped && a.miniTalking(); }

    /** The panel's talk holds the top while it is minimised here: other cards wait. */
    @Override public boolean busy() { return !stopped; }

    @Override public void stop() { stop(0); }

    /** The card is going (✕, swipe, Jarvis's service going, another Jarvis screen starting): the talk behind it ends too. */
    @Override public void stop(int yield) {
        if (stopped) return;
        stopped = true;
        main.removeCallbacksAndMessages(null);
        answer(false);
        if (!detached) a.miniGone();
    }

    /** The panel takes the talk back (it is on the screen again, or a call came, or it closed): only the card goes. */
    void detach() {
        if (stopped) return;
        detached = true;
        stopped = true;
        main.removeCallbacksAndMessages(null);
        CountDownLatch c = confirmWait;
        if (c != null) { handBack = true; confirmWait = null; c.countDown(); } // (a yes still open: the panel asks it itself)
        card.closeFromTalk(false);
    }

    /** The panel took the talk back while a yes was open here: it asks that yes itself. */
    private volatile boolean handBack;

    // ================================================================ a yes a job needs

    private void answer(boolean yes) {
        CountDownLatch c = confirmWait;
        AtomicInteger st = confirmState;
        if (c == null || st == null) return;
        if (!st.compareAndSet(0, yes ? 1 : 2)) return;
        confirmWait = null;
        if (countdown != null) { main.removeCallbacks(countdown); countdown = null; }
        c.countDown();
        if (!stopped) { shownState = -1; card.status(yes ? "సరే…" : "సరే, వద్దు"); refresh(); }
    }

    /**
     * From the panel's worker: the yes asked on the card (✅ / ✕); autoSeconds > 0 (a call, SOS) counts down only while
     * the card is seen. Null: no card to tap (the phone is locked) — the panel then comes back to ask it.
     */
    Boolean confirm(String title, String message, String yes, int autoSeconds) {
        if (stopped || !card.seen()) return null; // (not seen — locked, or down in the shade: the panel comes back to ask it)
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger st = new AtomicInteger(0);
        confirmState = st;
        confirmWait = done;
        main.post(() -> {
            if (stopped || confirmWait != done) { st.compareAndSet(0, 2); done.countDown(); return; }
            card.stayOpen();
            card.compact(false);
            card.orb(OrbView.IDLE);
            card.body(title + (message == null || message.isEmpty() ? "" : "\n" + message), 6);
            card.buttons(yes == null || yes.isEmpty() ? "✅ అవును" : "✅ " + yes, (Runnable) () -> { if (confirmState == st) answer(true); },
                    "✕ వద్దు", (Runnable) () -> { if (confirmState == st) answer(false); });
            if (autoSeconds > 0) {
                final int[] left = {autoSeconds};
                countdown = new Runnable() {
                    @Override public void run() {
                        if (confirmState != st || stopped) return;
                        if (!card.seen()) { left[0] = autoSeconds; card.status("⏱ స్క్రీన్ చూశాక లెక్కపెడతాను"); main.postDelayed(this, 1000); return; } // (never yes unseen: not voice only, not in the shade)
                        if (left[0] <= 0) { answer(true); return; }
                        card.status("⏱ " + left[0] + " · వద్దంటే ✕");
                        left[0]--;
                        main.postDelayed(this, 1000);
                    }
                };
                main.post(countdown);
            } else {
                card.status("👆 " + title);
            }
        });
        try { done.await(90 + Math.max(0, autoSeconds), TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (handBack && st.get() == 0) return null; // (the panel is on the screen again: it asks there)
        if (st.compareAndSet(0, 2)) main.post(() -> { if (confirmState == st) { confirmWait = null; shownState = -1; if (!stopped) { card.status("జవాబు రాలేదు: చేయలేదు"); refresh(); } } });
        return st.get() == 1;
    }
}
