package com.anil.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

/**
 * A card on the lock screen for whoever helps him after an accident: name, blood group, allergies, medical notes and
 * the emergency contact, in English and Telugu, readable without unlocking the phone. Silent; he fills it in Settings.
 */
final class MedicalId {
    private MedicalId() {}

    private static final int ID = 88;

    static boolean filled(Prefs p) {
        return !p.medBlood().isEmpty() || !p.medAllergy().isEmpty() || !p.medNotes().isEmpty() || !p.medContact().isEmpty();
    }

    static void update(Context c) {
        try {
            Prefs p = new Prefs(c);
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (!p.medIdOn() || !filled(p)) { nm.cancel(ID); return; }
            NotificationChannel ch = new NotificationChannel("jarvis_medid", "అత్యవసర సమాచారం (లాక్ స్క్రీన్)", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
            StringBuilder t = new StringBuilder();
            t.append("Name / పేరు: ").append(p.name());
            if (!p.medBlood().isEmpty()) t.append("\nBlood group / బ్లడ్ గ్రూప్: ").append(p.medBlood());
            if (!p.medAllergy().isEmpty()) t.append("\nAllergies / అలర్జీలు: ").append(p.medAllergy());
            if (!p.medNotes().isEmpty()) t.append("\nMedical / ఆరోగ్యం: ").append(p.medNotes());
            if (!p.medContact().isEmpty()) t.append("\nEmergency contact / ఎమర్జెన్సీ: ").append(p.medContact());
            t.append("\nAmbulance: 108");
            Notification n = new Notification.Builder(c, "jarvis_medid").setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle("🆘 EMERGENCY INFO · అత్యవసర సమాచారం")
                    .setContentText((p.medBlood().isEmpty() ? "" : "Blood " + p.medBlood() + " · ") + (p.medContact().isEmpty() ? "" : "Contact " + p.medContact()))
                    .setStyle(new Notification.BigTextStyle().bigText(t.toString()))
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .build();
            nm.notify(ID, n);
        } catch (Exception ignored) {}
    }
}
