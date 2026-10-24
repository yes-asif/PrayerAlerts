package com.asif.prayeralerts;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * One notification channel per prayer so each can have its own sound.
 * Android fixes a channel's sound at creation, so changing a sound replaces the channel.
 */
public final class Notifier {
    private Notifier() {}

    static final String REMINDER_CHANNEL = "reminders";
    private static final int REMINDER_ID = 200;

    static String channelId(Context c, int col) {
        return "prayer_" + col + "_v" + Store.channelVersion(c, col);
    }

    public static void ensureChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        for (int col : PrayerTimes.ALERT_COLUMNS) {
            String id = channelId(c, col);
            if (nm.getNotificationChannel(id) != null) continue;
            NotificationChannel ch = new NotificationChannel(id,
                    PrayerTimes.COLUMNS[col] + " prayer", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Alert at the start of " + PrayerTimes.COLUMNS[col]);
            ch.setSound(Store.soundUri(c, col), aa);
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(REMINDER_CHANNEL) == null) {
            NotificationChannel r = new NotificationChannel(REMINDER_CHANNEL,
                    "Timetable reminders", NotificationManager.IMPORTANCE_DEFAULT);
            r.setDescription("Reminds you to load next month's timetable");
            nm.createNotificationChannel(r);
        }
    }

    /** Changes the sound for one prayer (null = silent). */
    public static void setSound(Context c, int col, Uri uri) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.deleteNotificationChannel(channelId(c, col));
        Store.setSoundUri(c, col, uri);
        Store.bumpChannelVersion(c, col);
        ensureChannels(c);
    }

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static String displayName(int col, LocalDate day) {
        if (col == PrayerTimes.DHUHR && day.getDayOfWeek() == DayOfWeek.FRIDAY) return "Jumu'ah";
        return PrayerTimes.COLUMNS[col];
    }

    public static void showPrayer(Context c, int col, LocalDate day, int minutes, boolean test) {
        ensureChannels(c);
        String name = displayName(col, day);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, channelId(c, col))
                .setSmallIcon(R.drawable.ic_stat_prayer)
                .setContentTitle(name + " · " + PrayerTimes.format12(minutes))
                .setContentText(test ? "Test alert — this is how " + name + " will sound."
                        : "It's time for " + name + " prayer.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(openApp(c))
                .setAutoCancel(true);
        notify(c, 100 + col, b);
    }

    public static void showReminder(Context c, String title, String text) {
        ensureChannels(c);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, REMINDER_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_prayer)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp(c))
                .setAutoCancel(true);
        notify(c, REMINDER_ID, b);
    }

    private static void notify(Context c, int id, NotificationCompat.Builder b) {
        try {
            NotificationManagerCompat.from(c).notify(id, b.build());
        } catch (SecurityException ignored) {
            // Notification permission not granted; the main screen shows a warning.
        }
    }
}
