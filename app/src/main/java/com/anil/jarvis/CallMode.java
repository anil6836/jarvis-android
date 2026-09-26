package com.anil.jarvis;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

/**
 * While Jarvis speaks with "talk over" on, its voice goes through the phone's call path (like Live mode
 * and phone calls). Only then does the phone's echo canceller really remove Jarvis's own voice from the
 * mic, so Anil's voice can be told apart. Loudspeaker stays on; call volume is matched to media volume
 * and everything is put back when Jarvis stops.
 */
final class CallMode {
    private final AudioManager am;
    private boolean on;
    private int oldMode = AudioManager.MODE_NORMAL;
    private int oldCallVol = -1;

    CallMode(Context c) { am = (AudioManager) c.getApplicationContext().getSystemService(Context.AUDIO_SERVICE); }

    boolean active() { return on; }

    static boolean headset(AudioManager am) {
        if (am == null) return false;
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET || t == 26 /* BLE headset */) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Switches to the call path; false when not needed (headphones) or not possible (a real call). */
    boolean enter() {
        if (on) return true;
        if (am == null || headset(am)) return false;
        try {
            if (am.getMode() != AudioManager.MODE_NORMAL) return false; // a real call or another app owns the audio
            oldMode = am.getMode();
            int music = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int musicMax = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int callMax = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            oldCallVol = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
            on = true;
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            if (Build.VERSION.SDK_INT >= 31) {
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { am.setCommunicationDevice(d); break; }
                }
            } else {
                am.setSpeakerphoneOn(true);
            }
            int want = Math.max(1, Math.round(music * callMax / (float) musicMax));
            if (want != oldCallVol) {
                try { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, want, 0); } catch (Exception ignored) {}
            }
            return true;
        } catch (Exception e) {
            exit();
            return false;
        }
    }

    void exit() {
        if (!on) return;
        on = false;
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
            else am.setSpeakerphoneOn(false);
        } catch (Exception ignored) {}
        try { am.setMode(oldMode); } catch (Exception ignored) {}
        if (oldCallVol >= 0) {
            try { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, oldCallVol, 0); } catch (Exception ignored) {}
            oldCallVol = -1;
        }
    }
}
