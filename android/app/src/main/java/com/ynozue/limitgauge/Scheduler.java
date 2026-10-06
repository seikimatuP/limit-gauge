package com.ynozue.limitgauge;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/** Background work: a periodic fetch, one-off fetches, and a redraw when a window resets. */
final class Scheduler {
    static final int JOB_PERIODIC = 1001;
    static final int JOB_NOW = 1002;
    static final String ACTION_RENDER = "com.ynozue.limitgauge.action.RENDER";

    private static final long PERIOD_MS = 15L * 60L * 1000L; // JobScheduler minimum

    private Scheduler() {}

    /** Schedules the 15-minute fetch if it is not already scheduled. */
    static void ensurePeriodic(Context c) {
        if (!Prefs.configured(c)) return;
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_PERIODIC) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_PERIODIC, new ComponentName(c, RefreshJobService.class))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }

    /** Asks for a fetch as soon as the network allows. Replaces a pending one-off fetch. */
    static void refreshSoon(Context c) {
        if (!Prefs.configured(c)) return;
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        JobInfo job = new JobInfo.Builder(JOB_NOW, new ComponentName(c, RefreshJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        js.schedule(job);
    }

    /** No widgets and no notification: nothing shows the data, so stop waking up for it. */
    static void stopIfUnused(Context c) {
        if (Prefs.notifyEnabled(c)) return;
        android.appwidget.AppWidgetManager m = android.appwidget.AppWidgetManager.getInstance(c);
        if (m == null) return;
        Class<?>[] providers = {CardWidgetProvider.class, WeeklyGaugeProvider.class, FiveHourGaugeProvider.class};
        for (Class<?> p : providers) {
            int[] ids = m.getAppWidgetIds(new ComponentName(c, p));
            if (ids != null && ids.length > 0) return;
        }
        cancelAll(c);
    }

    static void cancelAll(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js != null) js.cancelAll();
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(renderIntent(c));
    }

    /** Redraws shortly after the next reset so widgets flip to "reset" without waiting for a fetch. */
    static void scheduleResetAlarm(Context c, UsageData d) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = renderIntent(c);
        long now = Formats.nowSec();
        long next = Long.MAX_VALUE;
        if (d != null) {
            if (d.fiveHour != null && d.fiveHour.resetsAt > now) next = Math.min(next, d.fiveHour.resetsAt);
            if (d.sevenDay != null && d.sevenDay.resetsAt > now) next = Math.min(next, d.sevenDay.resetsAt);
        }
        if (next == Long.MAX_VALUE) {
            am.cancel(pi);
            return;
        }
        // Inexact, non-wakeup (Android may deliver it up to ~10 min late); fetches also redraw.
        am.setWindow(AlarmManager.RTC, next * 1000L + 2000L, 60_000L, pi);
    }

    private static PendingIntent renderIntent(Context c) {
        Intent i = new Intent(c, SystemReceiver.class).setAction(ACTION_RENDER);
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
