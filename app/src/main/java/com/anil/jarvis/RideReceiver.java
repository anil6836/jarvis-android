package com.anil.jarvis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;

/** GPS points while the bike is connected (asked for by Bike.rideStart); adds up the ride's km. */
public class RideReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (i == null) return;
        try {
            Location l = i.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED);
            if (l != null) Bike.onLocation(c.getApplicationContext(), l);
        } catch (Exception ignored) {}
    }
}
