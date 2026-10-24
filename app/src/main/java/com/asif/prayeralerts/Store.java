package com.asif.prayeralerts;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Saved timetables (one JSON blob per month) and settings. */
public final class Store {
    private Store() {}

    private static final String TABLES = "timetables";
    private static final String SETTINGS = "settings";

    private static SharedPreferences tables(Context c) { return c.getSharedPreferences(TABLES, Context.MODE_PRIVATE); }
    private static SharedPreferences settings(Context c) { return c.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE); }

    public static String key(int year, int month) { return String.format(java.util.Locale.US, "%04d-%02d", year, month); }

    // ---- timetables ----

    public static void saveMonth(Context c, int year, int month, Map<Integer, String[]> days) {
        tables(c).edit().putString(key(year, month), toJson(days)).apply();
    }

    public static TreeMap<Integer, String[]> getMonth(Context c, int year, int month) {
        String s = tables(c).getString(key(year, month), null);
        return s == null ? null : fromJson(s);
    }

    public static void deleteMonth(Context c, int year, int month) {
        tables(c).edit().remove(key(year, month)).apply();
    }

    /** Saved month keys ("yyyy-MM"), newest first. */
    public static List<String> months(Context c) {
        List<String> k = new ArrayList<>(tables(c).getAll().keySet());
        Collections.sort(k, Collections.reverseOrder());
        return k;
    }

    public static String[] getDay(Context c, LocalDate d) {
        TreeMap<Integer, String[]> m = getMonth(c, d.getYear(), d.getMonthValue());
        return m == null ? null : m.get(d.getDayOfMonth());
    }

    public static String toJson(Map<Integer, String[]> days) {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<Integer, String[]> e : days.entrySet()) {
                JSONArray a = new JSONArray();
                for (String v : e.getValue()) a.put(v == null ? "" : v);
                o.put(String.valueOf(e.getKey()), a);
            }
        } catch (JSONException ignored) { }
        return o.toString();
    }

    public static TreeMap<Integer, String[]> fromJson(String s) {
        TreeMap<Integer, String[]> out = new TreeMap<>();
        try {
            JSONObject o = new JSONObject(s);
            JSONArray names = o.names();
            if (names == null) return out;
            for (int i = 0; i < names.length(); i++) {
                String k = names.getString(i);
                JSONArray a = o.getJSONArray(k);
                String[] row = new String[PrayerTimes.COLS];
                for (int c = 0; c < PrayerTimes.COLS; c++) row[c] = c < a.length() ? a.getString(c) : "";
                out.put(Integer.parseInt(k), row);
            }
        } catch (JSONException | NumberFormatException ignored) { }
        return out;
    }

    /** First launch: preload the October 2026 King Fahad Mosque timetable bundled with the app. */
    public static void seedIfEmpty(Context c) {
        if (settings(c).getBoolean("seeded", false)) return;
        settings(c).edit().putBoolean("seeded", true).apply();
        if (!tables(c).getAll().isEmpty()) return;
        TreeMap<Integer, String[]> days = new TreeMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                c.getAssets().open("seed-2026-10.csv"), StandardCharsets.UTF_8))) {
            String line = r.readLine(); // header
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split(",");
                if (p.length < 7) continue;
                String[] row = new String[PrayerTimes.COLS];
                System.arraycopy(p, 1, row, 0, PrayerTimes.COLS);
                days.put(Integer.parseInt(p[0]), row);
            }
        } catch (Exception ignored) { return; }
        saveMonth(c, 2026, 10, days);
    }

    // ---- settings ----

    public static boolean isEnabled(Context c, int col) { return settings(c).getBoolean("enabled_" + col, true); }
    public static void setEnabled(Context c, int col, boolean on) { settings(c).edit().putBoolean("enabled_" + col, on).apply(); }

    /** Chosen sound; null = silent. Defaults to the phone's default notification sound. */
    public static Uri soundUri(Context c, int col) {
        String s = settings(c).getString("sound_" + col, null);
        if (s == null) return Settings.System.DEFAULT_NOTIFICATION_URI;
        return s.isEmpty() ? null : Uri.parse(s);
    }
    public static void setSoundUri(Context c, int col, Uri uri) {
        settings(c).edit().putString("sound_" + col, uri == null ? "" : uri.toString()).apply();
    }

    public static int channelVersion(Context c, int col) { return settings(c).getInt("chan_" + col, 1); }
    public static void bumpChannelVersion(Context c, int col) {
        settings(c).edit().putInt("chan_" + col, channelVersion(c, col) + 1).apply();
    }

    public static String lastReminderDate(Context c) { return settings(c).getString("last_reminder", ""); }
    public static void setLastReminderDate(Context c, String d) { settings(c).edit().putString("last_reminder", d).apply(); }
}
