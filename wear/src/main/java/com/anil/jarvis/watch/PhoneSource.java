package com.anil.jarvis.watch;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

/** W61 "Jarvis ఫోన్ బ్యాటరీ" for any watch face (as last told by the phone). */
public class PhoneSource extends ComplicationDataSourceService {
    static String[] text(org.json.JSONObject info) {
        int b = info.optInt("phone_bat", -1);
        return new String[]{b < 0 ? "--" : b + "%" + (info.optBoolean("phone_chg") ? "⚡" : ""), "ఫోన్"};
    }

    @Override public void onComplicationRequest(ComplicationRequest request, ComplicationDataSourceService.ComplicationRequestListener listener) {
        try { listener.onComplicationData(request.getComplicationType() == ComplicationType.SHORT_TEXT ? DutySource.data(text(Link.info(this))) : null); }
        catch (Exception ignored) {}
    }

    @Override public ComplicationData getPreviewData(ComplicationType type) {
        return type == ComplicationType.SHORT_TEXT ? DutySource.data(new String[]{"64%", "ఫోన్"}) : null;
    }
}
