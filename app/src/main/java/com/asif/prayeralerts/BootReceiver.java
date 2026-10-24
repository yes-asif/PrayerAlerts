package com.asif.prayeralerts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarms are wiped on reboot and can drift on clock/time-zone changes, so re-arm. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        Store.seedIfEmpty(c);
        Notifier.ensureChannels(c);
        Scheduler.scheduleNext(c);
    }
}
