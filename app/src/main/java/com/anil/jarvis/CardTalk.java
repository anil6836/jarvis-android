package com.anil.jarvis;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.speech.SpeechRecognizer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * A new message while he is in another app, told the way the panel used to: who wrote, "చదవమంటారా?", the message read
 * out, "రిప్లై ఇవ్వమంటారా?", his reply read back and "పంపమంటారా?", sent only after he clearly says so (or taps 📤). But it
 * all happens on the small card at the top, in the background: the app he is in goes on, the keyboard stays open,
 * nothing covers the screen. Every step can also be tapped. Voice notes are played, photos described or shown, videos
 * opened, like before. When the panel, a call or the Jarvis app comes up, the card steps aside.
 */
final class CardTalk implements VoiceIO.Listener {
    /** How it starts: asking first, reading at once (he tapped 🔊 చదువు), or summing up (📝 సారాంశం). */
    static final int START_ASK = 0, START_READ = 1, START_SUMMARY = 2;

    // where the talk is
    private static final int ASK = 0, READING = 1, ASK_REPLY = 2, DICTATE = 3, CONFIRM = 4, DONE = 5;

    private final AccessibilityService svc;
    private final TopCard card;
    private final TopCard.Msg m;
    private final Prefs p;
    private final Handler main = new Handler(Looper.getMainLooper());
    private VoiceIO voice;
    private int step = ASK;
    private String reply = "";
    /** The text being read out (shown on the card, the spoken word highlighted). */
    private String reading = "";
    private volatile boolean stopped;
    private boolean talkSet;
    /** When the talk is over: put off under the dot ("తర్వాత"), and how long the card stays to be read. */
    private boolean doneLater;
    private long doneMs = 3000;
    /** A voice note is being found or played (WaMedia, on a thread); cancelPlay stops it. */
    private boolean voiceBusy;
    private volatile boolean cancelPlay;
    /** Answers not understood in a row (asked once more, then the buttons wait). */
    private int unclear;
    /** His other sound (a video, music) paused while he dictates and confirms a reply, so its words are never taken for his. */
    private AudioFocusRequest focus;

    CardTalk(AccessibilityService svc, TopCard card, TopCard.Msg m) {
        this.svc = svc;
        this.card = card;
        this.m = m;
        this.p = new Prefs(svc);
    }

    // ================================================================ start and end

    void start(int how) {
        voice = new VoiceIO(svc, p, this);
        hold();
        if (how == START_SUMMARY) summary();
        else if (how == START_READ) {
            if ("voice".equals(m.media) || "audio".equals(m.media)) playVoice();
            else if ("photo".equals(m.media)) describePhoto();
            else if ("video".equals(m.media)) openMedia("video");
            else readNow(true);
        } else ask();
    }

    /** Jarvis is talking or listening on the card (the panel and the wake word wait); never counted for over 10 minutes. */
    boolean talking() { return !stopped && talkSet && android.os.SystemClock.elapsedRealtime() - holdSince < 10 * 60_000L; }

    private long holdSince;

    /** Busy with him: talking, or waiting for his tap in the middle of a reply or a held speech (a new card must wait). */
    boolean busy() { return !stopped && (talkSet || step == DICTATE || step == CONFIRM || voice != null && voice.isPaused()); }

    /** Jarvis is talking with him: the wake word lets go of the mic, other messages and remarks wait their turn. */
    private void hold() {
        if (talkSet || stopped) return;
        Announcer.stop();
        ScreenReader.pauseIfReading(svc);
        talkSet = !MainActivity.inConversation;
        if (talkSet) MainActivity.talking(true);
        holdSince = android.os.SystemClock.elapsedRealtime();
        WakeService.pause(svc);
    }

    /** Not talking now (waiting for a tap, or done): "Jarvis" and the next message may come; his own sound comes back. */
    private void letGo() {
        focus(false);
        if (!talkSet) return;
        talkSet = false;
        MainActivity.talking(false);
        if (p.wakeReady()) WakeService.resume(svc);
    }

    /** Another Jarvis screen is about to start (it sets its own talk state), or already has (its state is left alone). */
    static final int YIELD_BEFORE = 1, YIELD_AFTER = 2;

    /** The card is closing: everything stops, nothing more is said or heard. */
    void stop() { stop(0); }

    /**
     * yield: another Jarvis screen (the panel, a call, the app) is taking over. Before it starts, only the card's own
     * "talking" mark goes (it sets its own and handles the wake word); once it has started, its state is left untouched.
     */
    void stop(int yield) {
        if (stopped) return;
        stopped = true;
        main.removeCallbacksAndMessages(null);
        cancelPlay = true; // a voice note stops by itself within a moment (its own thread releases the player)
        if (yield == YIELD_AFTER) talkSet = false;
        else if (yield == YIELD_BEFORE && talkSet) { talkSet = false; MainActivity.talking(false); }
        if (voice != null) voice.shutdown();
        letGo();
    }

    /** Ends the talk and closes the card (later: not heard yet, so it waits under the floating button's dot). */
    private void end(boolean later) {
        stop();
        card.closeFromTalk(later);
    }

