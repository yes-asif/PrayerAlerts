package com.asif.prayeralerts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Fires at a prayer's start time: shows the alert, then arms the next one. */
public class AlarmReceiver extends BroadcastReceiver {
    private static final long STALE_MS = 20 * 60 * 1000L;

    @Override
    public void onReceive(Context c, Intent intent) {
        int col = intent.getIntExtra(Scheduler.EXTRA_COL, -1);
        long when = intent.getLongExtra(Scheduler.EXTRA_WHEN, 0);
        int minutes = intent.getIntExtra(Scheduler.EXTRA_MIN, -1);
        String date = intent.getStringExtra(Scheduler.EXTRA_DATE);
        long now = System.currentTimeMillis();

        // Skip alerts that arrive long after the fact (phone was off, clock changed).
        if (col >= 0 && date != null && minutes >= 0 && now - when < STALE_MS) {
            Notifier.showPrayer(c, col, LocalDate.parse(date), minutes, false);
        }

        // Schedule from just after this alarm's time so an early wake-up can't repeat it.
        long from = Math.max(now, when + 1000);
        Scheduler.Next n = Scheduler.scheduleAfter(c,
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(from), ZoneId.systemDefault()));
        if (n == null) Scheduler.remindNoData(c);
        Scheduler.maybeRemindNextMonth(c);
    }
}
