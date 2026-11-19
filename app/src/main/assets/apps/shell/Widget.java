package __PKG__;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import org.json.JSONArray;

/** 📲 The home-screen widget: a title and up to 3 short lines the app sets (App.widget); a tap opens the app. */
public class Widget extends AppWidgetProvider {
    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("jarvis_widget", Context.MODE_PRIVATE); }

    static void set(Context c, String title, JSONArray lines) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; lines != null && i < lines.length() && i < 3; i++) { if (b.length() > 0) b.append('\n'); b.append(lines.optString(i)); }
        sp(c).edit().putString("title", title == null ? "" : title).putString("lines", b.toString()).apply();
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, Widget.class));
        if (ids != null && ids.length > 0) draw(c, m, ids);
    }

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { draw(c, m, ids); }

    private static void draw(Context c, AppWidgetManager m, int[] ids) {
        SharedPreferences s = sp(c);
        String title = s.getString("title", ""), lines = s.getString("lines", "");
        if (title.isEmpty()) title = c.getString(R.string.app_name);
        PendingIntent open = PendingIntent.getActivity(c, 7, new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
            v.setTextViewText(R.id.w_title, title);
            v.setTextViewText(R.id.w_lines, lines.isEmpty() ? "యాప్ తెరవండి" : lines);
            v.setOnClickPendingIntent(R.id.w_root, open);
            m.updateAppWidget(id, v);
        }
    }
}
