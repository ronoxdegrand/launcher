package dev.glgra.launcher;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** The OS owns the daily wakeup; failed downloads do not create a retry loop. */
public final class UpdateJobService extends JobService {
    private ExecutorService worker;
    private Future<?> work;
    private UpdateRepository repository;
    private volatile boolean stopped;
    private volatile int generation;

    @Override public boolean onStartJob(JobParameters parameters) {
        stopped = false;
        repository = new UpdateRepository(this);
        worker = Executors.newSingleThreadExecutor();
        final UpdateRepository activeRepository = repository;
        final ExecutorService activeWorker = worker;
        final int activeGeneration = ++generation;
        work = activeWorker.submit(() -> {
            try { activeRepository.checkBlocking(null); }
            finally {
                activeRepository.close(); activeWorker.shutdown();
                if (!stopped && activeGeneration == generation) jobFinished(parameters, false);
            }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        stopped = true;
        if (repository != null) repository.close();
        if (work != null) work.cancel(true);
        if (worker != null) worker.shutdownNow();
        return false;
    }

    @Override public void onDestroy() {
        stopped = true;
        if (repository != null) repository.close();
        if (worker != null) worker.shutdownNow();
        super.onDestroy();
    }
}
