package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Anil's shift duty: three batches take turns, each doing 2 days (48 hours) of duty and then 4 days at home.
 * Days can be changed one by one (extra duty, leave), and "covering" someone gives 4 days of duty followed
 * by 8 days off (the covered person then does his next turn). The calendar screen shows who is on duty on
 * each date; Jarvis answers "నా డ్యూటీ ఎప్పుడు?" and reminds him before each duty. Kept on the phone.
 */
final class Duty {
    private Duty() {}

    static final String ME = "me";

    // ================================================================ the roster (plain Java, no Android)

    static final class Batch {
        String id = "", name = "", time = "11:30"; // he relieves the batch before him at 11:30
        LocalDate start;                       // one of its duty days (the first of a turn); null = not set yet
        final List<String> members = new ArrayList<>();
    }

    static final class Change {
        LocalDate date;
        String who = ME;                       // "me", a person's name, or "batch:<id>"
        boolean duty;                          // true = on duty that day, false = off
        String note = "";
    }

    static final class Roster {
        int on = 2, off = 4;
        String mine = "A";
        boolean remind = true;
        /** How long before the duty starts he leaves home (the ride there): 11:30 duty -> leave at 10:00. */
        int leaveBefore = 90;
        final List<Batch> batches = new ArrayList<>();
        final List<Change> changes = new ArrayList<>();

        int cycle() { return Math.max(1, on + off); }

        Batch batch(String idOrName) {
            if (idOrName == null) return null;
            String k = idOrName.trim().toLowerCase(Locale.ROOT).replace("batch", "").replace("బ్యాచ్", "").trim();
            for (Batch b : batches) if (b.id.equalsIgnoreCase(k) || b.name.trim().equalsIgnoreCase(idOrName.trim())) return b;
            return null;
        }

        Batch myBatch() { return batch(mine); }

        /** The batch a person belongs to (me = my batch). */
        Batch batchOf(String person) {
            if (ME.equals(person)) return myBatch();
            for (Batch b : batches) for (String m : b.members) if (m.equalsIgnoreCase(person)) return b;
            return null;
        }

        /** The person's name as saved ("ravi" -> "Ravi"), or null. */
        String person(String name) {
            if (name == null) return null;
            String n = name.trim();
            for (Batch b : batches) for (String m : b.members) if (m.equalsIgnoreCase(n)) return m;
            for (Batch b : batches) for (String m : b.members) {
                String a = m.toLowerCase(Locale.ROOT), q = n.toLowerCase(Locale.ROOT);
                if (!q.isEmpty() && (a.contains(q) || q.contains(a))) return m;
            }
            return null;
        }

        boolean baseOn(Batch b, LocalDate d) {
            if (b == null || b.start == null) return false;
            long diff = d.toEpochDay() - b.start.toEpochDay();
            long m = ((diff % cycle()) + cycle()) % cycle();
            return m < on;
        }

        Change change(LocalDate d, String who) {
            for (int i = changes.size() - 1; i >= 0; i--) {
                Change c = changes.get(i);
                if (c.date.equals(d) && c.who.equalsIgnoreCase(who)) return c;
            }
            return null;
        }

        boolean batchOn(Batch b, LocalDate d) {
            Change c = change(d, "batch:" + b.id);
            return c != null ? c.duty : baseOn(b, d);
        }

        /** Is this person ("me" or a name) on duty that day? */
        boolean isOn(String who, LocalDate d) {
            if (who.startsWith("batch:")) {
                Batch b = batch(who.substring(6));
                return b != null && batchOn(b, d);
            }
            Change c = change(d, who);
            if (c != null) return c.duty;
            Batch b = batchOf(who);
            return b != null && batchOn(b, d);
        }

        /** Everyone on duty that day: "me" and names, in batch order, then extra people. */
        List<String> onDuty(LocalDate d) {
            Set<String> out = new LinkedHashSet<>();
            for (Batch b : batches) {
                if (!batchOn(b, d)) continue;
                List<String> people = new ArrayList<>();
                if (b.id.equalsIgnoreCase(mine)) people.add(ME);
                people.addAll(b.members);
                for (String p : people) {
                    Change c = change(d, p);
                    if (c == null || c.duty) out.add(p);
                }
            }
            for (Change c : changes) if (c.date.equals(d) && c.duty && !c.who.startsWith("batch:")) out.add(c.who);
            return new ArrayList<>(out);
        }

