package com.anil.jarvis;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Backup of Jarvis's memories, missions, chat and reminders to a PRIVATE repo "jarvis-backup" in Anil's GitHub
 * (with the token he already gave in Settings). Daily when switched on, or when he asks. A new phone (or the
 * future PC Jarvis) restores from the same repo. The token itself is never written anywhere.
 */
public class Backup extends JobService {
    static final String REPO = "jarvis-backup";
    private static final String[] FILES = {"memory.json", "missions.json", "chat.json", "reminders.json"};
    private static final int JOB_ID = 7401;

    // ---------------------------------------------------------------- the daily job

    static boolean enabled(Context c) { return sp(c).getBoolean("backup_daily", false); }

    static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("backup_daily", on).apply();
        schedule(c);
    }

    /** Daily with internet while switched on; cancelled when off. */
    static void schedule(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return;
            if (!enabled(c)) { js.cancel(JOB_ID); return; }
            if (js.getPendingJob(JOB_ID) != null) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, Backup.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(24 * 3600 * 1000L)
                    .setPersisted(true)
                    .build());
        } catch (Exception ignored) {}
    }

    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try { run(getApplicationContext()); } catch (Exception e) { sp(this).edit().putString("backup_error", String.valueOf(e.getMessage())).apply(); }
            finally { jobFinished(params, false); }
        }, "jarvis-backup").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis", Context.MODE_PRIVATE); }

    // ---------------------------------------------------------------- backup / restore

    private static String[] auth(Context c) {
        String token = new Prefs(c).githubToken().trim();
        if (token.isEmpty()) throw new IllegalStateException("GitHub token లేదు. Settings → కోడింగ్, వెబ్‌సైట్లు, యాప్‌లు లో GitHub token పెట్టండి.");
        return new String[]{"Authorization", "Bearer " + token, "Accept", "application/vnd.github+json", "X-GitHub-Api-Version", "2022-11-28"};
    }

    /** The private backup repo (made the first time). Refuses to write into a public repo of that name. */
    private static String repo(String[] auth, boolean create) throws Exception {
        String login = Coder.request("GET", "https://api.github.com/user", null, auth).optString("login");
        if (login.isEmpty()) throw new IllegalStateException("GitHub account కనిపించలేదు (token సరిచూడండి)");
        String full = login + "/" + REPO;
        try {
            JSONObject r = Coder.request("GET", "https://api.github.com/repos/" + full, null, auth);
            if (!r.optBoolean("private", false)) throw new IllegalStateException("GitHub లో " + REPO + " repo పబ్లిక్‌గా ఉంది. మీ డేటా కోసం అది private కావాలి: GitHub → ఆ repo → Settings → Change visibility → Private.");
        } catch (Http.ApiError e) {
            if (e.status != 404) throw e;
            if (!create) throw new IllegalStateException("GitHub లో ఇంకా backup లేదు.");
            Coder.request("POST", "https://api.github.com/user/repos", new JSONObject().put("name", REPO).put("private", true)
                    .put("auto_init", true).put("description", "Jarvis backup: memories, missions, chat, reminders (private)"), auth);
            Thread.sleep(1500); // GitHub makes the first commit
            put(auth, full, "README.md", ("# Jarvis backup (private)\n\nMade by the Jarvis Android app. Each file is JSON: `{\"items\": [...]}`.\n\n"
                    + "- memory.json: things Jarvis remembers\n- missions.json: missions (tasks)\n- chat.json: recent conversation\n- reminders.json: reminders\n\n"
                    + "Restore on a new phone: Jarvis Settings → Backup → Restore. The PC Jarvis can read the same files.\n").getBytes("UTF-8"));
        }
        return full;
    }

    /** Uploads the data files; returns a Telugu summary. */
    static String run(Context c) throws Exception {
        String[] a = auth(c);
        String full = repo(a, true);
        int n = 0;
        for (String f : FILES) {
            File file = new File(c.getFilesDir(), f);
            if (!file.exists()) continue;
            put(a, full, f, read(file));
            n++;
        }
        String at = new SimpleDateFormat("d MMM, HH:mm", Locale.ENGLISH).format(new Date());
        sp(c).edit().putString("backup_at", at).remove("backup_error").apply();
        return "Backup అయింది ✓ (" + n + " ఫైల్స్, GitHub → " + full + ", private)";
    }

    /** Replaces this phone's memories, missions, chat and reminders with the backup. */
    static String restore(Context c) throws Exception {
        String[] a = auth(c);
        String full = repo(a, false);
        int n = 0;
        for (String f : FILES) {
            byte[] data;
            try {
                JSONObject o = Coder.request("GET", "https://api.github.com/repos/" + full + "/contents/" + f, null, a);
                String content = o.optString("content", "");
                data = content.isEmpty() ? Coder.download(o.optString("download_url"), a) : Base64.decode(content, Base64.DEFAULT);
            } catch (Http.ApiError e) {
                if (e.status == 404) continue;
                throw e;
            }
            new JSONObject(new String(data, "UTF-8")).getJSONArray("items"); // only a valid file replaces the local one
            write(new File(c.getFilesDir(), f), data);
            n++;
        }
        if (n == 0) throw new IllegalStateException("Backup లో ఫైల్స్ ఏవీ లేవు.");
        Store.get(c).reload();
        return "Backup నుంచి తెచ్చాను ✓ (" + n + " ఫైల్స్)";
    }

    static String status(Context c) {
        SharedPreferences s = sp(c);
        String at = s.getString("backup_at", ""), err = s.getString("backup_error", "");
        return (at.isEmpty() ? "ఇంకా backup చేయలేదు." : "చివరి backup: " + at) + (err.isEmpty() ? "" : "\nచివరిసారి కాలేదు: " + err);
    }

    private static void put(String[] auth, String full, String path, byte[] bytes) throws Exception {
        String url = "https://api.github.com/repos/" + full + "/contents/" + path;
        String sha = null;
        try { sha = Coder.request("GET", url, null, auth).optString("sha", null); } catch (Http.ApiError e) { if (e.status != 404) throw e; }
        JSONObject body = new JSONObject().put("message", "Jarvis backup " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).format(new Date()))
                .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP));
        if (sha != null && !sha.isEmpty()) body.put("sha", sha);
        Coder.request("PUT", url, body, auth);
    }

    private static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    private static void write(File dest, byte[] data) throws IOException {
        File tmp = new File(dest.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(data); }
        if (!tmp.renameTo(dest)) throw new IOException("ఫైల్ రాయలేకపోయాను");
    }
}
