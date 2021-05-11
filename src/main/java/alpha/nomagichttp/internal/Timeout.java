package alpha.nomagichttp.internal;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

final class Timeout
{
    private final long nanos;
    private ScheduledFuture<?> task;
    
    Timeout(Duration timeout) {
        this.nanos = timeout.toNanos();
        this.task  = null;
    }
    
    private static final ScheduledThreadPoolExecutor SCHEDULER;
    static {
        (SCHEDULER = new ScheduledThreadPoolExecutor(1,
                new DaemonThreadFactory())).
                setRemoveOnCancelPolicy(true);
    }
    
    private static final class DaemonThreadFactory implements ThreadFactory {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("NoMagicTimeout");
            return t;
        }
    }
    
    void schedule(Runnable action) {
        task = SCHEDULER.schedule(action, nanos, NANOSECONDS);
    }
    
    void abort() {
        if (task != null) {
            task.cancel(false);
        }
    }
}