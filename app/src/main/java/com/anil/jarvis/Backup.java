package com.anil.jarvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * All of Jarvis's data in one password-locked file in Anil's own Google Drive, so nothing is lost when he changes or
 * loses his phone: every setting (API keys and model choices too), memories, chats, reminders, missions, notes, diary,
 * money, bike rides, duty, health, his voice print, the faces he introduced, saved pages, recordings, the sites and
 * apps Jarvis made. Only the big models anyone can download again (the "Jarvis" word models, the face model) are
 * left out; Jarvis fetches them again by itself.
 *
 * The file is a ZIP, encrypted in 1 MB pieces with AES-256-GCM under a key made from his password (PBKDF2); each piece
 * is checked, and the last one is marked, so a wrong password, a changed byte or a cut-off file is always noticed. The
 * key (never the password) stays on the phone, locked by the phone's own keystore, for the nightly backups. A backup
 * is checked in full on the phone before it replaces the one in Drive, and a Drive file another phone has taken over
 * (after a restore there) is never written over.
 *
 * A restore is opened and checked in full first, then put in place at the very start of Jarvis's next launch (before
 * anything reads the old data), with the old data kept aside until the new is completely in place.
 */
final class Backup {
    private Backup() {}

    interface Progress { void update(String text); }

    /** The password does not open this file (or its very first piece was damaged: the two look the same). */
    static final class WrongPassword extends IOException { WrongPassword() { super("wrong password"); } }

    /** Not a Jarvis backup, or damaged / cut off. */
    static final class BadFile extends IOException { BadFile(String why) { super(why); } }

    /** The Drive file now belongs to another phone (or another setup): this phone stops writing to it. */
    static final class Foreign extends IOException { Foreign(String why) { super(why); } }

    private static final byte[] MAGIC = "JRVSBAK1".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1, HEADER = 8 + 1 + 4 + 16 + 8, CHUNK = 1 << 20, ITERATIONS = 600_000;
    private static final String KEY_ALIAS = "jarvis_backup_key";
    /** The backup's own settings on this phone: the locked key, the Drive file, the last result. Never in a backup. */
    static final String PREFS = "jarvis_backup";
    private static final String STAGE = ".jarvis_restore", OLD = ".jarvis_old";
    /** One backup, open or restore at a time. */
    private static final AtomicBoolean busy = new AtomicBoolean(false);

    static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static boolean busy() { return busy.get(); }

    /** A password and a Drive file are set: the nightly backup runs. */
    static boolean configured(Context c) {
        SharedPreferences s = sp(c);
        return !s.getString("key", "").isEmpty() && !s.getString("uri", "").isEmpty();
    }

    static long lastOk(Context c) { return sp(c).getLong("last_ok", 0); }

    /** This install of Jarvis (a new phone, or Jarvis installed again, gets a new one). */
    static synchronized String installId(Context c) {
        SharedPreferences s = sp(c);
        String id = s.getString("install_id", "");
        if (id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString();
            s.edit().putString("install_id", id).commit();
        }
        return id;
    }

    /** One line for Settings: when the last backup was, how big, or what went wrong. */
    static String status(Context c) {
        SharedPreferences s = sp(c);
        String err = s.getString("last_error", "");
        if (!configured(c)) return "✗ బ్యాకప్ సెట్ అయి లేదు: ఫోన్ పోతే Jarvis డేటా పోతుంది." + (err.isEmpty() ? "" : "\n" + err);
        long ok = s.getLong("last_ok", 0);
        StringBuilder b = new StringBuilder();
        if (ok == 0) b.append("బ్యాకప్ సెట్ అయింది, మొదటి బ్యాకప్ ఇంకా పూర్తి కాలేదు.");
        else b.append("✓ చివరి బ్యాకప్: ").append(when(ok)).append(" · ").append(size(s.getLong("last_size", 0)));
        String where = s.getString("where", "");
        if (!where.isEmpty()) b.append("\nఎక్కడ: ").append(where);
        if (!err.isEmpty() && s.getLong("last_try", 0) > ok) b.append("\n✗ చివరి ప్రయత్నం కాలేదు: ").append(err);
        return b.toString();
    }

