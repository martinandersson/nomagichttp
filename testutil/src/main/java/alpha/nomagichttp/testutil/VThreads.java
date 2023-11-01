package alpha.nomagichttp.testutil;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static java.time.Instant.now;
import static java.util.concurrent.StructuredTaskScope.Subtask.State.FAILED;

/**
 * Getting a result by using a virtual thread.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class VThreads {
    private VThreads() {
        // Empty
    }
    
    // TODO: DRY (callUsingVThread and getUsingVThread)
    
    /**
     * Gets a result from the specified callable, using a forked virtual
     * thread.<p>
     * 
     * This method will wait at most 1 second for the result.
     * 
     * @param <R> type of result
     * @param task to execute
     * 
     * @return the result from the specified callable
     * 
     * @throws NullPointerException
     *             if {@code task} is {@code null}
     * @throws InterruptedException
     *             if the calling thread is interrupted while waiting
     * @throws TimeoutException
     *             if the {@code task} takes longer than 1 second
     * @throws ExecutionException
     *             with a cause from the given {@code task}
     */
    public static <R> R callUsingVThread(Callable<? extends R> task)
            throws InterruptedException, TimeoutException, ExecutionException {
        try (var scope = new StructuredTaskScope<>()) {
            Subtask<R> st = scope.fork(task);
            scope.joinUntil(now().plusSeconds(1));
            if (st.state() == FAILED) {
                var thr = st.exception();
                assert thr instanceof Exception;
                throw new ExecutionException(thr);
            }
            return st.get();
        }
    }
    
    /**
     * Get a result from the given supplier, using a forked virtual thread.<p>
     * 
     * This method will wait at most 1 second for the result.
     * 
     * @param <R> type of result
     * @param task to execute
     * 
     * @return the result from the specified callable
     * 
     * @throws NullPointerException
     *             if {@code task} is {@code null}
     * @throws InterruptedException
     *             if the calling thread is interrupted while waiting
     * @throws TimeoutException
     *             if the {@code task} takes longer than 1 second
     */
    public static <R> R getUsingVThread(Supplier<? extends R> task)
            throws InterruptedException, TimeoutException {
        try (var scope = new StructuredTaskScope<>()) {
            Subtask<R> st = scope.fork(task::get);
            scope.joinUntil(now().plusSeconds(1));
            if (st.state() == FAILED) {
                var thr = st.exception();
                assert thr instanceof RuntimeException;
                throw (RuntimeException) thr;
            }
            return st.get();
        }
    }
}