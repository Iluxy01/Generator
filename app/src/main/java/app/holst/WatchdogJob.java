package app.holst;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Если система всё-таки выгрузила службу, это задание поднимает её снова. */
public final class WatchdogJob extends JobService {
    private static final int ID = 7;

    static void schedule(Context c) {
        JobInfo job = new JobInfo.Builder(ID, new ComponentName(c, WatchdogJob.class))
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build();
        c.getSystemService(JobScheduler.class).schedule(job);
    }

    static void cancel(Context c) {
        c.getSystemService(JobScheduler.class).cancel(ID);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (App.get().prefs.enabled()) CanvasService.start(this);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
