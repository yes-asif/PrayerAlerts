package com.asif.prayeralerts;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Small widget: next prayer, its time and a live countdown. */
public class NextPrayerWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        Store.seedIfEmpty(c);
        Widgets.updateAll(c);
    }
}
