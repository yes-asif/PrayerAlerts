package com.asif.prayeralerts;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Wide card: active prayer, its time, time left, and the next prayer. */
public class PrayerCardWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        Store.seedIfEmpty(c);
        Widgets.updateAll(c);
    }
}
