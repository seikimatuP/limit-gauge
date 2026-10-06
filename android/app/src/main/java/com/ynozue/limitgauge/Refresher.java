package com.ynozue.limitgauge;

import android.content.Context;

/** Fetch → store → redraw everything that shows the data. */
final class Refresher {
    private static final Object LOCK = new Object();

    private Refresher() {}

    /**
     * Blocking; call from a background thread. Fetches from the relay (when configured),
     * stores the result and redraws widgets and the notification.
     *
     * @return null on success (or when not configured), otherwise a user-facing error message
     */
    static String refresh(Context context) {
        Context c = context.getApplicationContext();
        synchronized (LOCK) {
            String error = null;
            if (Prefs.configured(c)) {
                try {
                    String body = Fetcher.get(SetupLink.endpointFor(Prefs.url(c)), Prefs.token(c));
                    UsageData.parse(body); // validate before replacing what we have
                    Prefs.get(c).edit()
                            .putString(Prefs.DATA, body)
                            .putLong(Prefs.FETCHED_AT, System.currentTimeMillis())
                            .remove(Prefs.ERROR)
                            .remove(Prefs.ERROR_AT)
                            .commit();
                } catch (Exception e) {
                    error = Fetcher.describe(c, e);
                    Prefs.get(c).edit()
                            .putString(Prefs.ERROR, error)
                            .putLong(Prefs.ERROR_AT, System.currentTimeMillis())
                            .commit();
                }
            }
            renderAll(c);
            return error;
        }
    }

    /** Redraws from stored data only (no network). Safe on the main thread. */
    static void renderAll(Context context) {
        Context c = context.getApplicationContext();
        WidgetRenderer.updateAll(c);
        Notifier.update(c);
        Scheduler.scheduleResetAlarm(c, Prefs.data(c));
    }
}
