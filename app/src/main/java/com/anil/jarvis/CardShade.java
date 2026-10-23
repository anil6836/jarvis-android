package com.anil.jarvis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * The top card's notification in the shade (Anil, 23 Oct: swiped up, the card keeps running down there): its buttons,
 * a tap on it (the card back on the screen) and swiping it away (the card closes) come here; TopCard does them.
 */
public class CardShade extends BroadcastReceiver {
    static final String ACTION = "com.anil.jarvis.CARD_SHADE";
    static final String OP_BUTTON = "button", OP_SHOW = "show", OP_GONE = "gone";
    /** 📬's own note in the shade: all read, or the list. */
    static final String OP_LATER_ALL = "later_all", OP_LATER_LIST = "later_list";

    @Override public void onReceive(Context c, Intent i) {
        if (i == null || !ACTION.equals(i.getAction())) return;
        final String op = i.getStringExtra("op");
        final int ver = i.getIntExtra("ver", -1), idx = i.getIntExtra("idx", -1);
        final Context app = c.getApplicationContext();
        if (OP_LATER_ALL.equals(op) || OP_LATER_LIST.equals(op)) { new Handler(Looper.getMainLooper()).post(() -> LaterBox.action(app, op)); return; }
        new Handler(Looper.getMainLooper()).post(() -> TopCard.shade(app, op, ver, idx));
    }
}
