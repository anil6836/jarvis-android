package com.anil.jarvis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/** Wakes up for reminders, the morning briefing, and after reboots or app updates. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String action = i == null ? null : i.getAction();
        if (action == null) return;
        switch (action) {
            case Reminders.ACTION_FIRE: {
                String id = i.getStringExtra("id");
                Store s = Store.get(c);
                JSONObject r = null;
                for (JSONObject x : s.reminders()) if (x.optString("id").equals(id)) r = x;
                if (r == null || r.optBoolean("done")) return;
                s.markReminderDone(id);
                String text = r.optString("text");
                Reminders.notify(c, "⏰ Jarvis రిమైండర్", text, id == null ? 1 : id.hashCode());
                PendingResult pr = goAsync();
                Announcer.say(c, new Prefs(c).name() + ", గుర్తుచేస్తున్నాను: " + text);
                new Handler(Looper.getMainLooper()).postDelayed(pr::finish, 9000);
                break;
            }
            case GeoReminders.ACTION_GEO: {
                boolean entering = i.getBooleanExtra(android.location.LocationManager.KEY_PROXIMITY_ENTERING, false);
                PendingResult pr = goAsync();
                GeoReminders.fired(c, i.getStringExtra("id"), entering);
                new Handler(Looper.getMainLooper()).postDelayed(pr::finish, 9000);
                break;
            }
            case Reminders.ACTION_BRIEFING:
                BriefingService.start(c);
                Reminders.scheduleBriefing(c); // tomorrow
                break;
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
            case Intent.ACTION_TIME_CHANGED:
            case Intent.ACTION_TIMEZONE_CHANGED:
                Reminders.rescheduleAll(c);
                GeoReminders.rearmAll(c);
                break;
            default:
                break;
        }
    }
}
