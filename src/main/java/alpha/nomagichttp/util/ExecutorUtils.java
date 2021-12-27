package alpha.nomagichttp.util;

import java.util.function.Consumer;

/**
 * Util methods for executing code.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class ExecutorUtils
{
    /**
     * Execute the given runnable.<p>
     * 
     * If the runnable throws an exception, the new exception is marked as
     * suppressed by the given one, then this class returns normally.
     * 
     * <pre>
     *   try {
     *       doingSomeNormalStuff();
     *       // ...
     *   } catch (Throwable t) {
     *       ExecutorUtils.runSafe(clientOnErrorCallback, t);
     *       // {@code t} may now have suppressed one more
     *       throw t;
     *   }
     * </pre>
     * 
     * @param r to execute
     * @param t throwable with higher priority
     * @throws NullPointerException if {@code r} is {@code null}
     */
    public static void runSafe(Runnable r, Throwable t) {
        assert t != null;
        try {
            r.run();
        } catch (Throwable u) {
            t.addSuppressed(u);
        }
    }
    
    /**
     * Execute the given consumer.<p>
     * 
     * If the consumer throws an exception, the new exception is marked as
     * suppressed by the given one, then this class returns normally.
     * 
     * <pre>
     *   try {
     *       doingSomeNormalStuff();
     *       // ...
     *   } catch (Throwable t) {
     *       ExecutorUtils.acceptSafe(clientOnErrorCallback, forwardArg, t);
     *       // {@code t} may now have suppressed one more
     *       throw t;
     *   }
     * </pre>
     * 
     * @param <T> type of consumer argument
     * @param c to execute
     * @param arg forwarded to consumer
     * @param t throwable with higher priority
     * @throws NullPointerException if {@code c} is {@code null}
     */
    public static <T> void acceptSafe(Consumer<? super T> c, T arg, Throwable t) {
        assert t != null;
        try {
            c.accept(arg);
        } catch (Throwable u) {
            t.addSuppressed(u);
        }
    }
}