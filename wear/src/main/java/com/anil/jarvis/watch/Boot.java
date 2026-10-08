package com.anil.jarvis.watch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The watch restarted (or Jarvis was updated): Android lets the mic listen in the background only after the app has
 * been opened, so a small note asks him to open Jarvis once (only when wrist-raise or hours listening is on).
 */
public class Boot extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Beat.schedule(c); // ("still here" to the phone: alarms are cleared by a restart)
        if (!Link.raise(c) && !Link.hours(c)) return;
        Talk.listenBroken = true;
        Notes.broken(c, null);
    }
}
