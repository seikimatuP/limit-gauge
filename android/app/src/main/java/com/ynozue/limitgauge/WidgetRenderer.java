package com.ynozue.limitgauge;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds the RemoteViews for every widget, and the ring gauge pieces the notification shares.
 * Layouts only use views RemoteViews supports (FrameLayout, LinearLayout, TextView, ImageView).
 * The ring itself is a bitmap from {@link GaugeBitmap}; its colour comes from the ImageView's
 * {@code android:tint} in the layout, and the red "low" state is a second, pre-tinted ImageView that is
 * shown instead, so no colour is resolved here.
 */
final class WidgetRenderer {
    /** Below this many percent left, the ring turns red. */
    static final int LOW_LEFT = 20;

    /** Where one ring gauge sits in a layout, and how it is drawn. Unused text views are 0. */
    static final class Slot {
        final int ring, ringLow, label, value, reset;
        /** Dimen resource that sizes both the ImageView and its bitmap. */
        final int ringDimen;
        /** Draw the gauge glyph in the middle of the ring. */
        final boolean icon;
        /** &gt; 0: value as "94% left" with the number this many times larger and bold; 0: plain "94%". */
        final float numScale;
        /** Label is the short "Weekly left" (1x1 gauge) instead of "Claude · Weekly". */
        final boolean shortLabel;

        Slot(int ring, int ringLow, int label, int value, int reset, int ringDimen, boolean icon,
             float numScale, boolean shortLabel) {
            this.ring = ring;
            this.ringLow = ringLow;
            this.label = label;
            this.value = value;
            this.reset = reset;
            this.ringDimen = ringDimen;
            this.icon = icon;
            this.numScale = numScale;
            this.shortLabel = shortLabel;
        }
    }

    // Card (2x2): weekly is the main gauge, 5-hour the smaller one below it.
    private static final Slot CARD_WEEK = new Slot(R.id.w_ring, R.id.w_ring_low, R.id.w_label, R.id.w_value,
            R.id.w_reset, R.dimen.ring_lg, true, 2.0f, false);
    private static final Slot CARD_FIVE = new Slot(R.id.f_ring, R.id.f_ring_low, R.id.f_label, R.id.f_value,
            R.id.f_reset, R.dimen.ring_sm, true, 1.6f, false);
    // Card, wide: side by side.
    private static final Slot CARD_WIDE_WEEK = new Slot(R.id.w_ring, R.id.w_ring_low, R.id.w_label, R.id.w_value,
            R.id.w_reset, R.dimen.ring_lg, true, 2.0f, false);
    private static final Slot CARD_WIDE_FIVE = new Slot(R.id.f_ring, R.id.f_ring_low, R.id.f_label, R.id.f_value,
            R.id.f_reset, R.dimen.ring_md, true, 1.8f, false);
    // Single gauges (weekly or 5-hour) in three sizes.
    private static final Slot GAUGE_SMALL = new Slot(R.id.g_ring, R.id.g_ring_low, R.id.g_label, R.id.g_value,
            0, R.dimen.ring_md, false, 0f, true);
    private static final Slot GAUGE_WIDE = new Slot(R.id.g_ring, R.id.g_ring_low, R.id.g_label, R.id.g_value,
            R.id.g_reset, R.dimen.ring_md, true, 1.8f, false);
    private static final Slot GAUGE_LARGE = new Slot(R.id.g_ring, R.id.g_ring_low, R.id.g_label, R.id.g_value,
            R.id.g_reset, R.dimen.ring_xl, true, 2.4f, false);

    private WidgetRenderer() {}

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if (m == null) return;
        long now = Formats.nowSec();
        UsageData d = Prefs.data(c);
        boolean configured = Prefs.configured(c);
        boolean showUsed = Prefs.showUsed(c);
        UsageData.Window week = d == null ? null : d.sevenDay;
        UsageData.Window five = d == null ? null : d.fiveHour;

