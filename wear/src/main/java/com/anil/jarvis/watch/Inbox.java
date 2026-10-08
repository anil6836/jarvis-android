package com.anil.jarvis.watch;

import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.WearableListenerService;

/** Messages from the phone's Jarvis (paths /jarvis/...) arrive here and go to Talk; and the phone coming / going (W20). */
public class Inbox extends WearableListenerService {
    @Override public void onMessageReceived(MessageEvent e) {
        Talk.onMessage(this, e.getSourceNodeId(), e.getPath(), e.getData());
    }

    /** The phone's Jarvis came into / went out of reach (its "jarvis_phone" capability). */
    @Override public void onCapabilityChanged(CapabilityInfo info) {
        boolean near = false;
        if (info != null && info.getNodes() != null) for (Node n : info.getNodes()) if (n.isNearby()) { near = true; break; }
        Beat.lostCheck(getApplicationContext(), near);
    }
}
