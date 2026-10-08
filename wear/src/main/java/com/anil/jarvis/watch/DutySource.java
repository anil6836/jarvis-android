package com.anil.jarvis.watch;


import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.PlainComplicationText;
import androidx.wear.watchface.complications.data.ShortTextComplicationData;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

/** "Jarvis డ్యూటీ" for watch faces: time to the next duty (from the phone's duty calendar). */
public class DutySource extends ComplicationDataSourceService {
    @Override public void onComplicationRequest(ComplicationRequest request, ComplicationDataSourceService.ComplicationRequestListener listener) {
        String[] d = Complications.duty(this, System.currentTimeMillis());
        try { listener.onComplicationData(request.getComplicationType() == ComplicationType.SHORT_TEXT ? data(d == null ? new String[]{"--", "డ్యూటీ"} : d) : null); }
        catch (Exception ignored) {}
    }

    @Override public ComplicationData getPreviewData(ComplicationType type) {
        return type == ComplicationType.SHORT_TEXT ? data(new String[]{"5:20", "డ్యూటీ"}) : null;
    }

    static ComplicationData data(String[] d) {
        return new ShortTextComplicationData.Builder(new PlainComplicationText.Builder(d[0]).build(), new PlainComplicationText.Builder(d[1] + " " + d[0]).build())
                .setTitle(new PlainComplicationText.Builder(d[1]).build()).build();
    }
}
