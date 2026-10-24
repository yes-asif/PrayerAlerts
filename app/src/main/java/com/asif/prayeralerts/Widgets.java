package com.asif.prayeralerts;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the three home-screen widgets (emerald + gold design):
 *  - Prayer countdown (2x2): active prayer and the time it has left
 *  - Prayer card (4x2): active prayer, its start time, time left, and the next prayer
 *  - Today's prayers (4x3): all five prayers, the active one marked
 *
 * A prayer is "active" from its start until the next time on the timetable
 * (Fajr → Sunrise, Dhuhr → Asr, Asr → Maghrib, Maghrib → Isha, Isha → next Fajr).
 * Between Sunrise and Dhuhr nothing is active and the widgets count down to Dhuhr.
 * Alert on/off settings don't affect what the widgets show.
 *
 * Countdowns are Chronometers that tick on the home screen by themselves; the app only
 * refreshes the widgets at each prayer boundary (and just after midnight).
 */
public final class Widgets {
    private Widgets() {}

    static final String ACTION_REFRESH = "com.asif.prayeralerts.WIDGET_REFRESH";

    private static final int[] ROW = {R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4};
    private static final int[] NAME = {R.id.name0, R.id.name1, R.id.name2, R.id.name3, R.id.name4};
    private static final int[] TIME = {R.id.time0, R.id.time1, R.id.time2, R.id.time3, R.id.time4};

    /** One timetable entry (prayer or sunrise) at an exact moment. */
    private static final class Ev {
        final int col; final LocalDate day; final int minutes; final ZonedDateTime at;
        Ev(int col, LocalDate day, int minutes, ZonedDateTime at) { this.col = col; this.day = day; this.minutes = minutes; this.at = at; }
        boolean isPrayer() { return col != PrayerTimes.SUNRISE; }
    }

    /** What the widgets show right now. */
    static final class State {
        Ev active;          // null between Sunrise and Dhuhr, or with no data
        ZonedDateTime activeEnd;
        Ev upcoming;        // next prayer to start after now
        Ev afterUpcoming;   // the one after that
        ZonedDateTime nextChange;  // next moment any widget needs redrawing
        String[] today;     // today's row (may be null)
        LocalDate todayDate;
    }

    static State compute(Context c, ZonedDateTime now) {
        ZoneId zone = now.getZone();
        LocalDate today = now.toLocalDate();
        List<Ev> evs = new ArrayList<>();
        for (int i = -1; i <= 2; i++) {
            LocalDate d = today.plusDays(i);
            String[] row = Store.getDay(c, d);
            if (row == null) continue;
            for (int col = 0; col < PrayerTimes.COLS; col++) {
                int m = PrayerTimes.toMinutes(row[col], col);
                if (m >= 0) evs.add(new Ev(col, d, m, d.atTime(m / 60, m % 60).atZone(zone)));
            }
        }
        evs.sort((a, b) -> a.at.compareTo(b.at));

        State s = new State();
        s.todayDate = today;
        s.today = Store.getDay(c, today);
        int last = -1;
        for (int i = 0; i < evs.size(); i++) if (!evs.get(i).at.isAfter(now)) last = i;
        if (last >= 0 && evs.get(last).isPrayer()) {
            s.active = evs.get(last);
            if (last + 1 < evs.size()) s.activeEnd = evs.get(last + 1).at;
        }
        for (int i = last + 1; i < evs.size(); i++) {
            Ev e = evs.get(i);
            if (s.nextChange == null) s.nextChange = e.at;
            if (!e.isPrayer()) continue;
            if (s.upcoming == null) s.upcoming = e;
            else { s.afterUpcoming = e; break; }
        }
        ZonedDateTime midnight = today.plusDays(1).atStartOfDay(zone);
        if (s.nextChange == null || midnight.isBefore(s.nextChange)) s.nextChange = midnight;
        return s;
    }

    public static void updateAll(Context c) { updateAll(c, ZonedDateTime.now()); }

