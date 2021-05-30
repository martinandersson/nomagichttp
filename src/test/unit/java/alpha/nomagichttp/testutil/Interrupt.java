package alpha.nomagichttp.testutil;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Interrupt current thread after a given amount of time.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class Interrupt
{
    /**
     * Executes the given action and interrupts the current thread after a
     * specified amount of time, unless the action completes sooner.
     * 
     * @param duration duration of timeout
     * @param unit unit of duration
     * @param action to execute
     * 
     * @throws IOException if an I/O error occurs
     *                     (this includes {@link ClosedByInterruptException}!)
     */
    public static void after(long duration, TimeUnit unit, IORunnable action) throws IOException {
        after(duration, unit, () -> {
            action.run();
            return null;
        });
    }
    
    /**
     * Retrieves the result of the given action and interrupts the current
     * thread after a specified amount of time, unless the action completes
     * sooner.
     * 
     * @param duration duration of timeout
     * @param unit unit of duration
     * @param action to execute
     * @param <V> action result type
     * 
     * @return action result
     * 
     * @throws IOException if an I/O error occurs
     *                     (this includes {@link ClosedByInterruptException}!)
     */
    public static <V> V after(long duration, TimeUnit unit, IOSupplier<V> action) throws IOException {
        final Thread worker = Thread.currentThread();
        final boolean[] timer = {true};
        ScheduledFuture<?> task = SCHEDULER.schedule(() -> {
            synchronized (timer) {
                if (timer[0]) {
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
    
    private static final System.Logger LOG = System.getLogger(Interrupt.class.getPackageName());
    
    private static final ScheduledExecutorService SCHEDULER
            = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r);
                    t.setDaemon(true);
                    return t; });
}