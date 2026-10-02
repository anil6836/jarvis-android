package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A book read aloud like an audiobook: .txt and .epub straight away, PDF pages read by the AI he chose (page by page,
 * kept on the phone so a page is never read twice). It remembers where it stopped and goes on from there.
 * Pauses while Jarvis speaks or listens, and for calls; continues after.
 */
public class ReaderService extends Service {
    static final String ACTION_START = "com.anil.jarvis.READ_START", ACTION_TOGGLE = "com.anil.jarvis.READ_TOGGLE", ACTION_STOP = "com.anil.jarvis.READ_STOP";
    private static final int NOTE = 191;
    static volatile String reading = "";
    static volatile boolean paused;

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private volatile boolean ttsReady, stopFlag, focusLost;
    private volatile CountDownLatch spoken;
    private Thread worker;
    private AudioFocusRequest focusReq;

    // ---------------------------------------------------------------- the books he has (his documents folder)

    /** {name, uri, mime} of the .txt, .epub and .pdf files in his documents folder (and its folders, 3 deep). */
    static List<String[]> books(Context c) {
        List<String[]> out = new ArrayList<>();
        String tree = new Prefs(c).docsTree();
        if (tree.isEmpty()) return out;
        Uri t = Uri.parse(tree);
        list(c, t, DocumentsContract.getTreeDocumentId(t), out, 0);
        return out;
    }

