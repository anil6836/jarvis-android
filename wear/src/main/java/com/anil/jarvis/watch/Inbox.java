package com.anil.jarvis.watch;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

/** Messages from the phone's Jarvis (paths /jarvis/...) arrive here and go to Talk. */
public class Inbox extends WearableListenerService {
    @Override public void onMessageReceived(MessageEvent e) {
        Talk.onMessage(this, e.getSourceNodeId(), e.getPath(), e.getData());
    }
}
