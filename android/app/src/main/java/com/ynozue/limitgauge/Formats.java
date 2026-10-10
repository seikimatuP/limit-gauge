package com.ynozue.limitgauge;

import android.content.Context;
import android.text.format.DateFormat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Locale-aware text for times, dates and reset countdowns. */
final class Formats {
    private Formats() {}

    static long nowSec() {
        return System.currentTimeMillis() / 1000L;
    }

    /** "15:00" or "3:00 PM", following the device's 12/24-hour setting. */
    static String time(Context c, long epochSec) {
        return DateFormat.getTimeFormat(c).format(new Date(epochSec * 1000L));
    }

    /** "10/11" (ja) or "10/11" (en), from the locale's best pattern. */
    static String monthDay(long epochSec) {
        return pattern("Md", epochSec);
    }

    /** "10/11(土)" (ja) or "Sat, 10/11" (en). */
    static String monthDayWeekday(long epochSec) {
        return pattern("MdEEE", epochSec);
    }

    private static String pattern(String skeleton, long epochSec) {
        Locale locale = Locale.getDefault();
        String p = DateFormat.getBestDateTimePattern(locale, skeleton);
        return new SimpleDateFormat(p, locale).format(new Date(epochSec * 1000L));
    }

    static boolean sameDay(long aSec, long bSec) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(aSec * 1000L);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(bSec * 1000L);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /** A time if it is today, otherwise date + time. */
    static String when(Context c, long epochSec, long nowSec) {
        if (sameDay(epochSec, nowSec)) return time(c, epochSec);
        return monthDayWeekday(epochSec) + " " + time(c, epochSec);
    }

    /** Short line under a widget bar: "15:00にリセット", "あと5日でリセット", "リセット済み". */
    static String resetLine(Context c, UsageData.Window w, long nowSec, boolean weekly) {
        if (w == null) return c.getString(R.string.sub_no_data);
        if (w.isExpired(nowSec)) return c.getString(R.string.sub_reset_done);
        long left = w.secondsUntilReset(nowSec);
        if (weekly && left >= 24L * 3600L) {
            int days = (int) Math.round(left / 86400.0);
            return c.getResources().getQuantityString(R.plurals.sub_resets_in_days, days, days);
        }
        return c.getString(R.string.sub_resets_at, when(c, w.resetsAt, nowSec));
    }

    /**
     * Countdown line of the ring gauge: "Resets in 6 days" / "あと3時間でリセット" / "あと25分でリセット".
     * Days from 24 hours on, hours from 1 hour on, minutes below that.
     */
    static String resetIn(Context c, UsageData.Window w, long nowSec) {
        if (w == null) return c.getString(R.string.sub_no_data);
        if (w.isExpired(nowSec)) return c.getString(R.string.sub_reset_done);
        long left = w.secondsUntilReset(nowSec);
        if (left >= 86400L) {
            int days = (int) Math.max(1L, Math.round(left / 86400.0));
            return c.getResources().getQuantityString(R.plurals.reset_in_days, days, days);
        }
        if (left >= 3600L) {
            int hours = (int) Math.max(1L, Math.round(left / 3600.0));
            return c.getResources().getQuantityString(R.plurals.reset_in_hours, hours, hours);
        }
        int minutes = (int) Math.max(1L, (left + 59L) / 60L);
        return c.getResources().getQuantityString(R.plurals.reset_in_minutes, minutes, minutes);
    }

    /** Detailed line for the app screen: "10/11(土) 18:00 にリセット（あと4日6時間）". */
    static String resetDetail(Context c, UsageData.Window w, long nowSec) {
        if (w == null) return c.getString(R.string.sub_no_data);
        if (w.isExpired(nowSec)) return c.getString(R.string.sub_reset_done);
        String abs = monthDayWeekday(w.resetsAt) + " " + time(c, w.resetsAt);
        return c.getString(R.string.detail_resets, abs, relative(c, w.secondsUntilReset(nowSec)));
    }

    static String relative(Context c, long seconds) {
        if (seconds >= 86400L) {
            return c.getString(R.string.rel_days_hours, (int) (seconds / 86400L), (int) ((seconds % 86400L) / 3600L));
        }
        if (seconds >= 3600L) {
            return c.getString(R.string.rel_hours_minutes, (int) (seconds / 3600L), (int) ((seconds % 3600L) / 60L));
        }
        return c.getString(R.string.rel_minutes, (int) Math.max(1L, (seconds + 59L) / 60L));
    }

    /** Header label: "14:32時点" today, "10/5時点" otherwise. Empty when unknown. */
    static String asOf(Context c, long epochSec, long nowSec) {
        if (epochSec <= 0) return "";
        String t = sameDay(epochSec, nowSec) ? time(c, epochSec) : monthDay(epochSec);
        return c.getString(R.string.as_of, t);
    }

    /** "残り46%" / "46% left" (or the used variants). */
    static String value(Context c, int shown, boolean showUsed) {
        return c.getString(showUsed ? R.string.value_used : R.string.value_left, shown);
    }
}
