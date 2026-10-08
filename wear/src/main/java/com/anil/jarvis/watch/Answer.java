package com.anil.jarvis.watch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** His tap on "✓" / "✗" in an "are you sure?" notification. */
public class Answer extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Talk.answer(c, i.getIntExtra("id", -1), i.getBooleanExtra("yes", false));
    }
}