    static String when(long t) {
        long day = 86_400_000L;
        String time = new java.text.SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new java.util.Date(t));
        if (t >= Life.dayStart()) return "ఈరోజు " + time;
        if (t >= Life.dayStart() - day) return "నిన్న " + time;
        return new java.text.SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(new java.util.Date(t));
    }

    static String size(long b) {
        if (b < 1024 * 1024) return Math.max(1, b / 1024) + " KB";
        return String.format(Locale.ENGLISH, "%.1f MB", b / (1024.0 * 1024.0));
    }

    /** Google Drive's own files (the picker's Drive), not a folder on this phone. */
    static boolean inDrive(Uri u) {
        String a = u == null ? null : u.getAuthority();
        return a != null && a.startsWith("com.google.android.apps.docs");
    }

    // ================================================================ key

    static byte[] salt() {
        byte[] s = new byte[16];
        new SecureRandom().nextBytes(s);
        return s;
    }

    static int iterations() { return ITERATIONS; }

    /** The file key from his password (slow on purpose, so guessing passwords is slow too). */
    static byte[] derive(char[] password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static SecretKey phoneKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    /**
     * Keeps the file key on this phone, locked by the phone's keystore, with the Drive file to write to. takeOver: the
     * install whose backup this phone was restored from (it may write over that one), or "" for a new file.
     */
    static void remember(Context c, byte[] key, byte[] salt, int iterations, Uri uri, String where, String takeOver) throws Exception {
        save(c, settings(key, salt, iterations, uri, where, takeOver));
    }

    /** The backup settings for this phone (the key locked by the phone's keystore), not saved yet. */
    private static JSONObject settings(byte[] key, byte[] salt, int iterations, Uri uri, String where, String takeOver) throws Exception {
        Cipher wrap = Cipher.getInstance("AES/GCM/NoPadding");
        wrap.init(Cipher.ENCRYPT_MODE, phoneKey());
        byte[] locked = wrap.doFinal(key);
        return new JSONObject()
                .put("key", Base64.encodeToString(locked, Base64.NO_WRAP))
                .put("key_iv", Base64.encodeToString(wrap.getIV(), Base64.NO_WRAP))
                .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .put("iterations", iterations)
                .put("uri", uri.toString())
                .put("where", where)
                .put("take_over", takeOver == null ? "" : takeOver);
    }

    private static void save(Context c, JSONObject j) {
        sp(c).edit()
                .putString("key", j.optString("key"))
                .putString("key_iv", j.optString("key_iv"))
                .putString("salt", j.optString("salt"))
                .putInt("iterations", j.optInt("iterations", ITERATIONS))
                .putString("uri", j.optString("uri"))
                .putString("where", j.optString("where"))
                .putString("take_over", j.optString("take_over"))
                .putLong("last_ok", 0)
                .putLong("last_size", 0)
                .remove("last_error")
                .commit();
    }

    private static byte[] storedKey(SharedPreferences s) throws Exception {
        Cipher unwrap = Cipher.getInstance("AES/GCM/NoPadding");
        unwrap.init(Cipher.DECRYPT_MODE, phoneKey(), new GCMParameterSpec(128, Base64.decode(s.getString("key_iv", ""), Base64.NO_WRAP)));
        return unwrap.doFinal(Base64.decode(s.getString("key", ""), Base64.NO_WRAP));
    }

    /**
     * This phone stops backing up to that file (another phone took it over, or the permission is gone): only if it is
     * still the file this run used (he may have set the backup up again meanwhile).
     */
    private static synchronized void forget(Context c, String uri, String why) {
        SharedPreferences s = sp(c);
        if (!uri.equals(s.getString("uri", ""))) return;
        s.edit().remove("key").remove("key_iv").remove("uri").remove("take_over").putString("last_error", why).commit();
        BackupJob.cancel(c);
    }

    // ================================================================ what goes in

    /** His own settings files (not the ones libraries keep). */
    private static boolean ownPrefs(String name) {
        if (name.equals(PREFS) || name.equals(AppCrash.PREFS)) return false; // (an app-error note belongs to this phone)
        return name.startsWith("jarvis") || name.startsWith("hud_") || name.endsWith("Activity");
    }

    /** Big things anyone can download again, half-written temporary files and the restore's own folders: left out. */
    private static boolean skipFile(String name, boolean top) {
        if (name.endsWith(".tmp") || name.endsWith(".part")) return true;
        if (!top) return false;
        return name.equals("vosk-en") || name.equals("vosk-spk") || name.equals("facenet.tflite") || name.equals(STAGE) || name.equals(OLD)
                || name.equals("bible") // (the home tablet's copy of the Bible, fetched again by itself)
                || (name.startsWith("greet_") && name.endsWith(".pcm"));
    }

    private static File prefsDir(Context c) { return new File(c.getApplicationInfo().dataDir, "shared_prefs"); }

    private static List<File> list(File dir) {
        List<File> out = new ArrayList<>();
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs != null) out.addAll(Arrays.asList(fs));
        return out;
    }

    /** "x" for x.xml and x.xml.bak, else null. */
    private static String prefsBase(String n) {
        return n.endsWith(".xml.bak") ? n.substring(0, n.length() - 8) : n.endsWith(".xml") ? n.substring(0, n.length() - 4) : null;
    }

    /** A settings file as Android last finished writing it (its .bak while a write is under way), checked. */
    private static byte[] prefsBytes(File dir, String fileName) throws IOException {
        for (int attempt = 0; attempt < 6; attempt++) {
            File bak = new File(dir, fileName + ".bak");
            File f = bak.exists() ? bak : new File(dir, fileName);
            try {
                byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                if (validPrefs(b)) return b;
            } catch (IOException ignored) {}
            android.os.SystemClock.sleep(200);
        }
        throw new IOException("సెట్టింగ్స్ ఫైల్ " + fileName + " చదవలేకపోయాను");
    }

    /** A whole, well-formed settings file (<map> ... </map>). */
    private static boolean validPrefs(byte[] b) {
        if (b == null || b.length == 0) return false;
        try {
            XmlPullParser p = android.util.Xml.newPullParser();
            p.setInput(new ByteArrayInputStream(b), "UTF-8");
            int t;
            boolean map = false;
            while ((t = p.next()) != XmlPullParser.END_DOCUMENT) if (t == XmlPullParser.START_TAG && p.getDepth() == 1) map = "map".equals(p.getName());
            return map;
        } catch (Exception e) {
            return false;
        }
    }

    // ================================================================ backup

    /** Makes a backup, checks it, and writes it to his Drive file. Worker thread; false when another one is running. */
    static boolean run(Context ctx, Progress progress) throws Exception {
        Context c = ctx.getApplicationContext();
        if (!configured(c)) throw new IllegalStateException("బ్యాకప్ సెట్ అయి లేదు");
        if (!busy.compareAndSet(false, true)) return false;
        SharedPreferences s = sp(c);
        s.edit().putLong("last_try", System.currentTimeMillis()).apply();
        File tmp = new File(c.getCacheDir(), "jarvis-backup.tmp");
        byte[] key = null;
        String uriText = "";
        try {
            // all of it read together, so a password or file changed meanwhile can't mix
            key = storedKey(s);
            byte[] salt = Base64.decode(s.getString("salt", ""), Base64.NO_WRAP);
            int iterations = s.getInt("iterations", ITERATIONS);
            uriText = s.getString("uri", "");
            Uri uri = Uri.parse(uriText);
            String me = installId(c), takeOver = s.getString("take_over", "");
            say(progress, "బ్యాకప్ తయారుచేస్తున్నాను…");
            try (OutputStream file = new FileOutputStream(tmp)) {
                write(c, file, key, salt, iterations, me, progress);
            }
            say(progress, "బ్యాకప్ సరిగ్గా ఉందో చూస్తున్నాను…");
            verify(tmp, key); // never send a broken one over the good one in Drive
            say(progress, "Drive లో ఉన్న ఫైల్ చూస్తున్నాను…");
            String owner = owner(c, uri, key);
            if (owner != null && !owner.equals(me) && !owner.equals(takeOver))
                throw new Foreign("ఈ Drive బ్యాకప్ ఫైల్‌ని ఇప్పుడు వేరే ఫోన్ వాడుతోంది: ఈ ఫోన్ దానిపై రాయడం ఆపేసింది. ఇక్కడ కూడా కావాలంటే బ్యాకప్ కొత్తగా సెట్ చేయండి.");
            say(progress, "Google Drive కి పంపుతున్నాను… (" + size(tmp.length()) + ")");
            OutputStream out;
            try {
                out = c.getContentResolver().openOutputStream(uri, "wt");
            } catch (SecurityException e) {
                throw e;
            } catch (Exception e) {
                out = c.getContentResolver().openOutputStream(uri, "w"); // some places only know "w" (extra bytes after the end are ignored)
            }
            if (out == null) throw new IOException("Drive ఫైల్ తెరవలేకపోయాను");
            try (InputStream in = new FileInputStream(tmp); OutputStream o = out) {
                byte[] b = new byte[64 * 1024];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
            }
            s.edit().putLong("last_ok", System.currentTimeMillis()).putLong("last_size", tmp.length())
                    .putString("take_over", "").remove("last_error").commit();
            say(progress, "✓ బ్యాకప్ పూర్తయింది (" + size(tmp.length()) + ")");
            return true;
        } catch (Foreign e) {
            forget(c, uriText, e.getMessage());
            throw e;
        } catch (SecurityException e) { // the permission for the Drive file is gone (it does not come back by itself)
            String why = "Drive ఫైల్‌కి Jarvis అనుమతి పోయింది: సెట్టింగ్స్‌లో బ్యాకప్ మళ్లీ సెట్ చేయండి.";
            forget(c, uriText, why);
            throw new Foreign(why);
        } catch (FileNotFoundException e) { // deleted in Drive, or Drive offline: kept, tried again tomorrow, he is told
            s.edit().putString("last_error", "Drive లో బ్యాకప్ ఫైల్ తెరవలేకపోయాను (తీసేశారా? నెట్ ఉందా?): " + e.getMessage()).apply();
            throw e;
        } catch (Exception e) {
            s.edit().putString("last_error", String.valueOf(e.getMessage())).apply();
            throw e;
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            busy.set(false);
        }
    }

    /** run(), waiting up to a few minutes for a backup that is already running to finish first. */
    static void runWhenFree(Context c, Progress progress) throws Exception {
        for (int i = 0; i < 180; i++) {
            if (run(c, progress)) return;
            if (i == 0) say(progress, "ఇంకో బ్యాకప్ జరుగుతోంది, అది అయ్యాక…");
            Thread.sleep(1000);
        }
        throw new IOException("ఇంకో బ్యాకప్ ఇంకా జరుగుతోంది: కాసేపాగి మళ్లీ ప్రయత్నించండి");
    }

    private static void say(Progress p, String t) { if (p != null) p.update(t); }

    /** Writes the whole backup (encrypted) to out. */
    static void write(Context c, OutputStream out, byte[] key, byte[] salt, int iterations, String install, Progress progress) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new EncOut(out, key, salt, iterations))) {
            JSONObject meta = new JSONObject()
                    .put("v", VERSION)
                    .put("made", System.currentTimeMillis())
                    .put("build", Updater.currentBuild(c))
                    .put("install", install)
                    .put("phone", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            zip.putNextEntry(new ZipEntry("meta.json")); // first: its owner can be read from the first piece alone
            zip.write(meta.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            File pd = prefsDir(c);
            java.util.TreeSet<String> names = new java.util.TreeSet<>(); // x.xml, or only x.xml.bak while Android rewrites x
            for (File f : list(pd)) {
                String base = prefsBase(f.getName());
                if (base != null && ownPrefs(base)) names.add(base);
            }
            for (String base : names) {
                byte[] b = prefsBytes(pd, base + ".xml");
                zip.putNextEntry(new ZipEntry("prefs/" + base + ".xml"));
                zip.write(b);
                zip.closeEntry();
            }
            for (File f : list(c.getFilesDir())) if (!skipFile(f.getName(), true)) addTree(zip, f, "files/" + f.getName(), progress);
            File ext = c.getExternalFilesDir(null);
            for (File f : list(ext)) if (!skipFile(f.getName(), true)) addTree(zip, f, "external/" + f.getName(), progress);
        }
    }

    private static void addTree(ZipOutputStream zip, File f, String path, Progress progress) throws IOException {
        if (f.isDirectory()) {
            for (File k : list(f)) if (!skipFile(k.getName(), false)) addTree(zip, k, path + "/" + k.getName(), progress);
            return;
        }
        if (!f.isFile()) return;
        InputStream in;
        try {
            in = new FileInputStream(f);
        } catch (FileNotFoundException e) {
            return; // gone meanwhile (a temporary file): nothing to keep
        }
        if (f.length() > 4L * 1024 * 1024) say(progress, "బ్యాకప్: " + f.getName() + " (" + size(f.length()) + ")");
        try (InputStream i = in) {
            ZipEntry e = new ZipEntry(path);
            e.setTime(f.lastModified());
            zip.putNextEntry(e);
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = i.read(b)) > 0) zip.write(b, 0, n); // any error: the whole backup fails (and Drive keeps the last good one)
            zip.closeEntry();
        }
    }

    /** Opens the finished backup again and reads every byte (pieces, ZIP checksums, settings files) before it goes to Drive. */
    private static void verify(File f, byte[] key) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 256 * 1024))) {
            byte[] header = new byte[HEADER];
            in.readFully(header);
            DecIn dec = new DecIn(in, key, header);
            boolean meta = false;
            try (ZipInputStream zip = new ZipInputStream(dec)) {
                ZipEntry e;
                byte[] b = new byte[64 * 1024];
                while ((e = zip.getNextEntry()) != null) {
                    if (e.getName().equals("meta.json")) meta = true;
                    if (e.getName().startsWith("prefs/")) {
                        ByteArrayOutputStream x = new ByteArrayOutputStream();
                        int n;
                        while ((n = zip.read(b)) > 0) x.write(b, 0, n);
                        if (!validPrefs(x.toByteArray())) throw new BadFile("బ్యాకప్‌లో సెట్టింగ్స్ ఫైల్ పాడైంది: " + e.getName());
                    } else {
                        while (zip.read(b) > 0) { /* the ZIP checks each file's checksum at its end */ }
                    }
                }
            }
            if (!dec.finished() || !meta) throw new BadFile("బ్యాకప్ పూర్తిగా తయారవలేదు");
        }
    }

    /** Which install wrote the Drive file now (from its first piece); null for a new, empty or damaged file (ours to write). */
    private static String owner(Context c, Uri uri, byte[] key) throws IOException {
        InputStream raw = c.getContentResolver().openInputStream(uri);
        if (raw == null) throw new IOException("Drive ఫైల్ చదవలేకపోయాను");
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 64 * 1024))) {
            byte[] header = new byte[HEADER];
            try { in.readFully(header); } catch (EOFException e) { return null; } // new file
            for (int i = 0; i < MAGIC.length; i++)
                if (header[i] != MAGIC[i]) throw new Foreign("ఎంచుకున్న Drive ఫైల్ Jarvis బ్యాకప్ కాదు, దానిపై రాయను: బ్యాకప్ మళ్లీ సెట్ చేయండి.");
            try (ZipInputStream zip = new ZipInputStream(new DecIn(in, key, header))) {
                ZipEntry e = zip.getNextEntry();
                if (e == null || !"meta.json".equals(e.getName())) return null;
                ByteArrayOutputStream x = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = zip.read(b)) > 0) x.write(b, 0, n);
                return new JSONObject(x.toString("UTF-8")).optString("install", "");
            } catch (WrongPassword e) {
                throw new Foreign("Drive లోని బ్యాకప్ ఫైల్ ఇప్పుడు వేరే పాస్‌వర్డ్‌తో ఉంది (వేరే ఫోన్‌లో కొత్తగా సెట్ చేశారు): ఈ ఫోన్ దానిపై రాయడం ఆపేసింది.");
            } catch (BadFile | org.json.JSONException | java.util.zip.ZipException e) {
                return null; // a cut-off or damaged copy: writing a good one over it is right
            }
        }
    }

    // ================================================================ restore

    /** What a backup file holds, after it was opened with his password and fully checked (and unpacked aside). */
    static final class Opened {
        JSONObject meta;
        byte[] key, salt;
        int iterations;
        long bytes;
    }

    private static File stageIn(Context c) { return new File(c.getFilesDir(), STAGE); }

    private static File stageExt(Context c) {
        File e = c.getExternalFilesDir(null);
        return e == null ? null : new File(e, STAGE);
    }

    /**
     * Reads the file's header (its salt), makes the key from his password, then unpacks it all aside (internal data next
     * to the app's files, external data next to the external files, so putting them in place is only renaming) and
     * checks everything. Holds the backup lock until discard() or a restart.
     */
    static Opened open(Context ctx, Uri uri, char[] password, Progress progress) throws Exception {
        Context c = ctx.getApplicationContext();
        if (!busy.compareAndSet(false, true)) throw new IOException("ఇంకో బ్యాకప్ పని జరుగుతోంది: ఒక్క నిమిషం ఆగి మళ్లీ ప్రయత్నించండి");
        File in0 = stageIn(c), ex0 = stageExt(c);
        Opened o = new Opened();
        try {
            if (ex0 == null) throw new IOException("ఫోన్ స్టోరేజ్ దొరకలేదు");
            deleteTree(in0);
            deleteTree(ex0);
            if (!in0.mkdirs() || !ex0.mkdirs()) throw new IOException("ఫోన్‌లో చోటు లేదు");
            InputStream raw = c.getContentResolver().openInputStream(uri);
            if (raw == null) throw new BadFile("ఫైల్ తెరవలేకపోయాను");
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 256 * 1024))) {
                byte[] header = new byte[HEADER];
                try { in.readFully(header); } catch (EOFException e) { throw new BadFile("ఇది Jarvis బ్యాకప్ ఫైల్ కాదు"); }
                for (int i = 0; i < MAGIC.length; i++) if (header[i] != MAGIC[i]) throw new BadFile("ఇది Jarvis బ్యాకప్ ఫైల్ కాదు");
                ByteBuffer h = ByteBuffer.wrap(header);
                h.position(MAGIC.length);
                if (h.get() != VERSION) throw new BadFile("ఈ బ్యాకప్ కొత్త Jarvis వెర్షన్‌ది: ముందు Jarvis అప్డేట్ చేయండి");
                o.iterations = h.getInt();
                if (o.iterations < 10_000 || o.iterations > 5_000_000) throw new BadFile("బ్యాకప్ ఫైల్ పాడైంది");
                o.salt = new byte[16];
                h.get(o.salt);
                say(progress, "పాస్‌వర్డ్ చెక్ చేస్తున్నాను…");
                o.key = derive(password, o.salt, o.iterations);
                DecIn dec = new DecIn(in, o.key, header);
                try (ZipInputStream zip = new ZipInputStream(dec)) {
                    ZipEntry e;
                    byte[] b = new byte[64 * 1024];
                    String inRoot = in0.getCanonicalPath() + File.separator, exRoot = ex0.getCanonicalPath() + File.separator;
                    long shown = 0;
                    while ((e = zip.getNextEntry()) != null) {
                        String name = e.getName();
                        if (e.isDirectory()) continue;
                        File dst;
                        String root;
                        if (name.startsWith("prefs/")) { // only his own settings files, never this phone's backup settings
                            String n = name.substring("prefs/".length());
                            if (n.contains("/") || !n.endsWith(".xml") || !ownPrefs(n.substring(0, n.length() - 4))) continue;
                        }
                        if (name.equals("meta.json") || name.startsWith("prefs/") || name.startsWith("files/")) { dst = new File(in0, name); root = inRoot; }
                        else if (name.startsWith("external/")) { dst = new File(ex0, name.substring("external/".length())); root = exRoot; }
                        else continue;
                        if (!dst.getCanonicalPath().startsWith(root)) throw new BadFile("బ్యాకప్ ఫైల్ పాడైంది"); // no "../" tricks
                        File parent = dst.getParentFile();
                        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("ఫోన్‌లో చోటు లేదు");
                        try (OutputStream out = new FileOutputStream(dst)) {
                            int n;
                            while ((n = zip.read(b)) > 0) {
                                out.write(b, 0, n);
                                o.bytes += n;
                                if (o.bytes - shown > 5L * 1024 * 1024) { shown = o.bytes; say(progress, "తెరుస్తున్నాను… " + size(o.bytes)); }
                            }
                        }
                        if (e.getTime() > 0) //noinspection ResultOfMethodCallIgnored
                            dst.setLastModified(e.getTime());
                        if (name.startsWith("prefs/") && !validPrefs(java.nio.file.Files.readAllBytes(dst.toPath())))
                            throw new BadFile("బ్యాకప్‌లో సెట్టింగ్స్ ఫైల్ పాడైంది: " + name);
                    }
                }
                if (!dec.finished()) throw new BadFile("బ్యాకప్ ఫైల్ పూర్తిగా లేదు (మధ్యలో తెగిపోయింది)");
            }
            File meta = new File(in0, "meta.json");
            if (!meta.isFile()) throw new BadFile("బ్యాకప్ ఫైల్ పాడైంది");
            o.meta = new JSONObject(new String(java.nio.file.Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8));
            //noinspection ResultOfMethodCallIgnored
            meta.delete();
            return o;
        } catch (Exception e) {
            if (o.key != null) Arrays.fill(o.key, (byte) 0);
            deleteTree(in0);
            deleteTree(ex0);
            busy.set(false);
            throw e;
        }
    }

    /** He said no (or the screen went): the unpacked copy goes (in the background), then the lock is let go. */
    static void discard(Context ctx, Opened o) {
        Context c = ctx.getApplicationContext();
        if (o != null && o.key != null) Arrays.fill(o.key, (byte) 0);
        new Thread(() -> {
            try {
                deleteTree(stageIn(c));
                deleteTree(stageExt(c));
            } finally {
                busy.set(false);
            }
        }, "jarvis-restore-discard").start();
    }

    /**
     * He said yes: the unpacked backup is put in place when Jarvis starts next (restart right after). canWrite: Android
     * let Jarvis write to that Drive file, so it goes on as this phone's backup, but only once the restore is in.
     */
    static void markReady(Context ctx, Opened o, Uri uri, String where, boolean canWrite) throws Exception {
        Context c = ctx.getApplicationContext();
        File in0 = stageIn(c);
        try {
            if (canWrite) {
                JSONObject next = settings(o.key, o.salt, o.iterations, uri, where, o.meta.optString("install", ""));
                writeSynced(new File(in0, "BACKUP.json"), next.toString().getBytes(StandardCharsets.UTF_8));
            }
            writeSynced(new File(in0, "READY"), String.valueOf(o.meta.optLong("made")).getBytes(StandardCharsets.UTF_8));
        } finally {
            if (o.key != null) Arrays.fill(o.key, (byte) 0);
        }
    }

    private static void writeSynced(File f, byte[] b) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b);
            out.getFD().sync();
        }
    }

    /**
     * At Jarvis's start (JarvisApp, before anything reads its data): a restore he confirmed goes in place. Every move is
     * written to a journal first, so a restore cut off half way (battery, crash) is undone at the next start and tried
     * again; the phone's own data is only thrown away once all of the backup is in.
     */
    static void applyPending(Context c) {
        File ext = c.getExternalFilesDir(null), in0 = stageIn(c);
        String r = applyDirs(c.getFilesDir(), ext, prefsDir(c));
        if (r == null) return; // nothing to put in (or the phone's storage isn't ready yet: next start)
        if (r.equals("ok")) {
            // all in: only now this phone takes that Drive file over as its own backup (when Android let it write there)
            try {
                File next = new File(in0, "BACKUP.json");
                if (next.isFile()) save(c, new JSONObject(new String(java.nio.file.Files.readAllBytes(next.toPath()), StandardCharsets.UTF_8)));
            } catch (Exception ignored) {} // then he sets the backup up again (the note says so)
            cleanLater(c.getFilesDir(), ext);
        }
        sp(c).edit().putString("restore_note", r).commit();
    }

    /**
     * The file side of applyPending (plain folders, so it can be tested): "ok", "fail:why", or null when there was
     * nothing to put in.
     */
    static String applyDirs(File filesDir, File ext, File pd) {
        File in0 = new File(filesDir, STAGE), ex0 = ext == null ? null : new File(ext, STAGE);
        File old = new File(filesDir, OLD), oldExt = ext == null ? null : new File(ext, OLD);
        File ready = new File(in0, "READY"), journal = new File(old, "JOURNAL"), keep = new File(old, "KEEP");
        if (!ready.isFile()) {
            boolean leftovers = in0.exists() || (ex0 != null && ex0.exists()) || old.exists() || (oldExt != null && oldExt.exists());
            if (leftovers) new Thread(() -> { // an open he didn't confirm, or the old data after a restore that went in
                if (!busy.compareAndSet(false, true)) return; // he is opening a backup right now: next time
                try {
                    if (!ready.isFile()) { deleteTree(in0); deleteTree(ex0); }
                    if (!keep.exists()) { deleteTree(old); deleteTree(oldExt); } // KEEP: data that could not go back, never deleted
                } finally {
                    busy.set(false);
                }
            }, "jarvis-restore-clean").start();
            return null;
        }
        if (ext == null) return null; // the phone's storage isn't ready yet: everything stays as it is, tried at the next start
        if (keep.exists()) { // an earlier restore could not put everything back: touch nothing, tell him
            //noinspection ResultOfMethodCallIgnored
            ready.delete();
            return "fail:పాత డేటా ఇంకా పక్కన ఉంది";
        }
        if (journal.isFile() && !undo(journal)) { // cut off half way last time, and it can't all be put back: touch nothing more
            try { //noinspection ResultOfMethodCallIgnored
                keep.createNewFile();
            } catch (IOException ignored) {}
            //noinspection ResultOfMethodCallIgnored
            ready.delete();
            return "fail:మధ్యలో ఆగిన రీస్టోర్";
        }
        deleteTree(old);
        deleteTree(oldExt);
        List<File[]> moves = new ArrayList<>();
        try (FileOutputStream log = mkJournal(old, oldExt, journal)) {
            if (!pd.isDirectory() && !pd.mkdirs()) throw new IOException("సెట్టింగ్స్ ఫోల్డర్ లేదు");
            File oldPrefs = new File(old, "prefs"), oldFiles = new File(old, "files");
            if (!oldPrefs.mkdirs() || !oldFiles.mkdirs()) throw new IOException("ఫోన్‌లో చోటు లేదు");
            for (File f : list(pd)) {
                String base = prefsBase(f.getName());
                if (base != null && ownPrefs(base)) move(f, new File(oldPrefs, f.getName()), moves, log);
            }
            for (File f : list(new File(in0, "prefs"))) move(f, new File(pd, f.getName()), moves, log);
            for (File f : list(filesDir)) if (!skipFile(f.getName(), true)) move(f, new File(oldFiles, f.getName()), moves, log);
            for (File f : list(new File(in0, "files"))) move(f, new File(filesDir, f.getName()), moves, log);
            if (ex0 != null && ex0.isDirectory()) {
                for (File f : list(ext)) if (!skipFile(f.getName(), true)) move(f, new File(oldExt, f.getName()), moves, log);
                for (File f : list(ex0)) move(f, new File(ext, f.getName()), moves, log);
            }
            if (!ready.delete()) throw new IOException("READY");
        } catch (Exception e) {
            boolean back = true;
            for (int i = moves.size() - 1; i >= 0; i--) if (!moves.get(i)[1].renameTo(moves.get(i)[0])) back = false;
            if (back) {
                //noinspection ResultOfMethodCallIgnored
                journal.delete();
                deleteTree(old);
                deleteTree(oldExt);
                deleteTree(in0); // (the backup itself is still in Drive: he can try again)
                deleteTree(ex0);
            } else {
                try { //noinspection ResultOfMethodCallIgnored
                    keep.createNewFile();
                } catch (IOException ignored) {}
                //noinspection ResultOfMethodCallIgnored
                ready.delete();
            }
            return "fail:" + e.getMessage();
        }
        //noinspection ResultOfMethodCallIgnored
        journal.delete();
        return "ok";
    }

    /** After a restore went in: the old data and the empty unpack folders go (in the background: it may be big). */
    private static void cleanLater(File filesDir, File ext) {
        new Thread(() -> {
            if (!busy.compareAndSet(false, true)) return; // next start
            try {
                deleteTree(new File(filesDir, STAGE));
                deleteTree(new File(filesDir, OLD));
                if (ext != null) { deleteTree(new File(ext, STAGE)); deleteTree(new File(ext, OLD)); }
            } finally {
                busy.set(false);
            }
        }, "jarvis-restore-clean").start();
    }

    private static FileOutputStream mkJournal(File old, File oldExt, File journal) throws IOException {
        if (!old.mkdirs() || (oldExt != null && !oldExt.mkdirs())) throw new IOException("ఫోన్‌లో చోటు లేదు");
        return new FileOutputStream(journal);
    }

    /** Each move is written down (and to disk) before it is made, so it can be undone after a crash. */
    private static void move(File from, File to, List<File[]> moves, FileOutputStream log) throws IOException {
        log.write((from.getPath() + "\t" + to.getPath() + "\n").getBytes(StandardCharsets.UTF_8));
        log.getFD().sync();
        if (!from.renameTo(to)) throw new IOException("తరలించలేకపోయాను: " + from.getName());
        moves.add(new File[]{from, to});
    }

    /** Undoes the moves of a restore that was cut off, newest first (only those that were really made); true when all went back. */
    private static boolean undo(File journal) {
        try {
            String[] lines = new String(java.nio.file.Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8).split("\n");
            boolean all = true;
            for (int i = lines.length - 1; i >= 0; i--) {
                String[] m = lines[i].split("\t");
                if (m.length != 2) continue;
                File from = new File(m[0]), to = new File(m[1]);
                if (!to.exists()) continue;                       // never made (or already back)
                if (from.exists() || !to.renameTo(from)) all = false;
            }
            if (all) //noinspection ResultOfMethodCallIgnored
                journal.delete();
            return all;
        } catch (IOException e) {
            return false;
        }
    }

    /** After a restart: what happened to the restore (once), or null. */
    static String takeRestoreNote(Context c) {
        SharedPreferences s = sp(c);
        String n = s.getString("restore_note", null);
        if (n == null) return null;
        s.edit().remove("restore_note").apply();
        if (n.equals("ok"))
            return "✓ బ్యాకప్ నుంచి అన్నీ తిరిగి వచ్చాయి.\n\nఇప్పుడు సెట్టింగ్స్ → \"Jarvis చెక్\" లో ✗ ఉన్న అనుమతులు (మైక్, నోటిఫికేషన్లు, "
                    + "స్క్రీన్ యాక్సెస్, Display over other apps) ఒక్కొక్కటిగా ఇవ్వండి. \"Jarvis\" పదం మోడల్స్ తనంతట తానే మళ్లీ వస్తాయి."
                    + (configured(c) ? "" : "\n\nబ్యాకప్ కొనసాగడానికి సెట్టింగ్స్ → బ్యాకప్ లో \"బ్యాకప్ సెట్ చేయి\" ఒకసారి నొక్కండి.");
        return "✗ బ్యాకప్ తిరిగి తేవడం పూర్తి కాలేదు, ఫోన్‌లో ఉన్నది అలాగే ఉంచాను (" + n.substring(Math.min(5, n.length()))
                + "). సెట్టింగ్స్ → బ్యాకప్ లో మళ్లీ ప్రయత్నించండి.";
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) for (File k : list(f)) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ================================================================ the locked file format

    private static byte[] header(byte[] salt, int iterations, byte[] prefix) {
        ByteBuffer b = ByteBuffer.allocate(HEADER);
        b.put(MAGIC).put((byte) VERSION).putInt(iterations).put(salt).put(prefix);
        return b.array();
    }

    private static byte[] nonce(byte[] header, long index) {
        ByteBuffer b = ByteBuffer.allocate(12);
        b.put(header, HEADER - 8, 8).putInt((int) index);
        return b.array();
    }

    /** What each piece is checked against: the file's header, its number and whether it is the last. */
    private static byte[] aad(byte[] header, long index, boolean last) {
        return ByteBuffer.allocate(HEADER + 9).put(header).putLong(index).put((byte) (last ? 1 : 0)).array();
    }

    /** Encrypts what is written to it, piece by piece. close() writes the last piece. */
    static final class EncOut extends OutputStream {
        private final DataOutputStream out;
        private final SecretKeySpec key;
        private final byte[] header;
        private final byte[] buf = new byte[CHUNK];
        private int n;
        private long index;
        private boolean closed;

        EncOut(OutputStream out, byte[] key, byte[] salt, int iterations) throws IOException {
            this.out = new DataOutputStream(new java.io.BufferedOutputStream(out, 256 * 1024));
            this.key = new SecretKeySpec(key, "AES");
            byte[] prefix = new byte[8];
            new SecureRandom().nextBytes(prefix);
            header = Backup.header(salt, iterations, prefix);
            this.out.write(header);
        }

        @Override public void write(int b) throws IOException {
            buf[n++] = (byte) b;
            if (n == CHUNK) piece(false);
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int k = Math.min(len, CHUNK - n);
                System.arraycopy(b, off, buf, n, k);
                n += k;
                off += k;
                len -= k;
                if (n == CHUNK) piece(false);
            }
        }

        private void piece(boolean last) throws IOException {
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce(header, index)));
                c.updateAAD(aad(header, index, last));
                byte[] ct = c.doFinal(buf, 0, n);
                out.writeInt(n);
                out.writeByte(last ? 1 : 0);
                out.write(ct);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            }
            index++;
            n = 0;
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            piece(true);
            out.close();
        }
    }

    /** Decrypts and checks piece by piece; a wrong password fails on the first piece. */
    static final class DecIn extends InputStream {
        private final DataInputStream in;
        private final SecretKeySpec key;
        private final byte[] header;
        private byte[] cur = new byte[0];
        private int pos;
        private long index;
        private boolean done;

        DecIn(DataInputStream in, byte[] key, byte[] header) {
            this.in = in;
            this.key = new SecretKeySpec(key, "AES");
            this.header = header;
        }

        /** The last piece was read and checked (the file was not cut off). */
        boolean finished() throws IOException {
            while (!done) if (!next()) break; // pieces the ZIP reader did not need (normally just the empty last one)
            return done;
        }

        private boolean next() throws IOException {
            if (done) return false;
            int len, flag;
            try {
                len = in.readInt();
                flag = in.readUnsignedByte();
            } catch (EOFException e) {
                throw new BadFile("బ్యాకప్ ఫైల్ పూర్తిగా లేదు (మధ్యలో తెగిపోయింది)");
            }
            if (len < 0 || len > CHUNK || (flag != 0 && flag != 1)) throw new BadFile("బ్యాకప్ ఫైల్ పాడైంది");
            byte[] ct = new byte[len + 16];
            try { in.readFully(ct); } catch (EOFException e) { throw new BadFile("బ్యాకప్ ఫైల్ పూర్తిగా లేదు (మధ్యలో తెగిపోయింది)"); }
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce(header, index)));
                c.updateAAD(aad(header, index, flag == 1));
                cur = c.doFinal(ct);
            } catch (AEADBadTagException e) {
                if (index == 0) throw new WrongPassword();
                throw new BadFile("బ్యాకప్ ఫైల్ పాడైంది");
            } catch (Exception e) {
                throw new IOException(e);
            }
            pos = 0;
            index++;
            if (flag == 1) done = true;
            return true;
        }

        @Override public int read() throws IOException {
            while (pos >= cur.length) if (!next()) return -1;
            return cur[pos++] & 0xFF;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (pos >= cur.length) if (!next()) return -1;
            int k = Math.min(len, cur.length - pos);
            System.arraycopy(cur, pos, b, off, k);
            pos += k;
            return k;
        }
    }
}
