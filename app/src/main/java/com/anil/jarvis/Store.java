package com.anil.jarvis;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Memories, missions and the conversation, kept as small JSON files in app storage. */
final class Store {
    interface Listener { void onStoreChanged(); }

    private final File dir;
    private final List<JSONObject> memories;
    private final List<JSONObject> missions;
    private final List<JSONObject> chat;
    private final List<JSONObject> reminders;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    Listener listener;

    private static Store instance;

    /** One shared store for the whole app, so every screen sees the same data. */
    static synchronized Store get(Context c) {
        if (instance == null) instance = new Store(c.getApplicationContext());
        return instance;
    }

    private Store(Context c) {
        dir = c.getFilesDir();
        memories = load("memory.json");
        missions = load("missions.json");
        chat = load("chat.json");
        reminders = load("reminders.json");
    }

    // ---------- files ----------

    private List<JSONObject> load(String name) {
        List<JSONObject> out = new ArrayList<>();
        File f = new File(dir, name);
        if (!f.exists()) return out;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            JSONArray a = new JSONObject(new String(buf, 0, off, StandardCharsets.UTF_8)).getJSONArray("items");
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception e) {
            // A damaged file starts fresh rather than crashing the app.
        }
        return out;
    }

    private void save(String name, List<JSONObject> list) {
        try {
            JSONArray a = new JSONArray();
            for (JSONObject o : list) a.put(o);
            JSONObject root = new JSONObject().put("items", a);
            File tmp = new File(dir, name + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            File dest = new File(dir, name);
            if (!tmp.renameTo(dest)) throw new IOException("rename failed");
        } catch (Exception e) {
            // Best effort; the in-memory copy stays correct for this session.
        }
    }

    private void changed() {
        main.post(() -> { if (listener != null) listener.onStoreChanged(); });
    }

    private String newId(String prefix) {
        return prefix + Long.toString(System.currentTimeMillis(), 36) + Integer.toString(rnd.nextInt(1296), 36);
    }

    private static List<JSONObject> copy(List<JSONObject> src) {
        return new ArrayList<>(src);
    }

    // ---------- memories ----------

    synchronized List<JSONObject> memories() { return copy(memories); }

    synchronized JSONObject addMemory(String text) {
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) return null;
        if (text.length() > 400) text = text.substring(0, 400);
        try {
            JSONObject o = new JSONObject().put("id", newId("m")).put("text", text).put("t", System.currentTimeMillis());
            memories.add(o);
            while (memories.size() > 300) memories.remove(0);
            save("memory.json", memories);
            changed();
            return o;
        } catch (Exception e) { return null; }
    }

    synchronized JSONObject removeMemory(String id) {
        for (int i = 0; i < memories.size(); i++) {
            if (memories.get(i).optString("id").equals(id)) {
                JSONObject gone = memories.remove(i);
                save("memory.json", memories);
                changed();
                return gone;
            }
        }
        return null;
    }

    synchronized void restoreMemory(JSONObject o) {
        memories.add(o);
        save("memory.json", memories);
        changed();
    }

    // ---------- missions ----------

    synchronized List<JSONObject> missions() { return copy(missions); }

    synchronized JSONObject addMission(String text) {
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) return null;
        if (text.length() > 300) text = text.substring(0, 300);
        try {
            JSONObject o = new JSONObject().put("id", newId("x")).put("text", text).put("done", false).put("t", System.currentTimeMillis());
            missions.add(o);
            while (missions.size() > 200) {
                int k = -1;
                for (int i = 0; i < missions.size(); i++) if (missions.get(i).optBoolean("done")) { k = i; break; }
                missions.remove(k >= 0 ? k : 0);
            }
            save("missions.json", missions);
            changed();
            return o;
        } catch (Exception e) { return null; }
    }

    synchronized JSONObject setMissionDone(String id, boolean done) {
        for (JSONObject m : missions) {
            if (m.optString("id").equals(id)) {
                try {
                    m.put("done", done);
                    if (done) m.put("doneAt", System.currentTimeMillis()); else m.remove("doneAt");
                } catch (Exception ignored) {}
                save("missions.json", missions);
                changed();
                return m;
            }
        }
        return null;
    }

    synchronized JSONObject removeMission(String id) {
        for (int i = 0; i < missions.size(); i++) {
            if (missions.get(i).optString("id").equals(id)) {
                JSONObject gone = missions.remove(i);
                save("missions.json", missions);
                changed();
                return gone;
            }
        }
        return null;
    }

    synchronized void restoreMission(JSONObject o) {
        missions.add(o);
        save("missions.json", missions);
        changed();
    }

    // ---------- reminders ----------

    synchronized List<JSONObject> reminders() { return copy(reminders); }

    synchronized JSONObject addReminder(String text, long at) {
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) return null;
        if (text.length() > 300) text = text.substring(0, 300);
        try {
            JSONObject o = new JSONObject().put("id", newId("r")).put("text", text).put("at", at).put("done", false);
            reminders.add(o);
            // keep the list small: drop old finished reminders first
            while (reminders.size() > 150) {
                int k = -1;
                for (int i = 0; i < reminders.size(); i++) if (reminders.get(i).optBoolean("done")) { k = i; break; }
                reminders.remove(k >= 0 ? k : 0);
            }
            save("reminders.json", reminders);
            changed();
            return o;
        } catch (Exception e) { return null; }
    }

    synchronized JSONObject removeReminder(String id) {
        for (int i = 0; i < reminders.size(); i++) {
            if (reminders.get(i).optString("id").equals(id)) {
                JSONObject gone = reminders.remove(i);
                save("reminders.json", reminders);
                changed();
                return gone;
            }
        }
        return null;
    }

    synchronized JSONObject markReminderDone(String id) {
        for (JSONObject r : reminders) {
            if (r.optString("id").equals(id)) {
                try { r.put("done", true); } catch (Exception ignored) {}
                save("reminders.json", reminders);
                changed();
                return r;
            }
        }
        return null;
    }

    /** Changes one field of a reminder (used to move a repeating reminder to its next time). */
    synchronized JSONObject updateReminder(String id, String key, Object value) {
        for (JSONObject r : reminders) {
            if (r.optString("id").equals(id)) {
                try { r.put(key, value); } catch (Exception ignored) {}
                save("reminders.json", reminders);
                changed();
                return r;
            }
        }
        return null;
    }

    // ---------- conversation ----------

    synchronized List<JSONObject> chat() { return copy(chat); }

    synchronized void addChat(String role, String content, boolean photo) {
        try {
            JSONObject o = new JSONObject().put("role", role).put("content", content).put("t", System.currentTimeMillis());
            if (photo) o.put("photo", true);
            chat.add(o);
            while (chat.size() > 60) chat.remove(0);
            save("chat.json", chat);
            archive(o.toString());
        } catch (Exception ignored) {}
    }

    // ---------- long conversation archive ----------

    private static final String ARCHIVE = "chat_archive.jsonl";

    /**
     * All archive writes (append, trim, erase) run one after another on this background thread, so the
     * caller (often the main thread) never waits on file work and never holds the Store lock during it.
     */
    private final java.util.concurrent.ThreadPoolExecutor archiveIo = newArchiveThread();

    private static java.util.concurrent.ThreadPoolExecutor newArchiveThread() {
        java.util.concurrent.ThreadPoolExecutor x = new java.util.concurrent.ThreadPoolExecutor(1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), r -> new Thread(r, "jarvis-archive"));
        x.allowCoreThreadTimeOut(true);
        return x;
    }

    /** Every conversation line is also kept in a long archive, for "what did I tell you last week?". */
    private void archive(String line) {
        try {
            archiveIo.execute(() -> {
                try {
                    File f = new File(dir, ARCHIVE);
                    if (f.length() > 3_000_000L) trimArchive(f);
                    try (FileOutputStream out = new FileOutputStream(f, true)) {
                        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    /** Keeps the newer half, written to a temporary file and swapped in, so a crash can't leave it half-written. */
    private void trimArchive(File f) throws IOException {
        List<String> lines = readLines(f);
        File tmp = new File(dir, ARCHIVE + ".tmp");
        try (java.io.Writer w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            for (int i = lines.size() / 2; i < lines.size(); i++) w.write(lines.get(i) + "\n");
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("rename failed");
        }
    }

    /** Lines of a file as UTF-8; damaged bytes become replacement characters instead of failing the whole read. */
    private static List<String> readLines(File f) throws IOException {
        List<String> lines = new ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) lines.add(l);
        }
        return lines;
    }

    /**
     * Old conversation lines containing all the words, newest first. Not synchronized: it only reads the
     * archive file (swapped in whole when trimmed), so saving chat is never blocked by a long search.
     */
    java.util.List<JSONObject> searchArchive(String query, long since, int max) {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        String[] words = query == null ? new String[0] : query.toLowerCase(java.util.Locale.ROOT).trim().split("\\s+");
        try {
            File f = new File(dir, ARCHIVE);
            if (!f.exists()) return out;
            List<String> lines = readLines(f);
            for (int i = lines.size() - 1; i >= 0 && out.size() < max; i--) {
                JSONObject o;
                try { o = new JSONObject(lines.get(i)); } catch (Exception bad) { continue; } // a damaged or half-written line
                if (o.optLong("t") < since) break;
                String text = o.optString("content").toLowerCase(java.util.Locale.ROOT);
                boolean all = true;
                for (String w : words) if (!w.isEmpty() && !text.contains(w)) { all = false; break; }
                if (all) out.add(o);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** "Erase conversation": the recent chat and the long archive both go. */
    synchronized void clearChat() {
        chat.clear();
        save("chat.json", chat);
        // after any lines still waiting to be written, so none of them brings the archive back
        try {
            archiveIo.execute(() -> {
                new File(dir, ARCHIVE).delete();
                new File(dir, ARCHIVE + ".tmp").delete();
            });
        } catch (Exception e) {
            new File(dir, ARCHIVE).delete();
        }
    }
}
