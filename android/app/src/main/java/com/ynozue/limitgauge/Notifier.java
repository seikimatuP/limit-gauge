package com.ynozue.limitgauge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.RemoteViews;

/**
 * Optional ongoing notification so the limits also show on the lock screen.
 *
 * Normally it uses a custom view (DecoratedCustomViewStyle) with the ring gauge design shared with the
 * widgets: collapsed = weekly ring, label, value and countdown plus a small 5-hour ring; expanded = both
 * gauges side by side. When the Live Update option is on (Android 16+), it keeps the standard template
 * with a progress bar instead, because a notification with a custom view is never promoted.
 */
final class Notifier {
    static final String CHANNEL = "usage_status";
    static final int ID = 1;

    /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING (public from API 36.1; the key already works on 36). */
    private static final String EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing";

    private static final WidgetRenderer.Slot COLLAPSED_WEEK = new WidgetRenderer.Slot(R.id.w_ring,
            R.id.w_ring_low, R.id.w_label, R.id.w_value, R.id.w_reset, R.dimen.ring_notif, true, 1.75f, false);
    /** Only the small ring; its "5h 77%" text is set by {@link #collapsed}. */
    private static final WidgetRenderer.Slot COLLAPSED_FIVE = new WidgetRenderer.Slot(R.id.f_ring,
            R.id.f_ring_low, 0, 0, 0, R.dimen.ring_xs, false, 0f, false);
    private static final WidgetRenderer.Slot EXPANDED_WEEK = new WidgetRenderer.Slot(R.id.w_ring,
            R.id.w_ring_low, R.id.w_label, R.id.w_value, R.id.w_reset, R.dimen.ring_lg, true, 2.0f, false);
    private static final WidgetRenderer.Slot EXPANDED_FIVE = new WidgetRenderer.Slot(R.id.f_ring,
            R.id.f_ring_low, R.id.f_label, R.id.f_value, R.id.f_reset, R.dimen.ring_md, true, 2.0f, false);

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

        // Title and text stay set in both modes, for screen readers and the notification history.
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
                .setContentIntent(WidgetRenderer.openApp(c));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && Prefs.liveUpdate(c)) {
            // Live Update: the standard template, unchanged (a custom view would block promotion).
            // The option can only be switched on from Android 16, so older versions always get the gauge.
            b.setColor(c.getColor(R.color.lg_accent));
            if (week != null) {
                int left = week.leftPercent(now);
                b.setProgress(100, showUsed ? 100 - left : left, false);
            }
            Bundle extras = new Bundle();
            extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true);
            b.addExtras(extras);
            if (week != null) {
                int left = week.leftPercent(now);
                b.setShortCriticalText(c.getString(R.string.value_percent, showUsed ? 100 - left : left));
            }
        } else {
            b.setColor(c.getColor(R.color.lg_gauge))
                    .setStyle(new Notification.DecoratedCustomViewStyle())
                    .setCustomContentView(collapsed(c, week, five, showUsed, now))
                    .setCustomBigContentView(expanded(c, week, five, showUsed, now));
            String asOf = d == null ? "" : Formats.asOf(c, d.updatedAt, now);
            if (!asOf.isEmpty()) b.setSubText(asOf);
        }
        nm.notify(ID, b.build());
    }

    /** Collapsed: weekly gauge with label, value and countdown; a small 5-hour ring with its value. */
    private static RemoteViews collapsed(Context c, UsageData.Window week, UsageData.Window five,
                                         boolean showUsed, long now) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.notif_collapsed);
        WidgetRenderer.fillSlot(c, rv, COLLAPSED_WEEK, week, true, true, showUsed, now);
        WidgetRenderer.fillSlot(c, rv, COLLAPSED_FIVE, five, false, true, showUsed, now);
        String fiveValue = five == null ? c.getString(R.string.value_none)
                : c.getString(R.string.value_percent, shownPercent(five, showUsed, now));
        rv.setTextViewText(R.id.f_value, c.getString(R.string.gauge_sub_five, fiveValue));
        return rv;
    }

    /** Expanded: weekly (larger ring) and 5-hour gauges side by side. */
    private static RemoteViews expanded(Context c, UsageData.Window week, UsageData.Window five,
                                        boolean showUsed, long now) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.notif_expanded);
        WidgetRenderer.fillSlot(c, rv, EXPANDED_WEEK, week, true, true, showUsed, now);
        WidgetRenderer.fillSlot(c, rv, EXPANDED_FIVE, five, false, true, showUsed, now);
        return rv;
    }

    private static int shownPercent(UsageData.Window w, boolean showUsed, long now) {
        int left = w.leftPercent(now);
        return showUsed ? 100 - left : left;
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
