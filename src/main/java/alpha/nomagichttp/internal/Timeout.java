package alpha.nomagichttp.internal;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Schedule a command to execute after a configured timeout.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class Timeout
{
    private final long nanos;
    private volatile ScheduledFuture<?> task;
    
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
    
    /**
     * Run the given action on timeout.<p>
     * 
     * A scheduled task will be created and stored in this class using volatile
     * set. It can be aborted using {@link #abort()}.
     * 
     * @param action to run
     * 
     * @throws NullPointerException
     *             if {@code action} is {@code null}
     * 
     * @throws IllegalStateException
     *             if an observable task has already been scheduled
     */
    void schedule(Runnable action) {
        var t = task;
        if (t != null) {
            throw new IllegalStateException();
        }
        task = SCHEDULER.schedule(action, nanos, NANOSECONDS);
    }
    
    /**
     * Abort the current task.<p>
     * 
     * No guarantee is provided that the task is aborted.<p>
     * 
     * Whichever task that is observed will attempt to abort. There is no lock
     * between {@code schedule()} and {@code abort()}. However, they write+read
     * using volatile semantics.<p>
     * 
     * This method is NOP if no task has been scheduled.
     * 
     * @see ScheduledFuture#cancel(boolean) 
     */
    void abort() {
        var t = task;
        if (t != null) {
            t.cancel(false);
            task = null;
        }
    }
    
    /**
     * Same as calling {@link #abort()} followed by {@link #schedule(Runnable)},
     * except optimized to use one pair of volatile read+write instead of what
     * otherwise would have been two.
     * 
     * @param action to run
     * 
     * @throws NullPointerException
     *             if {@code action} is {@code null} (after potential abort)
     */
    void reschedule(Runnable action) {
        var t = task;
        if (t != null) {
            t.cancel(false);
        }
        task = SCHEDULER.schedule(action, nanos, NANOSECONDS);
    }
}