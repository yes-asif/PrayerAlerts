package com.asif.prayeralerts;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Column definitions, 12h → minutes conversion and sanity checks. Pure Java. */
public final class PrayerTimes {
    private PrayerTimes() {}

    /** Columns as printed on the timetable, left to right. */
    public static final String[] COLUMNS = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    public static final int COLS = 6;
    public static final int SUNRISE = 1;
    public static final int DHUHR = 2;

    /** Column indices that trigger notifications (Sunrise is stored but not alerted). */
    public static final int[] ALERT_COLUMNS = {0, 2, 3, 4, 5};

    private static final Pattern TIME = Pattern.compile("^(\\d{1,2}):(\\d{2})$");

    /**
     * Converts a printed "h:mm" (no am/pm) to minutes after midnight, using what each column
     * can plausibly be. Returns -1 if unreadable.
     */
    public static int toMinutes(String s, int col) {
        if (s == null) return -1;
        Matcher m = TIME.matcher(s.trim());
        if (!m.matches()) return -1;
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        if (h < 1 || h > 12 || min > 59) return -1;
        int h24;
        switch (col) {
            case 0: // Fajr
            case 1: // Sunrise
                h24 = (h == 12) ? 0 : h;
                break;
            case 2: // Dhuhr: 11:xx am, 12:xx noon, 1:xx pm
                if (h == 12) h24 = 12;
                else if (h >= 10) h24 = h;
                else h24 = h + 12;
                break;
            default: // Asr, Maghrib, Isha: always pm
                h24 = (h == 12) ? 12 : h + 12;
        }
        return h24 * 60 + min;
    }

    public static String format12(int minutes) {
        int h = minutes / 60, m = minutes % 60;
        String ap = h < 12 ? "AM" : "PM";
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return h12 + ":" + (m < 10 ? "0" : "") + m + " " + ap;
    }

    /**
     * Flags cells that look wrong: empty/unreadable, out of order within the day,
     * or jumping more than 8 minutes from both neighbouring days.
     */
    public static boolean[][] flag(Map<Integer, String[]> days, int daysInMonth) {
        boolean[][] bad = new boolean[daysInMonth + 1][COLS];
        for (int d = 1; d <= daysInMonth; d++) {
            String[] row = days.get(d);
            int prev = -1;
            for (int c = 0; c < COLS; c++) {
                int v = row == null ? -1 : toMinutes(row[c], c);
                if (v < 0) { bad[d][c] = true; continue; }
                if (prev >= 0 && v <= prev) bad[d][c] = true;
                prev = v;
                int a = neighbour(days, d - 1, c), b = neighbour(days, d + 1, c);
                boolean farA = a >= 0 && Math.abs(v - a) > 8;
                boolean farB = b >= 0 && Math.abs(v - b) > 8;
                if ((farA && farB) || (farA && b < 0) || (farB && a < 0)) bad[d][c] = true;
            }
        }
        return bad;
    }

    private static int neighbour(Map<Integer, String[]> days, int d, int c) {
        String[] r = days.get(d);
        return r == null ? -1 : toMinutes(r[c], c);
    }
}