    /** Another Jarvis screen came up: the card goes without touching that screen's talk (unheard: under the dot). */
    private void yieldTo() {
        boolean later = !card.heard();
        stop(YIELD_AFTER);
        card.closeFromTalk(later);
    }

    /** The panel, a call, the Jarvis app or the camera is up: the card must not talk over it or take its mic. */
    private boolean othersUp() {
        return SheetActivity.open || MainActivity.visible || JarvisCamera.open || CallControl.busyWithCall();
    }

    /** Steps aside for what is up: a Jarvis screen keeps its own talk state; for a call, the card's own mark is cleared. */
    private void yieldToOthers() {
        boolean screen = SheetActivity.open || MainActivity.visible || JarvisCamera.open;
        boolean later = !card.heard();
        stop(screen ? YIELD_AFTER : 0);
        card.closeFromTalk(later);
    }

    /** The talk is over: the card says how it ended and goes in a moment. */
    private void finishSoon(String status, long ms) {
        step = DONE;
        card.status(status);
        card.buttons("✕", (Runnable) () -> end(false));
        letGo();
        card.hideIn(ms, false);
    }

    // ================================================================ the steps

    /** "Anil, Ravi నుంచి WhatsApp లో మెసేజ్ వచ్చింది. చదవమంటారా?" */
    private void ask() {
        step = ASK;
        stepButtons();
        speak(m.say + " " + m.ask);
    }

    /** The buttons for the step Jarvis is at (also after a held speech is carried on). */
    private void stepButtons() {
        switch (step) {
            case ASK:
                if ("voice".equals(m.media) || "audio".equals(m.media)) {
                    if (!p.openAiKey().trim().isEmpty()) card.buttons("▶ విను", (Runnable) this::playVoice, "📝 మాటలు", (Runnable) this::voiceWords, "⏰ తర్వాత", (Runnable) () -> end(true));
                    else card.buttons("▶ విను", (Runnable) this::playVoice, "⏰ తర్వాత", (Runnable) () -> end(true));
                } else if ("photo".equals(m.media)) {
                    card.buttons("💬 ఏముంది", (Runnable) this::describePhoto, "👁 చూపించు", (Runnable) () -> openMedia("photo"), "⏰ తర్వాత", (Runnable) () -> end(true));
                } else if ("video".equals(m.media)) {
                    card.buttons("▶ ప్లే", (Runnable) () -> openMedia("video"), "⏰ తర్వాత", (Runnable) () -> end(true));
                } else if (m.group || m.texts.size() >= 3) {
                    card.buttons("🔊 చదువు", (Runnable) () -> readNow(false), "📝 సారాంశం", (Runnable) this::summary, "⏰ తర్వాత", (Runnable) () -> end(true));
                } else {
                    card.buttons("🔊 చదువు", (Runnable) () -> readNow(false), "⏰ తర్వాత", (Runnable) () -> end(true));
                }
                break;
            case READING:
                card.buttons("⏹ ఆపు", (Runnable) this::stopReading, "✕", (Runnable) () -> end(false));
                break;
            case ASK_REPLY:
                card.buttons("↩️ జవాబు", (Runnable) this::dictate, "✕", (Runnable) () -> end(false));
                break;
            case DICTATE:
                card.buttons("🎙️ చెప్పండి", (Runnable) this::dictate, "✕", (Runnable) () -> end(false));
                break;
            case CONFIRM:
                card.buttons("📤 పంపు", (Runnable) this::send, "✏️ మార్చు", (Runnable) this::dictate, "✕", (Runnable) () -> end(false));
                break;
            default:
                card.buttons("✕", (Runnable) () -> end(false));
        }
    }

    /** Reads the messages (withWho: he tapped 🔊 without the question, so who wrote comes first). */
    private void readNow(boolean withWho) {
        quiet();
        hold();
        step = READING;
        StringBuilder b = new StringBuilder();
        if (withWho && !m.group) b.append(m.from).append(" నుంచి:\n");
        for (String t : m.texts) b.append(b.length() == 0 || b.charAt(b.length() - 1) == '\n' ? "" : "\n").append(t);
        reading = b.toString();
        card.body(reading, 10);
        stepButtons();
        speak(reading);
    }

    /** A busy chat summed up in Telugu (needs the AI), then read like the messages. */
    private void summary() {
        quiet();
        hold();
        if (!p.hasBrain()) { readNow(false); return; } // no AI: read them all
        step = READING;
        card.status("📝 సారాంశం చేస్తున్నాను…");
        card.buttons("✕", (Runnable) () -> end(false));
        StringBuilder all = new StringBuilder();
        for (String t : m.texts) all.append(t).append('\n');
        final String names = p.myNames();
        new Thread(() -> {
            String out;
            try {
                out = Brain.oneShot(p, "You are Jarvis, " + p.name() + "'s assistant. Sum up these new chat messages from '" + m.from + "' ("
                        + m.app + ") in simple Telugu (Telugu script), 2-4 short lines, plain text for reading aloud. First anything addressed to him "
                        + "(his names: " + names + "), any question to him, dates, times or money; then the rest in brief. No markdown.",
                        all.toString(), null, false, 500);
            } catch (Exception e) {
                out = null;
            }
            final String o = out == null ? "" : out.replaceAll("[*#_`>]", "").trim();
            main.post(() -> {
                if (stopped || step != READING) return;
                if (o.isEmpty()) { readNow(false); return; } // no summary came: read them as they are
                reading = o;
                card.status("");
                card.body(o, 10);
                stepButtons();
                speak(o);
            });
        }, "jarvis-card-sum").start();
    }

