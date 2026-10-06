package com.ynozue.limitgauge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Boot/update re-scheduling, reset-time redraws, and redraws when the clock or locale changes. */
public class SystemReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Scheduler.ensurePeriodic(context);
            Refresher.renderAll(context);
            Scheduler.refreshSoon(context);
        } else if (Scheduler.ACTION_RENDER.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_LOCALE_CHANGED.equals(action)) {
            Refresher.renderAll(context);
        }
    }
}
