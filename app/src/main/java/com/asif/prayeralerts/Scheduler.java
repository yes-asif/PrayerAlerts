package com.asif.prayeralerts;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Keeps exactly one alarm set: the next enabled prayer. Each alarm schedules the following one. */
public final class Scheduler {
    private Scheduler() {}

    public static final String EXTRA_COL = "col";
    public static final String EXTRA_WHEN = "when";
    public static final String EXTRA_DATE = "date";
    public static final String EXTRA_MIN = "min";

    public static final class Next {
        public final int col; public final LocalDate day; public final int minutes; public final ZonedDateTime at;
        Next(int col, LocalDate day, int minutes, ZonedDateTime at) { this.col = col; this.day = day; this.minutes = minutes; this.at = at; }
    }

    /** Next enabled prayer strictly after {@code now}, searching up to 62 days ahead. */
    public static Next findNext(Context c, ZonedDateTime now) {
        Map<String, TreeMap<Integer, String[]>> cache = new HashMap<>();
        LocalDate start = now.toLocalDate();
        for (int i = 0; i < 62; i++) {
            LocalDate d = start.plusDays(i);
            String k = Store.key(d.getYear(), d.getMonthValue());
            if (!cache.containsKey(k)) cache.put(k, Store.getMonth(c, d.getYear(), d.getMonthValue()));
            TreeMap<Integer, String[]> month = cache.get(k);
            if (month == null) continue;
            String[] row = month.get(d.getDayOfMonth());
            if (row == null) continue;
            for (int col : PrayerTimes.ALERT_COLUMNS) {
                if (!Store.isEnabled(c, col)) continue;
                int m = PrayerTimes.toMinutes(row[col], col);
                if (m < 0) continue;
                ZonedDateTime at = d.atTime(m / 60, m % 60).atZone(now.getZone());
                if (at.isAfter(now)) return new Next(col, d, m, at);
            }
        }
        return null;
    }

    public static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        return c.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    private static PendingIntent pending(Context c, Intent i) {
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static Next scheduleNext(Context c) { return scheduleAfter(c, ZonedDateTime.now()); }

    public static Next scheduleAfter(Context c, ZonedDateTime after) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        Intent base = new Intent(c, AlarmReceiver.class);
        Next n = findNext(c, after);
        Widgets.updateAll(c, after);
        if (n == null) {
            am.cancel(pending(c, base));
            return null;
        }
        Intent i = new Intent(c, AlarmReceiver.class)
                .putExtra(EXTRA_COL, n.col)
                .putExtra(EXTRA_WHEN, n.at.toInstant().toEpochMilli())
                .putExtra(EXTRA_DATE, n.day.toString())
                .putExtra(EXTRA_MIN, n.minutes);
        PendingIntent pi = pending(c, i);
        long t = n.at.toInstant().toEpochMilli();
        try {
            if (canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
        }
        return n;
    }

    /** From the 25th on, nudge once a day if next month's timetable isn't loaded yet. */
    public static void maybeRemindNextMonth(Context c) {
        LocalDate today = LocalDate.now();
        if (today.getDayOfMonth() < 25) return;
        YearMonth next = YearMonth.from(today).plusMonths(1);
        if (Store.getMonth(c, next.getYear(), next.getMonthValue()) != null) return;
        if (today.toString().equals(Store.lastReminderDate(c))) return;
        Store.setLastReminderDate(c, today.toString());
        String name = next.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + next.getYear();
        Notifier.showReminder(c, "Load the " + name + " timetable",
                "Open Prayer Alerts and add the mosque's " + name + " timetable so alerts keep coming next month.");
    }

    /** Called when nothing is left to schedule. */
    public static void remindNoData(Context c) {
        Notifier.showReminder(c, "No prayer times loaded",
                "Prayer alerts have stopped because no timetable is loaded for the coming days. Open the app to add one.");
    }
}
