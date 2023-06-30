package alpha.nomagichttp.testutil;

import jdk.incubator.concurrent.StructuredTaskScope;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import static java.time.Instant.now;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Getting a result by using a virtual thread.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class VThreads {
    private VThreads() {
        // Empty
    }
    
    /**
     * Get a result from the given callable, using a forked virtual thread.<p>
     * 
     * This method will wait at most 1 second for the result.
     * 
     * @param <R> type of result
     * @param task to execute
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if {@code task} is {@code null}
     * @throws InterruptedException
     *             if the calling thread is interrupted while waiting
     * @throws ExecutionException
     *             if the {@code task} throws an exception
     * @throws TimeoutException
     *             if the {@code task} takes longer than 1 second
     */
    public static <R> R getUsingVThread(Callable<? extends R> task)
            throws InterruptedException, ExecutionException, TimeoutException {
        try (var scope = new StructuredTaskScope<>()) {
            Future<R> fut = scope.fork(task);
            scope.joinUntil(now().plusSeconds(1));
            // Future.resultNow() throws IllegalStateException, without a cause.
            // For tests, it's kind of important to assert the cause 👍
            return fut.get(0, SECONDS);
        }
    }
}