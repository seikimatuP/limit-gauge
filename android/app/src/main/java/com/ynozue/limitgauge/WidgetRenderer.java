package com.ynozue.limitgauge;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds the RemoteViews for every widget. Layouts only use views RemoteViews supports
 * (FrameLayout, LinearLayout, TextView, ImageView, ProgressBar); colour changes are done by
 * switching between pre-styled views rather than calling tint setters.
 */
final class WidgetRenderer {
    /** Below this many percent left, bars and rings turn red. */
    static final int LOW_LEFT = 20;

    private WidgetRenderer() {}

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if (m == null) return;
        long now = Formats.nowSec();
        UsageData d = Prefs.data(c);
        boolean configured = Prefs.configured(c);
        boolean showUsed = Prefs.showUsed(c);

        int[] ids = m.getAppWidgetIds(new ComponentName(c, CardWidgetProvider.class));
        if (ids != null && ids.length > 0) m.updateAppWidget(ids, card(c, d, configured, showUsed, now));

        ids = m.getAppWidgetIds(new ComponentName(c, WeeklyGaugeProvider.class));
        if (ids != null && ids.length > 0) {
            m.updateAppWidget(ids, gauge(c, d == null ? null : d.sevenDay, true, configured, showUsed, now));
        }

        ids = m.getAppWidgetIds(new ComponentName(c, FiveHourGaugeProvider.class));
        if (ids != null && ids.length > 0) {
            m.updateAppWidget(ids, gauge(c, d == null ? null : d.fiveHour, false, configured, showUsed, now));
        }
    }

    // ---- Card (2x2 and wide) -------------------------------------------------------------

    static RemoteViews card(Context c, UsageData d, boolean configured, boolean showUsed, long now) {
        RemoteViews narrow = new RemoteViews(c.getPackageName(), R.layout.widget_card);
        RemoteViews wide = new RemoteViews(c.getPackageName(), R.layout.widget_card_wide);
        fillCard(c, narrow, d, configured, showUsed, now);
        fillCard(c, wide, d, configured, showUsed, now);
        Map<SizeF, RemoteViews> sized = new HashMap<SizeF, RemoteViews>();
        sized.put(new SizeF(100f, 100f), narrow);
        sized.put(new SizeF(240f, 100f), wide);
        return new RemoteViews(sized);
    }

    private static void fillCard(Context c, RemoteViews rv, UsageData d, boolean configured,
                                 boolean showUsed, long now) {
        rv.setOnClickPendingIntent(android.R.id.background, openApp(c));
        if (!configured) {
            rv.setTextViewText(R.id.as_of, "");
            String setup = c.getString(R.string.sub_setup);
            window(c, rv, R.id.five_value, R.id.five_bar, R.id.five_bar_low, R.id.five_sub, null, false, showUsed, now);
            window(c, rv, R.id.week_value, R.id.week_bar, R.id.week_bar_low, R.id.week_sub, null, true, showUsed, now);
            rv.setTextViewText(R.id.five_sub, setup);
            rv.setTextViewText(R.id.week_sub, setup);
            return;
        }
        rv.setTextViewText(R.id.as_of, d == null ? "" : Formats.asOf(c, d.updatedAt, now));
        window(c, rv, R.id.five_value, R.id.five_bar, R.id.five_bar_low, R.id.five_sub,
                d == null ? null : d.fiveHour, false, showUsed, now);
        window(c, rv, R.id.week_value, R.id.week_bar, R.id.week_bar_low, R.id.week_sub,
                d == null ? null : d.sevenDay, true, showUsed, now);
    }

    private static void window(Context c, RemoteViews rv, int valueId, int barId, int lowBarId, int subId,
                               UsageData.Window w, boolean weekly, boolean showUsed, long now) {
        if (w == null) {
            rv.setTextViewText(valueId, c.getString(R.string.value_none));
            setBar(rv, barId, lowBarId, 0, false);
            rv.setTextViewText(subId, c.getString(R.string.sub_no_data));
            return;
        }
        int left = w.leftPercent(now);
        int shown = showUsed ? 100 - left : left;
        rv.setTextViewText(valueId, Formats.value(c, shown, showUsed));
        setBar(rv, barId, lowBarId, shown, left < LOW_LEFT);
        rv.setTextViewText(subId, Formats.resetLine(c, w, now, weekly));
    }

    private static void setBar(RemoteViews rv, int barId, int lowBarId, int progress, boolean low) {
        rv.setViewVisibility(barId, low ? View.GONE : View.VISIBLE);
        rv.setViewVisibility(lowBarId, low ? View.VISIBLE : View.GONE);
        rv.setProgressBar(low ? lowBarId : barId, 100, progress, false);
    }

    // ---- Ring gauges (weekly / 5-hour) ---------------------------------------------------

    static RemoteViews gauge(Context c, UsageData.Window w, boolean weekly, boolean configured,
                             boolean showUsed, long now) {
        RemoteViews small = new RemoteViews(c.getPackageName(), R.layout.widget_gauge);
        RemoteViews large = new RemoteViews(c.getPackageName(), R.layout.widget_gauge_large);
        fillGauge(c, small, w, weekly, configured, showUsed, now);
        fillGauge(c, large, w, weekly, configured, showUsed, now);
        Map<SizeF, RemoteViews> sized = new HashMap<SizeF, RemoteViews>();
        sized.put(new SizeF(40f, 40f), small);
        sized.put(new SizeF(140f, 140f), large);
        return new RemoteViews(sized);
    }

    private static void fillGauge(Context c, RemoteViews rv, UsageData.Window w, boolean weekly,
                                  boolean configured, boolean showUsed, long now) {
        rv.setOnClickPendingIntent(android.R.id.background, openApp(c));
        int labelRes = weekly
                ? (showUsed ? R.string.gauge_week_used : R.string.gauge_week_left)
                : (showUsed ? R.string.gauge_five_used : R.string.gauge_five_left);
        rv.setTextViewText(R.id.g_label, c.getString(labelRes));
        if (!configured) {
            rv.setTextViewText(R.id.g_value, c.getString(R.string.value_none));
            rv.setTextViewText(R.id.g_label, c.getString(R.string.gauge_setup));
            rv.setTextViewText(R.id.g_sub, c.getString(R.string.sub_setup));
            setBar(rv, R.id.g_ring, R.id.g_ring_low, 0, false);
            return;
        }
        if (w == null) {
            rv.setTextViewText(R.id.g_value, c.getString(R.string.value_none));
            rv.setTextViewText(R.id.g_sub, c.getString(R.string.sub_no_data));
            setBar(rv, R.id.g_ring, R.id.g_ring_low, 0, false);
            return;
        }
        int left = w.leftPercent(now);
        int shown = showUsed ? 100 - left : left;
        rv.setTextViewText(R.id.g_value, c.getString(R.string.value_percent, shown));
        rv.setTextViewText(R.id.g_sub, Formats.resetLine(c, w, now, weekly));
        setBar(rv, R.id.g_ring, R.id.g_ring_low, shown, left < LOW_LEFT);
    }

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
