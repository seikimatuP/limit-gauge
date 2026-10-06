package com.ynozue.limitgauge;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/** Shared behaviour for all widgets: draw from stored data at once, then fetch in the background. */
public abstract class BaseWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        WidgetRenderer.updateAll(context);
        Scheduler.ensurePeriodic(context);
        Scheduler.refreshSoon(context);
    }

    @Override
    public void onEnabled(Context context) {
        Scheduler.ensurePeriodic(context);
    }

    @Override
    public void onDisabled(Context context) {
        Scheduler.stopIfUnused(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int appWidgetId,
                                          Bundle newOptions) {
        WidgetRenderer.updateAll(context);
    }
}
