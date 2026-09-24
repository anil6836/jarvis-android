package com.anil.jarvis;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** A Quick Settings tile: swipe down, tap "Jarvis", and it starts listening (the mic turns on only then). */
public class JarvisTile extends TileService {
    @Override public void onStartListening() {
        Tile t = getQsTile();
        if (t != null) {
            t.setState(Tile.STATE_INACTIVE);
            t.updateTile();
        }
    }

    @Override public void onClick() {
        Intent i = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_WAKE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 11, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapseLegacy(i);
        }
    }

    @SuppressWarnings("deprecation")
    private void startActivityAndCollapseLegacy(Intent i) {
        startActivityAndCollapse(i);
    }
}
