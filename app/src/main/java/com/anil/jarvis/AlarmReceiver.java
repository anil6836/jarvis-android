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
                String repeat = r.optString("repeat", "");
                if (repeat.equals("daily") || repeat.equals("weekly")) {
                    // medicine and other repeating reminders: move to the next time instead of finishing
                    long next = r.optLong("at");
                    long step = repeat.equals("daily") ? 86400000L : 7 * 86400000L;
                    while (next <= System.currentTimeMillis()) next += step;
                    JSONObject moved = s.updateReminder(id, "at", next);
                    if (moved != null) Reminders.schedule(c, moved);
                } else {
                    s.markReminderDone(id);
                }
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
            case Medicine.ACTION_DUE:
            case Medicine.ACTION_SNOOZE:
            case Medicine.ACTION_CHECK:
            case Medicine.ACTION_TAKEN: {
                PendingResult pr = goAsync(); // time for Jarvis to say it
                try { Medicine.onAlarm(c, action, i.getStringExtra("id"), i.getStringExtra("time")); } catch (Exception ignored) {}
                new Handler(Looper.getMainLooper()).postDelayed(pr::finish, Medicine.ACTION_TAKEN.equals(action) ? 500 : 9000);
                break;
            }
            case StopAlarm.ACTION_OFF: StopAlarm.stoppedFromNotification(c); break;
            case Duty.ACTION_NAP: { // up 60 minutes before leaving at the latest; 90 minutes at most
                long leave = i.getLongExtra("leave", 0);
                int min = leave <= 0 ? 90 : (int) Math.min(90, (leave - 60 * 60_000L - System.currentTimeMillis()) / 60_000L);
                String said;
                if (min < 20) said = "కునుకుకి టైమ్ లేదు, డ్యూటీకి బయలుదేరే టైమ్ దగ్గర పడింది.";
                else {
                    try {
                        SongAlarm.nap(c, min);
                        said = "😴 " + min + " నిమిషాల తర్వాత అలారం";
                    } catch (Exception e) { said = "అలారం పెట్టలేకపోయాను. Jarvis కి 'కునుకు అలారం పెట్టు' అని చెప్పండి."; }
                }
                android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
                if (nm != null) nm.cancel("duty", 262);
                android.widget.Toast.makeText(c, said, android.widget.Toast.LENGTH_LONG).show();
                break;
            }
            case Bike.ACTION_CHARGE_DONE: {
                PendingResult pr = goAsync(); // time for Jarvis to say it
                try { Bike.chargeDone(c); } catch (Exception ignored) {}
                new Handler(Looper.getMainLooper()).postDelayed(pr::finish, 9000);
                break;
            }
            case CrashAlert.ACTION_OK: CrashAlert.ok(c); break;
            case RideCare.ACTION_AWAKE: RideCare.askAwake(c); break;
            case RideCare.ACTION_REACHED: {
                PendingResult pr = goAsync(); // the SMS goes out before the receiver lets go
                RideCare.sendFromNotification(c, pr::finish);
                break;
            }
            case RideCare.ACTION_AWAKE_OK: RideCare.awake(c); break;
            case RideCare.ACTION_REACHED_NO: RideCare.dismissReached(c); break;
            case Duty.ACTION_CHIME: {
                PendingResult pr = goAsync();
                Duty.chime(c);
                new Handler(Looper.getMainLooper()).postDelayed(pr::finish, 6000);
                break;
            }
            case Faith.ACTION_VERSE:
            case Faith.ACTION_PLAN:
            case Faith.ACTION_CHURCH: {
                PendingResult pr = goAsync(); // the verse is fetched and said
                Faith.fire(c, action);
                new Handler(Looper.getMainLooper()).postDelayed(pr::finish, 9000);
                break;
            }
            case Cook.ACTION_TIMER: {
                PendingResult pr = goAsync();
                Cook.timerUp(c, i.getStringExtra("label"));
                new Handler(Looper.getMainLooper()).postDelayed(pr::finish, 9000);
                break;
            }
            case CrashAlert.ACTION_SEND: CrashAlert.send(c, true); break;
            case SongAlarm.ACTION_RING:
                SongAlarm.fire(c, i.getStringExtra(SongAlarm.EXTRA_ID), i.getIntExtra(SongAlarm.EXTRA_COUNT, 0));
                break;
            case SongAlarm.ACTION_STOP: {
                SongAlarm.clearRinging(c);
                AlarmActivity.stopRinging();
                JSONObject al = SongAlarm.find(c, i.getStringExtra(SongAlarm.EXTRA_ID));
                if (al != null && al.optBoolean("nap")) AlarmActivity.napWake(c.getApplicationContext(), al.optInt("nap_minutes"));
                else AlarmActivity.greet(c.getApplicationContext());
                break;
            }
            case SongAlarm.ACTION_SNOOZE:
                SongAlarm.clearRinging(c);
                AlarmActivity.stopRinging();
                SongAlarm.snooze(c, i.getStringExtra(SongAlarm.EXTRA_ID), 5, i.getIntExtra(SongAlarm.EXTRA_COUNT, 0));
                break;
            case Exercise.ACTION_STOP:
                Exercise.stop(c);
                break;
            case Reminders.ACTION_BRIEFING:
                BriefingService.start(c);
                Reminders.scheduleBriefing(c); // tomorrow
                break;
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
            case Intent.ACTION_TIME_CHANGED:
            case Intent.ACTION_TIMEZONE_CHANGED:
                Reminders.rescheduleAll(c);
                Medicine.rescheduleAll(c);
                try { SongAlarm.rescheduleAll(c); } catch (Exception ignored) {}
                MedicalId.update(c);
                GeoReminders.rearmAll(c);
                Proactive.schedule(c);
                Faith.schedule(c);
                try { Duty.scheduleChime(c); } catch (Exception ignored) {}
                try { Bike.rearm(c); } catch (Exception ignored) {}
                UpdateJob.schedule(c);
                if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) Updater.cancelNotice(c);
                break;
            case AppRadio.ACTION_PAUSE: // "30 నిమిషాలు" for a station playing in the Telugu Radios app
                if (SoundService.nowPlaying.isEmpty()) AppRadio.pauseNow(c);
                break;
            default:
                break;
        }
    }
}
