package alpha.nomagichttp.internal;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

import static alpha.nomagichttp.internal.AtomicReferences.lazyInitOrElse;
import static alpha.nomagichttp.internal.AtomicReferences.take;
import static alpha.nomagichttp.internal.AtomicReferences.takeIfSame;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Schedule a command to execute after a configured timeout.<p>
 * 
 * This class is fully thread-safe and non-blocking.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class Timeout
{
    /**
     * Schedule an action to run after the given amount of nanoseconds.
     * 
     * @param nanos time to delay
     * @param action to run
     * 
     * @return the scheduled task
     * 
     * @throws NullPointerException if {@code action} is {@code null}
     */
    public static ScheduledFuture<?> schedule(long nanos, Runnable action) {
        return SCHEDULER.schedule(action, nanos, NANOSECONDS);
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
    
    private final long nanos;
    private final AtomicReference<
            CompletableFuture<
                    ScheduledFuture<?>>> task;
    
    Timeout(Duration timeout) {
        this.nanos = timeout.toNanos();
        this.task  = new AtomicReference<>();
    }
    
    
    /**
     * Run the given action on timeout.<p>
     * 
     * A scheduled task will be created and stored in this class. It can be
     * aborted using {@link #abort()}.
     * 
     * @param action to run
     * 
     * @throws NullPointerException
     *             if {@code action} is {@code null}
     * 
     * @throws IllegalStateException
     *             if a task has already been scheduled
     */
    void schedule(Runnable action) {
        requireNonNull(action);
        Object set = lazyInitOrElse(task, CompletableFuture::new, f -> {
            Runnable conditionally = () -> {
                if (takeIfSame(task, f).isEmpty()) {
                    // Not our box, abort
                    return;
                }
                ScheduledFuture<?> scheduled;
                try {
                    scheduled = f.get();
                } catch (InterruptedException | ExecutionException e) {
                    throw new AssertionError(e);
                }
                if (scheduled.isCancelled()) {
                    return;
                }
                action.run();
            };
            f.complete(schedule(nanos, conditionally));
        }, null);
        if (set == null) {
            throw new IllegalStateException();
        }
    }
    
    /**
     * Abort the current task.<p>
     * 
     * No guarantee is provided that the task is aborted.<p>
     * 
     * This method is NOP if no task has been scheduled.
     * 
     * @see ScheduledFuture#cancel(boolean) 
     */
    void abort() {
        take(task).ifPresent(cf -> cf.thenAccept(sf -> sf.cancel(false)));
    }
    
    /**
     * Semantically the same as calling {@link #abort()} followed by
     * {@link #schedule(Runnable)}, difference being this method does not
     * throw {@code IllegalStateException}.
     * 
     * @param action to run
     * 
     * @throws NullPointerException if {@code action} is {@code null}
     */
    void reschedule(Runnable action) {
        requireNonNull(action);
        for (;;) {
            abort();
            try {
                schedule(action);
                return;
            } catch (IllegalStateException ignored) {
                // Go again
            }
        }
    }
}