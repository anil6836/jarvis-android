package com.anil.jarvis;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * WhatsApp voice notes, videos and photos: finds the newest one that arrived, and plays,
 * transcribes or shows it. Uses the WhatsApp media folder Anil allowed once (or the phone's
 * media index for videos and photos).
 */
final class WaMedia {
    private WaMedia() {}

    static final class Found {
        final Uri uri;
        final String name;
        final long modified;
        Found(Uri uri, String name, long modified) { this.uri = uri; this.name = name; this.modified = modified; }
    }

    private static volatile MediaPlayer player;

    /** Initial place for the folder picker: Android/media/com.whatsapp/WhatsApp/Media. */
    static Uri pickerStart() {
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
                "primary:Android/media/com.whatsapp/WhatsApp/Media");
    }

    static String tree(Context c) {
        return c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getString("wa_tree", "");
    }

    private static String folder(String kind) {
        switch (kind) {
            case "voice": return "whatsapp voice notes";
            case "audio": return "whatsapp audio";
            case "video": return "whatsapp video";
            default: return "whatsapp images";
        }
    }

    private static boolean fileFits(String kind, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        switch (kind) {
            case "voice": return n.endsWith(".opus") || n.endsWith(".ogg") || n.endsWith(".m4a") || n.endsWith(".aac");
            case "audio": return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".ogg") || n.endsWith(".opus") || n.endsWith(".wav");
            case "video": return n.endsWith(".mp4") || n.endsWith(".3gp") || n.endsWith(".mkv") || n.endsWith(".webm");
            default: return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png");
        }
    }

    /** The newest received file of this kind (voice/audio/video/photo), newest first. */
    static List<Found> newest(Context c, String kind, int max) {
        List<Found> out = new ArrayList<>();
        String t = tree(c);
        if (!t.isEmpty()) {
            try {
                Uri tree = Uri.parse(t);
                walk(c, tree, DocumentsContract.getTreeDocumentId(tree), kind, false, out, 0);
            } catch (Exception ignored) {}
        }
        if (out.isEmpty() && !kind.equals("voice")) mediaStore(c, kind, out);
        out.sort((a, b) -> Long.compare(b.modified, a.modified));
        return out.size() > max ? out.subList(0, max) : out;
    }

    /** Files enough to be sure the newest ones are among them, once week folders are walked newest first. */
    private static final int ENOUGH = 100;

    private static void walk(Context c, Uri tree, String docId, String kind, boolean inside, List<Found> out, int depth) {
        if (depth > 5) return;
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        List<String[]> dirs = new ArrayList<>(); // {document id, lower-case name, "1" if inside the kind folder}
        try (Cursor cur = c.getContentResolver().query(kids, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                String id = cur.getString(0), name = cur.getString(1), mime = cur.getString(2);
                long mod = cur.getLong(3);
                String low = name == null ? "" : name.toLowerCase(Locale.ROOT);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (low.equals("sent") || low.equals("private") || low.startsWith(".")) continue; // his own sent media
                    boolean match = inside || low.equals(folder(kind)) || low.equals(folder(kind).replace("whatsapp", "whatsapp business"));
                    // stay on the path towards the right folder: Media, WhatsApp, the kind folder, week folders
                    if (match || low.equals("media") || low.equals("whatsapp") || low.equals("whatsapp business") || low.equals("com.whatsapp") || low.equals("com.whatsapp.w4b")) {
                        dirs.add(new String[]{id, low, match ? "1" : ""});
                    }
                } else if (inside && name != null && fileFits(kind, name)) {
                    out.add(new Found(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, mod));
                }
            }
        } catch (Exception ignored) {}
        if (inside) {
            // week folders ("202638" = year + week): newest first, and stop once enough files are found,
            // so the current week is never skipped in favour of old ones
            dirs.sort((a, b) -> b[1].compareTo(a[1]));
        }
        int start = out.size();
        for (String[] d : dirs) {
            if (inside && out.size() - start >= ENOUGH) break;
            walk(c, tree, d[0], kind, !d[2].isEmpty(), out, depth + 1);
        }
    }

    private static void mediaStore(Context c, String kind, List<Found> out) {
        boolean video = kind.equals("video");
        String perm = Build.VERSION.SDK_INT >= 33
                ? (video ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_MEDIA_IMAGES)
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (c.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) return;
        Uri base = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String bucket = video ? "WhatsApp Video" : "WhatsApp Images";
        try (Cursor cur = c.getContentResolver().query(base, new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.DATE_MODIFIED},
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME + " = ?", new String[]{bucket}, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            while (cur != null && cur.moveToNext() && out.size() < 20) {
                out.add(new Found(ContentUris.withAppendedId(base, cur.getLong(0)), cur.getString(1), cur.getLong(2) * 1000));
            }
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- playing

    /** Plays a voice note / audio out loud and waits until it ends (max 5 minutes). */
    static boolean play(Context c, Uri u) {
        stop();
        AudioManager am = c.getSystemService(AudioManager.class);
        AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build();
        try {
            if (am != null) am.requestAudioFocus(focus); // pause music meanwhile
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioAttributes(attrs);
            mp.setDataSource(c, u);
            mp.prepare();
            mp.start();
            long end = SystemClock.elapsedRealtime() + Math.min(300000, Math.max(3000, mp.getDuration() + 1500));
            SystemClock.sleep(300);
            while (player == mp && SystemClock.elapsedRealtime() < end) {
                try { if (!mp.isPlaying()) break; } catch (IllegalStateException e) { break; }
                SystemClock.sleep(200);
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            stop();
            if (am != null) am.abandonAudioFocusRequest(focus);
        }
    }

    static void stop() {
        MediaPlayer mp = player;
        player = null;
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
        }
    }

    // ---------------------------------------------------------------- transcribing

    /** The words of a voice note (OpenAI speech-to-text). */
    static String transcribe(Context c, String key, Uri u) throws Exception {
        byte[] audio;
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while (in != null && (n = in.read(buf)) > 0) b.write(buf, 0, n);
            audio = b.toByteArray();
        }
        if (audio.length == 0) throw new IllegalStateException("empty file");
        if (audio.length > 24_000_000) throw new IllegalStateException("too long");
        String boundary = "----jarvis" + System.nanoTime();
        HttpURLConnection con = (HttpURLConnection) new URL("https://api.openai.com/v1/audio/transcriptions").openConnection();
        try {
            con.setRequestMethod("POST");
            con.setConnectTimeout(20000);
            con.setReadTimeout(120000);
            con.setDoOutput(true);
            con.setRequestProperty("Authorization", "Bearer " + key);
            con.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream out = con.getOutputStream()) {
                String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\ngpt-4o-mini-transcribe\r\n"
                        + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voice.ogg\"\r\nContent-Type: audio/ogg\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.UTF_8));
                out.write(audio);
                out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            int code = con.getResponseCode();
            InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (in != null && (n = in.read(buf)) > 0) b.write(buf, 0, n);
            String body = b.toString("UTF-8");
            if (code >= 400) throw new IllegalStateException("transcription " + code + ": " + body.substring(0, Math.min(160, body.length())));
            return new JSONObject(body).optString("text", "").trim();
        } finally {
            con.disconnect();
        }
    }
}
