package com.ynozue.limitgauge;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;

/** Runs a fetch for both the periodic job and one-off "refresh soon" jobs. */
public class RefreshJobService extends JobService {

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Refresher.refresh(app);
                } finally {
                    jobFinished(params, false);
                }
            }
        }, "limit-gauge-refresh").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // The periodic job comes back on its own; nothing to retry.
        return false;
    }
}
