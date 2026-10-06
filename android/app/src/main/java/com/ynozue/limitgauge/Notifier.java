package com.ynozue.limitgauge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

/**
 * Optional ongoing notification so the limits also show on the lock screen.
 * On Android 16+ it can additionally ask to be promoted to a Live Update (status-bar chip).
 */
final class Notifier {
    static final String CHANNEL = "usage_status";
    static final int ID = 1;

    /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING (public from API 36.1; the key already works on 36). */
    private static final String EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing";

    private Notifier() {}

    static boolean hasPermission(Context c) {
        return Build.VERSION.SDK_INT < 33
                || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    static void update(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (!Prefs.notifyEnabled(c) || !Prefs.configured(c)) {
            nm.cancel(ID);
            return;
        }
        if (!hasPermission(c)) return;
        ensureChannel(c, nm);

        long now = Formats.nowSec();
        UsageData d = Prefs.data(c);
        boolean showUsed = Prefs.showUsed(c);
        UsageData.Window week = d == null ? null : d.sevenDay;
        UsageData.Window five = d == null ? null : d.fiveHour;

        String title = c.getString(R.string.notif_title,
                c.getString(R.string.label_weekly), valueText(c, week, showUsed, now),
                c.getString(R.string.label_five_hour), valueText(c, five, showUsed, now));
        String text = c.getString(R.string.notif_text,
                Formats.resetLine(c, week, now, true), Formats.resetLine(c, five, now, false));

        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_gauge)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(c.getColor(R.color.lg_accent))
                .setContentIntent(WidgetRenderer.openApp(c));
        if (week != null) {
            int left = week.leftPercent(now);
            b.setProgress(100, showUsed ? 100 - left : left, false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && Prefs.liveUpdate(c)) {
            Bundle extras = new Bundle();
            extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true);
            b.addExtras(extras);
            if (week != null) {
                int left = week.leftPercent(now);
                b.setShortCriticalText(c.getString(R.string.value_percent, showUsed ? 100 - left : left));
            }
        }
        nm.notify(ID, b.build());
    }

    private static String valueText(Context c, UsageData.Window w, boolean showUsed, long now) {
        if (w == null) return c.getString(R.string.value_none);
        int left = w.leftPercent(now);
        return Formats.value(c, showUsed ? 100 - left : left, showUsed);
    }

    private static void ensureChannel(Context c, NotificationManager nm) {
        NotificationChannel ch = new NotificationChannel(CHANNEL, c.getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(c.getString(R.string.channel_desc));
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }
}
