package com.anil.jarvis;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Home-screen widget: one tap to talk to Jarvis, with the time, battery and next reminder. */
public class JarvisWidget extends AppWidgetProvider {

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, views(c));
    }

    /** Refreshes every Jarvis widget (after reminders change, from the periodic check...). */
    static void refresh(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, JarvisWidget.class));
            if (ids.length > 0) for (int id : ids) m.updateAppWidget(id, views(c));
        } catch (Exception ignored) {}
    }

    private static RemoteViews views(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_jarvis);
        Prefs p = new Prefs(c);
        Intent talk = p.compactPanel()
                ? new Intent(c, SheetActivity.class)
                : new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_WAKE, true);
        talk.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 90, talk, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.widget_root, pi);

        StringBuilder info = new StringBuilder();
        BatteryManager bm = c.getSystemService(BatteryManager.class);
        if (bm != null) info.append("🔋 ").append(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).append("%");
        JSONObject next = null;
        long now = System.currentTimeMillis();
        for (JSONObject r : Store.get(c).reminders()) {
            if (r.optBoolean("done") || r.optLong("at") < now) continue;
            if (next == null || r.optLong("at") < next.optLong("at")) next = r;
        }
        if (next != null) {
            info.append("   ⏰ ").append(new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date(next.optLong("at"))))
                    .append(" ").append(next.optString("text"));
        } else {
            info.append("   \"Jarvis\" అని పిలవండి");
        }
        v.setTextViewText(R.id.widget_info, info.toString());
        return v;
    }
}
