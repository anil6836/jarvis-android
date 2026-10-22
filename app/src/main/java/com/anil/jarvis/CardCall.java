package com.anil.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.speech.SpeechRecognizer;

/**
 * 📞 A ringing call on the top card (Anil, 22 Oct: the call card moves up from the panel; Build 2). The same as the
 * panel's call mode always was: who it is said with "ఎత్తమంటారా?" over his ringtone made softer, one short listen, the
 * name said 2 times in all (CallCare's rules: then his own ringtone alone, and the buttons), 📞 ఎత్తు / ✖ కట్ / 🔕 సైలెంట్
 * and his ready messages as chips (a tap = cut + that SMS: the tap is his yes), the talk after a cut (the message to
 * the caller, read back, sent only on his word; the reminder). "ఎత్తు" by voice: the loudspeaker is pressed once, after
 * this card is gone (it never covers the call screen's buttons). A newer call ringing meanwhile: neither is acted on by voice.
 * The phone locked: no card, the same by voice; the phone's own call screen stays as it is.
 */
final class CardCall implements VoiceIO.Listener, TopCard.Flow {
    /** How long each listen for his word (seconds), his ringtone at his own volume between two, the name said this often. */
    private static final int LISTEN_S = 4, RING_GAP_MS = 1500, SAYS = 2;

    private final AccessibilityService svc;
    private final TopCard card;
    private final TopCard.Msg m;
    private final Prefs p;
    private final Handler main = new Handler(Looper.getMainLooper());
    private VoiceIO voice;
    private volatile boolean stopped;
    /** The call is still ringing and this card is about it. */
    private boolean ringing = true;
    private boolean talkSet;
    private int tries;
    private String partial = "";
    /** The talk after the cut (the message to the caller, the reminder), else null. */
    private CallWords.Reply reply;
    private CallWords.Step replyStep;
    /** This ring (CallCare.ringAt when it was said). */
    private final long ringAt;