        // One update per widget: a widget on the lock screen drops its card background.
        for (int id : ids(c, m, CardWidgetProvider.class)) {
            m.updateAppWidget(id, card(c, d, configured, showUsed, now, onKeyguard(m, id)));
        }
        for (int id : ids(c, m, WeeklyGaugeProvider.class)) {
            m.updateAppWidget(id, gauge(c, week, true, configured, showUsed, now, onKeyguard(m, id)));
        }
        for (int id : ids(c, m, FiveHourGaugeProvider.class)) {
            m.updateAppWidget(id, gauge(c, five, false, configured, showUsed, now, onKeyguard(m, id)));
        }
    }

    private static int[] ids(Context c, AppWidgetManager m, Class<?> provider) {
        int[] ids = m.getAppWidgetIds(new ComponentName(c, provider));
        return ids == null ? new int[0] : ids;
    }

    /** True when the widget is hosted on the lock screen (keyguard), where the reference look has no card. */
    static boolean onKeyguard(AppWidgetManager m, int appWidgetId) {
        Bundle o = m.getAppWidgetOptions(appWidgetId);
        return o != null && o.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, 0)
                == AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD;
    }

    private static void common(Context c, RemoteViews rv, boolean keyguard) {
        rv.setOnClickPendingIntent(android.R.id.background, openApp(c));
        if (keyguard) rv.setInt(android.R.id.background, "setBackgroundResource", 0);
    }

    // ---- Card (2x2 and wide) -------------------------------------------------------------

    static RemoteViews card(Context c, UsageData d, boolean configured, boolean showUsed, long now,
                            boolean keyguard) {
        RemoteViews narrow = new RemoteViews(c.getPackageName(), R.layout.widget_card);
        RemoteViews wide = new RemoteViews(c.getPackageName(), R.layout.widget_card_wide);
        fillCard(c, narrow, CARD_WEEK, CARD_FIVE, d, configured, showUsed, now, keyguard);
        fillCard(c, wide, CARD_WIDE_WEEK, CARD_WIDE_FIVE, d, configured, showUsed, now, keyguard);
        Map<SizeF, RemoteViews> sized = new HashMap<SizeF, RemoteViews>();
        sized.put(new SizeF(100f, 100f), narrow);
        sized.put(new SizeF(240f, 100f), wide);
        return new RemoteViews(sized);
    }

    private static void fillCard(Context c, RemoteViews rv, Slot weekSlot, Slot fiveSlot, UsageData d,
                                 boolean configured, boolean showUsed, long now, boolean keyguard) {
        common(c, rv, keyguard);
        rv.setTextViewText(R.id.as_of, configured && d != null ? Formats.asOf(c, d.updatedAt, now) : "");
        fillSlot(c, rv, weekSlot, d == null ? null : d.sevenDay, true, configured, showUsed, now);
        fillSlot(c, rv, fiveSlot, d == null ? null : d.fiveHour, false, configured, showUsed, now);
    }

    // ---- Ring gauges (weekly / 5-hour) ---------------------------------------------------

    static RemoteViews gauge(Context c, UsageData.Window w, boolean weekly, boolean configured,
                             boolean showUsed, long now, boolean keyguard) {
        RemoteViews small = new RemoteViews(c.getPackageName(), R.layout.widget_gauge);
        RemoteViews wide = new RemoteViews(c.getPackageName(), R.layout.widget_gauge_wide);
        RemoteViews large = new RemoteViews(c.getPackageName(), R.layout.widget_gauge_large);
        common(c, small, keyguard);
        common(c, wide, keyguard);
        common(c, large, keyguard);
        fillSlot(c, small, GAUGE_SMALL, w, weekly, configured, showUsed, now);
        fillSlot(c, wide, GAUGE_WIDE, w, weekly, configured, showUsed, now);
        fillSlot(c, large, GAUGE_LARGE, w, weekly, configured, showUsed, now);
        Map<SizeF, RemoteViews> sized = new HashMap<SizeF, RemoteViews>();
        sized.put(new SizeF(40f, 40f), small);
        // Resized to a short, wide shape (2x1 and similar): ring on the left, three lines on the right.
        sized.put(new SizeF(150f, 64f), wide);
        sized.put(new SizeF(140f, 140f), large);
        return new RemoteViews(sized);
    }

    // ---- Shared by widgets and the notification -----------------------------------------

    /** Draws one ring gauge and its text into {@code rv}. {@code w} may be null (no data yet). */
    static void fillSlot(Context c, RemoteViews rv, Slot s, UsageData.Window w, boolean weekly,
                         boolean configured, boolean showUsed, long now) {
        boolean has = configured && w != null;
        int shown = 0;
        boolean low = false;
        if (has) {
            int left = w.leftPercent(now);
            shown = showUsed ? 100 - left : left;
            low = left < LOW_LEFT;
        }
        rv.setImageViewBitmap(low ? s.ringLow : s.ring, GaugeBitmap.ringForDimen(c, s.ringDimen, shown, s.icon));
        rv.setViewVisibility(s.ring, low ? View.GONE : View.VISIBLE);
        rv.setViewVisibility(s.ringLow, low ? View.VISIBLE : View.GONE);

        if (s.label != 0) rv.setTextViewText(s.label, label(c, weekly, showUsed, s.shortLabel, configured));
        if (s.value != 0) {
            CharSequence value;
            if (!has) value = c.getString(R.string.value_none);
            else if (s.numScale > 0f) value = valueLine(c, shown, showUsed, s.numScale);
            else value = c.getString(R.string.value_percent, shown);
            rv.setTextViewText(s.value, value);
        }
        if (s.reset != 0) {
            rv.setTextViewText(s.reset, configured ? Formats.resetIn(c, w, now) : c.getString(R.string.sub_setup));
        }
        String desc = c.getString(weekly ? R.string.label_weekly : R.string.label_five_hour) + " "
                + (has ? Formats.value(c, shown, showUsed) : c.getString(R.string.sub_no_data));
        rv.setContentDescription(low ? s.ringLow : s.ring, desc);
    }

    private static String label(Context c, boolean weekly, boolean showUsed, boolean shortLabel,
                                boolean configured) {
        if (shortLabel) {
            if (!configured) return c.getString(R.string.gauge_setup);
            return c.getString(weekly
                    ? (showUsed ? R.string.gauge_week_used : R.string.gauge_week_left)
                    : (showUsed ? R.string.gauge_five_used : R.string.gauge_five_left));
        }
        return c.getString(weekly ? R.string.gauge_label_weekly : R.string.gauge_label_five);
    }

    /** "94% left" / "残り 94%" with the number enlarged and bold, like the lock screen widget. */
    static CharSequence valueLine(Context c, int shown, boolean showUsed, float numScale) {
        String num = c.getString(R.string.value_percent, shown);
        String full = c.getString(showUsed ? R.string.gauge_line_used : R.string.gauge_line_left, num);
        SpannableString sp = new SpannableString(full);
        int at = full.indexOf(num);
        if (at >= 0) {
            int end = at + num.length();
            sp.setSpan(new RelativeSizeSpan(numScale), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new StyleSpan(Typeface.BOLD), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sp;
    }

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