    /** ⏹ while reading (or a voice note playing): stop there and go on to the reply question. */
    private void stopReading() {
        if (voiceBusy) { // the voice note stops in a moment and its thread goes on to afterReading() itself
            cancelPlay = true;
            return;
        }
        quiet(); // the speech, and a listen he started by talking over it
        afterReading();
    }

    /** Read: "రిప్లై ఇవ్వమంటారా?" when the chat can be answered from here, else done. */
    private void afterReading() {
        if (stopped || step != READING) return;
        card.status("");
        if (!reading.isEmpty()) card.body(reading, 10); // the highlight off
        if (m.id <= 0) { finishSoon("✓ చదివాను", 6000); return; }
        step = ASK_REPLY;
        stepButtons();
        speak("రిప్లై ఇవ్వమంటారా?");
    }

    /** "ఏం చెప్పమంటారు?" and listens for his reply. */
    private void dictate() {
        quiet();
        hold();
        step = DICTATE;
        card.buttons("✕", (Runnable) () -> end(false));
        speak("ఏం చెప్పమంటారు?");
    }

    /** His reply on the card, read back: "… పంపమంటారా?" (an empty reply is never offered). */
    private void confirm() {
        if (reply.trim().isEmpty()) { dictate(); return; }
        step = CONFIRM;
        unclear = 0;
        card.body("↩️ " + reply, 6);
        stepButtons();
        speak(reply + ". పంపమంటారా?");
    }

    /** Sends through the notification's own reply button: only from CONFIRM, after he said send or tapped 📤. */
    private void send() {
        if (stopped || step != CONFIRM || reply.trim().isEmpty()) return;
        quiet();
        step = DONE;
        final String text = reply;
        reply = ""; // never twice
        NotifyListener.Item it = NotifyListener.get(m.id);
        if (it == null || it.reply == null) {
            cannot("ఆ మెసేజ్ నోటిఫికేషన్ పోయింది, పంపలేకపోయాను. " + m.app + " తెరిచి పంపండి.");
            return;
        }
        try {
            NotifyListener.reply(svc, it, text);
        } catch (Exception e) {
            cannot("పంపలేకపోయాను. " + m.app + " తెరిచి పంపండి.");
            return;
        }
        Store.get(svc).addChat("assistant", m.from + " కి " + m.app + " లో పంపాను: \"" + text + "\"", false);
        card.status("✓ పంపాను");
        card.buttons("✕", (Runnable) () -> end(false));
        speak("పంపాను.");
    }

    // ================================================================ media (WhatsApp voice notes, photos, videos)

    /** ▶ the voice note out loud, then the reply question. */
    private void playVoice() {
        quiet();
        hold();
        step = READING;
        reading = "";
        voiceBusy = true;
        cancelPlay = false;
        card.status("🔊 వాయిస్ మెసేజ్ వెతుకుతున్నాను…");
        stepButtons();
        final String kind = "audio".equals(m.media) ? "audio" : "voice";
        new Thread(() -> {
            WaMedia.Found f = newest(kind);
            boolean ok = false;
            if (f != null && !cancelPlay) {
                main.post(() -> { if (!stopped) { card.heardNow(); card.status("🔊 వాయిస్ మెసేజ్ వినిపిస్తున్నాను…"); } });
                ok = WaMedia.play(svc, f.uri, () -> cancelPlay);
            }
            final boolean played = ok, found = f != null;
            main.post(() -> {
                voiceBusy = false;
                if (stopped) return;
                if (!found && !cancelPlay) { mediaMissing(kind); return; }
                if (!played && !cancelPlay) { cannot("వాయిస్ మెసేజ్ ఈ ఫోన్‌లో ప్లే కాలేదు. " + m.app + " తెరిచి వినండి."); return; }
                afterReading(); // played, or ⏹ stopped it
            });
        }, "jarvis-card-voice").start();
    }

    /** 📝 the voice note's words (OpenAI speech-to-text), read out. */
    private void voiceWords() {
        final String key = p.openAiKey().trim();
        if (key.isEmpty() || !Net.online(svc)) { playVoice(); return; } // can't turn it into words: play it instead
        quiet();
        hold();
        step = READING;
        card.status("📝 మాటలు రాస్తున్నాను…");
        card.buttons("✕", (Runnable) () -> end(false));
        final String kind = "audio".equals(m.media) ? "audio" : "voice";
        new Thread(() -> {
            WaMedia.Found f = newest(kind);
            String said = null;
            if (f != null) try { said = WaMedia.transcribe(svc, key, f.uri); } catch (Exception ignored) {}
            final String s = said;
            main.post(() -> {
                if (stopped || step != READING) return;
                if (f == null) { mediaMissing(kind); return; }
                if (s == null) { playVoice(); return; } // the words didn't come: play it
                reading = s.trim().isEmpty() ? "(మాటలు ఏమీ వినిపించలేదు)" : s.trim();
                card.status("");
                card.body("🎤 " + reading, 10);
                stepButtons();
                speak(reading);
            });
        }, "jarvis-card-words").start();
    }

