package com.anil.jarvis.watch;

import android.content.Context;

import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.ColorBuilders;
import androidx.wear.protolayout.DimensionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.ModifiersBuilders;
import androidx.wear.protolayout.ResourceBuilders;
import androidx.wear.protolayout.TimelineBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.util.Calendar;

/**
 * W21: the Jarvis tile (swipe from the watch face): the next duty (or time left in this one) with the time to leave,
 * the next reminder, last night's sleep, and buttons: talk, the day (☀️), timer, cooker, status, tasks. Its lines come
 * from the phone's news (every half hour, and when a reminder is set); it is drawn again when they change.
 */
public class JarvisTile extends TileService {
    private static final String RES = "1";

    /** The phone's news changed: the tile is drawn again. */
    static void refresh(Context c) {
        try { getUpdater(c).requestUpdate(JarvisTile.class); } catch (Throwable ignored) {}
    }

    @Override protected ListenableFuture<TileBuilders.Tile> onTileRequest(RequestBuilders.TileRequest req) {
        TileBuilders.Tile tile = new TileBuilders.Tile.Builder().setResourcesVersion(RES).setFreshnessIntervalMillis(15 * 60_000L)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(this))).build();
        return CallbackToFutureAdapter.getFuture(c -> { c.set(tile); return "jarvis-tile"; });
    }

    @Override protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(RequestBuilders.ResourcesRequest req) {
        ResourceBuilders.Resources r = new ResourceBuilders.Resources.Builder().setVersion(RES).build();
        return CallbackToFutureAdapter.getFuture(c -> { c.set(r); return "jarvis-tile-res"; });
    }

    // ---------------------------------------------------------------- what it shows

    /** "🏍️ డ్యూటీ 2:00 · ఇంకా 1రో 4గం" / "🏍️ డ్యూటీలో" / "". */
    static String dutyLine(JSONObject info, long now) {
        JSONObject d = info.optJSONObject("duty");
        if (d == null) return "";
        long at = d.optLong("at", 0), end = d.optLong("end", 0);
        if (d.optBoolean("on") && (at == 0 || now < at) || at > 0 && at <= now && now < end) return "🏍️ డ్యూటీలో ఉన్నారు";
        if (at <= now) return "";
        Calendar k = Calendar.getInstance();
        k.setTimeInMillis(at);
        int h = k.get(Calendar.HOUR_OF_DAY);
        String time = (h % 12 == 0 ? 12 : h % 12) + ":" + String.format(java.util.Locale.ROOT, "%02d", k.get(Calendar.MINUTE));
        String leave = d.optString("leave");
        return "🏍️ డ్యూటీ " + time + " · ఇంకా " + Panel.span(at - now) + (leave.isEmpty() || at - now > 24 * 3600_000L ? "" : "\n" + leave + " కి బయలుదేరాలి");
    }

    /** "⏰ 6:00 పాలు తేవాలి" (today / tomorrow only) or "". */
    static String reminderLine(JSONObject info, long now) {
        JSONObject r = info.optJSONObject("rem");
        if (r == null || r.optLong("at") <= now || r.optLong("at") - now > 36 * 3600_000L) return "";
        Calendar k = Calendar.getInstance();
        k.setTimeInMillis(r.optLong("at"));
        int h = k.get(Calendar.HOUR_OF_DAY);
        Calendar today = Calendar.getInstance();
        String day = k.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) ? "" : "రేపు ";
        String text = r.optString("text");
        if (text.length() > 22) text = text.substring(0, 21) + "…";
        return "⏰ " + day + (h % 12 == 0 ? 12 : h % 12) + ":" + String.format(java.util.Locale.ROOT, "%02d", k.get(Calendar.MINUTE)) + " " + text;
    }

    static String sleepLine(JSONObject info) {
        long m = info.optLong("sleep", -1);
        if (m < 60) return "";
        return "😴 రాత్రి నిద్ర " + (m / 60) + "గం " + (m % 60) + "ని";
    }

    private static LayoutElementBuilders.LayoutElement layout(Context c) {
        JSONObject info = Link.info(c);
        long now = System.currentTimeMillis();
        Theme.refresh(c);
        LayoutElementBuilders.Column.Builder col = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand()).setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER);
        col.addContent(text("J.A.R.V.I.S", 11, Theme.accent, true, 1));
        String duty = dutyLine(info, now), rem = reminderLine(info, now), sleep = sleepLine(info);
        if (info.optBoolean("dutyMode")) duty = duty.isEmpty() ? "🛡️ డ్యూటీ మోడ్ ఆన్" : duty;
        if (!duty.isEmpty()) col.addContent(text(duty, 12, 0xFFDCEEF5, false, 2));
        if (!rem.isEmpty()) col.addContent(text(rem, 11, 0xFF8FA9B5, false, 1));
        if (!sleep.isEmpty()) col.addContent(text(sleep, 11, 0xFF8FA9B5, false, 1));
        if (duty.isEmpty() && rem.isEmpty() && sleep.isEmpty()) col.addContent(text(info.length() == 0 ? "ఫోన్ Jarvis తో కనెక్ట్ అవ్వాలి" : "ఏమీ రాబోవడం లేదు", 11, 0xFF8FA9B5, false, 2));
        col.addContent(new LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(6)).build());
        col.addContent(new LayoutElementBuilders.Row.Builder().setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(button(c, "talk", "🎙️", WatchActivity.class.getName(), WatchActivity.EXTRA_LISTEN, "tap"))
                .addContent(gap())
                .addContent(button(c, "day", "☀️", WatchActivity.class.getName(), WatchActivity.EXTRA_DO, "morning"))
                .addContent(gap())
                .addContent(button(c, "timer", "⏱️", Panel.class.getName(), Panel.EXTRA_KIND, "timer")).build());
        col.addContent(new LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(4)).build());
        col.addContent(new LayoutElementBuilders.Row.Builder().setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(button(c, "cooker", "🍲", Panel.class.getName(), Panel.EXTRA_KIND, "cooker"))
                .addContent(gap())
                .addContent(button(c, "status", "📊", Panel.class.getName(), Panel.EXTRA_KIND, "status"))
                .addContent(gap())
                .addContent(button(c, "tasks", "✅", Panel.class.getName(), Panel.EXTRA_KIND, "tasks")).build());
        return col.build();
    }

    private static LayoutElementBuilders.LayoutElement text(String s, float sp, int color, boolean bold, int lines) {
        return new LayoutElementBuilders.Text.Builder().setText(s).setMaxLines(lines)
                .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder().setSize(DimensionBuilders.sp(sp)).setColor(ColorBuilders.argb(color))
                        .setWeight(bold ? LayoutElementBuilders.FONT_WEIGHT_BOLD : LayoutElementBuilders.FONT_WEIGHT_NORMAL).build()).build();
    }

    private static LayoutElementBuilders.LayoutElement gap() {
        return new LayoutElementBuilders.Spacer.Builder().setWidth(DimensionBuilders.dp(6)).build();
    }

    /** A round button that opens the watch app's screen (with what to do there). */
    private static LayoutElementBuilders.LayoutElement button(Context c, String id, String emoji, String cls, String key, String value) {
        ActionBuilders.LaunchAction open = new ActionBuilders.LaunchAction.Builder().setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                .setPackageName(c.getPackageName()).setClassName(cls)
                .addKeyToExtraMapping(key, new ActionBuilders.AndroidStringExtra.Builder().setValue(value).build()).build()).build();
        ModifiersBuilders.Modifiers mods = new ModifiersBuilders.Modifiers.Builder()
                .setClickable(new ModifiersBuilders.Clickable.Builder().setId(id).setOnClick(open).build())
                .setBackground(new ModifiersBuilders.Background.Builder().setColor(ColorBuilders.argb(0xFF0E3A4A))
                        .setCorner(new ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(20)).build()).build()).build();
        return new LayoutElementBuilders.Box.Builder().setWidth(DimensionBuilders.dp(40)).setHeight(DimensionBuilders.dp(40))
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER).setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setModifiers(mods).addContent(text(emoji, 16, 0xFFFFFFFF, false, 1)).build();
    }
}
