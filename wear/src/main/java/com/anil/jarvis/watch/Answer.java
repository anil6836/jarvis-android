package com.anil.jarvis.watch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/** His tap on a notification's button: "are you sure?" (✓ / ✗), an alert's button, or a word for the phone ("చదువు"). */
public class Answer extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        try {
            if (i.hasExtra("alert")) { // the same button on the phone's notification
                Link.send(c, Link.P_ALERT_ACTION, new JSONObject().put("id", i.getIntExtra("alert", 0)).put("i", i.getIntExtra("i", -1))
                        .put("key", i.getStringExtra("key")));
                Alerts.gone(c, i.getIntExtra("alert", 0));
                return;
            }
            if (i.hasExtra("say")) { // as if he had said it
                Talk.saidByTap(c, i.getStringExtra("say"), i.getStringExtra("why"));
                Alerts.gone(c, i.getIntExtra("note", 0));
                return;
            }
        } catch (Exception ignored) {}
        Talk.answer(c, i.getIntExtra("id", -1), i.getBooleanExtra("yes", false));
    }
}
