package alpha.nomagichttp.core;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Schedule a task to execute after a configured delay.<p>
 * 
 * Two flavors are provided.<p>
 * 
 * One flavor is the static method {@link #schedule(long, Runnable)}, which
 * returns a {@link ScheduledFuture}, which doesn't have the most useful API on
 * earth, and the "cancel" operation is vaguely defined. Still, this is suitable
 * to schedule a one-shot task<p>
 * 
 * The second flavor is creating an instance of this class (encapsulates the
 * delay and task), which offers methods to explicitly {@link #schedule()} and
 * {@link #tryAbort()} the task.<p>
 * 
 * This class is thread-safe and offers strong guarantees using an exclusive,
 * blocking write-lock shared by the background thread executing the task and
 * whichever thread calls {@code schedule} and {@code abort}. That is to say,
 * if the task is executing, the API in this class blocks until the task
 * completes, and the other way around.<p>
 * 
 * This class does not implement {@code hashCode} nor {@code equals}.
 * 
 * @apiNote
 * This class should be used for all background tasks in the default server and
 * uses one background daemon thread to do the work.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DelayedTask
{
    /**
     * Schedule an action to run after the given number of nanoseconds.
     * 
     * @param nanos time to delay
     * @param task to execute
     * 
     * @return the scheduled task
     * 
     * @throws NullPointerException if {@code action} is {@code null}
     */
    public static ScheduledFuture<?> schedule(long nanos, Runnable task) {
        return SCHEDULER.schedule(task, nanos, NANOSECONDS);
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
    
    private static final Object
            NEVER_SCHEDULED = null,
            ABORTED = new Object(),
            EXECUTED = new Object();
    
    private final Lock lock;
    private final long nanos;
    private final Runnable action;
    private Object task;
    private int nextTaskId;
    
    /**
     * Constructs this object.
     * 
     * @param delay value
     * @param task to execute
     * 
     * @throws NullPointerException if any argument is {@code null}
     */
    DelayedTask(Duration delay, Runnable task) {
        this.lock   = new ReentrantLock();
        this.nanos  = delay.toNanos();
        this.action = requireNonNull(task);
        this.task   = NEVER_SCHEDULED;
    }
    
    /**
     * Schedules the task.<p>
     * 
     * This method is equivalent to:<p>
     * 
     * {@snippet :
     *   // @link substring="schedule" target="#schedule(Runnable)" :
     *   schedule(null);
     * }
     * 
     * @throws IllegalStateException if a task is already scheduled
     */
    void schedule() {
        schedule(null);
    }
    
    /**
     * Schedules the task.<p>
     * 
     * It is permissible to [re-]schedule a previously executed task or a task
     * that was aborted.<p>
     * 
     * The callback is designed for passing state from the thread scheduling the
     * task to the thread executing the task.
     * 
     * @param onSuccess
     *          optional callback invoked if the operation is successful
     *          (may be {@code null})
     * 
     * @throws IllegalStateException
     *           if a task is already scheduled
     */
    void schedule(Runnable onSuccess) {
        withLock(() -> {
            if (task == NEVER_SCHEDULED || task == ABORTED || task == EXECUTED) {
                int taskId = nextTaskId++;
                task = schedule(nanos, () -> runAction(taskId));
                if (onSuccess != null) {
                    onSuccess.run();
                }
            } else {
                throw new IllegalStateException();
            }
        });
    }
    
    private void runAction(int taskId) {
        withLock(() -> {
            assert task != NEVER_SCHEDULED :
                "Obviously, the task was scheduled";
            if (task == ABORTED || taskId != (nextTaskId - 1)) {
                // Aborted or not this task
                return;
            }
            assert task != EXECUTED :
                "It's doubtful the same task would ever execute twice!";
            try {
                action.run();
            } finally {
                task = EXECUTED;
            }
        });
    }
    
    /**
     * Tries to abort the task.<p>
     * 
     * Invoking this method repeatedly (without calling {@code schedule()}
     * in-between) yields the same return value.
     * 
     * @return {@code false} if the task already executed, otherwise {@code true}
     */
    boolean tryAbort() {
        return withLockGet(() -> {
            if (task == EXECUTED) {
                return false;
            }
            if (task == NEVER_SCHEDULED || task == ABORTED) {
                return true;
            }
            // A real task is referenced
            try {
                ((ScheduledFuture<?>) task).cancel(false);
            } finally {
                task = ABORTED;
            }
            return true;
        });
    }
    
    private void withLock(Runnable impl) {
        lock.lock();
        try {
            impl.run();
        } finally {
            lock.unlock();
        }
    }
    
    private boolean withLockGet(BooleanSupplier impl) {
        lock.lock();
        try {
            return impl.getAsBoolean();
        } finally {
            lock.unlock();
        }
    }
}
