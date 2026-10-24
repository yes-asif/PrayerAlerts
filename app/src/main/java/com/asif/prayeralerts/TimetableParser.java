package com.asif.prayeralerts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns OCR words from a monthly timetable image into a day → 6 times table.
 * Works on geometry: corrects page tilt, groups words into rows, then snaps each
 * time to the nearest of six columns. Pure Java so it can be unit tested.
 */
public final class TimetableParser {

    public static final class Result {
        public int month = -1;   // 1..12, -1 if not found
        public int year = -1;
        public final TreeMap<Integer, String[]> days = new TreeMap<>();
    }

    private static final String[] MONTHS = {"january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december"};
    private static final Pattern MONTH_YEAR = Pattern.compile(
            "(january|february|march|april|may|june|july|august|september|october|november|december)\\s*,?\\s*(20\\d\\d)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_IN = Pattern.compile("(\\d{1,2})[:.;,](\\d{2})");
    private static final Pattern WHOLE_TIME = Pattern.compile("^\\d{1,2}[:.;,]\\d{2}(?:[,;/]\\d{1,2}[:.;,]\\d{2})*[.,;]?$");
    private static final Pattern INT = Pattern.compile("^(\\d{1,2})[.,]?$");

    private static final class P {
        final float x, y; final String v; final int n;
        P(float x, float y, String v, int n) { this.x = x; this.y = y; this.v = v; this.n = n; }
    }

    /** Fixes common OCR misreads in numeric words (O→0, l→1, S→5 ...). */
    static String normalize(String raw) {
        String s = raw.trim();
        boolean hasDigit = false;
        for (char ch : s.toCharArray()) if (Character.isDigit(ch)) hasDigit = true;
        if (!hasDigit) return s;
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case 'O': case 'o': case 'D': case 'Q': b.append('0'); break;
                case 'l': case 'I': case 'i': case '|': case '!': b.append('1'); break;
                case 'S': case 's': b.append('5'); break;
                case 'B': b.append('8'); break;
                case 'Z': case 'z': b.append('2'); break;
                case 'g': b.append('9'); break;
                default: b.append(ch);
            }
        }
        String fixed = b.toString();
        // Only accept the fix if it turns the word into a time or a plain number.
        return (WHOLE_TIME.matcher(fixed).matches() || INT.matcher(fixed).matches()) ? fixed : s;
    }

    public static Result parse(List<OcrToken> tokens, String fullText) {
        Result res = new Result();
        if (fullText != null) {
            Matcher my = MONTH_YEAR.matcher(fullText);
            if (my.find()) {
                res.month = Arrays.asList(MONTHS).indexOf(my.group(1).toLowerCase(Locale.US)) + 1;
                res.year = Integer.parseInt(my.group(2));
            }
        }
        if (tokens == null || tokens.isEmpty()) return res;

        // 1. Page tilt: median baseline angle of numeric words.
        List<Float> angles = new ArrayList<>();
        List<Float> heights = new ArrayList<>();
        for (OcrToken t : tokens) {
            if (TIME_IN.matcher(normalize(t.text)).find()) { angles.add(t.angleDeg); heights.add(t.height); }
        }
        if (heights.isEmpty()) return res;
        double a = Math.toRadians(median(angles));
        float h = median(heights);
        double cos = Math.cos(a), sin = Math.sin(a);

        // 2. Classify words, in de-rotated coordinates.
        List<P> times = new ArrayList<>(), ints = new ArrayList<>(), ampm = new ArrayList<>();
        for (OcrToken t : tokens) {
            float x = (float) (t.cx * cos + t.cy * sin);
            float y = (float) (-t.cx * sin + t.cy * cos);
            String raw = t.text.trim();
            String low = raw.toLowerCase(Locale.US);
            if (low.equals("am") || low.equals("pm") || low.equals("a.m.") || low.equals("p.m.")) {
                ampm.add(new P(x, y, low, 0));
                continue;
            }
            if (low.contains("am") || low.contains("pm") || low.contains("min")) continue; // iqama row etc.
            String n = normalize(raw);
            Matcher whole = WHOLE_TIME.matcher(n);
            if (whole.matches()) {
                // OCR occasionally glues neighbouring cells into one word ("5:40,6:47"); split it.
                Matcher f = TIME_IN.matcher(n);
                List<String> found = new ArrayList<>();
                while (f.find()) found.add(Integer.parseInt(f.group(1)) + ":" + f.group(2));
                if (found.size() == 1) {
                    times.add(new P(x, y, found.get(0), 0));
                } else {
                    for (int i = 0; i < found.size(); i++) {
                        float dx = t.width * ((i + 0.5f) / found.size() - 0.5f);
                        times.add(new P(x + (float) (dx * cos), y, found.get(i), 0));
                    }
                }
                continue;
            }
            Matcher im = INT.matcher(n);
            if (im.matches()) ints.add(new P(x, y, n, Integer.parseInt(im.group(1))));
        }
        // Drop times followed by a separate "am"/"pm" word (iqama row, footer notes).
        List<P> kept = new ArrayList<>();
        for (P p : times) {
            boolean near = false;
            for (P q : ampm) if (Math.abs(q.y - p.y) < 0.7f * h && q.x > p.x && q.x - p.x < 3.5f * h) near = true;
            if (!near) kept.add(p);
        }
        times = kept;

        // 3. Group into rows by y.
        Collections.sort(times, (p, q) -> Float.compare(p.y, q.y));
        List<List<P>> rows = new ArrayList<>();
        List<P> cur = null; float sumY = 0;
        for (P p : times) {
            if (cur != null && Math.abs(p.y - sumY / cur.size()) < 0.6f * h) {
                cur.add(p); sumY += p.y;
            } else {
                cur = new ArrayList<>(); cur.add(p); sumY = p.y; rows.add(cur);
            }
        }
        List<List<P>> table = new ArrayList<>();
        for (List<P> r : rows) {
            if (r.size() >= 3) { Collections.sort(r, (p, q) -> Float.compare(p.x, q.x)); table.add(r); }
        }
        if (table.isEmpty()) return res;

        // 4. Column centres from complete rows.
        float[] col = new float[PrayerTimes.COLS];
        int full = 0;
        for (List<P> r : table) {
            if (r.size() == PrayerTimes.COLS) { for (int c = 0; c < PrayerTimes.COLS; c++) col[c] += r.get(c).x; full++; }
        }
        if (full == 0) return res;
        for (int c = 0; c < PrayerTimes.COLS; c++) col[c] /= full;
        float spacing = (col[PrayerTimes.COLS - 1] - col[0]) / (PrayerTimes.COLS - 1);

        // 5. Assign days and columns.
        int prevDay = 0;
        for (List<P> r : table) {
            float ry = 0; for (P p : r) ry += p.y; ry /= r.size();
            String[] cells = new String[PrayerTimes.COLS];
            float[] dist = new float[PrayerTimes.COLS];
            Arrays.fill(cells, "");
            Arrays.fill(dist, Float.MAX_VALUE);
            int assigned = 0;
            for (P p : r) {
                int best = -1; float bd = Float.MAX_VALUE;
                for (int c = 0; c < PrayerTimes.COLS; c++) {
                    float d = Math.abs(p.x - col[c]);
                    if (d < bd) { bd = d; best = c; }
                }
                if (bd > spacing * 0.5f) continue; // outside the table
                if (bd < dist[best]) {
                    if (dist[best] == Float.MAX_VALUE) assigned++;
                    dist[best] = bd; cells[best] = p.v;
                }
            }
            if (assigned < 3) continue;

            // Leftmost small integer on the row = Gregorian date.
            P dayTok = null;
            for (P q : ints) {
                if (Math.abs(q.y - ry) < 0.6f * h && q.x < col[0] - spacing * 0.5f && q.n >= 1 && q.n <= 31) {
                    if (dayTok == null || q.x < dayTok.x) dayTok = q;
                }
            }
            int day = prevDay + 1;
            if (dayTok != null && dayTok.n > prevDay && dayTok.n <= prevDay + 3) day = dayTok.n;
            if (day > 31) break;
            res.days.put(day, cells);
            prevDay = day;
        }
        return res;
    }

    private static float median(List<Float> v) {
        List<Float> s = new ArrayList<>(v);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }
}