    /** @param now reference time (an alarm passes a moment just after its own prayer). */
    public static void updateAll(Context c, ZonedDateTime now) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] small = m.getAppWidgetIds(new ComponentName(c, NextPrayerWidget.class));
        int[] card = m.getAppWidgetIds(new ComponentName(c, PrayerCardWidget.class));
        int[] list = m.getAppWidgetIds(new ComponentName(c, TodayWidget.class));
        if (small.length + card.length + list.length == 0) return;

        State s = compute(c, now);
        if (small.length > 0) m.updateAppWidget(small, buildSmall(c, s));
        if (card.length > 0) m.updateAppWidget(card, buildCard(c, s));
        if (list.length > 0) m.updateAppWidget(list, buildList(c, s));
        scheduleRefresh(c, s.nextChange);
    }

    private static String upper(Ev e) {
        return Notifier.displayName(e.col, e.day).toUpperCase(Locale.getDefault());
    }

    // ---- Prayer countdown (2x2) ----

    private static RemoteViews buildSmall(Context c, State s) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_next);
        v.setOnClickPendingIntent(android.R.id.background, openApp(c));
        if (s.active != null && s.activeEnd != null) {
            v.setTextViewText(R.id.w_title, upper(s.active));
            countdown(v, R.id.w_count, s.activeEnd, "%s");
            v.setTextViewText(R.id.w_sub, "left");
        } else if (s.upcoming != null) {
            v.setTextViewText(R.id.w_title, upper(s.upcoming));
            countdown(v, R.id.w_count, s.upcoming.at, "%s");
            v.setTextViewText(R.id.w_sub, "until start");
        } else {
            v.setTextViewText(R.id.w_title, "NO TIMES");
            v.setViewVisibility(R.id.w_count, View.INVISIBLE);
            v.setTextViewText(R.id.w_sub, "tap to load");
            return v;
        }
        v.setViewVisibility(R.id.w_count, View.VISIBLE);
        return v;
    }

    // ---- Prayer card (4x2) ----

    private static RemoteViews buildCard(Context c, State s) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_card);
        v.setOnClickPendingIntent(android.R.id.background, openApp(c));
        Ev shown = s.active != null ? s.active : s.upcoming;
        if (shown == null) {
            v.setTextViewText(R.id.c_title, "PRAYER TIMES");
            v.setTextViewText(R.id.c_time, "—");
            v.setViewVisibility(R.id.c_count, View.GONE);
            v.setTextViewText(R.id.c_next, "Tap to load this month's timetable");
            return v;
        }
        v.setTextViewText(R.id.c_title, upper(shown) + " PRAYER");
        v.setTextViewText(R.id.c_time, PrayerTimes.format12(shown.minutes));

        if (s.active != null && s.activeEnd != null) {
            v.setViewVisibility(R.id.c_count, View.VISIBLE);
            countdown(v, R.id.c_count, s.activeEnd, "%s left");
        } else if (s.active == null) {
            v.setViewVisibility(R.id.c_count, View.VISIBLE);
            countdown(v, R.id.c_count, shown.at, "starts in %s");
        } else {
            v.setViewVisibility(R.id.c_count, View.GONE);
        }

        // "Next" is the prayer after the one shown.
        Ev next = s.active != null ? s.upcoming : s.afterUpcoming;
        v.setTextViewText(R.id.c_next, next == null ? ""
                : "Next: " + upper(next) + " at " + PrayerTimes.format12(next.minutes));
        return v;
    }

    // ---- Today's prayers (4x3) ----

    private static RemoteViews buildList(Context c, State s) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_today);
        v.setOnClickPendingIntent(android.R.id.background, openApp(c));
        boolean has = s.today != null;
        v.setViewVisibility(R.id.t_rows, has ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.t_empty, has ? View.GONE : View.VISIBLE);
        if (!has) return v;

        int gold = c.getColor(R.color.wg_gold);
        int bright = c.getColor(R.color.wg_gold_bright);
        int green = c.getColor(R.color.wg_green_text);
        for (int i = 0; i < PrayerTimes.ALERT_COLUMNS.length; i++) {
            int col = PrayerTimes.ALERT_COLUMNS[i];
            String name = Notifier.displayName(col, s.todayDate).toUpperCase(Locale.getDefault());
            int m = PrayerTimes.toMinutes(s.today[col], col);
            boolean active = s.active != null && s.active.col == col;

            if (active) {
                SpannableStringBuilder sb = new SpannableStringBuilder(name + " ");
                int start = sb.length();
                sb.append("(active)");
                sb.setSpan(new ForegroundColorSpan(green), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                v.setTextViewText(NAME[i], sb);
                v.setTextColor(NAME[i], c.getColor(R.color.wg_white));
                v.setTextViewText(TIME[i], "ACTIVE");
                v.setTextColor(TIME[i], bright);
                v.setInt(ROW[i], "setBackgroundResource", R.drawable.wg_row_active);
            } else {
                v.setTextViewText(NAME[i], name);
                v.setTextColor(NAME[i], gold);
                v.setTextViewText(TIME[i], m < 0 ? "—" : PrayerTimes.format12(m));
                v.setTextColor(TIME[i], gold);
                v.setInt(ROW[i], "setBackgroundResource", R.drawable.wg_row_normal);
            }
        }
        return v;
    }

    // ---- helpers ----

    private static void countdown(RemoteViews v, int id, ZonedDateTime target, String format) {
        long remaining = target.toInstant().toEpochMilli() - System.currentTimeMillis();
        v.setChronometer(id, SystemClock.elapsedRealtime() + remaining, format, true);
        v.setChronometerCountDown(id, true);
    }

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 10, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Redraws the widgets at the next prayer boundary (including Sunrise) or midnight.
     * Non-waking: if the phone is asleep it runs as soon as the screen comes on.
     */
    private static void scheduleRefresh(Context c, ZonedDateTime when) {
        if (when == null) return;
        long at = when.toInstant().toEpochMilli() + 1000;
        Intent i = new Intent(c, BootReceiver.class).setAction(ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(c, 2, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        try {
            if (Scheduler.canExact(c)) am.setExact(AlarmManager.RTC, at, pi);
            else am.setWindow(AlarmManager.RTC, at, 60_000, pi);
        } catch (SecurityException e) {
            am.setWindow(AlarmManager.RTC, at, 60_000, pi);
        }
    }
}
