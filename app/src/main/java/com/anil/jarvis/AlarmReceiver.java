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
                if (Reminders.repeats(repeat)) {
                    // medicine and other repeating reminders: move to the next time instead of finishing
                    long next = Reminders.nextTime(r.optLong("at"), repeat, r.optInt("dom", 0), System.currentTimeMillis());
                    JSONObject moved = s.updateReminder(id, "at", next);
                    if (moved != null) Reminders.schedule(c, moved);
                } else {
                    s.markReminderDone(id);
                }
                String text = r.optString("text");
                Reminders.notify(c, "⏰ Jarvis రిమైండర్", text, id == null ? 1 : id.hashCode());
                if (DutyMode.on(c)) break; // at work: the notification (and the watch's buzz) only, not said aloud
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
            case CoughLog.ACTION_DOSE:
            case CoughLog.ACTION_DOSE_TAKEN:
            case CoughLog.ACTION_DOSE_LATER:
            case CoughLog.ACTION_CHECK: {
                PendingResult pr = goAsync(); // time for Jarvis to say it
                try { CoughLog.onAlarm(c, action, i.getStringExtra("id")); } catch (Exception ignored) {}
                new Handler(Looper.getMainLooper()).postDelayed(pr::finish, CoughLog.ACTION_DOSE_TAKEN.equals(action) ? 500 : 9000);
                break;
            }
            case Sounds.ACTION_COOKER_OK: Sounds.stopCooker(c); break;
            case SafetySounds.ACTION_CARE_NO: SafetySounds.cardNo(c); break;
            case Sounds.ACTION_NIGHT_EDGE: // 10 pm / 7 am: the mic starts / stops listening for the night
                WakeService.recheck(c);
                Sounds.armNightEdge(c);
                break;
            case StopAlarm.ACTION_OFF: StopAlarm.stoppedFromNotification(c); break;
            case Guard.ACTION_STOP: Guard.stop(c); break;
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
            case Automations.ACTION_FIRE: {
                PendingResult pr = goAsync();
                String id = i.getStringExtra("id");
                java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
                Runnable finishOnce = () -> { if (done.compareAndSet(false, true)) pr.finish(); };
                new Handler(Looper.getMainLooper()).postDelayed(finishOnce, 8000); // let go in time; the thread carries on
                new Thread(() -> { // the rain check (if the rule has one) uses the internet
                    try { Automations.fired(c.getApplicationContext(), id); } catch (Exception ignored) {}
                    new Handler(Looper.getMainLooper()).postDelayed(finishOnce, 3000); // time for the voice to start
                }, "jarvis-automation").start();
                break;
            }
            case Bike.ACTION_CHARGE_DONE: {
                PendingResult pr = goAsync(); // time for Jarvis to say it
                try { Bike.chargeDone(c); } catch (Exception ignored) {}
                new Handler(Looper.getMainLooper()).postDelayed(pr::finish, 9000);
                break;
            }
            case CrashAlert.ACTION_OK: CrashAlert.ok(c); break;
            case CrashAlert.ACTION_SOFT_OK: CrashAlert.softOk(c); break; // W80: fine after a knock only the phone felt
            case Music.ACTION_SLEEP: Music.sleepNow(c); break; // O46: the songs' sleep timer
            case HomeLink.ACTION_PLAY: HomeLink.playFromHome(c); break; // W65: the voice from home
            case HomeArrival.ACTION_GEO: HomeArrival.crossed(c, i); break; // W64: home / away
            case HomeArrival.ACTION_DO: HomeArrival.act(c, i.getStringExtra("do")); break;
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
            case CrashAlert.ACTION_SEND: if (CrashAlert.active) CrashAlert.send(c, true); else CrashAlert.sosNow(c, "సహాయం కావాలి"); break;
            case SongAlarm.ACTION_RING:
                SongAlarm.fire(c, i.getStringExtra(SongAlarm.EXTRA_ID), i.getIntExtra(SongAlarm.EXTRA_COUNT, 0));
                break;
            case WatchAlerts.ACTION_ALARM_LOUD: // the watch's alarm wasn't answered in 3 minutes: the phone rings
                WatchAlerts.loudNow(c, i.getStringExtra(SongAlarm.EXTRA_ID));
                SongAlarm.fire(c, i.getStringExtra(SongAlarm.EXTRA_ID), i.getIntExtra(SongAlarm.EXTRA_COUNT, 0), true);
                break;
            case DutyMode.ACTION_END: // the duty in his calendar is over
                DutyMode.off(c, true);
                break;
            case WatchAlerts.ACTION_INFO: { // the watch's news every half hour (duty, weather, where the phone is); best effort
                final android.content.Context app = c.getApplicationContext();
                new Thread(() -> WatchAlerts.pushInfo(app), "watch-info").start();
                break;
            }
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
                Stopwatch.restore(c);
                DutyMode.restore(c);
                try { CoughLog.rearm(c); } catch (Exception ignored) {}
                Sounds.armNightEdge(c);
                try { SongAlarm.rescheduleAll(c); } catch (Exception ignored) {}
                MedicalId.update(c);
                GeoReminders.rearmAll(c);
                try { HomeArrival.arm(c); } catch (Exception ignored) {} // W64
                Proactive.schedule(c);
                Faith.schedule(c);
                try { Duty.scheduleChime(c); } catch (Exception ignored) {}
                try { Bike.rearm(c); } catch (Exception ignored) {}
                try { Automations.schedule(c); } catch (Exception ignored) {}
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