    /** 💬 what is in the photo (the AI looks at it), read out. */
    private void describePhoto() {
        if (!p.hasBrain() || !Net.online(svc)) { openMedia("photo"); return; } // can't describe it here: show it
        quiet();
        hold();
        step = READING;
        card.status("👀 ఫోటో చూస్తున్నాను…");
        card.buttons("👁 చూపించు", (Runnable) () -> openMedia("photo"), "✕", (Runnable) () -> end(false));
        new Thread(() -> {
            WaMedia.Found f = newest("photo");
            String d = null;
            if (f != null) {
                try {
                    String jpeg = jpeg(f.uri);
                    if (jpeg != null) d = Brain.oneShot(p, "You are Jarvis, " + p.name() + "'s assistant. In simple Telugu (Telugu script), 2-3 short "
                            + "sentences for reading aloud: what is in this photo someone sent him on " + m.app + ", and any text in it. No markdown.",
                            "The photo from " + m.from + ".", jpeg, false, 400);
                } catch (Exception ignored) {}
            }
            final String o = d == null ? "" : d.replaceAll("[*#_`>]", "").trim();
            main.post(() -> {
                if (stopped || step != READING) return;
                if (f == null) { mediaMissing("photo"); return; }
                if (o.isEmpty()) {
                    cannot("ఫోటో ఏముందో చెప్పలేకపోయాను. 👁 చూపించు నొక్కండి.", "👁 చూపించు", (Runnable) () -> openMedia("photo"));
                    return;
                }
                reading = o;
                card.status("");
                card.body(o, 10);
                card.buttons("⏹ ఆపు", (Runnable) this::stopReading, "👁 చూపించు", (Runnable) () -> openMedia("photo"), "✕", (Runnable) () -> end(false));
                speak(o);
            });
        }, "jarvis-card-photo").start();
    }

