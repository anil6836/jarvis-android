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
import java.time.LocalTime;
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
        /** Home to duty, one way, in km (0 = not told): to see if the bike's charge is enough to go and come back. */
        int tripKm = 0;
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
            r.tripKm = o.optInt("trip_km", 0);
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
                    .put("leave_before", r.leaveBefore).put("trip_km", r.tripKm);
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
        try { SongAlarm.rescheduleAll(c); } catch (Exception ignored) {} // duty-day / day-off alarms follow the change
        try { scheduleChime(c); } catch (Exception ignored) {} // the night chime follows the duty days
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

    /** The morning he leaves home for duty (on duty today, not yesterday). */
    static boolean startsDuty(Roster r, LocalDate d) { return r.isOn(ME, d) && !r.isOn(ME, d.minusDays(1)); }

    /** A whole day at home: not on duty, and not the day a duty ends (at work till the relieve time). */
    static boolean homeAllDay(Roster r, LocalDate d) { return !r.isOn(ME, d) && !r.isOn(ME, d.minusDays(1)); }

    /**
     * His month: duty days and hours, extra days (on duty outside his own turn), days off from his turn (leave, or someone
     * did it), what each change was, and festivals / holidays he worked.
     */
    static JSONObject report(Context c, Roster r, java.time.YearMonth ym) throws Exception {
        Batch mine = r.myBatch();
        int days = 0;
        JSONArray extra = new JSONArray(), off = new JSONArray(), festivals = new JSONArray(), dutyDays = new JSONArray();
        List<Holidays.Day> hols = Holidays.between(c, ym.atDay(1), ym.atEndOfMonth());
        for (LocalDate d = ym.atDay(1); !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
            boolean on = r.isOn(ME, d), base = r.baseOn(mine, d);
            Change ch = r.change(d, ME);
            String note = ch == null ? "" : ch.note;
            if (on) {
                days++;
                dutyDays.put(d.getDayOfMonth());
                for (Holidays.Day h : hols) if (h.date.equals(d) && h.big()) festivals.put(day(d) + ": " + h.name);
            }
            if (on && !base) extra.put(day(d) + (note.isEmpty() ? "" : " (" + note + ")"));
            if (!on && base) off.put(day(d) + (note.isEmpty() ? "" : " (" + note + ")"));
        }
        int regular = 0;
        for (LocalDate d = ym.atDay(1); !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) if (r.baseOn(mine, d)) regular++;
        return new JSONObject().put("ok", true).put("month", monthName(ym.getMonthValue()) + " " + ym.getYear())
                .put("duty_days", days).put("hours", days * 24).put("his_regular_turn_days", regular)
                .put("extra_days", extra.length()).put("extra", extra)
                .put("days_off_from_his_turn", off.length()).put("off", off)
                .put("worked_on_festivals", festivals).put("dates", dutyDays)
                .put("note", ym.isAfter(java.time.YearMonth.now()) ? "A future month: from the plan as it stands." :
                        ym.equals(java.time.YearMonth.now()) ? "This month: the rest of it is from the plan." : "");
    }

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
        try { if (chimeHours(c) != null) scheduleChime(c); } catch (Exception ignored) {} // kept armed (an alarm lost to a restart comes back)
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
                String bag = checklist(c);
                String base = day(s) + ", " + when + ". ఇంటి నుంచి " + go + " కల్లా బయలుదేరండి." + (bag.isEmpty() ? "" : "\n🎒 బ్యాగ్: " + bag);
                int id = ("eve" + s).hashCode();
                notify(c, id, "🗓️ రేపు మీ డ్యూటీ", base);
                if (!quiet) Announcer.say(c, p.name() + ", రేపు మీ డ్యూటీ. " + t + " కి రిలీవ్ చేయాలి, " + go + " కల్లా బయలుదేరండి."
                        + (bag.isEmpty() ? "" : " బ్యాగ్ ఇప్పుడే సర్దుకోండి: " + bag + "."));
                tripLater(c, r, leave, start, true, id, "🗓️ రేపు మీ డ్యూటీ", base, quiet);
            }
            if (!now.isBefore(leave.minusMinutes(60)) && now.isBefore(leave) && once(c, "day|" + s)) {
                String bag = checklist(c);
                String base = when + ". అన్నీ సిద్ధం చేసుకోండి." + (bag.isEmpty() ? "" : "\n🎒 " + bag);
                int id = ("day" + s).hashCode();
                notify(c, id, "🗓️ ఈరోజు డ్యూటీ, " + go + " కల్లా బయలుదేరండి", base);
                Announcer.say(c, p.name() + ", ఈరోజు డ్యూటీ. " + t + " కి రిలీవ్ చేయాలి, " + go + " కల్లా బయలుదేరండి."
                        + (bag.isEmpty() ? "" : " మర్చిపోకండి: " + bag + "."));
                tripLater(c, r, leave, start, false, id, "🗓️ ఈరోజు డ్యూటీ, " + go + " కల్లా బయలుదేరండి", base, false);
            }
            restBefore(c, p, s, start, leave, hours, quiet);
            if (!now.isBefore(leave) && now.isBefore(start) && once(c, "go|" + s)) {
                notify(c, ("go" + s).hashCode(), "🏍️ బయలుదేరే టైమ్ అయింది", t + " కి రిలీవ్ చేయాలి.");
                Announcer.say(c, p.name() + ", బయలుదేరే టైమ్ అయింది. " + t + " కి రిలీవ్ చేయాలి. జాగ్రత్తగా వెళ్లండి.");
            }
        }
    }

    // ================================================================ sleep before a long duty

    static final String ACTION_NAP = "com.anil.jarvis.DUTY_NAP";

    static boolean restOn(Context c) { return sp(c).getBoolean("rest_before", true); }

    static void setRest(Context c, boolean on) { sp(c).edit().putBoolean("rest_before", on).apply(); }

    /**
     * Rested for a long duty: when it starts in the afternoon or at night, a nap that day (about an hour and a half,
     * up an hour before leaving), with a button for the nap alarm; when it starts in the morning, the evening before:
     * the time to be in bed for about 7.5 hours of sleep (by 11 at the latest).
     */
    private static void restBefore(Context c, Prefs p, LocalDate s, LocalDateTime start, LocalDateTime leave, long hours, boolean quiet) {
        if (!restOn(c)) return;
        LocalDateTime now = LocalDateTime.now();
        String t = hm(start);
        LocalDateTime nap = leave.minusMinutes(150);
        if (nap.getHour() >= 12 && nap.toLocalDate().equals(leave.toLocalDate())) { // leaving in the afternoon / evening / night: a nap first
            if (now.isBefore(nap) || !now.isBefore(nap.plusMinutes(45)) || !once(c, "nap|" + s)) return;
            String text = (start.toLocalDate().equals(nap.toLocalDate()) ? "ఈరోజు " : "ఈ రాత్రి ") + t + " కి డ్యూటీ, " + hours
                    + " గంటలు. ఇప్పుడు గంటన్నర కునుకు తీస్తే డ్యూటీలో అలసట తక్కువ. అలారం కావాలంటే కింద నొక్కండి, లేదా 'కునుకు అలారం పెట్టు' అనండి.";
            long leaveMs = leave.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            try {
                NotificationManager nm = c.getSystemService(NotificationManager.class);
                if (nm != null) {
                    nm.createNotificationChannel(new NotificationChannel("jarvis_duty", "డ్యూటీ రిమైండర్లు", NotificationManager.IMPORTANCE_HIGH));
                    PendingIntent alarm = PendingIntent.getBroadcast(c, 261, new Intent(c, AlarmReceiver.class).setAction(ACTION_NAP).putExtra("leave", leaveMs),
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    nm.notify("duty", 262, new Notification.Builder(c, "jarvis_duty").setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                            .setContentTitle("😴 డ్యూటీ ముందు కునుకు").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                            .setTimeoutAfter(Math.max(60_000L, leaveMs - 60 * 60_000L - System.currentTimeMillis())) // too late for a nap: gone
                            .setAutoCancel(true).addAction(new Notification.Action.Builder(null, "😴 కునుకు అలారం", alarm).build()).build());
                }
            } catch (Exception ignored) {}
            if (!quiet) Announcer.say(c, p.name() + ", " + text);
            return;
        }
        // leaving in the morning: in bed the night before
        LocalDateTime wake = leave.minusMinutes(60), bed = wake.minusMinutes(450), latest = wake.toLocalDate().minusDays(1).atTime(23, 0);
        if (bed.isAfter(latest)) bed = latest;
        LocalDateTime remind = bed.minusMinutes(30);
        if (remind.getHour() < 19 || now.isBefore(remind) || !now.isBefore(remind.plusMinutes(45)) || !once(c, "bed|" + s)) return;
        String text = "రేపు " + t + " కి డ్యూటీ, " + hours + " గంటలు. " + hm(wake) + " కి లేవాలంటే " + hm(bed)
                + " కల్లా పడుకోండి; డ్యూటీ ముందు నిద్ర బాగుంటే రెండు రోజులూ అలసట తక్కువ.";
        notify(c, ("bed" + s).hashCode(), "😴 డ్యూటీ ముందు నిద్ర", text);
        if (!quiet) Announcer.say(c, p.name() + ", " + text);
    }

    // ================================================================ travel check: rain on the way, bike charge

    static final String[] DUTY_PLACE = {"డ్యూటీ", "duty", "ఆఫీస్", "ఆఫీసు", "office"};
    static final String[] HOME_PLACE = {"ఇల్లు", "ఇంటి", "home", "house"};
    private static final String[] NOT_IT = {"ఛార్జ", "charg", "post", "పోస్ట్", "bank", "బ్యాంక్"};

    static JSONObject placeLike(Context c, String[] words) {
        for (JSONObject p : Places.all(c)) {
            if (!p.has("lat")) continue;
            String n = p.optString("name").toLowerCase(Locale.ROOT);
            boolean skip = false;
            for (String x : NOT_IT) if (n.contains(x)) skip = true;
            if (skip) continue;
            for (String w : words) if (n.contains(w)) return p;
        }
        return null;
    }

    /** The reminder goes out at once; rain and bike charge follow on their own thread and fill the same notification. */
    private static void tripLater(Context c, Roster r, LocalDateTime leave, LocalDateTime start, boolean evening, int id, String title, String base, boolean quiet) {
        new Thread(() -> {
            String trip = tripCheck(c, r, leave, start, evening);
            if (trip.isEmpty()) return;
            notify(c, id, title, base + "\n" + trip);
            if (!quiet && !CallControl.busyWithCall()) Announcer.say(c, trip);
        }, "duty-trip").start();
    }

    private static double km(double la1, double lo1, double la2, double lo2) {
        double r = 6371, dla = Math.toRadians(la2 - la1), dlo = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dla / 2) * Math.sin(dla / 2) + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.sin(dlo / 2) * Math.sin(dlo / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    /** One way home -> duty in km: what he told, or from his saved places (straight line x 1.3 for the road); 0 = not known. */
    static int tripKm(Context c, Roster r) {
        if (r.tripKm > 0) return r.tripKm;
        JSONObject home = placeLike(c, HOME_PLACE), duty = placeLike(c, DUTY_PLACE);
        if (home == null || duty == null) return 0;
        return (int) Math.round(km(home.optDouble("lat"), home.optDouble("lon"), duty.optDouble("lat"), duty.optDouble("lon")) * 1.3);
    }

    /** Rain chance (max %) and mm between these times, at this place; null if the forecast could not be had. */
    private static double[] rain(double lat, double lon, LocalDateTime from, LocalDateTime to) {
        try {
            java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(String.format(Locale.ENGLISH,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                    + "&hourly=precipitation_probability,precipitation&timezone=auto&forecast_days=3", lat, lon)).openConnection();
            con.setConnectTimeout(8000); // a reminder is waiting on this: short timeouts
            con.setReadTimeout(8000);
            JSONObject w;
            try (java.io.InputStream in = con.getInputStream()) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                w = new JSONObject(buf.toString("UTF-8"));
            } finally {
                con.disconnect();
            }
            JSONObject h = w.getJSONObject("hourly");
            JSONArray times = h.getJSONArray("time"), prob = h.getJSONArray("precipitation_probability"), mm = h.getJSONArray("precipitation");
            LocalDateTime a = from.withMinute(0).withSecond(0).withNano(0);
            double maxP = -1, sum = 0;
            for (int i = 0; i < times.length(); i++) {
                LocalDateTime t = LocalDateTime.parse(times.getString(i));
                if (t.isBefore(a) || t.isAfter(to)) continue;
                maxP = Math.max(maxP, prob.optDouble(i, 0));
                sum += mm.optDouble(i, 0);
            }
            return maxP < 0 ? null : new double[]{maxP, sum};
        } catch (Exception e) {
            return null;
        }
    }

    private static String hm(LocalDateTime t) { return String.format(Locale.ENGLISH, "%d:%02d", t.getHour(), t.getMinute()); }

    /**
     * Before going to duty, in short Telugu: rain on the way (at home and at duty if saved) and whether the bike's
     * charge is enough to go and come back. Empty when nothing is known. evening = said the night before (charge tonight).
     */
    static String tripCheck(Context c, Roster r, LocalDateTime leave, LocalDateTime start, boolean evening) {
        StringBuilder out = new StringBuilder();
        try {
            JSONObject home = placeLike(c, HOME_PLACE), duty = placeLike(c, DUTY_PLACE);
            double lat, lon;
            if (home != null) { lat = home.optDouble("lat"); lon = home.optDouble("lon"); }
            else {
                android.location.Location l = Tools.lastLocation(c);
                if (l == null) { lat = Double.NaN; lon = Double.NaN; } else { lat = l.getLatitude(); lon = l.getLongitude(); }
            }
            double[] w = Double.isNaN(lat) ? null : rain(lat, lon, leave, start);
            if (duty != null) {
                double[] w2 = rain(duty.optDouble("lat"), duty.optDouble("lon"), leave, start);
                if (w2 != null && (w == null || w2[0] > w[0])) w = w2;
            }
            if (w != null) {
                String span = hm(leave) + "–" + hm(start);
                if (w[0] >= 60 || w[1] >= 5) out.append("దారిలో (").append(span).append(") వర్షం పడే అవకాశం ").append(Math.round(w[0])).append("%")
                        .append(w[1] >= 5 ? ", భారీగా పడొచ్చు. రెయిన్‌కోట్ తీసుకెళ్లండి, నెమ్మదిగా వెళ్లండి. " : ". రెయిన్‌కోట్ తీసుకెళ్లండి. ");
                else if (w[0] >= 30) out.append("దారిలో (").append(span).append(") చిన్న జల్లులు పడొచ్చు (").append(Math.round(w[0])).append("%). ");
                else out.append("దారిలో వర్షం సూచన లేదు. ");
            }
        } catch (Exception ignored) {}
        try {
            int pct = Bike.estimatePct(c), one = tripKm(c, r);
            if (pct >= 0) {
                Prefs p = new Prefs(c);
                int full = Bike.fullRangeKm(p);
                long range = Math.round(full * pct / 100.0), usable = Math.round(full * (pct - 10) / 100.0);
                if (one > 0) {
                    int need = one * 2;
                    if (usable < need) out.append("బైక్‌లో సుమారు ").append(pct).append("% (").append(range).append(" కి.మీ) ఉంది, వెళ్లి రావడానికి ")
                            .append(need).append(" కి.మీ కావాలి. ").append(evening ? "ఈ రాత్రే ఛార్జ్ పెట్టండి." : "బయలుదేరే ముందు ఛార్జ్ పెట్టండి.");
                    else if (usable < need * 1.25) out.append("బైక్ ఛార్జ్ (సుమారు ").append(pct).append("%) సరిపోతుంది, కానీ మార్జిన్ తక్కువ; Eco మోడ్‌లో వెళ్లండి.");
                    else out.append("బైక్ ఛార్జ్ (సుమారు ").append(pct).append("%) వెళ్లి రావడానికి సరిపోతుంది.");
                } else {
                    out.append("బైక్‌లో సుమారు ").append(pct).append("% (").append(range).append(" కి.మీ) ఉంది.");
                }
            }
        } catch (Exception ignored) {}
        return out.toString().trim();
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

    // ================================================================ the duty bag

    /** What he takes to duty ("యూనిఫాం, ID కార్డ్, ఛార్జర్"), said the evening before and before leaving. */
    static String checklist(Context c) { return sp(c).getString("bag", ""); }

    static void setChecklist(Context c, String items) {
        String t = items == null ? "" : items.trim();
        String low = t.toLowerCase(Locale.ROOT);
        if (low.equals("off") || low.equals("clear") || t.contains("వద్దు") || t.contains("తీసేయ")) t = "";
        sp(c).edit().putString("bag", t.replaceAll("\\s*[,،;]\\s*", ", ")).apply();
    }

    // ================================================================ the night chime on duty

    static final String ACTION_CHIME = "com.anil.jarvis.DUTY_CHIME";

    /** {from hour, to hour} of the chime on duty nights, or null (off). */
    static int[] chimeHours(Context c) {
        String h = sp(c).getString("chime", "");
        if (h.isEmpty()) return null;
        String[] p = h.split("-");
        try { return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])}; } catch (Exception e) { return null; }
    }

    /** "22:00-05:00" / "22-5" / "off". Returns an error line or "". */
    static String setChime(Context c, String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.equals("off") || t.contains("వద్దు") || t.contains("ఆపు")) {
            sp(c).edit().remove("chime").apply();
            scheduleChime(c);
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})(?::\\d{2})?\\s*(?:-|to|నుంచి|నుండి)\\s*(\\d{1,2})").matcher(t);
        if (!m.find()) return "Give the hours as 'HH:00-HH:00', e.g. 22:00-05:00.";
        int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
        if (a > 23 || b > 23 || a == b) return "Hours 0-23, two different hours, e.g. 22:00-05:00.";
        sp(c).edit().putString("chime", a + "-" + b).apply();
        scheduleChime(c);
        return "";
    }

    private static boolean inHours(int h, int[] w) { return w[0] < w[1] ? h >= w[0] && h < w[1] : h >= w[0] || h < w[1]; }

    /** Is he on duty at this moment (between relieving the others and being relieved)? */
    static boolean onDutyAt(Roster r, LocalDateTime t) {
        String time = r.timeOf(ME);
        String[] hm = time.split(":");
        LocalTime st = LocalTime.of(Integer.parseInt(hm[0]), Integer.parseInt(hm[1]));
        for (LocalDate[] b : r.blocks(ME, t.toLocalDate().minusDays(3), t.toLocalDate().plusDays(1))) {
            LocalDateTime from = b[0].atTime(st), to = b[1].plusDays(1).atTime(st);
            if (!t.isBefore(from) && t.isBefore(to)) return true;
        }
        return false;
    }

    /** The next whole hour inside his chime hours while he is on duty: an exact alarm. */
    static void scheduleChime(Context c) {
        android.app.AlarmManager am = c.getSystemService(android.app.AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 201, new Intent(c, AlarmReceiver.class).setAction(ACTION_CHIME),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
        int[] w = chimeHours(c);
        Roster r = load(c);
        if (w == null || !ready(r)) return;
        LocalDateTime t = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0).plusHours(1);
        for (int k = 0; k < 24 * 8; k++, t = t.plusHours(1)) {
            if (!inHours(t.getHour(), w) || !onDutyAt(r, t)) continue;
            long when = t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            try {
                if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, when, pi);
                else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, when, pi);
            } catch (Exception e) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, when, pi);
            }
            return;
        }
    }

    /** The hour struck: a soft chime and the time (not over a call). */
    static void chime(Context c) {
        try { scheduleChime(c); } catch (Exception ignored) {}
        if (CallControl.busyWithCall()) return;
        try {
            android.media.ToneGenerator g = new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 70);
            g.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 400);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(g::release, 1500);
        } catch (Exception ignored) {}
        int h = LocalTime.now().getHour(), m = LocalTime.now().getMinute() < 5 ? 0 : LocalTime.now().getMinute();
        // "02:00 am" / "22:00": the voice says "రాత్రి రెండు గంటలు", "రాత్రి పది గంటలు"
        String t = h >= 13 || h == 0 ? String.format(Locale.ENGLISH, "%02d:%02d", h, m) : String.format(Locale.ENGLISH, "%d:%02d %s", h, m, h < 12 ? "am" : "pm");
        Announcer.say(c, "సమయం " + t + ".");
    }

    // ================================================================ just off a 48-hour duty

    /** Minutes since his last duty ended, if it ended within the last 8 hours; -1 otherwise. */
    /** His duty going on now {start, end, now = start}, or the next one {start, end}; null when not set up / none soon. */
    static LocalDateTime[] nowOrNext(Context c) {
        Roster r = load(c);
        if (!ready(r)) return null;
        String[] hm = r.timeOf(ME).split(":");
        int h = Integer.parseInt(hm[0]), m = Integer.parseInt(hm[1]);
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        for (LocalDate[] b : r.blocks(ME, today.minusDays(3), today.plusDays(12))) {
            LocalDateTime from = b[0].atTime(h, m), to = b[1].plusDays(1).atTime(h, m);
            if (to.isAfter(now)) return new LocalDateTime[]{from, to};
        }
        return null;
    }

    static long minutesSinceDuty(Context c) {
        Roster r = load(c);
        if (!ready(r)) return -1;
        String[] hm = r.timeOf(ME).split(":");
        LocalTime st = LocalTime.of(Integer.parseInt(hm[0]), Integer.parseInt(hm[1]));
        LocalDateTime now = LocalDateTime.now();
        long best = -1;
        for (LocalDate[] b : r.blocks(ME, now.toLocalDate().minusDays(3), now.toLocalDate())) {
            LocalDateTime end = b[1].plusDays(1).atTime(st);
            if (end.isAfter(now)) continue;
            long m = java.time.Duration.between(end, now).toMinutes();
            if (m <= 8 * 60 && (best < 0 || m < best)) best = m;
        }
        return best;
    }
}
