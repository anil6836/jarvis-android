package com.anil.jarvis;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

/**
 * Jarvis's voice for the watch (W11): the same voice as on the phone (OpenAI's natural voice when that is on, else the
 * phone's Telugu voice), made on the phone and sent to the watch as 16 kHz mu-law while it is being made, so the watch
 * starts speaking early. When his earphones are on the phone, the answer is said there instead (he hears the phone,
 * not the watch's speaker). Like the phone: if the natural voice fails before any sound, the phone's voice says it.
 */
final class WatchVoice {
    private WatchVoice() {}

    private static volatile int gen;
    static volatile String lastError = "";
    /** The main thread (made on first use, so the parts without Android can be tested on a desk). */
    private static final class M { static final Handler h = new Handler(Looper.getMainLooper()); }

    /** Something that takes the voice as it is made. */
    private interface Sink {
        void write(short[] pcm, int n, int rate) throws Exception;
        boolean wrote();
        /** All of it was made; finish (the phone's earphones: wait until heard, then done). */
        void end(LongConsumer sentMs, Runnable done) throws Exception;
        void abort();
    }

    /**
     * Says text. sentMs: all of the sound has gone to the watch (its length in ms; the watch says when it has played it).
     * done: said on the phone's earphones, or no voice could be made at all (nothing to wait for).
     * local: his earphones are on the phone: said there, not on the watch.
     */
    static void say(Context c, String text, int id, boolean local, LongConsumer sentMs, Runnable done) {
        final Context app = c.getApplicationContext();
        final int g = ++gen;
        final Prefs p = new Prefs(app);
        String clean = text.replaceAll("[*_#`>]", "").replaceAll("https?://\\S+", "").trim();
        if (clean.length() > 3500) clean = clean.substring(0, 3500);
        final String said = Spoken.of(clean).cut(3900).text; // numbers as Telugu words, as on the phone
        final String feeling = p.emotions() ? Emotion.forText(text) : Emotion.CALM;
        new Thread(() -> {
            Sink sink = local ? new PhoneSink(app, g) : new ToWatch(app, id, g);
            try {
                boolean ok = false;
                String key = p.openAiKey().trim();
                if (p.naturalVoice() && !key.isEmpty() && Net.online(app)) {
                    try {
                        natural(key, p, said, feeling, sink, g);
                        ok = true;
                    } catch (Exception e) {
                        lastError = "సహజ గొంతు: " + e.getMessage();
                        if (sink.wrote() || g != gen) throw e; // (half said: not again in another voice)
                    }
                }
                if (!ok && g == gen) phoneVoice(app, p, said, sink, g);
                if (g != gen) { sink.abort(); return; }
                sink.end(sentMs, done);
                if (ok) lastError = "";
            } catch (Exception e) {
                if (g != gen) { sink.abort(); return; }
                lastError = String.valueOf(e.getMessage());
                if (sink.wrote()) {
                    try { sink.end(sentMs, done); } catch (Exception ignored) { done.run(); }
                } else {
                    sink.abort();
                    done.run();
                }
            }
        }, "jarvis-watch-voice").start();
    }

    /** Whatever is being said or made stops (a new question, or his stop). */
    static void stop() {
        gen++;
        PhoneSink t = PhoneSink.playing;
        if (t != null) t.abort();
    }

    // ---------------------------------------------------------------- the two voices

