package com.anil.jarvis;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Notices the car/bike's Bluetooth connecting and disconnecting. */
public class CarReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (i == null || i.getAction() == null) return;
        BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (d == null) return;
        boolean connected = BluetoothDevice.ACTION_ACL_CONNECTED.equals(i.getAction());
        boolean disconnected = BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(i.getAction());
        if (!connected && !disconnected) return;
        PendingResult pr = goAsync();
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try { Life.carBluetooth(app, d.getAddress(), connected); } catch (Throwable ignored) {} finally { pr.finish(); }
        }, "jarvis-car").start();
    }
}
