package com.asif.prayeralerts;

import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/** Editable grid of the recognised times. Suspicious cells are highlighted in red before saving. */
public class ReviewActivity extends AppCompatActivity {
    public static final String EXTRA_YEAR = "year";
    public static final String EXTRA_MONTH = "month";
    public static final String EXTRA_JSON = "json";
    public static final String EXTRA_IMAGE = "image";

    private static final String[] HEAD = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};

    private TreeMap<Integer, String[]> data;
    private int year, month;
    private final List<Integer> years = new ArrayList<>();
    private EditText[][] cells = new EditText[32][PrayerTimes.COLS];
    private boolean[][] shownFlags = new boolean[32][PrayerTimes.COLS];
    private boolean building;

    private TableLayout grid;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_review);
        setTitle(R.string.review_title);

        year = getIntent().getIntExtra(EXTRA_YEAR, LocalDate.now().getYear());
        month = getIntent().getIntExtra(EXTRA_MONTH, LocalDate.now().getMonthValue());
        String json = getIntent().getStringExtra(EXTRA_JSON);
        data = json == null ? new TreeMap<>() : Store.fromJson(json);

        grid = findViewById(R.id.grid);
        tvStatus = findViewById(R.id.tvStatus);

        Spinner spMonth = findViewById(R.id.spMonth);
        Spinner spYear = findViewById(R.id.spYear);
        List<String> monthNames = new ArrayList<>();
        for (Month mo : Month.values()) monthNames.add(mo.getDisplayName(TextStyle.FULL, Locale.getDefault()));
        spMonth.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, monthNames));
        int now = LocalDate.now().getYear();
        for (int y = Math.min(now - 1, year); y <= Math.max(now + 2, year); y++) years.add(y);
        List<String> yearNames = new ArrayList<>();
        for (int y : years) yearNames.add(String.valueOf(y));
        spYear.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, yearNames));
        spMonth.setSelection(month - 1, false);
        spYear.setSelection(years.indexOf(year), false);

        AdapterView.OnItemSelectedListener l = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                int nm = spMonth.getSelectedItemPosition() + 1;
                int ny = years.get(spYear.getSelectedItemPosition());
                if (nm != month || ny != year) { month = nm; year = ny; buildGrid(); }
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        };
        spMonth.setOnItemSelectedListener(l);
        spYear.setOnItemSelectedListener(l);

        MaterialButton btnImage = findViewById(R.id.btnImage);
        ImageView iv = findViewById(R.id.ivSource);
        String img = getIntent().getStringExtra(EXTRA_IMAGE);
        if (img != null && new File(img).exists()) {
            btnImage.setOnClickListener(v -> {
                if (iv.getDrawable() == null) iv.setImageBitmap(BitmapFactory.decodeFile(img));
                iv.setVisibility(iv.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                btnImage.setText(iv.getVisibility() == View.VISIBLE ? "Hide" : getString(R.string.show_image));
            });
        } else {
            btnImage.setVisibility(View.GONE);
        }

        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        buildGrid();
    }

    private int daysInMonth() { return YearMonth.of(year, month).lengthOfMonth(); }

    private void buildGrid() {
        building = true;
        // Tidy OCR leftovers first so the grid and the checks see the same values.
        for (String[] r : data.values()) for (int c = 0; c < r.length; c++) r[c] = clean(r[c]);
        grid.removeAllViews();
        cells = new EditText[32][PrayerTimes.COLS];
        shownFlags = new boolean[32][PrayerTimes.COLS];

        TableRow head = new TableRow(this);
        head.addView(label("Day", true));
        for (String h : HEAD) head.addView(label(h, true));
        grid.addView(head);

        int len = daysInMonth();
        for (int d = 1; d <= len; d++) {
            TableRow row = new TableRow(this);
            String dow = LocalDate.of(year, month, d).getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault());
            row.addView(label(d + " " + dow, false));
            String[] vals = data.get(d);
            for (int c = 0; c < PrayerTimes.COLS; c++) {
                EditText e = new EditText(this);
                e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                e.setGravity(Gravity.CENTER);
                e.setSingleLine(true);
                e.setMinWidth(0);
                e.setMinimumWidth(0);
                e.setPadding(dp(2), dp(8), dp(2), dp(8));
                e.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
                e.setImeOptions(EditorInfo.IME_ACTION_NEXT);
                e.setText(vals == null ? "" : vals[c]);
                TableRow.LayoutParams lp = new TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT);
                lp.setMargins(dp(1), dp(1), dp(1), dp(1));
                e.setLayoutParams(lp);
                e.setBackground(cellBg(false));
                final int day = d, col = c;
                e.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { }
                    @Override public void onTextChanged(CharSequence s, int a, int b, int n) { }
                    @Override public void afterTextChanged(Editable s) {
                        if (building) return;
                        String[] r = data.get(day);
                        if (r == null) { r = new String[]{"", "", "", "", "", ""}; data.put(day, r); }
                        r[col] = clean(s.toString());
                        validate();
                    }
                });
                cells[d][c] = e;
                row.addView(e);
            }
            grid.addView(row);
        }
        building = false;
        validate();
    }

    /** "540", "5.40", "5 40" → "5:40". */
    static String clean(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("[.;, ]", ":").replaceAll(":+", ":");
        if (t.matches("\\d{3,4}")) t = t.substring(0, t.length() - 2) + ":" + t.substring(t.length() - 2);
        if (t.matches("0\\d:\\d{2}")) t = t.substring(1);
        return t;
    }

    private int validate() {
        int len = daysInMonth();
        boolean[][] f = PrayerTimes.flag(data, len);
        int bad = 0, filled = 0;
        for (int d = 1; d <= len; d++) {
            if (data.containsKey(d)) filled++;
            for (int c = 0; c < PrayerTimes.COLS; c++) {
                if (f[d][c]) bad++;
                if (cells[d][c] != null && shownFlags[d][c] != f[d][c]) {
                    cells[d][c].setBackground(cellBg(f[d][c]));
                    shownFlags[d][c] = f[d][c];
                }
            }
        }
        if (bad == 0) {
            tvStatus.setText("All " + len + " days look right. Compare a few rows with the image, then save.");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.brand));
        } else {
            tvStatus.setText(bad + " cell" + (bad == 1 ? "" : "s") + " need a look (red). Empty, out of order, "
                    + "or far from neighbouring days. " + filled + "/" + len + " days read.");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.flag_stroke));
        }
        return bad;
    }

    private void save() {
        int bad = validate();
        if (bad > 0) {
            new AlertDialog.Builder(this)
                    .setTitle(bad + " cell" + (bad == 1 ? " looks" : "s look") + " off")
                    .setMessage("Prayers with an unreadable time won't be alerted on that day. Save anyway?")
                    .setPositiveButton("Save anyway", (d, w) -> doSave())
                    .setNegativeButton("Fix", null)
                    .show();
        } else {
            doSave();
        }
    }

    private void doSave() {
        int len = daysInMonth();
        TreeMap<Integer, String[]> out = new TreeMap<>();
        for (int d = 1; d <= len; d++) if (data.containsKey(d)) out.put(d, data.get(d));
        boolean replaced = Store.getMonth(this, year, month) != null;
        Store.saveMonth(this, year, month, out);
        Scheduler.scheduleNext(this);
        String name = Month.of(month).getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + year;
        Toast.makeText(this, (replaced ? "Updated " : "Saved ") + name, Toast.LENGTH_SHORT).show();
        finish();
    }

    private TextView label(String s, boolean header) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, header ? 11 : 12);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(2), dp(6), dp(2), dp(6));
        if (header) t.setAlpha(0.7f);
        TableRow.LayoutParams lp = new TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_VERTICAL;
        t.setLayoutParams(lp);
        return t;
    }

    private GradientDrawable cellBg(boolean flagged) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(6));
        g.setColor(flagged ? ContextCompat.getColor(this, R.color.flag_fill) : Color.TRANSPARENT);
        g.setStroke(dp(1), ContextCompat.getColor(this, flagged ? R.color.flag_stroke : R.color.cell_stroke));
        return g;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