    /** OpenAI's natural voice (his model and voice), 24 kHz sound as it streams. */
    private static void natural(String key, Prefs p, String text, String feeling, Sink sink, int g) throws Exception {
        String model = p.ttsModel();
        boolean old = model.toLowerCase(Locale.ROOT).startsWith("tts-1");
        JSONObject body = new JSONObject().put("model", model).put("voice", p.naturalVoiceName()).put("input", text).put("response_format", "pcm");
        if (!old) body.put("instructions", NaturalVoice.STYLE + Emotion.style(feeling));
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.openai.com/v1/audio/speech").openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream o = c.getOutputStream()) { o.write(bytes); }
            int status = c.getResponseCode();
            if (status >= 400) {
                String err = "";
                try (InputStream es = c.getErrorStream()) {
                    if (es != null) { byte[] b = new byte[600]; int n = es.read(b); if (n > 0) err = new String(b, 0, n, StandardCharsets.UTF_8); }
                }
                throw new IllegalStateException("OpenAI " + status + " " + err);
            }
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[9600];
                int have = 0;
                int n;
                while ((n = in.read(buf, have, buf.length - have)) > 0) {
                    if (g != gen) return;
                    have += n;
                    int whole = have & ~1;
                    if (whole >= 3200 || whole == buf.length) {
                        short[] s = new short[whole / 2];
                        for (int i = 0; i < s.length; i++) s[i] = (short) ((buf[2 * i] & 0xFF) | (buf[2 * i + 1] << 8));
                        sink.write(s, s.length, NaturalVoice.RATE);
                        System.arraycopy(buf, whole, buf, 0, have - whole);
                        have -= whole;
                    }
                }
                if (have >= 2 && g == gen) {
                    short[] s = new short[have / 2];
                    for (int i = 0; i < s.length; i++) s[i] = (short) ((buf[2 * i] & 0xFF) | (buf[2 * i + 1] << 8));
                    sink.write(s, s.length, NaturalVoice.RATE);
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private static TextToSpeech tts;
    private static volatile boolean ttsOk;

    /** The phone's own Telugu voice, made into a file first (then sent). */
    private static void phoneVoice(Context app, Prefs p, String text, Sink sink, int g) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        M.h.post(() -> {
            if (tts != null) { ready.countDown(); return; }
            tts = new TextToSpeech(app, st -> { ttsOk = st == TextToSpeech.SUCCESS; ready.countDown(); });
        });
        if (!ready.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("ఫోన్ గొంతు (Text-to-speech) స్పందించలేదు");
        if (tts == null || !ttsOk && tts.getVoice() == null) throw new IllegalStateException("ఫోన్‌లో Text-to-speech పనిచేయడం లేదు");
        File f = new File(app.getCacheDir(), "watch_voice.wav");
        CountDownLatch done = new CountDownLatch(1);
        final boolean[] fine = {false};
        String uid = "w" + g;
        synchronized (WatchVoice.class) {
            tts.setLanguage(Locale.forLanguageTag("te-IN"));
            tts.setSpeechRate(p.speechRate());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { if (uid.equals(id)) { fine[0] = true; done.countDown(); } }
                @Override public void onError(String id) { if (uid.equals(id)) done.countDown(); }
            });
            if (tts.synthesizeToFile(text, null, f, uid) != TextToSpeech.SUCCESS) throw new IllegalStateException("ఫోన్ గొంతు ఫైల్ తయారు కాలేదు");
        }
        if (!done.await(30, TimeUnit.SECONDS) || !fine[0]) throw new IllegalStateException("ఫోన్ గొంతు ఫైల్ తయారు కాలేదు");
        if (g != gen) return;
        int[] rate = new int[1];
        short[] pcm = readWav(f, rate);
        f.delete();
        for (int at = 0; at < pcm.length && g == gen; at += rate[0] / 5) { // a fifth of a second at a time
            int n = Math.min(rate[0] / 5, pcm.length - at);
            sink.write(java.util.Arrays.copyOfRange(pcm, at, at + n), n, rate[0]);
        }
    }