        /** Batches on duty that day (for the colour on the calendar). */
        List<Batch> batchesOn(LocalDate d) {
            List<Batch> out = new ArrayList<>();
            for (Batch b : batches) if (batchOn(b, d)) out.add(b);
            return out;
        }

        /** Runs of duty days for this person between the two dates: {first, last}. */
        List<LocalDate[]> blocks(String who, LocalDate from, LocalDate to) {
            List<LocalDate[]> out = new ArrayList<>();
            LocalDate start = null, prev = null;
            // a run already going on "from" starts earlier: look back one cycle
            for (LocalDate d = from.minusDays(cycle()); !d.isAfter(to); d = d.plusDays(1)) {
                boolean on = isOn(who, d);
                if (on && start == null) start = d;
                if (!on && start != null) {
                    if (!prev.isBefore(from)) out.add(new LocalDate[]{start, prev});
                    start = null;
                }
                prev = d;
            }
            if (start != null) out.add(new LocalDate[]{start, to});
            return out;
        }

        /** Start time of a duty day for this person (his batch's time). */
        String timeOf(String who) {
            Batch b = batchOf(who);
            if (b == null) b = myBatch();
            return b == null ? "11:30" : b.time;
        }

        /** When he should leave home for a duty starting at this time ("11:30" -> "10:00"). */
        String leaveTime(String start) {
            String[] hm = start.split(":");
            int m = Integer.parseInt(hm[0]) * 60 + Integer.parseInt(hm[1]) - leaveBefore;
            m = ((m % 1440) + 1440) % 1440;
            return String.format(Locale.ENGLISH, "%02d:%02d", m / 60, m % 60);
        }

        void set(String who, LocalDate d, boolean duty, String note) {
            for (int i = changes.size() - 1; i >= 0; i--) {
                Change c = changes.get(i);
                if (c.date.equals(d) && c.who.equalsIgnoreCase(who)) changes.remove(i);
            }
            // a change back to the normal schedule is no change at all
            Batch b = who.startsWith("batch:") ? batch(who.substring(6)) : batchOf(who);
            boolean normal = b != null && (who.startsWith("batch:") ? baseOn(b, d) : batchOn(b, d));
            if (normal == duty && (note == null || note.isEmpty())) return;
            Change c = new Change();
            c.date = d;
            c.who = who;
            c.duty = duty;
            c.note = note == null ? "" : note;
            changes.add(c);
        }

        void clear(LocalDate d, String who) {
            for (int i = changes.size() - 1; i >= 0; i--) {
                Change c = changes.get(i);
                if (c.date.equals(d) && (who == null || c.who.equalsIgnoreCase(who))) changes.remove(i);
            }
        }

        /** "I do X's duty" (see swap): 4 days on, then X does my next turn and I get 8 days off. */
        LocalDate[] cover(String x, LocalDate date, String myName) {
            String xName = x.startsWith("batch:") ? (batch(x.substring(6)) == null ? x : batch(x.substring(6)).name) : x;
            return swap(ME, x, date, myName, xName);
        }

        /**
         * A swap of turns: "doer" does "absent"'s turn on/after that date (right after or before his own, so he is on
         * 4 days in a row), and "absent" then does doer's next turn after it (so absent is off 8 days, then on 4).
         * Either side can be "me", a person or "batch:<id>". Returns {absent's turn start, end, doer's given turn start, end}.
         */
        LocalDate[] swap(String doer, String absent, LocalDate date, String doerName, String absentName) {
            if (doer.equalsIgnoreCase(absent)) return null;
            LocalDate s = date;
            for (int i = 0; i < cycle() && !isOn(absent, s); i++) s = s.plusDays(1);
            if (!isOn(absent, s)) return null;
            while (isOn(absent, s.minusDays(1))) s = s.minusDays(1);
            LocalDate e = s;
            while (isOn(absent, e.plusDays(1))) e = e.plusDays(1);
            for (LocalDate d = s; !d.isAfter(e); d = d.plusDays(1)) {
                set(doer, d, true, absentName + " బదులు");
                set(absent, d, false, doerName + " చేశారు");
            }
            // doer's next own turn after that goes to absent
            LocalDate m = e.plusDays(1);
            for (int i = 0; i < 3 * cycle() && !isOn(doer, m); i++) m = m.plusDays(1);
            if (!isOn(doer, m)) return new LocalDate[]{s, e, null, null};
            LocalDate me = m;
            while (isOn(doer, me.plusDays(1))) me = me.plusDays(1);
            for (LocalDate d = m; !d.isAfter(me); d = d.plusDays(1)) {
                set(doer, d, false, absentName + " చేస్తారు (ముందు " + doerName + " చేశారు)");
                set(absent, d, true, doerName + " బదులు");
            }
            return new LocalDate[]{s, e, m, me};
        }