    CardCall(AccessibilityService svc, TopCard card, TopCard.Msg m) {
        this.svc = svc;
        this.card = card;
        this.m = m;
        this.p = new Prefs(svc);
        this.ringAt = m.postedAt;
    }

    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (stopped || !ringing) return;
            if (!CallControl.isRinging()) { // answered / cut on the watch or the phone, or it stopped
                endRing();
                if (reply == null) end();
                return;
            }
            main.postDelayed(this, 1000);
        }
    };

    /** His ringtone rang at his volume for a moment: once more who it is (softer ring meanwhile). */
    private final Runnable askAgain = this::sayAgain;

    private void sayAgain() {
        if (stopped || !ringing || !CallControl.isRinging() || CallCare.silenced || !sameCall()) return;
        CallCare.ringLow(svc, true);
        speak(m.ask + ". ఎత్తమంటారా?"); // (never "కట్": the watch's mic hears it too)
    }

    void start() {
        voice = new VoiceIO(svc, p, this);
        hold();
        CallCare.ringLow(svc, true); // his ringtone softer (not off) while Jarvis says who it is and listens
        callButtons();
        speak(m.say + ". ఎత్తమంటారా?");
        main.postDelayed(watch, 2000);
    }

    /** 📞 ఎత్తు / ✖ కట్ / 🔕 సైలెంట్, then his ready messages. */
    private void callButtons() {
        Object[] b = new Object[6 + CallCare.READY.length * 2];
        int i = 0;
        b[i++] = "📞 ఎత్తు";
        b[i++] = (Runnable) () -> doCall(true, false);
        b[i++] = "✖ కట్";
        b[i++] = (Runnable) () -> doCall(false, false);
        b[i++] = "🔕 సైలెంట్";
        b[i++] = (Runnable) this::silence;
        for (String[] q : CallCare.READY) {
            final String id = q[0];
            b[i++] = "💬 " + q[1].replaceFirst("^\\S+\\s", "");
            b[i++] = (Runnable) () -> quickTap(id);
        }
        card.buttons(b);
    }

    /** While it rings the talk is his (the wake word stays off: "Jarvis, ఎత్తు" must reach this card, not a new panel). */
    @Override public boolean talking() { return !stopped && (talkSet || ringing); }

    /** Still about a call that rings (the loudspeaker waits only for such a card). */
    boolean ringingNow() { return !stopped && ringing; }

    /** Busy while it rings and while the talk after the cut goes on: other cards wait. */
    @Override public boolean busy() { return !stopped; }

    @Override public void stop() { stop(0); }

    @Override public void stop(int yield) {
        if (stopped) return;
        stopped = true;
        main.removeCallbacksAndMessages(null);
        if (voice != null) voice.shutdown();
        if (reply != null) { reply = null; CallCare.releaseReply("card"); }
        CallCare.ringLow(svc, false); // (his ringtone back at his own volume)
        if (yield == CardTalk.YIELD_AFTER) talkSet = false;
        else if (yield == CardTalk.YIELD_BEFORE && talkSet) { talkSet = false; MainActivity.talking(false); }
        letGo();
    }

    private void hold() {
        if (talkSet || stopped) return;
        Announcer.stop();
        talkSet = !MainActivity.inConversation;
        if (talkSet) MainActivity.talking(true);
        WakeService.pause(svc);
    }

    private void letGo() {
        if (!talkSet) return;
        talkSet = false;
        MainActivity.talking(false);
        if (p.wakeReady()) WakeService.resume(svc);
    }

    private void end() {
        stop();
        card.closeFromTalk(false);
    }

    /** Ends with a line shown for a moment (said too when he spoke to it). */
    private void endSoon(String status, long ms) {
        card.status(status);
        card.buttons("✕", (Runnable) this::end);
        card.orb(OrbView.IDLE);
        letGo();
        card.hideIn(ms, false);
    }

    // ================================================================ the ring

    private boolean sameCall() { return ringAt == 0 || CallCare.ringAt == ringAt; }

    /** A newer call rang while this card asked about the earlier one: it acts on neither by voice. */
    private boolean otherCall() {
        if (sameCall()) return false;
        stopTalk();
        endRing();
        card.orb(OrbView.IDLE);
        card.status("ఇంకో కాల్ వచ్చింది: ఫోన్ స్క్రీన్‌లో చూసి మీరే నొక్కండి.");
        card.buttons("✕", (Runnable) this::end);
        card.hideIn(8000, false);
        return true;
    }

    private void stopTalk() {
        if (voice == null) return;
        if (voice.listening) voice.cancelListening();
        voice.stopSpeaking();
        main.removeCallbacks(askAgain);
    }

    private void endRing() {
        ringing = false;
        main.removeCallbacks(watch);
        main.removeCallbacks(askAgain);
        CallCare.ringLow(svc, false);
    }

    /** His words to the ringing call (CallWords: the same rules as the watch's; the caller's name and his own never count). */
    private void onCallWords(String t) {
        if (otherCall()) return;
        CallWords.Word w = CallWords.classify(t, new String[]{CallCare.who, p.name()});
        switch (w.kind) {
            case CallWords.ANSWER: doCall(true, true); return;
            case CallWords.SILENT: silence(); return;
            case CallWords.DECLINE:
            case CallWords.MESSAGE: cutCall(w, false); return;
            default: askCallAgain();
        }
    }

    private void askCallAgain() {
        if (!ringing || stopped) return;
        if (CallCare.silenced) { quiet(true); return; }
        CallCare.ringLow(svc, false); // his own ringtone at his volume meanwhile
        card.orb(OrbView.IDLE);
        if (++tries >= SAYS || !CallControl.isRinging()) {
            card.status("ఎత్తాలంటే 📞, కట్ చేయాలంటే ✖ నొక్కండి"); // (the wake word stays off while it rings)
            return;
        }
        card.status(m.say);
        main.removeCallbacks(askAgain);
        main.postDelayed(askAgain, RING_GAP_MS);
    }

    /** Answer (byVoice: the phone isn't in his hand: the loudspeaker) or cut, by a chip or his word. */
    private void doCall(boolean answer, boolean byVoice) {
        if (stopped || !ringing || otherCall()) return;
        if (!answer) { CallWords.Word w = new CallWords.Word(); w.kind = CallWords.DECLINE; cutCall(w, !byVoice); return; }
        stopTalk();
        String r = CallControl.answer(svc);
        if ("no_call".equals(r) && CallCare.doneJustNow(true)) r = "answered"; // (the watch answered it a moment ago)
        endRing();
        if ("answered".equals(r)) {
            stop(); card.closeFromTalkNow(); // the card goes at once: the speaker is pressed only when nothing of Jarvis covers the call screen
            CallCare.answered(svc, byVoice);
            return;
        }
        callFailed(r, true);
    }

    /** Cut (his word, the chip, or a ready-message tap); then the message / the reminder, if he wants one. */
    private void cutCall(CallWords.Word w, boolean tapped) {
        if (stopped || !ringing || otherCall()) return;
        stopTalk();
        CallWords.Reply r = CallCare.newReply();
        String res = CallControl.decline(svc);
        if ("no_call".equals(res) && CallCare.doneJustNow(false)) res = "declined"; // (cut on the watch a moment ago)
        endRing();
        if (!"declined".equals(res)) { callFailed(res, false); return; }
        CallCare.declined(svc);
        CallWords.Step step = tapped && w.kind == CallWords.DECLINE ? null : CallCare.claimReply("card") ? CallCare.afterCut(svc, r, w, tapped) : null;
        if (step == null) {
            CallCare.releaseReply("card");
            endSoon("✂️ కాల్ కట్ చేశాను", 1500);
            return;
        }
        reply = r;
        card.heardNow();
        doStep(step);
    }

    /** A ready message tapped while it rings: cut + that SMS (the tap is his yes). */
    private void quickTap(String q) {
        if (stopped || !ringing) return;
        CallWords.Word w = new CallWords.Word();
        w.kind = CallWords.MESSAGE;
        w.quick = q;
        cutCall(w, true);
    }

    private void callFailed(String r, boolean answer) {
        if ("need_permission".equals(r)) {
            CallCare.log(svc, "⚠️ కాల్స్ ఎత్తే/కట్ చేసే అనుమతి లేదు");
            endSoon("కాల్స్ ఎత్తడానికి/కట్ చేయడానికి అనుమతి కావాలి: \"Jarvis\" అని పిలిచి \"కాల్స్ అనుమతి\" అడగండి, లేదా Jarvis యాప్ → ⚙️ → 📞 లో ఇవ్వండి.", 8000);
            return;
        }
        CallCare.log(svc, answer ? "⚠️ ఎత్తలేకపోయాను" : "⚠️ కట్ చేయలేకపోయాను");
        endSoon(answer ? "కాల్ ఎత్తలేకపోయాను, మీరే నొక్కండి." : "కాల్ కట్ చేయలేకపోయాను, మీరే నొక్కండి.", 3000);
    }

    /** "సైలెంట్": this call's ring goes quiet (no voice: he asked for quiet); the chips stay while it rings. */
    private void silence() {
        if (stopped || !ringing || otherCall()) return;
        stopTalk();
        boolean ok = CallCare.silence(svc);
        WatchHub.callQuiet(svc, ok);
        CallCare.log(svc, ok ? "🔕 సైలెంట్ చేశాను" : "⚠️ సైలెంట్ కాలేదు");
        quiet(ok);
    }

    private void quiet(boolean ok) {
        card.orb(OrbView.IDLE);
        card.status(ok ? "🔕 సైలెంట్ చేశాను. ఎత్తాలంటే 📞, కట్ చేయాలంటే ✖." : "సైలెంట్ చేయలేకపోయాను: ఫోన్ వాల్యూమ్ బటన్ నొక్కండి (⚙️ → 📞 లో 🔕 అనుమతి ఇవ్వండి).");
    }

    // ================================================================ the talk after the cut (CallWords: the same as on the watch)

    private void doStep(CallWords.Step s) {
        replyStep = s;
        if (reply == null || stopped) return;
        if (s.send) { sendReply(); return; }
        if (s.remind) CallCare.remind(svc, reply);
        if (s.over) {
            reply = null;
            CallCare.releaseReply("card");
            if (s.say.isEmpty()) { end(); return; }
            card.body(s.say, 4);
            card.buttons("✕", (Runnable) this::end);
            speak(s.say); // (then it goes: onSpeakDone)
            return;
        }
        card.body(s.say, 4);
        card.buttons("✕", (Runnable) this::end);
        speak(s.say); // its end: listen (onSpeakDone)
    }

    private void sendReply() {
        final CallWords.Reply r = reply;
        card.status("💬 పంపుతున్నాను…");
        card.orb(OrbView.THINKING);
        CallCare.send(svc, r, err -> {
            if (reply != r || stopped) return;
            doStep(CallWords.sent(r, err));
        });
    }

    private void replyHeard(String text) {
        if (reply == null) return;
        doStep(CallWords.heard(reply, text));
    }

    // ================================================================ voice

    private void speak(String text) {
        if (stopped || voice == null) return;
        hold();
        card.stayOpen();
        card.orb(OrbView.SPEAKING);
        card.status("");
        voice.speak(text, p.speechRate());
    }

    private final Runnable listenR = this::listen;

    private void listen() {
        if (stopped || voice == null) return;
        partial = "";
        hold();
        if (ringing) voice.listenShort(p.listenLang(), LISTEN_S); // one short word: the name said twice, then the ringtone alone
        else voice.listen(p.listenLang());
        card.orb(OrbView.LISTENING);
        card.status(ringing ? "🎙️ వింటున్నాను… (ఎత్తు / కట్ / సైలెంట్)" : "🎙️ వింటున్నాను…");
    }

    @Override public void onListening() {}

    @Override public void onPartial(String text) {
        if (stopped) return;
        partial = text == null ? "" : text.trim();
        if (!partial.isEmpty()) card.status("🎙️ “" + partial + "”");
    }

    @Override public void onHeard(String text) {
        if (stopped) return;
        partial = "";
        String t = text == null ? "" : text.trim();
        if (reply != null) { replyHeard(t); return; }
        if (!ringing) { lastWords(); return; }
        if (t.isEmpty()) askCallAgain(); else onCallWords(t);
    }

    @Override public void onListenFailed(int error) {
        if (stopped) return;
        String part = partial;
        partial = "";
        if (reply != null) { replyHeard(part); return; }
        if (!ringing) { lastWords(); return; }
        if (!part.isEmpty()) onCallWords(part); else askCallAgain();
    }

    @Override public void onLevel(float level) { card.level(level); }

    @Override public void onSpeakStart() { if (!stopped) card.orb(OrbView.SPEAKING); }

    @Override public void onSpeakDone() {
        if (stopped) return;
        if (ringing) { main.removeCallbacks(listenR); main.postDelayed(listenR, 150); return; }
        if (reply != null) { if (replyStep != null && replyStep.listen) { main.removeCallbacks(listenR); main.postDelayed(listenR, 200); } return; }
        if (replyStep != null && replyStep.over) endSoon("✓", 1500); // (the last line was said)
    }

    @Override public void onVoiceReady() {}

    /** He talked over Jarvis: listen to him straight away (over the last line after a cut: that was all, it ends). */
    @Override public void onBargeIn() {
        if (stopped) return;
        if (!ringing && reply == null) { lastWords(); return; }
        main.removeCallbacks(listenR);
        main.postDelayed(listenR, 100);
    }

    /** Words over / after the last line of the talk after a cut: nothing more to do, the card goes. */
    private void lastWords() {
        if (voice != null) { if (voice.listening) voice.cancelListening(); if (voice.speaking) voice.stopSpeaking(); }
        endSoon("✓", 1500);
    }

    @Override public void onWord(String spoken, int start, int end) {}

    /** Another Jarvis screen took the mic: the card steps aside (the ring back at his volume). */
    @Override public void onMicTaken() {
        if (!stopped) { stop(CardTalk.YIELD_AFTER); card.closeFromTalk(false); }
    }
}
