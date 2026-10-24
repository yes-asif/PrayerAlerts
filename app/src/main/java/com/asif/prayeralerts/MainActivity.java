package com.asif.prayeralerts;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private TextView tvWarn, tvCurrentDate, tvCurrentPrayer, tvUpcomingPrayer;
    private MaterialCardView warnCard;
    private MaterialButton btnWarn, btnImport;
    private LinearLayout todayList, monthsList;
    private ProgressBar progress;
    /** Text of the main gold button; names the month when a timetable is due. */
    private String importLabel;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable tick = new Runnable() {
        @Override public void run() { refreshHeader(); buildToday(); handler.postDelayed(this, 30_000); }
    };

    private ActivityResultLauncher<String[]> pickFile;
    private ActivityResultLauncher<String> notifPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        setContentView(R.layout.activity_main);

        Store.seedIfEmpty(this);
        Notifier.ensureChannels(this);

        tvCurrentDate = findViewById(R.id.tvCurrentDate);
        tvCurrentPrayer = findViewById(R.id.tvCurrentPrayer);
        tvUpcomingPrayer = findViewById(R.id.tvUpcomingPrayer);
        styleSystemBars();
        sizeHeader();
        tvWarn = findViewById(R.id.tvWarn);
        warnCard = findViewById(R.id.warnCard);
        btnWarn = findViewById(R.id.btnWarn);
        btnImport = findViewById(R.id.btnImport);
        todayList = findViewById(R.id.todayList);
        monthsList = findViewById(R.id.monthsList);
        progress = findViewById(R.id.progress);

        pickFile = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) importFile(uri);
        });
        notifPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (!granted) openNotificationSettings();
            refresh();
        });

        btnImport.setOnClickListener(v -> pickFile.launch(new String[]{"image/*", "application/pdf"}));
        findViewById(R.id.btnSounds).setOnClickListener(v -> startActivity(new Intent(this, SoundsActivity.class)));
        findViewById(R.id.btnTest).setOnClickListener(v -> sendTest());

        if (Build.VERSION.SDK_INT >= 33 && !hasNotifPermission()) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Scheduler.scheduleNext(this);
        refresh();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    private boolean hasNotifPermission() {
        return Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void refresh() {
        refreshHeader();
        buildToday();
        buildMonths();
        updateWarning();
    }

    // ---- Header ----

    /** Status bar matches the top of the sky; light icons on the dark design. */
    private void styleSystemBars() {
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.status_bar));
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.panel));
        WindowInsetsControllerCompat c = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        c.setAppearanceLightStatusBars(false);
        c.setAppearanceLightNavigationBars(false);
    }

    /**
     * The header runs to 95% of the image height: the Now/Next panel sits at its bottom over the
     * mosque's base wall (domes stay clear), and the first card follows. Capped for landscape.
     */
    private void sizeHeader() {
        View header = findViewById(R.id.headerSpace);
        ImageView iv = findViewById(R.id.ivHeader);
        Drawable d = iv.getDrawable();
        int w = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        float aspect = (d != null && d.getIntrinsicWidth() > 0)
                ? (float) d.getIntrinsicHeight() / d.getIntrinsicWidth() : 0.885f;
        int h = Math.min(Math.round(w * aspect * 0.95f), Math.round(screenH * 0.5f));
        header.getLayoutParams().height = h;
        header.requestLayout();
    }

    private void refreshHeader() {
        ZonedDateTime now = ZonedDateTime.now();
        SpannableStringBuilder top = new SpannableStringBuilder(
                now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")) + "  ·  ");
        int start = top.length();
        top.append(now.format(DateTimeFormatter.ofPattern("h:mm a")));
        top.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), start, top.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvCurrentDate.setText(top);
        refreshPrayerStatus(now);
    }

    /**
     * "Now" = the most recent prayer that has started (Isha carries on overnight until Fajr).
     * "Next" = the next prayer time, whether or not its alert is turned on.
     */
    private void refreshPrayerStatus(ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        int nowMin = now.getHour() * 60 + now.getMinute();
        String current = null, nextName = null;
        int nextMin = -1;

        String[] t = Store.getDay(this, today);
        if (t != null) {
            for (int col : PrayerTimes.ALERT_COLUMNS) {
                int m = PrayerTimes.toMinutes(t[col], col);
                if (m < 0) continue;
                if (m <= nowMin) current = Notifier.displayName(col, today);
                else if (nextName == null) { nextName = Notifier.displayName(col, today); nextMin = m; }
            }
        }
        if (current == null) {
            LocalDate y = today.minusDays(1);
            String[] yr = Store.getDay(this, y);
            if (yr != null && PrayerTimes.toMinutes(yr[5], 5) >= 0) current = Notifier.displayName(5, y);
        }
        if (nextName == null) {
            LocalDate tm = today.plusDays(1);
            String[] tr = Store.getDay(this, tm);
            int m = tr == null ? -1 : PrayerTimes.toMinutes(tr[0], 0);
            if (m >= 0) { nextName = Notifier.displayName(0, tm); nextMin = m; }
        }

        tvCurrentPrayer.setText(current != null ? current : "—");
        if (nextName == null) {
            tvUpcomingPrayer.setText("—");
        } else {
            SpannableStringBuilder sb = new SpannableStringBuilder(nextName + "  ·  ");
            int start = sb.length();
            sb.append(PrayerTimes.format12(nextMin));
            // "Tomorrow" is shown on the gold card below; kept off this line so the panel stays clear of the moon.
            sb.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, R.color.gold)),
                    start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tvUpcomingPrayer.setText(sb);
        }
    }

    // ---- Prayer cards ----

    private static int iconFor(int col) {
        switch (col) {
            case 0: return R.drawable.ic_prayer_fajr;
            case 2: return R.drawable.ic_prayer_dhuhr;
            case 3: return R.drawable.ic_prayer_asr;
            case 4: return R.drawable.ic_prayer_maghrib;
            default: return R.drawable.ic_prayer_isha;
        }
    }

    /**
     * Fajr, Dhuhr, Asr, Maghrib, Isha in order; the next prayer is drawn as the gold card in place.
     * After Isha the list shows tomorrow, with Fajr highlighted.
     */
    private void buildToday() {
        ZonedDateTime now = ZonedDateTime.now();
        LocalDate today = now.toLocalDate();
        Scheduler.Next next = Scheduler.findNext(this, now);
        LocalDate day = (next != null && next.day.equals(today.plusDays(1))) ? next.day : today;

        todayList.removeAllViews();
        String[] row = Store.getDay(this, day);
        if (row == null) {
            View v = LayoutInflater.from(this).inflate(R.layout.item_prayer_row, todayList, false);
            ((ImageView) v.findViewById(R.id.icon)).setImageResource(R.drawable.ic_prayer_isha);
            ((TextView) v.findViewById(R.id.name)).setText("NO TIMES LOADED");
            ((TextView) v.findViewById(R.id.time)).setText("Load this month below");
            ((TextView) v.findViewById(R.id.time)).setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            v.findViewById(R.id.bell).setVisibility(View.GONE);
            todayList.addView(v);
            return;
        }

        LayoutInflater inf = LayoutInflater.from(this);
        for (int col : PrayerTimes.ALERT_COLUMNS) {
            final int c = col;
            boolean isNext = next != null && next.col == col && next.day.equals(day);
            View v = inf.inflate(isNext ? R.layout.item_prayer_next : R.layout.item_prayer_row, todayList, false);

            int m = PrayerTimes.toMinutes(row[col], col);
            String time = m < 0 ? "—" : PrayerTimes.format12(m);
            String name = Notifier.displayName(col, day).toUpperCase(Locale.getDefault());
            if (isNext && !day.equals(today)) name += "  ·  TOMORROW";

            ((ImageView) v.findViewById(R.id.icon)).setImageResource(iconFor(col));
            ((TextView) v.findViewById(R.id.name)).setText(name);
            ((TextView) v.findViewById(R.id.time)).setText(time);
            ((TextView) v.findViewById(R.id.timeRight)).setText(time);

            if (isNext) {
                long mins = Math.max(0, Duration.between(now, next.at).toMinutes() + 1);
                String in = mins >= 60 ? (mins / 60) + "h " + (mins % 60) + "m" : mins + "m";
                SpannableStringBuilder sb = new SpannableStringBuilder("Next prayer in ");
                int start = sb.length();
                sb.append(in);
                sb.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, R.color.gold)),
                        start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ((TextView) v.findViewById(R.id.countdown)).setText(sb);
            }

            ImageButton bell = v.findViewById(R.id.bell);
            boolean on = Store.isEnabled(this, col);
            bell.setImageResource(on ? R.drawable.ic_bell : R.drawable.ic_bell_off);
            bell.setColorFilter(ContextCompat.getColor(this, on ? R.color.gold : R.color.gold_dim));
            bell.setContentDescription((on ? "Turn off " : "Turn on ") + Notifier.displayName(col, day) + " alert");
            bell.setOnClickListener(b -> {
                boolean nowOn = !Store.isEnabled(this, c);
                Store.setEnabled(this, c, nowOn);
                Scheduler.scheduleNext(this);
                buildToday();
                Toast.makeText(this, Notifier.displayName(c, LocalDate.now()) + " alert "
                        + (nowOn ? "on" : "off"), Toast.LENGTH_SHORT).show();
            });

            todayList.addView(v);
        }
    }

    // ---- Months ----

    private void buildMonths() {
        monthsList.removeAllViews();
        for (String key : Store.months(this)) {
            String[] p = key.split("-");
            int y = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            TreeMap<Integer, String[]> data = Store.getMonth(this, y, m);
            if (data == null) continue;
            int n = data.size();
            YearMonth ym = YearMonth.of(y, m);
            String label = ym.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + y
                    + "  ·  " + n + "/" + ym.lengthOfMonth() + " days";
            TextView tv = text(label, 15, false);
            tv.setMinHeight(dp(44));
            tv.setGravity(Gravity.CENTER_VERTICAL);
            TypedValue out = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
            tv.setBackgroundResource(out.resourceId);
            tv.setOnClickListener(v -> openReview(y, m, Store.toJson(data), null));
            tv.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("Delete " + ym.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + y + "?")
                        .setMessage("Alerts for that month will stop.")
                        .setPositiveButton("Delete", (d, w) -> {
                            Store.deleteMonth(this, y, m);
                            Scheduler.scheduleNext(this);
                            refresh();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            });
            monthsList.addView(tv);
        }
        if (monthsList.getChildCount() == 0) monthsList.addView(text("None yet.", 14, false));
    }

    // ---- Warnings ----

    private void updateWarning() {
        importLabel = getString(R.string.load_month);
        try {
            showWarning();
        } finally {
            if (progress.getVisibility() != View.VISIBLE) btnImport.setText(importLabel);
        }
    }

    /**
     * Problems that need a different action get their own button in the card. When the fix is
     * "load a timetable", the card shows only the message and the main gold button takes the
     * month's name, so there is a single load button.
     */
    private void showWarning() {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            showWarn("Notifications are off, so prayer alerts can't appear.", "Allow notifications", v -> {
                if (Build.VERSION.SDK_INT >= 33 && !hasNotifPermission())
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
                else openNotificationSettings();
            });
            return;
        }
        if (!Scheduler.canExact(this) && Build.VERSION.SDK_INT >= 31) {
            showWarn("Exact alarms aren't allowed, so alerts may arrive a few minutes late.", "Allow exact alarms", v ->
                    startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName()))));
            return;
        }
        if (Scheduler.findNext(this, ZonedDateTime.now()) == null) {
            showWarn("No timetable is loaded for the coming days, so no alerts are scheduled.", null, null);
            importLabel = "Load timetable";
            return;
        }
        LocalDate today = LocalDate.now();
        YearMonth next = YearMonth.from(today).plusMonths(1);
        if (today.getDayOfMonth() >= 20 && Store.getMonth(this, next.getYear(), next.getMonthValue()) == null) {
            String name = next.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault());
            showWarn(name + "'s timetable isn't loaded yet. Add it when the mosque publishes it.", null, null);
            importLabel = "Load " + name + " timetable";
            return;
        }
        warnCard.setVisibility(View.GONE);
    }

    /** @param action button text, or null for a message-only card. */
    private void showWarn(String msg, String action, View.OnClickListener l) {
        warnCard.setVisibility(View.VISIBLE);
        tvWarn.setText(msg);
        btnWarn.setVisibility(action == null ? View.GONE : View.VISIBLE);
        if (action != null) {
            btnWarn.setText(action);
            btnWarn.setOnClickListener(l);
        }
    }

    private void openNotificationSettings() {
        startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
    }

    // ---- Test ----

    private void sendTest() {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Toast.makeText(this, "Turn on notifications first", Toast.LENGTH_SHORT).show();
            return;
        }
        Scheduler.Next n = Scheduler.findNext(this, ZonedDateTime.now());
        int col = n == null ? 0 : n.col;
        LocalDate day = n == null ? LocalDate.now() : n.day;
        int min = n == null ? 5 * 60 : n.minutes;
        Notifier.showPrayer(this, col, day, min, true);
    }

    // ---- Import ----

    private void importFile(Uri uri) {
        setBusy(true);
        io.execute(() -> {
            try {
                Bitmap bmp = OcrHelper.load(this, uri);
                String preview = savePreview(bmp);
                OcrHelper.recognize(bmp, new OcrHelper.Callback() {
                    @Override public void onResult(TimetableParser.Result r) {
                        runOnUiThread(() -> onParsed(r, preview));
                    }
                    @Override public void onError(Exception e) {
                        runOnUiThread(() -> onImportFailed(e.getMessage(), preview));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> onImportFailed(e.getMessage(), null));
            }
        });
    }

    private String savePreview(Bitmap bmp) {
        try {
            float s = Math.min(1f, 1600f / Math.max(bmp.getWidth(), bmp.getHeight()));
            Bitmap small = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * s), Math.round(bmp.getHeight() * s), true);
            File f = new File(getCacheDir(), "import.jpg");
            try (FileOutputStream out = new FileOutputStream(f)) { small.compress(Bitmap.CompressFormat.JPEG, 85, out); }
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private void onParsed(TimetableParser.Result r, String preview) {
        setBusy(false);
        int y = r.year, m = r.month;
        if (y < 0 || m < 1) {
            YearMonth guess = LocalDate.now().getDayOfMonth() >= 20 ? YearMonth.now().plusMonths(1) : YearMonth.now();
            y = guess.getYear();
            m = guess.getMonthValue();
        }
        if (r.days.size() < 5) {
            final int fy = y, fm = m;
            new AlertDialog.Builder(this)
                    .setTitle("Couldn't read the table")
                    .setMessage("Only " + r.days.size() + " rows were recognised. Try a sharper, straight-on photo or the PDF, "
                            + "or enter the times by hand.")
                    .setPositiveButton("Enter by hand", (d, w) -> openReview(fy, fm, Store.toJson(r.days), preview))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        openReview(y, m, Store.toJson(r.days), preview);
    }

    private void onImportFailed(String msg, String preview) {
        setBusy(false);
        new AlertDialog.Builder(this)
                .setTitle("Couldn't open that file")
                .setMessage(msg == null ? "Unknown error" : msg)
                .setPositiveButton("OK", null)
                .show();
    }

    private void openReview(int year, int month, String json, String imagePath) {
        Intent i = new Intent(this, ReviewActivity.class)
                .putExtra(ReviewActivity.EXTRA_YEAR, year)
                .putExtra(ReviewActivity.EXTRA_MONTH, month)
                .putExtra(ReviewActivity.EXTRA_JSON, json);
        if (imagePath != null) i.putExtra(ReviewActivity.EXTRA_IMAGE, imagePath);
        startActivity(i);
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnImport.setEnabled(!busy);
        btnImport.setText(busy ? "Reading timetable…" : (importLabel != null ? importLabel : getString(R.string.load_month)));
    }

    // ---- helpers ----

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(android.graphics.Color.parseColor("#E0D4B3"));
        try {
            android.graphics.Typeface tf = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.montserrat);
            t.setTypeface(tf, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        } catch (Exception e) {
            if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