        /** Fills missing batch dates from one that is set: the next batch starts "on" days later. */
        void fillStarts() {
            Batch known = null;
            int at = -1;
            for (int i = 0; i < batches.size(); i++) if (batches.get(i).start != null) { known = batches.get(i); at = i; break; }
            if (known == null) return;
            for (int i = 0; i < batches.size(); i++) {
                Batch b = batches.get(i);
                if (b.start == null) b.start = known.start.plusDays((long) (i - at) * on);
            }
        }
    }

    // ================================================================ storage

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_duty", Context.MODE_PRIVATE); }

    static final int[] COLORS = {0xFF38BDF8, 0xFFFB923C, 0xFFA78BFA, 0xFF34D399, 0xFFF472B6};

    static synchronized Roster load(Context c) {
        Roster r = new Roster();
        try {
            JSONObject o = new JSONObject(sp(c).getString("data", "{}"));
            r.on = o.optInt("on", 2);
            r.off = o.optInt("off", 4);
            r.mine = o.optString("mine", "A");
            r.remind = o.optBoolean("remind", true);
            r.leaveBefore = o.optInt("leave_before", 90);
            JSONArray bs = o.optJSONArray("batches");
            for (int i = 0; bs != null && i < bs.length(); i++) {
                JSONObject j = bs.getJSONObject(i);
                Batch b = new Batch();
                b.id = j.optString("id");
                b.name = j.optString("name", b.id + " బ్యాచ్");
                b.time = j.optString("time", "11:30");
                String s = j.optString("start", "");
                b.start = s.isEmpty() ? null : LocalDate.parse(s);
                JSONArray ms = j.optJSONArray("members");
                for (int k = 0; ms != null && k < ms.length(); k++) b.members.add(ms.getString(k));
                r.batches.add(b);
            }
            JSONArray cs = o.optJSONArray("changes");
            for (int i = 0; cs != null && i < cs.length(); i++) {
                JSONObject j = cs.getJSONObject(i);
                Change ch = new Change();
                ch.date = LocalDate.parse(j.getString("date"));
                ch.who = j.optString("who", ME);
                ch.duty = j.optBoolean("duty");
                ch.note = j.optString("note", "");
                r.changes.add(ch);
            }
        } catch (Exception ignored) {}
        if (r.batches.isEmpty()) {
            for (String id : new String[]{"A", "B", "C"}) {
                Batch b = new Batch();
                b.id = id;
                b.name = id + " బ్యాచ్";
                r.batches.add(b);
            }
        }
        return r;
    }

    static synchronized void save(Context c, Roster r) {
        try {
            JSONObject o = new JSONObject().put("on", r.on).put("off", r.off).put("mine", r.mine).put("remind", r.remind)
                    .put("leave_before", r.leaveBefore);
            JSONArray bs = new JSONArray();
            for (Batch b : r.batches) {
                JSONArray ms = new JSONArray();
                for (String m : b.members) ms.put(m);
                bs.put(new JSONObject().put("id", b.id).put("name", b.name).put("time", b.time)
                        .put("start", b.start == null ? "" : b.start.toString()).put("members", ms));
            }
            JSONArray cs = new JSONArray();
            LocalDate old = LocalDate.now().minusDays(120); // changes from long ago are dropped
            for (Change ch : r.changes) {
                if (ch.date.isBefore(old)) continue;
                cs.put(new JSONObject().put("date", ch.date.toString()).put("who", ch.who).put("duty", ch.duty).put("note", ch.note));
            }
            sp(c).edit().putString("data", o.put("batches", bs).put("changes", cs).toString()).apply();
        } catch (Exception ignored) {}
    }

    static boolean ready(Roster r) {
        Batch b = r.myBatch();
        return b != null && b.start != null;
    }

    // ================================================================ words

    private static final String[] DAYS = {"సోమ", "మంగళ", "బుధ", "గురు", "శుక్ర", "శని", "ఆది"};
    private static final String[] MONTHS = {"జనవరి", "ఫిబ్రవరి", "మార్చి", "ఏప్రిల్", "మే", "జూన్", "జూలై", "ఆగస్టు", "సెప్టెంబర్", "అక్టోబర్", "నవంబర్", "డిసెంబర్"};

