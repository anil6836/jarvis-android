package com.anil.jarvis.watch;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

/** W61 "Jarvis ఒత్తిడి" for any watch face: today's stress, Jarvis's estimate from his heart rate. */
public class StressSource extends ComplicationDataSourceService {
    static String[] text(org.json.JSONObject info) {
        int s = info.optInt("stress", -1);
        return new String[]{s < 0 ? "--" : s < 15 ? "తక్కువ" : s < 30 ? "మధ్య" : "ఎక్కువ", "ఒత్తిడి"};
    }

    @Override public void onComplicationRequest(ComplicationRequest request, ComplicationDataSourceService.ComplicationRequestListener listener) {
        try { listener.onComplicationData(request.getComplicationType() == ComplicationType.SHORT_TEXT ? DutySource.data(text(Link.info(this))) : null); }
        catch (Exception ignored) {}
    }

    @Override public ComplicationData getPreviewData(ComplicationType type) {
        return type == ComplicationType.SHORT_TEXT ? DutySource.data(new String[]{"తక్కువ", "ఒత్తిడి"}) : null;
    }
}
