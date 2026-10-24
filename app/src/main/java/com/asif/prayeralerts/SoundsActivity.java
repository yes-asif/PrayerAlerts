package com.asif.prayeralerts;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.time.LocalTime;
import java.time.LocalDate;

/** Pick any of the phone's notification, alarm or ringtone sounds for each prayer. */
public class SoundsActivity extends AppCompatActivity {
    private static final int ALL = -1;

    private LinearLayout list;
    private int pendingCol = ALL;
    private ActivityResultLauncher<Intent> picker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.sounds_title);

        picker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
            if (res.getResultCode() != Activity.RESULT_OK || res.getData() == null) return;
            Uri uri = res.getData().getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            if (pendingCol == ALL) {
                for (int col : PrayerTimes.ALERT_COLUMNS) Notifier.setSound(this, col, uri);
            } else {
                Notifier.setSound(this, pendingCol, uri);
            }
            build();
        });

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(12), dp(16), dp(16));
        scroll.addView(list);
        setContentView(scroll);
        build();
    }

    private void build() {
        list.removeAllViews();

        TextView intro = text("Each prayer can have its own sound. Alerts play at your phone's notification volume; "
                + "Silent mode or Do Not Disturb can mute them.", 13, false);
        intro.setAlpha(0.75f);
        intro.setPadding(0, 0, 0, dp(12));
        list.addView(intro);

        MaterialButton all = new MaterialButton(this);
        all.setText("Use one sound for all prayers");
        all.setOnClickListener(v -> choose(ALL));
        list.addView(all);

        for (int col : PrayerTimes.ALERT_COLUMNS) list.addView(card(col));
    }

    private MaterialCardView card(int col) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);
        card.setRadius(dp(16));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(8), dp(10));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        texts.addView(text(PrayerTimes.COLUMNS[col], 16, true));
        TextView sound = text(soundName(Store.soundUri(this, col)), 13, false);
        sound.setAlpha(0.75f);
        texts.addView(sound);
        row.addView(texts);

        MaterialButton test = new MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle);
        test.setText("Test");
        test.setOnClickListener(v -> {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                Toast.makeText(this, "Turn on notifications first", Toast.LENGTH_SHORT).show();
                return;
            }
            LocalTime t = LocalTime.now();
            Notifier.showPrayer(this, col, LocalDate.now(), t.getHour() * 60 + t.getMinute(), true);
        });
        row.addView(test);

        MaterialButton change = new MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle);
        change.setText("Change");
        change.setOnClickListener(v -> choose(col));
        row.addView(change);

        card.addView(row);
        return card;
    }

    /** Lets the user browse notification sounds, alarm sounds or ringtones. */
    private void choose(int col) {
        pendingCol = col;
        String[] kinds = {"Notification sounds", "Alarm sounds", "Ringtones"};
        new AlertDialog.Builder(this)
                .setTitle(col == ALL ? "Sound for all prayers" : "Sound for " + PrayerTimes.COLUMNS[col])
                .setItems(kinds, (d, which) -> {
                    int type = which == 0 ? RingtoneManager.TYPE_NOTIFICATION
                            : which == 1 ? RingtoneManager.TYPE_ALARM : RingtoneManager.TYPE_RINGTONE;
                    Uri def = which == 0 ? Settings.System.DEFAULT_NOTIFICATION_URI
                            : which == 1 ? Settings.System.DEFAULT_ALARM_ALERT_URI : Settings.System.DEFAULT_RINGTONE_URI;
                    Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, def)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, kinds[which]);
                    if (col != ALL) i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Store.soundUri(this, col));
                    try {
                        picker.launch(i);
                    } catch (Exception e) {
                        Toast.makeText(this, "No sound picker on this phone", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private String soundName(Uri uri) {
        if (uri == null) return "Silent";
        if (uri.equals(Settings.System.DEFAULT_NOTIFICATION_URI)) return "Default notification sound";
        if (uri.equals(Settings.System.DEFAULT_ALARM_ALERT_URI)) return "Default alarm sound";
        if (uri.equals(Settings.System.DEFAULT_RINGTONE_URI)) return "Default ringtone";
        try {
            Ringtone r = RingtoneManager.getRingtone(this, uri);
            if (r != null) return r.getTitle(this);
        } catch (Exception ignored) { }
        return "Custom sound";
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