    /** 👁 / ▶: the photo or video opens in the phone's viewer (he asked to see it), and the card goes. */
    private void openMedia(String kind) {
        quiet();
        card.status("⏳ వెతుకుతున్నాను…");
        new Thread(() -> {
            WaMedia.Found f = newest(kind);
            main.post(() -> {
                if (stopped) return;
                if (f == null) { mediaMissing(kind); return; }
                try {
                    svc.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, "video".equals(kind) ? "video/*" : "image/*")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION));
                    card.heardNow();
                    end(false);
                } catch (Exception e) {
                    cannot("ఇది తెరవలేకపోయాను. " + m.app + " తెరిచి చూడండి.");
                }
            });
        }, "jarvis-card-open").start();
    }

    /** A file saved this long before the message came can still be its own (clocks, the download starting early). */
    private static final long MEDIA_SLACK_MS = 2 * 60_000L;

    /**
     * The newest received file of this kind that is not older than this message (an older one belongs to another
     * message); it may still be downloading, so looked for again once. Off the main thread.
     */
    private WaMedia.Found newest(String kind) {
        long since = (m.postedAt > 0 ? m.postedAt : System.currentTimeMillis()) - MEDIA_SLACK_MS;
        for (int i = 0; i < 2 && !stopped && !cancelPlay; i++) {
            if (i > 0) android.os.SystemClock.sleep(3000);
            List<WaMedia.Found> f = WaMedia.newest(svc, kind, 1);
            if (!f.isEmpty() && f.get(0).modified >= since) return f.get(0);
        }
        return null;
    }

    private void mediaMissing(String kind) {
        if (stopped) return;
        boolean noFolder = WaMedia.tree(svc).isEmpty() && ("voice".equals(kind) || "audio".equals(kind));
        cannot(noFolder ? "WhatsApp వాయిస్ మెసేజ్‌లు చూడటానికి Jarvis కి ఇంకా అనుమతి లేదు. Jarvis సెట్టింగ్స్‌లో \"WhatsApp మీడియా\" లో ఒక్కసారి ఫోల్డర్ అనుమతి ఇవ్వండి."
                : "ఆ ఫైల్ ఫోన్‌లో దొరకలేదు (ఇంకా డౌన్‌లోడ్ కాలేదేమో). " + m.app + " తెరిచి చూడండి.");
    }

    /** Something could not be done: said, then the card goes (not heard yet: it waits under the dot). Extra buttons may stay. */
    private void cannot(String why, Object... extra) {
        step = DONE;
        doneMs = 8000; // time to read why
        doneLater = !card.heard();
        card.status("✗ " + why);
        Object[] b = new Object[extra.length + 2];
        System.arraycopy(extra, 0, b, 0, extra.length);
        b[extra.length] = "✕";
        b[extra.length + 1] = (Runnable) () -> end(doneLater);
        card.buttons(b);
        speak(why);
    }

    /** A photo for the AI: at most 1280 px, JPEG, base64. */
    private String jpeg(Uri u) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = svc.getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 1280) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap b;
            try (InputStream in = svc.getContentResolver().openInputStream(u)) { b = BitmapFactory.decodeStream(in, null, o2); }
            if (b == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 82, out);
            b.recycle();
            return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================ his answers

    private static int where(int step) { return step == ASK ? Words.ASK : step == CONFIRM ? Words.CONFIRM : Words.REPLY; }

    /** What he said, for the step Jarvis is at. */
    private void answer(String t) {
        if (step == DONE) { letGo(); card.hideIn(doneMs, doneLater); return; } // he spoke over the last words
        int a = Words.kind(t, where(step));
        switch (step) {
            case ASK:
                if (a == Words.NO) { card.heardNow(); finishSoon("సరే", 3000); speak("సరే."); return; }
                if (a == Words.LATER) { step = DONE; doneLater = true; card.status("⏰ తర్వాత"); card.buttons("✕", (Runnable) () -> end(true)); speak("సరే, తర్వాత."); return; }
                if (a == Words.SUMMARY) { summary(); return; }
                if ("voice".equals(m.media) || "audio".equals(m.media)) {
                    if (a == Words.WORDS || a == Words.DESCRIBE) { voiceWords(); return; } // (no OpenAI key: played instead)
                    if (a == Words.YES || a == Words.SHOW) { playVoice(); return; }
                } else if ("photo".equals(m.media)) {
                    if (a == Words.SHOW) { openMedia("photo"); return; }
                    if (a == Words.YES || a == Words.DESCRIBE) { describePhoto(); return; }
                } else if ("video".equals(m.media)) {
                    if (a == Words.YES || a == Words.SHOW || a == Words.DESCRIBE) { openMedia("video"); return; }
                } else if (a == Words.YES || a == Words.DESCRIBE || a == Words.SHOW) {
                    readNow(false);
                    return;
                }
                notUnderstood(m.ask);
                return;
            case READING: // he spoke over the reading ("రిప్లై ఇవ్వు", "చాలు"…): the reading stops there
                quiet();
                // fall through: what he said answers "రిప్లై ఇవ్వమంటారా?"
            case ASK_REPLY:
                if (m.id <= 0) { finishSoon("✓ చదివాను", 4000); return; } // this chat can't be answered from here
                if (a == Words.NO || a == Words.LATER) { finishSoon("✓ చదివాను", 3000); speak("సరే."); return; }
                if (a == Words.YES) { dictate(); return; }
                reply = Words.reply(t); // he said the reply straight away
                confirm();
                return;
            case DICTATE:
                if (a == Words.NO && Words.count(t) <= 2) { finishSoon("పంపలేదు", 3000); speak("సరే, పంపలేదు."); return; }
                reply = Words.reply(t);
                if (reply.isEmpty()) { notUnderstood("ఏం చెప్పమంటారు?"); return; }
                confirm();
                return;
            case CONFIRM:
                if (a == Words.YES) {
                    if (otherSound()) { waitForTap("🔇 వేరే సౌండ్ వస్తోంది: పంపాలంటే 📤 నొక్కండి"); return; } // that "yes" may not be his
                    send();
                    return;
                }
                if (a == Words.NO) { finishSoon("పంపలేదు", 3000); speak("సరే, పంపలేదు."); return; }
                if (a == Words.CHANGE) { reply = ""; step = DICTATE; card.buttons("✕", (Runnable) () -> end(false)); speak("సరే, మళ్ళీ చెప్పండి."); return; }
                if (Words.hasTail(t)) { // he said a new reply in full ("… అని చెప్పు")
                    String r = Words.reply(t);
                    if (!r.isEmpty()) { reply = r; confirm(); return; }
                }
                notUnderstood("పంపమంటారా? పంపు, మార్చు, లేదా వద్దు అనండి.");
                return;
            default:
        }
    }

    /** Not understood: asked once more, then the buttons wait for a tap. */
    private void notUnderstood(String again) {
        if (++unclear <= 1) { speak(again); return; }
        waitForTap("అర్థం కాలేదు: కింద బటన్ నొక్కండి");
    }

    /** No (clear) answer: Jarvis stops talking; the card waits for a tap for a while, then goes. */
    private void waitForTap(String status) {
        if (voice != null && voice.listening) voice.cancelListening();
        card.status(status);
        letGo();
        stepButtons();
        boolean later = step == ASK && !card.heard(); // not heard yet: put off under the dot
        card.hideIn(step == CONFIRM || step == DICTATE ? 20_000 : 10_000, later);
    }

    /** Another app is playing sound right now (its words could be heard as his). */
    private boolean otherSound() {
        AudioManager am = svc.getSystemService(AudioManager.class);
        return am != null && am.isMusicActive();
    }

    /** While he dictates and confirms a reply, his other sound (a video, music) is paused; it carries on afterwards. */
    private void focus(boolean on) {
        AudioManager am = svc.getSystemService(AudioManager.class);
        if (am == null) return;
        try {
            if (on && focus == null) {
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .build();
                am.requestAudioFocus(focus);
            } else if (!on && focus != null) {
                am.abandonAudioFocusRequest(focus);
                focus = null;
            }
        } catch (Exception ignored) {}
    }

    // ================================================================ voice

    private void speak(String text) {
        if (stopped || voice == null) return;
        if (othersUp()) { yieldToOthers(); return; }
        hold();
        card.stayOpen();
        voice.speak(text, p.speechRate());
    }

    /** A tap while Jarvis speaks or listens: that stops first. */
    private void quiet() {
        main.removeCallbacks(listenNow);
        card.stayOpen();
        if (voice == null) return;
        if (voice.listening) voice.cancelListening();
        if (voice.speaking) voice.stopSpeaking();
    }

    private final Runnable listenNow = this::listen;

    private void listen() {
        if (stopped || voice == null) return;
        if (othersUp()) { yieldToOthers(); return; }
        if (svc.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || !voice.canListen()) {
            waitForTap("🎙️ మైక్ అనుమతి లేదు: బటన్ నొక్కండి");
            return;
        }
        hold();
        if (step == DICTATE || step == CONFIRM) focus(true); // his video / music pauses while he gives the reply
        if (step == DICTATE || step == ASK_REPLY) voice.listenLong(p.listenLang()); else voice.listen(p.listenLang()); // (a reply may be long)
        card.status("🎙️ ఒక్క క్షణం…");
    }

    private String hint() {
        switch (step) {
            case ASK: return "ఆ / వద్దు / తర్వాత";
            case ASK_REPLY: return "అవును / వద్దు, లేదా జవాబు చెప్పండి";
            case DICTATE: return "జవాబు చెప్పండి";
            case CONFIRM: return "పంపు / మార్చు / వద్దు";
            default: return "";
        }
    }

    @Override public void onListening() { if (!stopped) card.status("🎙️ వింటున్నాను… (" + hint() + ")"); }

    @Override public void onPartial(String text) {
        if (stopped) return;
        if (text == null || text.isEmpty()) onListening(); // (that was only a noise)
        else card.status("🎙️ “" + text + "”");
    }

    @Override public void onUnderstanding() { if (!stopped) card.status("🎙️ అర్థం చేసుకుంటున్నాను…"); }

    @Override public void onHeard(String text) {
        if (stopped) return;
        String t = text == null ? "" : text.trim();
        if (voice.isPaused()) { // he spoke over Jarvis
            // over a question: a clear answer to it ("తర్వాత", "ప్లే", "చెప్పండి") is that answer, not "carry on"
            // ("ఆపు" / "చాలు" stay commands; "తర్వాత", "ప్లే", "చెప్పండి" here answer the question rather than carry it on)
            int cmd = t.isEmpty() ? VoiceIO.CMD_NONE : VoiceIO.command(t);
            if ((step == ASK || step == ASK_REPLY || step == CONFIRM) && !t.isEmpty()
                    && (cmd == VoiceIO.CMD_NONE || cmd == VoiceIO.CMD_RESUME) && Words.kind(t, where(step)) != Words.UNCLEAR) {
                voice.stopSpeaking();
            } else {
                int r = voice.pausedHeard(text); // "ఆపు" / "కొనసాగించు" / "చాలు", or a new answer
                if (afterPaused(r)) return;
            }
        }
        if (t.isEmpty()) { onListenFailed(SpeechRecognizer.ERROR_NO_MATCH); return; }
        card.status("🎙️ “" + t + "”");
        answer(t);
    }

    /** A held speech after he spoke: true when nothing more is to be done with his words. */
    private boolean afterPaused(int r) {
        if (r == VoiceIO.RESUMED) { card.status(""); return true; }
        if (r == VoiceIO.HELD) {
            card.status("⏸ ఆపాను");
            letGo();
            card.buttons("▶ కొనసాగించు", (Runnable) this::carryOn, "✕", (Runnable) () -> end(step == ASK && !card.heard()));
            card.hideIn(60_000, step == ASK && !card.heard());
            return true;
        }
        if (r == VoiceIO.STOPPED) { // "చాలు"
            if (step == READING) { afterReading(); return true; }
            finishSoon("సరే", 2500);
            return true;
        }
        return false; // a new answer: the held speech is dropped
    }

    /** ▶ కొనసాగించు: the held speech carries on (if it is still held), and the step's own buttons come back. */
    private void carryOn() {
        stepButtons();
        card.status("");
        if (voice != null && voice.isPaused()) {
            hold();
            card.stayOpen();
            voice.resume();
        } else {
            card.hideIn(10_000, step == ASK && !card.heard());
        }
    }

    @Override public void onListenFailed(int error) {
        if (stopped) return;
        if (voice.isPaused()) { // nothing (clear) while held: carry on, or stay held if he held it
            if (afterPaused(voice.pausedHeard(""))) return;
        }
        if (step == DONE) { letGo(); card.hideIn(doneMs, doneLater); return; }
        boolean silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
        if (silence && step == ASK_REPLY) { finishSoon("✓ చదివాను", 5000); return; } // no reply wanted
        String why = silence ? "జవాబు రాలేదు" : "🎙️ ఫోన్ ఇప్పుడు మైక్ ఇవ్వలేదు (" + VoiceIO.failText(error) + ")";
        waitForTap(why + ": కింద బటన్ నొక్కండి");
    }

    @Override public void onLevel(float level) {}

    @Override public void onSpeakStart() {
        if (stopped) return;
        if (step == READING) card.heardNow(); // heard from here on: not put off for later
        else card.status("🔊 …");
    }

    @Override public void onSpeakDone() {
        if (stopped) return;
        switch (step) {
            case READING:
                afterReading();
                break;
            case ASK:
            case ASK_REPLY:
            case DICTATE:
            case CONFIRM:
                main.removeCallbacks(listenNow);
                main.postDelayed(listenNow, 250); // the voice's tail must not be heard
                break;
            case DONE:
                letGo();
                card.hideIn(doneMs, doneLater);
                break;
            default:
        }
    }

    @Override public void onVoiceReady() {}

    /** He talked over Jarvis: the speech is held (not lost) and the mic opens for him. */
    @Override public void onBargeIn() {
        if (stopped) return;
        main.post(() -> { if (!stopped && voice != null && !voice.listening) voice.listen(p.listenLang()); });
    }

    @Override public void onWord(String spoken, int start, int end) {
        if (!stopped && step == READING) card.highlight(spoken, start, end);
    }

    /** Another Jarvis screen started listening (he opened the panel or the app): the card steps aside. */
    @Override public void onMicTaken() {
        if (!stopped) yieldTo();
    }

    // ================================================================ understanding a short answer

    /** His short answers: yes, no, later, summary, send, change… (Telugu words may carry an ending). Pure, for tests. */
    static final class Words {
        static final int UNCLEAR = 0, YES = 1, NO = 2, LATER = 3, SUMMARY = 4, SHOW = 5, DESCRIBE = 6, WORDS = 7, CHANGE = 8;
        static final int ASK = 0, REPLY = 1, CONFIRM = 2;

        private static final String[] NO_W = {"వద్దు", "లేదు", "no", "నో", "cancel", "క్యాన్సిల్", "అక్కర్లేదు", "అవసరంలేదు", "nope",
                "పంపకు", "చదవకు", "ఇవ్వకు", "వదిలేయ్", "వదిలెయ్", "వదిలేయి", "don", "dont"};
        private static final String[] LATER_W = {"తర్వాత", "తరువాత", "later", "తర్వాతచదువు"};
        /** "Wait" (not "no"): over Jarvis's voice they pause it; to "చదవమంటారా?" they put it off for later. */
        private static final String[] WAIT_W = {"ఆగు", "ఆగండి", "ఆపు", "ఆపండి", "ఆపేయ్", "wait", "stop", "హోల్డ్", "hold"};
        private static final String[] SUM_W = {"సారాంశం", "క్లుప్తంగా", "summary", "సంక్షిప్తంగా", "షార్ట్"};
        private static final String[] SHOW_W = {"చూపించు", "చూపు", "show", "ఓపెన్", "open", "ప్లే", "play", "విను", "వినిపించు"};
        private static final String[] DESC_W = {"ఏముంది", "ఏముందో", "describe", "చెప్పు", "చెప్పండి", "what"};
        private static final String[] WORDS_P = {"ఏం చెప్పారు", "ఏమన్నారు", "ఏమి చెప్పారు", "మాటలు", "టెక్స్ట్", "text", "రాసి"};
        private static final String[] YES_W = {"అవును", "చదువు", "చదవండి", "చదివి", "ఓకే", "ok", "okay", "yes", "yeah", "సరే", "హా", "హాం", "ఆ",
                "కావాలి", "read", "యెస్"};
        private static final String[] SEND_W = {"పంపు", "పంపండి", "పంపేయ్", "పంపేయి", "పంపేస", "పంపించు", "పంపించండి", "send", "పెట్టు", "పెట్టేయ్", "పెట్టేస"};
        private static final String[] CHANGE_W = {"మార్చు", "మార్చండి", "మార్చి", "వేరే", "change", "edit", "మళ్ళీ", "మళ్లీ", "తప్పు"};
        /** Words that only say "yes, a reply" (so "ఓకే రిప్లై ఇవ్వు" is a yes, not the reply itself). */
        private static final String[] REPLY_YES_W = {"రిప్లై", "reply", "జవాబు", "ఇవ్వు", "ఇవ్వండి", "ఇద్దాం", "చెయ్", "చేయి", "చేయండి", "పంపు",
                "పంపండి", "కావాలి", "జార్విస్", "jarvis", "ప్లీజ్", "please"};
        /**
         * "Send it": only these, each word exactly (no endings, so "సరేనా?" / "పంపుతావా?" are questions, not consent; "Jarvis",
         * "చదువు" or a filler like "ఆ" never send).
         */
        private static final String[] CONSENT_W = {"అవును", "yes", "ఓకే", "ok", "okay", "సరే", "యెస్", "పంపు", "పంపండి", "పంపేయ్", "పంపేయి",
                "పంపేసెయ్", "పంపేసేయ్", "పంపేసేయి", "పంపించు", "పంపించండి", "send"};

        static String[] tokens(String t) {
            return (t == null ? "" : t.toLowerCase(Locale.ROOT)).split("[^\\p{L}\\p{M}\\p{N}]+");
        }

        static int count(String t) {
            int n = 0;
            for (String s : tokens(t)) if (!s.isEmpty()) n++;
            return n;
        }

        /** A Telugu word may carry an ending (చదువు → చదవండి is listed; "పంపేసెయ్" starts with పంపేస…); English must match whole. */
        private static boolean is(String tok, String[] words) {
            for (String w : words) {
                if (tok.equals(w)) return true;
                if (w.charAt(0) > 0x7F && w.length() >= 3 && tok.startsWith(w)) return true;
            }
            return false;
        }

        private static boolean exact(String tok, String[] words) {
            for (String w : words) if (tok.equals(w)) return true;
            return false;
        }

        private static boolean has(String t, String[] words) {
            for (String tok : tokens(t)) if (!tok.isEmpty() && is(tok, words)) return true;
            return false;
        }

        private static boolean no(String t) {
            String[] ts = tokens(t);
            for (int i = 0; i < ts.length; i++) {
                String tok = ts[i];
                if (tok.isEmpty()) continue;
                if (is(tok, NO_W) || tok.endsWith("ద్దు")) return true; // చదవొద్దు, పంపొద్దు, వద్దు
                if (tok.equals("not") && (i > 0 && (ts[i - 1].equals("do") || ts[i - 1].equals("does")) || i + 1 < ts.length && ts[i + 1].equals("now"))) return true;
            }
            return false;
        }

        /** Every word is one of these (prefix rule): "అవును ఇవ్వు", "ఓకే రిప్లై ఇవ్వు". */
        private static boolean only(String t, String[]... lists) {
            int n = 0;
            for (String tok : tokens(t)) {
                if (tok.isEmpty()) continue;
                n++;
                boolean ok = false;
                for (String[] l : lists) if (is(tok, l)) { ok = true; break; }
                if (!ok) return false;
            }
            return n > 0;
        }

        /** Every word exactly a consent word ("పంపు", "అవును పంపు", "ok send"); "it" / "ఇది" only alongside one ("send it"). */
        private static boolean consent(String t) {
            int n = 0;
            for (String tok : tokens(t)) {
                if (tok.isEmpty() || tok.equals("it") || tok.equals("ఇది") || tok.equals("దీన్ని")) continue;
                n++;
                if (!exact(tok, CONSENT_W)) return false;
            }
            return n > 0;
        }

        static int kind(String t, int where) {
            String low = t == null ? "" : t.toLowerCase(Locale.ROOT);
            if (where == CONFIRM) {
                if (no(low)) return NO; // any no, however long: not sent
                if (has(low, CHANGE_W)) return CHANGE;
                if (consent(low)) return YES;
                return UNCLEAR;
            }
            // a no; but a longer answer to "reply?" with లేదు in it ("రేపు రావడం లేదు అని చెప్పు") is the reply itself
            if (no(low) && (where == ASK || count(low) <= 3) && !hasTail(low)) return NO;
            if (where == REPLY) {
                if (has(low, LATER_W) && count(low) <= 2 || only(low, WAIT_W)) return LATER; // ("ఆగు వస్తున్నా" is a reply)
                if (only(low, YES_W, REPLY_YES_W)) return YES;
                return UNCLEAR; // anything else: it is the reply itself
            }
            if (has(low, LATER_W) || has(low, WAIT_W)) return LATER;
            if (has(low, SUM_W)) return SUMMARY;
            for (String w : WORDS_P) if (low.contains(w)) return WORDS;
            if (has(low, SHOW_W)) return SHOW;
            if (has(low, DESC_W)) return DESCRIBE;
            if (has(low, YES_W) || has(low, SEND_W)) return YES;
            return UNCLEAR;
        }

        private static final java.util.regex.Pattern TAIL = java.util.regex.Pattern.compile(
                "[\\s,.!]*(అని)\\s*(చెప్పు|చెప్పండి|చెప్పేయ్|పంపు|పంపండి|పంపేయ్|పంపించు|రిప్లై\\s*(ఇవ్వు|ఇవ్వండి|పెట్టు|చెయ్|చేయి)?|రాయి|రాయండి|జవాబు\\s*(ఇవ్వు|ఇవ్వండి|చెప్పు)?|మెసేజ్\\s*(పెట్టు|చెయ్|చేయి)|పెట్టు|టైప్\\s*(చెయ్|చేయి))\\s*[.!]*$");
        private static final java.util.regex.Pattern HEAD = java.util.regex.Pattern.compile(
                "^\\s*((రిప్లై|జవాబు)\\s*(ఇవ్వు|ఇవ్వండి)?|reply)\\s*[:,]?\\s*");

        /** It ends with "… అని చెప్పు / పంపు / రిప్లై ఇవ్వు": what comes before is a reply he dictated. */
        static boolean hasTail(String t) {
            return t != null && TAIL.matcher(t.trim()).find();
        }

        /** The reply he dictated, without "… అని చెప్పు" / "రిప్లై:" around it. */
        static String reply(String t) {
            String s = t == null ? "" : t.trim();
            s = TAIL.matcher(s).replaceAll("");
            s = HEAD.matcher(s).replaceAll("");
            return s.trim();
        }
    }
}