    static String day(LocalDate d) { return DAYS[d.getDayOfWeek().getValue() - 1] + " " + d.getDayOfMonth() + " " + MONTHS[d.getMonthValue() - 1]; }

    static String monthName(int m) { return MONTHS[m - 1]; }

    static String name(Context c, String who) { return ME.equals(who) ? new Prefs(c).name() : who; }

    static String whenText(LocalDate d) {
        long n = d.toEpochDay() - LocalDate.now().toEpochDay();
        return n == 0 ? "ఈరోజు" : n == 1 ? "రేపు" : n == 2 ? "ఎల్లుండి" : n > 0 ? n + " రోజుల్లో" : (-n) + " రోజుల క్రితం";
    }

    /** A block as text: "గురు 1 అక్టోబర్ 11:30 నుంచి శని 3 అక్టోబర్ 11:30 వరకు, 48 గంటలు (రేపు)". */
    static String blockText(Roster r, String who, LocalDate[] b) {
        long days = b[1].toEpochDay() - b[0].toEpochDay() + 1;
        String t = r.timeOf(who);
        return day(b[0]) + " " + t + " నుంచి " + day(b[1].plusDays(1)) + " " + t + " వరకు, " + (days * 24) + " గంటలు (" + whenText(b[0]) + ")";
    }

    // ================================================================ reminders (Proactive's regular check)

    private static SharedPreferences told(Context c) { return c.getSharedPreferences("jarvis_duty_told", Context.MODE_PRIVATE); }

    private static boolean once(Context c, String key) {
        if (told(c).getBoolean(key, false)) return false;
        told(c).edit().putBoolean(key, true).apply();
        return true;
    }

    /**
     * Two days before (a note); the evening before (note + voice, with the time to leave home); on the day an
     * hour before leaving home, and again at the time to leave (the duty starts when he relieves the others).
     */
    static void tick(Context c, Prefs p, boolean quiet) {
        Roster r = load(c);
        if (!r.remind || !ready(r)) return;
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        for (LocalDate[] b : r.blocks(ME, today, today.plusDays(3))) {
            LocalDate s = b[0];
            if (s.isBefore(today)) continue;
            String t = r.timeOf(ME), go = r.leaveTime(t);
            String[] hm = t.split(":");
            LocalDateTime start = s.atTime(Integer.parseInt(hm[0]), Integer.parseInt(hm[1]));
            LocalDateTime leave = start.minusMinutes(r.leaveBefore);
            long hours = (b[1].toEpochDay() - s.toEpochDay() + 1) * 24;
            String when = t + " కి రిలీవ్ చేయాలి, " + hours + " గంటలు (" + day(b[1].plusDays(1)) + " " + t + " వరకు)";
            if (s.equals(today.plusDays(2)) && now.getHour() >= 9 && now.getHour() < 12 && once(c, "two|" + s)) {
                notify(c, ("two" + s).hashCode(), "🗓️ ఎల్లుండి డ్యూటీ", day(s) + ", " + when);
            }
            if (s.equals(today.plusDays(1)) && now.getHour() >= 20 && now.getHour() < 23 && once(c, "eve|" + s)) {
                notify(c, ("eve" + s).hashCode(), "🗓️ రేపు మీ డ్యూటీ", day(s) + ", " + when + ". ఇంటి నుంచి " + go + " కల్లా బయలుదేరండి.");
                if (!quiet) Announcer.say(c, p.name() + ", రేపు మీ డ్యూటీ. " + t + " కి రిలీవ్ చేయాలి, " + go + " కల్లా బయలుదేరండి.");
            }
            if (!now.isBefore(leave.minusMinutes(60)) && now.isBefore(leave) && once(c, "day|" + s)) {
                notify(c, ("day" + s).hashCode(), "🗓️ ఈరోజు డ్యూటీ, " + go + " కల్లా బయలుదేరండి", when + ". అన్నీ సిద్ధం చేసుకోండి.");
                Announcer.say(c, p.name() + ", ఈరోజు డ్యూటీ. " + t + " కి రిలీవ్ చేయాలి, " + go + " కల్లా బయలుదేరండి.");
            }
            if (!now.isBefore(leave) && now.isBefore(start) && once(c, "go|" + s)) {
                notify(c, ("go" + s).hashCode(), "🏍️ బయలుదేరే టైమ్ అయింది", t + " కి రిలీవ్ చేయాలి.");
                Announcer.say(c, p.name() + ", బయలుదేరే టైమ్ అయింది. " + t + " కి రిలీవ్ చేయాలి. జాగ్రత్తగా వెళ్లండి.");
            }
        }
    }