    private static void list(Context c, Uri tree, String docId, List<String[]> out, int depth) {
        if (depth > 3 || out.size() > 300) return;
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        try (Cursor cur = c.getContentResolver().query(kids, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                String id = cur.getString(0), n = cur.getString(1), mime = cur.getString(2);
                String low = n == null ? "" : n.toLowerCase(Locale.ROOT);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) list(c, tree, id, out, depth + 1);
                else if (low.endsWith(".txt") || low.endsWith(".epub") || low.endsWith(".pdf"))
                    out.add(new String[]{n, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), low.endsWith(".pdf") ? "pdf" : low.endsWith(".epub") ? "epub" : "txt"});
            }
        } catch (Exception ignored) {}
    }

    static SharedPreferences mark(Context c) { return c.getSharedPreferences("jarvis_reader", Context.MODE_PRIVATE); }

    static void start(Context c, String name, String uri, String kind, boolean fromStart) { start(c, name, uri, kind, fromStart, null); }

    /** onDone: what to do when it is read to the end ("bible_plan:N" marks that Bible plan part read); null = keep the bookmark's own. */
    static void start(Context c, String name, String uri, String kind, boolean fromStart, String onDone) {
        SharedPreferences m = mark(c);
        if (fromStart || !uri.equals(m.getString("uri", ""))) m.edit().putString("uri", uri).putString("name", name).putString("kind", kind)
                .putInt("unit", 0).putInt("chunk", 0).putString("on_done", onDone == null ? "" : onDone).apply();
        else if (onDone != null) m.edit().putString("on_done", onDone).apply();
        Intent i = new Intent(c, ReaderService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void control(Context c, String action) {
        if (reading.isEmpty()) return;
        try { c.startService(new Intent(c, ReaderService.class).setAction(action)); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- the service

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        String a = i == null ? ACTION_STOP : i.getAction();
        SharedPreferences m = mark(this);
        if (ACTION_START.equals(a)) {
            note("📖 " + m.getString("name", ""), "తెరుస్తున్నాను…");
            halt(); // another book (or the same again): the old reading stops first
            begin();
        } else if (ACTION_TOGGLE.equals(a)) {
            if (reading.isEmpty()) { stopSelf(id); return START_NOT_STICKY; } // nothing open (an old notification)
            paused = !paused;
            if (paused) halt(); else go();
            note("📖 " + m.getString("name", ""), paused ? "⏸ ఆగింది" : "చదువుతున్నాను");
        } else {
            stopFlag = true;
            halt();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void begin() {
        stopFlag = false;
        paused = false;
        reading = mark(this).getString("name", "");
        if (tts == null) {
            tts = new TextToSpeech(getApplicationContext(), s -> {
                ttsReady = s == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String u) {}
                        @Override public void onDone(String u) { CountDownLatch l = spoken; if (l != null) l.countDown(); }
                        @Override public void onError(String u) { CountDownLatch l = spoken; if (l != null) l.countDown(); }
                        @Override public void onStop(String u, boolean interrupted) { CountDownLatch l = spoken; if (l != null) l.countDown(); }
                    });
                    main.post(this::go);
                } else main.post(() -> { Announcer.say(this, "ఈ ఫోన్ వాయిస్ ఇంజిన్ తెరవలేకపోయాను."); stopSelf(); });
            });
        } else if (ttsReady) go();
    }

    /** Reads on from the bookmark (one worker at a time). */
    private void go() {
        if (worker != null || stopFlag || paused || !ttsReady) return;
        takeFocus();
        try { note("📖 " + mark(this).getString("name", ""), "చదువుతున్నాను"); } catch (Exception ignored) {}
        worker = new Thread(this::readLoop, "jarvis-reader");
        worker.start();
    }

    /** Stops speaking now; the bookmark stays at the sentence that was cut off. */
    private void halt() {
        Thread w = worker;
        worker = null;
        if (w != null) w.interrupt();
        try { if (tts != null) tts.stop(); } catch (Exception ignored) {}
        CountDownLatch l = spoken;
        if (l != null) l.countDown();
        dropFocus();
    }

    private void readLoop() {
        Thread me = Thread.currentThread();
        SharedPreferences m = mark(this);
        String uri = m.getString("uri", ""), kind = m.getString("kind", "txt");
        try {
            Prefs p = new Prefs(this);
            int unit = m.getInt("unit", 0), chunk = m.getInt("chunk", 0);
            int units = count(uri, kind);
            if (units <= 0) { finishWith("ఈ పుస్తకం తెరవలేకపోయాను."); return; }
            for (; unit < units; unit++, chunk = 0) {
                if (worker != me) return;
                String text = unitText(p, uri, kind, unit);
                if (worker != me) return;
                List<String> parts = split(text);
                if (kind.equals("pdf") && unit > 0) parts.add(0, "పేజీ " + (unit + 1) + "."); // always, so the bookmark counts the same
                for (int k = chunk; k < parts.size(); k++) {
                    if (worker != me) return;
                    m.edit().putInt("unit", unit).putInt("chunk", k).apply();
                    if (!say(parts.get(k), p)) return; // stopped / paused: this sentence is said again next time
                }
                m.edit().putInt("unit", unit + 1).putInt("chunk", 0).apply();
            }
            String done = m.getString("on_done", "");
            m.edit().remove("uri").remove("on_done").apply();
            if (done.startsWith("bible_plan:")) {
                try { Faith.planMark(this, Integer.parseInt(done.substring(11))); } catch (Exception ignored) {}
                finishWith("ఈరోజు బైబిల్ ప్లాన్ పూర్తయింది. దేవుడు మిమ్మల్ని దీవించును గాక.");
                return;
            }
            finishWith(m.getString("name", "పుస్తకం") + " పూర్తయింది.");
        } catch (Exception e) {
            if (worker == me) finishWith("చదవడంలో సమస్య వచ్చింది: " + (e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    private void finishWith(String line) {
        main.post(() -> {
            Announcer.say(this, line);
            worker = null;
            stopSelf();
        });
    }

    /** Speaks one piece and waits for it; false if it was cut off (paused / stopped). */
    private boolean say(String s, Prefs p) throws InterruptedException {
        CountDownLatch l = new CountDownLatch(1);
        spoken = l;
        try { tts.setLanguage(Lang.of(s)); } catch (Exception ignored) {}
        tts.setSpeechRate(p.speechRate());
        String said = Spoken.say(s);
        int max = TextToSpeech.getMaxSpeechInputLength() - 10;
        if (said.length() > max) said = said.substring(0, max); // a page of digits as words can pass the engine's limit
        if (tts.speak(said, TextToSpeech.QUEUE_FLUSH, null, "r" + System.nanoTime()) != TextToSpeech.SUCCESS)
            throw new IllegalStateException("ఫోన్ వాయిస్ ఇంజిన్ చదవలేకపోయింది");
        l.await(5, TimeUnit.MINUTES);
        return worker == Thread.currentThread() && !Thread.currentThread().isInterrupted() && !focusLost;
    }

    /** Sentences grouped up to about 280 characters. */
    static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        StringBuilder b = new StringBuilder();
        for (String s : text.replace("\r", "").split("(?<=[.!?।॥\\n])\\s+")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            if (b.length() > 0 && b.length() + t.length() > 280) { out.add(b.toString()); b.setLength(0); }
            if (b.length() > 0) b.append(' ');
            b.append(t);
            while (b.length() > 600) { out.add(b.substring(0, 600)); b.delete(0, 600); }
        }
        if (b.length() > 0) out.add(b.toString());
        return out;
    }

    // ---------------------------------------------------------------- the text of a book

    private int count(String uri, String kind) throws Exception {
        if (kind.equals("pdf")) {
            try (android.os.ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(Uri.parse(uri), "r");
                 android.graphics.pdf.PdfRenderer r = new android.graphics.pdf.PdfRenderer(fd)) { return r.getPageCount(); }
        }
        if (kind.equals("epub")) return epubChapters(uri).size();
        return txtParts(uri).size();
    }

    private List<int[]> txtParts;
    private String partsFor = "";

    /** The text cut into parts of about 4000 characters, each ending at a space (no word read twice). */
    private List<int[]> txtParts(String uri) throws Exception {
        if (uri.equals(partsFor) && txtParts != null) return txtParts;
        String all = readAll(uri);
        List<int[]> out = new ArrayList<>();
        int a = 0;
        while (a < all.length()) {
            int b = Math.min(all.length(), a + 4000);
            while (b < all.length() && b - a < 4600 && !Character.isWhitespace(all.charAt(b))) b++;
            out.add(new int[]{a, b});
            a = b;
        }
        partsFor = uri;
        txtParts = out;
        return out;
    }

    private String unitText(Prefs p, String uri, String kind, int unit) throws Exception {
        if (kind.equals("txt")) {
            int[] r = txtParts(uri).get(unit);
            return readAll(uri).substring(r[0], r[1]);
        }
        if (kind.equals("epub")) return epubChapters(uri).get(unit);
        // a PDF page: read by the AI once, kept on the phone
        File cache = new File(getFilesDir(), "reader/" + Integer.toHexString(uri.hashCode()) + "_" + unit + ".txt");
        if (cache.exists()) return new String(java.nio.file.Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8);
        if (p.apiKey().isEmpty()) throw new IllegalStateException("PDF చదవడానికి AI key కావాలి");
        String jpeg;
        try (android.os.ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(Uri.parse(uri), "r");
             android.graphics.pdf.PdfRenderer r = new android.graphics.pdf.PdfRenderer(fd);
             android.graphics.pdf.PdfRenderer.Page pg = r.openPage(unit)) {
            int w = 1200, h = Math.round(w * (pg.getHeight() / (float) pg.getWidth()));
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            bm.eraseColor(android.graphics.Color.WHITE);
            pg.render(bm, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out);
            bm.recycle();
            jpeg = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
        }
        String text;
        try {
            text = Brain.oneShot(p, "You read book pages aloud. Output only the page's running text exactly as written (Telugu or English), "
                    + "in reading order, as plain sentences. Leave out page numbers, headers, footers and figure labels. No comments. "
                    + "If the page has no text, output nothing.", "Read this page.", jpeg, false, 4000);
        } catch (Http.ApiError e) {
            if (!"empty reply".equals(e.getMessage())) throw e;
            text = ""; // a blank / picture-only page
        }
        String t = text == null ? "" : text.trim();
        cache.getParentFile().mkdirs();
        java.nio.file.Files.write(cache.toPath(), t.getBytes(StandardCharsets.UTF_8));
        return t;
    }

    private String txtFor = "", txtCache;

    private String readAll(String uri) throws Exception {
        if (uri.equals(txtFor) && txtCache != null) return txtCache;
        try (InputStream in = getContentResolver().openInputStream(Uri.parse(uri))) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int r; (r = in.read(buf)) > 0; ) { b.write(buf, 0, r); if (b.size() > 8_000_000) break; }
            txtCache = b.toString("UTF-8");
            txtFor = uri;
            return txtCache;
        }
    }

    private List<String> epubCache;
    private String epubFor = "";

    /** The chapters of an EPUB in reading order, as plain text. */
    private List<String> epubChapters(String uri) throws Exception {
        if (uri.equals(epubFor) && epubCache != null) return epubCache;
        java.util.Map<String, String> files = new java.util.HashMap<>();
        try (ZipInputStream z = new ZipInputStream(getContentResolver().openInputStream(Uri.parse(uri)))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                String low = n.toLowerCase(Locale.ROOT);
                if (!(low.endsWith(".opf") || low.endsWith(".xhtml") || low.endsWith(".html") || low.endsWith(".htm") || low.endsWith("container.xml"))) continue;
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int r; (r = z.read(buf)) > 0; ) b.write(buf, 0, r);
                files.put(n, b.toString("UTF-8"));
            }
        }
        String opfPath = "";
        for (String n : files.keySet()) if (n.toLowerCase(Locale.ROOT).endsWith(".opf")) { opfPath = n; break; }
        List<String> order = new ArrayList<>();
        if (!opfPath.isEmpty()) {
            String opf = files.get(opfPath), base = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            java.util.Map<String, String> href = new java.util.HashMap<>();
            java.util.regex.Matcher im = java.util.regex.Pattern.compile("<item\\b[^>]*>").matcher(opf);
            while (im.find()) {
                String tag = im.group();
                String id = attr(tag, "id"), h = attr(tag, "href");
                if (!id.isEmpty() && !h.isEmpty()) href.put(id, base + Uri.decode(h));
            }
            java.util.regex.Matcher sm = java.util.regex.Pattern.compile("<itemref\\b[^>]*idref=\"([^\"]+)\"").matcher(opf);
            while (sm.find()) { String h = href.get(sm.group(1)); if (h != null && files.containsKey(h)) order.add(h); }
        }
        if (order.isEmpty()) { order.addAll(files.keySet()); order.removeIf(n -> !n.toLowerCase(Locale.ROOT).matches(".*\\.(x?html?)$")); java.util.Collections.sort(order); }
        List<String> out = new ArrayList<>();
        for (String n : order) {
            String t = files.get(n).replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ")
                    .replaceAll("(?i)<br\\s*/?>|</p>|</h\\d>|</div>|</li>", "\n").replaceAll("<[^>]+>", " ")
                    .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&rsquo;", "'").replace("&lsquo;", "'")
                    .replace("&ldquo;", "\"").replace("&rdquo;", "\"").replace("&mdash;", " - ").replace("&ndash;", "-").replace("&hellip;", "...")
                    .replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n").trim();
            t = entities(t).replace("&amp;", "&");
            if (t.length() > 20) out.add(t);
        }
        epubFor = uri;
        epubCache = out;
        return out;
    }

    /** &#8217; and &#x2019; style characters. */
    private static String entities(String t) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(t);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String ch;
            try { ch = new String(Character.toChars(Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16))); } catch (Exception e) { ch = " "; }
            m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(ch));
        }
        m.appendTail(b);
        return b.toString();
    }

    private static String attr(String tag, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : "";
    }

    // ---------------------------------------------------------------- sound focus: quiet while Jarvis talks / listens, or a call

    private final AudioManager.OnAudioFocusChangeListener focus = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS) { // another app plays: stop here (bookmark kept)
            focusLost = true;
            main.post(() -> { halt(); stopSelf(); });
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            focusLost = true; // Jarvis speaking / listening: pause, on again after
            main.post(() -> { Thread w = worker; worker = null; if (w != null) w.interrupt(); try { tts.stop(); } catch (Exception ignored) {} });
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            focusLost = false;
            main.postDelayed(() -> { if (!paused && !stopFlag && worker == null) startWorkerOnly(); }, 600);
        }
    };

    private void startWorkerOnly() {
        if (worker != null || stopFlag || paused || !ttsReady) return;
        worker = new Thread(this::readLoop, "jarvis-reader");
        worker.start();
    }

    private void takeFocus() {
        focusLost = false;
        if (focusReq != null) return;
        try {
            focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setWillPauseWhenDucked(true)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(focus, main).build();
            getSystemService(AudioManager.class).requestAudioFocus(focusReq);
        } catch (Exception ignored) {}
    }

    private void dropFocus() {
        try { if (focusReq != null) getSystemService(AudioManager.class).abandonAudioFocusRequest(focusReq); } catch (Exception ignored) {}
        focusReq = null;
    }

    private void note(String title, String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("jarvis_reader", "పుస్తకం చదవడం", NotificationManager.IMPORTANCE_LOW));
        PendingIntent toggle = PendingIntent.getService(this, 192, new Intent(this, ReaderService.class).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 193, new Intent(this, ReaderService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "jarvis_reader").setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle(title).setContentText(text).setOngoing(true).setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, paused ? "▶ చదువు" : "⏸ ఆపు (గుర్తుంచుకో)", toggle).build())
                .addAction(new Notification.Action.Builder(null, "⏹ మూసేయి", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTE, n);
    }

    @Override public void onDestroy() {
        stopFlag = true;
        halt();
        reading = "";
        paused = false;
        try { if (tts != null) tts.shutdown(); } catch (Exception ignored) {}
        tts = null;
        super.onDestroy();
    }

    /** Where he stopped: {name, unit (page / chapter), of}. */
    static JSONObject bookmark(Context c) throws Exception {
        SharedPreferences m = mark(c);
        if (m.getString("uri", "").isEmpty()) return null;
        return new JSONObject().put("name", m.getString("name", "")).put("kind", m.getString("kind", ""))
                .put(m.getString("kind", "").equals("pdf") ? "page" : m.getString("kind", "").equals("epub") ? "chapter" : "part", m.getInt("unit", 0) + 1);
    }

    static JSONArray names(List<String[]> books) {
        JSONArray a = new JSONArray();
        for (String[] b : books) if (a.length() < 60) a.put(b[0]);
        return a;
    }
}
