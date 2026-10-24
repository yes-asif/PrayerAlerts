package com.asif.prayeralerts;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Larger widget: all of today's times with the next prayer highlighted, plus a countdown. */
public class TodayWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        Store.seedIfEmpty(c);
        Widgets.updateAll(c);
    }
}
