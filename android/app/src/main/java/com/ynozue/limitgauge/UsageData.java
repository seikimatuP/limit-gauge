package com.ynozue.limitgauge;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.OffsetDateTime;

/**
 * A snapshot of the Claude rate-limit windows (5-hour and 7-day).
 *
 * Accepts either the relay's response
 *   {"five_hour": {"used_percentage": 23.5, "resets_at": 1759761234, "captured_at": ...}, "seven_day": {...}, "updated_at": ...}
 * or Claude Code's raw status line JSON, which nests the same windows under "rate_limits".
 *
 * Pure Java + org.json only, so the logic can be unit-tested on a plain JVM.
 */
public final class UsageData {

    /** One rate-limit window. */
    public static final class Window {
        /** Percent of the window used, 0..100, as reported by Claude Code. */
        public final double usedPercent;
        /** When the window resets, in epoch seconds. */
        public final long resetsAt;
        /** When Claude Code reported this value, in epoch seconds (0 if unknown). */
        public final long capturedAt;

        Window(double usedPercent, long resetsAt, long capturedAt) {
            this.usedPercent = usedPercent;
            this.resetsAt = resetsAt;
            this.capturedAt = capturedAt;
        }

        /** True once the reset time has passed: the whole limit is available again. */
        public boolean isExpired(long nowSec) {
            return nowSec >= resetsAt;
        }

        /** Remaining percent right now, rounded down so it never overstates what is left. */
        public int leftPercent(long nowSec) {
            if (isExpired(nowSec)) return 100;
            int left = (int) Math.floor(100.0 - usedPercent + 1e-9);
            return Math.max(0, Math.min(100, left));
        }

        /** Used percent right now; always 100 - {@link #leftPercent}. */
        public int usedPercentRounded(long nowSec) {
            return 100 - leftPercent(nowSec);
        }

        public long secondsUntilReset(long nowSec) {
            return Math.max(0L, resetsAt - nowSec);
        }
    }

    /** The rolling 5-hour window, or null when Claude Code has not reported one. */
    public final Window fiveHour;
    /** The weekly (7-day) window, or null when Claude Code has not reported one. */
    public final Window sevenDay;
    /** When the data last changed, in epoch seconds (0 if unknown). */
    public final long updatedAt;

    UsageData(Window fiveHour, Window sevenDay, long updatedAt) {
        this.fiveHour = fiveHour;
        this.sevenDay = sevenDay;
        this.updatedAt = updatedAt;
    }

    public boolean isEmpty() {
        return fiveHour == null && sevenDay == null;
    }

    public static UsageData parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject src = root.optJSONObject("rate_limits");
        if (src == null) src = root;
        long rootCaptured = epochSeconds(root.opt("captured_at"));
        Window five = parseWindow(src.optJSONObject("five_hour"), rootCaptured);
        Window week = parseWindow(src.optJSONObject("seven_day"), rootCaptured);
        long updated = epochSeconds(root.opt("updated_at"));
        if (updated <= 0) {
            updated = Math.max(five != null ? five.capturedAt : 0L, week != null ? week.capturedAt : 0L);
        }
        return new UsageData(five, week, updated);
    }

    static Window parseWindow(JSONObject o, long fallbackCaptured) {
        if (o == null || !o.has("used_percentage") || o.isNull("used_percentage")) return null;
        double used = o.optDouble("used_percentage", Double.NaN);
        long resets = epochSeconds(o.opt("resets_at"));
        if (Double.isNaN(used) || Double.isInfinite(used) || resets <= 0) return null;
        long captured = epochSeconds(o.opt("captured_at"));
        if (captured <= 0) captured = fallbackCaptured;
        used = Math.max(0.0, Math.min(100.0, used));
        return new Window(used, resets, captured);
    }

    /** Accepts epoch seconds, epoch milliseconds, numeric strings or ISO-8601 strings. Returns 0 when unusable. */
    static long epochSeconds(Object v) {
        if (v == null || v == JSONObject.NULL) return 0L;
        double d;
        if (v instanceof Number) {
            d = ((Number) v).doubleValue();
        } else {
            String s = v.toString().trim();
            if (s.isEmpty()) return 0L;
            try {
                d = Double.parseDouble(s);
            } catch (NumberFormatException e) {
                try {
                    return OffsetDateTime.parse(s).toEpochSecond();
                } catch (RuntimeException e2) {
                    return 0L;
                }
            }
        }
        if (Double.isNaN(d) || Double.isInfinite(d) || d <= 0) return 0L;
        if (d > 1e12) d = d / 1000.0;
        return (long) d;
    }
}
