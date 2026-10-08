package com.anil.jarvis;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

/** Messages from the Jarvis watch app arrive here (paths /jarvis/...), and go to WatchHub. */
public class WatchLink extends WearableListenerService {
    @Override public void onMessageReceived(MessageEvent e) {
        WatchHub.onMessage(this, e.getSourceNodeId(), e.getPath(), e.getData());
    }
}
