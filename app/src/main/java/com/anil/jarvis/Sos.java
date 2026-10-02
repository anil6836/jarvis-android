package com.anil.jarvis;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.os.Build;
import android.provider.ContactsContract;
import android.telephony.SmsManager;

import java.util.ArrayList;
import java.util.List;

/** The SOS SMS with his location, sent without Jarvis's screen (used when he doesn't answer after a crash). */
final class Sos {
    private Sos() {}

    /** Sends to his SOS contacts (names in his contacts, or numbers). Returns {name, number} of each one sent to. */
    static List<String[]> send(Context c, String what) {
        List<String[]> sent = new ArrayList<>();
        Prefs p = new Prefs(c);
        String list = p.sosContacts().trim();
        if (list.isEmpty() || c.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return sent;
        Location l = DriveService.last;
        if (l == null || System.currentTimeMillis() - l.getTime() > 10 * 60000L) l = Tools.lastLocation(c);
        String where = l == null ? "(లొకేషన్ దొరకలేదు)" : "https://maps.google.com/?q=" + l.getLatitude() + "," + l.getLongitude();
        String text = "🆘 " + p.name() + " కి సహాయం కావాలి. " + (what == null || what.isEmpty() ? "" : what + ". ") + "లొకేషన్: " + where;
        SmsManager sm = Build.VERSION.SDK_INT >= 31 ? c.getSystemService(SmsManager.class) : SmsManager.getDefault();
        for (String who : list.split(",")) {
            String w = who.trim();
            if (w.isEmpty()) continue;
            String[] t = number(c, w);
            if (t == null) continue;
            try {
                sm.sendMultipartTextMessage(t[1], null, sm.divideMessage(text), null, null);
                sent.add(t);
            } catch (Exception ignored) {}
        }
        return sent;
    }

    /** {name, number} for a saved SOS entry: a number as it is, else the first contact whose name has it. */
    static String[] number(Context c, String who) {
        String digits = who.replaceAll("[^0-9+]", "");
        if (digits.replace("+", "").length() >= 10) return new String[]{who, digits};
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null;
        String[] best = null;
        int bestRank = 9;
        String w = who.toLowerCase(java.util.Locale.ROOT);
        try (Cursor cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", new String[]{"%" + who + "%"},
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY + " DESC")) {
            while (cur != null && cur.moveToNext()) {
                String n = cur.getString(0) == null ? "" : cur.getString(0).toLowerCase(java.util.Locale.ROOT);
                // the same name first, then one that starts with it ("Amma" before "Ammamma" / "Ramma")
                int rank = n.equals(w) ? 0 : n.startsWith(w + " ") ? 1 : n.startsWith(w) ? 2 : 3;
                if (rank < bestRank) { bestRank = rank; best = new String[]{cur.getString(0), cur.getString(1)}; }
            }
        } catch (Exception ignored) {}
        return best;
    }
}