    /** 16-bit mono (or the first channel) samples of a WAV file; rate[0] gets its rate. */
    static short[] readWav(File f, int[] rate) throws Exception {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] all = new byte[(int) r.length()];
            r.readFully(all);
            return parseWav(all, rate);
        }
    }

    static short[] parseWav(byte[] b, int[] rate) {
        if (b.length < 12 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F') throw new IllegalStateException("గొంతు ఫైల్ WAV కాదు");
        int at = 12, channels = 1, bits = 16;
        rate[0] = 16000;
        while (at + 8 <= b.length) {
            String id = new String(b, at, 4, StandardCharsets.US_ASCII);
            int len = le(b, at + 4);
            int body = at + 8;
            if ("fmt ".equals(id) && body + 16 <= b.length) {
                channels = Math.max(1, (b[body + 2] & 0xFF) | (b[body + 3] & 0xFF) << 8);
                rate[0] = le(b, body + 4);
                bits = (b[body + 14] & 0xFF) | (b[body + 15] & 0xFF) << 8;
            } else if ("data".equals(id)) {
                if (bits != 16) throw new IllegalStateException("గొంతు ఫైల్ " + bits + "-bit");
                int end = len <= 0 || body + len > b.length ? b.length : body + len;
                int frames = (end - body) / (2 * channels);
                short[] s = new short[frames];
                for (int i = 0; i < frames; i++) {
                    int k = body + i * 2 * channels;
                    s[i] = (short) ((b[k] & 0xFF) | (b[k + 1] << 8));
                }
                return s;
            }
            if (len < 0) break;
            at = body + len + (len & 1);
        }
        throw new IllegalStateException("గొంతు ఫైల్‌లో శబ్దం లేదు");
    }

    private static int le(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
    }

    // ---------------------------------------------------------------- where it goes

    /** To the watch: 16 kHz mu-law messages of 200 ms (WatchHub.P_AUDIO_*). */
    private static final class ToWatch implements Sink {
        private final Context app;
        private final int id, g;
        private final Ulaw.Down24 down = new Ulaw.Down24();
        private byte[] pending = new byte[0];
        private int seq;
        private long samples;
        private boolean started;

        ToWatch(Context app, int id, int g) { this.app = app; this.id = id; this.g = g; }

        @Override public void write(short[] pcm, int n, int rate) throws Exception {
            if (g != gen) return;
            short[] s = rate == NaturalVoice.RATE ? down.push(pcm, n) : Ulaw.to16k(pcm, n, rate);
            samples += s.length;
            byte[] u = Ulaw.encode(s, 0, s.length);
            byte[] all = new byte[pending.length + u.length];
            System.arraycopy(pending, 0, all, 0, pending.length);
            System.arraycopy(u, 0, all, pending.length, u.length);
            int at = 0;
            for (; at + 3200 <= all.length; at += 3200) packet(all, at, 3200);
            pending = java.util.Arrays.copyOfRange(all, at, all.length);
        }

        private void packet(byte[] b, int at, int n) throws Exception {
            if (!started) {
                started = true;
                WatchHub.send(app, WatchHub.P_AUDIO_START, new JSONObject().put("id", id).put("rate", Ulaw.RATE));
            }
            final int myId = id;
            WatchHub.send(app, WatchHub.P_AUDIO_DATA, Ulaw.packet(id, seq++, b, at, n), () -> WatchHub.voiceWanted(myId) && g == gen);
        }

        @Override public boolean wrote() { return started || pending.length > 0; }

        @Override public void end(LongConsumer sentMs, Runnable done) throws Exception {
            if (pending.length > 0) packet(pending, 0, pending.length);
            pending = new byte[0];
            if (!started) { done.run(); return; } // no sound at all: nothing to wait for
            WatchHub.send(app, WatchHub.P_AUDIO_END, new JSONObject().put("id", id).put("seqs", seq));
            sentMs.accept(samples * 1000 / Ulaw.RATE);
        }

        @Override public void abort() {
            if (!started) return;
            try { WatchHub.send(app, WatchHub.P_AUDIO_END, new JSONObject().put("id", id).put("seqs", seq).put("drop", true)); } catch (Exception ignored) {}
        }
    }

    /** His earphones are on the phone: said there (songs quieter meanwhile, as when the phone speaks). */
    private static final class PhoneSink implements Sink {
        static volatile PhoneSink playing;
        private final Context app;
        private final int g;
        private AudioTrack track;
        private int rate;
        private long frames;
        private volatile boolean aborted;

        PhoneSink(Context app, int g) { this.app = app; this.g = g; }

        @Override public void write(short[] pcm, int n, int r) throws Exception {
            if (aborted || g != gen) return;
            if (track == null) {
                rate = r;
                int min = AudioTrack.getMinBufferSize(r, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(r).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(Math.max(min, r))
                        .setTransferMode(AudioTrack.MODE_STREAM).build();
                playing = this;
                M.h.post(() -> Duck.on(app));
                track.play();
            }
            if (r != rate) { pcm = Ulaw.to16k(pcm, n, r); n = pcm.length; } // (never: one voice per answer)
            int at = 0;
            while (at < n && !aborted) {
                int w = track.write(pcm, at, n - at);
                if (w <= 0) break;
                at += w;
            }
            frames += n;
        }

        @Override public boolean wrote() { return track != null; }

        @Override public void end(LongConsumer sentMs, Runnable done) {
            AudioTrack t = track;
            if (t == null) { done.run(); return; }
            long until = System.currentTimeMillis() + frames * 1000 / Math.max(1, rate) + 3000;
            while (!aborted && System.currentTimeMillis() < until) {
                try { if (t.getPlaybackHeadPosition() >= frames) break; } catch (Exception e) { break; }
                try { Thread.sleep(50); } catch (InterruptedException e) { break; }
            }
            release();
            if (!aborted) done.run();
        }

        @Override public void abort() {
            aborted = true;
            release();
        }

        private synchronized void release() {
            AudioTrack t = track;
            track = null;
            if (playing == this) playing = null;
            if (t == null) return;
            try { t.pause(); t.flush(); } catch (Exception ignored) {}
            try { t.release(); } catch (Exception ignored) {}
            M.h.post(Duck::off);
        }
    }
}
