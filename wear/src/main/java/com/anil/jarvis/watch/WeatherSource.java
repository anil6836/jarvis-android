package com.anil.jarvis.watch;


import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

/** "Jarvis వాతావరణం" for watch faces: the temperature and the sky where the phone is. */
public class WeatherSource extends ComplicationDataSourceService {
    @Override public void onComplicationRequest(ComplicationRequest request, ComplicationDataSourceService.ComplicationRequestListener listener) {
        String[] w = Complications.weather(this, System.currentTimeMillis());
        try { listener.onComplicationData(request.getComplicationType() == ComplicationType.SHORT_TEXT ? DutySource.data(w == null ? new String[]{"--", "వాతావరణం"} : w) : null); }
        catch (Exception ignored) {}
    }

    @Override public ComplicationData getPreviewData(ComplicationType type) {
        return type == ComplicationType.SHORT_TEXT ? DutySource.data(new String[]{"28°", "మేఘం"}) : null;
    }
}
