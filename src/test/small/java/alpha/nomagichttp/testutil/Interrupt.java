package alpha.nomagichttp.testutil;

import alpha.nomagichttp.util.Throwing;

import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static java.lang.System.Logger.Level.INFO;

/**
 * Interrupt current thread after a given amount of time.<p>
 * 
 * If the specified {@code duration} is zero or negative, the calling thread
 * will be interrupted immediately and enter the action in an interrupted state.
 * Otherwise, the interrupt is done by a background thread.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Interrupt
{
    private Interrupt() {
        // Intentionally empty
    }
    
    private static final System.Logger LOG
            = System.getLogger(Interrupt.class.getPackageName());
    
    /**
     * Executes the given action and interrupts the current thread after a
     * specified amount of time, unless the action completes sooner.<p>
     * 
     * If and when the thread is interrupted, many ongoing channel operations
     * will return exceptionally with {@link ClosedByInterruptException}.
     * 
     * @param duration duration of timeout
     * @param unit unit of duration
     * @param name operation name (for logging)
     * @param action to execute
     * @param <X> action exception type
     * 
     * @throws X as defined by action
     */
    public static <X extends Exception> void after(
            long duration, TimeUnit unit, String name,
            Throwing.Runnable<X> action)
            throws X {
        after(duration, unit, name, () -> {
            action.run();
            return null;
        });
    }
    
    /**
     * Retrieves the result of the given action and interrupts the current
     * thread after a specified amount of time, unless the action completes
     * sooner.<p>
     * 
     * If and when the thread is interrupted, many ongoing channel operations
     * will return exceptionally with {@link ClosedByInterruptException}.
     * 
     * @param duration duration of timeout
     * @param unit unit of duration
     * @param name operation name (for logging)
     * @param action to execute
     * @param <V> action result type
     * @param <X> action exception type
     * 
     * @return action result
     * 
     * @throws X as defined by action
     */
    public static <V, X extends Exception> V after(
            long duration, TimeUnit unit, String name,
            Throwing.Supplier<V, X> action)
            throws X {
        final Thread worker = Thread.currentThread();
        
        if (duration <= 0) {
            worker.interrupt();
            return action.get();
        }
        
        final boolean[] timer = {true};
        ScheduledFuture<?> task = SCHEDULER.schedule(() -> {
            synchronized (timer) {
                if (timer[0]) {
                    LOG.log(INFO, () ->
                            "Interrupting operation \"" + name + "\".");
                    worker.interrupt();
                    timer[0] = false;
                }
            }
        }, duration, unit);
        try {
            V v = action.get();
            task.cancel(false);
            synchronized (timer) {
                if (timer[0]) {
                    timer[0] = false;
                } else {
                    // Close call, but we got a result, clear flag
                    Thread.interrupted();
                }
            }
            return v;
        } catch (Throwable t) {
            task.cancel(false);
            synchronized (timer) {
                timer[0] = false;
            }
            // If JUnit complains about test worker interrupt status, clear flag
            // Thread.interrupted();
            throw t;
        }
    }
    
    private static final ScheduledExecutorService SCHEDULER
            = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r);
                    t.setName("Test interrupter");
                    t.setDaemon(true);
                    return t; });
}