    private static void notify(Context c, int id, String title, String text) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel("jarvis_duty", "డ్యూటీ రిమైండర్లు", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent open = PendingIntent.getActivity(c, id, new Intent(c, DutyActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify("duty", id, new Notification.Builder(c, "jarvis_duty").setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                    .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).build());
        } catch (Exception ignored) {}
    }

    // ================================================================ set up from a few lines of text

    private static final java.util.regex.Pattern ISO = java.util.regex.Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final java.util.regex.Pattern DMY = java.util.regex.Pattern.compile("(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})");
    private static final java.util.regex.Pattern HM = java.util.regex.Pattern.compile("(\\d{1,2})[:.](\\d{2})");

    /**
     * Batches from lines like
     *   "నా బ్యాచ్: నేను, సోమయ్య | 2026-10-06 | 11:30"
     *   "బ్యాచ్: శ్రీను, రామకృష్ణ | 2026-10-08 | 11:30"
     *   "సైకిల్: 2 డ్యూటీ, 4 సెలవు"
     * (one line per batch, in the order they relieve each other; the date is the first day of one of its duties).
     * Replaces the batches; day-by-day changes are kept. Returns how many batches, or -1 if nothing was understood.
     */
    static int fromText(Roster r, String text, String myName) {
        List<Batch> found = new ArrayList<>();
        String mineId = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String low = line.toLowerCase(Locale.ROOT);
            if (low.startsWith("సైకిల్") || low.startsWith("cycle")) {
                java.util.regex.Matcher n = java.util.regex.Pattern.compile("(\\d+)").matcher(line);
                if (n.find()) r.on = Math.max(1, Math.min(10, Integer.parseInt(n.group(1))));
                if (n.find()) r.off = Math.max(0, Math.min(30, Integer.parseInt(n.group(1))));
                continue;
            }
            if (!line.contains("|")) continue;
            String[] parts = line.split("\\|");
            String who = parts[0];
            boolean mine = low.startsWith("నా ") || low.startsWith("మా ") || low.startsWith("my ");
            int colon = who.indexOf(':');
            if (colon >= 0) who = who.substring(colon + 1);
            Batch b = new Batch();
            b.id = String.valueOf((char) ('A' + found.size()));
            b.name = b.id + " బ్యాచ్";
            for (String m : who.split("\\s*(?:,|،|–|-| మరియు | and )\\s*")) {
                String n = m.trim();
                if (n.isEmpty()) continue;
                if (n.equals("నేను") || n.equalsIgnoreCase("me") || n.equalsIgnoreCase(myName)) { mine = true; continue; }
                b.members.add(n);
            }
            for (int k = 1; k < parts.length; k++) {
                String p = parts[k].trim();
                java.util.regex.Matcher iso = ISO.matcher(p), dmy = DMY.matcher(p), hm = HM.matcher(p);
                try {
                    if (iso.find()) b.start = LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
                    else if (dmy.find()) b.start = LocalDate.of(Integer.parseInt(dmy.group(3)), Integer.parseInt(dmy.group(2)), Integer.parseInt(dmy.group(1)));
                    else if (hm.find()) b.time = String.format(Locale.ENGLISH, "%02d:%02d", Integer.parseInt(hm.group(1)), Integer.parseInt(hm.group(2)));
                } catch (Exception ignored) {}
            }
            if (mine) mineId = b.id;
            found.add(b);
        }
        if (found.isEmpty()) return -1;
        r.batches.clear();
        r.batches.addAll(found);
        if (mineId != null) r.mine = mineId;
        r.fillStarts();
        return found.size();
    }

    // ================================================================ for the duty tool

    static LocalDate parseDate(String s) {
        try { return s == null || s.trim().isEmpty() ? null : LocalDate.parse(s.trim(), DateTimeFormatter.ISO_LOCAL_DATE); } catch (Exception e) { return null; }
    }

    /** "me" / a member's name / a batch ("B", "B బ్యాచ్") -> who, or null. */
    static String who(Roster r, String said) {
        if (said == null || said.trim().isEmpty()) return ME;
        String s = said.trim(), l = s.toLowerCase(Locale.ROOT);
        if (l.equals("me") || l.equals("నేను") || l.equals("నా") || l.equals("naa") || l.equals("anil")) return ME;
        String p = r.person(s);
        if (p != null) return p;
        Batch b = r.batch(s);
        if (b != null) return "batch:" + b.id;
        return null;
    }
}
